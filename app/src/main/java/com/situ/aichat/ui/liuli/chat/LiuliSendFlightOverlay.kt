package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.situ.aichat.ui.chat.ChatSendFlightState
import com.situ.aichat.ui.chat.FLIGHT_MAX_LINES
import com.situ.aichat.ui.chat.FLIGHT_MS
import com.situ.aichat.ui.chat.flightAlphaRamp
import com.situ.aichat.ui.chat.flightFrame
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.util.DateFormatters
import kotlin.math.roundToInt

/**
 * 琉璃发送变形飞入（图纸 2026-09-05 卷二B §4.5 · 契约 §5.2 · 机制契约 TELEGRAM_MOTION §4）：替换暖陶
 * `ChatSendFlightOverlay`。**机制逐条照抄**——单一 250ms 线性进度在渲染期派生三条曲线（横轴
 * [AppMotion.TgFlightX] / 纵轴 [AppMotion.TgMessageY] / 淡入斜坡 [flightAlphaRamp]）、移动靶终点、
 * >10 行降级闸、`finally` 交还真行；逐帧读取全压在 layout / draw 相位，飞行全程**零重组**。
 *
 * 琉璃的三处分叉（§3.3）：① 起点**无底色**——玻璃输入胶囊留在原位不复制，只把文字「拎起」，渐变泡在
 * 飞行中显形；② 圆角 lerp 22（胶囊）→ 18（琉璃泡）；③ 泡内戳画在泡里（不再外挂一行）——琉璃泡的时间戳
 * 本就浮在末行右下。渐变按帧矩形在窗口的 y 取 5 样，与真泡 `liuliUserBubbleGradient` 同公式 ⇒ 落地像素一致。
 */
