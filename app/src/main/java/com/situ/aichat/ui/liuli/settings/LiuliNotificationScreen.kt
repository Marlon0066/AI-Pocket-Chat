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
import com.situ.aichat.notification.CalendarReminderMode
import com.situ.aichat.notification.EconomyNotificationTier
import com.situ.aichat.notification.NotificationPermission
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.NotificationSettingsViewModel
import com.situ.aichat.work.BackgroundReliability

/** 页底留白（同设置主页）。 */
private val PAGE_BOTTOM = 24.dp

/**
 * 通知设置页（琉璃·图纸 2026-09-06 卷四 A-7）。与暖陶 `NotificationSettingsScreen` 共用
 * [NotificationSettingsViewModel]；权限流（launcher + `ON_RESUME` 复查）逐字搬（机制锁 §9 ④）。
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
    val economyTier by viewModel.economyTier.collectAsStateWithLifecycle()
    val milestoneEnabled by viewModel.milestoneEnabled.collectAsStateWithLifecycle()
    val advancedEnabled by viewModel.advancedModeEnabled.collectAsStateWithLifecycle()

    var hasPermission by remember { mutableStateOf(NotificationPermission.isGranted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = NotificationPermission.isGranted(context)
    }

    // 从系统设置返回时复查授权（逐字搬暖陶 F4 :83-90）。
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
        calendarMode = calendarMode,
        economyTier = economyTier,
        milestoneEnabled = milestoneEnabled,
        advancedEnabled = advancedEnabled,
        characters = characters,
        disabledIds = disabledIds,
        onGrantPermission = { permissionLauncher.launch(NotificationPermission.PERMISSION) },
        onOpenSystemSettings = { BackgroundReliability.openAppDetailsSettings(context) },
        onSetGlobalEnabled = { viewModel.setGlobalEnabled(it) },
        onSetQuietHoursEnabled = { viewModel.setQuietHoursEnabled(it) },
        onSetQuietHoursStart = { viewModel.setQuietHoursStartMinute(it) },
        onSetQuietHoursEnd = { viewModel.setQuietHoursEndMinute(it) },
        onSetCalendarMode = { viewModel.setCalendarReminderMode(it) },
        onSetEconomyTier = { viewModel.setEconomyTier(it) },
        onSetMilestoneEnabled = { viewModel.setMilestoneEnabled(it) },
        onSetCharacterEnabled = { uuid, enabled -> viewModel.setCharacterEnabled(uuid, enabled) },
        onBack = onBack,
        modifier = modifier,
    )
}

/** 通知页内容层（纯参数·可测）。节序与条件逐字继承暖陶。 */
@Composable
internal fun LiuliNotificationContent(
    hasPermission: Boolean,
    globalEnabled: Boolean,
    quietHoursEnabled: Boolean,
    quietHoursStart: Int,
    quietHoursEnd: Int,
    calendarMode: CalendarReminderMode,
    economyTier: EconomyNotificationTier,
    milestoneEnabled: Boolean,
    advancedEnabled: Boolean,
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
    onSetMilestoneEnabled: (Boolean) -> Unit,
    onSetCharacterEnabled: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.notif_settings_title)
    val bottomInset = PAGE_BOTTOM + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
                        bottom = LiuliPageGeometry.groupPadH, // §4.3「下 16」（复核 R1 🔵-4）
                    ),
                )
            }
            item(key = "groups") {
                Column(Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter)) {
                    if (!hasPermission) permissionGroup(onGrantPermission, onOpenSystemSettings)
                    globalSwitchGroup(globalEnabled, onSetGlobalEnabled)
                    quietHoursGroup(
                        enabled = quietHoursEnabled,
                        startMinute = quietHoursStart,
                        endMinute = quietHoursEnd,
                        onSetEnabled = onSetQuietHoursEnabled,
                        onSetStart = onSetQuietHoursStart,
                        onSetEnd = onSetQuietHoursEnd,
                    )
                    calendarModeGroup(calendarMode, onSetCalendarMode)
                    milestoneGroup(milestoneEnabled, onSetMilestoneEnabled)
                    if (advancedEnabled) economyGroup(economyTier, onSetEconomyTier)
                    perCharacterGroup(characters, disabledIds, onSetCharacterEnabled)
                }
            }
        }
    }
}
