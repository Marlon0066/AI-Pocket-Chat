package com.situ.aichat.ui.world.town

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * [TownAmbience] T1（台阶0 图纸 §7 T1-1·边界 E1/E2/E3/E4/E8/E9）——断言从图纸 §3.1 预设表与 §4.1 落值表
 * **独立反推**（期望值在此重新打字，不读实现常量）：相位归源 [com.situ.aichat.world.WorldClock] 后
 * 21:18 之后与 00:00-05:00 必须仍是深夜档（旧三角权重版的回落 bug）。
 *
 * 口径：DAWN [05:00,07:00) / DAY [07:00,17:00) / DUSK [17:00,19:30) / NIGHT 其余；边界 ±27min 线性叠化。
 */
class TownAmbienceTest {

    private val eps = 1e-4f

    // 图纸 §4.1 / §3.1 落值（独立打字）。
    private val nightTint = floatArrayOf(0.40f, 0.47f, 0.72f)
    private val duskTint = floatArrayOf(0.86f, 0.74f, 0.70f)
    private val dayTint = floatArrayOf(1.0f, 1.0f, 1.0f)
    private val nightFog = floatArrayOf(0.15f, 0.18f, 0.30f)
    private val duskFog = floatArrayOf(0.44f, 0.31f, 0.31f)
    private val dayFog = floatArrayOf(0.79f, 0.54f, 0.46f)

    private fun at(h: Int, m: Int, s: Int = 0, reduce: Boolean = false) =
        TownAmbience.current(LocalTime.of(h, m, s), reduceMotion = reduce)

    private fun assertVec(msg: String, expected: FloatArray, actual: FloatArray) {
        assertEquals("$msg 分量数", 3, actual.size)
        for (i in 0..2) assertEquals("$msg[$i]", expected[i], actual[i], eps)
    }

    // ─────────────────────────── E1 深夜回归（旧 bug 触发点）───────────────────────────

    @Test
    fun e1_night22_and03_stayNight_notDayish() {
        for (t in listOf(LocalTime.of(22, 0), LocalTime.of(3, 0), LocalTime.of(21, 18), LocalTime.of(0, 30))) {
            val a = TownAmbience.current(t)
            assertEquals("$t 应为深夜画层", 2, a.paintedPhase)
            assertVec("$t tint", nightTint, a.sceneTint)
            assertVec("$t fog", nightFog, a.fog)
            assertEquals("$t glowA", 0.16f, a.glowA, eps)
            assertEquals("$t lampT", 1f, a.lampT, eps)
            assertEquals("$t duskSec", 3600f, a.duskSec, eps)
            assertNotNull("$t 应有深夜色板", a.skyColors)
            // 深夜 7 停靠首停 = #070D1E（§3.1 逐值照搬）。
            assertEquals("$t sky[0].r", 7 / 255f, a.skyColors!![0], eps)
            assertEquals("$t sky[0].g", 13 / 255f, a.skyColors[1], eps)
            assertEquals("$t sky[0].b", 30 / 255f, a.skyColors[2], eps)
            assertVec("$t sun", floatArrayOf(-0.30f, 0.22f, 0.66f), a.sun)
        }
    }

    // ─────────────────────────── E2 DUSK/NIGHT 锁死边界 + 叠化连续 ───────────────────────────

    @Test
    fun e2_duskNightBoundaryFlipsAt1930() {
        assertEquals("19:29 仍是黄昏画层", 1, at(19, 29).paintedPhase)
        assertEquals("19:31 已是深夜画层", 2, at(19, 31).paintedPhase)
        assertEquals("17:00 起黄昏画层", 1, at(17, 0).paintedPhase)
        assertEquals("16:59 仍无画层", 0, at(16, 59).paintedPhase)
    }

    @Test
    fun e2_duskNightBlendMidpointIsExactLerp() {
        // 叠化窗 19:03-19:57，边界 19:30 = 中点 t=0.5 → 逐项恰为两侧均值。
        val a = at(19, 30)
        assertVec("19:30 tint", floatArrayOf(0.63f, 0.605f, 0.71f), a.sceneTint)
        assertVec("19:30 fog", floatArrayOf(0.295f, 0.245f, 0.305f), a.fog)
        assertEquals("19:30 glowA", 0.505f, a.glowA, eps)
        // 天空两侧均有色板 → 7 停靠逐停 lerp；首停 = (0x3A3050 + 0x070D1E)/2。
        val sky = requireNotNull(a.skyColors)
        assertEquals("19:30 sky[0].r", (0x3A / 255f + 7 / 255f) / 2f, sky[0], eps)
        assertEquals("19:30 sky[0].g", (0x30 / 255f + 13 / 255f) / 2f, sky[1], eps)
        assertEquals("19:30 sky[0].b", (0x50 / 255f + 30 / 255f) / 2f, sky[2], eps)
        // 末停（第 7 停靠）同理。
        assertEquals("19:30 sky[6].r", (0x6E / 255f + 0x1E / 255f) / 2f, sky[18], eps)
    }

    @Test
    fun e2_windowEndsAreExactlyBothSides() {
        val start = at(19, 3)   // t=0 → 纯黄昏
        assertVec("19:03 tint = 黄昏", duskTint, start.sceneTint)
        assertEquals("19:03 glowA = 黄昏", 0.85f, start.glowA, eps)
        val end = at(19, 57)    // t=1 → 纯深夜
        assertVec("19:57 tint = 深夜", nightTint, end.sceneTint)
        assertEquals("19:57 glowA = 深夜", 0.16f, end.glowA, eps)
    }

