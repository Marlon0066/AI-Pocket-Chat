package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
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
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.notification.CalendarReminderMode
import com.situ.aichat.notification.EconomyNotificationTier
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliTileTone
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliMasterCard
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRadioRow
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.settings.formatMinuteOfDay
import com.situ.aichat.ui.settings.snapToHalfHour

/**
 * 通知页各组（琉璃·设置全面重构卷四 B-4 / B-5 / D-6）。
 * 节序 / 条件 / 文案 / 回调与暖陶 `NotificationSettingsScreen` 逐项对齐。
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
                    Icon(AppActionIcons.Warning, contentDescription = null, tint = colors.status.onError, modifier = Modifier.size(20.dp))
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

/** 角色消息通知总开关大卡（B-5）。 */
@Composable
internal fun ColumnScope.globalSwitchGroup(enabled: Boolean, onChange: (Boolean) -> Unit) {
    LiuliMasterCard(
        title = stringResource(R.string.notif_character_msg_title),
        subtitle = stringResource(R.string.notif_character_msg_sub),
        icon = Icons.Filled.Notifications,
        tileTone = LiuliTileTone.Rose,
        checked = enabled,
        onCheckedChange = onChange,
    )
}

/**
 * 夜间免打扰组：开关行 + 开着时 range 文（组脚注位）+ 两条滑杆行。总开关关闭时整组变灰示因。
 */
@Composable
internal fun ColumnScope.quietHoursGroup(
    enabled: Boolean,
    controlsEnabled: Boolean,
    disabledReason: String?,
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
            enabled = controlsEnabled,
            disabledReason = disabledReason,
            onCheckedChange = onSetEnabled,
            divider = false,
        )
        if (enabled) {
            LiuliSliderRow(
                title = stringResource(R.string.notif_quiet_hours_start_label, formatMinuteOfDay(snapToHalfHour(liveStart))),
                value = liveStart,
                enabled = controlsEnabled,
                onValueChange = { liveStart = it },
                onValueChangeFinished = { onSetStart(snapToHalfHour(liveStart)) },
                valueRange = LiuliQuietHours.START_MIN..LiuliQuietHours.START_MAX,
                steps = LiuliQuietHours.START_STEPS,
            )
            LiuliSliderRow(
                title = stringResource(R.string.notif_quiet_hours_end_label, formatMinuteOfDay(snapToHalfHour(liveEnd))),
                value = liveEnd,
                enabled = controlsEnabled,
                onValueChange = { liveEnd = it },
                onValueChangeFinished = { onSetEnd(snapToHalfHour(liveEnd)) },
                valueRange = LiuliQuietHours.END_MIN..LiuliQuietHours.END_MAX,
                steps = LiuliQuietHours.END_STEPS,
            )
        }
    }
}

