package com.situ.aichat.meeting

import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.recovery.RecoveryClaimTracker
import com.situ.aichat.recovery.RecoveryReplyGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 爽约检测 + 角色自适应反应（Phase 11·1:1 iOS 错过见面的反应）。扫描「已确认、已过宽限期、仍未赴约」的约定，
 * 置 missed（终态·保证只反应一次），并插一条**隐藏旁白**（[MessageKind.SYSTEM_HINT]·用户不可见、喂 LLM）告诉角色
 * 「用户没来赴约」——再借**无头**回复生成器 [RecoveryReplyGenerator] 让角色按**自己的人设 + 你们当前关系阶段 + 约定
 * 份量**生成真切反应（决策③：不写模板）。人设 / 关系阶段已在 PromptBuilder 拼好的系统提示词里，旁白只补「错过」这件事。
 *
 * **只反应一次**：[MeetingAppointmentStore.markMissed] 把 confirmed→missed（终态），下轮扫描不再命中 → 旁白 + 反应各一次。
 * 即便反应生成失败（无 key / LLM 失败），旁白仍留在上下文里 → 角色在用户下次互动的回合里自然带出反应（不丢）。
 *
 * **触发**：App 启动 / 回前台由 [com.situ.aichat.ui.AppViewModel.onAppForeground] 调 [scanAndReact]（用户回到 App
 * 时反应自然浮现）。[running] 重入互斥防并发双反应；按会话经 [RecoveryClaimTracker] 与未答恢复协调、防同会话双答。
 *
 * **§7 坑**：① 线下见面进行中的会话**判已赴约**（`markHonored` 链当前 sessionId·卷一 D1b 拍板⑪·2026-08-26
 * 取代旧「只跳过旁白但仍置 missed」——用户正在赴这场约，missed 是终态不再被扫、无从更正）；② 旁白用纯括号叙述，
 * **避开 DirtyMessageDetector 的保留段标题**（【见面 · 】等），免误伤脏消息检测。
 */
