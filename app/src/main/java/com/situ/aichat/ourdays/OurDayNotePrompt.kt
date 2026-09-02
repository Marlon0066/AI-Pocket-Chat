package com.situ.aichat.ourdays

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.takeCodePoints

/**
 * 手记生成提示词装配（总图纸 §4.1 · **逐字锁定** · 硬编码中文 = LLM 资产·非本地化）。
 *
 * 人称双轨（Z-10）：手记里对方 = 昵称，空 → 「你」（[userCallName]）；事实行里对方 = 昵称，空 → 「用户」（[userRefName]）。
 * 对话素材（Z-9）：≤ 200 条直接渲染；超出取头 [HEAD_COUNT] + 尾 [TAIL_COUNT] 并插省略行；再按 [MAX_CODE_POINTS] 逐行截尾。
 */
internal object OurDayNotePrompt {

    const val PERSONALITY_MAX_CODE_POINTS = 300
    const val EXCERPT_MAX_MESSAGES = 200
    const val HEAD_COUNT = 120
    const val TAIL_COUNT = 80
    const val MAX_CODE_POINTS = 6000

    /** 聊天段为空时的替换行（锁定文本）。 */
    const val NO_CHAT_LINE = "（这一天没有文字聊天）"

    private const val USER_CALL_FALLBACK = "你"
    private const val USER_REF_FALLBACK = "用户"

    /** 手记里的对方称呼：昵称 trim 非空取昵称，否则「你」。 */
    fun userCallName(nickname: String): String = nickname.trim().ifEmpty { USER_CALL_FALLBACK }

    /** 事实行里的对方指名：昵称 trim 非空取昵称，否则「用户」。 */
    fun userRefName(nickname: String): String = nickname.trim().ifEmpty { USER_REF_FALLBACK }

    /** system 模板（占位符 {角色名} {用户称呼} {用户参照名} {日期} {周几}·总图纸 §4.1 逐字）。 */
    const val SYSTEM_TEMPLATE: String =
        "你是{角色名}。下面是{日期}（{周几}）这一天，你和{用户称呼}之间真实发生过的事（来自系统记录）和你们当天的聊天记录。请以{角色名}本人的身份，为这一天写两样东西，只输出一个 JSON 对象：\n" +
            "\n" +
            "{\"note\": \"……\", \"factLine\": \"……\"}\n" +
            "\n" +
            "一、note（手记）\n" +
            "- 这是你写给自己看的一天手记，第一人称「我」，语气和平时的你一致；对方直接称「{用户称呼}」。\n" +
            "- 120–200 字，写这一天里真正打动你、或你记得最清楚的一两个瞬间；有细节、有你的感受，不空泛、不升华。\n" +
            "- 只写当天真实发生的事：聊天里说过的、系统记录里列出的。没发生的不写，不编造对话。\n" +
            "- 不要复述约定清单，不要复述见面的完整经过（它们各有专门记录），提一句即可。\n" +
            "- 不要出现「用户」「角色」「AI」「系统」这类词，不要提「记录」「日志」。\n" +
            "\n" +
            "二、factLine（事实行）\n" +
            "- 一行、不超过 60 字、不换行。第三人称，两个人一律用名字：「{角色名}」和「{用户参照名}」。\n" +
            "- 只写这一天最重要的事实：聊了什么主题、发生了什么事、有什么结果。像给日后翻查的备忘。\n" +
            "- 不要写日期（系统会加），不要评价，不要感叹。\n" +
            "\n" +
            "补充要求：\n" +
            "- 严格输出 JSON，不要加代码块标记，不要有其它文字。\n" +
            "- 如果这一天几乎没有内容（只有几句寒暄），note 也要写，但可以短到 60 字，写这种平淡本身。"

    fun buildSystem(characterName: String, userCallName: String, userRefName: String, dateCn: String, weekdayCn: String): String =
        SYSTEM_TEMPLATE
            .replace("{角色名}", characterName)
            .replace("{用户称呼}", userCallName)
            .replace("{用户参照名}", userRefName)
            .replace("{日期}", dateCn)
            .replace("{周几}", weekdayCn)

    /**
     * user 段（各段空则整段省略·段间空一行）：【日期】恒在；【你的人设要点】= 人设去首尾空白后前 300 个字符（空省略）；
     * 【这一天的记录】= [OurDayFactsRenderer.render] 输出（空省略）；【当天的聊天记录】= 素材，空则整段替换为 [NO_CHAT_LINE]。
     */
    fun buildUser(dateCn: String, weekdayCn: String, personality: String, factsText: String, conversationText: String): String {
        val sections = mutableListOf("【日期】$dateCn $weekdayCn")
        val persona = personality.trim().takeCodePoints(PERSONALITY_MAX_CODE_POINTS)
        if (persona.isNotEmpty()) sections += "【你的人设要点】$persona"
        if (factsText.isNotEmpty()) sections += "【这一天的记录】\n$factsText"
        sections += "【当天的聊天记录】\n" + conversationText.ifEmpty { NO_CHAT_LINE }
        return sections.joinToString("\n\n")
    }

    /**
     * 对话素材（Z-9）：[messages] 为当天 `[start, end)` 升序消息（DAO 已限 2000）。> 200 条 ⇒ 头 120 + 尾 80，中间插一行
     * `（中间省略 N 条）`；渲染经 [MemoryService.formatMessages]（剥脏 + safeText 单源）；再按 codePoint > 6000 ⇒ 逐**行**从尾删。
     * 空 ⇒ `""`（由 [buildUser] 替换为 [NO_CHAT_LINE]）。
     */
    fun conversationExcerpt(messages: List<MessageEntity>, userRefName: String, characterName: String): String {
        if (messages.isEmpty()) return ""
        val text = if (messages.size > EXCERPT_MAX_MESSAGES) {
            val head = MemoryService.formatMessages(messages.take(HEAD_COUNT), userRefName, characterName)
            val tail = MemoryService.formatMessages(messages.takeLast(TAIL_COUNT), userRefName, characterName)
            "$head\n（中间省略 ${messages.size - EXCERPT_MAX_MESSAGES} 条）\n$tail"
        } else {
            MemoryService.formatMessages(messages, userRefName, characterName)
        }
        return trimToCodePointsByLine(text, MAX_CODE_POINTS)
    }

    /** 按行从尾部删，直到 codePoint 数 ≤ [max]。 */
    internal fun trimToCodePointsByLine(text: String, max: Int): String {
        if (text.codePointCount(0, text.length) <= max) return text
        val lines = text.lines().toMutableList()
        var current = text
        while (lines.isNotEmpty() && current.codePointCount(0, current.length) > max) {
            lines.removeAt(lines.lastIndex)
            current = lines.joinToString("\n")
        }
        return current
    }
}
