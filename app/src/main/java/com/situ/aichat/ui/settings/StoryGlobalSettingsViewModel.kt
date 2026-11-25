package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.resolvedConfigIsThinking
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App 设置「故事创作」子屏的 VM（故事二期卷四·图纸 §3.2）——**全局创作偏好的唯一读写通道**。
 *
 * 四个全局项里，一个在本屏直接改（创作温度滑条），三个（忌口 / 场面节拍 / 口味画像）
 * 点开进统一编辑页，由 `StoryFieldEditorViewModel` 的全局哨兵分支落库——本 VM 只负责显示它们的值标。
 *
 * **存储零碰**：温度的钳位（0..2）留在 [SettingsRepository.setStoryCreationTemperature] 侧，UI 不重复实现；
 * 三态语义（null=出厂默认 / ""=关闭 / 文本=自定义）由仓库与 [com.situ.aichat.story.globalValueLabel] 各自承担。
 */
@HiltViewModel
class StoryGlobalSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    functionRouter: ApiFunctionRouter,
    apiConfigs: ApiConfigRepository,
) : ViewModel() {

    /** 全局设置快照（温度 / 三个全局文本的原值都从这里读·三态照读不判空）。 */
    val settings: StateFlow<AppSettings> = settingsRepository.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /**
     * 故事创作功能当前解析到的配置是否思考模型（谓词逐字复用 `StorySettingsViewModel` 的同名链·
     * 三上游同一条回退顺序）。思考模型不吃温度参数，故只出一行提示、滑条照常可调。
     */
    val storyModelIsThinking: StateFlow<Boolean> = combine(
        functionRouter.assignments,
        apiConfigs.observeAll(),
        apiConfigs.observeActive(),
    ) { assignments, all, active ->
        resolvedConfigIsThinking(assignments[ApiFunction.STORY_CREATION], all, active)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 创作温度（全局·所有故事共用）。钳位在仓库侧，这里只透传。 */
    fun setTemperature(value: Double) {
        viewModelScope.launch { settingsRepository.setStoryCreationTemperature(value) }
    }

    /** B-6：将全局文字忌口、场面节拍与口味画像清空回出厂默认（单本书覆盖不受影响）。 */
    fun resetToDefaults() {
        viewModelScope.launch {
            settingsRepository.setStoryBannedExpressions(null)
            settingsRepository.setStorySceneBeats(null)
            settingsRepository.setStoryTasteProfile(null)
        }
    }
}
