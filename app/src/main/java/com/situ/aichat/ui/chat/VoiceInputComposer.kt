package com.situ.aichat.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.sin

/** 录音键长按防抖（毫秒，1:1 iOS scheduleRecordingStart 的 50ms：滤掉无意识误触）。 */
private const val VOICE_DEBOUNCE_MS = 50L

/** C6 录音按压：钮体放大目标（输入排契约 §2 P3·动效分解表）。 */
private const val VOICE_PRESS_SCALE = 1.1f

/** C6 录音中呼吸脉冲振幅（1.1 基础上 ±0.03）。 */
private const val VOICE_BREATH_AMPLITUDE = 0.03f

/** C6 呼吸周期（毫秒·≈1.2s 循环·效果轴）。 */
private const val VOICE_BREATH_CYCLE_MS = 1200

/**
 * Fable-5 主行动钮（契约 §3.3·D4）：44dp 圆·陶土玫 135° 渐变与用户气泡完全同源同向（「发送钮=我即将
 * 说出口的话」）+ 深墨图标（WCAG 决议）；48dp 命中框。发送/停止/草稿发送共用此件（语音键见
 * [VoiceRecordButton]——手势独立但视觉同族）。
 */
@Composable
internal fun ChatPrimaryActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = colors.text.onAccent)
        }
    }
}

/** 麦克风权限状态 + 申请回调（13.4b 语音消息录音入口；与语音通话共用 RECORD_AUDIO）。 */
class MicPermissionState(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberMicPermissionState(): MicPermissionState {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        if (!result) {
            Toast.makeText(context, R.string.voice_message_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }
    return MicPermissionState(granted = granted, request = { launcher.launch(Manifest.permission.RECORD_AUDIO) })
}

/**
 * 输入栏右侧「按住录音」语音键（输入框为空时显示，1:1 iOS 空文字→波形键）。手势：按下→50ms 防抖（快速点放不录）→
 * 仍按住则开始录音→跟手上滑（位移以 dp 回传，越 80dp 由 VM 置取消态）→松手收尾。无权限时按下先申请、本次不录。
 *
 * [blocked]=true（引用一期 E：托盘里挂着引用卡时）按下即拦、本次不录，只回 [onBlocked] 让调用方弹提示。
 * **拦截排在权限分支之前**——不为一个注定被拒的操作去要麦克风权限。
 */
@Composable
fun VoiceRecordButton(
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    /** 带引用时不许录音（引用一期 E·图纸 §4.3）；true → 按下即拦，本次手势直接作废。 */
    blocked: Boolean,
    /** 被拦那一刻的回调（调用方据此亮「引用时只能发文字」提示条）。 */
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
    // pointerInput(Unit) 的手势块只建一次（手势 owner 跨态不卸载铁律）→ 这两个也必须走 rememberUpdatedState，
    // 否则「有没有引用」会被冻在首帧（Compose 捕获过期·见 PITFALLS §1d）。
    val blockedNow by rememberUpdatedState(blocked)
    val onBlockedNow by rememberUpdatedState(onBlocked)
    val start by rememberUpdatedState(onStartRecording)
    val drag by rememberUpdatedState(onDrag)
    val finish by rememberUpdatedState(onFinish)
    val colors = AppTheme.colors
    // C6 按压反馈（契约 §2 P3·纯 graphicsLayer 视觉层·pointerInput 手势零碰）：录音开始（50ms 防抖过后
    // VM 置 recording 同刻）钮体 1→1.1 gentle 空间轴弹簧；录音中 ±0.03 呼吸（≈1.2s 正弦循环·效果轴——
    // sin 起点 0 无跳变）；越取消阈呼吸暂停定格 1.1（让位既有整行变红反馈）；松手 gentle 回弹 1.0。
    // reduceMotion 恒 1.0 静态。scale 在 graphicsLayer 块内读状态=逐帧只重绘零重组。
    val pressScale by animateFloatAsState(
        targetValue = if (recording && !reduceMotion) VOICE_PRESS_SCALE else 1f,
        animationSpec = if (reduceMotion) snap() else AppMotion.gentleSpring(),
        label = "voicePressScale",
    )
    val breathProgress: State<Float> = if (recording && !cancelling && !reduceMotion) {
        rememberInfiniteTransition(label = "voiceBreath").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(VOICE_BREATH_CYCLE_MS, easing = LinearEasing)),
            label = "voiceBreathProgress",
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    // Fable-5：视觉与发送钮同族（44dp 陶土玫渐变圆·深墨麦克风）；手势 owner 在外层 48dp 命中框，逻辑零碰。
    Box(
        modifier = modifier
            .size(48.dp)
            // 审计 Y3①：纯手势 Box 补按钮语义（merge 后拿到内部「按住说话」cd + Button 角色），读屏可发现可聚焦。
            .semantics(mergeDescendants = true) { role = Role.Button }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 引用一期 E：带引用时在「意图那一刻」拦下（形状与下面的权限分支同构）。
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
                .size(44.dp)
                .graphicsLayer {
                    val s = pressScale + VOICE_BREATH_AMPLITUDE * sin(breathProgress.value * 2f * PI.toFloat())
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = stringResource(R.string.voice_message_hold_to_record),
                tint = colors.text.onAccent,
            )
        }
    }
}

/**
 * 录音中浮层（替换输入栏中段·C5 输入排契约 §2 P5·R1 单行）：44dp 与输入胶囊同高同圆角——玻璃壳由调用处
 * `MaybeTrayGlass(InputCapsuleCorner)` 提供零动。布局 `[Mic 20dp][电平条][状态文案]…[上滑提示][时长 tnum 右缘]`；
 * 状态文案吃 weight 窄屏省略号不溢出；取消态=整行染 status.error 深档、状态文案换「松手取消」、右提示隐去
 * （色/文案资源与旧两行版同源，只重排容器）。
 */
@Composable
fun VoiceRecordingOverlay(level: Float, durationMs: Long, cancelling: Boolean) {
    val colors = AppTheme.colors
    // 取消态=status.error 功能深档（非裸红）；常态=陶土玫（波形装饰用主强调·文字用功能深档保 4.5:1）。
    val barColor = if (cancelling) colors.status.onError else colors.accent.primary
    val textAccent = if (cancelling) colors.status.onError else colors.accent.text
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Mic, contentDescription = null, tint = textAccent, modifier = Modifier.size(20.dp))
        VoiceLevelBars(level = level, color = barColor, modifier = Modifier.width(40.dp).height(20.dp))
        Text(
            stringResource(if (cancelling) R.string.voice_recording_cancel_hint else R.string.voice_message_recording),
            style = AppTypography.secondary,
            color = textAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!cancelling) {
            Text(
                stringResource(R.string.voice_recording_hint),
                style = AppTypography.caption,
                color = colors.text.secondary,
                maxLines = 1,
            )
        }
        Text(
            formatVoiceDuration(durationMs),
            style = AppTypography.amount,
            color = colors.text.primary,
        )
    }
}

