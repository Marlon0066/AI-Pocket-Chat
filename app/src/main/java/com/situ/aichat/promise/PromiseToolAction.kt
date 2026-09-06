package com.situ.aichat.promise

/**
 * 「我们的约定」聊天内工具路 / 暗号路解析出的**待落库动作**（图纸 2026-09-06 约定工具调用化 §3.2）。
 *
 * 纯类型，跨层共享（[PromiseChatTool] 产出 → [com.situ.aichat.offline.ToolCallActionExtractor] /
 * [com.situ.aichat.ui.chat.AssistantResponsePreprocessor] 搬运 → `ChatPromiseToolHandler` 过闸落库）。
 * **闸门不在此**：这里的字段是模型说的原话，一个字都还没验（证据 / 编号 / 上限全在 handler 的 `screen`）。
 */
sealed interface PromiseToolAction {

    /** 新约定：[content] 一句话概括；[dueAtMillis] 解析失败 / 未给 → null；[evidence] 模型抄的原话（未验）。 */
    data class Record(val content: String, val dueAtMillis: Long?, val evidence: String) : PromiseToolAction

    /**
     * 了结一条既有约定：[no] = 本轮【我们的约定】注入块里的编号（单源
     * [PromiseInjectionRenderer.numberedOpen]）；[status] ∈ fulfilled | cancelled（未验）；[evidence] 原话（未验）。
     */
    data class Resolve(val no: Int, val status: String, val evidence: String) : PromiseToolAction
}
