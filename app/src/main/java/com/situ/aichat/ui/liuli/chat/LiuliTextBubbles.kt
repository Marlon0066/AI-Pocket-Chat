package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette
import kotlin.math.PI
import kotlin.math.sin

/**
 * 琉璃文字气泡族（图纸 2026-09-05 卷二A §4.4 · 契约 §5.3）：用户泡 = 渐变窗口 + 白字；AI 泡 = 纸白 +
 * 0.5dp 发丝 + 浅档 1dp 接触影；两者的时间戳都**进泡**（[LiuliInlineStampLayout] 浮末行右下），
 * 连发段末条带尾巴（[liuliBubbleTail]）。AI 泡的打字态 → 正文是**同 key 原地变身**（机制照抄暖陶 F8，
 * REDLINES §7「打字气泡 = 同 key 原地变身」不动）。
 */

/** 用户泡上的白字档（图纸 §3.2 锁：正文纯白、时间戳 72%、引用竖线 75%、引用正文 92%）。 */
private const val USER_STAMP_ALPHA = 0.72f
private const val USER_QUOTE_BAR_ALPHA = 0.75f
private const val USER_QUOTE_TEXT_ALPHA = 0.92f

/** 气泡内边距（契约 §5.7：上 7 / 下 6 / 左 12 / 右 11·用户泡与 AI 泡同）。 */
private val BubblePadStart = 12.dp
private val BubblePadEnd = 11.dp
private val BubblePadTop = 7.dp
private val BubblePadBottom = 6.dp

/** 泡右内边距的对外只读口：泡内时间戳右缘落在这里，回应徽章伸进泡内不得超过它（零重叠 ⑯·`LiuliReactionBurstTest`）。 */
internal val LiuliBubblePadEnd: Dp get() = BubblePadEnd

/** AI 泡发丝与接触影（契约 §5.3）。 */
private val AiHairline = 0.5.dp
private val AiContactShadow = 1.dp

/** 用户文字气泡（渐变窗口锚定屏幕·尾色取泡底缘的渐变值）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiuliUserBubble(
    text: String,
    quotedContent: String?,
    quotedSender: String?,
    timestampMs: Long,
    deliveryRead: Boolean?,
    tail: Boolean,
    maxWidth: Dp,
    onLongClick: () -> Unit,
    a11yDescription: String?,
) {
    val anchor = remember { LiuliBubbleAnchor() }
    val shape = liuliBubbleShape(isUser = true, tail = tail)
    Column(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .liuliBubbleAnchor(anchor)
            .liuliBubbleTail(isUser = true, show = tail) {
                LiuliBubbleGradient.colorAt((anchor.yInRoot + size.height) / anchor.rootHeight)
            }
            .clip(shape)
            .liuliUserBubbleGradient(yInRoot = { anchor.yInRoot }, rootHeightPx = { anchor.rootHeight })
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.a11y_message_menu),
            )
            .then(a11yDescription?.let { Modifier.semantics { contentDescription = it } } ?: Modifier)
            .padding(start = BubblePadStart, end = BubblePadEnd, top = BubblePadTop, bottom = BubblePadBottom),
    ) {
        LiuliQuoteBlock(quotedContent, quotedSender, onUser = true)
        LiuliInlineStampLayout(
            textString = text,
            textStyle = AppTypography.body,
            stamp = {
                LiuliInlineStamp(
                    timestampMs = timestampMs,
                    isUser = true,
                    read = deliveryRead,
                    stampColor = Palette.White.copy(alpha = USER_STAMP_ALPHA),
                )
            },
            text = { Text(text, style = AppTypography.body, color = Palette.White) },
        )
    }
}

/**
 * AI 文字气泡（会变身）：三点与正文同处一个泡内，按 [revealed] 交叉变身——机制逐条照抄暖陶
 * `AssistantTextBubble`（morph 单值驱动 + `animateContentSize` 承担长高 + 外层 clip 显露），只换皮。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiuliAssistantBubble(
    revealed: Boolean,
    text: String,
    quotedContent: String?,
    quotedSender: String?,
    timestampMs: Long,
    tail: Boolean,
    maxWidth: Dp,
    onLongClick: () -> Unit,
    /** 卷二B：双击 = ❤️ 回应（纯瞬态·A-8）。单击本就无动作，加双击不牺牲任何既有手感。 */
    onDoubleClick: (() -> Unit)? = null,
    a11yDescription: String?,
    /** 卷二C A-1：本条 uuid + 会话级折叠记账——长文只裁显示高度，机制一概不动。 */
    messageUuid: String = "",
    fold: LiuliFoldState? = null,
) {
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()
    val shape = liuliBubbleShape(isUser = false, tail = tail)
    val bubbleColor = colors.bubble.ai
    // 卷二C A-10：这两枚字面量已收进 `LiuliPalette`（琉璃裸 Color 的唯一出口）。
    val stampColor = if (colors.isDark) LiuliPalette.aiStampDark else LiuliPalette.aiStampLight
    val morph by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else AppMotion.gentleSpring(),
        label = "liuliBubbleMorph",
    )
    Box(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .liuliBubbleTail(isUser = false, show = tail) { bubbleColor }
            .then(if (!colors.isDark) Modifier.shadow(AiContactShadow, shape, clip = false) else Modifier)
            .clip(shape)
            .background(bubbleColor)
            .border(AiHairline, colors.bubble.aiStroke, shape)
            .combinedClickable(
                onClick = {},
                onLongClick = { if (revealed) onLongClick() },
                onLongClickLabel = stringResource(R.string.a11y_message_menu),
                onDoubleClick = onDoubleClick,
            )
            .padding(start = BubblePadStart, end = BubblePadEnd, top = BubblePadTop, bottom = BubblePadBottom),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier.animateContentSize(if (reduceMotion) snap() else AppMotion.gentleSpring()),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (revealed) {
                Column(
                    modifier = (if (a11yDescription != null) Modifier.semantics { contentDescription = a11yDescription } else Modifier)
                        .graphicsLayer { alpha = morph },
                ) {
                    LiuliQuoteBlock(quotedContent, quotedSender, onUser = false)
                    // 卷二C A-1：正文槽换成可折叠件——引用块在折叠区**之上**不被裁（E15）。
                    LiuliFoldableText(
                        text = text,
                        style = AppTypography.body,
                        color = colors.text.primary,
                        revealed = revealed,
                        isUser = false,
                        expanded = fold?.isExpanded(messageUuid) ?: true,
                        onExpand = { fold?.expand(messageUuid) },
                        fadeColor = bubbleColor,
                        stamp = {
                            LiuliInlineStamp(
                                timestampMs = timestampMs,
                                isUser = false,
                                read = null,
                                stampColor = stampColor,
                            )
                        },
                    )
                }
            }
            if (morph < MORPH_DONE) {
                val typingCd = stringResource(R.string.a11y_typing_indicator)
                Box(
                    modifier = Modifier
                        .clearAndSetSemantics { contentDescription = typingCd }
                        .graphicsLayer {
                            alpha = 1f - morph
                            scaleX = 1f - TYPING_SHRINK * morph
                            scaleY = 1f - TYPING_SHRINK * morph
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // 高度锚：隐形单行正文 → 单行回复变身只长宽不跳高（照抄暖陶）。
                    Text(" ", style = AppTypography.body)
                    LiuliTypingDots()
                }
            }
        }
    }
}

