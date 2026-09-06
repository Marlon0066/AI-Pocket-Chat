package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme

/** 转圈落值（§3.2）：直径 12 · 弧 270° · 一圈 1s 线性 · 线宽 1.5。 */
private const val SWEEP_DEGREES = 270f
private const val SPIN_MILLIS = 1000
private val STROKE = 1.5.dp

/**
 * 琉璃小转圈（图纸 2026-09-05 卷二C §4.11 · 落值 §3.2 · A-15）。
 *
 * 自绘一段 270° 圆弧匀速转，**禁 M3 `CircularProgressIndicator`**（§9 ⑤）。默认 12dp——它只用在
 * 「一行字旁边」的语境（草稿条「识别中…」），不是整屏 loading。[rememberReduceMotion] 为真时**静止**
 * 停在 −90°（12 点方向）起笔，形还在、不转。
 */
@Composable
fun LiuliSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    color: Color = AppTheme.colors.accent.text,
) {
    val reduceMotion = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "liuliSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SPIN_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "liuliSpinnerAngle",
    )
    Canvas(modifier.size(size)) {
        val stroke = STROKE.toPx()
        val start = if (reduceMotion) -90f else angle - 90f
        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = SWEEP_DEGREES,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke),
        )
    }
}
