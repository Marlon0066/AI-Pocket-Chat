package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.AffectField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 活人感内核·卷三《场内核与渲染收编》T1-2（图纸 §7.2 · E9 / E10 / E11 / E28）：场内核纯数学的逐点断言。
 *
 * 断言从图纸 §3.6 的四张点值表与公式**独立反推**（用 `6·tanh(|raw|/6)` / `0.15·|x−a|` 手算后再打字），不照抄实现：
 * - [saturate]：整数点值表全点 + 负侧对称 + `|raw| < 0.5 ⇒ 0`（废保底）+ 非整数例
 * - [springStep]：幅值点值 `≤3→0 · 4..9→1 · 10..16→2 · 17..23→3 · 24..29→4 · 30→5`，方向朝锚
 * - [guardBand]：界内钳住 / 界外只许往里（两侧）
 * - [relaxToward]：一个半衰期恰半、dt≤0 原样、长尾 snap 规则
 * - [arousalBaseline] 24 小时表；[dailyTilt] 同种子同值、域内、跨日会变；[scaleToBudget] E9；[rollDay] E10
 */
class AffectMathTest {

    // MARK: - saturate

    @Test
    fun saturate_integerPointTable() {
        val expected = mapOf(
            0 to 0, 1 to 1, 2 to 2, 3 to 3, 4 to 3, 5 to 4, 6 to 5, 7 to 5, 8 to 5, 9 to 5,
            10 to 6, 11 to 6, 12 to 6, 20 to 6,
        )
        for ((raw, want) in expected) {
            assertEquals("saturate($raw)", want, saturate(raw.toDouble()))
            assertEquals("saturate(-$raw) 幅值对称", -want, saturate(-raw.toDouble()))
        }
    }

    @Test
    fun saturate_nonIntegerExamples_andDeadZone() {
        assertEquals(1, saturate(1.25))
        assertEquals(1, saturate(1.5))    // 6·tanh(0.25) = 1.47
        assertEquals(2, saturate(2.4))
        assertEquals(6, saturate(10.8))
        assertEquals(-6, saturate(-10.8))
        // 废保底：|raw| < 0.5 ⇒ 0（内核路径上的 ±1 不再有「至少 ±1」）
        assertEquals(0, saturate(0.4))
        assertEquals(0, saturate(-0.4))
        assertEquals(0, saturate(0.0))
        assertEquals(1, saturate(0.6))
    }

    @Test
    fun saturate_neverExceedsSixInMagnitude() {
        for (raw in listOf(6.0, 30.0, 100.0, 1e6, -100.0)) {
            assertTrue("saturate($raw)", kotlin.math.abs(saturate(raw)) <= 6)
        }
    }

    // MARK: - springStep

    @Test
    fun springStep_magnitudeTable_andDirection() {
        val table = mapOf(0 to 0, 1 to 0, 3 to 0, 4 to 1, 9 to 1, 10 to 2, 16 to 2, 17 to 3, 23 to 3, 24 to 4, 29 to 4, 30 to 5)
        for ((dist, m) in table) {
            assertEquals("x 在锚点上方 $dist ⇒ 往下 $m", -m, springStep(50 + dist, 50))
            assertEquals("x 在锚点下方 $dist ⇒ 往上 $m", m, springStep(50 - dist, 50))
        }
    }

    // MARK: - guardBand（E11 / E28）

    @Test
    fun guardBand_insideBand_clampsToBand() {
        assertEquals(70, guardBand(before = 65, after = 75, anchor = 50))   // 想越上界 ⇒ 钳在 70
        assertEquals(30, guardBand(before = 35, after = 20, anchor = 50))   // 想越下界 ⇒ 钳在 30
        assertEquals(58, guardBand(before = 55, after = 58, anchor = 50))   // 界内自由
        assertEquals(70, guardBand(before = 70, after = 71, anchor = 50))   // 已在边界：不外移
    }

    @Test
    fun guardBand_outsideAbove_onlyMovesInward() {
        // E28：用户把锚点从 70 拖到 20，现值 70 离锚 50 ⇒ 界外；不瞬移到 40，只许在 [40, 70] 内往里走。
        assertEquals(66, guardBand(before = 70, after = 66, anchor = 20))   // 往里：允许
        assertEquals(70, guardBand(before = 70, after = 75, anchor = 20))   // 往外：钳回起点
        assertEquals(40, guardBand(before = 70, after = 30, anchor = 20))   // 一步跨过带：停在带边
    }