/** 变身进度视作完成的阈值 / 三点层缩放幅度（照抄暖陶 F8）。 */
private const val MORPH_DONE = 0.999f
private const val TYPING_SHRINK = 0.06f

/** 泡内引用块（图纸 §4.4）：左竖线 + 发送者 + 内容；[quotedContent] 为空时零渲染。 */
@Composable
private fun LiuliQuoteBlock(quotedContent: String?, quotedSender: String?, onUser: Boolean) {
    if (quotedContent.isNullOrEmpty()) return
    val colors = AppTheme.colors
    val barColor = if (onUser) Palette.White.copy(alpha = USER_QUOTE_BAR_ALPHA) else colors.accent.text
    val senderColor = if (onUser) Palette.White.copy(alpha = USER_QUOTE_TEXT_ALPHA) else colors.text.secondary
    Column(
        modifier = Modifier
            .padding(bottom = QuoteBottomGap)
            .drawBehind {
                drawRect(barColor, size = size.copy(width = QuoteBarWidth.toPx()))
            }
            .padding(start = QuoteInset),
    ) {
        if (!quotedSender.isNullOrEmpty()) {
            Text(quotedSender, style = QuoteSenderStyle, color = senderColor, maxLines = 1)
        }
        Text(
            quotedContent.take(QUOTE_PREVIEW_CHARS),
            style = AppTypography.secondary,
            color = senderColor,
            maxLines = 2,
        )
    }
}

/** 引用块落值（图纸 §4.4 锁：竖线 2.5dp · 内缩 8dp · 下距 5dp · 发送者 12sp/W520 = label 降号）。 */
private val QuoteBarWidth = 2.5.dp
private val QuoteInset = 8.dp
private val QuoteBottomGap = 5.dp
private val QuoteSenderStyle = AppTypography.label.copy(fontSize = 12.sp, lineHeight = 15.sp)

/** 引用摘要截断长度（照抄暖陶 `Bubble` 的 `take(40)`）。 */
private const val QUOTE_PREVIEW_CHARS = 40

/**
 * 打字三点（照抄暖陶 `TypingDots` 的节奏与几何：8dp 点 · 上跳 4dp · bounce 300 / stagger 150 / 周期 900ms ·
 * sin 包络 · reduceMotion 静帧）；点色 = `text.tertiary`（图纸 §4.4）。
 */
@Composable
private fun LiuliTypingDots(modifier: Modifier = Modifier) {
    val bounceMs = 300f
    val staggerMs = 150f
    val cycleMs = staggerMs * 2 + bounceMs + 300f
    val phaseState: State<Float> = if (rememberReduceMotion()) {
        remember { mutableStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "liuliTyping").animateFloat(
            initialValue = 0f,
            targetValue = cycleMs,
            animationSpec = infiniteRepeatable(
                animation = tween(cycleMs.toInt(), easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "liuliTypingPhase",
        )
    }
    val dotColor = AppTheme.colors.text.tertiary
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(3) { index ->
            Box(
                Modifier
                    .offset {
                        val localTime = phaseState.value - index * staggerMs
                        val bounce = if (localTime in 0f..bounceMs) sin(localTime / bounceMs * PI).toFloat() else 0f
                        IntOffset(0, (-4f * bounce).dp.roundToPx())
                    }
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}
