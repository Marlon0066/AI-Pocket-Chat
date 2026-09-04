package com.situ.aichat.ui.designsystem

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.rememberReduceMotion

/** 陶环三档尺寸（六件套草图 §2.6·36 / 24 / 16dp）。 */
enum class AppLoadingRingSize(val diameter: Dp) {
    /** 整屏等待（`AppRoot` 冷启、备份导入…）。 */
    Large(36.dp),

    /** 卡内 / 段内等待（默认）。 */
    Medium(24.dp),

    /** 行内 / 按钮内等待。 */
    Small(16.dp),
}

/**
 * Fable-5 加载陶环（六件套草图 2026-07-17 过审·拍板④「加载 = 陶环，不是三点」）——取代 M3
 * `CircularProgressIndicator`。
 *
 * 画法：`drawArc` 铺一整圈 [Brush.sweepGradient]，色标即对版稿 `conic-gradient` 的 token 化——
 * 从透明起笔，经 [AppColors.accent] gradientStart 35% 透明度，收在 accent primary → accent text，
 * 于是环有「头淡尾浓」的转动感，而不是 M3 那种匀色缺口圈。环宽恒 **3.5dp**、[StrokeCap.Round] 圆头帽。
 *
 * 转速 **1s 匀速**（`tween(1000, LinearEasing)`）——**禁用弹簧**：转圈是恒速的机械动作，
 * 任何缓动都会读成「卡了一下」。
 *
 * [rememberReduceMotion] 为真时**不转**，画一段静态 3/4 环（270°）——契约明文：关动画的人要看到
 * 「在忙」的静态记号，而不是一个完整的圆（那读起来像「已完成」）。
 *
 * @param contentDescription 非空时挂读屏名；为空 = 纯装饰（收编站按原 `CircularProgressIndicator`
 *   有没有语义照搬，不新增也不删）。
 */
@Composable
fun AppLoadingRing(
    modifier: Modifier = Modifier,
    size: AppLoadingRingSize = AppLoadingRingSize.Medium,
    contentDescription: String? = null,
) {
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()
    val angle by if (reduceMotion) {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "loadingRing").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "loadingRingAngle",
        )
    }
    val brush = Brush.sweepGradient(
        0f to colors.accent.primary.copy(alpha = 0f),
        0.14f to colors.accent.primary.copy(alpha = 0f),
        0.34f to colors.accent.gradientStart.copy(alpha = 0.35f),
        0.72f to colors.accent.primary,
        1f to colors.accent.text,
    )
    Canvas(
        modifier = modifier
            .size(size.diameter)
            .graphicsLayer { rotationZ = angle }
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        val stroke = 3.5.dp.toPx()
        val inset = stroke / 2f
        drawArc(
            brush = brush,
            startAngle = -90f,
            sweepAngle = if (reduceMotion) 270f else 360f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
