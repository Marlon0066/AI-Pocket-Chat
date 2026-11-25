package com.situ.aichat.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.notification.CalendarReminderMode
import com.situ.aichat.notification.EconomyNotificationTier
import com.situ.aichat.notification.NotificationPermission
import com.situ.aichat.ui.components.SettingsInfoNote
import com.situ.aichat.ui.components.SettingsMasterCard
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppRadio
import com.situ.aichat.ui.designsystem.AppSlider
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.work.BackgroundReliability
import kotlin.math.roundToInt

/**
 * 通知设置页（设置全面重构卷四 B-4 / B-5 / D-6）：
 * POST_NOTIFICATIONS 运行时授权 + 角色消息总控大卡（关掉时夜间免打扰/世界通知/每角色开关变灰示因）
 * + 日历提醒方式 + 角色经济动态（常显，不再受高级模式门控）+ 统一「其他通知」（6 类）+ 每角色开关 + 底部说明条。
 */
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val globalEnabled by viewModel.globalEnabled.collectAsStateWithLifecycle()
    val quietHoursEnabled by viewModel.quietHoursEnabled.collectAsStateWithLifecycle()
    val quietHoursStart by viewModel.quietHoursStartMinute.collectAsStateWithLifecycle()
    val quietHoursEnd by viewModel.quietHoursEndMinute.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val disabledIds by viewModel.disabledCharacterIds.collectAsStateWithLifecycle()
    val calendarMode by viewModel.calendarReminderMode.collectAsStateWithLifecycle()
    val worldTier by viewModel.worldNotificationTier.collectAsStateWithLifecycle()
    val economyTier by viewModel.economyTier.collectAsStateWithLifecycle()
    val milestoneEnabled by viewModel.milestoneEnabled.collectAsStateWithLifecycle()
    val momentNewPostEnabled by viewModel.momentNewPostEnabled.collectAsStateWithLifecycle()
    val momentInteractionEnabled by viewModel.momentInteractionEnabled.collectAsStateWithLifecycle()
    val storyFinishedEnabled by viewModel.storyFinishedEnabled.collectAsStateWithLifecycle()
    val meetingReminderEnabled by viewModel.meetingReminderEnabled.collectAsStateWithLifecycle()
    val redPacketExpiringEnabled by viewModel.redPacketExpiringEnabled.collectAsStateWithLifecycle()

    var hasPermission by remember { mutableStateOf(NotificationPermission.isGranted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = NotificationPermission.isGranted(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasPermission = NotificationPermission.isGranted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val disabledReason = if (!globalEnabled) stringResource(R.string.notif_disabled_reason) else null
    val scrollState = rememberScrollState()
    val lifted by remember { androidx.compose.runtime.derivedStateOf { scrollState.value > 0 } }
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.notif_settings_title),
                onBack = onBack,
                lifted = lifted,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.notif_settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            if (!hasPermission) {
                NotificationPermissionCard(
                    onGrant = { permissionLauncher.launch(NotificationPermission.PERMISSION) },
                    onOpenSettings = { BackgroundReliability.openAppDetailsSettings(context) },
                )
            }

            // 角色消息通知总开关大卡（B-5）
            SettingsMasterCard(
                title = stringResource(R.string.notif_character_msg_title),
                subtitle = stringResource(R.string.notif_character_msg_sub),
                icon = Icons.Filled.Notifications,
                checked = globalEnabled,
                onCheckedChange = { viewModel.setGlobalEnabled(it) },
            )

            // 夜间免打扰（受总开关联动变灰示因）
            SettingsSection(title = stringResource(R.string.notif_quiet_hours_header)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.notif_quiet_hours_label),
                    subtitle = stringResource(R.string.notif_quiet_hours_desc),
                    checked = quietHoursEnabled,
                    onCheckedChange = { viewModel.setQuietHoursEnabled(it) },
                    enabled = globalEnabled,
                    disabledReason = disabledReason,
                )
                if (quietHoursEnabled) {
                    Column(Modifier.alpha(if (globalEnabled) 1f else 0.48f)) {
                        Text(
                            stringResource(
                                R.string.notif_quiet_hours_range,
                                formatMinuteOfDay(quietHoursStart),
                                formatMinuteOfDay(quietHoursEnd),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        QuietHoursSlider(
                            label = stringResource(R.string.notif_quiet_hours_start_label, formatMinuteOfDay(quietHoursStart)),
                            value = quietHoursStart.toFloat(),
                            valueRange = QUIET_START_MIN..QUIET_START_MAX,
                            steps = QUIET_START_STEPS,
                            enabled = globalEnabled,
                            onValueChange = { viewModel.setQuietHoursStartMinute(snapToHalfHour(it)) },
                        )
                        QuietHoursSlider(
                            label = stringResource(R.string.notif_quiet_hours_end_label, formatMinuteOfDay(quietHoursEnd)),
                            value = quietHoursEnd.toFloat(),
                            valueRange = QUIET_END_MIN..QUIET_END_MAX,
                            steps = QUIET_END_STEPS,
                            enabled = globalEnabled,
                            onValueChange = { viewModel.setQuietHoursEndMinute(snapToHalfHour(it)) },
                        )
                    }
                }
            }

            // 世界通知（自世界设置收拢，受角色消息总开关控制）
            SettingsSection(
                title = stringResource(R.string.world_notification_section),
                footer = disabledReason ?: stringResource(R.string.world_notification_foot),
            ) {
                ModeOption(
                    selected = worldTier == AppSettings.WORLD_NOTIFICATION_SILENT,
                    title = stringResource(R.string.world_notification_silent_name),
                    description = stringResource(R.string.world_notification_silent_desc),
                    enabled = globalEnabled,
                    onSelect = { viewModel.setWorldNotificationTier(AppSettings.WORLD_NOTIFICATION_SILENT) },
                )
                ModeOption(
                    selected = worldTier == AppSettings.WORLD_NOTIFICATION_GENTLE,
                    title = "${stringResource(R.string.world_notification_gentle_name)} · ${stringResource(R.string.world_notification_default_tag)}",
                    description = stringResource(R.string.world_notification_gentle_desc),
                    enabled = globalEnabled,
                    onSelect = { viewModel.setWorldNotificationTier(AppSettings.WORLD_NOTIFICATION_GENTLE) },
                )
                ModeOption(
                    selected = worldTier == AppSettings.WORLD_NOTIFICATION_ALL,
                    title = stringResource(R.string.world_notification_all_name),
                    description = stringResource(R.string.world_notification_all_desc),
                    enabled = globalEnabled,
                    onSelect = { viewModel.setWorldNotificationTier(AppSettings.WORLD_NOTIFICATION_ALL) },
                )
            }

            // 日历提醒方式
            SettingsSection(title = stringResource(R.string.notif_calendar_header)) {
                Text(
                    stringResource(R.string.notif_calendar_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                ModeOption(
                    selected = calendarMode == CalendarReminderMode.BOTH,
                    title = stringResource(R.string.notif_calendar_both_label),
                    description = stringResource(R.string.notif_calendar_both_desc),
                    onSelect = { viewModel.setCalendarReminderMode(CalendarReminderMode.BOTH) },
                )
                ModeOption(
                    selected = calendarMode == CalendarReminderMode.SYSTEM,
                    title = stringResource(R.string.notif_calendar_system_label),
                    description = stringResource(R.string.notif_calendar_system_desc),
                    onSelect = { viewModel.setCalendarReminderMode(CalendarReminderMode.SYSTEM) },
                )
                ModeOption(
                    selected = calendarMode == CalendarReminderMode.CHARACTER,
                    title = stringResource(R.string.notif_calendar_character_label),
                    description = stringResource(R.string.notif_calendar_character_desc),
                    onSelect = { viewModel.setCalendarReminderMode(CalendarReminderMode.CHARACTER) },
                )
            }

            // 角色经济动态三档（D-6 常显，不再受高级模式隐藏）
            SettingsSection(title = stringResource(R.string.notif_economy_header)) {
                Text(
                    stringResource(R.string.notif_economy_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                ModeOption(
                    selected = economyTier == EconomyNotificationTier.DETAILED,
                    title = stringResource(R.string.notif_economy_detailed_label),
                    description = stringResource(R.string.notif_economy_detailed_desc),
                    onSelect = { viewModel.setEconomyTier(EconomyNotificationTier.DETAILED) },
                )
                ModeOption(
                    selected = economyTier == EconomyNotificationTier.BRIEF,
                    title = stringResource(R.string.notif_economy_brief_label),
                    description = stringResource(R.string.notif_economy_brief_desc),
                    onSelect = { viewModel.setEconomyTier(EconomyNotificationTier.BRIEF) },
                )
                ModeOption(
                    selected = economyTier == EconomyNotificationTier.OFF,
                    title = stringResource(R.string.notif_economy_off_label),
                    description = stringResource(R.string.notif_economy_off_desc),
                    onSelect = { viewModel.setEconomyTier(EconomyNotificationTier.OFF) },
                )
            }

            // 其他通知（统一收拢 6 类，含 B-4 新增四开关）
            SettingsSection(title = stringResource(R.string.notif_group_other)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.notif_milestone_setting_label),
                    subtitle = stringResource(R.string.notif_milestone_setting_desc),
                    checked = milestoneEnabled,
                    onCheckedChange = { viewModel.setMilestoneEnabled(it) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.moment_settings_new_post_notif),
                    subtitle = stringResource(R.string.moment_settings_new_post_notif_desc),
                    checked = momentNewPostEnabled,
                    onCheckedChange = { viewModel.setMomentNewPostEnabled(it) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.notif_moment_interaction_title),
                    subtitle = stringResource(R.string.notif_moment_interaction_sub),
                    checked = momentInteractionEnabled,
                    onCheckedChange = { viewModel.setMomentInteractionEnabled(it) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.notif_story_finished_title),
                    subtitle = stringResource(R.string.notif_story_finished_sub),
                    checked = storyFinishedEnabled,
                    onCheckedChange = { viewModel.setStoryFinishedEnabled(it) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.notif_meeting_reminder_title),
                    subtitle = stringResource(R.string.notif_meeting_reminder_sub),
                    checked = meetingReminderEnabled,
                    onCheckedChange = { viewModel.setMeetingReminderEnabled(it) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.notif_red_packet_expiring_title),
                    subtitle = stringResource(R.string.notif_red_packet_expiring_sub),
                    checked = redPacketExpiringEnabled,
                    onCheckedChange = { viewModel.setRedPacketExpiringEnabled(it) },
                )
            }

            // 每角色开关（受角色消息总开关联动变灰）
            SettingsSection(
                title = stringResource(R.string.notif_per_character_header),
                footer = disabledReason,
            ) {
                if (characters.isEmpty()) {
                    Text(
                        stringResource(R.string.notif_per_character_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    characters.forEach { character ->
                        val enabled = character.uuid !in disabledIds
                        SettingsSwitchRow(
                            title = character.name,
                            checked = enabled,
                            enabled = globalEnabled,
                            onCheckedChange = { viewModel.setCharacterEnabled(character.uuid, it) },
                        )
                    }
                }
            }

            // 底部说明：宠物与每本书的提醒入口说明
            SettingsInfoNote(
                text = stringResource(R.string.notif_where_else_info),
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun NotificationPermissionCard(
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp)
            .appCardSurface()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppActionIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.notif_perm_denied),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        AppButton(
            onClick = onGrant,
            style = AppButtonStyle.Primary,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.notif_perm_grant_action))
        }
        AppButton(
            onClick = onOpenSettings,
            style = AppButtonStyle.Primary,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.notif_perm_settings_action))
        }
    }
}

