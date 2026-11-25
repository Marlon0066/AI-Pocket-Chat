package com.situ.aichat.ui.liuli.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.notification.CalendarReminderMode
import com.situ.aichat.notification.EconomyNotificationTier
import com.situ.aichat.notification.NotificationPermission
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.page.LiuliInfoNote
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.NotificationSettingsViewModel
import com.situ.aichat.work.BackgroundReliability

/** 页底留白（同设置主页）。 */
private val PAGE_BOTTOM = 24.dp

/** 「其他通知」6 项开关聚合态，避免纯参数内容层签名过长。 */
@Immutable
internal data class LiuliOtherNotificationsState(
    val milestoneEnabled: Boolean = true,
    val momentNewPostEnabled: Boolean = true,
    val momentInteractionEnabled: Boolean = true,
    val storyFinishedEnabled: Boolean = true,
    val meetingReminderEnabled: Boolean = true,
    val redPacketExpiringEnabled: Boolean = true,
)

/**
 * 通知设置页（琉璃·设置全面重构卷四 B-4 / B-5 / D-6）。与暖陶 `NotificationSettingsScreen` 共用
 * [NotificationSettingsViewModel]；权限流（launcher + `ON_RESUME` 复查）逐字对齐。
 */
@Composable
fun LiuliNotificationScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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

    LiuliNotificationContent(
        hasPermission = hasPermission,
        globalEnabled = globalEnabled,
        quietHoursEnabled = quietHoursEnabled,
        quietHoursStart = quietHoursStart,
        quietHoursEnd = quietHoursEnd,
        worldTier = worldTier,
        calendarMode = calendarMode,
        economyTier = economyTier,
        other = LiuliOtherNotificationsState(
            milestoneEnabled = milestoneEnabled,
            momentNewPostEnabled = momentNewPostEnabled,
            momentInteractionEnabled = momentInteractionEnabled,
            storyFinishedEnabled = storyFinishedEnabled,
            meetingReminderEnabled = meetingReminderEnabled,
            redPacketExpiringEnabled = redPacketExpiringEnabled,
        ),
        characters = characters,
        disabledIds = disabledIds,
        onGrantPermission = { permissionLauncher.launch(NotificationPermission.PERMISSION) },
        onOpenSystemSettings = { BackgroundReliability.openAppDetailsSettings(context) },
        onSetGlobalEnabled = { viewModel.setGlobalEnabled(it) },
        onSetQuietHoursEnabled = { viewModel.setQuietHoursEnabled(it) },
        onSetQuietHoursStart = { viewModel.setQuietHoursStartMinute(it) },
        onSetQuietHoursEnd = { viewModel.setQuietHoursEndMinute(it) },
        onSetWorldTier = { viewModel.setWorldNotificationTier(it) },
        onSetCalendarMode = { viewModel.setCalendarReminderMode(it) },
        onSetEconomyTier = { viewModel.setEconomyTier(it) },
        onSetMilestoneEnabled = { viewModel.setMilestoneEnabled(it) },
        onSetMomentNewPostEnabled = { viewModel.setMomentNewPostEnabled(it) },
        onSetMomentInteractionEnabled = { viewModel.setMomentInteractionEnabled(it) },
        onSetStoryFinishedEnabled = { viewModel.setStoryFinishedEnabled(it) },
        onSetMeetingReminderEnabled = { viewModel.setMeetingReminderEnabled(it) },
        onSetRedPacketExpiringEnabled = { viewModel.setRedPacketExpiringEnabled(it) },
        onSetCharacterEnabled = { uuid, enabled -> viewModel.setCharacterEnabled(uuid, enabled) },
        onBack = onBack,
        modifier = modifier,
    )
}

