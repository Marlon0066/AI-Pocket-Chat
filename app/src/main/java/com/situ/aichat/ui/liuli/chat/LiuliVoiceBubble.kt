package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.ui.chat.StickerImage
import com.situ.aichat.ui.chat.rememberBubbleMaxWidth
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.Palette
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 琉璃语音泡（图纸 2026-09-05 卷二C §4.1 · A-5）：**泡形不是卡**——用户 = 渐变窗口泡（尾巴规则同文字泡）、
 * AI = 纸白泡；播放圆 30 + 细波形（3dp 条 / 2dp 间 / 高 22）+ 时长；AI 的转写「转文字 / 收起」留在泡内，
 * 展开后压在**泡内一道发丝线**下（不再另开 sunken 面）；时间戳泡内右下。
 *
 * 机制逐条照抄暖陶 `VoiceMessageBubble`（F6）：泡宽 `140 + (时长−1)×20` 钳 140–260、条数 8 / 12 / 16、
 * 播放进度 lambda（非播放行喂零常量·不触快照）、级联波形只播一次、`reduceMotion` 走静态形。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiuliVoiceBubble(
    message: MessageEntity,
    isUser: Boolean,
    isPlaying: Boolean,
    progress: () -> Float,
    customStickers: List<CustomStickerEntity>,
    tail: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    a11yDescription: String?,
    cascadePlay: Boolean,
    onCascadePlayed: () -> Unit,
    deliveryRead: Boolean?,
) {
    val colors = AppTheme.colors
    val progressValue = progress() // F6：组合期单点读——零常量无快照依赖，只有播放行随 tick 失效。
    val durationSec = max(1.0, message.audioDuration ?: 0.0)
    val transcript = remember(message.content) { StickerTagParser.stripStickerTags(message.content).trim() }
    val stickerIds = remember(message.content) { StickerTagParser.extractStickerIds(message.content) }
    var showTranscript by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()

    val bubbleWidth = liuliVoiceBubbleWidth(durationSec).coerceAtMost(rememberBubbleMaxWidth())
    val barCount = liuliVoiceBarCount(durationSec)
    val shape = liuliBubbleShape(isUser = isUser, tail = tail)
    val anchor = remember { LiuliBubbleAnchor() }

    // A-5 用色（复核 R1 🟡-3 改正）：对版稿的 White@0.8 / accent.text@0.7 是**静止 / 未播**条色——泡一到就是
    // 这个样子（过审的长相）；已播条照暖陶 F6 满色（用户白 / AI `accent.primary`），进度差靠「满色 vs 七八成」读出来。
    val contentColor = if (isUser) Palette.White else colors.text.primary
    val unplayedColor = if (isUser) {
        Palette.White.copy(alpha = USER_WAVE_ALPHA)
    } else {
        colors.accent.text.copy(alpha = AI_WAVE_ALPHA)
    }
    val playedColor = if (isUser) Palette.White else colors.accent.primary

    Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Column(
            modifier = Modifier
                .width(bubbleWidth)
                .then(if (isUser) Modifier.liuliBubbleAnchor(anchor) else Modifier)
                .liuliBubbleTail(isUser = isUser, show = tail) {
                    if (isUser) {
                        LiuliBubbleGradient.colorAt((anchor.yInRoot + size.height) / anchor.rootHeight)
                    } else {
                        colors.bubble.ai
                    }
                }
                .then(
                    if (!isUser && !colors.isDark) Modifier.shadow(BUBBLE_CONTACT_SHADOW, shape, clip = false) else Modifier,
                )
                .clip(shape)
                .then(
                    if (isUser) {
                        Modifier.liuliUserBubbleGradient({ anchor.yInRoot }, { anchor.rootHeight })
                    } else {
                        Modifier.background(colors.bubble.ai).border(BUBBLE_HAIRLINE, colors.bubble.aiStroke, shape)
                    },
                )
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = onLongClick,
                    onLongClickLabel = stringResource(R.string.a11y_message_menu),
                )
                .then(a11yDescription?.let { Modifier.semantics { contentDescription = it } } ?: Modifier)
                .padding(horizontal = BUBBLE_PAD_SIDE, vertical = BUBBLE_PAD_VERTICAL),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
            ) {
                Box(
                    modifier = Modifier
                        .size(LiuliChatGeometry.voicePlay)
                        .clip(CircleShape)
                        .background(if (isUser) Palette.White.copy(alpha = USER_PLAY_ALPHA) else colors.accent.container),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.a11y_voice_pause else R.string.a11y_voice_play,
                        ),
                        tint = if (isUser) Palette.White else colors.accent.onContainer,
                        modifier = Modifier.size(PLAY_ICON),
                    )
                }
                LiuliVoiceWaveform(
                    isPlaying = isPlaying,
                    progress = progressValue,
                    playedColor = playedColor,
                    unplayedColor = unplayedColor,
                    barCount = barCount,
                    appearPlay = cascadePlay && !reduceMotion,
                    onAppearPlayed = onCascadePlayed,
                    modifier = Modifier.weight(1f).height(LiuliChatGeometry.voiceBarHeight),
                )
                Text(
                    text = liuliVoiceDurationLabel(durationSec, isPlaying, progressValue),
                    color = contentColor.copy(alpha = DURATION_ALPHA),
                    style = AppTypography.captionNumeric,
                )
            }
            // AI 语音才给转写切换（剥 sticker 后还有纯文字时）——用户语音无转写（F6 同）。
            if (!isUser && transcript.isNotEmpty()) {
                Text(
                    text = if (showTranscript) "收起" else "转文字",
                    color = colors.text.secondary,
                    style = AppTypography.secondary,
                    modifier = Modifier
                        .padding(top = TRANSCRIPT_TOGGLE_TOP)
                        .clip(RoundedCornerShape(TRANSCRIPT_TOGGLE_CORNER))
                        .combinedClickable(
                            onClick = { showTranscript = !showTranscript },
                            onLongClick = onLongClick,
                            onLongClickLabel = stringResource(R.string.a11y_message_menu),
                        )
                        .padding(horizontal = TRANSCRIPT_TOGGLE_SIDE, vertical = TRANSCRIPT_TOGGLE_VERTICAL),
                )
                AnimatedVisibility(
                    visible = showTranscript,
                    enter = if (reduceMotion) {
                        EnterTransition.None
                    } else {
                        expandVertically(AppMotion.gentleSpring()) + fadeIn(AppMotion.gentleSpring())
                    },
                    exit = if (reduceMotion) {
                        ExitTransition.None
                    } else {
                        shrinkVertically(AppMotion.gentleSpring()) + fadeOut(AppMotion.gentleSpring())
                    },
                ) {
                    Column {
                        Spacer(Modifier.height(TRANSCRIPT_GAP))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(BUBBLE_HAIRLINE)
                                .background(colors.surface.stroke),
                        )
                        Spacer(Modifier.height(TRANSCRIPT_GAP))
                        Text(
                            text = transcript,
                            color = colors.text.secondary,
                            style = AppTypography.snackbarBody,
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LiuliInlineStamp(
                    timestampMs = message.timestamp,
                    isUser = isUser,
                    read = deliveryRead,
                    stampColor = contentColor.copy(alpha = if (isUser) USER_STAMP_ALPHA else 1f),
                )
            }
        }
        // 语音里夹的表情包独立显示（F6 照抄；尺寸走琉璃档 110·A-7）。
        stickerIds.forEach { id ->
            Spacer(Modifier.height(STICKER_GAP))
            StickerImage(stickerId = id, customStickers = customStickers, size = LiuliChatGeometry.stickerSize)
        }
    }
}