// 免打扰滑条范围（图纸 §9 锁定）：起点 20:00–23:30、终点 05:00–11:00，均 30min 步进。
private const val QUIET_STEP_MINUTES = 30
private const val QUIET_START_MIN = 1200f // 20:00
private const val QUIET_START_MAX = 1410f // 23:30
private const val QUIET_START_STEPS = 6 // (1410-1200)/30-1
private const val QUIET_END_MIN = 300f // 05:00
private const val QUIET_END_MAX = 660f // 11:00
private const val QUIET_END_STEPS = 11 // (660-300)/30-1

/** 当日分钟数 → "HH:mm"（两侧自拼，Locale 无关）。`internal` = 琉璃通知页复用同一份实现（卷四 A-7）。 */
internal fun formatMinuteOfDay(minuteOfDay: Int): String {
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}

/** 吸附到 30min 整档（图纸 §4）。`internal` = 琉璃通知页复用同一份实现（卷四 A-7）。 */
internal fun snapToHalfHour(value: Float): Int =
    (value.roundToInt() / QUIET_STEP_MINUTES) * QUIET_STEP_MINUTES

/** 免打扰起 / 止滑条行：上方一行当前值文本 + 滑条。 */
@Composable
private fun QuietHoursSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    AppSlider(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun ModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.48f)
            .selectable(selected = selected, enabled = enabled, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AppRadio(selected = selected, onClick = onSelect, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
