package com.situ.aichat.ui.ourdays

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import java.time.LocalDate

/**
 * 月视图（卷三图纸 §4.3·提案 D-4·mockup 帧 3 / 4）：期头 + 周几表头 + 月格（50dp·10dp 圆角·热度底·今天药丸·选中描边·
 * 副行·三点 / 识别圈·休班角标）+ 汇总行 + 选中日卡。空 / 未来 / 邻月格不挂 clickable（照 `DiaryCalendarSection`）。
 */
@Composable
internal fun OurDaysMonthView(
    model: MonthModel,
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
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()
    val mdPattern = stringResource(R.string.our_days_fmt_md)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        OurDaysPeriodHeader(
            title = OurDaysFormat.date(model.yearMonth.atDay(1), stringResource(R.string.our_days_fmt_month)),
            showToday = !periodContainsToday, onToday = onToday, onShift = onShift,
        )
        Row(Modifier.padding(bottom = 4.dp)) {
            model.weekdayLabels.forEach { label ->
                Text(label, style = AppTypography.caption, color = colors.text.tertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            model.cells.chunked(7).forEach { week ->
                Row {
                    week.forEach { cell -> MonthCell(cell, allMode, mdPattern, onClick = { onSelectDate(cell.date) }, modifier = Modifier.weight(1f)) }
                }
            }
        }
        Text(monthSummaryText(model.summary), style = AppTypography.secondary.copy(fontSize = 12.sp), color = colors.text.secondary, modifier = Modifier.padding(top = 10.dp))
        model.selectedCard?.let { card ->
            OurDaysDayCard(
                card = card, characterUuid = characterUuid, allMode = allMode, characterName = characterName, onOpenDay = onOpenDay,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .then(if (reduceMotion) Modifier else Modifier.animateContentSize(AppMotion.calmSpring(IntSize.VisibilityThreshold))),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 期头（§4.3-A·月 / 周 / 年共用）：标题 liveRegion + 「今天」药丸（所在期不含今天才显）+ 上一期 / 下一期。 */
@Composable
internal fun OurDaysPeriodHeader(title: String, showToday: Boolean, onToday: () -> Unit, onShift: (Int) -> Unit) {
    val colors = AppTheme.colors
    val todayLabel = stringResource(R.string.our_days_menu_today)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)) {
        Text(title, style = AppTypography.titleSmall, color = colors.text.primary, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        if (showToday) {
            Text(
                stringResource(R.string.our_days_today),
                style = AppTypography.caption,
                color = colors.accent.text,
                modifier = Modifier
                    .clip(AppShapes.full)
                    .border(1.dp, colors.accent.primary.copy(alpha = 0.35f), AppShapes.full)
                    .clickable(onClickLabel = todayLabel) { onToday() }
                    .padding(horizontal = 9.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        IconButton(onClick = { onShift(-1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.our_days_a11y_prev), tint = colors.text.secondary)
        }
        IconButton(onClick = { onShift(1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.our_days_a11y_next), tint = colors.text.secondary)
        }
    }
}

/** 汇总行文案（§4.3-D·纯核只给数）。 */
@Composable
private fun monthSummaryText(s: MonthSummary): String {
    val monthLabel = OurDaysFormat.date(s.yearMonth.atDay(1), stringResource(R.string.our_days_fmt_month))
    val sep = " " + stringResource(R.string.our_days_summary_sep) + " "
    if (s.allMode) return monthLabel + sep + stringResource(R.string.our_days_summary_all, s.characterCount, s.recordedDays)
    val chat = stringResource(R.string.our_days_summary_chat_days, s.chatDays)
    if (s.justStarted) {
        return listOf(monthLabel, stringResource(R.string.our_days_summary_just_started)).plus(if (s.chatDays > 0) listOf(chat) else emptyList()).joinToString(sep)
    }
    val parts = buildList {
        if (s.chatDays > 0) add(chat)
        if (s.meetings > 0) add(stringResource(R.string.our_days_summary_meetings, s.meetings))
        if (s.promisesFulfilled > 0) add(stringResource(R.string.our_days_summary_promises, s.promisesFulfilled))
        if (s.milestones > 0) add(stringResource(R.string.our_days_summary_milestones, s.milestones))
    }
    return if (parts.isEmpty()) monthLabel + sep + stringResource(R.string.our_days_summary_none) else monthLabel + sep + parts.joinToString(sep)
}

/** 月格 a11y 状态串（今天 / 有记录 / 已选中·以「，」连）。 */
@Composable
internal fun cellStateDescription(cell: CellModel): String = buildList {
    if (cell.isToday) add(stringResource(R.string.our_days_a11y_state_today))
    if (cell.heatLevel > 0 || cell.dots.isNotEmpty() || cell.identity.isNotEmpty()) add(stringResource(R.string.our_days_a11y_state_record))
    if (cell.selected) add(stringResource(R.string.our_days_a11y_state_selected))
}.joinToString("，")

@Composable
private fun MonthCell(cell: CellModel, allMode: Boolean, mdPattern: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val shape = RoundedCornerShape(10.dp)
    val background = if (cell.isFuture || !cell.inPeriod) Color.Transparent else heatColor(cell.heatLevel)
    val dateLabel = OurDaysFormat.date(cell.date, mdPattern)
    val state = cellStateDescription(cell)
    val description = if (state.isEmpty()) dateLabel else stringResource(R.string.our_days_a11y_cell, dateLabel, state)
    val clickable = cell.inPeriod && !cell.isFuture
    Box(
        modifier
            .height(50.dp)
            .clip(shape)
            .background(background)
            .then(if (cell.selected) Modifier.border(1.5.dp, colors.accent.primary, shape) else Modifier)
            .then(if (clickable) Modifier.clickable(onClickLabel = dateLabel) { haptics.selection(); onClick() } else Modifier)
            .semantics {
                selected = cell.selected
                if (state.isNotEmpty()) stateDescription = state
                contentDescription = description
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp).then(if (cell.isFuture) Modifier.alpha(0.45f) else Modifier),
        ) {
            Box(
                Modifier
                    .size(width = 26.dp, height = 22.dp)
                    .then(
                        if (cell.isToday) {
                            Modifier.clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(colors.accent.deepStart, colors.accent.deepEnd)))
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${cell.date.dayOfMonth}",
                    style = if (cell.isToday) AppTypography.label else AppTypography.listPreview,
                    color = when {
                        cell.isToday -> colors.accent.onDeep
                        !cell.inPeriod -> colors.text.tertiary.copy(alpha = 0.5f)
                        else -> colors.text.primary
                    },
                )
            }
            val decor = cell.decor
            if (cell.inPeriod && decor != null) {
                // R1 🟡-1：强调副行（节日 / 生日 / 纪念）落在陶土热度填充上时改深墨——accent.text × heat2 / heat3 实测
                // 4.06 / 3.53（浅）· 3.82（深 heat3）达不到正文 4.5:1，且这正是设计语言 §1.4「陶土填充上的文字一律深墨」
                // 的适用面（同日记月历「tint 上功能文字一律 primary」房规）。无填充格（含未来 / 邻月）仍用陶土功能深档。
                val emphasizedColor = if (cell.heatLevel > 0 && !cell.isFuture) colors.text.primary else colors.accent.text
                Text(
                    decor.subtitle,
                    style = AppTypography.caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    color = if (decor.emphasized) emphasizedColor else colors.text.tertiary,
                    fontWeight = if (decor.emphasized) FontWeight.Medium else null,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                )
            }
            Row(Modifier.padding(top = 2.dp).height(6.dp), horizontalArrangement = Arrangement.spacedBy(if (allMode) (-2).dp else 2.5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (allMode) {
                    cell.identity.forEach { idx ->
                        Box(Modifier.size(7.dp).clip(CircleShape).background(colors.ourDays.identity[idx]).border(1.5.dp, colors.surface.raised, CircleShape))
                    }
                    if (cell.moreIdentity) Box(Modifier.size(7.dp).clip(CircleShape).background(colors.surface.sunken).border(1.5.dp, colors.text.tertiary, CircleShape))
                } else {
                    cell.dots.forEach { Box(Modifier.size(4.5.dp).clip(CircleShape).background(dotColor(it))) }
                }
            }
        }
        val badge = cell.decor?.badge
        if (cell.inPeriod && badge != null) {
            Text(
                stringResource(if (badge == DayBadge.REST) R.string.our_days_badge_rest else R.string.our_days_badge_work),
                style = AppTypography.caption.copy(fontSize = 8.5.sp, lineHeight = 12.sp),
                color = if (badge == DayBadge.REST) colors.status.onSuccess else colors.status.onWarning,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (badge == DayBadge.REST) colors.status.successContainer else colors.status.warningContainer)
                    .padding(horizontal = 3.dp),
            )
        }
    }
}
