package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.prompt.CalendarItemParser
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

/**
 * 琉璃日程卡（图纸 2026-09-05 卷二C §4.6 · A-3）：`[#E1] …` 行经 [CalendarItemParser] 拆段——
 * 文字段照 `body` **原位**直出、**连续**的条目段合并进同一张 236 卡的时间列（对版稿 `.card.sched .bd` 两列网格），
 * 段序与解析器给的一致（复核 R1 🟡-5：不把文字全提到卡前——条目后的那句话仍在卡下面）。
 *
 * 解析器零碰（红线：`[#E1]` ↔ `CalendarItemParser` 双侧同步）；这里只消费解析结果，`dateInfo` 原样上屏
 * 不再二次解析（F10）。整卡长按 = 沉浸菜单（照抄暖陶 `ScheduleCardBubble`）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiuliScheduleCard(content: String, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val blocks = remember(content) { liuliScheduleBlocks(CalendarItemParser.parse(content)) }

    Column(
        modifier = modifier.combinedClickable(
            onClick = {},
            onLongClick = onLongClick,
            onLongClickLabel = stringResource(R.string.a11y_message_menu),
        ),
        verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
    ) {
        blocks.forEach { block ->
            when (block) {
                is LiuliScheduleBlock.Text -> Text(block.text, style = AppTypography.body, color = colors.text.primary)
                is LiuliScheduleBlock.Items -> LiuliScheduleItemsCard(block.items)
            }
        }
    }
}

@Composable
private fun LiuliScheduleItemsCard(items: List<CalendarItemParser.ParsedCalendarItem>) {
    val colors = AppTheme.colors
    // 图标块两色（§4.6「EVENT / 提醒 两色只上图标块」）：卡只有一个头，故按**首条**条目的类型取色——
    // REMINDER 在安卓是平台缺口的防御渲染分支（暖陶 `CalendarItemCard` 原注），实测不会与 EVENT 混排。
    val reminder = items.firstOrNull()?.type == CalendarItemParser.ItemType.REMINDER
    LiuliCard(width = LiuliChatGeometry.cardWidth) {
        LiuliCardHeader(
            icon = Icons.Filled.DateRange,
            title = "今天的安排",
            subtitle = null,
            tag = "日程",
            iconBlockColor = if (reminder) colors.status.warningContainer else colors.accent.container,
            iconColor = if (reminder) colors.status.onWarning else colors.accent.onContainer,
        )
        LiuliCardBody {
            items.forEach { item ->
                Row(
                    modifier = Modifier.padding(bottom = ROW_GAP),
                    horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
                    verticalAlignment = Alignment.Top,
                ) {
                    // 时间列只设**下限 + 上限**：`dateInfo` 是解析器原样给的括号内文本，可能是「08:30」也可能是
                    // 「9月7日 14:00~15:30」——短的对齐成一列，长的自然展开、标题跟着右移，不截不叠；上限留给
                    // 标题至少 86dp（复核 R1 🟡-5）。
                    Text(
                        item.dateInfo,
                        style = AppTypography.captionNumeric,
                        color = colors.text.tertiary,
                        modifier = Modifier.widthIn(min = TIME_COLUMN, max = TIME_COLUMN_MAX),
                    )
                    Text(
                        item.title,
                        style = AppTypography.secondary,
                        color = colors.text.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 段 → 块：文字段原位、连续条目段合并成一张卡（纯函数 · T1）。 */
internal sealed interface LiuliScheduleBlock {
    data class Text(val text: String) : LiuliScheduleBlock
    data class Items(val items: List<CalendarItemParser.ParsedCalendarItem>) : LiuliScheduleBlock
}

internal fun liuliScheduleBlocks(segments: List<CalendarItemParser.Segment>): List<LiuliScheduleBlock> {
    val out = mutableListOf<LiuliScheduleBlock>()
    val run = mutableListOf<CalendarItemParser.ParsedCalendarItem>()
    fun flush() {
        if (run.isNotEmpty()) {
            out += LiuliScheduleBlock.Items(run.toList())
            run.clear()
        }
    }
    segments.forEach { seg ->
        when (seg) {
            is CalendarItemParser.Segment.Text -> {
                flush()
                out += LiuliScheduleBlock.Text(seg.text)
            }
            is CalendarItemParser.Segment.Item -> run += seg.item
        }
    }
    flush()
    return out
}

/** 落值（对版稿 `.card.sched .bd{gap:4 10}`·时间列下限按 `HH:mm` 五字宽定，条目再多也对齐成一列）。 */
private val SEGMENT_GAP = 4.dp
private val ROW_GAP = 4.dp
private val COLUMN_GAP = 10.dp
private val TIME_COLUMN = 38.dp
/** 236 卡 − 14×2 边 − 10 列距 − 标题至少 86 = 112。 */
private val TIME_COLUMN_MAX = 112.dp
