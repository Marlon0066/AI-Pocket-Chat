package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsProviderType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 语音设置统一页 VM（设置全面重构卷二）：
 * 聚合语音服务当前提供商名、语音条回复轮次范围、语音通话打断灵敏度（拆除原「语音通话」独立页）。
 * 所有写口仍直达 [SettingsRepository] 原有 setter，存储键与钳位一字不变。
 */
@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    ttsRepository: TtsConfigurationRepository,
) : ViewModel() {

    val ttsProviderName: StateFlow<String> = ttsRepository.configuration
        .map { it.providerType.displayName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TtsProviderType.SYSTEM.displayName)

    val state: StateFlow<AppSettings> = settings.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val sliderPosition: StateFlow<Float> = settings.appSettings
        .map { VoiceCallSensitivity.sliderFromThreshold(it.sanitizedVoiceCallInterruptThreshold) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            VoiceCallSensitivity.sliderFromThreshold(AppSettings.DEFAULT_VOICE_CALL_INTERRUPT_THRESHOLD),
        )

    fun setVoiceReplyRoundRange(min: Int, max: Int) {
        viewModelScope.launch { settings.setVoiceReplyRoundRange(min, max) }
    }

    fun setSliderPosition(slider: Float) {
        val threshold = VoiceCallSensitivity.thresholdFromSlider(slider)
        viewModelScope.launch { settings.setVoiceCallInterruptThreshold(threshold) }
    }
}
