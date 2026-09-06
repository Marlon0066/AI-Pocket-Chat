package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.chat.QuoteTextOnlyHintState
import com.situ.aichat.ui.chat.VoiceDraftState
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.liuli.page.liuliFootprint
import com.situ.aichat.ui.offline.OfflineImmersiveInputView
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃输入区（图纸 2026-09-05 卷二A §4.5 · 契约 §5.2）：**三片分体**——「+」圆钮 44 / 输入胶囊 44 /
 * 右圆钮 44，片间 6dp、离屏左右 10dp、底 12dp（+ 导航栏 inset 由调用方给）。整块住在 `BackdropHost.overlay`
 * 里、按面板区高度向上偏移，故玻璃能模糊身后的气泡，托盘又永远贴着键盘 / 面板顶（PLUS_PANEL 硬指标）。
 *
 * **不持 `ChatViewModel`**：所有动作经回调进来（[onSend] 返回「发送是否被受理」）——既是分层纪律，
 * 也让 T2-4 能直接驱动它。
 *
 * 卷二B（图纸 2026-09-05 §2.2）：声波条 / 引用条 / 提示条 / 日历卡四件换成琉璃自己的脸
 * （[LiuliRecordingBar] / [LiuliReplyBar] / [LiuliQuoteHint] / [LiuliCalendarCard]）；卷二C C6c
 * 补上第五件 [LiuliDraftBar]（录好待发·56 高玻璃胶囊·发送球留在胶囊外）。
 *
 * **[onSend] 的语义（A-7）**：返回 true = 发送被受理；**清空输入框由调用方在飞入握手时完成**
 * （`liuliSendHandler` 的 `commit`），本件受理后只发触觉。闸关时 `tryBegin` 立即 commit = 与旧写法同帧。
 */
@Composable
internal fun LiuliInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Boolean,
    panelOpen: Boolean,
    onTogglePanel: () -> Unit,
    inputFieldModifier: Modifier,
    characterName: String,
    replyTarget: MessageEntity?,
    onClearReply: () -> Unit,
    quoteHint: QuoteTextOnlyHintState,
    pendingCalendarAction: CalendarAction?,
    onConfirmCalendar: () -> Unit,
    onCancelCalendar: () -> Unit,
    voiceDraft: VoiceDraftState?,
    draftPlaying: Boolean,
    onPlayDraft: () -> Unit,
    onCancelDraft: () -> Unit,
    onSendDraft: () -> Unit,
    onRetryTranscription: () -> Unit,
    micPermissionGranted: Boolean,
    onRequestMicPermission: () -> Unit,
    onStartRecording: () -> Unit,
    onRecordingDrag: (Float) -> Unit,
    onFinishRecording: () -> Unit,
    voiceRecording: Boolean,
    voiceRecordingLevel: Float,
    voiceRecordingDurationMs: Long,
    voiceRecordingCancelling: Boolean,
    offlineImmersiveInput: Boolean,
    offlineThemeColor: Color,
    reduceMotion: Boolean,
    /** M3b ④：握手 / 飞行期抑制占位符视觉，免得输入框在飞行泡还没落地时就闪回「说点什么…」。 */
    hidePlaceholder: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = LiuliChatGeometry.inputSide, end = LiuliChatGeometry.inputSide, bottom = LiuliChatGeometry.inputBottom),
    ) {
        // 自下而上：三片行 → 引用提示 → 引用预览 → 日历卡（Column 自上而下即倒序·图纸 §4.5）。
        // 间距逐项自带（不用 spacedBy）：隐着的引用提示是一个 0 高的 AnimatedVisibility 节点，spacedBy 会给它
        // 前后各留 6dp = 幽灵缝（复核 R1 🟡-3：默认态 overlay 高被撑到 62、引用条与三片行之间 12 而非 6）。
        pendingCalendarAction?.let { action ->
            LiuliCalendarCard(
                characterName = characterName,
                action = action,
                onConfirm = onConfirmCalendar,
                onCancel = onCancelCalendar,
                modifier = Modifier.padding(bottom = LiuliChatGeometry.stackGap),
            )
        }
        replyTarget?.let { target ->
            LiuliReplyBar(
                senderLabel = if (target.roleRaw == "user") "你" else characterName,
                content = target.content,
                onClear = onClearReply,
                modifier = Modifier.padding(bottom = LiuliChatGeometry.stackGap),
            )
        }
        Box(Modifier.padding(bottom = if (quoteHint.visible) LiuliChatGeometry.stackGap else 0.dp)) {
            LiuliQuoteHint(visible = quoteHint.visible, reduceMotion = reduceMotion)
        }
        when {
            offlineImmersiveInput -> Box(Modifier.liuliGlass(LiuliShapes.medium, dark = dark)) {
                OfflineImmersiveInputView(onSend = { onSend(it) }, themeColor = offlineThemeColor)
            }
            // 录好待发 → 草稿条顶替输入胶囊（录制已结束、无活动手势，可整条替换·照抄暖陶互斥）；
            // 卷二C C6c：换琉璃版 56 高玻璃胶囊，**发送球留在胶囊外**、位置与三片行同（§4.12）。
            voiceDraft != null -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(LiuliChatGeometry.inputPieceGap),
            ) {
                LiuliDraftBar(
                    draft = voiceDraft,
                    isPlaying = draftPlaying,
                    onPlay = onPlayDraft,
                    onCancel = onCancelDraft,
                    onRetryTranscription = onRetryTranscription,
                    modifier = Modifier.weight(1f),
                )
                LiuliSendButton(
                    onClick = onSendDraft,
                    contentDescription = stringResource(R.string.a11y_send_voice_message),
                )
            }
            else -> LiuliInputRow(
                input = input,
                onInputChange = onInputChange,
                onSend = onSend,
                panelOpen = panelOpen,
                onTogglePanel = onTogglePanel,
                inputFieldModifier = inputFieldModifier,
                replyTarget = replyTarget,
                quoteHint = quoteHint,
                micPermissionGranted = micPermissionGranted,
                onRequestMicPermission = onRequestMicPermission,
                onStartRecording = onStartRecording,
                onRecordingDrag = onRecordingDrag,
                onFinishRecording = onFinishRecording,
                voiceRecording = voiceRecording,
                voiceRecordingLevel = voiceRecordingLevel,
                voiceRecordingDurationMs = voiceRecordingDurationMs,
                voiceRecordingCancelling = voiceRecordingCancelling,
                hidePlaceholder = hidePlaceholder,
                reduceMotion = reduceMotion,
            )
        }
    }
}

