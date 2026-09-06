package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.data.model.SystemEventData
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 琉璃系统行（图纸 2026-09-05 卷二C §4.8 · A-8）：居中小字 12sp `text.secondary`、宽 ≤ 82%，
 * **去掉暖陶那枚 sunken 胶囊底**（对版稿 `.sysline` 无底）。
 *
 * 红包系统事件保留暖陶「emoji + 文案 · M月d日 HH:mm」的拼法与「时间为空即隐藏点与时间」的规则（F13）；
 * 系统耳语（`SYSTEM_HINT`）走楷体（`kaiQuote`）——它由 `OfflineChatVisibility` 无条件挡在日常聊天之外，
 * 与暖陶同（此件供剧场 / 复核取证与 T2-10 钉样式，日常列表恒不调）。
 */
@Composable
internal fun LiuliSystemEventLine(event: SystemEventData, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val time = remember(event.timestamp) { liuliFormatSystemEventTime(event.timestamp) }
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = LINE_VERTICAL),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(SYSTEM_LINE_WIDTH_FRACTION),
            horizontalArrangement = Arrangement.spacedBy(PART_GAP, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(event.emoji, style = LiuliSystemLineStyle, color = colors.text.secondary)
            Text(
                event.title,
                style = LiuliSystemLineStyle,
                color = colors.text.secondary,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
            if (time.isNotEmpty()) {
                Text("·", style = LiuliSystemLineStyle, color = colors.text.tertiary)
                Text(time, style = LiuliSystemLineStyle.copy(fontFeatureSettings = TNUM), color = colors.text.secondary)
            }
        }
    }
}

/** 系统耳语 / 旁白（A-8）：同居中小字位，但走楷体点缀族。 */
@Composable
internal fun LiuliSystemHintLine(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = LINE_VERTICAL),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = LiuliSystemHintStyle,
            color = AppTheme.colors.text.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(SYSTEM_LINE_WIDTH_FRACTION),
        )
    }
}

/**
 * ISO-8601 →「M月d日 HH:mm」（**重打**暖陶 `RedPacketSystemEventCard.formatEventTime` 同值·那侧是 private·
 * 两侧注释互指）：空串 / 解析失败恒 ""（调用方据此隐藏「·」与时间）。
 */
internal fun liuliFormatSystemEventTime(iso: String): String =
    if (iso.isBlank()) {
        ""
    } else {
        runCatching {
            Instant.parse(iso).atZone(ZoneId.systemDefault()).format(LIULI_SYSTEM_EVENT_FORMATTER)
        }.getOrDefault("")
    }

private val LIULI_SYSTEM_EVENT_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.ROOT)

/** 落值（A-8 + 对版稿 `.sysline{12px; line-height:1.4; max-width:82%}`·孤值即打回）。 */
private const val SYSTEM_LINE_WIDTH_FRACTION = 0.82f
private val LINE_VERTICAL = 6.dp
private val PART_GAP = 6.dp
private const val TNUM = "tnum"

/** 系统行字（A-8：12sp·对版稿 `line-height:1.4` → 17sp）。internal 便于 T2-10 直接钉字号。 */
internal val LiuliSystemLineStyle = AppTypography.caption.copy(fontSize = 12.sp, lineHeight = 17.sp)

/** 耳语字（A-8：楷体点缀族）。internal 便于 T2-10 钉 `fontFamily == kaiFontFamily`。 */
internal val LiuliSystemHintStyle = AppTypography.kaiQuote
