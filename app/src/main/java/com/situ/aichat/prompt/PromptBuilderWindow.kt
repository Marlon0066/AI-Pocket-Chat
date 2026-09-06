package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.offline.OfflineMarkerStartPayload
import java.time.Instant

/**
 * 短期记忆窗口预算 + 历史截断核心算法（自 [PromptBuilder] 抽出 · 文件瘦身，**行为零改 / 逐字不变**）：
 * [prepareFilteredRecentMessages] 按动态记忆长度切窗 + 分离聊天/见面消息 + 邀约事件流剥离 + 脏消息过滤；
 * [truncateToRecentRounds] 按「轮」截断（整段线下见面 / 通话特殊块算 1 轮，1:1 iOS）。
 *
 * 特殊块内「保留几条」不在本文件算：一律经 [retainedSpecialBlockCount]（见面 = CJK 字符预算 / 通话 = 条数），
 * 与前情提要协调器同源（图纸 2026-09-06 见面窗口与节拍卡七件 §3.B/F）。
 *
 * 由 [PromptBuilder.buildMessages] 装配第 0 步调用（同包顶层函数·无需限定）；回调 [PromptBuilder] 的
 * shouldKeepOfflineMarkerStart / ROLE_USER / ROLE_ASSISTANT 经 `PromptBuilder.` 限定，消息类型解析用同包
 * [MessageEntity.kind] 顶层扩展。
 */

internal fun prepareFilteredRecentMessages(
    sortedMessages: List<MessageEntity>,
    appSettings: AppSettings,
    isCurrentlyInOfflineMode: Boolean,
    currentOfflineSessionId: String?,
    unsummarizedRoundsOutsideBaseWindow: Int,
    now: Instant,
): Triple<List<MessageEntity>, List<MessageEntity>, List<String>> {
    val effectiveMemoryLength = calculateEffectiveMemoryLength(appSettings, unsummarizedRoundsOutsideBaseWindow)

    val truncationNotes = mutableListOf<String>()

    // 分离：聊天消息 vs 见面消息
    val chatOnlyMessages: List<MessageEntity>
    val recentMeetingMessages: List<MessageEntity>
    if (isCurrentlyInOfflineMode) {
        // 记忆改造二期·见面去重（部件⑥窗口侧·有意行为变化）：旧场见面原文不再进窗口——
        // 旧见面知识由【见面 · 】档案卡承担（{{见面记忆}} 在见面中本就无门控注入）。
        chatOnlyMessages = sortedMessages.filter {
            !it.isOfflineMode || (currentOfflineSessionId != null && it.offlineSessionId == currentOfflineSessionId)
        }
        recentMeetingMessages = emptyList()
    } else {
        // §3.6 原文通道退役（梦剧场 B 部）：普通聊天只注入见面【总结】（{{见面记忆}} 宏·来自结构化行渲染），
        // 不再把见面原文消息塞进窗口（旧 meetingRetentionDays 保留期通道作废）。见面【中】分支一行不动（上）。
        // 留痕改造 2026-08-31：**离场标记例外放行**——它是「这场见面结束了」的事务级真相，改写成一行系统记录进
        // 普通聊天窗口（渲染在 [appendConversationMessages]），根治「见面刚结束角色失忆式重发邀约」。见面【中】
        // 分支不放行：那里的 session 过滤天然挡住旧场离场标记（旧见面知识由【见面 · 】档案卡承担）。
        chatOnlyMessages = sortedMessages.filter {
            !it.isOfflineMode || it.kind() == MessageKind.OFFLINE_MARKER_END
        }
        recentMeetingMessages = emptyList()
    }
    val recentChatMessages = truncateToRecentRounds(
        messages = chatOnlyMessages,
        maxRounds = effectiveMemoryLength,
        policy = SpecialBlockPolicy.from(appSettings),
        isIncluded = { it.content.isNotEmpty() && it.kind() != MessageKind.CALL_RECORD_CARD },
        onTruncation = { note -> truncationNotes.add(note) },
    )

    val recentMessages = if (recentMeetingMessages.isEmpty()) {
        recentChatMessages
    } else {
        (recentChatMessages + recentMeetingMessages).sortedBy { it.timestamp }
    }

    // 邀约事件流剥离 + 脏消息过滤（1:1 iOS）。留痕改造 2026-08-31：邀约卡与离场标记不再整条剥离——
    // 两者转由 [appendConversationMessages] 改写成脱敏的 `[系统记录：…]` 留痕行（原文 JSON / 标记文本仍绝不进
    // prompt）；此处只剩结束确认卡整条剥离（其 finalMood 已由离场标记与见面摘要承载）。
    val filteredMessages = recentMessages.filter { msg ->
        if (DirtyMessageDetector.isDirty(msg.content, msg.kind())) return@filter false
        when (msg.kind()) {
            MessageKind.OFFLINE_END_CARD -> false
            // 入场标记：仅当前在线下模式 + 本次 session 匹配才保留（供提场景种子 + 上下文；其他 session 标记不误匹配）。
            MessageKind.OFFLINE_MARKER_START ->
                PromptBuilder.shouldKeepOfflineMarkerStart(isCurrentlyInOfflineMode, currentOfflineSessionId, msg.offlineSessionId)
            else -> true
        }
    }

    return Triple(filteredMessages, recentMessages, truncationNotes)
}

