package com.situ.aichat.meeting

import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.model.MeetingCandidate
import com.situ.aichat.data.model.MeetingProposedBy
import com.situ.aichat.data.model.MeetingSource
import com.situ.aichat.data.model.MeetingStatus
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 未来约定见面真理源服务（1:1 iOS `Services/MeetingAppointmentStore.swift`）。
 * 所有写入与状态流转的唯一入口：识别层产候选 → 这里查重 / 落库 / 流转。
 *
 * - DAO 交互走 suspend（Room 自管线程）。状态流转 = 读 → 纯函数算新行 → @Update（不可变 Room 行，与 RedPacketService 一致）。
 * - 状态机守卫 + 字段变化、查重判定抽成 companion **纯函数**（不碰 DB），便于复用与单测。
 * - 状态机：proposed →(确认)→ confirmed →(赴约)→ honored；confirmed →(过宽限)→ missed；
 *   proposed/confirmed →(取消)→ cancelled。终态不可再流转（纯函数返回 null = 守卫拒绝）——
 *   **唯一例外** = [repairMissedToHonored]（missed→honored·爽约误判自愈·图纸 2026-08-31，仅
 *   [MeetingFulfillmentService] 凭入场标记实证调用）。
 */
@Singleton
class MeetingAppointmentStore @Inject constructor(
    private val dao: MeetingAppointmentDao,
    private val meetupNotificationService: MeetupNotificationService,
) {

    // ── 创建 ──

    /** 由候选 + 解析结果创建「已提出」约定并入库（尚未确认、不排通知）。 */
    suspend fun createProposed(
        candidate: MeetingCandidate,
        resolution: MeetingTimeResolver.Resolution,
        characterUuid: String,
        conversationUuid: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): MeetingAppointmentEntity {
        val appt = MeetingAppointmentEntity(
            uuid = UUID.randomUUID().toString(),
            characterUuid = characterUuid,
            conversationUuid = conversationUuid,
            status = MeetingStatus.PROPOSED.raw,
            proposedBy = candidate.proposedBy.raw,
            source = candidate.source.raw,
            scheduledAt = resolution.scheduledAtMillis,
            timeGranularity = resolution.granularity.raw,
            rawWhenText = candidate.rawWhen,
            location = candidate.location,
            activity = candidate.activity,
            invitationText = candidate.invitationText,
            tensionHint = candidate.tensionHint,
            hiddenTensionSeed = candidate.hiddenTensionSeed,
            createdAt = nowMillis,
        )
        dao.insert(appt)
        return appt
    }

    /** 手动发起：跳过确认闸门，直接 confirmed（用户自填无误判风险）。 */
    suspend fun createConfirmedManually(
        resolution: MeetingTimeResolver.Resolution,
        characterUuid: String,
        conversationUuid: String,
        location: String,
        activity: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): MeetingAppointmentEntity {
        val appt = MeetingAppointmentEntity(
            uuid = UUID.randomUUID().toString(),
            characterUuid = characterUuid,
            conversationUuid = conversationUuid,
            status = MeetingStatus.CONFIRMED.raw,
            proposedBy = MeetingProposedBy.USER.raw,
            source = MeetingSource.MANUAL.raw,
            scheduledAt = resolution.scheduledAtMillis,
            timeGranularity = resolution.granularity.raw,
            location = location,
            activity = activity,
            createdAt = nowMillis,
            confirmedAt = nowMillis,
        )
        dao.insert(appt)
        return appt
    }

    // ── 状态流转（读 → 纯函数 → update；守卫拒绝 / 找不到 → null） ──

    suspend fun confirm(uuid: String, nowMillis: Long = System.currentTimeMillis()): MeetingAppointmentEntity? =
        transition(uuid) { confirmed(it, nowMillis) }

    suspend fun reschedule(uuid: String, resolution: MeetingTimeResolver.Resolution): MeetingAppointmentEntity? =
        transition(uuid) { rescheduled(it, resolution) }

    suspend fun cancel(uuid: String, nowMillis: Long = System.currentTimeMillis()): MeetingAppointmentEntity? =
        transition(uuid) { cancelled(it, nowMillis) }

    suspend fun markHonored(uuid: String, sessionId: String, nowMillis: Long = System.currentTimeMillis()): MeetingAppointmentEntity? =
        transition(uuid) { honored(it, sessionId, nowMillis) }

    suspend fun markMissed(uuid: String, nowMillis: Long = System.currentTimeMillis()): MeetingAppointmentEntity? =
        transition(uuid) { missed(it, nowMillis) }

    /**
     * 爽约误判自愈（状态机唯一终态例外·图纸 2026-08-31）：missed → honored，链上实证见面的 sessionId。
     * 只由 [MeetingFulfillmentService.repairMissedAppointments] 凭入场标记实证调用；其余状态守卫拒绝。
     */
    suspend fun repairMissedToHonored(uuid: String, sessionId: String, nowMillis: Long = System.currentTimeMillis()): MeetingAppointmentEntity? =
        transition(uuid) { repairedToHonored(it, sessionId, nowMillis) }

    private suspend fun transition(
        uuid: String,
        op: (MeetingAppointmentEntity) -> MeetingAppointmentEntity?,
    ): MeetingAppointmentEntity? {
        val current = dao.getByUuid(uuid) ?: return null
        val updated = op(current) ?: return null
        dao.update(updated)
        return updated
    }

    // ── 查询 ──

    /** 按 uuid 取约定（识别侧判 proposed/confirmed 决定直接 mutate 还是过变更确认卡）。 */
    suspend fun get(uuid: String): MeetingAppointmentEntity? = dao.getByUuid(uuid)

    suspend fun activeForCharacter(characterUuid: String): List<MeetingAppointmentEntity> =
        dao.activeForCharacter(characterUuid)

    suspend fun activeForConversation(conversationUuid: String): List<MeetingAppointmentEntity> =
        dao.activeForConversation(conversationUuid)

    /** 某角色下一个「未来且已确认」的约定（倒数小条用）。 */
    suspend fun nextUpcomingForCharacter(characterUuid: String, nowMillis: Long): MeetingAppointmentEntity? =
        dao.activeForCharacter(characterUuid).firstOrNull {
            MeetingStatus.fromRaw(it.status) == MeetingStatus.CONFIRMED && it.scheduledAt > nowMillis
        }

    /** 全部已错过的约定（爽约误判自愈扫描用·表小，内存过滤）。 */
    suspend fun allMissed(): List<MeetingAppointmentEntity> =
        dao.getAllAppointments().filter { MeetingStatus.fromRaw(it.status) == MeetingStatus.MISSED }

    // ── 查重 ──

    /** 在某角色已有进行中约定里找与候选重复的那条（先取 activeForCharacter 再判，避免重复查库）。 */
    suspend fun findDuplicateForCharacter(
        characterUuid: String,
        resolvedDateMillis: Long,
        activity: String,
        zone: ZoneId,
    ): MeetingAppointmentEntity? =
        findDuplicate(resolvedDateMillis, activity, dao.activeForCharacter(characterUuid), zone)

    /**
     * 识别路查重（图纸 2026-08-31 C3·**范围含已赴约**）：治幽灵约定——见面后识别重扫旧消息时，刚核销的
     * 约定已从 isActive 查重消失，同一件事被当新约重复立卡。HONORED 计入重复；MISSED/CANCELLED 仍放行
     * （错过或取消后同日重约是正当新约定）。手动「约见面」表单仍走 [findDuplicateForCharacter]（用户显式意图不拦）。
     */
    suspend fun findDuplicateForCharacterIncludingHonored(
        characterUuid: String,
        resolvedDateMillis: Long,
        activity: String,
        zone: ZoneId,
    ): MeetingAppointmentEntity? {
        val candidates = dao.getAllAppointments().filter { it.characterUuid == characterUuid }
        return findDuplicateIncludingHonored(resolvedDateMillis, activity, candidates, zone)
    }

    /** 近 [withinMillis] 内已赴约的约定（按 outcomeAt·识别提示词【近期已赴约的见面】块用·C3；表小内存过滤）。 */
    suspend fun recentlyHonoredForCharacter(characterUuid: String, nowMillis: Long, withinMillis: Long): List<MeetingAppointmentEntity> =
        dao.getAllAppointments().filter {
            it.characterUuid == characterUuid &&
                MeetingStatus.fromRaw(it.status) == MeetingStatus.HONORED &&
                (it.outcomeAt ?: Long.MIN_VALUE) >= nowMillis - withinMillis
        }

    // ── 删除（删角色 / 删会话清理）：§7 坑 = **先枚举 uuid 撤每条 meetup_<uuid> 到点通知、再删记录** ──
    // （删行后约定从真理源消失，[MeetupNotificationService.rescheduleAll] 全量对账再也够不着 → 已排的闹钟变孤儿、
    //   到点弹「角色都删了还喊你赴约」的鬼通知；故清理必须删行前逐条撤。cancel 不存在的 key = no-op，幂等安全。）

    /** 删某角色全部约定（删角色流程必须调用·无 FK 不级联）。先撤每条到点通知、再删记录。 */
    suspend fun deleteForCharacter(characterUuid: String) {
        dao.uuidsForCharacter(characterUuid).forEach { meetupNotificationService.cancel(it) }
        dao.deleteForCharacter(characterUuid)
    }

    /** 删某会话全部约定（删会话 / 空会话清理流程必须调用）。先撤每条到点通知、再删记录。 */
    suspend fun deleteForConversation(conversationUuid: String) {
        dao.uuidsForConversations(listOf(conversationUuid)).forEach { meetupNotificationService.cancel(it) }
        dao.deleteForConversations(listOf(conversationUuid))
    }

    companion object {
        // ── 纯函数状态机（输入 entity，输出新 entity；null = 终态 / 非法，不流转） ──

        /** 确认：proposed/confirmed → confirmed（首次记 confirmedAt，不覆盖已有）。 */
        internal fun confirmed(appt: MeetingAppointmentEntity, nowMillis: Long): MeetingAppointmentEntity? {
            if (!MeetingStatus.fromRaw(appt.status).isActive) return null
            return appt.copy(
                status = MeetingStatus.CONFIRMED.raw,
                confirmedAt = appt.confirmedAt ?: nowMillis,
            )
        }

        /** 改期：更新时间 + 精度（保持 proposed/confirmed），清通知标记供 Phase 8 重排。 */
        internal fun rescheduled(
            appt: MeetingAppointmentEntity,
            resolution: MeetingTimeResolver.Resolution,
        ): MeetingAppointmentEntity? {
            if (!MeetingStatus.fromRaw(appt.status).isActive) return null
            return appt.copy(
                scheduledAt = resolution.scheduledAtMillis,
                timeGranularity = resolution.granularity.raw,
                lastReminderScheduledAt = null,
            )
        }

        /** 取消：→ cancelled。 */
        internal fun cancelled(appt: MeetingAppointmentEntity, nowMillis: Long): MeetingAppointmentEntity? {
            if (!MeetingStatus.fromRaw(appt.status).isActive) return null
            return appt.copy(status = MeetingStatus.CANCELLED.raw, outcomeAt = nowMillis)
        }

        /** 赴约：→ honored（链线下 sessionId）。 */
        internal fun honored(appt: MeetingAppointmentEntity, sessionId: String, nowMillis: Long): MeetingAppointmentEntity? {
            if (!MeetingStatus.fromRaw(appt.status).isActive) return null
            return appt.copy(status = MeetingStatus.HONORED.raw, honoredSessionId = sessionId, outcomeAt = nowMillis)
        }

        /** 错过：→ missed。 */
        internal fun missed(appt: MeetingAppointmentEntity, nowMillis: Long): MeetingAppointmentEntity? {
            if (!MeetingStatus.fromRaw(appt.status).isActive) return null
            return appt.copy(status = MeetingStatus.MISSED.raw, outcomeAt = nowMillis)
        }

        /**
         * 自愈修复（**状态机唯一终态例外**·图纸 2026-08-31）：missed → honored；其余状态一律 null 拒绝。
         * 背景：真实赴过的见面因入口未核销/幽灵约定被判 missed（终态·常规流转无从更正），
         * [MeetingFulfillmentService] 凭入场标记实证翻案。绝不给其他终态开口子。
         */
        internal fun repairedToHonored(appt: MeetingAppointmentEntity, sessionId: String, nowMillis: Long): MeetingAppointmentEntity? {
            if (MeetingStatus.fromRaw(appt.status) != MeetingStatus.MISSED) return null
            return appt.copy(status = MeetingStatus.HONORED.raw, honoredSessionId = sessionId, outcomeAt = nowMillis)
        }

        // ── 查重纯函数 ──

        /** 同一天（按 zone 当地日历）+ 活动相近 → 视为重复。仅在进行中里找。 */
        internal fun findDuplicate(
            resolvedDateMillis: Long,
            activity: String,
            existing: List<MeetingAppointmentEntity>,
            zone: ZoneId,
        ): MeetingAppointmentEntity? =
            findDuplicateCore(resolvedDateMillis, activity, existing, zone) { it.isActive }

        /** 同 [findDuplicate]，但 HONORED 也计入重复（识别路防幽灵·C3）；MISSED/CANCELLED 仍不算。 */
        internal fun findDuplicateIncludingHonored(
            resolvedDateMillis: Long,
            activity: String,
            existing: List<MeetingAppointmentEntity>,
            zone: ZoneId,
        ): MeetingAppointmentEntity? =
            findDuplicateCore(resolvedDateMillis, activity, existing, zone) { it.isActive || it == MeetingStatus.HONORED }

        private fun findDuplicateCore(
            resolvedDateMillis: Long,
            activity: String,
            existing: List<MeetingAppointmentEntity>,
            zone: ZoneId,
            statusCounts: (MeetingStatus) -> Boolean,
        ): MeetingAppointmentEntity? {
            val day = Instant.ofEpochMilli(resolvedDateMillis).atZone(zone).toLocalDate()
            return existing.firstOrNull { appt ->
                if (!statusCounts(MeetingStatus.fromRaw(appt.status))) return@firstOrNull false
                val apptDay = Instant.ofEpochMilli(appt.scheduledAt).atZone(zone).toLocalDate()
                apptDay == day && activitySimilar(appt.activity, activity)
            }
        }

        /** 活动相近：归一化后相等或互相包含视为同一活动；任一方为空则仅凭「同一天」判重。 */
        internal fun activitySimilar(a: String, b: String): Boolean {
            val na = a.trim().lowercase()
            val nb = b.trim().lowercase()
            if (na.isEmpty() || nb.isEmpty()) return true
            return na == nb || na.contains(nb) || nb.contains(na)
        }
    }
}
