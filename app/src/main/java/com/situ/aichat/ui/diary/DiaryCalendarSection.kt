package com.situ.aichat.ui.diary

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.DiaryEntryWithComments
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppIconButton
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * 心情日历（方向 C 升级）：卡片承托的疏朗月度 7 列网格（52dp 行高 · 13dp 圆角格 · 多篇角点标）+
 * 分段式情绪气候光谱条；下方列出选中日的日记卡片。两张脸共用 [DiaryCalendarMonth]，支持外部传入承托卡材质。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiaryCalendarSection(
    entries: List<DiaryEntryWithComments>,
    charactersByUuid: Map<String, com.situ.aichat.data.local.entity.CharacterEntity> = emptyMap(),
    onOpenEntry: (String) -> Unit,
    onPublish: (String) -> Unit = {},
    onLongPress: (String) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val calendar = rememberDiaryCalendarState(zone)
    val data = remember(entries) { diaryCalendarData(entries, zone) }
    val selectedEntries = data.entriesByDay[calendar.selectedDate].orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppSpacing.screenGutter,
            end = AppSpacing.screenGutter,
            top = 12.dp,
            bottom = 88.dp,
        ),
    ) {
        item { DiaryCalendarMonth(calendar, data, zone) }
        items(selectedEntries, key = { it.entry.uuid }) { ewc ->
            val authorDisplay = diaryAuthorDisplayOf(ewc.entry, charactersByUuid)
            DiaryEntryCard(
                entry = ewc.entry,
                commentCount = ewc.comments.size,
                preview = true,
                reactionCount = ewc.reactions.size,
                onPublish = { onPublish(ewc.entry.uuid) },
                authorName = authorDisplay?.name,
                isOrphan = authorDisplay?.isOrphan == true,
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .combinedClickable(
                        onClickLabel = stringResource(R.string.a11y_diary_open),
                        onClick = { onOpenEntry(ewc.entry.uuid) },
                        onLongClickLabel = stringResource(R.string.a11y_diary_delete),
                        onLongClick = { onLongPress(ewc.entry.uuid) },
                    ),
            )
        }
    }
}

/**
 * 心情日历的月历块（两张脸共用）：承托卡内包含翻月标题 + 星期行 + 52dp 疏朗日期格 + 分段式情绪光谱条；
 * 卡外下方呈现选中日标题 /「这天没有日记」。
 */
