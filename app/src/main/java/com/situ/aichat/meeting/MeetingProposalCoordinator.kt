package com.situ.aichat.meeting

import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.FutureMeetingChangeData
import com.situ.aichat.data.model.FutureMeetingChangeJson
import com.situ.aichat.data.model.FutureMeetingProposalData
import com.situ.aichat.data.model.FutureMeetingProposalJson
import com.situ.aichat.data.model.MeetingCandidate
import com.situ.aichat.data.model.MeetingCandidateIntent
import com.situ.aichat.data.model.MeetingProposedBy
import com.situ.aichat.data.model.MeetingSource
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.MessageRepository
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 候选入库协调器（1:1 iOS `ChatViewModel+MeetingAppointment` 的 ingest 管线）。识别四路（扫描 / 工具 / 文本暗号 / 手动）
 * 都汇到这里：解析时间 → 按 intent 分派 → **确认闸门**（new 才落 proposed + 插确认卡，误判由用户在卡上拦）。
 *
 * 职责边界（保持纯逻辑、可 MockK 测）：只动真理源 [MeetingAppointmentStore] + 插确认卡消息 [MessageRepository]。
 * **不碰**会话预览刷新 / 滚动 / 到点通知排程——这些副作用由调用方（Phase 8 接线的 ChatViewModel / Phase 10 通知）
 * 在拿到返回的约定后处理（约定状态变了就刷该约定的到点通知）。
 */
