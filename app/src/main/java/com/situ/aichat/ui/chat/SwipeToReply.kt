package com.situ.aichat.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SWIPE_MAX = 80.dp        // 1:1 iOS maxOffset：气泡右移上限。
private val SWIPE_THRESHOLD = 60.dp  // 1:1 iOS threshold：越此距离触发引用 + 触觉。
private val SWIPE_REVEAL = 4.dp      // 越此距离才显回弹箭头。

/**
 * chat-ui-4 右滑引用（行为 1:1 iOS SwipeToReplyModifier）：在气泡上右滑 0~80dp，越 60dp 阈值触发（松手后）
 * [onTriggered]（= 设引用目标），左缘渐显 `arrow.uturn.backward` 回弹箭头（越阈值「弹大」），松手 spring 弹回。
 *
 * C3-haptics（契约 §3.5）：双段触觉=越阈一次性 medium **预告** + 松手真触发 light **落定**；箭头越阈染
 * accent.primary（陶土玫因果线「即将引用」），未越阈 text.tertiary。全经 [LocalAppHaptics] 分级语义。
 *
 * 与纵向滚动共存：`detectHorizontalDragGestures` 仅在水平 slop 越过后才认领指针，纵向滚动自然胜出（= iOS
 * `abs(vx)>abs(vy)` 判定）。与 Android 预测式返回共存：气泡从不贴屏幕左右缘（AI 缩进 ~52dp、用户右对齐），系统
 * 边缘手势在到达气泡 pointerInput 前已被系统接管，故无需显式避让左缘（iOS 因 inverted scrollview 才需 30pt 守卫）。
 * [enabled]=`message.isContentRevealed && messageCanBeQuoted(message)`——流式占位气泡不可引用（1:1 iOS），
 * 且自 2026-09-04 用户拍板起**只有正文有话可引的气泡可引用**（纯文字/贴纸/转写到位的语音；图片、各类卡片、
 * 占位转写的语音一律滑不动·判据单源见 [com.situ.aichat.ui.chat.messageCanBeQuoted]）。用户与 AI 两侧同等适用。
 *
 * ⚠️ 结构恒定铁律（2026-07-08 V9 根因修复）：禁用态**只关手势，绝不换子树**——旧写法 `if (!enabled) { content();
 * return }` 让 content 在 enabled 翻转时落到不同调用点 → Compose 视为全新子树整体重建 → 「占位→显形」瞬间
 * 气泡的变身动画状态清零、直接渲染终态 = 三点一帧瞬变正文（用户所报抖动的总根源·逐帧+隔离测试双实证）。
 */
@Composable
fun SwipeToReplyBox(
    enabled: Boolean,
    onTriggered: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val maxPx = with(density) { SWIPE_MAX.toPx() }
    val thresholdPx = with(density) { SWIPE_THRESHOLD.toPx() }
    val revealPx = with(density) { SWIPE_REVEAL.toPx() }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }
    // 1:1 iOS .spring(duration:0.3, bounce:0.1)。
    val springBack = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)

    Box {
        // 回弹箭头藏在气泡左缘后，气泡右滑露出；越阈值从 0.85→1.15「弹大」+ 随位移渐显（1:1 iOS overlay leading）。
        val progress = (offsetX.value / thresholdPx).coerceIn(0f, 1f)
        val arrowScale by animateFloatAsState(
            targetValue = if (offsetX.value >= thresholdPx) 1.15f else 0.85f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "swipeReplyArrowScale",
        )
        if (offsetX.value > revealPx) {
            Icon(
                Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = if (offsetX.value >= thresholdPx) AppTheme.colors.accent.primary else AppTheme.colors.text.tertiary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(20.dp)
                    .graphicsLayer {
                        scaleX = arrowScale
                        scaleY = arrowScale
                        alpha = progress
                    },
            )
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                // enabled 作 pointerInput key：翻转时重启手势协程——禁用态不装手势（结构不变只关交互）。
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { hasTriggeredHaptic = false },
                        onDragEnd = {
                            if (offsetX.value >= thresholdPx) {
                                haptics.light() // 落定（双段之二）
                                onTriggered()
                            }
                            scope.launch { offsetX.animateTo(0f, springBack) }
                            hasTriggeredHaptic = false
                        },
                        onDragCancel = {
                            scope.launch { offsetX.animateTo(0f, springBack) }
                            hasTriggeredHaptic = false
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        // 右滑限幅 0~80dp：左滑（负位移）钳到 0（1:1 iOS min(max(tx,0),80)）。
                        val target = (offsetX.value + dragAmount).coerceIn(0f, maxPx)
                        scope.launch { offsetX.snapTo(target) }
                        if (target >= thresholdPx && !hasTriggeredHaptic) {
                            hasTriggeredHaptic = true // 一次性（1:1 iOS hasTriggeredHaptic）。
                            haptics.medium() // 越阈预告（双段之一·= iOS .medium impact）
                        }
                    }
                },
        ) {
            content()
        }
    }
}
