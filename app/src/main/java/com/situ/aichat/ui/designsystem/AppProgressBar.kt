package com.situ.aichat.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * 把外部进度钳到 `0f..1f`；`NaN` 归 0（除零、未初始化的分母都会喂进 NaN，而 NaN 一路画下去是
 * 「宽度算不出来」的静默崩）。抽成 internal 纯函数是为了给 T1 直接出题。
 */
internal fun coerceProgress(raw: Float): Float = if (raw.isNaN()) 0f else raw.coerceIn(0f, 1f)

/**
 * Fable-5 条形进度（六件套草图 2026-07-17 过审）——取代 M3 `LinearProgressIndicator`。
 *
 * 轨 4dp 高、全圆角、[AppColors.surface] sunken；填充是 [AppColors.accent] gradientStart→gradientEnd
 * 横向渐变，同圆角。宽度按 [coerceProgress] 后的比例真算（`layout` 量出轨宽再乘，**不写死 dp**）。
 *
 * 过渡走 [AppMotion.effectMediumSpring]（效果轴·恒不过冲——进度条过冲会「倒退一下」，读起来像出错），
 * [rememberReduceMotion] 为真时 `snap()` 直落。
 *
 * **`progress = 0f` 时不画填充**：零宽的圆角矩形在部分驱动上会画出一个小圆点，看着像「已经开始了 1%」。
 *
 * 只做**确定态**——不确定态归陶环 [AppLoadingRing]（图纸 §0.4：不为不存在的场景造机制）。
 */
@Composable
fun AppProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()
    val target = coerceProgress(progress)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "progressBar",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(AppShapes.full)
            .background(colors.surface.sunken),
    ) {
        val fraction = coerceProgress(animated)
        if (fraction > 0f) {
            Box(
                Modifier
                    .layout { measurable, constraints ->
                        val width = (constraints.maxWidth * fraction).toInt()
                        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(width, placeable.height) { placeable.place(0, 0) }
                    }
                    .fillMaxHeight()
                    .clip(AppShapes.full)
                    .background(
                        Brush.horizontalGradient(
                            listOf(colors.accent.gradientStart, colors.accent.gradientEnd),
                        ),
                    ),
            )
        }
    }
}
