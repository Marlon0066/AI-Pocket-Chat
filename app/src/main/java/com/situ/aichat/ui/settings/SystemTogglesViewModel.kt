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
 * 系统开关（P12.1b）：成长/关系自动进化/日程/宠物/货币/主动送礼/故事一致性的子系统总开关。
 * 这些字段早被各服务/PromptBuilder/ChatViewModel 读取以 gate 子系统，本屏只接上读写 UI。
 */
@HiltViewModel
class SystemTogglesViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<AppSettings> = settings.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setGrowthSystemEnabled(v: Boolean) = launch { settings.setGrowthSystemEnabled(v) }
    fun setRelationshipAutoAdvanceEnabled(v: Boolean) = launch { settings.setRelationshipAutoAdvanceEnabled(v) }
    fun setScheduleSystemEnabled(v: Boolean) = launch { settings.setScheduleSystemEnabled(v) }
    fun setCrossCharacterLevel(level: Int) = launch { settings.setCrossCharacterLevel(level) }
    fun setPetSystemEnabled(v: Boolean) = launch { settings.setPetSystemEnabled(v) }
    fun setPetDiaryAutoGenerateEnabled(v: Boolean) = launch { settings.setPetDiaryAutoGenerateEnabled(v) }
    fun setCurrencySystemEnabled(v: Boolean) = launch { settings.setCurrencySystemEnabled(v) }
    fun setCharacterProactiveGiftEnabled(v: Boolean) = launch { settings.setCharacterProactiveGiftEnabled(v) }

    private inline fun launch(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
