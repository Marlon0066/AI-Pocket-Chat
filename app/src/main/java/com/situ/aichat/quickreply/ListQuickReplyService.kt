package com.situ.aichat.quickreply

import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.offline.outgoingOfflineSessionId
import com.situ.aichat.recovery.RecoveryClaimTracker
import com.situ.aichat.recovery.RecoveryReplyGenerator
import com.situ.aichat.util.StreakManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 列表内联快捷回复（B5·安卓超越 iOS）：在聊天列表行长按「不进会话回一句」，后台跑完整一轮角色 LLM 回复。
 *
 * **复用既有管线**：先把用户消息落库（照 [com.situ.aichat.ui.chat.ChatViewModel] send 的最小集：插 user
 * `MessageEntity` + `recordLastMessage`），再调 **现成的** [RecoveryReplyGenerator.generateAndPersist]（= 未答
 * 消息恢复同款无头回复管线：装配上下文→调 LLM→落 assistant 段→mood→会话末条→未读 +1）。assistant 回复落库后
 * 列表的 Room 流自动刷新预览/时间/未读角标——「列表即操作台」。
 *
 * **跨屏存活**：跑在自有 app 级 `@Singleton` scope（`SupervisorJob`），**不**用 ViewModel scope——列表页长按回完
 * 即可能离屏，绑生命周期会把回合砍断（照 [com.situ.aichat.busyreply.BusyReplyService] 范式）。
 *
 * **并发守卫 + 让位重试**：用户消息落库后该会话 `lastMessageRole="user"` → 会被未答恢复扫描 / 进会话 autoRecover
 * 选中。故起回合前先 [RecoveryClaimTracker] 占坑，三方互斥防双答。若占不到坑（别处正有一轮在飞），**短重试**等其
 * 结束后再抢——抢到后先复核「仍是 user 末条」再生成（防别处已应答时重复生成）；始终抢不到则用户消息留库、交后台
 * 恢复扫描兜底（对抗复核 1a-race）。
 *
 * **有意简化（与未答恢复路径一致）**：纯文字（无打字动画/分段时延）、不走语音、不发日历/线下工具卡、不触发逐回合
 * 记忆摘要/成长/关系/节拍维护、不补向量嵌入——这些留给用户下次正常进会话的前台回合或周期 Worker 兜账。
 * **火花续期除外**（前后置区审计 R1-N8·2026-07-13 用户拍板）：通知直接回复/列表快捷回复/快聊也是「今天聊过」，
 * [insertUserMessage] 落库后与主路径同源续期（否则只经通知栏互动的用户火花会断）。
 */
