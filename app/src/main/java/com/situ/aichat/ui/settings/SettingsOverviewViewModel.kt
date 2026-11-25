package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.SettingsPreferences
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.worldbook.WorldBookRepository
import com.situ.aichat.notification.EconomyNotificationTier
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.ui.settings.hub.SettingsHubSnapshot
import com.situ.aichat.ui.settings.hub.resolveChatSendWaitSeconds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 设置主页 + 高级页只读快照 VM（设置全面重构卷一 / 卷四补齐通知活跃类别计数）：
 * 聚合全部行尾回显数据成单一 [snapshot] 流，主页不再依赖重量级 ApiConfigViewModel 或 UserProfileViewModel；
 * 同时透传 [embedderLoadState]（被动只读，绝不触发懒加载）供「高级」页诊断区使用。
 */
@HiltViewModel
class SettingsOverviewViewModel @Inject constructor(
    apiConfigRepository: ApiConfigRepository,
    settingsPreferences: SettingsPreferences,
    settingsRepository: SettingsRepository,
    ttsRepository: TtsConfigurationRepository,
    worldBookRepository: WorldBookRepository,
    embedder: TextEmbedder,
) : ViewModel() {

    /** 深层记忆（向量嵌入器）加载状态回显（被动读，不触发 23MB 模型懒加载）。 */
    val embedderLoadState: StateFlow<TextEmbedder.LoadState> = embedder.loadState

    private val corePart = combine(
        apiConfigRepository.observeActive().map { cfg -> cfg?.let { "${it.providerName} · ${it.modelName}" } },
        settingsPreferences.appSkin,
        settingsPreferences.appearanceMode,
        ttsRepository.configuration.map { it.providerType.displayName },
        worldBookRepository.observeAllBooks().map { books -> books.count { it.enabled } },
    ) { activeLabel, skin, mode, ttsName, enabledBooks ->
        CorePart(
            activeConfigLabel = activeLabel,
            appSkin = skin,
            appearanceMode = mode,
            ttsProviderName = ttsName,
            enabledWorldBookCount = enabledBooks,
        )
    }

    val snapshot: StateFlow<SettingsHubSnapshot> = combine(
        corePart,
        settingsRepository.appSettings,
        settingsRepository.chatSendWaitSecondsFlow,
        settingsRepository.autoBackupConfig,
    ) { core, s, waitSecondsRaw, backup ->
        val activeNotifCategories = listOf(
            s.notificationsEnabled,
            s.notificationsEnabled && s.worldNotificationTier != AppSettings.WORLD_NOTIFICATION_SILENT,
            s.economyNotificationTier != EconomyNotificationTier.OFF.raw,
            s.milestoneNotificationEnabled,
            s.momentNewPostNotificationEnabled,
            s.notifMomentInteractionEnabled,
            s.notifStoryFinishedEnabled,
            s.notifMeetingReminderEnabled,
            s.notifRedPacketExpiringEnabled,
        ).count { it }
        SettingsHubSnapshot(
            activeConfigLabel = core.activeConfigLabel,
            appSkin = core.appSkin,
            appearanceMode = core.appearanceMode,
            chatSendWaitSeconds = resolveChatSendWaitSeconds(waitSecondsRaw),
            replySegmentRange = s.sanitizedReplySegmentRange,
            ttsProviderName = core.ttsProviderName,
            offlineNarrativeDetailRaw = s.offlineNarrativeDetailRaw,
            stickersEnabled = s.characterCanSendStickersEnabled,
            shortTermMemoryLength = s.shortTermMemoryLength,
            enabledWorldBookCount = core.enabledWorldBookCount,
            scheduleEnabled = s.scheduleSystemEnabled,
            crossCharacterLevel = s.crossCharacterLevel,
            growthEnabled = s.growthSystemEnabled,
            petEnabled = s.petSystemEnabled,
            currencyEnabled = s.currencySystemEnabled,
            worldVividnessTier = s.worldVividnessTier,
            diaryAutoEnabled = s.diaryAutoGenerateEnabled,
            diaryAutoTime = s.diaryAutoGenerateTime,
            momentAutoPostFrequency = s.momentAutoPostFrequency,
            storyTemperature = s.sanitizedStoryCreationTemperature,
            notificationsEnabled = s.notificationsEnabled,
            activeNotifCategoryCount = activeNotifCategories.takeIf { it > 0 },
            calendarEnabled = s.calendarIntegrationEnabled,
            autoBackupEnabled = backup.enabled,
            lastBackupAt = backup.lastBackupAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsHubSnapshot())

    private data class CorePart(
        val activeConfigLabel: String?,
        val appSkin: com.situ.aichat.data.model.AppSkin,
        val appearanceMode: com.situ.aichat.data.model.AppearanceMode,
        val ttsProviderName: String,
        val enabledWorldBookCount: Int,
    )
}
