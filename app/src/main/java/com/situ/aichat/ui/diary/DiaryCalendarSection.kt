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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.DiaryEntryWithComments
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * 心情日历（日记重设计 R1·契约 §1.1 S2）：月度 7 列网格（周日起）+ 上/下月翻页 + **有日记的格子染当日
 * 心情莫兰迪浅档 tint + emoji 角标**（无心情=中性 sunken·色彩只承氛围、辨识靠 emoji 冗余）+ 今天/选中态 +
 * 本月心情分布条；下方列出选中日的日记卡片。从已加载的全部日记按日分组，无需按月再查（数据量小）。
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
    val today = remember { LocalDate.now(zone) }
    var displayedMonth by remember { mutableStateOf(YearMonth.now(zone)) }
    var selectedDate by remember { mutableStateOf(today) }

    val entriesByDay = remember(entries) {
        entries.groupBy { Instant.ofEpochMilli(it.entry.timestamp).atZone(zone).toLocalDate() }
    }
    // 每日代表心情 = 该日最新一条带心情的**用户**日记（entries 按时间降序；R4：TA 的信不抢格子染色）。
    val moodByDay = remember(entriesByDay) {
        entriesByDay.mapValues { (_, list) ->
            list.firstNotNullOfOrNull { ewc ->
                ewc.entry.takeIf { it.authorCharacterUuid == null }?.moodEmoji?.takeIf(String::isNotEmpty)
            }
        }
    }
    val selectedEntries = entriesByDay[selectedDate].orEmpty()

    // 周日起的 7 个星期标签 + 当月日期格（前导空白补 null）。
    val weekdayLabels = remember {
        val order = listOf(
            DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
        )
        order.map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }
    val weeks = remember(displayedMonth) {
        val firstDay = displayedMonth.atDay(1)
        val leadingBlanks = firstDay.dayOfWeek.value % 7 // 周日=0、周一=1…
        val cells = buildList<LocalDate?> {
            repeat(leadingBlanks) { add(null) }
            for (d in 1..displayedMonth.lengthOfMonth()) add(displayedMonth.atDay(d))
            while (size % 7 != 0) add(null)
        }
        cells.chunked(7)
    }
    val monthTitleMillis = remember(displayedMonth) {
        displayedMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    // 本月心情分布（emoji 计数·降序）。
    val monthMoodCounts = remember(moodByDay, displayedMonth) {
        moodByDay.entries
            .filter { YearMonth.from(it.key) == displayedMonth && it.value != null }
            .groupingBy { it.value!! }
            .eachCount()
            .entries.sortedByDescending { it.value }
    }

    // 屏 gutter 恒 20（设计语言 §2.5 军规）
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = AppSpacing.screenGutter, vertical = 16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { displayedMonth = displayedMonth.minusMonths(1L) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.a11y_diary_prev_month),
                        tint = AppTheme.colors.accent.text,
                    )
                }
                Text(
                    formatDiaryDate(monthTitleMillis, stringResource(R.string.diary_fmt_month_title), zone),
                    style = AppTheme.typography.nameTopBar,
                    color = AppTheme.colors.text.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { displayedMonth = displayedMonth.plusMonths(1L) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.a11y_diary_next_month),
                        tint = AppTheme.colors.accent.text,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                weekdayLabels.forEach { label ->
                    Text(
                        label,
                        style = AppTheme.typography.caption,
                        color = AppTheme.colors.text.secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                weeks.forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            DayCell(
                                modifier = Modifier.weight(1f),
                                date = date,
                                isSelected = date != null && date == selectedDate,
                                isToday = date != null && date == today,
                                hasEntry = date != null && entriesByDay.containsKey(date),
                                moodEmoji = date?.let { moodByDay[it] },
                                onClick = { date?.let { selectedDate = it } },
                            )
                        }
                    }
                }
            }
            if (monthMoodCounts.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                MonthMoodBar(monthMoodCounts)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                formatDiaryDate(
                    selectedDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                    stringResource(R.string.diary_fmt_day_title),
                    zone,
                ),
                style = AppTheme.typography.label,
                color = AppTheme.colors.text.primary,
            )
            Spacer(Modifier.height(8.dp))
            if (selectedEntries.isEmpty()) {
                Text(
                    stringResource(R.string.diary_no_entries_day),
                    style = AppTheme.typography.secondary,
                    color = AppTheme.colors.text.secondary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        items(selectedEntries, key = { it.entry.uuid }) { ewc ->
            // U3：活角色取活名，已删取快照名 + 「故友的信」淡标（§6.3 O1/O2）。
            val authorDisplay = diaryAuthorDisplay(
                ewc.entry.authorCharacterUuid,
                ewc.entry.authorNameSnapshot,
                ewc.entry.authorCharacterUuid?.let { charactersByUuid[it]?.name },
            )
            DiaryEntryCard(
                entry = ewc.entry,
                commentCount = ewc.comments.size,
                preview = true,
                reactionCount = ewc.reactions.size,
                onPublish = { onPublish(ewc.entry.uuid) },
                authorName = authorDisplay?.name,
                isOrphan = authorDisplay?.isOrphan == true,
                modifier = Modifier
                    .padding(vertical = 5.dp)
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

/** 本月心情分布：情绪原型堆叠条（全强度装饰色·无文字）+ emoji 计数小字（辨识冗余）。 */
@Composable
private fun MonthMoodBar(emojiCounts: List<Map.Entry<String, Int>>) {
    val colors = AppTheme.colors
    val toneCounts = DiaryMoodTone.entries.mapNotNull { tone ->
        val count = emojiCounts.filter { diaryMoodTone(it.key) == tone }.sumOf { it.value }
        if (count > 0) tone to count else null
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.diary_month_mood_header),
            style = AppTheme.typography.caption,
            color = colors.text.secondary,
        )
        Row(Modifier.fillMaxWidth().height(8.dp).clip(AppTheme.shapes.full)) {
            toneCounts.forEach { (tone, count) ->
                Box(
                    Modifier
                        .weight(count.toFloat())
                        .fillMaxSize()
                        .background(colors.emotion.toneColor(tone)),
                )
            }
        }
        Text(
            emojiCounts.take(4).joinToString(" · ") { "${it.key} ${it.value}" },
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
    hasEntry: Boolean,
    moodEmoji: String?,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    // P1-16 语义保持：选中态 + 有日记 stateDescription；空格子不挂 clickable（防 TalkBack 死停靠点）。
    val hasEntryLabel = stringResource(R.string.a11y_diary_day_has_entry)
    val selectedHasEntryLabel = stringResource(R.string.a11y_diary_day_selected_has_entry)
    Box(
        modifier = modifier
            .height(48.dp)
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
            val cellShape = RoundedCornerShape(10.dp)
            val fill = when {
                tint != null -> tint
                hasEntry -> colors.surface.sunken
                else -> null
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(cellShape)
                    .then(fill?.let { Modifier.background(it) } ?: Modifier)
                    .then(if (isSelected) Modifier.border(1.5.dp, colors.accent.primary, cellShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${date.dayOfMonth}",
                        // tint 上功能文字只许 primary（ColorContrastTest「diary.mood」网）；今天在无 tint 格用陶土功能深档标色。
                        style = if (isToday) AppTheme.typography.label else AppTheme.typography.secondary,
                        color = if (isToday && fill == null) colors.accent.text else colors.text.primary,
                    )
                    if (tint != null && moodEmoji != null) {
                        Text(moodEmoji, style = AppTheme.typography.caption)
                    }
                }
            }
        }
    }
}