    @Test
    fun e2_noJumpAcrossPhaseFlip() {
        val before = at(19, 29).sceneTint
        val after = at(19, 31).sceneTint
        for (i in 0..2) {
            // 2 分钟 = 窗宽的 2/54 → 每分量变化必 < 两侧差值的 1/20。
            val span = kotlin.math.abs(duskTint[i] - nightTint[i])
            assertTrue("tint[$i] 跨相位翻转处跳变过大", kotlin.math.abs(after[i] - before[i]) < span / 20f)
        }
    }

    // ─────────────────────────── E3 白天回归 ───────────────────────────

    @Test
    fun e3_noonIsPlainDay() {
        val a = at(12, 0)
        assertNull("白天不接管天空", a.skyColors)
        assertEquals("白天无画层", 0, a.paintedPhase)
        assertEquals("白天 glowA", 0.45f, a.glowA, eps)
        assertVec("白天 tint 中性", dayTint, a.sceneTint)
        assertVec("白天 fog", dayFog, a.fog)
        assertEquals("白天灯全灭", 0f, a.lampT, eps)
        assertEquals("白天 duskSec", 0f, a.duskSec, eps)
        assertVec("白天 sun", floatArrayOf(-0.55f, 0.5f, 0.42f), a.sun)
    }

    @Test
    fun e3_dayDuskWindowKeepsColoredSideSky() {
        // 16:33-17:27 白天侧无色板 → 只 lerp 数值，天空取有色侧（黄昏色板）。
        val a = at(16, 33)
        assertEquals("16:33 t=0 → glowA 仍白天值", 0.45f, a.glowA, eps)
        assertVec("16:33 t=0 → tint 仍中性", dayTint, a.sceneTint)
        val sky = requireNotNull(a.skyColors) { "叠化窗内天空应取有色侧（黄昏色板）" }
        assertEquals("16:33 sky[0].r = 黄昏首停", 0x3A / 255f, sky[0], eps)
        assertEquals("16:33 仍无画层（相位仍是白天）", 0, a.paintedPhase)
        // 白天末端灯仍全灭（黄昏点亮由 duskSec 错峰独立驱动）。
        assertEquals("16:33 lampT", 0f, a.lampT, eps)
    }

    // ─────────────────────────── E4 黎明渐熄 ───────────────────────────

    @Test
    fun e4_dawnLampFadeThreePoints() {
        assertEquals("04:33 灯满", 1f, at(4, 33).lampT, eps)
        assertEquals("05:00 灯半", 0.5f, at(5, 0).lampT, eps)
        assertEquals("05:27 灯灭", 0f, at(5, 27).lampT, eps)
        assertEquals("04:32 窗外仍满", 1f, at(4, 32).lampT, eps)
        assertEquals("05:28 已灭", 0f, at(5, 28).lampT, eps)
    }

    @Test
    fun e4_dawnFadeKeepsDuskSecFull_R1D5() {
        // R1 修订 D-5：渐熄窗内 DAWN 侧 duskSec 保持 3600 → 着色器 on = lampT 完整走 1→0，不被 05:00 相位翻转硬切。
        assertEquals("05:10 渐熄中 duskSec 保持满", 3600f, at(5, 10).duskSec, eps)
        assertEquals("05:27 窗末仍满", 3600f, at(5, 27).duskSec, eps)
        assertEquals("05:28 窗外归零", 0f, at(5, 28).duskSec, eps)
        assertEquals("04:50 夜侧本就满", 3600f, at(4, 50).duskSec, eps)
    }

    @Test
    fun e4_afterDawnNoPaintedLayer() {
        assertEquals("05:28 无画层", 0, at(5, 28).paintedPhase)
        assertNull("05:28 起天空回落城市渐变", at(6, 0).skyColors)
        assertEquals("06:00 灯全灭", 0f, at(6, 0).lampT, eps)
    }

    // ─────────────────────────── E8 reduceMotion 错峰取消 ───────────────────────────

    @Test
    fun e8_reduceMotionForcesDuskSecFull() {
        assertEquals("黄昏第 5 秒实算", 5f, at(17, 0, 5).duskSec, eps)
        assertEquals("reduceMotion 直接给满", 3600f, at(17, 0, 5, reduce = true).duskSec, eps)
        assertEquals("白天 reduceMotion 仍 0", 0f, at(12, 0, reduce = true).duskSec, eps)
    }

    @Test
    fun duskSecCountsFrom1700() {
        assertEquals("17:00:00", 0f, at(17, 0, 0).duskSec, eps)
        assertEquals("17:00:12", 12f, at(17, 0, 12).duskSec, eps)
        assertEquals("18:30:00", 5400f, at(18, 30, 0).duskSec, eps)
        assertEquals("黄昏灯主控恒 1", 1f, at(17, 0).lampT, eps)
    }

    // ─────────────────────────── E9 跨午夜 ───────────────────────────

    @Test
    fun e9_acrossMidnightIsFlatNight() {
        val before = at(23, 59)
        val after = at(0, 1)
        assertEquals("23:59 深夜", 2, before.paintedPhase)
        assertEquals("00:01 深夜", 2, after.paintedPhase)
        assertVec("跨午夜 tint 不变", before.sceneTint, after.sceneTint)
        assertEquals("跨午夜 glowA 不变", before.glowA, after.glowA, 0f)
        assertEquals("跨午夜 lampT 不变", before.lampT, after.lampT, 0f)
        assertEquals("跨午夜 duskSec 不变", before.duskSec, after.duskSec, 0f)
    }

    @Test
    fun nightPaletteHasSevenStops() {
        val sky = requireNotNull(at(22, 0).skyColors)
        assertEquals("7 停靠 × rgb", 21, sky.size)
    }
}