@Singleton
class MeetingProposalCoordinator @Inject constructor(
    private val store: MeetingAppointmentStore,
    private val messageRepo: MessageRepository,
) {

    /**
     * 候选入库（四路汇入）。返回受影响约定（调用方据此刷到点通知 / 会话预览）；
     * none / 查重命中 / 找不到目标 → null。
     */
    suspend fun ingestCandidate(
        candidate: MeetingCandidate,
        characterUuid: String,
        conversationUuid: String,
        zone: ZoneId,
        nowMillis: Long = System.currentTimeMillis(),
    ): MeetingAppointmentEntity? = when (candidate.intent) {
        MeetingCandidateIntent.NONE -> null

        // 取消（决策①·2026-06-25）：proposed 仍在确认闸门内 → 直接取消（低危）；confirmed 已排提醒 →
        // **不直接动**，插「变更确认卡」让用户点头（杜绝 AI 误判静默取消已确认约定）；终态不可改。
        MeetingCandidateIntent.CANCEL -> {
            val uuid = candidate.targetAppointmentUuid ?: return null
            val target = store.get(uuid) ?: return null
            when (MeetingStatus.fromRaw(target.status)) {
                MeetingStatus.PROPOSED -> store.cancel(uuid, nowMillis)
                MeetingStatus.CONFIRMED -> {
                    insertChangeCard(target, FutureMeetingChangeData.KIND_CANCEL, null, candidate, conversationUuid, nowMillis, zone)
                    null
                }
                else -> null
            }
        }

        MeetingCandidateIntent.CONFIRM ->
            candidate.targetAppointmentUuid?.let { store.confirm(it, nowMillis) }

        // 改期（决策①同上）：proposed 直接改；confirmed 过变更确认卡（带原时间→拟改新时间）。
        MeetingCandidateIntent.RESCHEDULE -> {
            val uuid = candidate.targetAppointmentUuid ?: return null
            val target = store.get(uuid) ?: return null
            val resolution = resolve(candidate, nowMillis, zone)
            when (MeetingStatus.fromRaw(target.status)) {
                MeetingStatus.PROPOSED -> store.reschedule(uuid, resolution)
                MeetingStatus.CONFIRMED -> {
                    insertChangeCard(target, FutureMeetingChangeData.KIND_RESCHEDULE, resolution, candidate, conversationUuid, nowMillis, zone)
                    null
                }
                else -> null
            }
        }

        MeetingCandidateIntent.NEW -> {
            val resolution = resolve(candidate, nowMillis, zone)
            // 查重：同角色 + 同天 + 活动相近 → 不重复建卡（漏判由下轮扫描补，误判由确认卡拦）。
            // 图纸 2026-08-31 C3：识别路范围**含已赴约**——刚见完的约不许被旧消息重扫成幽灵新约。
            if (store.findDuplicateForCharacterIncludingHonored(characterUuid, resolution.scheduledAtMillis, candidate.activity, zone) != null) {
                null
            } else {
                val appt = store.createProposed(candidate, resolution, characterUuid, conversationUuid, nowMillis)
                insertProposalCard(appt.uuid, candidate, resolution, conversationUuid, nowMillis, zone, responded = null)
                appt
            }
        }
    }

    /**
     * 手动发起（「+」菜单）：用户自填无误判风险，**跳过确认闸门直接 confirmed**，落「已约定」回执卡（无按钮）。
     * 查重命中 → null。granularity 由表单决定（选了具体时间 = exact；只选日期 = dayOnly）。
     */
    suspend fun startManual(
        characterUuid: String,
        conversationUuid: String,
        scheduledAtMillis: Long,
        granularity: MeetingTimeGranularity,
        location: String,
        activity: String,
        zone: ZoneId,
        nowMillis: Long = System.currentTimeMillis(),
    ): MeetingAppointmentEntity? {
        if (store.findDuplicateForCharacter(characterUuid, scheduledAtMillis, activity, zone) != null) return null
        val resolution = MeetingTimeResolver.Resolution(scheduledAtMillis, granularity)
        val appt = store.createConfirmedManually(resolution, characterUuid, conversationUuid, location, activity, nowMillis)
        val candidate = MeetingCandidate(
            intent = MeetingCandidateIntent.NEW,
            proposedBy = MeetingProposedBy.USER,
            source = MeetingSource.MANUAL,
            location = location,
            activity = activity,
        )
        insertProposalCard(appt.uuid, candidate, resolution, conversationUuid, nowMillis, zone, responded = FutureMeetingProposalData.RESPONDED_ACCEPTED)
        return appt
    }

    /** 确认卡「答应」：proposed → confirmed。返回受影响约定（调用方刷到点通知）。 */
    suspend fun confirmFromCard(appointmentUuid: String, nowMillis: Long = System.currentTimeMillis()): MeetingAppointmentEntity? =
        store.confirm(appointmentUuid, nowMillis)

    /** 确认卡「先不约」：取消。 */
    suspend fun declineFromCard(appointmentUuid: String, nowMillis: Long = System.currentTimeMillis()): MeetingAppointmentEntity? =
        store.cancel(appointmentUuid, nowMillis)

    /** 改期到新时间（确认卡「换个时间」/ 管理入口）：更新时间 + 确认（清排程标记供 Phase 10 重排）。 */
    suspend fun rescheduleTo(
        appointmentUuid: String,
        scheduledAtMillis: Long,
        granularity: MeetingTimeGranularity,
        nowMillis: Long = System.currentTimeMillis(),
    ): MeetingAppointmentEntity? {
        store.reschedule(appointmentUuid, MeetingTimeResolver.Resolution(scheduledAtMillis, granularity)) ?: return null
        return store.confirm(appointmentUuid, nowMillis)
    }

    /**
     * 变更确认卡「好，改 / 取消约定」：应用变更到真理源（reschedule → 改期 + 确认；cancel → 取消），并把卡标 applied 回执。
     * 返回受影响约定（调用方刷到点通知）。卡已不在 / 解析失败 / 守卫拒绝 → null。
     */
    suspend fun applyChangeFromCard(messageUuid: String, nowMillis: Long = System.currentTimeMillis()): MeetingAppointmentEntity? {
        val message = messageRepo.get(messageUuid) ?: return null
        val data = FutureMeetingChangeJson.parse(message.content) ?: return null
        val affected = when (data.changeKind) {
            FutureMeetingChangeData.KIND_RESCHEDULE -> {
                val millis = data.newScheduledAtMillis ?: return null
                val gran = MeetingTimeGranularity.fromRaw(data.newGranularity ?: MeetingTimeGranularity.EXACT.raw)
                store.reschedule(data.appointmentUuid, MeetingTimeResolver.Resolution(millis, gran))?.let {
                    store.confirm(data.appointmentUuid, nowMillis)
                }
            }
            FutureMeetingChangeData.KIND_CANCEL -> store.cancel(data.appointmentUuid, nowMillis)
            else -> null
        }
        // 即便守卫拒绝（约定已不可变），也把卡收成回执，避免按钮悬空。
        messageRepo.upsert(message.copy(content = FutureMeetingChangeJson.encode(data.copy(responded = FutureMeetingChangeData.RESPONDED_APPLIED))))
        return affected
    }

    /** 变更确认卡「保留 / 还是原来的」：不动真理源，仅把卡标 kept 回执。 */
    suspend fun keepChangeFromCard(messageUuid: String) {
        val message = messageRepo.get(messageUuid) ?: return
        val data = FutureMeetingChangeJson.parse(message.content) ?: return
        messageRepo.upsert(message.copy(content = FutureMeetingChangeJson.encode(data.copy(responded = FutureMeetingChangeData.RESPONDED_KEPT))))
    }

    // ── 内部 ──

    private fun resolve(candidate: MeetingCandidate, nowMillis: Long, zone: ZoneId): MeetingTimeResolver.Resolution =
        MeetingTimeResolver.resolve(candidate.isoDateTime, candidate.rawWhen, Instant.ofEpochMilli(nowMillis), zone)

    /** 插一条确认卡消息（assistant·结构化卡·content=脱敏 JSON）。responded=null 带按钮；accepted/declined 为回执。 */
    private suspend fun insertProposalCard(
        appointmentUuid: String,
        candidate: MeetingCandidate,
        resolution: MeetingTimeResolver.Resolution,
        conversationUuid: String,
        nowMillis: Long,
        zone: ZoneId,
        responded: String?,
    ) {
        val whenDisplay = MeetingDisplayFormatter.whenDisplay(resolution.scheduledAtMillis, resolution.granularity, zone)
        val data = FutureMeetingProposalData(
            appointmentUuid = appointmentUuid,
            whenDisplay = whenDisplay.ifBlank { null },
            location = candidate.location.ifBlank { null },
            activity = candidate.activity.ifBlank { null },
            invitation = candidate.invitationText.ifBlank { null },
            tensionHint = candidate.tensionHint.ifBlank { null },
            responded = responded,
        )
        messageRepo.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "assistant",
                content = FutureMeetingProposalJson.encode(data),
                timestamp = nowMillis,
                messageKindRaw = MessageKind.FUTURE_MEETING_PROPOSAL_CARD.raw,
            ),
        )
    }

    /**
     * 插一条「变更确认卡」（决策①·confirmed 约定的改期/取消走此卡）。target=被变更的已确认约定；
     * [resolution] 仅改期非空（拟改到的新时刻）。**幂等去重**：同会话已有「同约定 + 同变更类型 + 待确认（responded=null）」
     * 的卡则不重复插——扫描每轮重测同一意图不堆卡（漏判下轮补、用户点过的卡 responded 非空不再拦）。
     */
    private suspend fun insertChangeCard(
        target: MeetingAppointmentEntity,
        changeKind: String,
        resolution: MeetingTimeResolver.Resolution?,
        candidate: MeetingCandidate,
        conversationUuid: String,
        nowMillis: Long,
        zone: ZoneId,
    ) {
        val alreadyPending = messageRepo.messagesByKind(conversationUuid, MessageKind.FUTURE_MEETING_CHANGE_CARD.raw)
            .any { msg ->
                FutureMeetingChangeJson.parse(msg.content)?.let {
                    it.responded == null && it.appointmentUuid == target.uuid && it.changeKind == changeKind
                } == true
            }
        if (alreadyPending) return

        val oldWhen = MeetingDisplayFormatter.whenDisplay(
            target.scheduledAt, MeetingTimeGranularity.fromRaw(target.timeGranularity), zone,
        )
        val data = FutureMeetingChangeData(
            appointmentUuid = target.uuid,
            changeKind = changeKind,
            oldWhenDisplay = oldWhen.ifBlank { null },
            newWhenDisplay = resolution?.let { MeetingDisplayFormatter.whenDisplay(it.scheduledAtMillis, it.granularity, zone) }?.ifBlank { null },
            newScheduledAtMillis = resolution?.scheduledAtMillis,
            newGranularity = resolution?.granularity?.raw,
            location = target.location.ifBlank { null },
            activity = target.activity.ifBlank { null },
            reason = candidate.invitationText.ifBlank { null },
            responded = null,
        )
        messageRepo.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "assistant",
                content = FutureMeetingChangeJson.encode(data),
                timestamp = nowMillis,
                messageKindRaw = MessageKind.FUTURE_MEETING_CHANGE_CARD.raw,
            ),
        )
    }
}
