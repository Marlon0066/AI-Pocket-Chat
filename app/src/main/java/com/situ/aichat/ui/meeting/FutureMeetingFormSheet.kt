package com.situ.aichat.ui.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.meeting.MeetingDisplayFormatter
import com.situ.aichat.meeting.MeetingTimeResolver
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 未来约定见面表单（8c·1:1 iOS `OfflineManualMeetingSheet` + 日期/时间选择·Fable-5 换装）。
 * 同一表单两用，由 [showPlaceActivity] 区分：
 * - **手动约见面**（「+」菜单·[showPlaceActivity]=true）：日期 + 时段 + 地点 + 活动 → [onConfirm] 走 `coordinator.startManual`（跳确认闸门直 confirmed）。
 * - **改期**（确认卡「换个时间」·[showPlaceActivity]=false）：仅日期 + 时段 → [onConfirm] 走 `coordinator.rescheduleTo`（地点/活动留空、上游不动它们）。
 *
 * 时段精度对齐真理源模型：开「约个具体时间」= [MeetingTimeGranularity.EXACT]（用所选时刻）；
 * 关 = [MeetingTimeGranularity.DAY_ONLY]（补默认时段 [MeetingTimeResolver.DEFAULT_DAY_ONLY_HOUR] 点，与解析器同一口径）。
 *
 * **纯展示 + 本地状态**：不碰库；[onConfirm] 回调由调用方接 ViewModel→Coordinator。日期/时间选择复用 M3 原生
 * （DatePicker 走「UTC 当天 0 点」与本地日历互转·同 ScheduleFullDayScreen；TimePicker 包 AppDialog·同 StorySettings）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FutureMeetingFormSheet(
    title: String,
    confirmLabel: String,
    showPlaceActivity: Boolean,
    onConfirm: (scheduledAtMillis: Long, granularity: MeetingTimeGranularity, location: String, activity: String) -> Unit,
    onDismiss: () -> Unit,
    initialMillis: Long? = null,
    initialGranularity: MeetingTimeGranularity? = null,
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val zone = remember { ZoneId.systemDefault() }

    // 初值：改期带入原约定的日期/时段；手动默认今天 + 默认时段（exact，常见说法是带具体时间）。
    // 审计 B2（拍板 2026-07-02）：表单字段跨重建存活——填一半转屏/切深色不丢（重建后 saveable 恢复值优先于初值）。
    val initialDateTime = remember { initialMillis?.let { Instant.ofEpochMilli(it).atZone(zone) } }
    var selectedDate by rememberSaveable(stateSaver = LocalDateSaver) {
        mutableStateOf(initialDateTime?.toLocalDate() ?: LocalDate.now(zone))
    }
    var useExactTime by rememberSaveable {
        mutableStateOf(initialGranularity?.let { it == MeetingTimeGranularity.EXACT } ?: true)
    }
    var selectedTime by rememberSaveable(stateSaver = LocalTimeSaver) {
        mutableStateOf(
            initialDateTime?.toLocalTime()?.withSecond(0)?.withNano(0)
                ?: LocalTime.of(MeetingTimeResolver.DEFAULT_DAY_ONLY_HOUR, 0),
        )
    }
    var location by rememberSaveable { mutableStateOf("") }
    var activity by rememberSaveable { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // 落库时刻 / 精度：开关决定 exact（所选时刻）vs dayOnly（补默认时段）——与 MeetingTimeResolver 同一口径。
    val (scheduledAtMillis, granularity) = resolveFormSchedule(selectedDate, useExactTime, selectedTime, zone)
    val whenPreview = MeetingDisplayFormatter.whenDisplay(scheduledAtMillis, granularity, zone)
    // 手动需地点 + 活动俱全（同 iOS）；改期只看日期（恒有值），故 canConfirm 恒真。
    val canConfirm = !showPlaceActivity || (location.trim().isNotEmpty() && activity.trim().isNotEmpty())

    // skipPartiallyExpanded：表单较高（日期/时间/开关 + 两输入框 + 按钮），半展开档位会把「约定！」按钮压到屏幕外、
    // 上拉又回弹够不着——直接全展开，确保确认/取消按钮可达（T4 模拟器实测发现）。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = typography.titleSmall, color = colors.text.primary)

            // 选定时间预览（陶土色·与确认卡同视觉）。
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(18.dp))
                Text(whenPreview, style = typography.titleSmall, color = colors.accent.text)
            }

            // 日期行
            AppButton(
                onClick = { showDatePicker = true },
                style = AppButtonStyle.Tonal,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(dateLabel(selectedDate)) }

            // 「约个具体时间」开关 + 时间行
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("约个具体时间", style = typography.body, color = colors.text.primary)
                AppSwitch(
                    checked = useExactTime,
                    onCheckedChange = { useExactTime = it },
                )
            }
            if (useExactTime) {
                AppButton(
                    onClick = { showTimePicker = true },
                    style = AppButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("%02d:%02d".format(selectedTime.hour, selectedTime.minute))
                    }
                }
            }

            if (showPlaceActivity) {
                AppTextField(
                    value = location,
                    onValueChange = { location = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "地点",
                    placeholder = "星巴克、公园、家里…",
                )
                AppTextField(
                    value = activity,
                    onValueChange = { activity = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "活动",
                    placeholder = "喝咖啡、散步、看电影…",
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton(onClick = onDismiss, style = AppButtonStyle.Text, modifier = Modifier.weight(1f)) { Text("取消") }
                AppButton(
                    onClick = {
                        onConfirm(scheduledAtMillis, granularity, location.trim(), activity.trim())
                        onDismiss()
                    },
                    style = AppButtonStyle.Primary,
                    enabled = canConfirm,
                    modifier = Modifier.weight(1f),
                ) { Text(confirmLabel) }
            }
        }
    }

    if (showDatePicker) {
        FutureMeetingDatePickerDialog(
            selected = selectedDate,
            zone = zone,
            onConfirm = { selectedDate = it; showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showTimePicker) {
        FutureMeetingTimePickerDialog(
            initialHour = selectedTime.hour,
            initialMinute = selectedTime.minute,
            onConfirm = { h, m -> selectedTime = LocalTime.of(h, m); showTimePicker = false },
            onDismiss = { showTimePicker = false },
        )
    }
}

/**
 * 表单选择 → 落库时刻 + 精度（纯函数·可单测）。开「具体时间」= EXACT（用所选 [time]）；
 * 关 = DAY_ONLY（补 [MeetingTimeResolver.DEFAULT_DAY_ONLY_HOUR] 点，与解析器同口径）。毫秒按 [zone] 当地日历算。
 */
internal fun resolveFormSchedule(
    date: LocalDate,
    useExactTime: Boolean,
    time: LocalTime,
    zone: ZoneId,
): Pair<Long, MeetingTimeGranularity> {
    val effectiveTime = if (useExactTime) time else LocalTime.of(MeetingTimeResolver.DEFAULT_DAY_ONLY_HOUR, 0)
    val granularity = if (useExactTime) MeetingTimeGranularity.EXACT else MeetingTimeGranularity.DAY_ONLY
    val millis = date.atTime(effectiveTime).atZone(zone).toInstant().toEpochMilli()
    return millis to granularity
}

/** 日期标签「6月27日 周六」（dayOnly 口径·与确认卡 whenDisplay 同源，但只取日期段）。 */
private fun dateLabel(date: LocalDate): String {
    val zone = ZoneId.systemDefault()
    val millis = date.atTime(MeetingTimeResolver.DEFAULT_DAY_ONLY_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
    return MeetingDisplayFormatter.whenDisplay(millis, MeetingTimeGranularity.DAY_ONLY, zone)
}

/** 日期选择对话框。限「今天 ~ +365 天」（约定 horizon）。M3 用 UTC 当天 0 点毫秒，与本地日历互转（同 ScheduleFullDayScreen）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FutureMeetingDatePickerDialog(
    selected: LocalDate,
    zone: ZoneId,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = remember(zone) { LocalDate.now(zone) }
    val horizon = remember(today) { today.plusDays(MeetingTimeResolver.DEFAULT_HORIZON_DAYS) }
    fun localDateToUtc(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun utcToLocalDate(utc: Long): LocalDate = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()

    val state = rememberDatePickerState(
        initialSelectedDateMillis = localDateToUtc(selected),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcToLocalDate(utcTimeMillis) in today..horizon
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = DatePickerDefaults.colors(containerColor = AppTheme.colors.surface.raised),
        confirmButton = {
            AppButton(style = AppButtonStyle.Text, onClick = {
                state.selectedDateMillis?.let { onConfirm(utcToLocalDate(it)) }
            }) { Text("确定") }
        },
        dismissButton = { AppButton(style = AppButtonStyle.Text, onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = state)
    }
}

/** 时间选择对话框（24 小时制·AppDialog 包 M3 TimePicker·同 StorySettingsScreen）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FutureMeetingTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    AppDialog(
        onDismissRequest = onDismiss,
        title = "选择时间",
        confirmText = "确定",
        onConfirm = { onConfirm(state.hour, state.minute) },
        dismissText = "取消",
        onDismiss = onDismiss,
        content = { TimePicker(state = state) },
    )
}

/** 审计 B2：LocalDate/LocalTime 的 Bundle 存取（天数 / 当日秒数），供表单字段 rememberSaveable 用。 */
private val LocalDateSaver = Saver<LocalDate, Long>(save = { it.toEpochDay() }, restore = { LocalDate.ofEpochDay(it) })
private val LocalTimeSaver = Saver<LocalTime, Int>(save = { it.toSecondOfDay() }, restore = { LocalTime.ofSecondOfDay(it.toLong()) })
