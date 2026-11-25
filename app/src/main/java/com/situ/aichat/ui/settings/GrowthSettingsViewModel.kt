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
 * 成长设置（P12.1d）：性格分析频率 / 成长记录数量 / 兴趣遗忘周期。对齐 iOS `GrowthRelationshipSettingsView`
 * 的「分析设置 / 记忆与兴趣」段（成长与关系总开关已在 12.1b 系统开关屏、记忆提取频率已在 12.1a 记忆设置屏；
 * 情绪记录数量 moodHistoryMaxCount 已被聊天/未答恢复的情绪历史归档路径消费为截断上限，本屏已接入滑杆）。这些字段早被 ChatViewModel /
 * GrowthAnalysisCoordinator 读取，本屏只接上读写 UI。滑杆受 growthSystemEnabled 门控（1:1 iOS）。
 */
@HiltViewModel
class GrowthSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<AppSettings> = settings.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setGrowthSystemEnabled(enabled: Boolean) = launch { settings.setGrowthSystemEnabled(enabled) }
    fun setRelationshipAutoAdvanceEnabled(enabled: Boolean) = launch { settings.setRelationshipAutoAdvanceEnabled(enabled) }
    fun setGrowthAnalysisInterval(rounds: Int) = launch { settings.setGrowthAnalysisInterval(rounds) }
    fun setGrowthLogMaxCount(count: Int) = launch { settings.setGrowthLogMaxCount(count) }
    fun setMoodHistoryMaxCount(count: Int) = launch { settings.setMoodHistoryMaxCount(count) }
    fun setInterestCooldownDays(days: Int) = launch { settings.setInterestCooldownDays(days) }

    private inline fun launch(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
