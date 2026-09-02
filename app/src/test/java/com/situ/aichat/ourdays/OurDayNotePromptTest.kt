package com.situ.aichat.ourdays

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.prompt.DirtyMessageDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-6（卷一图纸 §7.2）：手记提示词装配。system 文本在此**重新打字**（总图纸 §4.1 逐字·三组占位符替换）；
 * 昵称双轨（E27）/ 人设截 300（E28）/ 段省略与聊天空替换行 / 素材头 120 尾 80 + 省略行 / 6000 字按行截尾（E29）/ 剥脏。
 */
class OurDayNotePromptTest {

    private fun msg(i: Int, content: String = "第${i}句", role: String = if (i % 2 == 0) "user" else "assistant") = MessageEntity(
        messageUUID = "m$i", conversationUuid = "conv", roleRaw = role, content = content, timestamp = 1_700_000_000_000L + i * 60_000L,
    )

    @Test
    fun system逐字_占位符替换() {
        val expected = listOf(
            "你是林晚。下面是2026年9月2日（周三）这一天，你和小明之间真实发生过的事（来自系统记录）和你们当天的聊天记录。请以林晚本人的身份，为这一天写两样东西，只输出一个 JSON 对象：",
            "",
            "{\"note\": \"……\", \"factLine\": \"……\"}",
            "",
            "一、note（手记）",
            "- 这是你写给自己看的一天手记，第一人称「我」，语气和平时的你一致；对方直接称「小明」。",
            "- 120–200 字，写这一天里真正打动你、或你记得最清楚的一两个瞬间；有细节、有你的感受，不空泛、不升华。",
            "- 只写当天真实发生的事：聊天里说过的、系统记录里列出的。没发生的不写，不编造对话。",
            "- 不要复述约定清单，不要复述见面的完整经过（它们各有专门记录），提一句即可。",
            "- 不要出现「用户」「角色」「AI」「系统」这类词，不要提「记录」「日志」。",
            "",
            "二、factLine（事实行）",
            "- 一行、不超过 60 字、不换行。第三人称，两个人一律用名字：「林晚」和「小明」。",
            "- 只写这一天最重要的事实：聊了什么主题、发生了什么事、有什么结果。像给日后翻查的备忘。",
            "- 不要写日期（系统会加），不要评价，不要感叹。",
            "",
            "补充要求：",
            "- 严格输出 JSON，不要加代码块标记，不要有其它文字。",
            "- 如果这一天几乎没有内容（只有几句寒暄），note 也要写，但可以短到 60 字，写这种平淡本身。",
        ).joinToString("\n")
        assertEquals(expected, OurDayNotePrompt.buildSystem("林晚", "小明", "小明", "2026年9月2日", "周三"))
    }

    @Test
    fun 昵称空_手记用你_事实行用用户_E27() {
        assertEquals("你", OurDayNotePrompt.userCallName(""))
        assertEquals("用户", OurDayNotePrompt.userRefName("   "))
        assertEquals("小明", OurDayNotePrompt.userCallName(" 小明 "))
        assertEquals("小明", OurDayNotePrompt.userRefName("小明"))
        val system = OurDayNotePrompt.buildSystem("林晚", "你", "用户", "2026年9月2日", "周三")
        assertTrue(system.contains("对方直接称「你」。"))
        assertTrue(system.contains("两个人一律用名字：「林晚」和「用户」。"))
        assertFalse(system.contains("{用户"))
    }

    @Test
    fun user段_全有时四段空行相隔() {
        val user = OurDayNotePrompt.buildUser("2026年9月2日", "周三", " 温柔话少 ", "- 聊天：3 条，09:00–10:00", "[2026-09-02 09:00] 小明：早")
        assertEquals(
            "【日期】2026年9月2日 周三\n\n【你的人设要点】温柔话少\n\n【这一天的记录】\n- 聊天：3 条，09:00–10:00\n\n【当天的聊天记录】\n[2026-09-02 09:00] 小明：早",
            user,
        )
    }

    @Test
    fun user段_人设与记录空则省略_聊天空替换为固定行() {
        val user = OurDayNotePrompt.buildUser("2026年9月2日", "周三", "   ", "", "")
        assertEquals("【日期】2026年9月2日 周三\n\n【当天的聊天记录】\n（这一天没有文字聊天）", user)
    }

