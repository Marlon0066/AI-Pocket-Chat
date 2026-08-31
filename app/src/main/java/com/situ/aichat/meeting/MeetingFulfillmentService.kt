package com.situ.aichat.meeting

import android.util.Log
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.offline.OfflineMarkerStartPayload
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 约定「兑现判定」单源（图纸 `docs/handoff/2026-08-31-约定爽约误判与幽灵约定修复.md`）。
 *
 * 背景：核销（confirmed→honored）此前只接在「出发赴约」按钮 / 到点通知两条入口；手动发起见面、
 * 接受邀约卡进的**同一场约**不被承认，过宽限即被爽约扫描判 missed + 插「你没来」旁白（真机实报）。
 * 另有「幽灵约定」：见面结束后识别簇重扫无时间戳的旧消息（「明天买裙子」被当今天说的）+ 已赴约约定
 * 从查重/识别清单双双消失（isActive 不含 honored）→ 同一件事被重复立约，过期同样被冤成爽约。
 *
 * 本服务以**入场标记消息**（[MessageKind.OFFLINE_MARKER_START]·见面必产、字节稳定）为实证，三件事：
 * 1. [findFulfillingMeeting]：某约定是否已被一场真实见面兑现（爽约扫描判 missed 前的闸）。
 * 2. [honorDueAppointmentsOnMeetingStart]：任意入口进见面时，顺手核销本会话到点窗口内的已确认约定
 *    （并撤到点通知——不然见面中/后还会弹「该赴约啦」）。
 * 3. [repairMissedAppointments]：存量自愈——历史被误判的 missed 翻回 honored + 删「你没来」旁白
 *    （受害用户经 GitHub 分发无法触达设备，修复必须随 App 自愈）。幂等，挂在爽约扫描入口随前台跑。
 *
 * **偏向拍板（图纸 §4）**：宁可漏怪、绝不冤枉——tier2 幽灵匹配会放过「见面后 48h 内重约同活动又爽约」
 * 的极端组合，有意接受；爽约本就是负面体验，误伤代价远高于漏报。
 * 日志纪律：只打 uuid / sessionId / 计数，绝不打地点、活动等内容（REDLINES §3）。
 */
