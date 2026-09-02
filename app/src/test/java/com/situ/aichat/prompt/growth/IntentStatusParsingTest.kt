package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感内核·卷四《意图队列 + 性格复盘》T1-4（图纸 §7.2 · §3.5 · E37）：层 ② 意图段的**格式锁**——生成侧 [IntentStatusParsing.section]
 * 与解析侧 [IntentStatusParsing.parse] 同文件同对。
 *
 * 断言从图纸 §3.5 逐字反推（段形状 / `今天萌生` / `3 天前萌生` / `活跃中` / `已表达但未了结` / `[wantApologize]` 尾标 / 末两行）：
 * - 空 ⇒ `""`；只列 live 条目；按 effective 强度降序
 * - `parse` 三值 / 大小写与空白 / 未知 key / 未知值 / 非对象 / 缺席 ⇒ 全部不抛
 */
class IntentStatusParsingTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun intent(
        kind: IntentKind,
        state: IntentState = IntentState.ACTIVE,
        strength: Int = 50,
        bornAt: Long = now,
        lastChangeAt: Long = now,
    ) = CharacterIntent(id = kind.key, kind = kind, state = state, strength = strength, bornAt = bornAt, lastChangeAt = lastChangeAt)

    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    // MARK: - 生成侧

    @Test
    fun section_isVerbatim_withKeyTags_andLastTwoLines() {
        val out = IntentStatusParsing.section(
            listOf(
                intent(IntentKind.WANT_APOLOGIZE, bornAt = now - 3 * day, strength = 50),
                intent(IntentKind.WANT_COMFORT, state = IntentState.EXPRESSED, bornAt = now, strength = 60),
            ),
            charName = "林悦", userName = "小明", now = now,
        )
        assertEquals(
            listOf(
                "【林悦当前挂着的意图】",
                "- 林悦想被小明哄一哄（今天萌生，已表达但未了结）[wantComfort]",
                "- 林悦想向小明道歉（3 天前萌生，活跃中）[wantApologize]",
                "请重点看最近 8 轮，判断这些意图有没有了结。",
                "了结 = 这件事在对话里被正面接住、说开了；只是提了一嘴算 expressed；什么都没发生算 open。",
            ).joinToString("\n"),
            out,
        )
    }

    @Test
    fun section_buddingReadsAsActive_and23HoursIsStillToday() {
        val out = IntentStatusParsing.section(
            listOf(intent(IntentKind.WANT_SHARE, state = IntentState.BUDDING, bornAt = now - 23 * 3_600_000L)),
            "林悦", "小明", now,
        )
        assertTrue(out, out.contains("- 林悦有件事想跟小明分享（今天萌生，活跃中）[wantShare]"))
    }

    @Test
    fun section_empty_orNoLive_isEmptyString() {
        assertEquals("", IntentStatusParsing.section(emptyList(), "林悦", "小明", now))
        assertEquals(
            "",
            IntentStatusParsing.section(
                listOf(
                    intent(IntentKind.WANT_HIDE, state = IntentState.FADED),
                    intent(IntentKind.WANT_PROBE, state = IntentState.RESOLVED, strength = 0),
                    intent(IntentKind.WANT_CONFIRM, strength = 10),
                ),
                "林悦", "小明", now,
            ),
        )
    }

    @Test
    fun section_ordersByEffectiveStrengthDescending() {
        val out = IntentStatusParsing.section(
            listOf(
                intent(IntentKind.WANT_COMFORT, strength = 30),
                intent(IntentKind.WANT_APOLOGIZE, strength = 60),
                intent(IntentKind.WANT_PROBE, strength = 45),
            ),
            "林悦", "小明", now,
        )
        val lines = out.lines()
        assertTrue(lines[1].endsWith("[wantApologize]"))
        assertTrue(lines[2].endsWith("[wantProbe]"))
        assertTrue(lines[3].endsWith("[wantComfort]"))
    }

    // MARK: - 解析侧（E37）

    @Test
    fun parse_acceptsThreeValues_storesCanonicalKey() {
        val map = IntentStatusParsing.parse(json("""{"wantApologize":"resolved","wantComfort":"expressed","wantShare":"open"}"""))
        assertEquals(mapOf("wantApologize" to "resolved", "wantComfort" to "expressed", "wantShare" to "open"), map)
    }

    @Test
    fun parse_normalizesCaseAndWhitespace_onKeyAndValue() {
        val map = IntentStatusParsing.parse(json("""{" wantApologize ":" Resolved\n"}"""))
        assertEquals(mapOf("wantApologize" to "resolved"), map)
    }

    @Test
    fun parse_dropsUnknownKeys_unknownValues_andNonStringValues() {
        val map = IntentStatusParsing.parse(
            json("""{"wantMoney":"resolved","wantApologize":"done","wantComfort":1,"wantHide":null,"wantShare":"resolved"}"""),
        )
        assertEquals(mapOf("wantShare" to "resolved"), map)
    }

    @Test
    fun parse_nonObject_orAbsent_isEmptyMap_neverThrows() {
        assertTrue(IntentStatusParsing.parse(null).isEmpty())
        assertTrue(IntentStatusParsing.parse(json("[]")).isEmpty())
        assertTrue(IntentStatusParsing.parse(json("\"resolved\"")).isEmpty())
        assertTrue(IntentStatusParsing.parse(json("null")).isEmpty())
        assertTrue(IntentStatusParsing.parse(json("{}")).isEmpty())
    }
}
