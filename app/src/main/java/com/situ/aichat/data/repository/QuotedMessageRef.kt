package com.situ.aichat.data.repository

/**
 * 被引用消息的预取快照（引用一期·图纸 2026-09-04 §0.2 决策一/三）：调用方在装 prompt 前一次性查好
 * 「被引用的那条消息**当时**是什么时候说的、原文是什么」，经参数交给
 * [com.situ.aichat.prompt.PromptQuoteLine] 拼引用行——与 `imageAttachments` / `audioAttachments`
 * （`ui/chat/TurnMediaAttachments.kt`）同款「调用方预取对照表」模式，**不加 Room 列、不做迁移**。
 *
 * [rawContent] 有意是**原始 content**（含 `[sticker:xxx]` 标签）而非落库的 `quotedContent` 显示串：
 * 引用行注入排在表情标签转语义之前，原始标签下游会自动变成 `[非语言情绪：…]`，被引用的表情因此带上语义
 * 而不是零信息的 `[表情包]`。查不到（消息已删）时调用方回退用落库快照。
 *
 * **但它只对「正文即人话」的消息给值**（复核 R1 🔴）：结构化卡的 content 是 JSON——红包带 `amount`、
 * 礼物带 `cost`，而 `AssistantTurnController` 落库时**特意**只存脱敏串就是为了堵住「引用上下文喂 LLM」
 * 这条泄漏面。2026-09-04 收紧「只能引用纯文字」之前右滑能引用卡片，库里存得下这样的老行，若预取把原始
 * JSON 端回去等于把那个修复原样拆掉。故非纯文字目标一律 [rawContent] = null，调用方回退落库快照；
 * **时间戳照给**，老引用同样拿得到时间锚。
 */
data class QuotedMessageRef(
    /** 被引用消息的发生时刻（epoch millis）= `MessageEntity.timestamp`。 */
    val timestampMillis: Long,
    /**
     * 被引用消息的原始正文 = `MessageEntity.content`（未经任何显示层清洗）。
     * **null = 该目标不是纯文字**（结构化卡 / 图片）→ 调用方必须回退用落库的 `quotedContent` 脱敏快照。
     */
    val rawContent: String?,
)
