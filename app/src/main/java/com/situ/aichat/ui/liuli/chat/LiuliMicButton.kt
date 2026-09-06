package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.liuli.glass.LiuliGlassStyle
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.liuli.page.liuliFootprint
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 琉璃「按住说话」键（图纸 2026-09-05 卷二A §2.1 / §4.5）。
 *
 * **手势块逐字搬自暖陶 `VoiceRecordButton`**（`pointerInput(Unit)` 只建一次 + 六个 `rememberUpdatedState`）——
 * REDLINES §7「录音手势 owner 跨态不卸载」与「捕获过期」两条都由这个结构保证，一个字不许改；
 * 换的只有视觉：44dp 玻璃圆 + 麦克风图标，录音中外扩 8dp 钴蓝光晕 + 呼吸缩放。
 */
@Composable
internal fun LiuliMicButton(
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    /** 带引用时不许录音（引用一期 E）；true → 按下即拦，本次手势作废。 */
    blocked: Boolean,
    onBlocked: () -> Unit,
    onStartRecording: () -> Unit,
    onDrag: (draggedUpDp: Float) -> Unit,
    onFinish: () -> Unit,
    recording: Boolean,
    cancelling: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val perm by rememberUpdatedState(hasMicPermission)
    val reqPerm by rememberUpdatedState(onRequestPermission)
    val blockedNow by rememberUpdatedState(blocked)
    val onBlockedNow by rememberUpdatedState(onBlocked)
    val start by rememberUpdatedState(onStartRecording)
    val drag by rememberUpdatedState(onDrag)
    val finish by rememberUpdatedState(onFinish)
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val pressScale by animateFloatAsState(
        targetValue = if (recording && !reduceMotion) VOICE_PRESS_SCALE else 1f,
        animationSpec = if (reduceMotion) snap() else AppMotion.gentleSpring(),
        label = "liuliVoicePressScale",
    )
    val breathProgress: State<Float> = if (recording && !cancelling && !reduceMotion) {
        rememberInfiniteTransition(label = "liuliVoiceBreath").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(androidx.compose.animation.core.tween(VOICE_BREATH_CYCLE_MS, easing = LinearEasing)),
            label = "liuliVoiceBreathProgress",
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    val haloColor = colors.accent.primary
    Box(
        modifier = modifier
            // 触达框 48 不占版：布局脚印 44 与输入胶囊同高（复核 R1 🔴-2）；手势 owner 仍挂在 48dp 框上。
            .liuliFootprint(LiuliChatGeometry.inputPieceSize)
            // 审计 Y3① 照抄：纯手势 Box 补按钮语义（merge 后拿内部「按住说话」cd + Button 角色）。
            .semantics(mergeDescendants = true) { role = Role.Button }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (blockedNow) {
                        onBlockedNow()
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    if (!perm) {
                        reqPerm()
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    down.consume()
                    // 50ms 防抖：在此期间松手 = 快速点放，不录。
                    val releasedEarly = withTimeoutOrNull(VOICE_DEBOUNCE_MS) { waitForUpOrCancellation() }
                    if (releasedEarly != null) return@awaitEachGesture
                    start()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        val draggedUp = (down.position.y - change.position.y).toDp().value.coerceAtLeast(0f)
                        drag(draggedUp)
                        change.consume()
                    }
                    finish()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(LiuliChatGeometry.inputPieceSize)
                .graphicsLayer {
                    val s = pressScale + VOICE_BREATH_AMPLITUDE * sin(breathProgress.value * 2f * PI.toFloat())
                    scaleX = s
                    scaleY = s
                }
                // 录音中外扩 8dp 钴蓝光晕（图纸 §4.5 锁 accent.primary@0.18）。
                .drawBehind {
                    if (recording) {
                        drawCircle(
                            color = haloColor,
                            radius = size.minDimension / 2f + HaloExpand.toPx(),
                            alpha = HALO_ALPHA,
                        )
                    }
                }
                // 圆钮甲（用户 09-06）：按钮档玻璃 + 钴蓝图标，与顶栏圆钮同族。
                .liuliGlass(CircleShape, dark = dark, style = LiuliGlassStyle.Button),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = stringResource(R.string.voice_message_hold_to_record),
                tint = AppTheme.colors.accent.text,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// 以下四个落值与暖陶 `VoiceInputComposer` 的同名 private 常量**逐值相同**（那边不可见，此处重打一遍；
// 改任一侧须两侧同步——手势节奏是 REDLINES §7 的承重件）。
internal const val VOICE_DEBOUNCE_MS = 50L
private const val VOICE_PRESS_SCALE = 1.1f
private const val VOICE_BREATH_AMPLITUDE = 0.03f
private const val VOICE_BREATH_CYCLE_MS = 1200

/** 录音光晕（图纸 §4.5 锁：外扩 8dp · alpha 0.18）。 */
private val HaloExpand = 8.dp
private const val HALO_ALPHA = 0.18f
