package com.situ.aichat.ui.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fable-5 间距军规看门（设计语言 §2.5·V2 提案 §2 并入 2026-09-06）。
 *
 * 断言全部**从规格独立反推**（数字取自设计语言 §2.5 的 token 表与视觉边缘换算表，不照抄实现）：
 * - [gridTokens_allMultiplesOfFour] = 军规硬规「任何 margin/padding 必须取 token（4 的倍数）——
 *   出现 5/10/14 之类孤值即打回」的机器化；
 * - [gutterPadding_compensatesToTwentyDpVisualEdge] / [namedGutterConstants_matchConversionTable]
 *   钉住视觉边缘换算表的三种形态（实心 20 / 文字钮 8 / 圆钮 16）；
 * - [screenGutter_isTwentyDp] 防军规落值日后被顺手改掉（2026-09-06 甲′「尊重现有军规、不改军规本身」
 *   的全部意义就在这个 20——**绝不许改成 16**）。
 */
class AppSpacingTest {

    /** S1 · 军规硬规：八枚 token 全部落在 4dp 网格上（孤值 5/10/14/18 一律不许进表）。 */
    @Test
    fun gridTokens_allMultiplesOfFour() {
        val tokens = mapOf(
            "xs" to AppSpacing.xs, "s" to AppSpacing.s, "m" to AppSpacing.m, "l" to AppSpacing.l,
            "xl" to AppSpacing.xl, "xxl" to AppSpacing.xxl,
            "hero" to AppSpacing.hero, "section" to AppSpacing.section,
        )
        assertEquals("token 枚数应为八枚", 8, tokens.size)
        tokens.forEach { (name, value) ->
            assertEquals("space.$name = ${value.value}dp 不在 4dp 网格上（军规：孤值即打回）", 0f, value.value % 4f, 0f)
        }
        // 规格原表（V2 §2·一字不改）：xs=4 / s=8 / m=12 / l=16 / xl=20 / xxl=24 / hero=32 / section=48
        val expected = listOf(4f, 8f, 12f, 16f, 20f, 24f, 32f, 48f)
        assertEquals(expected, tokens.values.map { it.value })
    }

    /** S2 · 视觉边缘换算：三种内部补偿算出的布局 padding = 20 / 8 / 16dp。 */
    @Test
    fun gutterPadding_compensatesToTwentyDpVisualEdge() {
        // 无补偿（实心钮 / 裸文字 / 卡片 / 输入框）
        assertEquals(20.dp, AppSpacing.gutterPadding())
        assertEquals(20.dp, AppSpacing.gutterPadding(0.dp))
        // 文字钮自带横 12dp 内边距
        assertEquals(8.dp, AppSpacing.gutterPadding(12.dp))
        // 白瓷圆钮触达 48 / 视觉 40 → 每边溢 4dp
        assertEquals(16.dp, AppSpacing.gutterPadding(4.dp))
    }

    /** S2b · 三枚具名常量（图纸二一律引它们，不写字面量）对上换算表。 */
    @Test
    fun namedGutterConstants_matchConversionTable() {
        assertEquals(20.dp, AppSpacing.gutterForSolid)
        assertEquals(8.dp, AppSpacing.gutterForTextButton)
        assertEquals(16.dp, AppSpacing.gutterForRoundButton)
    }

    /**
     * S2c · 补偿超过 gutter 时**当场报错**，而不是算出负 padding 让 `Modifier.padding` 在深层布局里崩。
     *
     * 可达性不是假想：Text 档钮挂 `minimumInteractiveComponentSize()`，内容极窄时布局被撑到 48dp 并居中，
     * 补偿 = 12 + (48 − 钮宽)/2，钮宽 24dp 就已算到 24dp > gutter（设计语言 §2.5.3「补偿失效的边界」）。
     */
    @Test
    fun gutterPadding_rejectsCompensationLargerThanGutter() {
        // 边界上仍合法（恰好压平到 0dp padding）
        assertEquals(0.dp, AppSpacing.gutterPadding(20.dp))
        val e = assertThrows(IllegalArgumentException::class.java) { AppSpacing.gutterPadding(24.dp) }
        assertTrue("报错须点名超限的那个数", e.message.orEmpty().contains("24"))
    }

    /** S3 · 屏幕水平 gutter 恒 20dp——防被顺手改回 16（军规落值，改须走「语言进化」过审）。 */
    @Test
    fun screenGutter_isTwentyDp() {
        val twentyDp: Dp = 20.dp
        assertEquals(twentyDp, AppSpacing.screenGutter)
        // 军规另两条落值同钉：卡片内边距 16 / hero 卡 20 / 列表行水平起点 16
        assertEquals(16.dp, AppSpacing.cardInset)
        assertEquals(20.dp, AppSpacing.heroCardInset)
        assertEquals(16.dp, AppSpacing.rowInset)
    }
}
