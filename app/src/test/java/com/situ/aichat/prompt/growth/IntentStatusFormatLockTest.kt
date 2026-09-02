package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 活人感内核·卷四《意图队列 + 性格复盘》T1-4 格式锁部分（图纸 §7.2 · §3.5 · 外部行为清单 2）：
 * `GrowthAnalysisService` 侧的 `intent_status` 生成 / 解析接线——与 [IntentStatusParsing] 是同一对（图纸 §6）。
 *
 * 断言从图纸 §3.5 逐字反推：
 * - system 框恒含 `"intent_status"` 输出格式行（`custom_hits` 之后、`events` 之前）与注意行（`所有文字用中文` 之前）
 * - `intentSection` 空时 userPrompt 与卷三公式**逐字节相同**；非空时以 `\n\n` + 段收尾
 * - `parseAnalysisResponse` 对含 `intent_status` 的响应回填 `intentStatus`；旧响应（无该键·复用 `GainHitsParseTest` 样本）其余字段与卷三相同且 `intentStatus` 空；坏形状绝不抛
 *
 * 单独成类（不并入纯 JVM 的 [IntentStatusParsingTest]）：构造真服务照 `GainHitsParseTest` 走 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentStatusFormatLockTest {

    private val service = GrowthAnalysisService(
        contextLog = mockk<ContextLogService>(relaxed = true),
        conversationDao = mockk<ConversationDao>(relaxed = true),
        messageDao = mockk<MessageDao>(relaxed = true),
        scheduleDao = mockk<ScheduleDao>(relaxed = true),
    )

    private val messages = listOf(
        MessageEntity(messageUUID = "u1", conversationUuid = "c", roleRaw = "user", content = "我今天有点累", timestamp = 1_000L),
        MessageEntity(messageUUID = "a1", conversationUuid = "c", roleRaw = "assistant", content = "那早点休息", timestamp = 2_000L),
    )

    private fun prompt(intentSection: String) = service.buildAnalysisPrompt(
        messages = messages, characterName = "林悦", spectrum = PersonalitySpectrum.NEUTRAL,
        quality = RelationshipQuality(), interests = emptyList(), userName = "小明", scheduleAnalysis = "\n\n【最近一周的日常活动模式】\n- 画画（3次）",
        intentSection = intentSection,
    )

    private val section = listOf(
        "【林悦当前挂着的意图】",
        "- 林悦想向小明道歉（3 天前萌生，活跃中）[wantApologize]",
        "请重点看最近 8 轮，判断这些意图有没有了结。",
        "了结 = 这件事在对话里被正面接住、说开了；只是提了一嘴算 expressed；什么都没发生算 open。",
    ).joinToString("\n")

    // MARK: - system 框

    @Test
    fun systemPrompt_hasIntentStatusOutputLine_betweenCustomHitsAndEvents() {
        val sys = prompt("").first
        val line = "\n  \"intent_status\": {\"意图key\": \"open 或 expressed 或 resolved\", ...},\n"
        assertTrue(sys, sys.contains(line))
        assertTrue(sys.indexOf("\"custom_hits\": [{\"label\": \"怕黑\", \"tone\": \"neg\"}],") < sys.indexOf("\"intent_status\""))
        assertTrue(sys.indexOf("\"intent_status\"") < sys.indexOf("\"events\": [{\"type\": \"personalityShift\""))
        assertEquals("恒在、只出现一次（静态·与 intentSection 无关）", 1, Regex("\"intent_status\"").findAll(sys).count())
        assertTrue(prompt(section).first == sys)
    }

    @Test
    fun systemPrompt_hasNoticeLine_beforeChineseOnlyLine() {
        val sys = prompt("").first
        // 修缮卷 §3.6：注意行改用段标题常量互指（角色名 + SECTION_KEYWORD），与 user 框段标题「【林悦当前挂着的意图】」同源
        val notice = "\n- intent_status 只对「林悦当前挂着的意图」段里列出的意图作答，key 用每行末尾方括号里的英文；没有列出就写 {}\n"
        assertTrue(sys, sys.contains(notice))
        assertEquals("当前挂着的意图", IntentStatusParsing.SECTION_KEYWORD)
        assertTrue(section.startsWith("【林悦" + IntentStatusParsing.SECTION_KEYWORD + "】"))
        assertTrue(sys.indexOf("- intent_status 只对") < sys.indexOf("- 所有文字用中文"))
        assertTrue(sys.indexOf("- events 的 summary 和 narrative") < sys.indexOf("- intent_status 只对"))
    }

    // MARK: - user 框

    @Test
    fun userPrompt_emptySection_isByteIdenticalToVolumeThreeFormula() {
        val conversationText = buildConversationText(messages, 0, "小明", "林悦")
        val expectedBefore = "以下是最近的对话记录，请分析角色的成长变化：\n\n$conversationText\n\n【最近一周的日常活动模式】\n- 画画（3次）"
        assertEquals(expectedBefore, prompt("").second)
        // 默认参与显式空串同路
        val byDefault = service.buildAnalysisPrompt(
            messages = messages, characterName = "林悦", spectrum = PersonalitySpectrum.NEUTRAL,
            quality = RelationshipQuality(), interests = emptyList(), userName = "小明",
            scheduleAnalysis = "\n\n【最近一周的日常活动模式】\n- 画画（3次）",
        ).second
        assertEquals(expectedBefore, byDefault)
    }

    @Test
    fun userPrompt_nonEmptySection_appendsWithTwoNewlines_atTheVeryEnd() {
        val before = prompt("").second
        val after = prompt(section).second
        assertEquals(before + "\n\n" + section, after)
        assertTrue(after.endsWith(section))
    }

    // MARK: - 解析侧

    private fun parse(extraJson: String) = service.parseAnalysisResponse(
        """{"events":[{"type":"majorEvent","summary":"x"}],"narrative":"n"$extraJson}""",
    )

    @Test
    fun parse_fillsIntentStatus_fromResponse() {
        val r = parse(""","intent_status":{"wantApologize":"resolved","wantMoney":"open","wantShare":" Expressed ","wantHide":"done"}""")
        assertEquals(mapOf("wantApologize" to "resolved", "wantShare" to "expressed"), r.intentStatus)
    }

    @Test
    fun parse_malformedIntentStatus_isEmpty_neverThrows() {
        assertTrue(parse("").intentStatus.isEmpty())
        assertTrue(parse(""","intent_status":"resolved"""").intentStatus.isEmpty())
        assertTrue(parse(""","intent_status":["wantApologize"]""").intentStatus.isEmpty())
        assertTrue(parse(""","intent_status":null""").intentStatus.isEmpty())
        assertTrue(parse(""","intent_status":{}""").intentStatus.isEmpty())
    }

    @Test
    fun legacyResponse_parsesExactlyAsBefore_withEmptyIntentStatus() {
        // 复用 GainHitsParseTest 的旧响应样本（卷二形状）：其余字段解析结果与卷三完全相同。
        val legacy = """{"personality_changes":{"warmth":3,"bogus":9},"relationship_changes":{"trust":{"pos":3,"neg":2},"fun":4},
            "new_interests":[{"name":"手冲咖啡"}],"interest_heat_changes":{"读书":6},
            "events":[{"type":"relationshipChange","summary":"更信任了"}],"narrative":"n"}"""
        val r = service.parseAnalysisResponse(legacy)
        assertEquals(mapOf("warmth" to 3), r.personalityChanges)
        assertEquals(GrowthAnalysisResult.PressureDelta(3, 2), r.relationshipChanges["trust"])
        assertEquals(GrowthAnalysisResult.PressureDelta(4, 0), r.relationshipChanges["fun"])
        assertEquals("手冲咖啡", r.newInterests.single().name)
        assertEquals(6, r.interestHeatChanges["读书"])
        assertEquals("更信任了", r.events.single().summary)
        assertEquals("n", r.narrative)
        assertTrue(r.gainHits.isEmpty())
        assertTrue(r.customHits.isEmpty())
        assertTrue(r.intentStatus.isEmpty())
    }
}
