package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import kotlin.math.sin

/**
 * 细波形（**重打**暖陶 `VoiceWaveform` 的算法：sin 包络 + 静态形 + 级联长出 + 已播着色），
 * 只把条宽 / 间距换成琉璃档（3 / 2·对版稿 `.voice .wave i`）。
 */
@Composable
internal fun LiuliVoiceWaveform(
    isPlaying: Boolean,
    progress: Float,
    playedColor: Color,
    unplayedColor: Color,
    barCount: Int,
    appearPlay: Boolean,
    onAppearPlayed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // RM 播放中走静态形（played 着色仍随 progress 推进 = 进度信息不丢）——照抄 F6。
    val animating = isPlaying && !rememberReduceMotion()
    val phaseState: State<Float> = if (animating) {
        rememberInfiniteTransition(label = "liuliWave").animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(WAVE_CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "liuliWavePhase",
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    val appearState: State<Float> = if (appearPlay) {
        val p = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            onAppearPlayed()
            p.animateTo(1f, tween(VOICE_CASCADE_MS, delayMillis = VOICE_CASCADE_DELAY_MS, easing = AppMotion.EaseOutQuint))
        }
        p.asState()
    } else {
        remember { mutableStateOf(1f) }
    }

    Canvas(modifier) {
        val phase = phaseState.value
        val appear = appearState.value
        val barWidth = LiuliChatGeometry.voiceBarWidth.toPx()
        val slot = barWidth + LiuliChatGeometry.voiceBarGap.toPx()
        val maxHeight = size.height
        val minHeight = LiuliChatGeometry.voiceBarWidth.toPx()
        var i = 0
        while (i < barCount) {
            val x = i * slot
            if (x + barWidth > size.width) break
            val norm = if (barCount <= 1) 0f else i.toFloat() / (barCount - 1)
            val envelope = 0.78f + sin(norm * Math.PI.toFloat()) * 0.42f
            val fraction = if (animating) {
                0.55f + sin(phase + i * 0.9f) * 0.33f * envelope
            } else {
                STATIC_PATTERN[i % STATIC_PATTERN.size]
            }
            val h = (fraction * maxHeight).coerceIn(minHeight, maxHeight)
            val bart = (appear * barCount - i).coerceIn(0f, 1f)
            val grown = minHeight + (h - minHeight) * bart
            val played = (i + 1).toFloat() / barCount <= progress
            drawRoundRect(
                color = if (played) playedColor else unplayedColor,
                topLeft = Offset(x, (maxHeight - grown) / 2f),
                size = Size(barWidth, grown),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
            i++
        }
    }
}


/** 静止态条高比例（照抄暖陶 `STATIC_PATTERN`·对齐 iOS heightForStatic 的相对节奏）。 */
private val STATIC_PATTERN = floatArrayOf(0.30f, 0.45f, 0.60f, 0.85f, 0.70f, 1.0f, 0.85f, 0.55f, 0.70f, 0.45f, 0.60f, 0.30f)

/** 波形落值（F6 照抄的动画时长·孤值即打回）。 */
private const val WAVE_CYCLE_MS = 1000
private const val VOICE_CASCADE_MS = 600
private const val VOICE_CASCADE_DELAY_MS = 90
