package com.situ.aichat.ui.diary

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import com.situ.aichat.ui.designsystem.AppActionIcons
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.chat.rememberMicPermissionState
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.designsystem.AppTheme
import kotlinx.coroutines.withTimeoutOrNull

/** 录音键长按防抖（毫秒·仿 [com.situ.aichat.ui.chat.VoiceRecordButton] 的 50ms：滤掉无意识误触·快点放不录）。 */
private const val VOICE_DEBOUNCE_MS = 50L

/**
 * J6「说一段」麦克风钮（图纸 §4-J6）：40dp 视觉图标 + `minimumInteractiveComponentSize`（触达 48·a11y 红线）。
 * **按住手势**照 [com.situ.aichat.ui.chat.VoiceRecordButton] 仿写（不改它）：按下→50ms 防抖→仍按住则 [onStart]→
 * 跟手上滑（位移 dp 上报 [onDrag]）→松手 [onFinish]；无权限则按下先申请、本次不录（[rememberMicPermissionState] 直接消费）。
 * 录音反馈由屏根 [com.situ.aichat.ui.chat.VoiceRecordingOverlay] 承载·本钮不叠视觉。
 */
@Composable
internal fun DiaryMicButton(onStart: () -> Unit, onDrag: (Float) -> Unit, onFinish: () -> Unit) {
    val colors = AppTheme.colors
    val mic = rememberMicPermissionState()
    val perm by rememberUpdatedState(mic.granted)
    val reqPerm by rememberUpdatedState(mic.request)
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val finish by rememberUpdatedState(onFinish)
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(CircleShape)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!perm) {
                        reqPerm()
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    down.consume()
                    // 50ms 防抖：其间松手 = 快点放·不录。
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
        Icon(
            AppActionIcons.Mic,
            contentDescription = stringResource(R.string.voice_message_hold_to_record),
            tint = colors.accent.text,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * J6 转写中提示（图纸 §4-J6）：纸面下方一行楷体小字「正在落笔…」+ 复用 AiStartPill 的 breathing
 * （tween 900ms Reverse·`reduceMotion` 静态）。
 */
@Composable
internal fun DiaryTranscribingPill(reduceMotion: Boolean) {
    val colors = AppTheme.colors
    val breath = rememberInfiniteTransition(label = "diaryTranscribeBreath")
    val pulse by breath.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = AppMotion.EaseInOut), RepeatMode.Reverse),
        label = "diaryTranscribeAlpha",
    )
    val alpha = if (reduceMotion) 1f else pulse
    Text(
        stringResource(R.string.diary_voice_transcribing),
        style = AppTheme.typography.kaiQuote,
        color = colors.text.secondary,
        modifier = Modifier.alpha(alpha),
    )
}
