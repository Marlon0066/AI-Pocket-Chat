package com.situ.aichat.ui.liuli.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.situ.aichat.ui.designsystem.ColorContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-1 渐变窗口（图纸 2026-09-05 卷二A §7）：三 stop 逐值 + **取样区间 [0.12, 1] 对白字恒 ≥ 4.5:1**。
 *
 * 后者是这一卷最硬的红线——契约原中段 `#2F7CF2` 白字只有 4.0，作者据此改为 `#2570E8`（§0 ② 3）；
 * 这条测试就是那次修订的看门狗：任何人把中段调亮一点，逐 0.01 采样立刻抓住。
 *
 * 2026-09-05 C7：末 stop 由 `#1557CC` 换成偏紫的 `#3B3FC6`（用户选乙·图纸 §4.16），底端白字对比
 * 6.4 → 7.7；两条 WCAG 用例原样重跑即验（区间下限仍是膝点 `#2570E8` 的 4.6，未被此改动触及）。
 */
class LiuliBubbleGradientTest {

    /** 图纸 §3.2 锁定的三 stop——在测试里重打一遍（不引用实现常量）。 */
    private val top = Color(0xFF6FB1FF)
    private val knee = Color(0xFF2570E8)
    private val bottom = Color(0xFF3B3FC6)

    @Test fun stops_matchLockedValues() {
        assertEquals(top.toArgb(), LiuliBubbleGradient.colorAt(0f).toArgb())
        assertEquals(knee.toArgb(), LiuliBubbleGradient.colorAt(0.12f).toArgb())
        assertEquals(bottom.toArgb(), LiuliBubbleGradient.colorAt(1f).toArgb())
    }

    @Test fun colorAt_clampsOutOfRange_neverExtrapolates() {
        assertEquals(top.toArgb(), LiuliBubbleGradient.colorAt(-3f).toArgb())
        assertEquals(bottom.toArgb(), LiuliBubbleGradient.colorAt(7f).toArgb())
    }

    @Test fun colorAt_isPiecewiseLinear_betweenStops() {
        // 0.06 = 顶段中点 → 两端通道各取一半（逐通道 sRGB 线性插值）。
        val mid = LiuliBubbleGradient.colorAt(0.06f)
        assertEquals((top.red + knee.red) / 2f, mid.red, 1e-2f)
        assertEquals((top.green + knee.green) / 2f, mid.green, 1e-2f)
        assertEquals((top.blue + knee.blue) / 2f, mid.blue, 1e-2f)
    }

    @Test fun whiteText_meetsWcag_acrossSamplingRange() {
        // 气泡只会落在 [0.12, 1]（0–12% 是 chrome 区，气泡到那儿已在玻璃之下·图纸 §0 ② 3）。
        var worst = Double.MAX_VALUE
        var worstT = 0f
        var t = 0.12f
        while (t <= 1.0f + 1e-6f) {
            val ratio = ColorContrast.ratio(Color.White, LiuliBubbleGradient.colorAt(t))
            if (ratio < worst) {
                worst = ratio
                worstT = t
            }
            t += 0.01f
        }
        assertTrue("白字最差对比出现在 t=$worstT，仅 $worst（红线 4.5）", worst >= 4.5)
    }

    @Test fun contrastGetsStronger_downTheWindow() {
        // 单调性前提：越往下越深 → 白字越安全（若哪天中段被调亮，上面那条会先红）。
        val atKnee = ColorContrast.ratio(Color.White, LiuliBubbleGradient.colorAt(0.12f))
        val atBottom = ColorContrast.ratio(Color.White, LiuliBubbleGradient.colorAt(1f))
        assertTrue("窗口底端应比折点更暗（$atBottom vs $atKnee）", atBottom > atKnee)
    }
}
