package com.situ.aichat.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
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

private val CHECKBOX_SIZE = 22.dp
private val CHECKBOX_SHAPE = RoundedCornerShape(7.dp)

/**
 * Fable-5 自研勾选框（去谷歌化批一过审）——取代 M3 `Checkbox`。
 *
 * 22dp 柔圆方（圆角 7dp）：未选 = 1.6dp [AppColors.text] tertiary 描边；选中 = 品牌渐层实底
 * （[AppColors.accent] gradientStart→gradientEnd）+ 圆头手绘对勾（[AppColors.accent] onPrimary）。
 * [onCheckedChange] 为 null = 纯显示态（整行已接管点击）。
 */
@Composable
fun AppCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else AppMotion.calmSpring(),
        label = "checkboxProgress",
    )
    val gradient = Brush.linearGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd))
    val checkColor = colors.accent.onPrimary

    Box(
        modifier = modifier
            .then(if (onCheckedChange != null) Modifier.minimumInteractiveComponentSize() else Modifier)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onValueChange = { next ->
                            haptics.light()
                            onCheckedChange(next)
                        },
                    )
                } else {
                    Modifier
                },
            )
            .alpha(if (enabled) 1f else 0.38f)
            .size(CHECKBOX_SIZE)
            .clip(CHECKBOX_SHAPE)
            .border(1.6.dp, colors.text.tertiary.copy(alpha = (1f - progress).coerceIn(0f, 1f)), CHECKBOX_SHAPE)
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
