package com.situ.aichat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.offline.OfflineTheater
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Voice-message bubble: a play/pause button, an animated waveform, and a duration readout, plus any
 * stickers in the message and a tap-to-expand transcript (1:1 iOS `VoiceMessageBubble`, Material 3
 * native rather than pixel-for-pixel). Stateless — playback state + the toggle are hoisted to
 * [ChatViewModel]/[TtsAudioPlayer]; tapping replays the stored audio (never re-synthesizes).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceMessageBubble(
    message: MessageEntity,
    isUser: Boolean,
    isPlaying: Boolean,
    /** 审计 P3：进度改 lambda——非播放行喂零常量（无快照依赖），只有播放行随 80ms tick 局部重组。 */
    progress: () -> Float,
    customStickers: List<CustomStickerEntity>,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    a11yDescription: String? = null,
    /** Fable-5：形状由调用方传入（默认=统一 16dp 消息气泡）。 */
    shape: Shape = AppShapes.bubble,
    /** Chunk 3：true=该语音消息「新到达」一刻，波形从左到右依次长出（只播一次·门控在调用点 ChatScreen）。 */
    cascadePlay: Boolean = false,
    onCascadePlayed: () -> Unit = {},
    /** 卷三 §4.2：true=在见面剧场里渲染（舞台深玻璃药丸皮肤·播放机制零改）；false=聊天列表现状，逐像素不动。 */
    onStage: Boolean = false,
    /** [onStage]=true 时调用方必传的舞台调和色（已播波形 + 播放键）。 */
    stageAccent: Color = Color.Unspecified,
) {
    val progressValue = progress() // P3：组合期单点读——ZeroProgress 无状态读、播放行才随 tick 失效
    val durationSec = max(1.0, message.audioDuration ?: 0.0)
    val transcript = remember(message.content) { StickerTagParser.stripStickerTags(message.content).trim() }
    val stickerIds = remember(message.content) { StickerTagParser.extractStickerIds(message.content) }
    var showTranscript by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()

    // Fable-5（契约 §3.2）：与文本气泡同族——用户=陶土玫渐变+深墨内容，AI=raised 暖白纸；
    // 波形已播=主强调（AI）/深墨（用户），未播=同色低透明（装饰·不承载语义）。
    val colors = AppTheme.colors
    // onStage（剧场内）：时长/图标基色=舞台次亮字，已播波形=舞台调和色，未播=暖白 28%（§4.2）；否则聊天列表现状。
    val contentColor = when {
        onStage -> OfflineTheater.textDim
        isUser -> colors.bubble.onUser
        else -> colors.text.primary
    }
    val playedColor = when {
        onStage -> stageAccent
        isUser -> colors.bubble.onUser
        else -> colors.accent.primary
    }
    val unplayedColor = if (onStage) OfflineTheater.waveIdle else contentColor.copy(alpha = 0.30f)

    // 语音条宽度随时长（140–260dp，对齐 iOS bubbleWidth）；波形条数 8/12/16。
    val bubbleWidth = (140.0 + (durationSec - 1) * 20).coerceIn(140.0, 260.0).dp
    val barCount = when {
        durationSec <= 2 -> 8
        durationSec <= 5 -> 12
        else -> 16
    }

    Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Row(
            modifier = Modifier
                .width(bubbleWidth)
                .then(if (!onStage && !isUser && !colors.isDark) Modifier.shadow(1.dp, shape, clip = false) else Modifier)
                .clip(shape)
                .then(
                    if (onStage) {
                        // 舞台深玻璃药丸（§4.2）：scrimPill 底 + pillStroke 发丝边，替掉渐变/raised/影/深色描边四件。
                        Modifier.background(OfflineTheater.scrimPill)
                    } else if (isUser) {
                        // 审计 P5：渐变按主题色 remember（同 ChatBubbles）。
                        Modifier.background(remember(colors) { Brush.linearGradient(listOf(colors.bubble.userStart, colors.bubble.userEnd)) })
                    } else {
                        Modifier.background(colors.bubble.ai)
                    },
                )
                .then(
                    if (onStage) {
                        Modifier.border(1.dp, OfflineTheater.pillStroke, shape)
                    } else if (!isUser && colors.isDark) {
                        Modifier.border(1.dp, colors.bubble.aiStroke, shape)
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(onClick = onToggle, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.a11y_message_menu)) // Y2
                // P1-1：合并朗读句（「…发送了语音消息：{转写}」=iOS）覆盖逐子件朗读；点按播放/长按菜单保留。
                .then(a11yDescription?.let { Modifier.semantics { contentDescription = it } } ?: Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onStage) {
                // 剧场：22dp 图标外包 26dp 强调色圆底（§4.2）；聊天列表无圆底=现状。
                Box(
                    Modifier.size(26.dp).background(stageAccent.copy(alpha = OfflineTheater.voicePlayCircleAlpha), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { VoicePlayIcon(isPlaying, stageAccent) }
            } else {
                VoicePlayIcon(isPlaying, contentColor)
            }
            Spacer(Modifier.width(8.dp))
            VoiceWaveform(
                isPlaying = isPlaying,
                progress = progressValue,
                playedColor = playedColor,
                unplayedColor = unplayedColor,
                barCount = barCount,
                appearPlay = cascadePlay && !reduceMotion,
                onAppearPlayed = onCascadePlayed,
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = durationLabel(durationSec, isPlaying, progressValue),
                color = contentColor.copy(alpha = 0.8f),
                style = AppTypography.captionNumeric.copy(fontSize = 12.sp),
            )
        }

        // 语音消息里的表情包独立显示（对齐 iOS：转文字只剥 sticker 标签，表情包另渲染）。剧场内不渲染芯片（§4.2·J6）。
        if (!onStage) stickerIds.forEach { id ->
            Spacer(Modifier.size(6.dp))
            StickerImage(stickerId = id, customStickers = customStickers, size = 120.dp)
        }

        // AI 语音才显示「转文字」（剥 sticker 后还有纯文字时）。剧场内转写外置常显，内部展开不渲染（§4.2·J5·防双转写）。
        if (!onStage && !isUser && transcript.isNotEmpty()) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = if (showTranscript) "收起" else "转文字",
                color = colors.text.secondary,
                style = AppTypography.secondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .combinedClickable(onClick = { showTranscript = !showTranscript }, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.a11y_message_menu)) // Y2/Y3：标签+可发现（触达扩围会改气泡高度→并 B3 审）
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            // P4：转写区顺滑展开/收起（gentle 弹簧·1:1 周围动效调性）；开「减弱动画」→ 瞬时直出（守无障碍）。
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
                    Spacer(Modifier.size(4.dp))
                    Surface(
                        color = colors.surface.sunken,
                        shape = AppShapes.small,
                        modifier = Modifier.width(260.dp),
                    ) {
                        Text(
                            text = transcript,
                            color = colors.text.primary,
                            style = AppTypography.body,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 播放/暂停键图标（22dp·两处调用同一份：剧场包圆底、聊天列表裸放）。 */
@Composable
private fun VoicePlayIcon(isPlaying: Boolean, tint: Color) {
    Icon(
        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
        contentDescription = if (isPlaying) stringResource(R.string.a11y_voice_pause) else stringResource(R.string.a11y_voice_play), // Y5②
        tint = tint,
        modifier = Modifier.size(22.dp),
    )
}

/** 时长显示：静止 `5"`，播放 `0:03 / 0:05`（对齐 iOS durationText）。 */
private fun durationLabel(durationSec: Double, isPlaying: Boolean, progress: Float): String {
    val total = max(1, durationSec.roundToInt())
    if (isPlaying) {
        val elapsed = (progress * durationSec).toInt().coerceIn(0, total)
        return "${elapsed / 60}:${(elapsed % 60).pad2()} / ${total / 60}:${(total % 60).pad2()}"
    }
    return "$total\""
}

private fun Int.pad2(): String = toString().padStart(2, '0')

/**
 * Deterministic waveform: a sin-driven envelope (taller in the middle, 1:1 iOS), animated while
 * playing, with bars up to [progress] tinted [playedColor]. One Canvas draw, no per-bar recomposition.
 */
@Composable
private fun VoiceWaveform(
    isPlaying: Boolean,
    progress: Float,
    playedColor: Color,
    unplayedColor: Color,
    barCount: Int,
    appearPlay: Boolean,
    onAppearPlayed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // P1-23：RM 播放中条高走 STATIC_PATTERN 静态形（=气泡固有静态外观，绝非 sin(phase0) 怪异帧），
    // played 着色仍随 progress 推进=播放进度信息保留；iOS VoiceMessageBubble TimelineView 不读 RM=加项。
    val animating = isPlaying && !rememberReduceMotion()
    // 审计 P5：phase/appear 保持 State、在 Canvas 绘制 lambda 里读——播放/入场期间动画帧只重绘，不再逐帧重组。
    val phaseState: State<Float> = if (animating) {
        val transition = rememberInfiniteTransition(label = "wave")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    // Chunk 3（参照 DrKLO/Telegram SeekBarWaveform.java:62/376 appearFloat + 每条 clamp）：到达时波形从左到右
    // 依次长出。appearPlay=false（历史/已播/reduceMotion）→ appear=1 满高、无动画、不重组（沿用上方 phase 的条件 remember 惯例）。
    val appearState: State<Float> = if (appearPlay) {
        val p = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            onAppearPlayed()
            p.animateTo(1f, tween(VoiceCascadeMs, delayMillis = 90, easing = AppMotion.EaseOutQuint))
        }
        p.asState()
    } else {
        remember { mutableStateOf(1f) }
    }

    Canvas(modifier) {
        val phase = phaseState.value // 绘制期读取（审计 P5）
        val appear = appearState.value
        val barWidth = 3.dp.toPx()
        val gap = 3.dp.toPx()
        val slot = barWidth + gap
        val maxHeight = size.height
        val minHeight = 3.dp.toPx()
        var i = 0
        while (i < barCount) {
            val x = i * slot
            if (x + barWidth > size.width) break
            val norm = if (barCount <= 1) 0f else i.toFloat() / (barCount - 1)
            val envelope = 0.78f + sin(norm * Math.PI.toFloat()) * 0.42f
            val fraction = if (animating) {
                (0.55f + sin(phase + i * 0.9f) * 0.33f * envelope)
            } else {
                STATIC_PATTERN[i % STATIC_PATTERN.size]
            }
            val h = (fraction * maxHeight).coerceIn(minHeight, maxHeight)
            // Chunk 3 级联：每条生长比例 clamp(appear×根数 − 序号)，左→右从 minHeight 依次长到 h。
            val bart = (appear * barCount - i).coerceIn(0f, 1f)
            val grown = minHeight + (h - minHeight) * bart
            val played = (i + 1).toFloat() / barCount <= progress
            drawRoundRect(
                color = if (played) playedColor else unplayedColor,
                topLeft = Offset(x, (maxHeight - grown) / 2f),
                size = Size(barWidth, grown),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
            i++
        }
    }
}

// 静止态条高比例（对齐 iOS heightForStatic 的相对节奏）。
private val STATIC_PATTERN = floatArrayOf(0.30f, 0.45f, 0.60f, 0.85f, 0.70f, 1.0f, 0.85f, 0.55f, 0.70f, 0.45f, 0.60f, 0.30f)

/** Chunk 3 波形级联时长（ms·参照 Telegram SeekBarWaveform appearFloat 600ms）。 */
private const val VoiceCascadeMs = 600
