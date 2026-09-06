package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.notification.CalendarReminderMode
import com.situ.aichat.notification.EconomyNotificationTier
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRadioRow
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.settings.formatMinuteOfDay
import com.situ.aichat.ui.settings.snapToHalfHour

/**
 * 通知页各组（琉璃·图纸 2026-09-06 卷四 A-7）。节序 / 条件 / 文案 / 回调逐字继承暖陶
 * `NotificationSettingsScreen`；`formatMinuteOfDay` / `snapToHalfHour` 直接 import 暖陶那两个
 * （§2.2-3 已把它们从 private 提为 internal·实现零改）。
 */

/**
 * 免打扰滑条范围：起点 20:00–23:30、终点 05:00–11:00，均 30min 步进（steps = 中间刻数）。
 *
 * **与暖陶 `NotificationSettingsScreen.kt` 的 `QUIET_*` 常量同值**——那边是 private 且 §2.2 只许把两个
 * 函数提为 internal，故此处自写一份。改任一侧必须同步另一侧（图纸 §11 D-11：A-7 写的 `0f..1439f, steps = 47`
 * 会把用户可设范围整整放宽一倍，那是行为变更不是换皮，故不采）。
 */
internal object LiuliQuietHours {
    const val START_MIN = 1200f // 20:00
    const val START_MAX = 1410f // 23:30
    const val START_STEPS = 6 // (1410-1200)/30-1
    const val END_MIN = 300f // 05:00
    const val END_MAX = 660f // 11:00
    const val END_STEPS = 11 // (660-300)/30-1
}

/** 权限卡（仅未授权时显示）：警告行 + 两枚钮（授予 / 前往系统设置）。 */
@Composable
internal fun ColumnScope.permissionGroup(onGrant: () -> Unit, onOpenSystemSettings: () -> Unit) {
    val colors = AppTheme.colors
    LiuliGroup {
        LiuliRowBase(divider = false, verticalPadding = LiuliPageGeometry.groupPadH, verticalAlignment = Alignment.Top) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.status.onError, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.notif_perm_denied),
                        style = AppTypography.bodyEmphasis,
                        color = colors.status.onError,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiuliButton(onClick = onGrant, style = LiuliButtonStyle.Prominent) {
                        Text(stringResource(R.string.notif_perm_grant_action))
                    }
                    LiuliButton(onClick = onOpenSystemSettings, style = LiuliButtonStyle.Text) {
                        Text(stringResource(R.string.notif_perm_settings_action))
                    }
                }
            }
        }
    }
}

/** 总开关组（无标题）。 */
@Composable
internal fun ColumnScope.globalSwitchGroup(enabled: Boolean, onChange: (Boolean) -> Unit) {
    LiuliGroup {
        LiuliToggleRow(
            title = stringResource(R.string.notif_global_label),
            subtitle = stringResource(R.string.notif_global_desc),
            checked = enabled,
            onCheckedChange = onChange,
            divider = false,
        )
    }
}

/**
 * 夜间免打扰组：开关行 + 开着时 range 文（组脚注位）+ 两条滑杆行。
 *
 * 滑杆走「拖动只改本地、松手才写库」（图纸 §3）：吸附仍是 `snapToHalfHour`（机制锁 §9 ④）。
 */
@Composable
internal fun ColumnScope.quietHoursGroup(
    enabled: Boolean,
    startMinute: Int,
    endMinute: Int,
    onSetEnabled: (Boolean) -> Unit,
    onSetStart: (Int) -> Unit,
    onSetEnd: (Int) -> Unit,
) {
    var liveStart by remember(startMinute) { mutableFloatStateOf(startMinute.toFloat()) }
    var liveEnd by remember(endMinute) { mutableFloatStateOf(endMinute.toFloat()) }
    LiuliGroup(
        header = stringResource(R.string.notif_quiet_hours_header),
        footer = if (enabled) {
            stringResource(
                R.string.notif_quiet_hours_range,
                formatMinuteOfDay(snapToHalfHour(liveStart)),
                formatMinuteOfDay(snapToHalfHour(liveEnd)),
            )
        } else {
            null
        },
    ) {
        LiuliToggleRow(
            title = stringResource(R.string.notif_quiet_hours_label),
            subtitle = stringResource(R.string.notif_quiet_hours_desc),
            checked = enabled,
            onCheckedChange = onSetEnabled,
            divider = false,
        )
        if (enabled) {
            LiuliSliderRow(
                title = stringResource(R.string.notif_quiet_hours_start_label, formatMinuteOfDay(snapToHalfHour(liveStart))),
                value = liveStart,
                onValueChange = { liveStart = it },
                onValueChangeFinished = { onSetStart(snapToHalfHour(liveStart)) },
                valueRange = LiuliQuietHours.START_MIN..LiuliQuietHours.START_MAX,
                steps = LiuliQuietHours.START_STEPS,
            )
            LiuliSliderRow(
                title = stringResource(R.string.notif_quiet_hours_end_label, formatMinuteOfDay(snapToHalfHour(liveEnd))),
                value = liveEnd,
                onValueChange = { liveEnd = it },
                onValueChangeFinished = { onSetEnd(snapToHalfHour(liveEnd)) },
                valueRange = LiuliQuietHours.END_MIN..LiuliQuietHours.END_MAX,
                steps = LiuliQuietHours.END_STEPS,
            )
        }
    }
}