/** 世界通知三档（自世界设置收拢，受角色消息总开关控制）。 */
@Composable
internal fun ColumnScope.worldNotificationGroup(
    tier: String,
    enabled: Boolean,
    disabledReason: String?,
    onSelect: (String) -> Unit,
) {
    LiuliGroup(
        header = stringResource(R.string.world_notification_section),
        footer = disabledReason ?: stringResource(R.string.world_notification_foot),
    ) {
        LiuliRadioRow(
            title = stringResource(R.string.world_notification_silent_name),
            subtitle = stringResource(R.string.world_notification_silent_desc),
            selected = tier == AppSettings.WORLD_NOTIFICATION_SILENT,
            enabled = enabled,
            onSelect = { onSelect(AppSettings.WORLD_NOTIFICATION_SILENT) },
            divider = false,
        )
        LiuliRadioRow(
            title = "${stringResource(R.string.world_notification_gentle_name)} · ${stringResource(R.string.world_notification_default_tag)}",
            subtitle = stringResource(R.string.world_notification_gentle_desc),
            selected = tier == AppSettings.WORLD_NOTIFICATION_GENTLE,
            enabled = enabled,
            onSelect = { onSelect(AppSettings.WORLD_NOTIFICATION_GENTLE) },
        )
        LiuliRadioRow(
            title = stringResource(R.string.world_notification_all_name),
            subtitle = stringResource(R.string.world_notification_all_desc),
            selected = tier == AppSettings.WORLD_NOTIFICATION_ALL,
            enabled = enabled,
            onSelect = { onSelect(AppSettings.WORLD_NOTIFICATION_ALL) },
        )
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

/** 角色经济三档（D-6 常显，不再受高级模式门控）。 */
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

/** 其他通知（统一收拢 6 类，含 B-4 四开关）。 */
@Composable
internal fun ColumnScope.otherNotificationsGroup(
    state: LiuliOtherNotificationsState,
    onSetMilestoneEnabled: (Boolean) -> Unit,
    onSetMomentNewPostEnabled: (Boolean) -> Unit,
    onSetMomentInteractionEnabled: (Boolean) -> Unit,
    onSetStoryFinishedEnabled: (Boolean) -> Unit,
    onSetMeetingReminderEnabled: (Boolean) -> Unit,
    onSetRedPacketExpiringEnabled: (Boolean) -> Unit,
) {
    LiuliGroup(header = stringResource(R.string.notif_group_other)) {
        LiuliToggleRow(
            title = stringResource(R.string.notif_milestone_setting_label),
            subtitle = stringResource(R.string.notif_milestone_setting_desc),
            checked = state.milestoneEnabled,
            onCheckedChange = onSetMilestoneEnabled,
            divider = false,
        )
        LiuliToggleRow(
            title = stringResource(R.string.moment_settings_new_post_notif),
            subtitle = stringResource(R.string.moment_settings_new_post_notif_desc),
            checked = state.momentNewPostEnabled,
            onCheckedChange = onSetMomentNewPostEnabled,
        )
        LiuliToggleRow(
            title = stringResource(R.string.notif_moment_interaction_title),
            subtitle = stringResource(R.string.notif_moment_interaction_sub),
            checked = state.momentInteractionEnabled,
            onCheckedChange = onSetMomentInteractionEnabled,
        )
        LiuliToggleRow(
            title = stringResource(R.string.notif_story_finished_title),
            subtitle = stringResource(R.string.notif_story_finished_sub),
            checked = state.storyFinishedEnabled,
            onCheckedChange = onSetStoryFinishedEnabled,
        )
        LiuliToggleRow(
            title = stringResource(R.string.notif_meeting_reminder_title),
            subtitle = stringResource(R.string.notif_meeting_reminder_sub),
            checked = state.meetingReminderEnabled,
            onCheckedChange = onSetMeetingReminderEnabled,
        )
        LiuliToggleRow(
            title = stringResource(R.string.notif_red_packet_expiring_title),
            subtitle = stringResource(R.string.notif_red_packet_expiring_sub),
            checked = state.redPacketExpiringEnabled,
            onCheckedChange = onSetRedPacketExpiringEnabled,
        )
    }
}

/** 每角色开关组：空 → 一句空态文；否则每角色一行（leading = 28 头像），总开关关闭时整组变灰。 */
@Composable
internal fun ColumnScope.perCharacterGroup(
    characters: List<CharacterEntity>,
    disabledIds: Set<String>,
    controlsEnabled: Boolean,
    disabledReason: String?,
    onSetEnabled: (String, Boolean) -> Unit,
) {
    LiuliGroup(
        header = stringResource(R.string.notif_per_character_header),
        footer = disabledReason,
    ) {
        if (characters.isEmpty()) {
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
                    enabled = controlsEnabled,
                    onCheckedChange = { onSetEnabled(character.uuid, it) },
                    leading = { CharacterAvatar(character.name, character.avatarPath, AVATAR) },
                    divider = index > 0,
                )
            }
        }
    }
}

/** 每角色行的头像直径。 */
private val AVATAR = 28.dp
/** 每角色空态那一句的上下内距。 */
private val EMPTY_PAD_V = 12.dp
