package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.model.PersonaVocab
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 活人感内核·卷三《场内核与渲染收编》T1-9（图纸 §7.2 · E29）：`gain_hits` / `custom_hits` 的**格式锁**。
 *
 * 生成端（`buildAnalysisPrompt` 的「### 敏感点命中规则」段 + 「## 输出格式」两行）与解析端（`parseAnalysisResponse`）
 * 在同一个类里（图纸 §6.1）——任一侧改了键名 / 值域 / 形状而另一侧没跟，这条测试就该红。断言从图纸 §3.3 独立反推：
 * - 提示词含两行输出字面、含 27 行 `- gNN 标签`、专属项行只在传入清单非空时出现
 * - `gain_hits` 过滤 ∈ GAIN_KEYS（大小写敏感）、去重、截 8
 * - `custom_hits` label trim + 忽略大小写对上传入清单（回填原文），tone 恰为 pos/neg，否则整条丢
 * - 两字段缺席 / 非法形状 ⇒ 空列表且**绝不抛**；旧响应（卷二形状）其余字段解析结果与卷二完全相同
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GainHitsParseTest {

    private val service = GrowthAnalysisService(
        contextLog = mockk<ContextLogService>(relaxed = true),
        conversationDao = mockk<ConversationDao>(relaxed = true),
        messageDao = mockk<MessageDao>(relaxed = true),
        scheduleDao = mockk<ScheduleDao>(relaxed = true),
    )

    private fun systemPrompt(customLabels: List<String> = emptyList()): String = service.buildAnalysisPrompt(
        messages = emptyList(), characterName = "林晚", spectrum = PersonalitySpectrum.NEUTRAL,
        quality = RelationshipQuality(), interests = emptyList(), userName = "小明", scheduleAnalysis = "",
        customGainLabels = customLabels,
    ).first

    private fun parse(extraJson: String, labels: List<String> = emptyList()) = service.parseAnalysisResponse(
        """{"events":[{"type":"majorEvent","summary":"x"}],"narrative":"n"$extraJson}""",
        labels,
    )

    // MARK: - 生成端字面

    @Test
    fun prompt_containsOutputFormatLines_andSensitivitySection() {
        val p = systemPrompt()
        assertTrue(p.contains("\"gain_hits\": [\"g04\", \"g13\"],"))
        assertTrue(p.contains("\"custom_hits\": [{\"label\": \"怕黑\", \"tone\": \"neg\"}],"))
        assertTrue(p.contains("### 敏感点命中规则"))
        assertTrue(p.contains("- 下面是她可能在意的事。**这段对话里确实发生过的**才算命中，没发生的不要写；一次对话通常命中 0~3 项"))
        assertTrue(p.contains("- custom_hits 只能写上面列出的专属项，label 原文照抄；tone 写 pos（让她舒服的）或 neg（让她难受的）"))
        for (key in PersonaVocab.GAIN_KEYS) assertTrue("缺 $key 行", p.contains("\n- ${PersonaVocab.gainPromptLine(key)}\n"))
        assertTrue(p.contains("\n- g13 吵架 · 被凶\n"))
        // 段落顺序：关系规则 → 敏感点 → 兴趣规则；且 trimIndent 真剥掉了模板缩进（段标题在行首）。
        assertTrue(p.indexOf("### 关系变化规则") < p.indexOf("### 敏感点命中规则"))
        assertTrue(p.indexOf("### 敏感点命中规则") < p.indexOf("### 兴趣变化规则"))
        assertTrue(p.contains("\n### 敏感点命中规则\n"))
        assertFalse("无专属项时整行不出", p.contains("她的专属敏感点"))
        // 修缮卷 §3.6：删句 + 注意行改用段标题常量
        assertFalse("「正负都给得高」整句已删", p.contains("正负都给得高"))
        assertTrue(p.contains("- **关系变化要正负分开报**"))
        assertTrue(p.contains("- intent_status 只对「林晚当前挂着的意图」段里列出的意图作答，key 用每行末尾方括号里的英文；没有列出就写 {}"))
        assertTrue(p.contains("「林晚${IntentStatusParsing.SECTION_KEYWORD}」"))
    }

    // MARK: - 修缮卷 T1-11（E23 / E24 / 🔵-1）：前缀归一 + 丢弃计数 + tone 大小写

    @Test
    fun gainHits_prefixNormalized_andDroppedCounted_E23() {
        val r = parse(""","gain_hits":["G04","g4","g13 吵架 · 被凶","被夸"]""")
        assertEquals(listOf("g04", "g13"), r.gainHits)
        assertEquals("只有「被夸」认不出；g4 归一后与 G04 重复不计丢弃", 1, r.droppedHits)
    }

    @Test
    fun gainHits_droppedCounting_nonStringAndUnknownKeys_duplicatesNotCounted() {
        val r = parse(""","gain_hits":["g04", 7, null, {"x":1}, "g99", "g123", "g00", "g04", "bandUp", ""]""")
        assertEquals(listOf("g04"), r.gainHits)
        assertEquals("7 / null / 对象 / g99 / g123 / g00 / bandUp / 空串 = 8；重复 g04 不算", 8, r.droppedHits)
        assertEquals("非数组 ⇒ 空且 0", 0, parse(""","gain_hits":"g04"""").droppedHits)
        assertEquals(0, parse("").droppedHits)
    }

    @Test
    fun customHits_toneIsCaseInsensitive_E24() {
        val r = parse(""","custom_hits":[{"label":"怕黑","tone":"Neg"},{"label":"被叫全名","tone":" POS "}]""", listOf("怕黑", "被叫全名"))
        assertEquals(listOf(false, true), r.customHits.map { it.positive })
    }

    @Test
    fun prompt_listsCustomLabels_whenProvided() {
        val p = systemPrompt(listOf("怕黑", "被叫全名"))
        assertTrue(p.contains("\n- 她的专属敏感点（原文照抄标签）：怕黑、被叫全名\n"))
        // 专属项行紧跟 27 行之后、custom_hits 规则行之前
        assertTrue(p.indexOf("- g27 被抛弃的信号") < p.indexOf("她的专属敏感点"))
        assertTrue(p.indexOf("她的专属敏感点") < p.indexOf("- custom_hits 只能写"))
    }

    // MARK: - gain_hits

    @Test
    fun gainHits_filterDistinctAndCap() {
        val r = parse(""","gain_hits":["g04","g99","g04","G13"," g13 ","bandUp",""]""")
        assertEquals(listOf("g04", "g13"), r.gainHits)
        val ten = (1..10).joinToString(",") { "\"g%02d\"".format(it) }
        assertEquals("截 8", 8, parse(""","gain_hits":[$ten]""").gainHits.size)
        assertEquals((1..8).map { "g%02d".format(it) }, parse(""","gain_hits":[$ten]""").gainHits)
    }

    @Test
    fun gainHits_absentOrMalformed_isEmpty_neverThrows() {
        assertTrue(parse("").gainHits.isEmpty())
        assertTrue(parse(""","gain_hits":"g04"""").gainHits.isEmpty())
        assertTrue(parse(""","gain_hits":{"g04":1}""").gainHits.isEmpty())
        assertTrue(parse(""","gain_hits":null""").gainHits.isEmpty())
        assertEquals(listOf("g04"), parse(""","gain_hits":["g04", 7, null, {"x":1}]""").gainHits)
    }

    // MARK: - custom_hits

    @Test
    fun customHits_matchLabelsIgnoringCaseAndTrim_andRequireValidTone() {
        // 修缮卷 E24 起 tone 忽略大小写（`POS` 也认），故「非法 tone」反例改用 `neutral`
        val r = parse(
            ""","custom_hits":[{"label":" 怕黑 ","tone":"neg"},{"label":"怕黑","tone":"neutral"},{"label":"不在清单","tone":"pos"},
               {"label":"BEING CALLED","tone":"pos"},{"label":"怕黑"},{"tone":"neg"},"怕黑",3]""",
            labels = listOf("怕黑", "being called"),
        )
        assertEquals(
            listOf(
                GrowthAnalysisResult.CustomHit("怕黑", positive = false),
                GrowthAnalysisResult.CustomHit("being called", positive = true),
            ),
            r.customHits,
        )
    }

    @Test
    fun customHits_absentMalformedOrNoLabelsProvided_isEmpty() {
        assertTrue(parse("").customHits.isEmpty())
        assertTrue(parse(""","custom_hits":{"label":"怕黑","tone":"neg"}""", listOf("怕黑")).customHits.isEmpty())
        assertTrue("没传清单 ⇒ 什么都对不上", parse(""","custom_hits":[{"label":"怕黑","tone":"neg"}]""").customHits.isEmpty())
    }

    // MARK: - 旧响应（卷二形状）零回归

    @Test
    fun legacyResponse_parsesExactlyAsBefore_withEmptyHits() {
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
    }
}
