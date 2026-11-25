package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.meeting.MeetupNotificationService
import com.situ.aichat.notification.CalendarNotificationScheduler
import com.situ.aichat.notification.CalendarReminderMode
import com.situ.aichat.notification.EconomyNotificationTier
import com.situ.aichat.notification.NotificationScheduler
import com.situ.aichat.redpacket.RedPacketExpirationScanService
import com.situ.aichat.work.ReliabilityPromptController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 通知设置页 VM（设置全面重构卷四：统一管理角色消息通知、世界通知、日历提醒、经济动态、
 * 关系里程碑、朋友圈新动态/互动、故事完成、约见面到点、红包快过期预警与每角色开关）。
 */
@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val characterRepository: CharacterRepository,
    private val notificationScheduler: NotificationScheduler,
    private val calendarNotificationScheduler: CalendarNotificationScheduler,
    private val meetupNotificationService: MeetupNotificationService,
    private val redPacketExpirationScanService: RedPacketExpirationScanService,
    private val reliabilityPromptController: ReliabilityPromptController,
) : ViewModel() {

    val globalEnabled: StateFlow<Boolean> = settingsRepository.appSettings
        .map { it.notificationsEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val quietHoursEnabled: StateFlow<Boolean> = settingsRepository.appSettings
        .map { it.quietHoursEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val quietHoursStartMinute: StateFlow<Int> = settingsRepository.appSettings
        .map { it.quietHoursStartMinute }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1380)

    val quietHoursEndMinute: StateFlow<Int> = settingsRepository.appSettings
        .map { it.quietHoursEndMinute }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 450)

    val characters: StateFlow<List<CharacterEntity>> = characterRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val disabledCharacterIds: StateFlow<Set<String>> = settingsRepository.disabledNotificationCharacterIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val calendarReminderMode: StateFlow<CalendarReminderMode> = settingsRepository.appSettings
        .map { CalendarReminderMode.fromRaw(it.calendarReminderMode) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarReminderMode.BOTH)

    val worldNotificationTier: StateFlow<String> = settingsRepository.appSettings
        .map { it.worldNotificationTier }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings.WORLD_NOTIFICATION_GENTLE)

    val milestoneEnabled: StateFlow<Boolean> = settingsRepository.appSettings
        .map { it.milestoneNotificationEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val economyTier: StateFlow<EconomyNotificationTier> = settingsRepository.appSettings
        .map { EconomyNotificationTier.fromRaw(it.economyNotificationTier) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EconomyNotificationTier.BRIEF)

    val momentNewPostEnabled: StateFlow<Boolean> = settingsRepository.appSettings
        .map { it.momentNewPostNotificationEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val momentInteractionEnabled: StateFlow<Boolean> = settingsRepository.appSettings
        .map { it.notifMomentInteractionEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val storyFinishedEnabled: StateFlow<Boolean> = settingsRepository.appSettings
        .map { it.notifStoryFinishedEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val meetingReminderEnabled: StateFlow<Boolean> = settingsRepository.appSettings
        .map { it.notifMeetingReminderEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val redPacketExpiringEnabled: StateFlow<Boolean> = settingsRepository.appSettings
        .map { it.notifRedPacketExpiringEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setWorldNotificationTier(tier: String) {
        viewModelScope.launch { settingsRepository.setWorldNotificationTier(tier) }
    }

    fun setMilestoneEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMilestoneNotificationEnabled(enabled) }
    }

    fun setEconomyTier(tier: EconomyNotificationTier) {
        viewModelScope.launch { settingsRepository.setEconomyNotificationTier(tier.raw) }
    }

    fun setMomentNewPostEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMomentNewPostNotificationEnabled(enabled) }
    }

    fun setMomentInteractionEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotifMomentInteractionEnabled(enabled) }
    }

    fun setStoryFinishedEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotifStoryFinishedEnabled(enabled) }
    }

    fun setMeetingReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotifMeetingReminderEnabled(enabled)
            meetupNotificationService.rescheduleAll()
        }
    }

    fun setRedPacketExpiringEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotifRedPacketExpiringEnabled(enabled)
            redPacketExpirationScanService.onExpiringNotificationSettingChanged(enabled)
        }
    }

    fun setCalendarReminderMode(mode: CalendarReminderMode) {
        viewModelScope.launch {
            settingsRepository.setCalendarReminderMode(mode.raw)
            calendarNotificationScheduler.refreshForForeground()
        }
    }

    fun setGlobalEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotificationsEnabled(enabled)
            notificationScheduler.scheduleAll()
            if (enabled) reliabilityPromptController.onBackgroundFeatureEnabled()
        }
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setQuietHoursEnabled(enabled)
            rescheduleAll()
        }
    }

    fun setQuietHoursStartMinute(minuteOfDay: Int) {
        viewModelScope.launch {
            settingsRepository.setQuietHoursStartMinute(minuteOfDay)
            rescheduleAll()
        }
    }

    fun setQuietHoursEndMinute(minuteOfDay: Int) {
        viewModelScope.launch {
            settingsRepository.setQuietHoursEndMinute(minuteOfDay)
            rescheduleAll()
        }
    }

    private suspend fun rescheduleAll() {
        notificationScheduler.cancelAll()
        notificationScheduler.scheduleAll()
    }

    fun setCharacterEnabled(characterId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCharacterNotificationEnabled(characterId, enabled)
            characterRepository.get(characterId)?.let { notificationScheduler.handlePreferenceChange(it, enabled) }
        }
    }
}
