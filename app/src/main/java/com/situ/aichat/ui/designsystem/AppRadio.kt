package com.situ.aichat.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * Fable-5 陶珠单选（六件套草图 2026-07-17 过审）——取代 M3 `RadioButton` 的空心圆 + 实心点。
 *
 * 20dp 正圆。**未选** = 1.5dp [AppColors.text] tertiary 描边、不填充；**选中** = [AppColors.accent]
 * gradientStart→gradientEnd 斜向渐变实心 + 居中 7dp 白瓷点（浅档取 [AppColors.surface] glaze、
 * 深档取 text primary —— 对版稿的 `#EDE8E2` 正是深档的 text primary）。
 *
 * 切换时中点走 [AppMotion.calmSpring] 缩放（位移轴·像一颗珠子按下去），底色走
 * [AppMotion.effectMediumSpring]（效果轴）；[rememberReduceMotion] 为真时两者都 `snap()`。
 *
 * [onClick] 为 null = 纯显示态（整行 `selectable` 已接管点击的场景）：本件不可点，但 `Role.RadioButton`
 * 与选中语义仍在（读屏要念得出「单选按钮，已选中」）。
 */
@Composable
fun AppRadio(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val dotScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else AppMotion.calmSpring(),
        label = "radioDot",
    )
    val fillAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "radioFill",
    )
    val gradient = Brush.linearGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd))
    val dotColor = if (colors.isDark) colors.text.primary else colors.surface.glaze
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(
                if (onClick != null) {
                    Modifier.selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { haptics.light(); onClick() },
                    )
                } else {
                    Modifier
                },
            )
            .size(20.dp)
            // 未选的描边随选中淡出——选中态是实心陶土珠，不该再套一圈灰边。
            .border(1.5.dp, colors.text.tertiary.copy(alpha = 1f - fillAlpha), AppShapes.full)
            .drawBehind {
                if (fillAlpha > 0f) {
                    drawCircle(brush = gradient, radius = size.minDimension / 2f, alpha = fillAlpha)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .scale(dotScale)
                .size(7.dp)
                .drawBehind { drawCircle(color = dotColor, radius = size.minDimension / 2f) },
        )
    }
}
