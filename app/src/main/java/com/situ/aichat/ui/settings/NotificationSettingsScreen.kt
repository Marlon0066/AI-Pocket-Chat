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
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.notification.CalendarReminderMode
import com.situ.aichat.notification.EconomyNotificationTier
import com.situ.aichat.notification.NotificationPermission
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppRadio
import com.situ.aichat.ui.designsystem.AppSlider
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.work.BackgroundReliability
import kotlin.math.roundToInt

/**
 * 通知设置页（P6.1c-ii）。POST_NOTIFICATIONS 运行时授权 + 全局开关 + **夜间免打扰**（开关 + 起止双滑条）
 * + 日历提醒方式 + 里程碑 / 经济档位 + 每角色开关。
 *
 * 「文案生成方式」双选段已随双模式退役删除（主动通知真实感改造）：正文一律到点现做、失败走短句池兜底，
 * 无档可选。免打扰窗内到点的主动消息一律作废、不顺延补发。
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
    val economyTier by viewModel.economyTier.collectAsStateWithLifecycle()
    val milestoneEnabled by viewModel.milestoneEnabled.collectAsStateWithLifecycle()
    val advancedEnabled by viewModel.advancedModeEnabled.collectAsStateWithLifecycle()

    var hasPermission by remember { mutableStateOf(NotificationPermission.isGranted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = NotificationPermission.isGranted(context)
    }

    // 从系统设置返回时复查授权。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasPermission = NotificationPermission.isGranted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.notif_settings_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
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

            // 通知权限（仅 Android 13+ 未授权时显示）：无标题卡壳（§4.A2·卡内 padding 16），内容行零改。
            if (!hasPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp)
                        .appCardSurface()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.notif_perm_denied),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    AppButton(
                        onClick = { permissionLauncher.launch(NotificationPermission.PERMISSION) },
                        style = AppButtonStyle.Primary,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.notif_perm_grant_action))
                    }
                    AppButton(
                        onClick = { BackgroundReliability.openAppDetailsSettings(context) },
                        style = AppButtonStyle.Primary,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.notif_perm_settings_action))
                    }
                }
            }

            // 主动消息总开关（无独立分区标题·§11 D-A2 用无标题卡壳制式）
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                // 单行开关无标题卡壳（§4.0-6·appCardSurface 内 padding vertical 6）
                Column(Modifier.fillMaxWidth().appCardSurface().padding(vertical = 6.dp)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.notif_global_label),
                        subtitle = stringResource(R.string.notif_global_desc),
                        checked = globalEnabled,
                        onCheckedChange = { viewModel.setGlobalEnabled(it) },
                    )
                }
            }

            // 夜间免打扰（主动通知真实感改造 §4）：开关 + 起止双滑条（仅开时显示，平铺 if 同本页 advancedEnabled 惯例）
            SettingsSection(title = stringResource(R.string.notif_quiet_hours_header)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.notif_quiet_hours_label),
                    subtitle = stringResource(R.string.notif_quiet_hours_desc),
                    checked = quietHoursEnabled,
                    onCheckedChange = { viewModel.setQuietHoursEnabled(it) },
                )
                if (quietHoursEnabled) {
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
                        onValueChange = { viewModel.setQuietHoursStartMinute(snapToHalfHour(it)) },
                    )
                    QuietHoursSlider(
                        label = stringResource(R.string.notif_quiet_hours_end_label, formatMinuteOfDay(quietHoursEnd)),
                        value = quietHoursEnd.toFloat(),
                        valueRange = QUIET_END_MIN..QUIET_END_MAX,
                        steps = QUIET_END_STEPS,
                        onValueChange = { viewModel.setQuietHoursEndMinute(snapToHalfHour(it)) },
                    )
                }
            }

            // 日历提醒方式（P6.3，decision②）：分区 intro 进卡首行（卡内 padding 16）。
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

            // 关系里程碑庆祝通知（P1-33·安卓超越 iOS：iOS 自动评估零通知）。常显（无独立分区标题·§11 D-A2 无标题卡壳）。
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                Column(Modifier.fillMaxWidth().appCardSurface().padding(vertical = 6.dp)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.notif_milestone_setting_label),
                        subtitle = stringResource(R.string.notif_milestone_setting_desc),
                        checked = milestoneEnabled,
                        onCheckedChange = { viewModel.setMilestoneEnabled(it) },
                    )
                }
            }

            // 角色经济动态三档（P1-40·安卓超越 iOS：iOS 对发薪/房租/奖金零通知）。仅高级模式显示
            // （P1-24 gate·平铺 if 同 ProfileScreen 惯例）；ECONOMY 渠道静音，价值在留痕可回看。
            if (advancedEnabled) {
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
            }

            // 忙碌时延迟回复（P6.2）已整体删除（2026-07-11 用户拍板）。

            // 每角色开关
            SettingsSection(title = stringResource(R.string.notif_per_character_header)) {
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
                            onCheckedChange = { viewModel.setCharacterEnabled(character.uuid, it) },
                        )
                    }
                }
            }
        }
    }
}

// 免打扰滑条范围（图纸 §9 锁定）：起点 20:00–23:30、终点 05:00–11:00，均 30min 步进。
// steps = 中间刻数 = (max-min)/30 - 1。
private const val QUIET_STEP_MINUTES = 30
private const val QUIET_START_MIN = 1200f // 20:00
private const val QUIET_START_MAX = 1410f // 23:30
private const val QUIET_START_STEPS = 6 // (1410-1200)/30-1
private const val QUIET_END_MIN = 300f // 05:00
private const val QUIET_END_MAX = 660f // 11:00
private const val QUIET_END_STEPS = 11 // (660-300)/30-1

/** 当日分钟数 → "HH:mm"（两侧自拼，Locale 无关）。 */
private fun formatMinuteOfDay(minuteOfDay: Int): String {
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}

/** 吸附到 30min 整档（图纸 §4）。 */
private fun snapToHalfHour(value: Float): Int =
    (value.roundToInt() / QUIET_STEP_MINUTES) * QUIET_STEP_MINUTES

/** 免打扰起 / 止滑条行：上方一行当前值文本 + 滑条（制式同本页其他 bodySmall 说明行）。 */
@Composable
private fun QuietHoursSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AppRadio(selected = selected, onClick = onSelect)
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