/** 通知页内容层（纯参数·可测）。节序与暖陶逐项对齐。 */
@Composable
internal fun LiuliNotificationContent(
    hasPermission: Boolean,
    globalEnabled: Boolean,
    quietHoursEnabled: Boolean,
    quietHoursStart: Int,
    quietHoursEnd: Int,
    calendarMode: CalendarReminderMode,
    economyTier: EconomyNotificationTier,
    characters: List<CharacterEntity>,
    disabledIds: Set<String>,
    onGrantPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onSetGlobalEnabled: (Boolean) -> Unit,
    onSetQuietHoursEnabled: (Boolean) -> Unit,
    onSetQuietHoursStart: (Int) -> Unit,
    onSetQuietHoursEnd: (Int) -> Unit,
    onSetCalendarMode: (CalendarReminderMode) -> Unit,
    onSetEconomyTier: (EconomyNotificationTier) -> Unit,
    onSetCharacterEnabled: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    worldTier: String = AppSettings.WORLD_NOTIFICATION_GENTLE,
    other: LiuliOtherNotificationsState = LiuliOtherNotificationsState(),
    onSetWorldTier: (String) -> Unit = {},
    onSetMilestoneEnabled: (Boolean) -> Unit = {},
    onSetMomentNewPostEnabled: (Boolean) -> Unit = {},
    onSetMomentInteractionEnabled: (Boolean) -> Unit = {},
    onSetStoryFinishedEnabled: (Boolean) -> Unit = {},
    onSetMeetingReminderEnabled: (Boolean) -> Unit = {},
    onSetRedPacketExpiringEnabled: (Boolean) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.notif_settings_title)
    val bottomInset = PAGE_BOTTOM + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val disabledReason = if (!globalEnabled) stringResource(R.string.notif_disabled_reason) else null
    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            item(key = "intro") {
                Text(
                    stringResource(R.string.notif_settings_intro),
                    style = AppTypography.listPreview,
                    color = AppTheme.colors.text.secondary,
                    modifier = Modifier.padding(
                        start = LiuliPageGeometry.gutter,
                        end = LiuliPageGeometry.gutter,
                        top = LiuliPageGeometry.titleGap,
                        bottom = LiuliPageGeometry.groupPadH,
                    ),
                )
            }
            item(key = "groups") {
                Column(Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter)) {
                    if (!hasPermission) permissionGroup(onGrantPermission, onOpenSystemSettings)
                    globalSwitchGroup(globalEnabled, onSetGlobalEnabled)
                    quietHoursGroup(
                        enabled = quietHoursEnabled,
                        controlsEnabled = globalEnabled,
                        disabledReason = disabledReason,
                        startMinute = quietHoursStart,
                        endMinute = quietHoursEnd,
                        onSetEnabled = onSetQuietHoursEnabled,
                        onSetStart = onSetQuietHoursStart,
                        onSetEnd = onSetQuietHoursEnd,
                    )
                    worldNotificationGroup(
                        tier = worldTier,
                        enabled = globalEnabled,
                        disabledReason = disabledReason,
                        onSelect = onSetWorldTier,
                    )
                    calendarModeGroup(calendarMode, onSetCalendarMode)
                    economyGroup(economyTier, onSetEconomyTier)
                    otherNotificationsGroup(
                        state = other,
                        onSetMilestoneEnabled = onSetMilestoneEnabled,
                        onSetMomentNewPostEnabled = onSetMomentNewPostEnabled,
                        onSetMomentInteractionEnabled = onSetMomentInteractionEnabled,
                        onSetStoryFinishedEnabled = onSetStoryFinishedEnabled,
                        onSetMeetingReminderEnabled = onSetMeetingReminderEnabled,
                        onSetRedPacketExpiringEnabled = onSetRedPacketExpiringEnabled,
                    )
                    perCharacterGroup(
                        characters = characters,
                        disabledIds = disabledIds,
                        controlsEnabled = globalEnabled,
                        disabledReason = disabledReason,
                        onSetEnabled = onSetCharacterEnabled,
                    )
                    LiuliInfoNote(text = stringResource(R.string.notif_where_else_info))
                }
            }
        }
    }
}
