package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.ourdays.CellModel
import com.situ.aichat.ui.ourdays.OurDaysFormat
import com.situ.aichat.ui.ourdays.OurDaysStripState
import com.situ.aichat.ui.ourdays.OurDaysStripViewModel

/** 七格落值（照暖陶 `OurDaysStrip` 逐字）：格高 40 · 圆角 9 · 今日圆标 20×18 · 日号 12.5 · 星期 9.5。 */
private val CELL_HEIGHT = 40.dp
private val CELL_CORNER = 9.dp
private val CELL_GAP = 4.dp
private val TODAY_PILL_WIDTH = 20.dp
private val TODAY_PILL_HEIGHT = 18.dp
private const val FUTURE_ALPHA = 0.5f

/**
 * 琉璃「我们的日子」全宽条（图纸 2026-09-06 卷三 §4.5）。结构 / 七格算法 / 尾句三态**逐字照暖陶**
 * `OurDaysStrip`（同一个 [OurDaysStripViewModel]、同一套 `OurDaysFormat`），只换皮：纸白卡 20 发丝无软影、
 * 今日圆标走 `accent.gradient`（暖陶是 deep 渐变·A-12 同口径色族替换）。
 */
@Composable
fun LiuliOurDaysStrip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OurDaysStripViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiuliOurDaysStripContent(state = state, onClick = onClick, modifier = modifier)
}

/** 无 VM 版（T2 可直接驱动）。 */
@Composable
internal fun LiuliOurDaysStripContent(
    state: OurDaysStripState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val title = stringResource(R.string.our_days_title)
    val subText = state.character?.let { stringResource(R.string.our_days_strip_sub, it.name, state.nthDay ?: 1) }
        ?: stringResource(R.string.our_days_strip_sub_empty)
    val a11y = stringResource(R.string.our_days_a11y_strip, subText)
    LiuliHubCard(
        onClick = onClick,
        onClickLabel = title,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = a11y },
    ) {
        LiuliStripHeader(title = title, subText = subText)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            state.week.forEach { cell -> LiuliStripCell(cell, Modifier.weight(1f)) }
        }
        val preview = state.preview
        val mdPattern = stringResource(R.string.our_days_fmt_md)
        val tailStyle = AppTypography.snackbarBody
        if (preview != null) {
            val label = if (preview.isYesterday) {
                stringResource(R.string.our_days_strip_yesterday, preview.characterName)
            } else {
                stringResource(R.string.our_days_strip_dated, OurDaysFormat.date(preview.date, mdPattern), preview.characterName)
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.accent.text, fontWeight = FontWeight.Medium)) { append(label) }
                    append("　")
                    withStyle(SpanStyle(fontFamily = AppTypography.kaiFontFamily)) { append(preview.firstSentence) }
                },
                style = tailStyle, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 9.dp),
            )
        } else {
            Text(
                stringResource(R.string.our_days_empty_hint), style = tailStyle, color = colors.text.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp),
            )
        }
    }
}

@Composable
private fun LiuliStripCell(cell: CellModel, modifier: Modifier) {
    val colors = AppTheme.colors
    Box(
        modifier
            .height(CELL_HEIGHT)
            .clip(RoundedCornerShape(CELL_CORNER))
            .background(liuliHeatColor(cell.heatLevel))
            .then(if (cell.isFuture) Modifier.alpha(FUTURE_ALPHA) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val todayPill = if (cell.isToday) {
                Modifier.clip(RoundedCornerShape(CELL_CORNER))
                    .background(Brush.linearGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd)))
            } else {
                Modifier
            }
            Box(
                Modifier.size(width = TODAY_PILL_WIDTH, height = TODAY_PILL_HEIGHT).then(todayPill),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${cell.date.dayOfMonth}",
                    style = AppTypography.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                    color = if (cell.isToday) colors.text.onAccent else colors.text.primary,
                )
            }
            Text(OurDaysFormat.weekdayNarrow(cell.date.dayOfWeek), style = AppTypography.caption.copy(fontSize = 9.5.sp), color = colors.text.tertiary)
        }
    }
}

/** 热度底色（与暖陶同一组 `ourDays.heat*` token·色族沿用·契约 §3.1 #3）。 */
@Composable
private fun liuliHeatColor(level: Int): Color = when (level) {
    1 -> AppTheme.colors.ourDays.heat1
    2 -> AppTheme.colors.ourDays.heat2
    3 -> AppTheme.colors.ourDays.heat3
    else -> Color.Transparent
}
