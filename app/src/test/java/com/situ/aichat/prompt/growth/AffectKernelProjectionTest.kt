package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.CustomGain
import com.situ.aichat.data.model.PersonaGains
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.fromQuality
import com.situ.aichat.data.model.setNetKeepingNeg
import com.situ.aichat.data.model.toQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感内核·卷三《场内核与渲染收编》T1-4（图纸 §7.2 · E29 / E30）：投影与扩散的纯函数部分。
 *
 * 断言从图纸 §3.5 的两张表与公式**独立手算**（`saturate(coef × 10 × factor)`，逐命中饱和再求和、单场钳 ±12）：
 * - g04 正常档 ⇒ 效价 `saturate(5)=4`、激活 `saturate(2)=2`、投入 `saturate(1)=1`、安全感 0
 * - 档 0 ⇒ 效价 `saturate(2)=2`；档 2 ⇒ `saturate(9)=5`（E30）
 * - g04+g10+g16 三命中效价 `4+5+4=13 ⇒ 钳 12`（对总和饱和会让三件好事 ≈ 一件，故逐命中饱和）
 * - custom pos/neg 两行；标签忽略大小写与首尾空白对上专属项档位
 * - 扩散：`fΔ.security = −5` ⇒ trust `saturate(−1.5)=−1`、tension `saturate(+1.25)=+1`、closeness `saturate(−1.0)=−1`、
 *   openness `saturate(−0.75)=−1`、attachment/warmth `saturate(∓0.5)=0`；其余维 0
 * - 关系维经 `setNetKeepingNeg` 落用后 `neg` 一字节不变（R1-1）
 * - E29 空命中 / 非法 key ⇒ 零投影；预算池不含场（一次 +12 的场位移不占 40）
 */
class AffectKernelProjectionTest {

    private val normal = PersonaGains()

    private fun hits(vararg keys: String) = keys.toList()

    // MARK: - 投影

    @Test
    fun g04_normalLevel_projectsPerTable() {
        val d = AffectKernel.project(hits("g04"), emptyList(), normal)
        assertEquals(FieldDelta(security = 0, investment = 1, valence = 4, arousal = 2), d)
    }

    @Test
    fun g04_levelZeroAndTwo_scaleByGainFactor() {
        val numb = PersonaGains(system = mapOf("g04" to 0))
        val sensitive = PersonaGains(system = mapOf("g04" to 2))
        assertEquals(2, AffectKernel.project(hits("g04"), emptyList(), numb).valence)       // 5×0.4=2 ⇒ 2
        assertEquals(5, AffectKernel.project(hits("g04"), emptyList(), sensitive).valence)  // 5×1.8=9 ⇒ 5
        assertEquals(6, AffectKernel.project(hits("g10"), emptyList(), PersonaGains(system = mapOf("g10" to 2))).valence) // 10.8 ⇒ 6
    }

    @Test
    fun threePositiveHits_sumPerHit_thenCapAtTwelve() {
        val d = AffectKernel.project(hits("g04", "g10", "g16"), emptyList(), normal)
        assertEquals("4 + 5 + 4 = 13 ⇒ 钳 12", 12, d.valence)
        // 激活：g04 2 + g10 saturate(4)=3 + g16 2 = 7；投入：1 + 0 + saturate(3)=3 = 4
        assertEquals(7, d.arousal)
        assertEquals(4, d.investment)
    }

    @Test
    fun threeNegativeHits_capAtMinusTwelve() {
        // g13 −6 ⇒ −5；g19 −5 ⇒ −4；g27 −4 ⇒ −3 ⇒ 效价 −12（恰到帽）；安全感 −3/−7/−8 ⇒ −3 −5 −5 = −13 ⇒ −12
        val d = AffectKernel.project(hits("g13", "g19", "g27"), emptyList(), normal)
        assertEquals(-12, d.valence)
        assertEquals(-12, d.security)
    }