    @Test
    fun 人设截300码点_E28() {
        val persona = "性".repeat(350)
        val user = OurDayNotePrompt.buildUser("2026年9月2日", "周三", persona, "", "x")
        val line = user.lines().first { it.startsWith("【你的人设要点】") }.removePrefix("【你的人设要点】")
        assertEquals(300, line.codePointCount(0, line.length))
        // 含代理对（emoji）也按码点数
        val emojiPersona = "😊".repeat(310)
        val u2 = OurDayNotePrompt.buildUser("2026年9月2日", "周三", emojiPersona, "", "x")
        val l2 = u2.lines().first { it.startsWith("【你的人设要点】") }.removePrefix("【你的人设要点】")
        assertEquals(300, l2.codePointCount(0, l2.length))
    }

    @Test
    fun 素材201条_头120尾80加省略行_E29() {
        val messages = (1..201).map { msg(it) }
        val text = OurDayNotePrompt.conversationExcerpt(messages, "小明", "林晚")
        val lines = text.lines()
        assertEquals(120 + 1 + 80, lines.size)
        assertEquals("（中间省略 1 条）", lines[120])
        assertTrue(lines[119].endsWith("第120句"))
        assertTrue(lines[121].endsWith("第122句"))
        assertFalse("被省略的第 121 条不出现", text.contains("第121句"))
        assertTrue(lines.first().contains("小明：") || lines.first().contains("林晚："))
    }

    @Test
    fun 素材200条不省略() {
        val text = OurDayNotePrompt.conversationExcerpt((1..200).map { msg(it) }, "小明", "林晚")
        assertEquals(200, text.lines().size)
        assertFalse(text.contains("中间省略"))
    }

    @Test
    fun 超6000码点按行截尾_不切半行_E29() {
        val messages = (1..60).map { msg(it, content = "字".repeat(150)) } // 每行 ≈ 170 码点 ⇒ 总量 > 6000
        val full = OurDayNotePrompt.conversationExcerpt(messages.take(0) + messages, "小明", "林晚")
        assertTrue(full.codePointCount(0, full.length) <= 6000)
        val untrimmed = com.situ.aichat.prompt.memory.MemoryService.formatMessages(messages, "小明", "林晚").lines()
        val kept = full.lines()
        assertTrue("至少保留一行", kept.isNotEmpty())
        assertTrue("截掉了若干行", kept.size < untrimmed.size)
        assertEquals("保留的行 = 原文前 N 整行（不切半行）", untrimmed.take(kept.size), kept)
        // 再加一行就超：证明是「删到刚好 ≤ 6000」而非过度删
        val plusOne = (kept + untrimmed[kept.size]).joinToString("\n")
        assertTrue(plusOne.codePointCount(0, plusOne.length) > 6000)
    }

    @Test
    fun 空素材返空串() {
        assertEquals("", OurDayNotePrompt.conversationExcerpt(emptyList(), "小明", "林晚"))
    }

    @Test
    fun 素材经formatMessages剥脏() {
        val dirty = "我发出了一张线下见面邀请，快来"
        assertTrue("前提：样例必须真被判脏", DirtyMessageDetector.isDirty(dirty, MessageKind.PLAIN_TEXT))
        val messages = listOf(msg(1, "早呀"), msg(2, dirty), msg(3, "晚安"))
        val text = OurDayNotePrompt.conversationExcerpt(messages, "小明", "林晚")
        assertFalse(text.contains("线下见面邀请"))
        assertTrue(text.contains("早呀") && text.contains("晚安"))
        assertEquals(2, text.lines().size)
    }

    @Test
    fun 素材用参照名与角色名渲染说话人() {
        val text = OurDayNotePrompt.conversationExcerpt(listOf(msg(2, "早呀", role = "user"), msg(3, "早", role = "assistant")), "小明", "林晚")
        val lines = text.lines()
        assertTrue(lines[0].contains(" 小明：早呀"))
        assertTrue(lines[1].contains(" 林晚：早"))
    }
}
