package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.liuli.page.liuliFootprint

private val LIULI_CB_SIZE = 22.dp
private val LIULI_CB_SHAPE = RoundedCornerShape(7.dp)

/**
 * 琉璃 2.0 自研勾选框（去谷歌化批一过审）——取代 M3 `Checkbox`。
 *
 * 22dp 柔圆方（圆角 7dp），版位恒 22×22、触达 48 由 [liuliFootprint] 居中外溢；选中走鸢尾紫渐变
 * （`AppTheme.colors.accent` gradientStart→gradientEnd）+ 亮白圆头对勾，切态 `haptics.selection()`。
 */
@Composable
fun LiuliCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else AppMotion.calmSpring(),
        label = "liuliCheckbox",
    )
    val gradient = Brush.linearGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd))
    val checkColor = colors.accent.onPrimary

    Box(
        modifier = modifier
            .then(if (onCheckedChange != null) Modifier.liuliFootprint(LIULI_CB_SIZE) else Modifier.size(LIULI_CB_SIZE))
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onValueChange = { next ->
                            haptics.selection()
                            onCheckedChange(next)
                        },
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .alpha(if (enabled) 1f else 0.38f)
                .size(LIULI_CB_SIZE)
                .clip(LIULI_CB_SHAPE)
                .border(1.6.dp, colors.text.tertiary.copy(alpha = (1f - progress).coerceIn(0f, 1f)), LIULI_CB_SHAPE)
                .drawBehind {
                    if (progress > 0.01f) {
                        drawRect(brush = gradient, alpha = progress.coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (progress > 0.05f) {
                Canvas(Modifier.size(14.dp)) {
                    val p = progress.coerceIn(0f, 1f)
                    val path = Path().apply {
                        moveTo(size.width * 0.18f, size.height * 0.52f)
                        lineTo(size.width * 0.42f, size.height * 0.75f)
                        lineTo(size.width * 0.84f, size.height * 0.28f)
                    }
                    drawPath(
                        path = path,
                        color = checkColor.copy(alpha = p),
                        style = Stroke(width = 2.1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}