/**
 * 录好待发草稿条（替换输入栏，1:1 iOS voiceComposerBar 草稿态）：取消 + ▶试听 + 波形 + 标题/副标题 + 时长 + 发送。
 * P1-41/42 副标题三态：识别中转圈 / 失败粒度文案（EMPTY/TIMEOUT 附「重新识别」内联钮；UNAVAILABLE 引擎粘滞失败
 * 重试必败，仅提示改用文字）/ 就绪「点按试听」。
 */
@Composable
fun VoiceDraftBar(
    draft: VoiceDraftState,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onRetryTranscription: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel), tint = colors.text.secondary)
        }
        IconButton(onClick = onPlay) {
            Icon(
                if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) stringResource(R.string.a11y_stop_playback) else stringResource(R.string.a11y_voice_play), // Y5②
                tint = colors.accent.text,
            )
        }
        VoiceLevelBars(level = 0.6f, color = colors.accent.primary, modifier = Modifier.width(44.dp).height(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.voice_draft_title),
                style = AppTypography.label,
                color = colors.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (draft.isTranscriptPending) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppLoadingRing(size = AppLoadingRingSize.Small)
                    Text(
                        stringResource(R.string.voice_draft_processing),
                        style = AppTypography.secondary,
                        color = colors.text.secondary,
                    )
                }
            } else {
                val failure = draft.transcriptFailure
                if (failure != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(
                                when (failure) {
                                    VoiceTranscriptFailure.UNAVAILABLE -> R.string.voice_draft_failed_unavailable
                                    VoiceTranscriptFailure.EMPTY -> R.string.voice_draft_failed_empty
                                    VoiceTranscriptFailure.TIMEOUT -> R.string.voice_draft_failed_timeout
                                },
                            ),
                            style = AppTypography.secondary,
                            color = colors.status.onError,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (failure != VoiceTranscriptFailure.UNAVAILABLE) {
                            Text(
                                stringResource(R.string.voice_draft_retry),
                                style = AppTypography.secondary,
                                color = colors.accent.text,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize() // Y3③：识别失败唯一恢复路径·触达≥48dp（同行 48dp IconButton 已定行高，不撑高）
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button) { onRetryTranscription() }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.voice_draft_ready),
                        style = AppTypography.secondary,
                        color = colors.text.secondary,
                    )
                }
            }
        }
        Text(
            formatVoiceDuration((draft.durationSec * 1000).toLong()),
            style = AppTypography.amount,
            color = colors.text.primary,
        )
        ChatPrimaryActionButton(
            icon = Icons.AutoMirrored.Filled.Send,
            contentDescription = stringResource(R.string.a11y_send_voice_message),
            onClick = onSend,
        )
    }
}

/** 电平/波形条：barCount 根竖条，高度由 [level] (0..1) 与逐条放大系数（1:1 iOS RecordingPowerBars 的 0.55+i*0.14）决定。 */
@Composable
private fun VoiceLevelBars(level: Float, color: Color, modifier: Modifier, barCount: Int = 5) {
    val clamped = level.coerceIn(0f, 1f)
    Canvas(modifier = modifier) {
        if (barCount <= 0) return@Canvas
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val minH = 4.dp.toPx().coerceAtMost(size.height)
        for (i in 0 until barCount) {
            val multiplier = 0.55f + i * 0.14f
            val h = (minH + clamped * (size.height - minH) * multiplier).coerceIn(minH, size.height)
            val x = i * (barWidth + gap)
            val top = (size.height - h) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x, top),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** 时长格式化 M:SS（1:1 iOS formatDuration）。 */
private fun formatVoiceDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
