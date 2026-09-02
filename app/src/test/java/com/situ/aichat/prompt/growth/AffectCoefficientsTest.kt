package com.situ.aichat.prompt.growth

import android.content.Context
import android.content.res.Configuration
import com.situ.aichat.data.model.PersonaVocab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * 活人感内核·卷三《场内核与渲染收编》T1-3（图纸 §7.2）：两张系数表与提示词标签表的**机器约束**。
 *
 * 断言从总图纸 §3.4「系数表的落值时机」与卷三 §3.5 / §3.7 独立反推：
 * - [AffectCoefficients.validateTables] 为空（键集 == GAIN_KEYS · 非零 1..3 · 步长 · 扩散 ≤6 维 · 域 · 负值登记）
 * - 扩散表里真正为负的格恰 = 登记集合（表与登记互证，不许一边偷偷多一格）
 * - `GAIN_PROMPT_LABELS` 27 项与 zh 资源 `persona_gain_gNN` **逐字相等**（同源双写的看门·Robolectric 读 zh 资源）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class AffectCoefficientsTest {

    private fun zhContext(): Context {
        val base = RuntimeEnvironment.getApplication()
        val conf = Configuration(base.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        return base.createConfigurationContext(conf)
    }

    @Test
    fun validateTables_reportsNoViolation() {
        assertEquals(emptyList<String>(), AffectCoefficients.validateTables())
    }

    @Test
    fun projection_keysAreExactlyTheGainKeys() {
        assertEquals(PersonaVocab.GAIN_KEYS.toSet(), AffectCoefficients.PROJECTION.keys)
        assertEquals(27, AffectCoefficients.PROJECTION.size)
    }

    @Test
    fun projection_everyEntryTouchesOneToThreeFields_withinDomainAndStep() {
        for ((key, vec) in AffectCoefficients.PROJECTION) {
            val values = Field.entries.map { vec[it] }
            val nonZero = values.count { it != 0.0 }
            assertTrue("$key 非零场数 $nonZero", nonZero in 1..3)
            for (v in values) {
                assertTrue("$key 系数 $v 越界", v >= -1.0 && v <= 1.0)
                assertEquals("$key 系数 $v 不是 0.1 步长", Math.round(v * 10) / 10.0, v, 1e-9)
            }
        }
    }

    @Test
    fun diffusion_negativeCells_areExactlyTheRegisteredOnes() {
        val actualNegatives = AffectCoefficients.DIFFUSION.flatMap { (field, row) ->
            row.filter { it.value < 0.0 }.map { field to it.key }
        }.toSet()
        assertEquals(
            setOf(Field.SECURITY to "tension", Field.INVESTMENT to "independence", Field.VALENCE to "tension"),
            actualNegatives,
        )
        assertEquals("登记集合与表互证", AffectCoefficients.NEGATIVE_DIFFUSION, actualNegatives)
    }

    @Test
    fun diffusion_eachFieldTouchesAtMostSixDims_withinDomainAndStep() {
        val legal = AffectCoefficients.DIM_KEYS.toSet()
        assertEquals("16 维 key", 16, legal.size)
        for ((field, row) in AffectCoefficients.DIFFUSION) {
            assertTrue("$field 扩散到 ${row.size} 维", row.size <= 6)
            for ((dim, v) in row) {
                assertTrue("$field → $dim 不是合法维", dim in legal)
                assertTrue("$field → $dim 系数 $v 幅值越界", kotlin.math.abs(v) <= 0.5)
                assertEquals("$field → $dim 不是 0.05 步长", Math.round(v * 20) / 20.0, v, 1e-9)
            }
        }
        assertEquals("四个场都在表里", Field.entries.toSet(), AffectCoefficients.DIFFUSION.keys)
    }

    @Test
    fun gainPromptLabels_matchZhResourcesVerbatim() {
        val zh = zhContext()
        assertEquals(27, PersonaVocab.GAIN_PROMPT_LABELS.size)
        for (key in PersonaVocab.GAIN_KEYS) {
            val fromRes = zh.getString(PersonaVocab.GAINS.getValue(key))
            assertEquals("$key 提示词标签与 zh 资源不同源", fromRes, PersonaVocab.GAIN_PROMPT_LABELS[key])
        }
        // K-14 提示词行形状：`g13 吵架 · 被凶`
        assertEquals("g13 吵架 · 被凶", PersonaVocab.gainPromptLine("g13"))
        assertEquals("g01 被关心问候", PersonaVocab.gainPromptLine("g01"))
    }
}
