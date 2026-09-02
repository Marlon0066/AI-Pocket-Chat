package com.situ.aichat.ui.ourdays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface
import java.time.YearMonth

/**
 * 年视图（卷三图纸 §4.5·提案 D-6·帧 6）：期头 + 副标 + 3×4 迷你月（8dp 内距·12dp 圆角·微格 2.5dp 圆角 / 2dp 间·见面暖金 /
 * 热度三档 / 今天描边·相识前 45%）+ 数字药丸排（零项省略·整串资源渲染·数字不加粗·全屏一致）。点迷你月 → 月视图。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OurDaysYearView(
    model: YearModel,
    periodContainsToday: Boolean,
    allMode: Boolean,
    characterName: String?,
    onShift: (Int) -> Unit,
    onToday: () -> Unit,
    onOpenMonth: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        OurDaysPeriodHeader(title = "${model.year}", showToday = !periodContainsToday, onToday = onToday, onShift = onShift)
        val subtitle = when {
            allMode -> stringResource(R.string.our_days_year_sub_all, model.characterCount, model.recordedDays)
            characterName != null && model.firstDay != null && model.daysTogether != null ->
                stringResource(R.string.our_days_year_sub, characterName, OurDaysFormat.date(model.firstDay, stringResource(R.string.our_days_fmt_md)), model.daysTogether)
            else -> null
        }
        if (subtitle != null) Text(subtitle, style = AppTypography.secondary.copy(fontSize = 12.sp), color = colors.text.secondary)
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            model.months.chunked(3).forEach { rowMonths ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowMonths.forEach { m -> MiniMonthCard(m, onClick = { onOpenMonth(m.yearMonth) }, modifier = Modifier.weight(1f)) }
                }
            }
        }
        val pills = buildList {
            if (model.stats.chatDays > 0) add(stringResource(R.string.our_days_year_chat_days, model.stats.chatDays))
            if (model.stats.meetings > 0) add(stringResource(R.string.our_days_year_meetings, model.stats.meetings))
            if (model.stats.milestones > 0) add(stringResource(R.string.our_days_year_milestones, model.stats.milestones))
            if (model.stats.promisesFulfilled > 0) add(stringResource(R.string.our_days_year_promises, model.stats.promisesFulfilled))
            if (model.stats.calls > 0) add(stringResource(R.string.our_days_year_calls, model.stats.calls))
        }
        if (pills.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pills.forEach { text ->
                    Row(Modifier.appCardSurface(cornerRadius = 20.dp).padding(horizontal = 11.dp, vertical = 5.dp)) {
                        Text(text, style = AppTypography.caption.copy(fontSize = 12.sp), color = colors.text.secondary)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MiniMonthCard(month: MiniMonth, onClick: () -> Unit, modifier: Modifier) {
    val colors = AppTheme.colors
    val monthLabel = OurDaysFormat.monthShort(month.yearMonth)
    val a11y = stringResource(R.string.our_days_a11y_mini_month, monthLabel)
    val cellShape = RoundedCornerShape(2.5.dp)
    Column(
        modifier
            .appCardSurface(cornerRadius = 12.dp)
            .clickableScale(onClickLabel = a11y) { onClick() }
            .padding(8.dp)
            .then(if (month.dimmed) Modifier.alpha(0.45f) else Modifier)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 5.dp)) {
            Text(monthLabel, style = AppTypography.caption.copy(fontSize = 11.5.sp), fontWeight = FontWeight.Medium, color = colors.text.primary)
            if (month.isCurrent) {
                Text(stringResource(R.string.our_days_year_now), style = AppTypography.caption.copy(fontSize = 9.sp), color = colors.text.tertiary, modifier = Modifier.padding(start = 4.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            month.cells.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    week.forEach { cell ->
                        val color = when {
                            !cell.inMonth -> Color.Transparent
                            cell.meeting -> colors.ourDays.dotMeeting
                            cell.heatLevel == 0 -> colors.surface.sunken
                            else -> heatColor(cell.heatLevel)
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(cellShape)
                                .background(color)
                                .then(if (cell.isToday) Modifier.border(1.5.dp, colors.accent.primary, cellShape) else Modifier),
                        )
                    }
                }
            }
        }
    }
}
