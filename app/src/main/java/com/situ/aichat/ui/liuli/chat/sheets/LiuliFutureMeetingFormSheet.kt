package com.situ.aichat.ui.liuli.chat.sheets

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.meeting.MeetingDisplayFormatter
import com.situ.aichat.meeting.MeetingTimeResolver
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliChip
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.LiuliSwitch
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.meeting.resolveFormSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 琉璃版「约个见面 / 换个时间」表单（图纸 2026-09-05 卷二C C6b · A-16 / A-14 · 照抄源 F26 后半
 * `ui/meeting/FutureMeetingFormSheet.kt:65-288`）。
 *
 * **只换渲染皮**：字段 saveable 存活（`LocalDate` / `LocalTime` 两个 Saver 重打同式）、落库时刻与精度
 * 走**同一个** internal 纯函数 [resolveFormSchedule]（直接 import 复用·不复制）、`whenPreview` 走
 * [MeetingDisplayFormatter].whenDisplay、`canConfirm` 门（改期恒真）、`skipPartiallyExpanded`（半展开会把
 * 确认钮压出屏幕·T4 实测过的），全部逐字照抄。
 *
 * 两枚选择对话框沿用 M3 [DatePicker] / [TimePicker] 机制——§9 ⑤ 对本文件放行的机制用法（A-14：
 * 日历 / 表盘属「行为重组件包壳不重写」，M3 清零 30 站有意保留项同一口径），外壳换成 [LiuliDialog]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiuliFutureMeetingFormSheet(
    title: String,
    confirmLabel: String,
    showPlaceActivity: Boolean,
    onConfirm: (scheduledAtMillis: Long, granularity: MeetingTimeGranularity, location: String, activity: String) -> Unit,
    onDismiss: () -> Unit,
    initialMillis: Long? = null,
    initialGranularity: MeetingTimeGranularity? = null,
) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val zone = remember { ZoneId.systemDefault() }

    val initialDateTime = remember { initialMillis?.let { Instant.ofEpochMilli(it).atZone(zone) } }
    var selectedDate by rememberSaveable(stateSaver = LiuliLocalDateSaver) {
        mutableStateOf(initialDateTime?.toLocalDate() ?: LocalDate.now(zone))
    }
    var useExactTime by rememberSaveable {
        mutableStateOf(initialGranularity?.let { it == MeetingTimeGranularity.EXACT } ?: true)
    }
    var selectedTime by rememberSaveable(stateSaver = LiuliLocalTimeSaver) {
        mutableStateOf(
            initialDateTime?.toLocalTime()?.withSecond(0)?.withNano(0)
                ?: LocalTime.of(MeetingTimeResolver.DEFAULT_DAY_ONLY_HOUR, 0),
        )
    }
    var location by rememberSaveable { mutableStateOf("") }
    var activity by rememberSaveable { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val (scheduledAtMillis, granularity) = resolveFormSchedule(selectedDate, useExactTime, selectedTime, zone)
    val whenPreview = MeetingDisplayFormatter.whenDisplay(scheduledAtMillis, granularity, zone)
    val canConfirm = !showPlaceActivity || (location.trim().isNotEmpty() && activity.trim().isNotEmpty())

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LiuliSheetShell(onDismissRequest = onDismiss, sheetState = sheetState, title = title) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(18.dp))
                Text(whenPreview, style = AppTypography.titleSmall, color = colors.accent.text)
            }

            // 日期 / 时间两枚「胶囊」= 满宽浅染 chip（对版稿 A 甲：表单里的选择行不是按钮，是可点的浅染条）。
            LiuliChip(
                selected = true,
                onClick = { showDatePicker = true },
                label = liuliDateLabel(selectedDate, zone),
                fillWidth = true,
                // 这两枚胶囊是「点开选择器」的按钮，不是选择组的一员——role 走 Button，
                // 否则读屏会报「单选按钮，已选中」（浅染只是它的长相）。
                role = Role.Button,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("约个具体时间", style = AppTypography.listPreview, color = onGlass.primary)
                LiuliSwitch(checked = useExactTime, onCheckedChange = { useExactTime = it })
            }
            if (useExactTime) {
                LiuliChip(
                    selected = true,
                    onClick = { showTimePicker = true },
                    label = "%02d:%02d".format(selectedTime.hour, selectedTime.minute),
                    fillWidth = true,
                    role = Role.Button,
                    leading = { Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
            }

            if (showPlaceActivity) {
                LiuliField(
                    value = location,
                    onValueChange = { location = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "地点",
                    placeholder = "星巴克、公园、家里…",
                )
                LiuliField(
                    value = activity,
                    onValueChange = { activity = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "活动",
                    placeholder = "喝咖啡、散步、看电影…",
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiuliButton(onClick = onDismiss, style = LiuliButtonStyle.Text, modifier = Modifier.weight(1f)) { Text("取消") }
                LiuliButton(
                    onClick = {
                        onConfirm(scheduledAtMillis, granularity, location.trim(), activity.trim())
                        onDismiss()
                    },
                    style = LiuliButtonStyle.Prominent,
                    enabled = canConfirm,
                    modifier = Modifier.weight(1f),
                ) { Text(confirmLabel) }
            }
        }
    }

    if (showDatePicker) {
        LiuliDatePickerDialog(
            selected = selectedDate,
            zone = zone,
            onConfirm = { selectedDate = it; showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = selectedTime.hour, initialMinute = selectedTime.minute, is24Hour = true)
        LiuliDialog(
            onDismissRequest = { showTimePicker = false },
            title = "选择时间",
            confirmText = "确定",
            onConfirm = { selectedTime = LocalTime.of(state.hour, state.minute); showTimePicker = false },
            dismissText = "取消",
            onDismiss = { showTimePicker = false },
            content = { TimePicker(state = state) },
        )
    }
}

/**
 * 日期标签「6月27日 周六」（dayOnly 口径·与确认卡 whenDisplay 同源，但只取日期段）。
 * 暖陶 `dateLabel` 是 private → 重打同式（两侧注释互指·同卷二B `M:SS` 判例）。
 */
private fun liuliDateLabel(date: LocalDate, zone: ZoneId): String {
    val millis = date.atTime(MeetingTimeResolver.DEFAULT_DAY_ONLY_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
    return MeetingDisplayFormatter.whenDisplay(millis, MeetingTimeGranularity.DAY_ONLY, zone)
}

/** 日期选择对话框：限「今天 ~ +DEFAULT_HORIZON_DAYS」，M3 用 UTC 当天 0 点毫秒与本地日历互转（照抄 F26）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiuliDatePickerDialog(
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
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcToLocalDate(utcTimeMillis) in today..horizon
        },
    )
    LiuliDialog(
        onDismissRequest = onDismiss,
        title = null,
        confirmText = "确定",
        onConfirm = { state.selectedDateMillis?.let { onConfirm(utcToLocalDate(it)) } },
        dismissText = "取消",
        onDismiss = onDismiss,
        content = {
            // 日历本体的 M3 默认容器色是一块灰底，压在玻璃卡上像贴了张纸——透明化让玻璃透上来
            // （`DatePickerDefaults` 是被放行的 `DatePicker` 的配色入口·§9 ⑤ 机制放行同一处）。
            DatePicker(
                state = state,
                title = null,
                headline = null,
                showModeToggle = false,
                colors = DatePickerDefaults.colors(containerColor = Color.Transparent),
            )
        },
    )
}

/** 表单字段的 Bundle 存取（天数 / 当日秒数）——暖陶两个 Saver 是 private → 重打同式（照抄 F26 末）。 */
private val LiuliLocalDateSaver = Saver<LocalDate, Long>(save = { it.toEpochDay() }, restore = { LocalDate.ofEpochDay(it) })
private val LiuliLocalTimeSaver = Saver<LocalTime, Int>(save = { it.toSecondOfDay() }, restore = { LocalTime.ofSecondOfDay(it.toLong()) })