/** 日历提醒方式：组标题 + intro（组脚注位）+ 三行单选。 */
@Composable
internal fun ColumnScope.calendarModeGroup(mode: CalendarReminderMode, onSelect: (CalendarReminderMode) -> Unit) {
    LiuliGroup(
        header = stringResource(R.string.notif_calendar_header),
        footer = stringResource(R.string.notif_calendar_intro),
    ) {
        LiuliRadioRow(
            title = stringResource(R.string.notif_calendar_both_label),
            subtitle = stringResource(R.string.notif_calendar_both_desc),
            selected = mode == CalendarReminderMode.BOTH,
            onSelect = { onSelect(CalendarReminderMode.BOTH) },
            divider = false,
        )
        LiuliRadioRow(
            title = stringResource(R.string.notif_calendar_system_label),
            subtitle = stringResource(R.string.notif_calendar_system_desc),
            selected = mode == CalendarReminderMode.SYSTEM,
            onSelect = { onSelect(CalendarReminderMode.SYSTEM) },
        )
        LiuliRadioRow(
            title = stringResource(R.string.notif_calendar_character_label),
            subtitle = stringResource(R.string.notif_calendar_character_desc),
            selected = mode == CalendarReminderMode.CHARACTER,
            onSelect = { onSelect(CalendarReminderMode.CHARACTER) },
        )
    }
}

/** 里程碑开关组（无标题·常显）。 */
@Composable
internal fun ColumnScope.milestoneGroup(enabled: Boolean, onChange: (Boolean) -> Unit) {
    LiuliGroup {
        LiuliToggleRow(
            title = stringResource(R.string.notif_milestone_setting_label),
            subtitle = stringResource(R.string.notif_milestone_setting_desc),
            checked = enabled,
            onCheckedChange = onChange,
            divider = false,
        )
    }
}

/** 角色经济三档（仅高级模式显示）。 */
@Composable
internal fun ColumnScope.economyGroup(tier: EconomyNotificationTier, onSelect: (EconomyNotificationTier) -> Unit) {
    LiuliGroup(
        header = stringResource(R.string.notif_economy_header),
        footer = stringResource(R.string.notif_economy_intro),
    ) {
        LiuliRadioRow(
            title = stringResource(R.string.notif_economy_detailed_label),
            subtitle = stringResource(R.string.notif_economy_detailed_desc),
            selected = tier == EconomyNotificationTier.DETAILED,
            onSelect = { onSelect(EconomyNotificationTier.DETAILED) },
            divider = false,
        )
        LiuliRadioRow(
            title = stringResource(R.string.notif_economy_brief_label),
            subtitle = stringResource(R.string.notif_economy_brief_desc),
            selected = tier == EconomyNotificationTier.BRIEF,
            onSelect = { onSelect(EconomyNotificationTier.BRIEF) },
        )
        LiuliRadioRow(
            title = stringResource(R.string.notif_economy_off_label),
            subtitle = stringResource(R.string.notif_economy_off_desc),
            selected = tier == EconomyNotificationTier.OFF,
            onSelect = { onSelect(EconomyNotificationTier.OFF) },
        )
    }
}

/** 每角色开关组：空 → 一句空态文；否则每角色一行（leading = 28 头像）。 */
@Composable
internal fun ColumnScope.perCharacterGroup(
    characters: List<CharacterEntity>,
    disabledIds: Set<String>,
    onSetEnabled: (String, Boolean) -> Unit,
) {
    LiuliGroup(header = stringResource(R.string.notif_per_character_header)) {
        if (characters.isEmpty()) {
            // 空态一句放在组内（行基线给 16 内距·别再套脚注件：它自带左 16 上 6 会叠成 32 / 22·复核 R1 🔵-4）。
            LiuliRowBase(divider = false, verticalPadding = EMPTY_PAD_V) {
                Text(
                    stringResource(R.string.notif_per_character_empty),
                    style = AppTypography.secondary.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = AppTheme.colors.text.tertiary,
                )
            }
        } else {
            characters.forEachIndexed { index, character ->
                LiuliToggleRow(
                    title = character.name,
                    checked = character.uuid !in disabledIds,
                    onCheckedChange = { onSetEnabled(character.uuid, it) },
                    leading = { CharacterAvatar(character.name, character.avatarPath, AVATAR) },
                    divider = index > 0,
                )
            }
        }
    }
}

/** 每角色行的头像直径（A-7）。 */
private val AVATAR = 28.dp
/** 每角色空态那一句的上下内距。 */
private val EMPTY_PAD_V = 12.dp
