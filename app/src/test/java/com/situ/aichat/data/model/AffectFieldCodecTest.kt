package com.situ.aichat.data.model

import com.situ.aichat.data.local.entity.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感内核·卷三《场内核与渲染收编》T1-1（图纸 §7.2 · E19 / E26）：[AffectField] 的编解码与访问器兜底。
 *
 * 断言从图纸 §3.1 的规格**独立反推**，不照抄实现：
 * - 默认值 `50 / 30 / 0 / 30`，预算与命中全空
 * - 往返：`encode → decodeAffectFieldOrNull` 逐字段相等（含 9 个命中与四枚时间戳）
 * - 空串 / 坏 JSON ⇒ `null`；访问器把两者**同路**回默认 `AffectField()`（K-11）
 * - 域外值钳位：security/investment/arousal `0..100`、valence `-100..100`、budgetUsed `0..40`、hits 截 9（修缮卷 6→9）、
 *   `slowDayUsed` 补齐 / 截为 2 项各钳 `0..15`（修缮卷 J3）
 * - 老 JSON 缺键 / 未来多键 ⇒ 不抛、缺键取默认（修缮卷三个新字段 `slowRefAt / slowDayUsed / pullbackDone` 回 0 / [0,0] / false·E1；
 *   内心行换气两个新字段 `slowBands / slowBandsAt` 回 [−1,−1] / [0,0]）
 * - `slowBands` 补齐 / 截为 2 项各钳 `−1..2`、`slowBandsAt` 补齐 / 截为 2 项（内心行换气微图纸 §2）
 * - 全字段 + 9 命中的编码长度 ≤ 357 字节（总图纸 §3.9 性能约束的落值上限·修缮卷实测 293 → 内心行换气按两新字段最长形态实测 357 重定）
 */
class AffectFieldCodecTest {

    private fun entity(json: String) = CharacterEntity(uuid = "c1", name = "林晚", creationDate = 0L, affectFieldJSON = json)

    // MARK: - 默认值

    @Test
    fun defaults_matchSpec() {
        val f = AffectField()
        assertEquals(50, f.security)
        assertEquals(30, f.investment)
        assertEquals(0, f.valence)
        assertEquals(30, f.arousal)
        assertEquals(0L, f.updatedAt)
        assertEquals(0L, f.budgetDayStart)
        assertEquals(0, f.budgetUsed)
        assertTrue(f.hits.isEmpty())
        assertEquals(0L, f.hitsAt)
        assertEquals(0L, f.slowRefAt)
        assertEquals(listOf(0, 0), f.slowDayUsed)
        assertEquals(false, f.pullbackDone)
        assertEquals("内心行换气：档未知", listOf(-1, -1), f.slowBands)
        assertEquals(listOf(0L, 0L), f.slowBandsAt)
        assertEquals(9, AffectField.MAX_HITS)
        assertEquals("bandUp", AffectField.BAND_UP)
        assertEquals(40, AffectField.DAILY_BUDGET)
        assertEquals(15, AffectField.FIELD_DAY_CAP)
    }

    // MARK: - 往返

    @Test
    fun roundTrip_preservesEveryField() {
        val original = AffectField(
            security = 83, investment = 91, valence = -57, arousal = 66,
            updatedAt = 1_700_000_123_456L, budgetDayStart = 1_699_977_600_000L, budgetUsed = 33,
            hits = listOf("g04", "g10", "g16", "g22", "g23", "g01", "g07", "g09", AffectField.BAND_UP), hitsAt = 1_700_000_000_000L,
            slowRefAt = 1_699_900_000_000L, slowDayUsed = listOf(7, 12), pullbackDone = true,
            slowBands = listOf(2, 0), slowBandsAt = listOf(1_699_950_000_000L, 1_699_960_000_000L),
        )
        val json = GrowthJson.encode(original)
        assertTrue("编码不得回落空串", json.isNotEmpty())
        assertEquals(original, GrowthJson.decodeAffectFieldOrNull(json))
    }

    @Test
    fun encodedSize_withNineHits_isAtMost357Bytes() {
        // 取每个字段的「最长形态」：三位数 / 负三位数 / 13 位时间戳 ×6 / 两位预算 / 9 个命中含字面 bandUp / 日帽两位 ×2 / false / 档 [−1,−1]。
        val worst = AffectField(
            security = 100, investment = 100, valence = -100, arousal = 100,
            updatedAt = 1_700_000_000_000L, budgetDayStart = 1_700_000_000_000L, budgetUsed = 40,
            hits = listOf("g27", "g26", "g25", "g24", "g23", "g22", "g21", "g20", AffectField.BAND_UP), hitsAt = 1_700_000_000_000L,
            slowRefAt = 1_700_000_000_000L, slowDayUsed = listOf(15, 15), pullbackDone = false,
            slowBands = listOf(-1, -1), slowBandsAt = listOf(1_700_000_000_000L, 1_700_000_000_000L),
        )
        val bytes = GrowthJson.encode(worst).toByteArray(Charsets.UTF_8).size
        assertTrue("全字段 + 9 命中编码 $bytes 字节，超过 357", bytes <= 357)
    }

    // MARK: - 空串 / 坏 JSON（E26）

    @Test
    fun emptyString_decodesToNull() {
        assertNull(GrowthJson.decodeAffectFieldOrNull(""))
    }

