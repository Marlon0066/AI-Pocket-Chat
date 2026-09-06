package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.tts.EmotionType
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 呼吸心情圈（契约 §5.1 灵感板 9 活头像第一层）：静环 2dp [ringColor]@0.9；动环 2dp 同色，2800ms 线性
 * scale 1→1.5 / alpha 0.6→0。[breathing] = false 时只画静环（减弱动画 / 调用方另有理由）。
 *
 * 2026-09-05 卷二C C6c **自 `LiuliChatTopBar` 只搬不改**抽出（原名 `LiuliBreathingRing`·private）：
 * 空会话引导 [LiuliEmptyHint] 要用同一枚圈，且必须与顶栏同源取色。像素零差由 `LiuliChatTopBarTest`
 * 全绿担保。唯一签名变化：减弱动画从件内自读改为调用方传 [breathing]（顶栏传
 * `!rememberReduceMotion()` = 原行为）。
 */
@Composable
internal fun LiuliMoodRing(
    ringColor: Color,
    size: Dp,
    breathing: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val progress: State<Float> = if (!breathing) {
        remember { mutableStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "liuliBreathRing").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(RING_CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "liuliBreathRingPhase",
        )
    }
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                val stroke = RING_WIDTH.toPx()
                val staticRadius = (this.size.minDimension - stroke) / 2f
                drawCircle(color = ringColor, radius = staticRadius, alpha = RING_STATIC_ALPHA, style = Stroke(stroke))
                if (breathing) {
                    val t = progress.value
                    val scale = 1f + (RING_MAX_SCALE - 1f) * t
                    drawCircle(
                        color = ringColor,
                        radius = staticRadius * scale,
                        alpha = RING_PULSE_ALPHA * (1f - t),
                        style = Stroke(stroke),
                    )
                }
            },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/**
 * 心情圈取色（自 `LiuliChatTopBar` 只搬不改）：无心情 emoji 时退 `accent.primary`，
 * 否则按 [liuliMoodFamily] 落到四色 + 平静。顶栏与空会话引导共用这一处，两处永不漂移。
 */
@Composable
internal fun liuliMoodRingColor(moodEmoji: String): Color {
    val colors = AppTheme.colors
    return if (moodEmoji.isEmpty()) {
        colors.accent.primary
    } else {
        when (liuliMoodFamily(EmotionType.from(moodEmoji))) {
            LiuliMoodFamily.JOY -> colors.emotion.joy
            LiuliMoodFamily.SHY -> colors.emotion.shy
            LiuliMoodFamily.SAD -> colors.emotion.sad
            LiuliMoodFamily.ANGER -> colors.emotion.anger
            LiuliMoodFamily.CALM -> colors.emotion.calm
        }
    }
}

/** 呼吸环落值（图纸 §9 ② 锁：2800ms · scale 1→1.5 · alpha .6→0）。 */
private const val RING_CYCLE_MS = 2800
private const val RING_MAX_SCALE = 1.5f
private const val RING_STATIC_ALPHA = 0.9f
private const val RING_PULSE_ALPHA = 0.6f
private val RING_WIDTH = 2.dp