/** 三片行本体（录音中：中段 alpha 0 隐身**不卸载**，右键 owner 跨态不换装——REDLINES §7）。 */
@Composable
private fun LiuliInputRow(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Boolean,
    panelOpen: Boolean,
    onTogglePanel: () -> Unit,
    inputFieldModifier: Modifier,
    replyTarget: MessageEntity?,
    quoteHint: QuoteTextOnlyHintState,
    micPermissionGranted: Boolean,
    onRequestMicPermission: () -> Unit,
    onStartRecording: () -> Unit,
    onRecordingDrag: (Float) -> Unit,
    onFinishRecording: () -> Unit,
    voiceRecording: Boolean,
    voiceRecordingLevel: Float,
    voiceRecordingDurationMs: Long,
    voiceRecordingCancelling: Boolean,
    hidePlaceholder: Boolean,
    reduceMotion: Boolean,
) {
    val haptics = LocalAppHaptics.current
    val plusRotation by animateFloatAsState(
        targetValue = if (panelOpen) PLUS_OPEN_DEGREES else 0f,
        animationSpec = if (reduceMotion) snap() else tween(PLUS_ROTATE_MS),
        label = "liuliPlusRotation",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(LiuliChatGeometry.inputPieceGap),
    ) {
        Box(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(LiuliChatGeometry.inputPieceGap),
                modifier = Modifier.alpha(if (voiceRecording) 0f else 1f),
            ) {
                LiuliCircleButton(
                    onClick = onTogglePanel,
                    contentDescription = stringResource(
                        if (panelOpen) R.string.a11y_close_panel else R.string.a11y_open_panel,
                    ),
                    // 触达框不占版：三片 Bottom 对齐时圆钮与输入胶囊底缘齐平（复核 R1 🔴-2）。
                    modifier = Modifier.liuliFootprint(LiuliChatGeometry.inputPieceSize),
                    size = LiuliChatGeometry.inputPieceSize,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        // 圆钮甲（用户 09-06）：图标色跟圆钮走（`LiuliCircleButton` 提供的 accent.text 钴蓝）。
                        tint = LocalContentColor.current,
                        modifier = Modifier.size(22.dp).rotate(plusRotation),
                    )
                }
                LiuliInputField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = inputFieldModifier.weight(1f),
                    hidePlaceholder = hidePlaceholder,
                )
            }
            if (voiceRecording) {
                // 浮层叠画在隐身的中段之上；空点击拦截防误触底下的「+」/ 输入框（照抄暖陶）。
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    LiuliRecordingBar(
                        level = voiceRecordingLevel,
                        durationMs = voiceRecordingDurationMs,
                        cancelling = voiceRecordingCancelling,
                        reduceMotion = reduceMotion,
                    )
                }
            }
        }
        // 右键两态（照抄暖陶 C3）：有字 = 发送 / 空 = 麦克风；140ms 交叉缩放 0.7。
        // 手势 owner 铁律：录音只从空输入的麦克风态开始、录音期 input 不变 → targetState 恒 false，绝不换装。
        AnimatedContent(
            targetState = input.isNotBlank(),
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (fadeIn(tween(PRIMARY_MORPH_MS)) + scaleIn(tween(PRIMARY_MORPH_MS), initialScale = MORPH_SCALE)) togetherWith
                        (fadeOut(tween(PRIMARY_MORPH_MS)) + scaleOut(tween(PRIMARY_MORPH_MS), targetScale = MORPH_SCALE))
                }
            },
            label = "liuliPrimaryActionMorph",
        ) { showSend ->
            if (showSend) {
                LiuliSendButton(
                    onClick = {
                        // A-7：受理只发触觉——清空由调用方（liuliSendHandler）在飞入握手时 commit，
                        // 否则输入框先空、飞行泡的「源文字」层就没得抄了。闸关时 commit 同帧发生。
                        if (onSend(input)) haptics.light()
                    },
                )
            } else {
                LiuliMicButton(
                    hasMicPermission = micPermissionGranted,
                    onRequestPermission = onRequestMicPermission,
                    blocked = replyTarget != null,
                    onBlocked = { quoteHint.trigger() },
                    onStartRecording = {
                        haptics.medium()
                        onStartRecording()
                    },
                    onDrag = onRecordingDrag,
                    onFinish = onFinishRecording,
                    recording = voiceRecording,
                    cancelling = voiceRecordingCancelling,
                    reduceMotion = reduceMotion,
                )
            }
        }
    }
}

/** 右键变身（照抄暖陶 140ms / 0.7）与「+」旋转（图纸 §4.5 锁 45° / 220ms）。 */
private const val PRIMARY_MORPH_MS = 140
private const val MORPH_SCALE = 0.7f
private const val PLUS_OPEN_DEGREES = 45f
private const val PLUS_ROTATE_MS = 220