    @Test
    fun brokenJson_decodesToNull() {
        assertNull(GrowthJson.decodeAffectFieldOrNull("{这不是 JSON"))
        assertNull(GrowthJson.decodeAffectFieldOrNull("[1,2,3]"))
        assertNull(GrowthJson.decodeAffectFieldOrNull("null"))
    }

    @Test
    fun accessor_emptyAndBrokenColumn_bothFallBackToDefaultField() {
        assertEquals(AffectField(), entity("").affectField)
        assertEquals(AffectField(), entity("{坏").affectField)
        // 同路：两者产物逐字段相等（K-11：本列无派生源，默认值即正确兜底）。
        assertEquals(entity("").affectField, entity("{坏").affectField)
    }

    // MARK: - 域外值钳位

    @Test
    fun outOfRangeValues_areClampedIntoDomain() {
        val decoded = GrowthJson.decodeAffectFieldOrNull(
            """{"security":150,"investment":-9,"valence":-300,"arousal":101,"budgetUsed":99}""",
        )!!
        assertEquals(100, decoded.security)
        assertEquals(0, decoded.investment)
        assertEquals(-100, decoded.valence)
        assertEquals(100, decoded.arousal)
        assertEquals(40, decoded.budgetUsed)
    }

    @Test
    fun valencePositiveOverflow_andNegativeBudget_areClamped() {
        val decoded = GrowthJson.decodeAffectFieldOrNull("""{"valence":250,"budgetUsed":-3}""")!!
        assertEquals(100, decoded.valence)
        assertEquals(0, decoded.budgetUsed)
    }

    @Test
    fun hits_areTruncatedToNine_keepingOrder() {
        val decoded = GrowthJson.decodeAffectFieldOrNull(
            """{"hits":["g01","g02","g03","g04","g05","g06","g07","g08","g09","g10","g11"]}""",
        )!!
        assertEquals(listOf("g01", "g02", "g03", "g04", "g05", "g06", "g07", "g08", "g09"), decoded.hits)
    }

    @Test
    fun slowDayUsed_isFittedToTwoItems_andClampedInto0To15() {
        assertEquals("三项截两项且各钳 0..15", listOf(15, 0), GrowthJson.decodeAffectFieldOrNull("""{"slowDayUsed":[99,-4,7]}""")!!.slowDayUsed)
        assertEquals("一项右补 0", listOf(3, 0), GrowthJson.decodeAffectFieldOrNull("""{"slowDayUsed":[3]}""")!!.slowDayUsed)
        assertEquals("空列表补两个 0", listOf(0, 0), GrowthJson.decodeAffectFieldOrNull("""{"slowDayUsed":[]}""")!!.slowDayUsed)
    }

    @Test
    fun slowBands_isFittedToTwoItems_andClampedIntoMinus1To2_slowBandsAtFittedToTwo() {
        // 内心行换气：档三项截两项且各钳 −1..2；时刻三项截两项、一项右补 0、空列表补两个 0
        val decoded = GrowthJson.decodeAffectFieldOrNull("""{"slowBands":[9,-7,1],"slowBandsAt":[5,6,7]}""")!!
        assertEquals(listOf(2, -1), decoded.slowBands)
        assertEquals(listOf(5L, 6L), decoded.slowBandsAt)
        assertEquals("一项右补未知", listOf(1, -1), GrowthJson.decodeAffectFieldOrNull("""{"slowBands":[1]}""")!!.slowBands)
        assertEquals("空列表补两个未知", listOf(-1, -1), GrowthJson.decodeAffectFieldOrNull("""{"slowBands":[]}""")!!.slowBands)
        assertEquals(listOf(9L, 0L), GrowthJson.decodeAffectFieldOrNull("""{"slowBandsAt":[9]}""")!!.slowBandsAt)
        assertEquals(listOf(0L, 0L), GrowthJson.decodeAffectFieldOrNull("""{"slowBandsAt":[]}""")!!.slowBandsAt)
    }

    // MARK: - 老 JSON 缺键 / 未来多键

    @Test
    fun missingKeys_takeDefaults_andUnknownKeysAreIgnored() {
        val decoded = GrowthJson.decodeAffectFieldOrNull("""{"valence":-70,"futureField":1}""")!!
        assertEquals(-70, decoded.valence)
        assertEquals(50, decoded.security)
        assertEquals(30, decoded.investment)
        assertEquals(30, decoded.arousal)
        assertTrue(decoded.hits.isEmpty())
        assertEquals(0L, decoded.hitsAt)
        // 修缮卷 E1：卷三老列没有这三个键 ⇒ 0 / [0,0] / false（首次 tick 才置 slowRefAt）。
        assertEquals(0L, decoded.slowRefAt)
        assertEquals(listOf(0, 0), decoded.slowDayUsed)
        assertEquals(false, decoded.pullbackDone)
        // 内心行换气：修缮卷老列没有这两个键 ⇒ 档未知 [−1,−1]、时刻 [0,0]（首次 tick 只记档不记时）
        assertEquals(listOf(-1, -1), decoded.slowBands)
        assertEquals(listOf(0L, 0L), decoded.slowBandsAt)
    }

    @Test
    fun accessor_decodesValidColumn() {
        val json = GrowthJson.encode(AffectField(valence = -70, hits = listOf("g13")))
        val f = entity(json).affectField
        assertEquals(-70, f.valence)
        assertEquals(listOf("g13"), f.hits)
    }
}