/** 动态扩展短期记忆窗口：基准轮数 + 窗口外未总结轮数（上限 = 基准），确保零记忆真空。 */
internal fun calculateEffectiveMemoryLength(appSettings: AppSettings, unsummarizedRounds: Int): Int {
    val base = appSettings.shortTermMemoryLength
    return base + minOf(unsummarizedRounds, base)
}

// MARK: - 截断（核心算法，整段特殊块算 1 轮）

internal fun truncateToRecentRounds(
    messages: List<MessageEntity>,
    maxRounds: Int,
    policy: SpecialBlockPolicy,
    isIncluded: (MessageEntity) -> Boolean = { true },
    onTruncation: ((String) -> Unit)? = null,
): List<MessageEntity> {
    if (maxRounds <= 0) return emptyList()

    var roundCount = 0
    val collected = mutableListOf<MessageEntity>()
    var lastRole: String? = null
    var pendingSpecialBlock = mutableListOf<MessageEntity>()
    var pendingBlockType: SpecialBlockKind? = null

    fun flushSpecialBlock() {
        val blockType = pendingBlockType
        if (pendingSpecialBlock.isEmpty() || blockType == null) {
            pendingSpecialBlock = mutableListOf()
            pendingBlockType = null
            return
        }
        roundCount += 1
        // 保留数单源（图纸 2026-09-06 七件 §3.B/F）：pendingSpecialBlock 是**最新在前**，单源函数吃升序 →
        // 传 asReversed()；take(retained) 即最新 retained 条（与原 take(limit) 同向）。
        val retained = retainedSpecialBlockCount(pendingSpecialBlock.asReversed(), blockType, policy)
        val toCollect: List<MessageEntity> = if (retained < pendingSpecialBlock.size) {
            onTruncation?.invoke("（${blockType.label}更早部分已省略，仅保留最近 $retained 条）")
            pendingSpecialBlock.take(retained)
        } else {
            pendingSpecialBlock
        }
        collected.addAll(toCollect.filter(isIncluded))
        pendingSpecialBlock = mutableListOf()
        pendingBlockType = null
        lastRole = null
    }

    loop@ for (message in messages.asReversed()) {
        val currentBlockType = SpecialBlockKind.classify(message)

        if (currentBlockType != null) {
            val existing = pendingBlockType
            if (existing != null && existing != currentBlockType) {
                flushSpecialBlock()
                if (roundCount >= maxRounds) break@loop
            }
            pendingBlockType = currentBlockType
            pendingSpecialBlock.add(message)
            continue
        }

        if (pendingSpecialBlock.isNotEmpty()) {
            flushSpecialBlock()
            if (roundCount >= maxRounds) break@loop
        }

        val currentRole = message.roleRaw
        if (lastRole == PromptBuilder.ROLE_USER && currentRole == PromptBuilder.ROLE_ASSISTANT) {
            roundCount += 1
            if (roundCount >= maxRounds) {
                lastRole = currentRole
                if (isIncluded(message)) collected.add(message)
                break@loop
            }
        }

        lastRole = currentRole
        if (!isIncluded(message)) continue
        collected.add(message)
    }

    flushSpecialBlock()
    return collected.asReversed()
}

/**
 * 当前 session 的入场标记 payload：从**全量**历史（调用方传入的 sortedMessages·未截断）倒序找
 * 第一条 kind()==OFFLINE_MARKER_START 且 offlineSessionId==currentOfflineSessionId 的消息并解析。
 * sessionId 空白 / 无匹配标记 / parse 失败 → null（找到首条即停，不续扫）。
 * 供 PromptBuilder 线下末位分支取地点 / 活动 / 心事种子——不再从截断后的窗口里找
 * （见面块超字符预算后入场标记会被丢出窗口）。
 */
internal fun currentOfflineSessionStartPayload(
    sortedMessages: List<MessageEntity>,
    currentOfflineSessionId: String?,
): OfflineMarkerStartPayload? {
    if (currentOfflineSessionId.isNullOrBlank()) return null
    for (msg in sortedMessages.asReversed()) {
        if (msg.kind() == MessageKind.OFFLINE_MARKER_START && msg.offlineSessionId == currentOfflineSessionId) {
            return OfflineMarkerStartPayload.parse(msg.content)
        }
    }
    return null
}
