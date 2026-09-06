package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.LiuliGlassStyle
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 手势落值**逐字**照抄暖陶 `SwipeToReply.kt:33-35`（图纸 §9 ④ 机制锁·改一侧必改另一侧）。 */
private val SWIPE_MAX = 80.dp        // 气泡右移上限。
private val SWIPE_THRESHOLD = 60.dp  // 越此距离触发引用 + 触觉。
private val SWIPE_REVEAL = 4.dp      // 越此距离才显回弹箭头。

/**
 * 琉璃右滑引用（图纸 2026-09-05 卷二B §4.8）：手势块**逐字搬**暖陶 `SwipeToReplyBox`
 * （80 / 60 / 4 三个落值、双段触觉「越阈 medium 预告 + 松手 light 落定」、限幅、回弹弹簧全同），
 * 只把左缘那枚裸箭头换成一枚 28dp 玻璃圆——琉璃里凡是浮在内容之上的小件都是玻璃。
 *
 * 越阈色改 `accent.text`（暖陶用 `accent.primary`——那是实底档，压在玻璃圆上对比不足）。
 *
 * ⚠️ 结构恒定铁律（REDLINES §7）：禁用态**只关手势，绝不换子树**——`if (!enabled) { content(); return }`
 * 会让 content 落到不同调用点 → Compose 视为全新子树整体重建 → 气泡「占位 → 显形」的变身动画状态清零。
 */
@Composable
internal fun LiuliSwipeToReplyBox(
    enabled: Boolean,
    onTriggered: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val density = LocalDensity.current
    val dark = LocalIsDarkTheme.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val maxPx = with(density) { SWIPE_MAX.toPx() }
    val thresholdPx = with(density) { SWIPE_THRESHOLD.toPx() }
    val revealPx = with(density) { SWIPE_REVEAL.toPx() }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }
    // 1:1 iOS .spring(duration:0.3, bounce:0.1)。
    val springBack = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)

    Box {
        val progress = (offsetX.value / thresholdPx).coerceIn(0f, 1f)
        val arrowScale by animateFloatAsState(
            targetValue = if (offsetX.value >= thresholdPx) ARROW_SCALE_OVER else ARROW_SCALE_UNDER,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "liuliSwipeReplyArrowScale",
        )
        if (offsetX.value > revealPx) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(LiuliChatGeometry.swipeArrow)
                    .graphicsLayer {
                        scaleX = arrowScale
                        scaleY = arrowScale
                        alpha = progress
                    }
                    .liuliGlass(CircleShape, dark = dark, style = LiuliGlassStyle.Button),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = if (offsetX.value >= thresholdPx) AppTheme.colors.accent.text else LiuliTheme.onGlass.primary,
                    modifier = Modifier.size(ARROW_ICON_SIZE),
                )
            }
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
                        // 右滑限幅 0~80dp：左滑（负位移）钳到 0。
                        val target = (offsetX.value + dragAmount).coerceIn(0f, maxPx)
                        scope.launch { offsetX.snapTo(target) }
                        if (target >= thresholdPx && !hasTriggeredHaptic) {
                            hasTriggeredHaptic = true // 一次性
                            haptics.medium() // 越阈预告（双段之一）
                        }
                    }
                },
        ) {
            content()
        }
    }
}

// 落值（图纸 §3.2 右滑箭头一节·缩放两档与暖陶同值）。
private const val ARROW_SCALE_OVER = 1.15f
private const val ARROW_SCALE_UNDER = 0.85f
private val ARROW_ICON_SIZE = 16.dp
