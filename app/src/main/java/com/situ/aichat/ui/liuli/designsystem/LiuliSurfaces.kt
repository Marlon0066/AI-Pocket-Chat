package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃的两个面修饰符（契约 FABLE5_THEME_LIULI_PROPOSAL.md §3.4 / §4.1 · 图纸 §4.4）。
 *
 * 与暖陶的分工：琉璃里**只有导航层是玻璃**（[com.situ.aichat.ui.liuli.glass.liuliGlass]），内容层的卡是
 * 实心纸面——所以这里的卡面**无影、无颗粒**，只有一道发丝，跟暖陶的 `appCardSurface`（软影 + 颗粒）不是一回事。
 */

/** 琉璃卡面：实心纸面 + 0.5dp 发丝，无影无颗粒（内容层不抢导航层玻璃的层次）。 */
fun Modifier.liuliCardSurface(shape: Shape = LiuliShapes.medium): Modifier = composed {
    val colors = AppTheme.colors
    this
        .clip(shape)
        .background(colors.surface.raised)
        .border(0.5.dp, colors.surface.stroke, shape)
}

/**
 * 琉璃按压反馈（灵感板 12 · 契约 §4.1）：缩 0.96 + 可选提亮（叠白 昼 6% / 夜 8%）。
 *
 * 提亮叠在**内容之上**——它是玻璃整片的亮度反馈，不是底垫；只在 180ms 量级的按压态出现，白 6% 叠深字
 * 只会让字略浅（`#111318` + 白 6% ≈ `#1F2126`，对玻璃合成底仍 >7:1），不破对比红线。
 * [rememberReduceMotion] 时不缩不亮（触觉与 ripple 由调用方保留）。
 */
internal fun Modifier.liuliPressable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    brighten: Boolean,
): Modifier = composed {
    val dark = LocalIsDarkTheme.current
    val reduceMotion = rememberReduceMotion()
    val pressed by interactionSource.collectIsPressedAsState()
    val active = pressed && enabled && !reduceMotion
    val scale by animateFloatAsState(
        targetValue = if (active) 0.96f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "liuliPress",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(
            if (brighten) {
                Modifier.drawWithContent {
                    drawContent()
                    if (active) drawRect(Color.White, alpha = if (dark) 0.08f else 0.06f)
                }
            } else {
                Modifier
            },
        )
}