    @Test
    fun guardBand_outsideBelow_onlyMovesInward() {
        assertEquals(14, guardBand(before = 10, after = 14, anchor = 60))
        assertEquals(10, guardBand(before = 10, after = 5, anchor = 60))
        assertEquals(40, guardBand(before = 10, after = 55, anchor = 60))
    }

    // MARK: - relaxToward

    @Test
    fun relaxToward_oneHalfLife_isExactlyHalfway() {
        val h = 24L * 3_600_000L
        assertEquals(20, relaxToward(value = 40, target = 0, dtMs = h, halfLifeMs = h))
        assertEquals(-20, relaxToward(value = -40, target = 0, dtMs = h, halfLifeMs = h))
        assertEquals(40, relaxToward(value = 30, target = 50, dtMs = h, halfLifeMs = h))
        assertEquals(10, relaxToward(value = 40, target = 0, dtMs = 2 * h, halfLifeMs = h))
    }

    @Test
    fun relaxToward_zeroOrNegativeDt_returnsValue() {
        assertEquals(40, relaxToward(40, 0, 0L, 3_600_000L))
        assertEquals(40, relaxToward(40, 0, -5L, 3_600_000L))
        assertEquals(50, relaxToward(50, 50, 3_600_000L, 3_600_000L))
    }

    @Test
    fun relaxToward_snapRule_movesOneStepAfterAnHourWhenRoundingStalls() {
        val h24 = 24L * 3_600_000L
        // 1 → 0，1 小时后 r = 0.9715 取整仍是 1（停滞）且 dt ≥ 1h ⇒ 向目标走 1 ⇒ 0
        assertEquals(0, relaxToward(1, 0, 3_600_000L, h24))
        // 半小时：同样停滞但 dt < 1h ⇒ 不 snap
        assertEquals(1, relaxToward(1, 0, 1_800_000L, h24))
        // 负侧对称：-1 → 0
        assertEquals(0, relaxToward(-1, 0, 3_600_000L, h24))
        // 目标在上方：29 → 30（快场 24h）
        assertEquals(30, relaxToward(29, 30, 3_600_000L, h24))
        // 修缮卷 J1 反例：慢场（半衰 30d）dt = 1h、70 → 50 取整原地不动但**不**补步 ⇒ 仍 70（否则一周归零·D-1）
        assertEquals(70, relaxToward(70, 50, 3_600_000L, 30L * 86_400_000L))
        assertEquals(29, relaxToward(29, 30, 3_600_000L, 30L * 86_400_000L))
        // 补步门槛恰在 24h 半衰期（含）
        assertEquals(30, relaxToward(29, 30, 3_600_000L, RelationshipBands.RELAX_SNAP_HALF_LIFE_MAX_MS))
        assertEquals(29, relaxToward(29, 30, 3_600_000L, RelationshipBands.RELAX_SNAP_HALF_LIFE_MAX_MS + 1))
    }

    // MARK: - arousalBaseline

    @Test
    fun arousalBaseline_24HourTable() {
        val expected = IntArray(24) { hour ->
            when (hour) {
                in 0..5 -> 12
                in 6..8 -> 28
                in 9..11 -> 42
                12, 13 -> 36
                in 14..17 -> 44
                in 18..21 -> 40
                22 -> 28
                else -> 18
            }
        }
        for (hour in 0 until 24) assertEquals("hour=$hour", expected[hour], arousalBaseline(hour))
        // 白天基线 + 30 的脉冲上限最高 74 < 句子阈 75（修缮卷 F16 修后内核真停在 74）
        assertTrue((0 until 24).maxOf { arousalBaseline(it) } + 30 < 75)
        assertEquals(74, (0 until 24).maxOf { arousalBaseline(it) } + 30)
    }

    // MARK: - dailyTilt（K-9：确定性）

    @Test
    fun dailyTilt_isDeterministic_withinDomain_andVariesAcrossDays() {
        val day = LocalDate.of(2026, 9, 2)
        val a = dailyTilt("uuid-1", day)
        val b = dailyTilt("uuid-1", day)
        assertEquals("同角色同日恒同值", a, b)
        val tilts = (0 until 30).map { dailyTilt("uuid-1", day.plusDays(it.toLong())) }
        for (t in tilts) {
            assertTrue("效价倾 ${t.valence} 越域", t.valence in -6..6)
            assertTrue("激活倾 ${t.arousal} 越域", t.arousal in -4..4)
        }
        assertTrue("30 天里不可能天天同值", tilts.toSet().size > 1)
        assertNotEquals("不同角色同日一般不同（种子含 uuid）", dailyTilt("uuid-1", day), dailyTilt("uuid-2", day.plusDays(3)))
    }