@Composable
internal fun LiuliSendFlightOverlay(state: ChatSendFlightState, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 起飞门：>10 行降级（Telegram TMET:128·需目标排版故在此判）。过闸即起飞；不过 = 静默走普通入场。
    DisposableEffect(state, textMeasurer, density) {
        state.onLaunch = { message, targetBounds, _ ->
            val contentW = with(density) { targetBounds.width - (BubblePadStart + BubblePadEnd).toPx() }.roundToInt()
            if (contentW > 0) {
                val lines = textMeasurer.measure(
                    AnnotatedString(message.content),
                    style = AppTypography.body,
                    constraints = Constraints(maxWidth = contentW),
                ).lineCount
                if (lines <= FLIGHT_MAX_LINES) state.beginFlight(message, targetBounds)
            }
        }
        onDispose { state.onLaunch = null }
    }
    val flight = state.flight ?: return

    val progress = remember(flight) { Animatable(0f) }
    LaunchedEffect(flight) {
        try {
            progress.animateTo(1f, tween(FLIGHT_MS, easing = LinearEasing))
        } finally {
            state.endFlight() // 完成或中断（离屏）都交还真实行
        }
    }

    val onGlass = LiuliTheme.onGlass
    val timestampText = remember(flight) { DateFormatters.hourMinute(flight.timestampMs) }
    val sourceWidthPx = remember(flight) {
        with(density) { (flight.startBounds.width - (InputPadH * 2).toPx()).roundToInt().coerceAtLeast(1) }
    }
    val targetWidthPx = remember(flight) {
        with(density) { (flight.targetBounds.width - (BubblePadStart + BubblePadEnd).toPx()).roundToInt().coerceAtLeast(1) }
    }

    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var rootHeightPx by remember { mutableIntStateOf(0) }
    // 逐帧读取入口（只在布局 / 绘制相位调用）。
    val t = { progress.value }

    Layout(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                rootOffset = it.positionInWindow()
                rootHeightPx = it.findRootCoordinates().size.height
            },
        content = {
            Box(
                Modifier
                    .graphicsLayer {
                        shape = RoundedCornerShape(
                            lerp(InputCapsuleCorner, LiuliBubbleCorner, AppMotion.TgFlightX.transform(t())),
                        )
                        clip = true
                    }
                    .drawBehind {
                        val a = flightAlphaRamp(t())
                        if (a <= 0f) return@drawBehind
                        // 渐变窗口锚定屏幕：按本帧矩形在窗口里的 y 取样（与真泡同公式·§4.4）。
                        val ya = AppMotion.TgMessageY.transform(t())
                        val topInWindow =
                            flight.startBounds.top + (flight.targetBounds.top - flight.startBounds.top) * ya
                        val rootH = rootHeightPx.toFloat().coerceAtLeast(1f)
                        val stops = Array(GRADIENT_SAMPLES) { i ->
                            val f = i.toFloat() / (GRADIENT_SAMPLES - 1)
                            f to LiuliBubbleGradient.colorAt((topInWindow + size.height * f) / rootH)
                        }
                        drawRect(Brush.verticalGradient(colorStops = stops, startY = 0f, endY = size.height), alpha = a)
                    },
            ) {
                Layout(
                    content = {
                        // 双层全程共动 crossfade：源排版 = 输入胶囊宽、目标排版 = 气泡宽，前 40% 换血。
                        Text(
                            flight.text,
                            style = AppTypography.body,
                            color = onGlass.primary,
                            modifier = Modifier.graphicsLayer { alpha = 1f - flightAlphaRamp(t()) },
                        )
                        Text(
                            flight.text,
                            style = AppTypography.body,
                            color = Palette.White,
                            modifier = Modifier.graphicsLayer { alpha = flightAlphaRamp(t()) },
                        )
                        Text(
                            timestampText,
                            style = AppTypography.captionNumeric,
                            color = Palette.White.copy(alpha = STAMP_ALPHA),
                            modifier = Modifier.graphicsLayer { alpha = flightAlphaRamp(t()) },
                        )
                    },
                ) { measurables, constraints ->
                    val src = measurables[0].measure(Constraints(maxWidth = sourceWidthPx))
                    val dst = measurables[1].measure(Constraints(maxWidth = targetWidthPx))
                    val stamp = measurables[2].measure(Constraints())
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val xa = AppMotion.TgFlightX.transform(t())
                        val ya = AppMotion.TgMessageY.transform(t())
                        val px = lerp(InputPadH, BubblePadStart, xa).roundToPx()
                        val py = lerp(InputPadV, BubblePadTop, ya).roundToPx()
                        src.place(px, py)
                        dst.place(px, py)
                        stamp.place(
                            constraints.maxWidth - BubblePadEnd.roundToPx() - stamp.width,
                            constraints.maxHeight - BubblePadBottom.roundToPx() - stamp.height,
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val xa = AppMotion.TgFlightX.transform(t())
        val ya = AppMotion.TgMessageY.transform(t())
        val start = flight.startBounds.translate(-rootOffset.x, -rootOffset.y)
        val target = flight.targetBounds.translate(-rootOffset.x, -rootOffset.y)
        val frame = flightFrame(start, target, xa, ya)
        val bubble = measurables[0].measure(
            Constraints.fixed(
                frame.width.roundToInt().coerceAtLeast(1),
                frame.height.roundToInt().coerceAtLeast(1),
            ),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            bubble.place(frame.left.roundToInt(), frame.top.roundToInt())
        }
    }
}

/**
 * 发送一条消息的完整通路（纯函数·T2-5）：VM 受理 → 绕心情四色一步 → 交给飞入握手。
 *
 * A-7：清空输入框是 [commit]，由 [ChatSendFlightState.tryBegin] 决定「押后到新气泡就位那一帧」还是
 * 「立即」（闸关时立即 = 与旧写法同帧）。发送被拒（[send] 回 false）根本不进握手，输入框原样保留。
 */
internal fun liuliSendHandler(
    text: String,
    send: (String) -> Boolean,
    gatesOpen: Boolean,
    sendFlight: ChatSendFlightState,
    commit: () -> Unit,
    onAccepted: () -> Unit,
): Boolean {
    if (!send(text)) return false
    onAccepted()
    sendFlight.tryBegin(text, gatesOpen, commit)
    return true
}

// 落值（图纸 §3.2 飞行一节·孤值即打回）。
/** 输入胶囊圆角（44dp pill 的半径）与琉璃泡圆角（= `LiuliShapes.bubble`·此处参与 lerp 故取值常量）。 */
private val InputCapsuleCorner = 22.dp
private val LiuliBubbleCorner = 18.dp
/** 起点内边距（= `LiuliInputField` decorationBox 的 16 / 10）。 */
private val InputPadH = 16.dp
private val InputPadV = 10.dp
/** 终点内边距（= `LiuliTextBubbles` 的 12 / 11 / 7 / 6·两侧同值，改一处必改另一处）。 */
private val BubblePadStart = 12.dp
private val BubblePadEnd = 11.dp
private val BubblePadTop = 7.dp
private val BubblePadBottom = 6.dp
/** 泡内戳白 72%（= `LiuliTextBubbles.USER_STAMP_ALPHA`·两侧同值）。 */
private const val STAMP_ALPHA = 0.72f
/** 渐变取样点数（= `LiuliBubbleGradient` 的 BUBBLE_GRADIENT_SAMPLES·两侧同值）。 */
private const val GRADIENT_SAMPLES = 5
