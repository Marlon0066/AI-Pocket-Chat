package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette

/**
 * 用户气泡的「渐变窗口」（图纸 2026-09-05 卷二A §3.2 锁 · 契约 §5.3 灵感板 2）：一道**锚定屏幕**的竖向渐变，
 * 每个气泡按自身在窗口里的 y 取样——满屏看去像透过气泡看见同一片天。
 *
 * 三 stop 由图纸 §0 ② 3 作者修订落定（原契约中段 `#2F7CF2` 白字仅 4.0 过不了红线）：
 * `0.00 #6FB1FF · 0.12 #2570E8 · 1.00 #3B3FC6`（末 stop 由 C7 换成「钴 → 紫」·用户 2026-09-05 选乙·
 * 图纸 §4.16）；0–12% 过渡带只落在顶栏 + 世界胶囊的 chrome 区（§4.7 算出 chrome 底 ≈ 屏高 12%），
 * 气泡进入该带时已在玻璃之下。**取样区间 [0.12, 1] 对白字恒 ≥ 4.5**（T1-1 逐 0.01 钉·换色后底端由
 * 6.4 提到 7.7）。中 stop 与 [Palette.Cobalt26GradStart] 同源单值，末 stop 走
 * [LiuliPalette].bubbleGradientEnd（琉璃裸 `Color` 的唯一出口）。
 */
object LiuliBubbleGradient {

    /** 顶端亮天蓝：`Palette` 无对应项（钴蓝族最亮档是夜档文字色 `#6FA8FF`，不同值），故为本卷唯一渐变字面量。 */
    private val Top = Color(0xFF6FB1FF)

    /** `(位置, 颜色)` 升序·位置为窗口高度占比。 */
    val stops: List<Pair<Float, Color>> = listOf(
        0f to Top,
        0.12f to Palette.Cobalt26GradStart,
        1f to LiuliPalette.bubbleGradientEnd,
    )

    /**
     * 窗口高度占比 [t] 处的渐变色（分段**线性**插值·逐通道在 sRGB 上算）。[t] 自动钳到 `[0, 1]`——
     * 气泡被滚出窗口外时取端点色，绝不外推。
     */
    fun colorAt(t: Float): Color {
        val clamped = t.coerceIn(0f, 1f)
        for (i in 0 until stops.size - 1) {
            val (p0, c0) = stops[i]
            val (p1, c1) = stops[i + 1]
            if (clamped <= p1) {
                val f = if (p1 == p0) 0f else (clamped - p0) / (p1 - p0)
                return Color(
                    red = c0.red + (c1.red - c0.red) * f,
                    green = c0.green + (c1.green - c0.green) * f,
                    blue = c0.blue + (c1.blue - c0.blue) * f,
                    alpha = 1f,
                )
            }
        }
        return stops.last().second
    }
}

/**
 * 一枚气泡在窗口里的锚（渐变窗口 + 尾色共用）：位置与窗口高度在 `onGloballyPositioned` 里写入，
 * 只被 draw 期的 lambda 读 → 滚动时**只重绘不重组**。
 */
@Stable
internal class LiuliBubbleAnchor {
    var yInRoot by mutableStateOf(0f)
    var rootHeight by mutableStateOf(1f)
}

/** 把气泡挂上 [LiuliBubbleAnchor]（放在最外层，量的是气泡自身在窗口里的位置）。 */
internal fun Modifier.liuliBubbleAnchor(anchor: LiuliBubbleAnchor): Modifier =
    this.onGloballyPositioned { coords ->
        anchor.yInRoot = coords.positionInRoot().y
        anchor.rootHeight = coords.findRootCoordinates().size.height.toFloat().coerceAtLeast(1f)
    }

/** 渐变窗口在气泡内的取样点数（首尾 + 中间 3 点·足以还原 0.12 处的折点，不必逐像素）。 */
private const val BUBBLE_GRADIENT_SAMPLES = 5

/**
 * 把 [LiuliBubbleGradient] 的对应切片画进本气泡（屏幕锚定·图纸 §4.4）。
 * [yInRoot] / [rootHeightPx] 都是**取值 lambda**：在 draw 期读，位置变化只重绘不重组。
 */
internal fun Modifier.liuliUserBubbleGradient(
    yInRoot: () -> Float,
    rootHeightPx: () -> Float,
): Modifier = this.drawBehind {
    val rootH = rootHeightPx().takeIf { it > 0f } ?: size.height.coerceAtLeast(1f)
    val top = yInRoot()
    val stops = Array(BUBBLE_GRADIENT_SAMPLES) { i ->
        val f = i.toFloat() / (BUBBLE_GRADIENT_SAMPLES - 1)
        f to LiuliBubbleGradient.colorAt((top + size.height * f) / rootH)
    }
    drawRect(Brush.verticalGradient(colorStops = stops, startY = 0f, endY = size.height))
}
