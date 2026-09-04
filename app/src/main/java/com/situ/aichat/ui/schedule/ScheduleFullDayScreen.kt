package com.situ.aichat.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 全天行程视图（P14.2b，1:1 iOS `ScheduleFullDayView`）：日期头（上下日箭头 + 相对日文案 + 日历选择器）+
 * 左右滑切日 + 天气行（降级·P11）+ 全天事件时间线（过去日全部/今天已开始/未来日空）+ 两空态；
 * userInteraction 事件点击跳对应会话。**只读不生成**——无日程的日子只显空态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleFullDayScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    viewModel: ScheduleFullDayViewModel = hiltViewModel(),
) {
    val characterName by viewModel.characterName.collectAsStateWithLifecycle()
    val dayState by viewModel.dayState.collectAsStateWithLifecycle()
    val daySchedule by viewModel.daySchedule.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val relativeDay by viewModel.relativeDay.collectAsStateWithLifecycle()
    val canGoForward by viewModel.canGoForward.collectAsStateWithLifecycle()
    val bounds by viewModel.bounds.collectAsStateWithLifecycle()

    var showDatePicker by remember { mutableStateOf(false) }

    // userInteraction 行点击 → VM 反查会话 UUID → 一次性事件 → 导航。
    LaunchedEffect(Unit) {
        viewModel.openConversation.collect { onOpenChat(it) }
    }

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.schedule_full_day_title, characterName),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // 左右滑切日：detectHorizontalDragGestures 仅在水平 touch-slop 越过时认领手势（纵向 drag 由内层
                // verticalScroll 消费）——以安卓地道方式达成 iOS daySwitchGesture 的 abs(width)>abs(height) 意图；
                // 阈值 50dp≈iOS 50pt，越界由 VM shiftDay→canNavigateTo 守卫静默无效。
                .pointerInput(Unit) {
                    var totalDx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDx = 0f },
                        onDragEnd = {
                            val threshold = 50.dp.toPx()
                            when {
                                totalDx <= -threshold -> viewModel.shiftDay(1)   // 左滑 → 下一日
                                totalDx >= threshold -> viewModel.shiftDay(-1)   // 右滑 → 上一日
                            }
                        },
                        onHorizontalDrag = { _, dragAmount -> totalDx += dragAmount },
                    )
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                DateHeader(
                    selectedDate = selectedDate,
                    relativeDay = relativeDay,
                    canGoForward = canGoForward,
                    onPrev = { viewModel.shiftDay(-1) },
                    onNext = { viewModel.shiftDay(1) },
                    onPickDate = { showDatePicker = true },
                )

                WeatherRow(daySchedule)

                when (val state = dayState) {
                    ScheduleDayUiState.Loading -> Unit
                    ScheduleDayUiState.NoSchedule ->
                        ScheduleEmptyState(
                            icon = Icons.Filled.EventBusy,
                            text = stringResource(R.string.schedule_full_day_empty_no_schedule),
                        )
                    ScheduleDayUiState.NotStarted ->
                        ScheduleEmptyState(
                            icon = Icons.Filled.Schedule,
                            text = stringResource(R.string.schedule_full_day_empty_not_started),
                        )
                    is ScheduleDayUiState.Events ->
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(horizontal = 20.dp)
                                .padding(bottom = 24.dp),
                        ) {
                            state.rows.forEachIndexed { index, row ->
                                val event = row.event
                                val msgUuid = event.relatedMessageUUID
                                val clickable = event.eventTypeRaw == USER_INTERACTION_EVENT_TYPE &&
                                    !msgUuid.isNullOrEmpty()
                                val rowModifier = if (clickable) {
                                    // P1-18：动作标签（行内 clearAndSetSemantics 清不到祖先 clickable·标签存活）。
                                    Modifier.clickable(
                                        onClickLabel = stringResource(R.string.a11y_schedule_open_conversation),
                                    ) { viewModel.onUserInteractionClick(msgUuid) }
                                } else {
                                    Modifier
                                }
                                ScheduleEventRow(
                                    event = event,
                                    timeState = row.timeState,
                                    isLast = index == state.rows.lastIndex,
                                    modifier = rowModifier,
                                )
                            }
                        }
                }
            }
        }
    }

    if (showDatePicker) {
        ScheduleDatePickerDialog(
            selectedDate = selectedDate,
            earliest = bounds.earliest,
            latest = bounds.latest,
            onConfirm = { viewModel.setDate(it) },
            onDismiss = { showDatePicker = false },
        )
    }
}