    // MARK: - scaleToBudget（E9）

    @Test
    fun scaleToBudget_poolWithinRemaining_isUnchanged() {
        val out = scaleToBudget(listOf(3, -2, 0), listOf(4, 0), listOf(1, -1), budgetUsed = 10)
        assertEquals(listOf(3, -2, 0), out.personality)
        assertEquals(listOf(4, 0), out.relationship)
        assertEquals(listOf(1, -1), out.diffusion)
        assertEquals(11, out.used)
    }

    @Test
    fun scaleToBudget_pool60_remaining20_roundsEachToOneThird() {
        // pool = 20 + 20 + 20 = 60，已用 20 ⇒ 剩 20 ⇒ factor 1/3 ⇒ 10 → 3.33 → 3、-10 → -3、5 → 1.67 → 2（修缮卷 F9：取整不截断）
        // Σ = 3+3+3+3+2×4 = 20 = 剩余 ⇒ 不修剪；用量 20（旧截断口径是 1,1,1,1 / 用量 16——小位移整批被吞）
        val out = scaleToBudget(listOf(10, -10), listOf(10, 10), listOf(5, 5, 5, 5), budgetUsed = 20)
        assertEquals(listOf(3, -3), out.personality)
        assertEquals(listOf(3, 3), out.relationship)
        assertEquals(listOf(2, 2, 2, 2), out.diffusion)
        assertEquals(20, out.used)
        assertTrue("实际用量不得越过剩余额度", out.used <= 20)
    }

    @Test
    fun scaleToBudget_roundsHalfUp_notTruncate() {
        // Δ = 5、factor 0.5（pool 10、剩 5）⇒ 2.5 → roundToInt = 3；两笔 3 + 3 = 6 > 5 ⇒ 修剪最大者（并列取 personality 下标 0）⇒ 2 + 3 = 5
        val out = scaleToBudget(listOf(5), listOf(5), emptyList(), budgetUsed = 35)
        assertEquals(listOf(2), out.personality)
        assertEquals(listOf(3), out.relationship)
        assertEquals(5, out.used)
    }

    @Test
    fun scaleToBudget_roundingIsSignSymmetric_R1() {
        // R1 🟡-2：幅值取整再配号——`roundToInt` 平局向 +∞ 会把 +2.5 / −2.5 落成 3 / −2。
        // [5, −5]、剩 6、池 10 ⇒ factor 0.6 ⇒ ±3.0 ⇒ [3, −3]，Σ 6 不修剪
        val even = scaleToBudget(listOf(5, -5), emptyList(), emptyList(), budgetUsed = 34)
        assertEquals(listOf(3, -3), even.personality)
        assertEquals(6, even.used)
        // [3, −3]、剩 3 ⇒ factor 0.5 ⇒ ±1.5 ⇒ 对称取整 [2, −2]，Σ 4 > 3 ⇒ 修剪最大者（并列取下标 0）⇒ [1, −2]
        val tie = scaleToBudget(listOf(3, -3), emptyList(), emptyList(), budgetUsed = 37)
        assertEquals(listOf(1, -2), tie.personality)
        assertEquals(3, tie.used)
    }

    @Test
    fun scaleToBudget_E7_remaining3_pool37_trimsLargestFirst() {
        // 剩 3、池 37（三组各有非零）：factor 3/37 ⇒ 20 → 1.62 → 2、10 → 0.81 → 1、7 → 0.57 → 1 ⇒ Σ 4 > 3
        // ⇒ 修剪 |Δ'| 最大者（personality[0] = 2）向零走 1 ⇒ 1 / 1 / 1，Σ = 3
        val out = scaleToBudget(listOf(20, 0), listOf(10), listOf(7), budgetUsed = 37)
        assertEquals(listOf(1, 0), out.personality)
        assertEquals(listOf(1), out.relationship)
        assertEquals(listOf(1), out.diffusion)
        assertEquals(3, out.used)
        // 并列取序 personality › relationship › diffusion、同组下标小者：三笔各 -1 但只剩 2 ⇒ 砍 personality[0]
        val tie = scaleToBudget(listOf(-6, -6), listOf(-6), emptyList(), budgetUsed = 38)
        // factor 2/18 ⇒ -0.67 → -1 各三笔 ⇒ Σ 3 > 2 ⇒ 砍 personality[0] → 0
        assertEquals(listOf(0, -1), tie.personality)
        assertEquals(listOf(-1), tie.relationship)
        assertEquals(2, tie.used)
    }

