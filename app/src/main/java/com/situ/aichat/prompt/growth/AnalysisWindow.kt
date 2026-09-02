package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.prompt.memory.MemoryService

// MARK: - 按轮切窗（活人感内核卷零 §3.4 · 从 GrowthAnalysisService 只搬不改拆出）
//
// 本文件是「按轮切窗」一族的家：窗口类型 [AnalysisWindow] + 轮首常量 [ROLE_USER] + 切轮纯函数
// [lastNRounds] + 对话记录渲染 [buildConversationText]。四者原先住在 GrowthAnalysisService 里，
// 卷二 chunk 0 按卷零 R1 🟡-1 的强制前置动作**只搬不改**迁来（行为字节级不变，仅
// [buildConversationText] 由类内 private 升为同包 internal —— 搬出类体的必然推论）。
// 窗口的取数与切分入口仍是 `GrowthAnalysisService.collectAnalysisWindow`（那里持有 DAO）。

/**
 * 按轮切分的分析窗口（活人感内核卷零 §3.4）。
 *
 * [leadIn] = 上次分析已计过分的前置上下文（只供 LLM 理解语境，提示词里显式标注「不要重复计分」）；
 * [fresh] = 本次真正要评分的新内容。两段合起来才是喂给 LLM 的对话记录（[all]）。
 */
data class AnalysisWindow(val leadIn: List<MessageEntity>, val fresh: List<MessageEntity>) {
    val all: List<MessageEntity> get() = leadIn + fresh
}

/** 用户消息的 `roleRaw` 值——**一轮的起点**（与 [MessageEntity.roleRaw] 的既有取值一致）。 */
internal const val ROLE_USER = "user"

/**
 * 取升序列表 [ascending] 末尾的 [n] 轮（活人感内核卷零 §3.4）。
 *
 * **一轮 = 从一条 `roleRaw == "user"` 的消息起，到下一条 user 消息之前**（角色可能连发多条，
 * 它们都属于同一轮——这正是「按轮」比「按条」稳的原因）。
 *
 * 不足 [n] 轮返回全部；列表中一条 user 消息都没有则返回空（无从切轮，宁可不给前置也不给半截）。
 */
internal fun lastNRounds(ascending: List<MessageEntity>, n: Int): List<MessageEntity> {
    if (n <= 0) return emptyList()
    var userSeen = 0
    for (i in ascending.indices.reversed()) {
        if (ascending[i].roleRaw != ROLE_USER) continue
        userSeen++
        // 倒数第 n 条 user 消息**就是**末 n 轮的轮首 ⇒ 从它（含）到结尾即末 n 轮。
        if (userSeen == n) return ascending.subList(i, ascending.size).toList()
    }
    // 一条 user 都没有 → 无从切轮；有但不足 n 轮 → 全给。
    return if (userSeen == 0) emptyList() else ascending.toList()
}

/**
 * 渲染对话记录段。[leadInCount] > 0 时在前置段与新内容段之间插一行标注，告诉模型前面那几轮
 * 已经计过分、只供理解语境——否则同一段对话会被连着几次分析反复加分（活人感内核卷零 §3.4）。
 *
 * 标注行里的轮数是**真实前置轮数**（= 前置段里 user 消息的条数），不足 4 轮就写真实值；
 * [leadInCount] 为 0 或前置段渲染为空时整行不输出，此时输出与旧版逐字节相同。
 */
internal fun buildConversationText(
    messages: List<MessageEntity>,
    leadInCount: Int,
    resolvedUserName: String,
    characterName: String,
): String {
    if (leadInCount !in 1..messages.size) {
        return MemoryService.formatMessages(messages, userLabel = resolvedUserName, charLabel = characterName)
    }
    val leadIn = messages.take(leadInCount)
    val rounds = leadIn.count { it.roleRaw == ROLE_USER }
    val leadInText = MemoryService.formatMessages(leadIn, userLabel = resolvedUserName, charLabel = characterName)
    val freshText = MemoryService.formatMessages(messages.drop(leadInCount), userLabel = resolvedUserName, charLabel = characterName)
    if (rounds == 0 || leadInText.isEmpty()) return freshText
    return listOf(leadInText, "以上 $rounds 轮已在上次分析中计过分，只供你理解语境，不要重复计分。", freshText)
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}
