package com.situ.aichat.ui.schedule

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppIconButton
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.grainSurface
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 全天行程视图（暖陶外衣）：日期头 + 左右滑切日 + 天气行 + 全天事件时间线 + 两空态。
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
    LaunchedEffect(Unit) { viewModel.openConversation.collect { onOpenChat(it) } }

    val scrollState = rememberScrollState()
    val colors = AppTheme.colors
    Scaffold(
        containerColor = colors.surface.base,
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
                .background(colors.surface.base)
                .grainSurface()
                .pointerInput(Unit) {
                    var totalDx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDx = 0f },
                        onDragEnd = {
                            val threshold = 50.dp.toPx()
                            when {
                                totalDx <= -threshold -> viewModel.shiftDay(1)
                                totalDx >= threshold -> viewModel.shiftDay(-1)
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

                ScheduleWeatherRow(daySchedule)

                when (val state = dayState) {
                    ScheduleDayUiState.Loading -> Unit
                    ScheduleDayUiState.NoSchedule ->
                        ScheduleEmptyState(Icons.Filled.EventBusy, stringResource(R.string.schedule_full_day_empty_no_schedule))
                    ScheduleDayUiState.NotStarted ->
                        ScheduleEmptyState(AppActionIcons.Clock, stringResource(R.string.schedule_full_day_empty_not_started))
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
                                val clickable = event.eventTypeRaw == SCHEDULE_USER_INTERACTION_TYPE && !msgUuid.isNullOrEmpty()
                                val rowModifier = if (clickable) {
                                    Modifier.clickable(onClickLabel = stringResource(R.string.a11y_schedule_open_conversation)) {
                                        viewModel.onUserInteractionClick(msgUuid)
                                    }
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
    val colors = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconButton(
            icon = AppActionIcons.ChevronLeft,
            contentDescription = stringResource(R.string.schedule_prev_day),
            onClick = onPrev,
            tint = colors.text.secondary,
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                scheduleDateHeaderText(selectedDate, relativeDay),
                style = AppTypography.label,
                color = colors.text.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            AppIconButton(
                icon = AppActionIcons.Calendar,
                contentDescription = stringResource(R.string.schedule_pick_date),
                onClick = onPickDate,
                tint = colors.accent.text,
                iconSize = 20.dp,
            )
        }
        AppIconButton(
            icon = AppActionIcons.ChevronRight,
            contentDescription = stringResource(R.string.schedule_next_day),
            onClick = onNext,
            enabled = canGoForward,
            tint = colors.text.secondary,
        )
    }
}

@Composable
internal fun scheduleDateHeaderText(selectedDate: Long, relativeDay: ScheduleRelDay): String {
    val locale = Locale.getDefault()
    val date = remember(selectedDate) {
        Instant.ofEpochMilli(selectedDate).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val base = remember(selectedDate, locale) {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
        date.format(DateTimeFormatter.ofPattern(pattern, locale))
    }
    val weekday = remember(selectedDate, locale) {
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

@Composable
internal fun ScheduleWeatherRow(schedule: CharacterDailyScheduleEntity?) {
    val city = schedule?.cityName?.takeIf { it.isNotBlank() } ?: return
    val emoji = schedule.weatherEmoji ?: "🌡"
    val condition = schedule.weatherCondition ?: stringResource(R.string.schedule_weather_loading)
    val low = schedule.temperatureLow
    val high = schedule.temperatureHigh
    val tempRange = if (low != null && high != null) "${low.roundToInt()}°~${high.roundToInt()}°" else null
    val color = AppTheme.colors.text.secondary
    val style = AppTypography.secondary

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
internal fun ScheduleDatePickerDialog(
    selectedDate: Long,
    earliest: Long,
    latest: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
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
internal fun ScheduleEmptyState(icon: ImageVector, text: String) {
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(colors.accent.container), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp), tint = colors.accent.text)
        }
        Text(text, style = AppTypography.secondary, color = colors.text.secondary)
    }
}

internal const val SCHEDULE_USER_INTERACTION_TYPE = "userInteraction"