    @Test
    fun scaleToBudget_remainingZero_zeroesEverything() {
        val out = scaleToBudget(listOf(6, -6), listOf(5), listOf(2, 2), budgetUsed = 40)
        assertEquals(listOf(0, 0), out.personality)
        assertEquals(listOf(0), out.relationship)
        assertEquals(listOf(0, 0), out.diffusion)
        assertEquals(0, out.used)
        // 已用超过 40（坏数据）同样视为剩 0
        assertEquals(0, scaleToBudget(listOf(6), emptyList(), emptyList(), budgetUsed = 99).used)
    }

    // MARK: - rollDay（E10）

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, m: Int, d: Int, h: Int) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun rollDay_firstEver_appliesTiltAndSetsDayStart() {
        val now = at(2026, 9, 2, 15)
        val out = rollDay(AffectField(budgetUsed = 33), now, zone, "u")
        val tilt = dailyTilt("u", LocalDate.of(2026, 9, 2))
        assertEquals(at(2026, 9, 2, 0), out.budgetDayStart)
        assertEquals("预算归零", 0, out.budgetUsed)
        assertEquals(0 + tilt.valence, out.valence)
        assertEquals(30 + tilt.arousal, out.arousal)
    }

    @Test
    fun rollDay_sameDay_doesNotTiltAgain() {
        val noon = at(2026, 9, 2, 12)
        val once = rollDay(AffectField(), noon, zone, "u")
        val again = rollDay(once.copy(budgetUsed = 7), at(2026, 9, 2, 23), zone, "u")
        assertEquals("同日重复调用：预算不归零、不再倾", 7, again.budgetUsed)
        assertEquals(once.valence, again.valence)
        assertEquals(once.arousal, again.arousal)
        assertEquals(once.budgetDayStart, again.budgetDayStart)
    }

    @Test
    fun rollDay_nextDay_resetsBudgetAndTiltsWithNewSeed() {
        val day1 = rollDay(AffectField(), at(2026, 9, 2, 12), zone, "u").copy(budgetUsed = 25, valence = 10)
        val day2 = rollDay(day1, at(2026, 9, 3, 1), zone, "u")
        val tilt2 = dailyTilt("u", LocalDate.of(2026, 9, 3))
        assertEquals(at(2026, 9, 3, 0), day2.budgetDayStart)
        assertEquals(0, day2.budgetUsed)
        assertEquals(10 + tilt2.valence, day2.valence)
    }

    @Test
    fun rollDay_nextDay_zeroesSlowDayUsed_sameDayKeepsIt() {
        // 修缮卷 E5：跨日 slowDayUsed 归零（与预算同命运）；同日原样。
        val day1 = rollDay(AffectField(), at(2026, 9, 2, 12), zone, "u").copy(slowDayUsed = listOf(15, 7), budgetUsed = 25)
        assertEquals(listOf(15, 7), rollDay(day1, at(2026, 9, 2, 23), zone, "u").slowDayUsed)
        val day2 = rollDay(day1, at(2026, 9, 3, 1), zone, "u")
        assertEquals(listOf(0, 0), day2.slowDayUsed)
        assertEquals(0, day2.budgetUsed)
    }

    @Test
    fun rollDay_tiltIsClampedIntoDomain() {
        val out = rollDay(AffectField(valence = 100, arousal = 0), at(2026, 9, 2, 12), zone, "u")
        assertTrue(out.valence in -100..100)
        assertTrue(out.arousal in 0..100)
    }

    // MARK: - 内心行换气（微图纸 2026-09-02 §5）：slowBand / trackSlowBands / scriptVariant

    @Test
    fun slowBand_sixBoundaries_securityAndInvestment() {
        // 安全感 ≤30 低 / ≥80 高（微图纸 §4 档界）
        assertEquals(0, slowBand(30, 30, 80))
        assertEquals(1, slowBand(31, 30, 80))
        assertEquals(1, slowBand(79, 30, 80))
        assertEquals(2, slowBand(80, 30, 80))
        // 投入度 ≤10 低 / ≥80 高
        assertEquals(0, slowBand(10, 10, 80))
        assertEquals(1, slowBand(11, 10, 80))
        assertEquals(1, slowBand(79, 10, 80))
        assertEquals(2, slowBand(80, 10, 80))
        assertEquals(0, slowBand(0, 30, 80))
        assertEquals(2, slowBand(100, 30, 80))
        // 档界常量 = 场句阈值（`InnerStateRenderer.fieldSentence` 同源·改一侧必改另一侧）
        assertEquals(30, RelationshipBands.SLOW_SECURITY_LOW_MAX)
        assertEquals(80, RelationshipBands.SLOW_SECURITY_HIGH_MIN)
        assertEquals(10, RelationshipBands.SLOW_INVESTMENT_LOW_MAX)
        assertEquals(80, RelationshipBands.SLOW_INVESTMENT_HIGH_MIN)
    }

    @Test
    fun trackSlowBands_unknownRecordsBandNotTime_crossingRecordsTime_sameBandUntouched() {
        val now = 1_700_000_000_000L
        // 未知（默认 / 老列 −1）⇒ 记档不记时：安全感 85 ⇒ 档 2；投入度 30 ⇒ 档 1；slowBandsAt 仍 [0,0]（不出慢场句）
        val first = trackSlowBands(AffectField(), effSecurity = 85, effInvestment = 30, nowMs = now)
        assertEquals(listOf(2, 1), first.slowBands)
        assertEquals(listOf(0L, 0L), first.slowBandsAt)
        // 跨档 ⇒ 记档 + 记时（只动跨档那一格）：安全感 85 → 74 落中档；投入度 30 仍中档不动
        val crossed = trackSlowBands(first, effSecurity = 74, effInvestment = 30, nowMs = now + 1)
        assertEquals(listOf(1, 1), crossed.slowBands)
        assertEquals(listOf(now + 1, 0L), crossed.slowBandsAt)
        // 同档 ⇒ 一字不动（时刻不刷新）
        assertEquals(crossed, trackSlowBands(crossed, effSecurity = 60, effInvestment = 50, nowMs = now + 2))
        // 投入度跨到高档 ⇒ 第二格记时、第一格不动
        val invest = trackSlowBands(crossed, effSecurity = 60, effInvestment = 80, nowMs = now + 3)
        assertEquals(listOf(1, 2), invest.slowBands)
        assertEquals(listOf(now + 1, now + 3), invest.slowBandsAt)
        // 其余字段原样（只动两列表）
        assertEquals(AffectField().copy(slowBands = invest.slowBands, slowBandsAt = invest.slowBandsAt), invest)
        // 已知档跨到未知不可能；已知档相同数值不同（79 → 31 都是中档）也不动
        assertEquals(invest, trackSlowBands(invest, effSecurity = 31, effInvestment = 99, nowMs = now + 4))
    }

    @Test
    fun scriptVariant_rotatesEveryThreeLocalDays_byEpochDay() {
        // 手算 (d/3)%3：20000 → 6666 → 0；20003 → 6667 → 1；20006 → 6668 → 2；20009 → 6669 → 0
        for ((epochDay, want) in listOf(20_000L to 0, 20_003L to 1, 20_006L to 2, 20_009L to 0)) {
            val noon = LocalDate.ofEpochDay(epochDay).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            assertEquals("epochDay=$epochDay", want, scriptVariant(noon, zone))
        }
        // 同一 3 天窗内恒同值（首日零点 → 第三日 23:59），第四日零点即换；窗按 epochDay 的 3 倍数对齐：20001 = 3 × 6667 是窗首（6667 % 3 = 1）
        val d = LocalDate.ofEpochDay(20_001L)
        assertEquals(1, scriptVariant(d.atStartOfDay(zone).toInstant().toEpochMilli(), zone))
        assertEquals(1, scriptVariant(d.plusDays(2).atTime(23, 59).atZone(zone).toInstant().toEpochMilli(), zone))
        assertEquals(2, scriptVariant(d.plusDays(3).atStartOfDay(zone).toInstant().toEpochMilli(), zone))
        // 按本地日算：同一 epoch 毫秒在东八区已是次日、在 UTC 仍是前一日 ⇒ 可能落不同变体
        val utc = ZoneId.of("UTC")
        val lateUtc = d.plusDays(2).atTime(20, 0).atZone(utc).toInstant().toEpochMilli()   // UTC 第三日 20:00 = 上海第四日 04:00
        assertEquals(1, scriptVariant(lateUtc, utc))
        assertEquals(2, scriptVariant(lateUtc, zone))
        assertEquals(3, RelationshipBands.SCRIPT_ROTATE_DAYS)
        assertEquals(3, RelationshipBands.SCRIPT_VARIANTS)
    }
}
