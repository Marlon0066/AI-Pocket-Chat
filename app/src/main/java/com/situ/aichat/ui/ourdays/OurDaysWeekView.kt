package com.situ.aichat.ui.ourdays

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface
import java.time.LocalDate

/**
 * 周视图「一周书页」（卷三图纸 §4.4·提案 D-5）：期头 + 七格周条（44dp·热度底·今天药丸）+ 七张日卡竖排（未来日不出卡）。
 * 全部模式日卡改分段（同选中卡）。左右滑翻周由页面壳挂手势。
 */
@Composable
internal fun OurDaysWeekView(
    model: WeekModel,
    periodContainsToday: Boolean,
    allMode: Boolean,
    characterUuid: String?,
    characterName: String?,
    onSelectDate: (LocalDate) -> Unit,
    onShift: (Int) -> Unit,
    onToday: () -> Unit,
    onOpenDay: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mdPattern = stringResource(R.string.our_days_fmt_md)
    val title = if (model.start.month == model.end.month) {
        stringResource(R.string.our_days_week_range_same_month, OurDaysFormat.date(model.start, mdPattern), model.end.dayOfMonth)
    } else {
        stringResource(R.string.our_days_week_range, OurDaysFormat.date(model.start, mdPattern), OurDaysFormat.date(model.end, mdPattern))
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        OurDaysPeriodHeader(title = title, showToday = !periodContainsToday, onToday = onToday, onShift = onShift)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            model.strip.forEach { cell -> WeekStripCell(cell, mdPattern, onClick = { onSelectDate(cell.date) }, modifier = Modifier.weight(1f)) }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            model.cards.forEach { card -> WeekCard(card, allMode, characterUuid, characterName, onOpenDay) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WeekStripCell(cell: CellModel, mdPattern: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val shape = RoundedCornerShape(10.dp)
    val dateLabel = OurDaysFormat.date(cell.date, mdPattern)
    val state = cellStateDescription(cell)
    val description = if (state.isEmpty()) dateLabel else stringResource(R.string.our_days_a11y_cell, dateLabel, state)
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(if (cell.isFuture) Color.Transparent else heatColor(cell.heatLevel))
            .then(if (cell.isFuture) Modifier else Modifier.clickable(onClickLabel = dateLabel) { haptics.selection(); onClick() })
            .semantics {
                selected = cell.selected
                if (state.isNotEmpty()) stateDescription = state
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = if (cell.isFuture) Modifier.alpha(0.45f) else Modifier) {
            Box(
                Modifier
                    .size(width = 22.dp, height = 20.dp)
                    .then(
                        if (cell.isToday) {
                            Modifier.clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(colors.accent.deepStart, colors.accent.deepEnd)))
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${cell.date.dayOfMonth}",
                    style = AppTypography.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                    color = if (cell.isToday) colors.accent.onDeep else colors.text.primary,
                )
            }
            Text(OurDaysFormat.weekdayNarrow(cell.date.dayOfWeek), style = AppTypography.caption.copy(fontSize = 10.sp), color = colors.text.tertiary)
        }
    }
}

/** 周卡（§4.4）：左列日数 20sp / 周几 / 副行；右列手记两行（13.5/23）+ chips；空日 75% 不透明；全部模式分段。 */
@Composable
private fun WeekCard(card: DayCardModel, allMode: Boolean, characterUuid: String?, characterName: String?, onOpenDay: (String, String) -> Unit) {
    val colors = AppTheme.colors
    val weekday = OurDaysFormat.date(card.date, stringResource(R.string.our_days_fmt_weekday))
    val openUuid = characterUuid?.takeIf { !allMode }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .appCardSurface()
            .then(if (openUuid != null) Modifier.clickableScale { onOpenDay(openUuid, card.key) } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .then(if (card.status == CardStatus.EMPTY) Modifier.alpha(0.75f) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${card.date.dayOfMonth}", style = AppTypography.titleMedium.copy(fontSize = 20.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum"), color = colors.text.primary)
            Text(weekday, style = AppTypography.caption.copy(fontSize = 10.sp), color = colors.text.tertiary)
            card.decor?.let { d ->
                Text(d.subtitle, style = AppTypography.caption.copy(fontSize = 10.sp), color = if (d.emphasized) colors.accent.text else colors.text.tertiary)
            }
        }
        Column(Modifier.weight(1f)) {
            if (allMode) {
                OurDaysAllSegments(card, onOpenDay)
            } else {
                OurDayCardBody(card, characterName, AppTypography.kaiQuote.copy(fontSize = 13.5.sp, lineHeight = 23.sp))
                if (card.chips.isNotEmpty()) OurDayCardChips(card.chips, Modifier.padding(top = 8.dp))
            }
        }
    }
}