@Singleton
class ListQuickReplyService @Inject constructor(
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val replyGenerator: RecoveryReplyGenerator,
    private val claimTracker: RecoveryClaimTracker,
    // 火花续期（R1-N8）：照 DiaryExchangeService「service 直用 DAO」先例，避免拖 CharacterRepository 重构造。
    private val characterDao: CharacterDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 列表回一句（fire-and-forget）：落用户消息 → 占坑（带让位重试）→ 后台跑一轮 LLM 回复。空白忽略。 */
    fun send(conversationUuid: String, text: String) {
        if (text.isBlank()) return
        scope.launch {
            try {
                sendAndAwait(conversationUuid, text)
            } catch (e: Exception) {
                // 防异常逸出到 SupervisorJob 顶层打噪声堆栈（对抗复核 6c）；用户消息已落、待恢复扫描兜底。
                Log.w(TAG, "list quick reply failed for $conversationUuid", e)
            }
        }
    }

    /**
     * 同步版（suspend，跑完才返回）：落用户消息 → 占坑（带让位重试）→ 跑一轮 LLM 回复。返回是否成功生成并落库了
     * assistant 回复（false = 空白文本 / 始终抢不到坑 / 超时 / 生成空回复；此时用户消息仍已落库交后台恢复扫描兜底）。
     * 供 13.8·B1 通知直接回复在加急 worker 里**等回合跑完**再把 AI 回复回推通知栏（[send] 的 fire-and-forget 拿不到完成时机）。
     * 与 [send] 共用同一落库 + 占坑逻辑（DRY），不重复任何持久化 / 并发守卫。
     */
    suspend fun sendAndAwait(conversationUuid: String, text: String): Boolean {
        if (!insertUserMessage(conversationUuid, text)) return false
        return generateReplyWithClaim(conversationUuid)
    }

    /**
     * 只落用户消息（W12 C6 快聊·从 [sendAndAwait] **抽取**其前半·行为字节不变）：见面期随会话打线下标记 → 插 user
     * [MessageEntity] → 会话末条快照（见面中只 `touchLastMessageDate` 不写预览·卷一 A2a）。返回 false = 空白文本（不落库）。**忙碌快聊**需先即时落用户消息（气泡经消息流
     * 即时回显·§4.4 demo 行为）再延迟起回合，故拆此 insert-only 口；[sendAndAwait] 改为调它再 [generateReplyWithClaim]，
     * 既有调用方零感知（R1 复核 🔴-1）。
     */
    suspend fun insertUserMessage(conversationUuid: String, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val now = System.currentTimeMillis()
        // 见面期间列表快捷回复也须随会话打线下标记（与 ChatViewModel.send / 助手投递 deliverTextReply 同源），否则
        // 用户消息漏进普通聊天 + 缺席沉浸剧场（按目标会话当前线下态判定；会话不存在→普通消息，落库行为同原先）。
        val convo = conversationRepo.get(conversationUuid)
        val offlineSessionId = outgoingOfflineSessionId(convo?.isInOfflineMode == true, convo?.currentOfflineSessionId)
        messageRepo.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "user",
                content = trimmed,
                timestamp = now,
                isOfflineMode = offlineSessionId != null,
                offlineSessionId = offlineSessionId,
            ),
        )
        // 只写末条信息，不动 unread/lastReadDate（自己发的，不该让自己会话 +1 未读）。
        // 见面中（卷一 A2a）：这句属「见面期间产生的消息」，不写预览、仅刷新最后活动时间保鲜排序
        //（与主路径 AssistantTurnController.storeUserMessage / AI 侧 assistantDeliveryPreview 方案 A 同源）。
        if (offlineSessionId != null) {
            conversationRepo.touchLastMessageDate(conversationUuid, now)
        } else {
            conversationRepo.recordLastMessage(conversationUuid, trimmed, "user", now)
        }
        // 观测点（13.10a 分享投递共用此管线）：用户消息已真落库，只打长度不打内容。
        Log.d(TAG, "用户消息已落库 conv=$conversationUuid textLen=${trimmed.length}")
        // 火花续期（审计 R1-N8·2026-07-13 拍板）：语义与主路径 AssistantTurnController 同源（recordChat 同日
        // 去重 + 两列 UPDATE）。best-effort：续期失败绝不阻断回合——通知回复的核心价值是回复本身。
        runCatching { renewStreak(convo?.characterUuid, now) }
            .onFailure { Log.w(TAG, "火花续期失败（不影响回合）: ${it.message}") }
        return true
    }

    /** 与主路径同款续期：角色缺失 / 今天已聊过（recordChat 引用相等）→ 零写。 */
    private suspend fun renewStreak(characterUuid: String?, now: Long) {
        val character = characterUuid?.let { characterDao.getByUuid(it) } ?: return
        val renewed = StreakManager.recordChat(character, now)
        if (renewed !== character) {
            characterDao.updateStreak(character.uuid, renewed.streakCount, renewed.lastChatDate ?: now)
        }
        // 相识天数图纸 §4.1：首条消息落「第一次聊天时间」（SQL 只往早改；老角色由冷启补账改成真最早）。
        if (character.firstMessageDate == null) characterDao.markFirstMessageDate(character.uuid, now)
    }

    /**
     * 重试回复（W12 C6 快聊·薄包装 [generateReplyWithClaim]）：**绝不重插用户消息**——上一条用户消息已落库（[sendAndAwait]
     * 失败时用户消息仍在），此处只重跑「占坑 → 生成 → 落 assistant」。返回是否真生成并落库回复。既有 [send]/[sendAndAwait] 零改。
     */
    suspend fun retryReply(conversationUuid: String): Boolean = generateReplyWithClaim(conversationUuid)

    /**
     * 占坑后跑一轮回复；占不到则短重试等在飞回合结束再抢（对抗复核 1a-race 修法）。返回是否真生成并落库 assistant 回复。
     * 抢到坑后复核「仍是 user 末条」——若别处已在重试期间应答则跳过（返回 false），避免对已答会话重复生成。
     */
    private suspend fun generateReplyWithClaim(conversationUuid: String): Boolean {
        repeat(MAX_CLAIM_ATTEMPTS) { attempt ->
            if (claimTracker.tryBegin(conversationUuid)) {
                try {
                    if (conversationRepo.get(conversationUuid)?.lastMessageRole != "user") return false
                    // 对齐恢复路径加超时，防 LLM 僵死长期占坑冻结该会话（对抗复核 1b）。
                    return withTimeoutOrNull(REPLY_TIMEOUT_MS) { replyGenerator.generateAndPersist(conversationUuid) } ?: false
                } finally {
                    claimTracker.end(conversationUuid)
                }
            }
            if (attempt < MAX_CLAIM_ATTEMPTS - 1) delay(CLAIM_RETRY_DELAY_MS)
        }
        // 始终抢不到坑（别处长回合占用）：用户消息已落、lastMessageRole=user，交后台恢复扫描兜底。
        return false
    }

    private companion object {
        const val TAG = "ListQuickReply"
        const val MAX_CLAIM_ATTEMPTS = 4
        const val CLAIM_RETRY_DELAY_MS = 1_500L
        const val REPLY_TIMEOUT_MS = 5 * 60_000L // 对齐 UnansweredMessageRecoveryService（iOS 300s）。
    }
}
