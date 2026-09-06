package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.SettingsPreferences
import com.situ.aichat.data.model.AppearanceMode
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsProviderType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 设置主页行尾回显（SETTINGS_REORG D6）：聚合只读现状——外观（配色 + 深浅）、TTS 提供商、
 * 通知总开关、世界鲜活度，主页不点进子页即可见当前值；另回显深层记忆加载状态（被动读·记忆健壮性 #3）。
 * 只出枚举 / 布尔，文案映射留在 UI 层（枚举 → 字符串资源）；本 VM 无任何写口。
 */
@HiltViewModel
class SettingsOverviewViewModel @Inject constructor(
    settingsPreferences: SettingsPreferences,
    settingsRepository: SettingsRepository,
    ttsRepository: TtsConfigurationRepository,
    embedder: TextEmbedder,
) : ViewModel() {

    /**
     * 深层记忆（向量嵌入器）加载状态回显（记忆健壮性 #3·诊断区可观测）：直接透传 [TextEmbedder.loadState]，
     * 读取是**被动**的——绝不触发懒加载（区别于 isAvailable 一读即同步加载 23MB 模型），故接入 UI 不破坏懒加载。
     */
    val embedderLoadState: StateFlow<TextEmbedder.LoadState> = embedder.loadState

    val appSkin: StateFlow<AppSkin> = settingsPreferences.appSkin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSkin.CLAY)

    val appearanceMode: StateFlow<AppearanceMode> = settingsPreferences.appearanceMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppearanceMode.SYSTEM)

    val ttsProvider: StateFlow<TtsProviderType> = ttsRepository.configuration
        .map { it.providerType }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TtsProviderType.SYSTEM)

    val notificationsEnabled: StateFlow<Boolean> = settingsRepository.appSettings
        .map { it.notificationsEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 世界组行尾回显：鲜活度档 raw（lite/standard/rich·UI 层映射档名·W13 图纸 §3.4）。 */
    val worldVividnessTier: StateFlow<String> = settingsRepository.appSettings
        .map { it.worldVividnessTier }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.situ.aichat.data.model.AppSettings.WORLD_VIVIDNESS_STANDARD)
}