@Singleton
class MeetingMissedReactionService @Inject constructor(
    private val db: AppDatabase,
    private val dao: MeetingAppointmentDao,
    private val store: MeetingAppointmentStore,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val replyGenerator: RecoveryReplyGenerator,
    private val claimTracker: RecoveryClaimTracker,
    private val userProfileDao: UserProfileDao,
) {

    private val running = AtomicBoolean(false)

    /** 扫描爽约约定 → 置 missed + 插隐藏旁白 → 每个受影响会话生成一次角色自适应反应。重入互斥、可反复安全调。 */
    suspend fun scanAndReact(now: Long = System.currentTimeMillis()) {
        if (!running.compareAndSet(false, true)) return
        try {
            val zone = ZoneId.systemDefault()
            val missed = dao.getAllAppointments().filter { isMissedConfirmed(it, now, zone) }
            if (missed.isEmpty()) return
            val toReact = LinkedHashSet<String>() // 去重会话（同会话多条爽约只反应一次）
            for (appt in missed) {
                // 复核 LOW：置 missed（终态门·只反应一次）+ 插隐藏旁白在**同一事务**原子落库——防两写之间进程死把约定
                // 搁成「已 missed 却没旁白 → 反应永久丢失」（markMissed 终态后不再被扫，无从补）。返回是否需生成反应。
                // markMissed 必须先于插旁白（作门）：并发已被取消/赴约的约定守卫拒绝 → 不插「你没来」错旁白。
                val needsReaction = db.withTransaction {
                    // 卷一 D1b（拍板⑪）：**先**看会话是否正在线下见面——用户此刻正在赴这场约，
                    // 判 honored 而不是 missed（旧实现 markMissed 先行，只跳过旁白，missed 终态仍落下 →
                    // 赴着约却被记成爽约，且 missed 是终态不再被扫、无从更正）。守卫拒绝（并发已取消/已赴约）
                    // 返 null → 照旧跳过。honored 改写与下面的 missed 分支同在这一笔事务内（原子不变量不破）。
                    val convo = conversationRepo.get(appt.conversationUuid)
                    if (convo != null && convo.isInOfflineMode) {
                        store.markHonored(appt.uuid, convo.currentOfflineSessionId.orEmpty(), now)
                        return@withTransaction false
                    }
                    if (store.markMissed(appt.uuid, now) == null) return@withTransaction false // 守卫拒绝（并发已流转）
                    // 会话已删：只清数据（已置 missed），不插反应。
                    if (convo == null) return@withTransaction false
                    insertMissedHint(appt, now, zone)
                    true
                }
                if (needsReaction) toReact.add(appt.conversationUuid)
            }
            for (conversationUuid in toReact) reactInConversation(conversationUuid)
        } finally {
            running.set(false)
        }
    }

    /** 插隐藏旁白（assistant 不可见路径·roleRaw=user 同 [com.situ.aichat.offline.OfflineMeetingService] 的取消提示）。 */
    private suspend fun insertMissedHint(appt: MeetingAppointmentEntity, now: Long, zone: ZoneId) {
        val whenDisplay = MeetingDisplayFormatter.whenDisplay(
            appt.scheduledAt, MeetingTimeGranularity.fromRaw(appt.timeGranularity), zone,
        )
        val userName = (userProfileDao.get()?.nickname ?: "").ifBlank { "用户" }
        messageRepo.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = appt.conversationUuid,
                roleRaw = "user",
                content = missedHint(whenDisplay, appt.location, appt.activity, userName),
                timestamp = now,
                messageKindRaw = MessageKind.SYSTEM_HINT.raw,
            ),
        )
    }

    /** 无头生成角色反应并落库（5 分钟超时兜底）。占坑防与未答恢复同会话双答；失败仅记日志（旁白已在上下文兜底）。 */
    private suspend fun reactInConversation(conversationUuid: String) {
        if (!claimTracker.tryBegin(conversationUuid)) return
        try {
            withTimeoutOrNull(REACTION_TIMEOUT_MS) { replyGenerator.generateAndPersist(conversationUuid) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "爽约反应生成失败 conv=$conversationUuid: ${e.message}")
        } finally {
            claimTracker.end(conversationUuid)
        }
    }

    companion object {
        private const val TAG = "MeetingMissedReaction"
        private const val REACTION_TIMEOUT_MS = 5L * 60 * 1000 // 5 分钟（对齐未答恢复 deadline）

        /** confirmed 且已过宽限期未赴约 = 爽约。纯函数便于单测。 */
        internal fun isMissedConfirmed(appt: MeetingAppointmentEntity, now: Long, zone: ZoneId): Boolean =
            MeetingStatus.fromRaw(appt.status) == MeetingStatus.CONFIRMED &&
                MeetingArrivalPolicy.isMissed(appt.scheduledAt, MeetingTimeGranularity.fromRaw(appt.timeGranularity), now, zone)

        /**
         * 爽约隐藏旁白（喂 LLM·用户不可见·SYSTEM_HINT）：只陈述「约定份量 + 用户没赴约」的事实 + 让角色按既有人设/
         * 关系真切反应（决策③·不写模板）。**纯括号旁白·不含 DirtyMessageDetector 保留段标题**（§7）。纯函数便于单测。
         */
        internal fun missedHint(whenDisplay: String, location: String, activity: String, userName: String): String {
            val act = activity.trim()
            val where = location.trim()
            val detail = buildString {
                if (whenDisplay.isNotBlank()) append(whenDisplay)
                if (act.isNotEmpty()) append("一起").append(act)
                if (where.isNotEmpty()) append("（在").append(where).append("）")
            }
            val plan = if (detail.isBlank()) "你和${userName}约好的那次见面" else "你和${userName}约好了 $detail 见面"
            return "（$plan，但约定的时间已经过去，${userName}始终没有出现，这次见面就这样错过了。" +
                "请你以符合自己性格、契合你们当前关系的方式，真切地表达此刻的心情——不要套用模板。）"
        }
    }
}
