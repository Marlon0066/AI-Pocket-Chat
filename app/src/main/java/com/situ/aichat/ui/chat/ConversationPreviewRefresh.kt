package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.prompt.DirtyMessageDetector
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 删消息后重算会话列表「最后一条」预览快照。
 *
 * 背景：聊天列表每行显示的「最后一条」是会话上**反范式存的快照**（`lastMessagePreview`/`lastMessageRole`/
 * `lastMessageDate`），只在发/收消息时由各插入点写入（[ConversationRepository.recordLastMessage]）。删一条消息
 * 此前**无人重算该快照** → 删掉最后一条后，列表仍显示那条已删消息的预览（用户报告问题②）。
 *
 * 修法：任一删消息路径删完后调用本函数——取删后真正的最新【可见】消息（与聊天屏 [MessageDao.observeVisibleWindowed]
 * 同口径过滤），按 [snapshotPreviewText] 口径算预览写回快照（红包/礼物/语音/通话等结构化卡绝不露原文）；
 * 整会话删空则清空快照（退出活跃列表）。删非最后一条时本函数为幂等（重算出的快照与原值一致）。
 *
 * 并发（审计 R2）：「读 latest → 写快照」两段挂起、无原子性——自底向上连删两条时，先删的那次若读完挂起、
 * 后删的整轮先完成，迟到的旧写回会把**已删消息**重新挂上列表。per-会话 [Mutex] 把「读+写」串行化：只要每次删除
 * 之后都跟一次本函数（现有两处调用点均如此），末次调用的读必发生在全部删除提交后 → 终值恒正确。
 * 残余窗口（有意接受·注释留档）：与 AI 递送层的**插入**并发时，本函数可能以毫秒级窗口写回删除时刻的旧预览，
 * 下一段递送的 finalizeDelivery 即覆写自愈；写入已是定向三列 UPDATE，绝不覆写 mood/voiceRounds 等并发列。
 */
internal suspend fun refreshConversationLastMessage(
    conversationUuid: String,
    messageRepo: MessageRepository,
    conversationRepo: ConversationRepository,
) {
    previewRefreshLocks.computeIfAbsent(conversationUuid) { Mutex() }.withLock {
        // 图纸 2026-09-01 件①：最新几条里跳过库内历史脏行（脏行在聊天屏已彻底隐身，列表预览也不该露它）。
        // 扫描窗 [PREVIEW_DIRTY_SCAN] 条：连续这么多条可见消息全脏的会话几乎不存在，真遇上就退化为清空预览。
        val latest = messageRepo.latestVisibleMessages(conversationUuid, PREVIEW_DIRTY_SCAN)
            .firstOrNull { !DirtyMessageDetector.isDirty(it.content, MessageKind.fromRaw(it.messageKindRaw)) }
        if (latest != null) {
            conversationRepo.recordLastMessage(
                conversationUuid,
                snapshotPreviewText(latest),
                latest.roleRaw,
                latest.timestamp,
            )
        } else {
            conversationRepo.clearLastMessage(conversationUuid)
        }
    }
}

/** 预览重算的脏行扫描窗（图纸件①）：最新这么多条里挑第一条非脏的；全脏则清空快照。 */
private const val PREVIEW_DIRTY_SCAN = 10

/** per-会话重算锁（进程级单例）：键=conversationUuid，条目数 ≤ 用户删过消息的会话数，不清理。 */
private val previewRefreshLocks = ConcurrentHashMap<String, Mutex>()

/**
 * 重算路径的预览口径（拍板 2026-07-02·审计 B1 = 对齐各插入点的格式，用户完全无感）：
 * - 用户语音消息 = `[语音] ` + 转写前 40 字（同 AssistantTurnController.sendVoiceMessage 插入式）；
 * - assistant 侧 = 截断 50 字（同 [assistantDeliveryPreview]；AI 语音消息插入点也走该式，不带前缀）；
 * - 其余沿用 [MessagePreviewText] 脱敏口径（结构化卡绝不露原始 JSON；用户文字插入点不截断，此处同样不截）。
 * 独立于 [MessagePreviewText.forMessage]：那是通知/快捷回复面的口径（无语音前缀），此处只管列表快照，互不牵动。
 */
internal fun snapshotPreviewText(message: MessageEntity): String {
    if (message.isVoiceMessage && message.roleRaw == "user") return "[语音] " + message.content.take(40)
    val base = MessagePreviewText.forMessage(message)
    return if (message.roleRaw == "assistant") base.take(50) else base
}
