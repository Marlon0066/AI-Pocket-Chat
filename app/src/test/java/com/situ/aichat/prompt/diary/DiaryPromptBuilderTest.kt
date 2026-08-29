package com.situ.aichat.prompt.diary

import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.util.DateFormatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.ZoneId
import org.junit.Test

/**
 * 1:1 结构校验 [DiaryPromptBuilder.buildSystemPrompt]（对齐 iOS `DiaryPromptBuilder.buildSystemPrompt`）。
 * 用「哨兵值」字符串（每个字段唯一）+ 整串逐行断言，能抓出 section 顺序错乱 / 字段错位 / 可选段漏现/误现 /
 * 覆盖未生效 / 礼物段误加 `##` / 额外规则未加 `-` 前缀 等移植 bug。
 */
class DiaryPromptBuilderTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val nowMillis = 1_700_000_000_000L // 固定时刻，配 UTC → date 串确定

    /** 哨兵串：每个字段一个唯一值；含参字段保留 %1$s 占位（builder 用 format 填）。 */
    private fun sentinelStrings() = DiaryPromptStrings(
        intro = "intro<%1\$s>",
        requirementsHeader = "REQH",
        firstPerson = "FP",
        styleDefault = "SD",
        wordCount = "WC<%1\$s>",
        emoji = "EM",
        events = "EV",
        chatMention = "CM",
        innerVoice = "IV",
        noAi = "NA",
        shortOk = "SO",
        personaHeader = "PH",
        personaCity = "PC<%1\$s>",
        chatSummaryHeader = "CSH",
        chatGroupHeader = "CGH<%1\$s>",
        scheduleHeader = "SCH",
        currentTime = "CT<%1\$s>",
        outputOnly = "OO",
        moodHeader = "MH",
        moodOutputRule = "MOR",
        userMessage = "UM",
        guideHeader = "GH",
        guideLead = "GL",
        guideEvent = "GE<%1\$s>",
        guideFeeling = "GF<%1\$s>",
        guideUnsaid = "GU<%1\$s>",
        roleMe = "ME",
        roleOther = "OT",
        chatLine = "[%1\$s] %2\$s: %3\$s",
        calendarLine = "%1\$s-%2\$s %3\$s",
        eventUntitled = "UNTITLED",
        userFallback = "FALLBACK",
        photosBlind = "photos<%1\$d>",
    )

    @Test fun `minimal — no overrides, all optional sections empty`() {
        val out = DiaryPromptBuilder.buildSystemPrompt(
            strings = sentinelStrings(),
            userName = "U",
            nowMillis = nowMillis,
            zone = zone,
            chatSummary = "",
            calendarSummary = "",
        )
        val dateStr = DateFormatters.yearMonthDayHourMinuteWithWeekday(nowMillis, zone)
        val expected = listOf(
            "intro<U>",              // 入戏开场·顶部
            "",
            "CT<$dateStr>",          // 无任何素材 → 直接到当前时间（含周几）
            "",
            "REQH",                  // 要求段·底部
            "FP",                    // 默认人称（无覆盖）
            "SD",                    // 默认风格
            "WC<1000>",              // 默认字数范围（2026-07-13：1000）
            "EM", "EV", "CM", "IV", "NA", "SO",
            "",
            "OO",
            "MOR",                   // MOOD 尾行输出指令恒在（outputOnly 的显式唯一例外）
        ).joinToString("\n")
        assertEquals(expected, out)
    }

    @Test fun `full — overrides + all optional sections present, requirements at bottom`() {
        val out = DiaryPromptBuilder.buildSystemPrompt(
            strings = sentinelStrings(),
            userName = "U",
            nowMillis = nowMillis,
            zone = zone,
            chatSummary = "CHAT",
            calendarSummary = "CAL",
            persona = "PERSONA",
            petSummary = "PET",
            giftInspiration = "GIFT",
            moodHint = "MOODHINT",
            overrides = mapOf(
                DiaryPromptField.WORD_COUNT_RANGE.raw to "10-20",
                DiaryPromptField.NARRATIVE_PERSON.raw to "NP",
                DiaryPromptField.STYLE_HINT.raw to "SH",
                DiaryPromptField.EXTRA_RULES.raw to "r1\nr2",
            ),
        )
        val dateStr = DateFormatters.yearMonthDayHourMinuteWithWeekday(nowMillis, zone)
        val expected = listOf(
            "intro<U>",
            "",
            "PH", "PERSONA", "",           // 关于我·紧跟入戏开场
            "MH", "MOODHINT", "",          // 今日心情
            "CSH", "CHAT", "",             // 聊天摘要
            "SCH", "CAL", "",              // 日程
            "## Pet Status", "PET", "",    // 宠物段标题固定英文
            "GIFT", "",                    // 礼物灵感段无 ## 标题
            "CT<$dateStr>",                // 当前时间（含周几）
            "",
            "REQH",                        // 要求段·底部
            "- NP",                        // narrativePerson 覆盖 → 取代默认 FP
            "- SH",                        // styleHint 覆盖 → 取代默认 SD
            "WC<10-20>",                   // 字数覆盖
            "EM", "EV", "CM", "IV", "NA", "SO",
            "- r1", "- r2",                // 额外规则逐行加 - 前缀
            "",
            "OO",
            "MOR",
        ).joinToString("\n")
        assertEquals(expected, out)
    }

    @Test fun `guide — three-question answers inject right after intro, empties skipped (U2①)`() {
        val out = DiaryPromptBuilder.buildSystemPrompt(
            strings = sentinelStrings(),
            userName = "U",
            nowMillis = nowMillis,
            zone = zone,
            chatSummary = "",
            calendarSummary = "",
            guide = DiaryGuideAnswers(event = "去了旧书店", feeling = "  ", unsaid = "谢谢你"),
        )
        val dateStr = DateFormatters.yearMonthDayHourMinuteWithWeekday(nowMillis, zone)
        val expected = listOf(
            "intro<U>",
            "",
            "GH",                       // 引导段标题（顶部·贴用户本意·persona 为空故紧跟 intro）
            "GL",                       // 前导
            "GE<去了旧书店>\nGU<谢谢你>",   // 只非空答案·空白 feeling 跳过·事→未说序
            "",
            "CT<$dateStr>",
            "",
            "REQH",                     // 要求段·底部
            "FP", "SD", "WC<1000>", "EM", "EV", "CM", "IV", "NA", "SO",
            "",
            "OO",
            "MOR",
        ).joinToString("\n")
        assertEquals(expected, out)
    }

    @Test fun `blank overrides fall back to defaults (空白覆盖等同未设置)`() {
        val out = DiaryPromptBuilder.buildSystemPrompt(
            strings = sentinelStrings(),
            userName = "U",
            nowMillis = nowMillis,
            zone = zone,
            chatSummary = "",
            calendarSummary = "",
            overrides = mapOf(
                DiaryPromptField.NARRATIVE_PERSON.raw to "   ",
                DiaryPromptField.STYLE_HINT.raw to "",
                DiaryPromptField.WORD_COUNT_RANGE.raw to "  ",
            ),
        )
        // 空白/空覆盖 → 用默认 FP/SD/1000
        assert(out.contains("\nFP\nSD\nWC<1000>\n")) { "blank overrides should fall back to defaults; got:\n$out" }
    }

    @Test fun `persona injected right after intro, requirements stay at bottom`() {
        val out = DiaryPromptBuilder.buildSystemPrompt(
            strings = sentinelStrings(), userName = "U", nowMillis = nowMillis, zone = zone,
            chatSummary = "", calendarSummary = "", persona = "我是个爱猫的人。",
        )
        val lines = out.lines()
        assertEquals("intro<U>", lines[0])
        assertEquals("PH", lines[2])                 // personaHeader 紧跟入戏开场
        assertEquals("我是个爱猫的人。", lines[3])
        assertTrue("要求段在时间之后（底部）", out.indexOf("CT<") < out.indexOf("REQH"))
        assertTrue("persona 在要求段之前（顶部）", out.indexOf("PH") < out.indexOf("REQH"))
    }

    // ── 涟漪②·§3.9 见面提及（T2-6·E14） ──

    private fun meetingRow(charUuid: String, startedAt: Long, location: String, activity: String, summary: String) =
        OfflineMeetingMemoryEntity(
            uuid = "u-$charUuid", characterUuid = charUuid, sessionId = "s-$charUuid", kindRaw = "meeting",
            startedAtMillis = startedAt, location = location, activity = activity, summary = summary,
            createdAtMillis = 0L, updatedAtMillis = 0L,
        )

    @Test fun `meetingSummary 注入日历段之后 含标题与引导`() {
        val out = DiaryPromptBuilder.buildSystemPrompt(
            strings = sentinelStrings(), userName = "U", nowMillis = nowMillis, zone = zone,
            chatSummary = "", calendarSummary = "CALX",
            meetingSummary = "「15:30 与小雨在公园散步，很开心。」",
        )
        assertTrue(out.contains("## 今天的见面"))
        assertTrue(out.contains("「15:30 与小雨在公园散步，很开心。」"))
        assertTrue(out.contains("（这次见面对你今天的心情很重要，日记里自然地写到它。）"))
        assertTrue("见面段应在日历段之后", out.indexOf("CALX") < out.indexOf("## 今天的见面"))
    }

    @Test fun `meetingSummary 空 不注入`() {
        val out = DiaryPromptBuilder.buildSystemPrompt(
            strings = sentinelStrings(), userName = "U", nowMillis = nowMillis, zone = zone,
            chatSummary = "", calendarSummary = "", meetingSummary = "",
        )
        assertFalse(out.contains("## 今天的见面"))
    }

    @Test fun `formatDiaryMeetingLines 两次见面两行 取首句 空活动直跟摘要`() {
        val rows = listOf(
            meetingRow("c1", 0L, "公园", "散步", "很开心的一次。还聊了很多别的。"),
            meetingRow("c2", 3_600_000L, "咖啡馆", "", "安静地待着"),
        )
        val lines = formatDiaryMeetingLines(rows, mapOf("c1" to "小雨", "c2" to "阿哲"), zone).split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("与小雨在公园散步，很开心的一次。"))
        assertFalse("首句「。」后应截断", lines[0].contains("还聊了很多"))
        assertTrue(lines[1].contains("与阿哲在咖啡馆，安静地待着")) // 活动空 → 「在咖啡馆」后直跟摘要
    }

    @Test fun `formatDiaryMeetingLines 无见面 返回空`() {
        assertEquals("", formatDiaryMeetingLines(emptyList(), emptyMap(), zone))
    }

    // ── 契约 §B8：「AI 帮我写」的盲图提示（R2 🟡-4 补实现·R3 🟡-4 补这两条锁） ──

    @Test fun `已贴照片时注入盲图提示_带真实张数`() {
        // 病灶：用户先贴 9 张海边照再点「AI 帮我写」，生成的正文对照片完全无感知，只复述当天聊天记录。
        val out = DiaryPromptBuilder.buildSystemPrompt(
            strings = sentinelStrings(), userName = "U", nowMillis = nowMillis, zone = zone,
            chatSummary = "", calendarSummary = "", photoCount = 3,
        )
        assertTrue("张数必须是真的传进去的那个数", out.contains("photos<3>"))
    }

    @Test fun `没贴照片时整段不注入`() {
        // 反向钉：默认 0 张时若也注入，角色会凭空以为每篇日记都有照片
        val out = DiaryPromptBuilder.buildSystemPrompt(
            strings = sentinelStrings(), userName = "U", nowMillis = nowMillis, zone = zone,
            chatSummary = "", calendarSummary = "", photoCount = 0,
        )
        assertFalse(out.contains("photos<"))
    }
}