/** 泡宽随时长（**重打**暖陶 F6 同式：`140 + (秒−1)×20` 钳 140–260）。纯函数 · T1。 */
internal fun liuliVoiceBubbleWidth(durationSec: Double) =
    (VOICE_WIDTH_MIN + (durationSec - 1) * VOICE_WIDTH_PER_SEC).coerceIn(VOICE_WIDTH_MIN, VOICE_WIDTH_MAX).dp

/** 波形条数（**重打**暖陶 F6 同式：≤2s → 8 / ≤5s → 12 / 其余 16）。纯函数 · T1。 */
internal fun liuliVoiceBarCount(durationSec: Double): Int = when {
    durationSec <= 2 -> 8
    durationSec <= 5 -> 12
    else -> 16
}

/** 时长显示（**重打**暖陶 F6 `durationLabel` 同值）：静止 `5"`，播放 `0:03 / 0:05`。纯函数 · T1。 */
internal fun liuliVoiceDurationLabel(durationSec: Double, isPlaying: Boolean, progress: Float): String {
    val total = max(1, durationSec.roundToInt())
    if (isPlaying) {
        val elapsed = (progress * durationSec).toInt().coerceIn(0, total)
        return "${elapsed / 60}:${(elapsed % 60).pad2()} / ${total / 60}:${(total % 60).pad2()}"
    }
    return "$total\""
}

private fun Int.pad2(): String = toString().padStart(2, '0')

/** 落值（A-5 + F6 照抄值 + 对版稿 `.voice`·孤值即打回）。 */
private const val VOICE_WIDTH_MIN = 140.0
private const val VOICE_WIDTH_MAX = 260.0
private const val VOICE_WIDTH_PER_SEC = 20.0
private const val USER_WAVE_ALPHA = 0.8f
private const val AI_WAVE_ALPHA = 0.7f
private const val USER_PLAY_ALPHA = 0.22f
private const val DURATION_ALPHA = 0.85f
private const val USER_STAMP_ALPHA = 0.72f
private val PLAY_ICON = 12.dp
private val BUBBLE_CONTACT_SHADOW = 1.dp
private val BUBBLE_HAIRLINE = 0.5.dp
private val BUBBLE_PAD_SIDE = 12.dp
private val BUBBLE_PAD_VERTICAL = 8.dp
private val ROW_GAP = 8.dp
private val TRANSCRIPT_TOGGLE_TOP = 4.dp
private val TRANSCRIPT_TOGGLE_CORNER = 6.dp
private val TRANSCRIPT_TOGGLE_SIDE = 6.dp
private val TRANSCRIPT_TOGGLE_VERTICAL = 2.dp
private val TRANSCRIPT_GAP = 4.dp
private val STICKER_GAP = 6.dp
