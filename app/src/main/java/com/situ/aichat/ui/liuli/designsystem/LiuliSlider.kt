package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.liuli.page.liuliTouchHeight
import kotlin.math.roundToInt

/** 滑杆几何（§3.2）：轨 4 高 · 拇指 20 白 · 触达 48 外溢不占版。 */
private val TRACK_HEIGHT = 4.dp
private val THUMB = 20.dp
private const val TRACK_OFF_ALPHA = 0.15f
private const val DISABLED_ALPHA = 0.38f

/**
 * 琉璃滑杆（图纸 2026-09-05 卷二C §4.11 · 落值 §3.2 · A-15 / A-20）。
 *
 * 自绘，**禁 M3 `Slider`**（§9 ⑤）：轨 4dp 全圆角（未过段 = 玻璃上主文字 15%），已过段 =
 * `accent` gradientStart→End 横向渐变；拇指 20dp 纯白正圆 + 1dp 影（与 [LiuliSwitch] 同一枚白圆）。
 * A-20 明令 DIY 礼物的「花多少金币」保持滑杆，**不许改成档位 chip**（那是交互变化不是换皮）。
 *
 * 手势 = [draggable]（横向）；[steps] > 0 时逐格吸附并在跨格时 `haptics.selection()` 打一记「嗒」
 * （与暖陶滑杆的 Detent 触觉同一句话）。版位恒 20 高，触达由 `liuliTouchHeight` 上下外溢到 48。
 * a11y：[progressSemantics] 报当前值 / 值域 / 格数，外加 `setProgress` 让读屏能直接设值。
 *
 * [onValueChangeFinished]（卷四 A-7 / §11 D-3 增补·**加法零回归**：不传 = 与增补前逐字节同行为）在**松手**时落一次
 * ——给「拖动中只改本地态、松手才写库」这类时序用（暖陶 `QuietHoursSlider` 同时序）。
 */
@Composable
fun LiuliSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val density = LocalDensity.current
    val currentValue by rememberUpdatedState(value)
    var widthPx by remember { mutableIntStateOf(0) }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val thumbPx = with(density) { THUMB.toPx() }
    val travelPx = (widthPx - thumbPx).coerceAtLeast(1f)
    val trackOff = LiuliTheme.onGlass.primary.copy(alpha = TRACK_OFF_ALPHA)
    val gradient = Brush.horizontalGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd))

    /** 吸附到 [steps] 定义的格点（steps = 0 时连续）并钳进值域。 */
    fun snap(raw: Float): Float {
        val clamped = raw.coerceIn(valueRange.start, valueRange.endInclusive)
        if (steps <= 0) return clamped
        val slots = steps + 1
        val index = ((clamped - valueRange.start) / span * slots).roundToInt()
        return valueRange.start + index.toFloat() / slots * span
    }

    val dragState = rememberDraggableState { deltaPx ->
        val next = snap(currentValue + deltaPx / travelPx * span)
        if (next != currentValue) {
            if (steps > 0) haptics.selection()
            onValueChange(next)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(THUMB)
            .liuliTouchHeight()
            .onSizeChanged { widthPx = it.width }
            .progressSemantics(value, valueRange, steps)
            .semantics {
                setProgress { target ->
                    val next = snap(target)
                    if (next != currentValue) onValueChange(next)
                    // 读屏设值 = 「拖一下再松手」，故也要落一次收口回调（同 M3 `Slider` 的 SetProgress 语义）。
                    onValueChangeFinished?.invoke()
                    true
                }
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStopped = { onValueChangeFinished?.invoke() },
            )
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(THUMB)
                .drawBehind {
                    val radius = CornerRadius(TRACK_HEIGHT.toPx() / 2f, TRACK_HEIGHT.toPx() / 2f)
                    val trackTop = (size.height - TRACK_HEIGHT.toPx()) / 2f
                    // 轨左右各让出半个拇指，让已过段的末端永远被拇指盖住（无对齐补偿）。
                    val inset = thumbPx / 2f
                    val trackWidth = size.width - inset * 2f
                    drawRoundRect(
                        color = trackOff,
                        topLeft = Offset(inset, trackTop),
                        size = Size(trackWidth, TRACK_HEIGHT.toPx()),
                        cornerRadius = radius,
                    )
                    if (fraction > 0f) {
                        drawRoundRect(
                            brush = gradient,
                            topLeft = Offset(inset, trackTop),
                            size = Size(trackWidth * fraction, TRACK_HEIGHT.toPx()),
                            cornerRadius = radius,
                        )
                    }
                },
        )
        Box(
            Modifier
                .offset { IntOffset((travelPx * fraction).roundToInt(), 0) }
                .shadow(1.dp, CircleShape)
                .size(THUMB)
                .background(Color.White, CircleShape),
        )
    }
}
