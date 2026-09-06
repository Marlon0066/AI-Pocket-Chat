package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay

/**
 * 琉璃录音声波条（图纸 2026-09-05 卷二B §4.1 · 契约 §5.2「录音 = 输入胶囊整条变声波」）：44dp 玻璃 pill，
 * `[红点][计时][滚动波形][提示]`。替换暖陶 `VoiceRecordingOverlay`——**结构不变**（仍叠在隐身中段之上、
 * 录音手势 owner 仍是 `LiuliMicButton`，REDLINES §7 机制零碰），只换长相。与暖陶 5 根「电平柱」的分别：
 * 这里画的是**历史**（[LiuliWaveHistory] 环形缓冲·新样本自右进左出），像 Telegram 那样滚动。
 */
@Composable
internal fun LiuliRecordingBar(
    level: Float,
    durationMs: Long,
    cancelling: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    /** 波形历史（默认随组合新建、重建即空·E3）；测试可注入以观察采样节拍。 */
    history: LiuliWaveHistory = remember { LiuliWaveHistory(WAVE_CAPACITY) },
) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val dark = LocalIsDarkTheme.current
    // 图纸 §3.2：红点 / 取消态整条一律走 status 家族的功能红深档（深浅双档·≥4.5:1）。
    val alarm = colors.status.onError
    val waveColor = if (cancelling) alarm else colors.accent.primary

    // 采样按**时间**推格（每 80ms 一格 ≈ 12.5 样本/秒），不按「level 变化」推（复核 R1 🟡-5）：录音器
    // （`VoiceMessageRecorder`·1024 样本 = 64ms 一帧）的 StateFlow 会把相同电平去重，数字静音 = 恒 0 ⇒ 按变化推
    // 的波形会**冻在最后几根高柱上不再滚动**；按时间推则静音时源源不断进 0 ⇒ 波形滚成 6dp 底高（E1）。
    val latestLevel by rememberUpdatedState(level)
    LaunchedEffect(history) {
        while (true) {
            history.push(latestLevel)
            delay(WAVE_SAMPLE_MS)
        }
    }
    // 红点闪烁：全周期 1000ms（alpha 1 → 0.3 → 1）⇒ Reverse 的半程 = 500ms（PITFALLS §1d）。RM 恒亮。
    val dotAlpha by rememberInfiniteTransition(label = "liuliRecordingDot").animateFloat(
        initialValue = 1f,
        targetValue = DOT_DIM_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(DOT_BLINK_HALF_MS, easing = AppMotion.EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liuliRecordingDotAlpha",
    )
    val dotVisibleAlpha = if (reduceMotion || cancelling) 1f else dotAlpha

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(LiuliChatGeometry.inputPieceSize)
            .liuliGlass(LiuliShapes.pill, dark = dark)
            .padding(start = BAR_START_PADDING, end = BAR_END_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BAR_PIECE_GAP),
    ) {
        Box(
            Modifier
                .size(LiuliChatGeometry.recordingDot)
                .alpha(dotVisibleAlpha)
                .clip(CircleShape)
                .background(alarm),
        )
        Text(
            liuliFormatVoiceDuration(durationMs),
            style = AppTypography.amount,
            color = onGlass.primary,
            maxLines = 1,
            modifier = Modifier.widthIn(min = DURATION_MIN_WIDTH),
        )
        LiuliWaveform(history, waveColor, Modifier.weight(1f).fillMaxHeight())
        Text(
            if (cancelling) {
                stringResource(R.string.voice_recording_cancel_hint)
            } else {
                stringResource(R.string.voice_recording_hint) + HINT_SEPARATOR +
                    stringResource(R.string.liuli_voice_release_to_send)
            },
            style = AppTypography.settingsRowValue,
            color = if (cancelling) alarm else onGlass.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 滚动波形：最近 N 根自右向左排，不足时左侧留空（N 由可用宽算，绝不写死根数）。 */
@Composable
private fun LiuliWaveform(history: LiuliWaveHistory, color: Color, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val barW = WAVE_BAR_WIDTH.toPx()
        val gap = WAVE_BAR_GAP.toPx()
        val step = barW + gap
        if (step <= 0f || size.width <= 0f) return@Canvas
        val slots = ((size.width + gap) / step).toInt()
        if (slots <= 0) return@Canvas
        // 在 draw 相位读快照（history.version）——新样本只触发重绘，不触发重组。
        val samples = history.latest(slots)
        val radius = CornerRadius(barW / 2f, barW / 2f)
        samples.forEachIndexed { i, sample ->
            val h = liuliWaveBarHeightDp(sample).toPx().coerceAtMost(size.height)
            val x = size.width - barW - i * step
            if (x + barW < 0f) return@forEachIndexed
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * 波形历史环形缓冲（纯逻辑·T1-1）：定容 [capacity]，满后先进先出。[version] 是快照计数——画布在
 * draw 相位读它即可在新样本到来时重绘（零重组）。
 */
internal class LiuliWaveHistory(private val capacity: Int) {
    private val buffer = FloatArray(capacity)
    private var count = 0
    private var head = 0

    /** 供 Compose draw 相位订阅的失效计数（每次 [push] +1）。 */
    var version by mutableIntStateOf(0)
        private set

    val size: Int get() = count

    fun push(level: Float) {
        if (capacity <= 0) return
        buffer[head] = level
        head = (head + 1) % capacity
        if (count < capacity) count++
        version++
    }

    /** 最近 [n] 个样本，**新的在前**（索引 0 = 最新一格 = 画在最右）。 */
    fun latest(n: Int): List<Float> {
        @Suppress("UNUSED_VARIABLE")
        val subscribe = version
        val take = n.coerceAtMost(count).coerceAtLeast(0)
        return List(take) { i -> buffer[Math.floorMod(head - 1 - i, capacity)] }
    }
}

/** 单根波形柱高（对版稿 6–22px）：钳位在 [0,1] 后线性插值，纯函数便于 T1-1。 */
internal fun liuliWaveBarHeightDp(level: Float): Dp =
    WAVE_BAR_MIN_HEIGHT + WAVE_BAR_HEIGHT_RANGE * level.coerceIn(0f, 1f)

/**
 * 时长 `M:SS`（暖陶 `VoiceInputComposer.kt` 的 `formatVoiceDuration` 是 private，本卷**重打同值**；改任一侧
 * 必须同步另一侧——不抽公共层是有意的边界纪律，见图纸 §2.1）。
 */
internal fun liuliFormatVoiceDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

// 落值（图纸 §3.2 声波条一节·孤值即打回）。
private const val WAVE_CAPACITY = 40
/** 波形采样节拍（复核 R1 🟡-5·= 图纸原写的「VM 80ms tick」那一档，改为本件自己的时钟）。 */
internal const val WAVE_SAMPLE_MS = 80L
private val WAVE_BAR_WIDTH = 3.dp
private val WAVE_BAR_GAP = 2.dp
private val WAVE_BAR_MIN_HEIGHT = 6.dp
private val WAVE_BAR_HEIGHT_RANGE = 16.dp
private const val DOT_BLINK_HALF_MS = 500
private const val DOT_DIM_ALPHA = 0.3f
private val BAR_START_PADDING = 14.dp
private val BAR_END_PADDING = 12.dp
private val BAR_PIECE_GAP = 10.dp
private val DURATION_MIN_WIDTH = 40.dp
private const val HINT_SEPARATOR = " · "