@Composable
private fun DateHeader(
    selectedDate: Long,
    relativeDay: ScheduleRelDay,
    canGoForward: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPickDate: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 上一日：1:1 iOS 始终可点，越界由 shiftDay 守卫静默无效。
        IconButton(onClick = onPrev) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = stringResource(R.string.schedule_prev_day),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                scheduleDateHeaderText(selectedDate, relativeDay),
                style = MaterialTheme.typography.titleSmall,
                // P1-18：切日（箭头/滑动）后自动播报新日期（iOS 零 a11y=超越）。
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(onClick = onPickDate) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = stringResource(R.string.schedule_pick_date),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // 下一日：越界（已到 latest）禁用并淡化（1:1 iOS canNavigateForward）。
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.schedule_next_day),
                tint = if (canGoForward) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                },
            )
        }
    }
}

/** 日期头文案「M月d日（今天/昨天/明天/星期X）」（1:1 iOS formattedDate；M月d日 与星期按系统区域本地化）。 */
@Composable
private fun scheduleDateHeaderText(selectedDate: Long, relativeDay: ScheduleRelDay): String {
    val locale = Locale.getDefault()
    val date = remember(selectedDate) {
        Instant.ofEpochMilli(selectedDate).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val base = remember(selectedDate, locale) {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
        date.format(DateTimeFormatter.ofPattern(pattern, locale))
    }
    val weekday = remember(selectedDate, locale) {
        // 1:1 iOS：zh 用全称「星期一」(EEEE)，其余区域用缩写「Mon」(EEE)。
        val pattern = if (locale.language == "zh") "EEEE" else "EEE"
        date.format(DateTimeFormatter.ofPattern(pattern, locale))
    }
    val suffix = when (relativeDay) {
        ScheduleRelDay.TODAY -> stringResource(R.string.schedule_day_today)
        ScheduleRelDay.YESTERDAY -> stringResource(R.string.schedule_day_yesterday)
        ScheduleRelDay.TOMORROW -> stringResource(R.string.schedule_day_tomorrow)
        ScheduleRelDay.OTHER -> weekday
    }
    return stringResource(R.string.schedule_day_header_format, base, suffix)
}

/** 天气行（1:1 iOS weatherRow）：仅当日程有非空城市名才显。当前天气/城市列恒 null（P11）→ 不渲染。 */
@Composable
private fun WeatherRow(schedule: CharacterDailyScheduleEntity?) {
    val city = schedule?.cityName?.takeIf { it.isNotBlank() } ?: return
    val emoji = schedule.weatherEmoji ?: "🌡"
    val condition = schedule.weatherCondition ?: stringResource(R.string.schedule_weather_loading)
    val low = schedule.temperatureLow
    val high = schedule.temperatureHigh
    val tempRange = if (low != null && high != null) "${low.roundToInt()}°~${high.roundToInt()}°" else null

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        val style = MaterialTheme.typography.bodyMedium
        Text(emoji, style = style)
        Text(city, style = style, color = color)
        Text("·", style = style, color = color)
        Text(condition, style = style, color = color)
        if (tempRange != null) {
            Text("·", style = style, color = color)
            Text(tempRange, style = style, color = color)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDatePickerDialog(
    selectedDate: Long,
    earliest: Long,
    latest: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    // M3 DatePicker 用「UTC 当天 0 点」毫秒；与本地（设备时区）当天 0 点经 LocalDate 互转。
    fun deviceDayToUtc(dayStart: Long): Long =
        Instant.ofEpochMilli(dayStart).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun utcToDeviceDay(utc: Long): Long =
        Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    val state = rememberDatePickerState(
        initialSelectedDateMillis = deviceDayToUtc(selectedDate),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcToDeviceDay(utcTimeMillis) in earliest..latest
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = DatePickerDefaults.colors(containerColor = AppTheme.colors.surface.raised),
        confirmButton = {
            AppButton(style = AppButtonStyle.Text, onClick = {
                state.selectedDateMillis?.let { onConfirm(utcToDeviceDay(it)) }
                onDismiss()
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            AppButton(style = AppButtonStyle.Text, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun ScheduleEmptyState(icon: ImageVector, text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** iOS `ScheduleEvent.EventType.userInteraction` 的 rawValue（聊天/线下见面写回）。 */
private const val USER_INTERACTION_EVENT_TYPE = "userInteraction"