@Composable
internal fun DiaryCalendarMonth(
    state: DiaryCalendarState,
    data: DiaryCalendarData,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    cardSurface: Modifier = Modifier.appCardSurface(cornerRadius = 20.dp),
) {
    val weekdayLabels = remember {
        val order = listOf(
            DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
        )
        order.map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }
    val weeks = remember(state.displayedMonth) { diaryCalendarWeeks(state.displayedMonth) }
    val monthTitleMillis = remember(state.displayedMonth) { diaryMonthStartMillis(state.displayedMonth, zone) }
    val monthMoodCounts = remember(data.moodByDay, state.displayedMonth) { diaryMonthMoodCounts(data.moodByDay, state.displayedMonth) }

    Column(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(cardSurface)
                .padding(horizontal = 14.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(
                    icon = AppActionIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.a11y_diary_prev_month),
                    onClick = { state.displayedMonth = state.displayedMonth.minusMonths(1L) },
                    tint = AppTheme.colors.accent.text,
                )
                Text(
                    formatDiaryDate(monthTitleMillis, stringResource(R.string.diary_fmt_month_title), zone),
                    style = AppTheme.typography.nameTopBar,
                    color = AppTheme.colors.text.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                AppIconButton(
                    icon = AppActionIcons.ChevronRight,
                    contentDescription = stringResource(R.string.a11y_diary_next_month),
                    onClick = { state.displayedMonth = state.displayedMonth.plusMonths(1L) },
                    tint = AppTheme.colors.accent.text,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                weekdayLabels.forEach { label ->
                    Text(
                        label,
                        style = AppTheme.typography.caption,
                        color = AppTheme.colors.text.tertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                weeks.forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            val count = date?.let { data.entriesByDay[it]?.size } ?: 0
                            DayCell(
                                modifier = Modifier.weight(1f),
                                date = date,
                                isSelected = date != null && date == state.selectedDate,
                                isToday = date != null && date == state.today,
                                entryCount = count,
                                moodEmoji = date?.let { data.moodByDay[it] },
                                onClick = { date?.let { state.selectedDate = it } },
                            )
                        }
                    }
                }
            }
            if (monthMoodCounts.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                DiaryDottedDivider()
                Spacer(Modifier.height(12.dp))
                MonthMoodBar(monthMoodCounts)
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            formatDiaryDate(
                state.selectedDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                stringResource(R.string.diary_fmt_day_title),
                zone,
            ),
            style = AppTheme.typography.label,
            color = AppTheme.colors.text.primary,
        )
        Spacer(Modifier.height(8.dp))
        if (data.entriesByDay[state.selectedDate].orEmpty().isEmpty()) {
            Text(
                stringResource(R.string.diary_no_entries_day),
                style = AppTheme.typography.secondary,
                color = AppTheme.colors.text.secondary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 本月情绪气候光谱：分段圆角胶囊条（2dp 呼吸间隙）+ emoji 计数图例。 */
@Composable
private fun MonthMoodBar(emojiCounts: List<Map.Entry<String, Int>>) {
    val colors = AppTheme.colors
    val toneCounts = DiaryMoodTone.entries.mapNotNull { tone ->
        val count = emojiCounts.filter { diaryMoodTone(it.key) == tone }.sumOf { it.value }
        if (count > 0) tone to count else null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.diary_month_mood_header),
            style = AppTheme.typography.caption,
            color = colors.text.secondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(9.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            toneCounts.forEach { (tone, count) ->
                Box(
                    Modifier
                        .weight(count.toFloat())
                        .fillMaxSize()
                        .clip(AppTheme.shapes.full)
                        .background(colors.emotion.toneColor(tone)),
                )
            }
        }
        Text(
            emojiCounts.take(5).joinToString(" · ") { "${it.key} ${it.value}" },
            style = AppTheme.typography.captionNumeric,
            color = colors.text.secondary,
        )
    }
}

@Composable
private fun DayCell(
    modifier: Modifier,
    date: LocalDate?,
    isSelected: Boolean,
    isToday: Boolean,
    entryCount: Int,
    moodEmoji: String?,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val hasEntry = entryCount > 0
    val hasEntryLabel = stringResource(R.string.a11y_diary_day_has_entry)
    val selectedHasEntryLabel = stringResource(R.string.a11y_diary_day_selected_has_entry)
    Box(
        modifier = modifier
            .height(52.dp)
            .then(if (date != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics {
                if (date != null) {
                    selected = isSelected
                    if (hasEntry) {
                        stateDescription = if (isSelected) selectedHasEntryLabel else hasEntryLabel
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (date != null) {
            val tint = diaryMoodTint(moodEmoji)
            val cellShape = RoundedCornerShape(13.dp)
            val fill = when {
                tint != null -> tint
                hasEntry -> colors.surface.sunken
                else -> null
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(cellShape)
                    .then(fill?.let { Modifier.background(it) } ?: Modifier)
                    .then(if (isSelected) Modifier.border(1.5.dp, colors.accent.primary, cellShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (entryCount > 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(colors.accent.primary),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${date.dayOfMonth}",
                        // tint 上功能文字只许 primary（ColorContrastTest「diary.mood」网）；今天在无 tint 格用陶土功能深档标色。
                        style = (if (isToday) AppTheme.typography.label else AppTheme.typography.secondary).copy(
                            fontFeatureSettings = "tnum",
                            fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (isToday && fill == null) colors.accent.text else colors.text.primary,
                    )
                    if (tint != null && moodEmoji != null) {
                        Text(moodEmoji, fontSize = 11.sp, lineHeight = 13.sp)
                    }
                }
            }
        }
    }
}