@Singleton
class MeetingFulfillmentService @Inject constructor(
    private val store: MeetingAppointmentStore,
    private val messageRepo: MessageRepository,
    private val meetupNotificationService: MeetupNotificationService,
) {

    /** 兑现这条约定的那场见面：链接用 sessionId + 入场时刻。 */
    data class FulfillingMeeting(val sessionId: String, val startMillis: Long)

    /**
     * 某约定是否已被本会话的一场真实见面兑现：扫会话全部入场标记，按 [matchesAppointment]
     * （tier1 时窗 / tier2 幽灵）匹配，多场命中取**最晚**一场（离约定最近的那次）。无 → null。
     */
    suspend fun findFulfillingMeeting(
        appt: MeetingAppointmentEntity,
        zone: ZoneId = ZoneId.systemDefault(),
    ): FulfillingMeeting? =
        messageRepo.messagesByKind(appt.conversationUuid, MessageKind.OFFLINE_MARKER_START.raw)
            .filter { marker ->
                val activity = OfflineMarkerStartPayload.parse(marker.content)?.activity.orEmpty()
                matchesAppointment(appt, marker.timestamp, activity, zone)
            }
            .maxByOrNull { it.timestamp }
            ?.let { FulfillingMeeting(it.offlineSessionId.orEmpty(), it.timestamp) }

    /**
     * 入口核销：进见面成功后（手动发起 / 邀约卡 / 赴约任意入口），把本会话「到点窗口内」
     * （[isDueNow]·含 3h 提前量）的已确认约定全部核销到本场 [sessionId]，并全量重排到点通知
     * （撤掉已核销约定的闹钟）。无到期约定 → 零写零重排。守卫拒绝（并发已流转）静默跳过。
     */
    suspend fun honorDueAppointmentsOnMeetingStart(
        conversationUuid: String,
        sessionId: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val due = store.activeForConversation(conversationUuid).filter { isDueNow(it, nowMillis, zone) }
        if (due.isEmpty()) return
        var honored = 0
        for (appt in due) {
            if (store.markHonored(appt.uuid, sessionId, nowMillis) != null) honored++
        }
        if (honored > 0) {
            Log.i(TAG, "进见面核销到期约定 $honored 条 session=$sessionId")
            meetupNotificationService.rescheduleAll()
        }
    }

    /**
     * 存量自愈（幂等·翻案后不再命中）：全部 missed 约定逐条找兑现见面，命中 → **先删「你没来」旁白、
     * 后翻 honored**（图纸 §4 锁定顺序：中途进程死 → 行仍 missed，下轮重入收敛；反序会留毒——
     * 旁白删不成而状态已翻，honored 不再被扫）。旁白定位见 [deleteMissedHints]。
     */
    suspend fun repairMissedAppointments(
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        for (appt in store.allMissed()) {
            val meeting = findFulfillingMeeting(appt, zone) ?: continue
            deleteMissedHints(appt)
            store.repairMissedToHonored(appt.uuid, meeting.sessionId, nowMillis)
            Log.i(TAG, "爽约误判自愈：${appt.uuid} → honored session=${meeting.sessionId}")
        }
    }

    /**
     * 删这条约定的「你没来」隐藏旁白：kind=SYSTEM_HINT + 含 [MeetingMissedReactionService.MISSED_HINT_SIGNATURE]
     * + 与 outcomeAt 相差 ≤[HINT_MATCH_TOLERANCE_MS]（旁白与 markMissed 同事务同 now → 正常恰相等，
     * 容差只防时钟毛刺）。outcomeAt 为空（异常数据）→ 不删，绝不按内容盲删别的旁白。
     */
    private suspend fun deleteMissedHints(appt: MeetingAppointmentEntity) {
        val outcomeAt = appt.outcomeAt ?: return
        messageRepo.messagesByKind(appt.conversationUuid, MessageKind.SYSTEM_HINT.raw)
            .filter {
                it.content.contains(MeetingMissedReactionService.MISSED_HINT_SIGNATURE) &&
                    abs(it.timestamp - outcomeAt) <= HINT_MATCH_TOLERANCE_MS
            }
            .forEach { messageRepo.deleteByUuid(it.messageUUID) }
    }

    companion object {
        private const val TAG = "MeetingFulfillment"

        /** tier2 幽灵匹配的回看窗：约定创建前多久内的见面算「它复述的那场」。 */
        internal const val GHOST_LOOKBACK_MS = 48L * 3600 * 1000

        /** 旁白时间戳与 outcomeAt 的匹配容差。 */
        internal const val HINT_MATCH_TOLERANCE_MS = 5L * 60 * 1000

        /**
         * 兑现时窗**起点**：EXACT = scheduledAt − 3h（提前到也算·镜像 [MeetingArrivalPolicy.EXACT_GRACE_HOURS]）；
         * DAY_ONLY / VAGUE = 约定那天当地 0 点（哪个钟点见都算那天的约）。终点统一
         * [MeetingArrivalPolicy.missedDeadlineMillis]（复用不重写）。
         */
        internal fun fulfillmentWindowStartMillis(
            scheduledAtMillis: Long,
            granularity: MeetingTimeGranularity,
            zone: ZoneId,
        ): Long = when (granularity) {
            MeetingTimeGranularity.EXACT ->
                scheduledAtMillis - MeetingArrivalPolicy.EXACT_GRACE_HOURS * 60 * 60 * 1000
            MeetingTimeGranularity.DAY_ONLY, MeetingTimeGranularity.VAGUE ->
                Instant.ofEpochMilli(scheduledAtMillis).atZone(zone).toLocalDate()
                    .atStartOfDay(zone).toInstant().toEpochMilli()
        }

        /**
         * 一场见面（入场时刻 [meetingStartMillis] + 标记里的活动 [meetingActivity]）是否兑现了 [appt]：
         * - **tier1 时窗匹配（无活动要求）**：入场 ∈ [兑现窗起点, 爽约截止]——约定时间前后见的面就是赴约，
         *   活动写什么不较真（按钮路核销同样不比对活动）。
         * - **tier2 幽灵匹配（带活动要求）**：入场 ∈ [createdAt − 48h, createdAt] 且活动相近
         *   （[MeetingAppointmentStore.activitySimilar]·空活动按其既有语义算相近）——专捕「见面**之后**才被
         *   识别出来的旧事重提」：约定生在见面后，时窗必对不上，靠“先见面、后立约、说的是同一件事”定罪。
         */
        internal fun matchesAppointment(
            appt: MeetingAppointmentEntity,
            meetingStartMillis: Long,
            meetingActivity: String,
            zone: ZoneId,
        ): Boolean {
            val granularity = MeetingTimeGranularity.fromRaw(appt.timeGranularity)
            val deadline = MeetingArrivalPolicy.missedDeadlineMillis(appt.scheduledAt, granularity, zone)
            val windowStart = fulfillmentWindowStartMillis(appt.scheduledAt, granularity, zone)
            val tier1 = meetingStartMillis in windowStart..deadline
            val tier2 = meetingStartMillis in (appt.createdAt - GHOST_LOOKBACK_MS)..appt.createdAt &&
                MeetingAppointmentStore.activitySimilar(appt.activity, meetingActivity)
            return tier1 || tier2
        }

        /** 入口核销的到期判定：已确认 且 此刻落在兑现时窗内（含 3h 提前量·终点=爽约截止）。 */
        internal fun isDueNow(appt: MeetingAppointmentEntity, nowMillis: Long, zone: ZoneId): Boolean {
            if (MeetingStatus.fromRaw(appt.status) != MeetingStatus.CONFIRMED) return false
            val granularity = MeetingTimeGranularity.fromRaw(appt.timeGranularity)
            val windowStart = fulfillmentWindowStartMillis(appt.scheduledAt, granularity, zone)
            val deadline = MeetingArrivalPolicy.missedDeadlineMillis(appt.scheduledAt, granularity, zone)
            return nowMillis in windowStart..deadline
        }
    }
}