    @Test
    fun customHits_useTheirOwnLevel_matchedCaseInsensitively() {
        val gains = PersonaGains(custom = listOf(CustomGain(id = "u1", label = "怕黑", level = 2)))
        val pos = AffectKernel.project(emptyList(), listOf(GrowthAnalysisResult.CustomHit("  怕黑 ", positive = true)), gains)
        // custom·pos × 1.8：S 0.1→1.8⇒2，V 0.4→7.2⇒5，A 0.2→3.6⇒3
        assertEquals(FieldDelta(security = 2, investment = 0, valence = 5, arousal = 3), pos)
        val neg = AffectKernel.project(emptyList(), listOf(GrowthAnalysisResult.CustomHit("被叫全名", positive = false)), gains)
        // 清单里没有的标签按正常档：S −2⇒−2，V −4⇒−3，A +2⇒2
        assertEquals(FieldDelta(security = -2, investment = 0, valence = -3, arousal = 2), neg)
    }

    @Test
    fun emptyOrUnknownHits_projectNothing() {
        assertEquals(FieldDelta.ZERO, AffectKernel.project(emptyList(), emptyList(), normal))
        assertEquals(FieldDelta.ZERO, AffectKernel.project(hits("g99", "bandUp", ""), emptyList(), normal))
    }

    // MARK: - 扩散（场 → 维单向）

    private fun dim(key: String) = AffectCoefficients.DIM_KEYS.indexOf(key)

    @Test
    fun diffuse_securityMinusFive_perTable() {
        val out = AffectKernel.diffuse(FieldDelta(security = -5, investment = 0, valence = 0, arousal = 0))
        assertEquals(16, out.size)
        assertEquals(-1, out[dim("trust")])
        assertEquals(1, out[dim("tension")])
        assertEquals(-1, out[dim("closeness")])
        assertEquals(-1, out[dim("openness")])
        assertEquals(0, out[dim("attachment")])
        assertEquals(0, out[dim("warmth")])
        for (key in listOf("familiarity", "rapport", "respect", "fun", "extroversion", "humor", "independence", "curiosity")) {
            assertEquals(key, 0, out[dim(key)])
        }
    }

    @Test
    fun diffuse_zeroField_isAllZero_andSumsAcrossFields() {
        assertTrue(AffectKernel.diffuse(FieldDelta.ZERO).all { it == 0 })
        // tension 同时吃 security(−0.25) 与 valence(−0.20)：S −8, V −8 ⇒ 2.0 + 1.6 = 3.6 ⇒ saturate = 3
        val out = AffectKernel.diffuse(FieldDelta(security = -8, investment = 0, valence = -8, arousal = 0))
        assertEquals(3, out[dim("tension")])
        // fun 吃 valence(0.30)：−8×0.3 = −2.4 ⇒ −2
        assertEquals(-2, out[dim("fun")])
    }

    @Test
    fun diffuse_magnitudeNeverExceedsSix() {
        val out = AffectKernel.diffuse(FieldDelta(security = 12, investment = 12, valence = 12, arousal = 12))
        assertTrue(out.all { kotlin.math.abs(it) <= 6 })
    }

    // MARK: - 落用口径（R1-1）与预算范围（K-3）

    @Test
    fun applyingDiffusionToRelationshipDim_keepsNegUntouched() {
        val quality = RelationshipQuality(trust = 60)
        val pressure = RelationshipPressure.fromQuality(quality).let { p ->
            // 造一个带负压的 trust：pos 70 / neg 10 ⇒ 净额 60
            p.copy(pos = p.pos.toMutableList().also { it[1] = 70 }, neg = p.neg.toMutableList().also { it[1] = 10 })
        }
        val dTrust = AffectKernel.diffuse(FieldDelta(security = -5, investment = 0, valence = 0, arousal = 0))[dim("trust")]
        val after = pressure.setNetKeepingNeg(1, quality.trust + dTrust)
        assertEquals("扩散经 setNetKeepingNeg：只动 pos", 10, after.neg[1])
        assertEquals(59, after.toQuality().trust)
    }

    @Test
    fun budgetPool_excludesFields() {
        val d = AffectKernel.project(hits("g07", "g09", "g20"), emptyList(), normal)
        assertEquals("安全感一次 +12（g07 4 + g09 3 + g20 5 = 12）", 12, d.security)
        // 预算池只收 16 维位移：场位移不进池 ⇒ 零位移时用量 0，与场无关
        val budget = scaleToBudget(List(8) { 0 }, List(8) { 0 }, List(16) { 0 }, budgetUsed = 0)
        assertEquals(0, budget.used)
        assertEquals(8, PersonalitySpectrum.DIMENSION_KEYS.size)
    }
}
