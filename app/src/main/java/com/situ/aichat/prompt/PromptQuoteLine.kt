package com.situ.aichat.prompt

import java.time.Instant
import java.time.ZoneId

/**
 * 用户消息「引用上下文」旁注行的生成器（引用一期·图纸 2026-09-04 §3.2/§3.4·纯函数 T1）。
 *
 * 措辞甲案：`【{用户名}在回复你 {时间锚} 说的这句：「{正文}」】`——把「谁在回复谁的哪句话、那句话是什么时候说的」
 * 一次说清。人称用「你」而非双真名：本行嵌在 **user 回合**里，「你」本就指角色（同一条消息正文里的「那你几点
 * 下班？」用的是同一个「你」），零视角歧义；记忆 `project_memory_naming_thirdperson` 2026-08-31 的双名第三人称
 * 终拍只约束「嵌在 assistant 回合内的记录行」，与此不冲突（原则不变：**人称跟所在回合走**）。
 *
 * 时间锚必须**装 prompt 时按 now 现算**、绝不在发送时冻结：这条用户消息只要还留在窗口里，引用行每一轮都会被
 * 重建——发送当天冻成「今天 14:03」，第二天再喂一次就成了假信息。锚的格式直接借 [HistoryTimeDivider.formatLabel]
 * （今天 / 昨天 / M月D日 周X + HH:mm），与同一份提示词里的历史时间分割线同款，模型零学习成本。
 *
 * 返回串里嵌的是被引用消息的**原始 content**（含 `[sticker:xxx]` 标签）——调用方
 * [appendConversationMessages] 在本行注入**之后**才跑 `StickerService.convertStickerTagsToDescription`，
 * 于是引用到的表情会自动变成 `[非语言情绪：…]` 而不是零信息的 `[表情包]`。**两步的先后顺序是本设计的地基，
 * 不许调换**。
 */
internal object PromptQuoteLine {

    /** 引用正文长度上限（原 80·2026-09-04 用户拍板放宽）：超过则中间省略。 */
    const val MAX_QUOTED_CHARS = 300

    /** 超长时保留的头部字数。 */
    const val HEAD_CHARS = 160

    /** 超长时保留的尾部字数。 */
    const val TAIL_CHARS = 100

    /** 中间省略的连接串。 */
    const val JOINER = "…（中间略）…"

    private const val STICKER_TAG_OPEN = "[sticker:"

    /**
     * 拼一行引用旁注。
     *
     * @param userName 已解析好的用户昵称（空昵称回退「用户」由调用方经 `R.string.pb_user_fallback` 算好，
     *   与同函数内红包等文案共用同一个 `resolvedUserName`，不在此处再算一遍）
     * @param quotedContent 被引用消息的正文（优先传预取到的**原始 content**；原消息已删时回退传落库的 `quotedContent`）
     * @param quotedSenderRole 被引用消息的 role（`"user"` = 用户在引用自己的话）
     * @param quotedTimestampMillis 被引用消息的发生时刻；null = 查不到（uuid 为空 / 消息已删 / 该路径没预取）→ 无锚降级
     * @param now 当前真实时间；null = 调用方场景门控关掉了时间线（线下见面 / 语音通话 / 忙碌回复）→ 无锚降级
     */
    fun build(
        userName: String,
        quotedContent: String,
        quotedSenderRole: String?,
        quotedTimestampMillis: Long?,
        now: Instant?,
        zone: ZoneId,
    ): String {
        val target = if (quotedSenderRole == "user") "自己" else "你"
        val anchor = if (quotedTimestampMillis != null && now != null) {
            HistoryTimeDivider.formatLabel(quotedTimestampMillis, now, zone)
        } else {
            null
        }
        // 有锚：「在回复你 8月17日 周日 09:12 说的这句」；无锚：「在回复你先前说的这句」（逐字锁定·图纸 §3.2）。
        val middle = if (anchor != null) " $anchor " else "先前"
        return "【${userName}在回复${target}${middle}说的这句：「${truncate(quotedContent)}」】"
    }

    /**
     * 引用正文截断（图纸 §3.4）：≤ [MAX_QUOTED_CHARS] 原样（不加任何标记）；超长取头 [HEAD_CHARS] + [JOINER] +
     * 尾 [TAIL_CHARS]。**中间省略而不是从头截**——用户想引用的那个点不一定在开头（线下见面的整段叙事尤其）。
     */
    internal fun truncate(content: String): String {
        if (content.length <= MAX_QUOTED_CHARS) return content
        return headOf(content) + JOINER + tailOf(content)
    }

    /**
     * 头段 + 断标签守卫（复核 R1 🟡 修）：`take(HEAD_CHARS)` 若把一个 `[sticker:…]` 拦腰截断（头段里最后一个
     * `[sticker:` 在头段内找不到 `]`），从那个 `[` 处切掉。半截标签既转不成语义
     *（`convertStickerTagsToDescription` 认不出），又会把 `[sticker:abc` 这串内部实现直接漏给模型。
     *
     * ⚠️ 原实现把头尾拼好后只查**最后一个** `[sticker:`——尾段里但凡有一个完整标签，头段那个断标签就查不到、
     * 原样漏出去（复核实测：311 字含两个表情的消息即可复现）。故改为头尾**各自**守卫。
     */
    private fun headOf(content: String): String {
        val head = content.take(HEAD_CHARS)
        val open = head.lastIndexOf(STICKER_TAG_OPEN)
        if (open < 0) return head
        return if (head.indexOf(']', startIndex = open) < 0) head.take(open) else head
    }

    /**
     * 尾段 + 残片守卫：`takeLast(TAIL_CHARS)` 的起点若落在某个 `[sticker:…]` 中间，尾段会以 `ticker:abc]`
     * 这种残片开头。判据取自**原文**（起点之前最近的 `[sticker:` 其 `]` 落在起点之后），不靠「尾段里有没有 `]`」
     * 这种启发式——正文里本来就可能有中括号，误切会吃掉真内容。
     */
    private fun tailOf(content: String): String {
        val start = content.length - TAIL_CHARS
        val open = content.lastIndexOf(STICKER_TAG_OPEN, startIndex = start)
        if (open >= 0) {
            val close = content.indexOf(']', startIndex = open)
            if (close >= start) return content.substring(close + 1)
        }
        return content.substring(start)
    }
}
