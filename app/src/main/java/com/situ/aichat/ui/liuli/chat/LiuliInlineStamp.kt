package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.util.DateFormatters
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 泡内时间戳（图纸 2026-09-05 卷二A §4.4 · 契约 §5.3「时间戳浮末行右下」）。
 *
 * Compose 没有 float 排版，故自写 [LiuliInlineStampLayout]：用 `rememberTextMeasurer` 在**测量期**量出正文
 * 末行右缘——末行右缘 + 8dp + 戳宽 ≤ 可用宽 → 戳坐末行右下同一行；否则另起一行右对齐（高 += 戳高 + 2dp）。
 * 零延迟无抖（不靠 `onTextLayout` 回填致二次布局）。
 */

/** 末行右缘与戳之间的最小空当（图纸 §4.7 零重叠 ⑦）。 */
private val StampGap = 8.dp

/** 戳另起一行时与正文的行距。 */
private val StampNewLineGap = 2.dp

@Composable
internal fun LiuliInlineStampLayout(
    textString: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    stamp: @Composable () -> Unit,
    text: @Composable () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val stampPlaceable = subcompose(StampSlot.Stamp, stamp).first().measure(Constraints())
        val textPlaceable = subcompose(StampSlot.Text, text).first().measure(loose)
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else textPlaceable.width
        val measured = measurer.measure(
            text = AnnotatedString(textString),
            style = textStyle,
            constraints = Constraints(maxWidth = available),
        )
        val lastLineRight = if (measured.lineCount > 0) measured.getLineRight(measured.lineCount - 1) else 0f
        val gapPx = StampGap.toPx()
        val inlineWidth = stampInlineWidthPx(lastLineRight, gapPx, stampPlaceable.width)
        val inline = stampFitsOnLastLine(lastLineRight, gapPx, stampPlaceable.width, available)
        val width = if (inline) {
            max(textPlaceable.width, inlineWidth)
        } else {
            max(textPlaceable.width, stampPlaceable.width)
        }
        val newLineGapPx = StampNewLineGap.roundToPx()
        val height = if (inline) textPlaceable.height else textPlaceable.height + newLineGapPx + stampPlaceable.height
        layout(width, height) {
            textPlaceable.place(0, 0)
            if (inline) {
                // 与末行底缘对齐（戳高恒小于行高 → 贴泡内下缘）。
                stampPlaceable.place(width - stampPlaceable.width, textPlaceable.height - stampPlaceable.height)
            } else {
                stampPlaceable.place(width - stampPlaceable.width, textPlaceable.height + newLineGapPx)
            }
        }
    }
}

private enum class StampSlot { Stamp, Text }

/**
 * 戳坐末行右下所需的**总宽**（px）：末行右缘 + 空当 + 戳宽（向上取整避免半像素挤掉一像素）。纯函数 · T1。
 */
internal fun stampInlineWidthPx(lastLineRightPx: Float, gapPx: Float, stampWidthPx: Int): Int =
    ceil(lastLineRightPx + gapPx).roundToInt() + stampWidthPx

/**
 * 戳能否坐末行右下（图纸 §4.4 判据）：所需总宽 ≤ 可用宽即同行，否则另起一行右对齐。纯函数 · T1。
 */
internal fun stampFitsOnLastLine(
    lastLineRightPx: Float,
    gapPx: Float,
    stampWidthPx: Int,
    availableWidthPx: Int,
): Boolean = stampInlineWidthPx(lastLineRightPx, gapPx, stampWidthPx) <= availableWidthPx

/**
 * 一枚时间戳（HH:mm + 用户消息回执勾）。语义照抄暖陶 `BubbleInlineTimestamp`（F9）：
 * [read]=true → ✓✓；false → ✓ 且**发出 1s 后才显**（前 1s 只有时间）；null（AI 消息）→ 只有时间。
 * 色：用户泡白 72%（[stampColor]）/ AI 泡 `LiuliPalette.aiStampLight` / `aiStampDark` / 玻璃上走 onGlass。
 */
@Composable
internal fun LiuliInlineStamp(
    timestampMs: Long,
    isUser: Boolean,
    read: Boolean?,
    stampColor: Color,
    modifier: Modifier = Modifier,
    /** 卷二C A-6：图片泡的玻璃戳是 10.5sp（默认档 = 泡内戳的 11sp `captionNumeric`·既有调用零变化）。 */
    textStyle: TextStyle = AppTypography.captionNumeric,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            DateFormatters.hourMinute(timestampMs),
            style = textStyle,
            color = stampColor,
        )
        if (isUser && read != null) {
            Crossfade(targetState = read, label = "liuliReceipt") { isRead ->
                if (isRead) {
                    Icon(
                        Icons.Filled.DoneAll,
                        contentDescription = stringResource(R.string.a11y_message_read),
                        tint = stampColor,
                        modifier = Modifier.size(TickSize),
                    )
                } else {
                    // 送达：发出 1s 后才显单勾（1:1 iOS deliveryReceiptRevealDelaySeconds=1.0）。
                    var revealed by remember(timestampMs) { mutableStateOf(false) }
                    LaunchedEffect(timestampMs) {
                        delay(1000)
                        revealed = true
                    }
                    if (revealed) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.a11y_message_delivered),
                            tint = stampColor,
                            modifier = Modifier.size(TickSize),
                        )
                    }
                }
            }
        }
    }
}

/** 勾号尺寸（图纸 §3.2 锁）。 */
private val TickSize = 14.dp
