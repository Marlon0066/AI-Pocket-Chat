package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 记忆设置（P12.1）：短期记忆 / 长期记忆(滚动摘要) / 结构化记忆 / 向量检索阈值。
 * 这些字段早被 ChatViewModel/BusyReplyService/RecoveryReplyGenerator 读取，本屏只是接上读写 UI。
 */
@HiltViewModel
class MemorySettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<AppSettings> = settings.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setShortTermMemoryLength(rounds: Int) = launch { settings.setShortTermMemoryLength(rounds) }
    fun setAutoSummarizeInterval(rounds: Int) = launch { settings.setAutoSummarizeInterval(rounds) }
    fun setMemorySummaryMaxLength(chars: Int) = launch { settings.setMemorySummaryMaxLength(chars) }
    fun setMemorySummaryCooldownMinutes(minutes: Int) = launch { settings.setMemorySummaryCooldownMinutes(minutes) }
    fun setProgressiveCompressionEnabled(enabled: Boolean) = launch { settings.setProgressiveCompressionEnabled(enabled) }
    fun setStructuredMemoryInterval(rounds: Int) = launch { settings.setStructuredMemoryInterval(rounds) }
    fun setVectorSearchThreshold(percent: Int) = launch { settings.setVectorSearchThreshold(percent) }

    private inline fun launch(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
