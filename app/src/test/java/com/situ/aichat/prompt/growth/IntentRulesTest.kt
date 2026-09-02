package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感内核·卷四《意图队列 + 性格复盘》T1-2（图纸 §7.2 · §3.3 / §9.2）：[IntentRules] 的锁定值与三个只读纯函数。
 *
 * 断言从图纸 §3.3 三要素表 / §9.2 锁定数值**独立反推**（关键词在此重新打字为字面量·PITFALLS §1e）：
 * - 6 个 `key` 与 `@SerialName` 字面逐字相等（`Json.encodeToString` 去引号比对）、`fromKey` trim / 未知 ⇒ null
 * - 半衰期六值 = 表（24h·72h·48h·12h·24h·72h）且全在 `[12h, 7d]`；了结正压六值 = 表
 * - `effectiveStrength` 恰半（60 @ 1 半衰期 ⇒ 30；2 ⇒ 15）、`dt < 0` 不衰减（E51）
 * - `isLive` 三个条件各一反例（FADED / <15 / ≥7d）+ E51「bornAt 未来 ⇒ 不超时不衰减 ⇒ 仍 live」
 * - 三张关键词表每 kind 各一命中一未命中，且全表逐字锁定；全清词 5 个逐字
 */
class IntentRulesTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    private fun intent(
        kind: IntentKind = IntentKind.WANT_COMFORT,
        state: IntentState = IntentState.ACTIVE,
        strength: Int = 60,
        bornAt: Long = now,
        lastChangeAt: Long = now,
    ) = CharacterIntent(id = "x", kind = kind, state = state, strength = strength, bornAt = bornAt, lastChangeAt = lastChangeAt)

    // MARK: - key ↔ @SerialName

    @Test
    fun keys_matchSerialNameLiterals_bothWays() {
        val expected = mapOf(
            IntentKind.WANT_COMFORT to "wantComfort",
            IntentKind.WANT_APOLOGIZE to "wantApologize",
            IntentKind.WANT_PROBE to "wantProbe",
            IntentKind.WANT_HIDE to "wantHide",
            IntentKind.WANT_SHARE to "wantShare",
            IntentKind.WANT_CONFIRM to "wantConfirm",
        )
        assertEquals(6, IntentKind.entries.size)
        for (kind in IntentKind.entries) {
            assertEquals(expected.getValue(kind), kind.key)
            assertEquals("\"${kind.key}\"", Json.encodeToString(IntentKind.serializer(), kind))
            assertEquals(kind, IntentKind.fromKey(kind.key))
            assertEquals("fromKey 应 trim", kind, IntentKind.fromKey("  ${kind.key} \n"))
        }
        assertNull(IntentKind.fromKey("wantMoney"))
        assertNull(IntentKind.fromKey("WANTCOMFORT"))
        assertNull(IntentKind.fromKey(""))
    }

    // MARK: - 锁定值

    @Test
    fun lockedConstants_matchSpec() {
        assertEquals(3, IntentRules.QUEUE_CAP)
        assertEquals(24 * hour, IntentRules.COOLDOWN_MS)
        assertEquals(7 * day, IntentRules.TIMEOUT_MS)
        assertEquals(7 * day, IntentRules.RESIDUE_TTL_MS)
        assertEquals(15, IntentRules.FADE_MIN)
        assertEquals(2, IntentRules.MAX_BIRTHS_PER_ANALYSIS)
        assertEquals(45, IntentRules.INVEST_MID)
        assertEquals(40, IntentRules.SECURITY_LOW)
        assertEquals(60, IntentRules.SECURITY_PROBE_MAX)
        assertEquals(-3, IntentRules.VALENCE_COMFORT)
        assertEquals(3, IntentRules.VALENCE_SHARE)
        assertEquals(8, IntentRules.RECENT_ROUNDS_HINT)
        assertEquals("修缮卷 J4：全清只对 ≤12 字短消息", 12, IntentRules.CLEAR_MAX_LEN)
        // 内心行换气微图纸 §4 锁定值：FADED 同类冷却 3 天（单源 RelationshipBands）· 慢场句 TTL 3 天 · 台词每 3 天轮换 3 变体
        assertEquals(72 * hour, IntentRules.FADE_COOLDOWN_MS)
        assertEquals(72 * hour, RelationshipBands.FADE_COOLDOWN_MS)
        assertEquals(72 * hour, RelationshipBands.SLOW_SENTENCE_TTL_MS)
        assertEquals(3, RelationshipBands.SCRIPT_ROTATE_DAYS)
        assertEquals(3, RelationshipBands.SCRIPT_VARIANTS)
        assertTrue("FADED 冷却必须长于 RESOLVED 冷却（图纸目标：消退后不当周重生）", IntentRules.FADE_COOLDOWN_MS > IntentRules.COOLDOWN_MS)
        assertTrue("残留 TTL 不短于 FADED 冷却（残留句仍 7 天）", IntentRules.RESIDUE_TTL_MS >= IntentRules.FADE_COOLDOWN_MS)
    }

    @Test
    fun residueKinds_isEverythingExceptShare() {
        // 修缮卷 J5（用户拍板 ③）：负向五种留残留，想分享不留
        assertEquals(
            setOf(IntentKind.WANT_COMFORT, IntentKind.WANT_APOLOGIZE, IntentKind.WANT_PROBE, IntentKind.WANT_HIDE, IntentKind.WANT_CONFIRM),
            IntentRules.RESIDUE_KINDS,
        )
        assertFalse(IntentKind.WANT_SHARE in IntentRules.RESIDUE_KINDS)
    }

    @Test
    fun strengthForLevel_isFortyFiftySixty() {
        assertEquals(40, IntentRules.strengthForLevel(0))
        assertEquals(50, IntentRules.strengthForLevel(1))
        assertEquals(60, IntentRules.strengthForLevel(2))
        assertEquals("未知档位按正常", 50, IntentRules.strengthForLevel(99))
    }

    @Test
    fun birthRules_orderIsLocked() {
        assertEquals(
            listOf(
                IntentKind.WANT_COMFORT, IntentKind.WANT_APOLOGIZE, IntentKind.WANT_HIDE,
                IntentKind.WANT_CONFIRM, IntentKind.WANT_PROBE, IntentKind.WANT_SHARE,
            ),
            IntentRules.BIRTH_RULES.map { it.kind },
        )
    }

    @Test
    fun halfLives_matchTable_andStayInsideDomain() {
        assertEquals(24 * hour, IntentRules.halfLifeOf(IntentKind.WANT_COMFORT))
        assertEquals(72 * hour, IntentRules.halfLifeOf(IntentKind.WANT_APOLOGIZE))
        assertEquals(48 * hour, IntentRules.halfLifeOf(IntentKind.WANT_PROBE))
        assertEquals(12 * hour, IntentRules.halfLifeOf(IntentKind.WANT_HIDE))
        assertEquals(24 * hour, IntentRules.halfLifeOf(IntentKind.WANT_SHARE))
        assertEquals(72 * hour, IntentRules.halfLifeOf(IntentKind.WANT_CONFIRM))
        for (kind in IntentKind.entries) {
            val h = IntentRules.halfLifeOf(kind)
            assertTrue("$kind 半衰期 $h 越出 [12h, 7d]", h in (12 * hour)..(7 * day))
        }
    }

    @Test
    fun resolveBonus_matchesTable() {
        assertEquals("closeness" to 3, IntentRules.resolveBonus(IntentKind.WANT_COMFORT))
        assertEquals("trust" to 3, IntentRules.resolveBonus(IntentKind.WANT_APOLOGIZE))
        assertEquals("familiarity" to 2, IntentRules.resolveBonus(IntentKind.WANT_PROBE))
        assertEquals("respect" to 2, IntentRules.resolveBonus(IntentKind.WANT_HIDE))
        assertEquals("rapport" to 3, IntentRules.resolveBonus(IntentKind.WANT_SHARE))
        assertEquals("attachment" to 3, IntentRules.resolveBonus(IntentKind.WANT_CONFIRM))
    }

    // MARK: - 惰性衰减

    @Test
    fun effectiveStrength_halvesPerHalfLife_andNeverGrowsOnClockRollback() {
        val comfort = intent(IntentKind.WANT_COMFORT, strength = 60)   // 半衰期 24h
        assertEquals(60, IntentRules.effectiveStrength(comfort, now))
        assertEquals(30, IntentRules.effectiveStrength(comfort, now + 24 * hour))
        assertEquals(15, IntentRules.effectiveStrength(comfort, now + 48 * hour))
        assertEquals("时钟回拨不反向增长", 60, IntentRules.effectiveStrength(comfort, now - 5 * hour))
        assertEquals("道歉 72h 半衰：50 → 25", 25, IntentRules.effectiveStrength(intent(IntentKind.WANT_APOLOGIZE, strength = 50), now + 72 * hour))
        assertEquals("四舍五入：60 × 2^(-1.5) = 21.2 ⇒ 21", 21, IntentRules.effectiveStrength(comfort, now + 36 * hour))
    }

    // MARK: - isLive

    @Test
    fun isLive_threeLiveStates_areLive_resolvedAndFadedAreNot() {
        assertTrue(IntentRules.isLive(intent(state = IntentState.BUDDING), now))
        assertTrue(IntentRules.isLive(intent(state = IntentState.ACTIVE), now))
        assertTrue(IntentRules.isLive(intent(state = IntentState.EXPRESSED), now))
        assertFalse(IntentRules.isLive(intent(state = IntentState.RESOLVED, strength = 0), now))
        assertFalse(IntentRules.isLive(intent(state = IntentState.FADED, strength = 60), now))
    }

    @Test
    fun isLive_strengthBelowFifteen_orDecayedBelow_isNotLive() {
        assertFalse(IntentRules.isLive(intent(strength = 14), now))
        assertTrue(IntentRules.isLive(intent(strength = 15), now))
        // 60 经 3 个 24h 半衰期 = 7.5 ⇒ 8 < 15 ⇒ 消退线以下
        assertFalse(IntentRules.isLive(intent(strength = 60, bornAt = now - 3 * day, lastChangeAt = now - 3 * day), now))
    }

    @Test
    fun isLive_sevenDaysAfterBirth_isNotLive_evenIfStrengthRefreshed() {
        assertFalse(IntentRules.isLive(intent(bornAt = now - 7 * day, lastChangeAt = now), now))
        assertTrue(IntentRules.isLive(intent(bornAt = now - 7 * day + 1, lastChangeAt = now), now))
    }

    @Test
    fun isLive_bornInTheFuture_isNeitherTimedOutNorDecayed_E51() {
        assertTrue(IntentRules.isLive(intent(bornAt = now + day, lastChangeAt = now + day), now))
    }

    // MARK: - 全清词（逐字锁定·修缮卷 J4：表达 / 了结两张表已删）

    private val neutral = "今天天气很好"

    @Test
    fun clearAllWords_areExactlyFive() {
        assertEquals(listOf("没事了", "过去了", "不提了", "翻篇", "别放在心上"), IntentRules.CLEAR_ALL_WORDS)
        assertTrue(IntentRules.CLEAR_ALL_WORDS.any { "好啦没事了".contains(it) })
        assertFalse(IntentRules.CLEAR_ALL_WORDS.any { neutral.contains(it) })
    }
}
