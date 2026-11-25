package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.local.entity.resolvedConfigIsThinking
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.ui.chat.ChatMessageDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 回复规则设置（14.3a）：回复段数范围 + 语音条回复轮次范围。后端字段（replySegmentMin/Max、
 * voiceReplyRoundMin/Max）已建并被 ChatViewModel 消费，本屏只接读写 UI（持久化经 [SettingsRepository]）。
 * C2（输入排契约 §3.2-11）：+ 消息发送等待时间（合并等待窗·0.5–5s 步 0.5·默认/钳位单源
 * [ChatMessageDispatcher] 伴生·自适应后台改值时经 Flow 实时回显）。
 * CREATIVITY_RELOCATION（2026-07-11）：+ 创造力（温度，搬自记忆页）与思考模型提示谓词。
 */
@HiltViewModel
class ReplyRuleSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    functionRouter: ApiFunctionRouter,
    apiConfigs: ApiConfigRepository,
) : ViewModel() {

    val state: StateFlow<AppSettings> = settings.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** 合并等待窗秒数（显示值=持久值经钳位·未设过用默认 1.5s——与 dispatcher 读取口径一致）。 */
    val chatSendWaitSeconds: StateFlow<Float> = settings.chatSendWaitSecondsFlow
        .map { (it ?: ChatMessageDispatcher.DEFAULT_WAIT_SECONDS).coerceIn(ChatMessageDispatcher.MIN_WAIT_SECONDS, ChatMessageDispatcher.MAX_WAIT_SECONDS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatMessageDispatcher.DEFAULT_WAIT_SECONDS)

    /**
     * 聊天功能当前解析到的配置是否思考模型（CREATIVITY_RELOCATION D-3 提示行谓词）。
     * 回退语义对齐 [ApiConfigRepository.resolveConfigValues]：显式分配且配置仍存在用它，否则用默认配置。
     */
    val chatModelIsThinking: StateFlow<Boolean> = combine(
        functionRouter.assignments,
        apiConfigs.observeAll(),
        apiConfigs.observeActive(),
    ) { assignments, all, active ->
        chatConfigIsThinking(assignments[ApiFunction.CHAT], all, active)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setReplySegmentRange(min: Int, max: Int) =
        launch { settings.setReplySegmentRange(min, max) }

    fun setVoiceReplyRoundRange(min: Int, max: Int) =
        launch { settings.setVoiceReplyRoundRange(min, max) }

    /** 写合并等待窗（UI 已按 0.5 步量化；这里再钳范围兜底）。自适应从新值的样本继续学（iOS setDebounce 同款语义）。 */
    fun setChatSendWaitSeconds(seconds: Float) = launch {
        settings.setChatSendWaitSeconds(seconds.coerceIn(ChatMessageDispatcher.MIN_WAIT_SECONDS, ChatMessageDispatcher.MAX_WAIT_SECONDS))
    }

    /** 创造力（温度）——搬自记忆页，存储 key 与钳位不变（持久化经 [SettingsRepository.setLlmTemperature]）。 */
    fun setLlmTemperature(value: Double) = launch { settings.setLlmTemperature(value) }

    /** 自然短句口吻（搬自旧设置主页·持久化经 [SettingsRepository.setTextingToneEnabled]）。 */
    fun setTextingToneEnabled(enabled: Boolean) = launch { settings.setTextingToneEnabled(enabled) }

    /** 消息情绪动画（搬自旧设置主页·持久化经 [SettingsRepository.setEmotionAnimationEnabled]）。 */
    fun setEmotionAnimationEnabled(enabled: Boolean) = launch { settings.setEmotionAnimationEnabled(enabled) }

    private inline fun launch(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        /**
         * 纯函数：聊天功能解析出的配置是否思考模型（分配失效→默认配置；无任何配置→false）。
         * 实现在 [resolvedConfigIsThinking]（卷三 C1 抽为共用谓词，故事设置页同用），本处保薄委托 + 既有测试锚。
         */
        internal fun chatConfigIsThinking(
            assignedUuid: String?,
            all: List<ApiConfigEntity>,
            active: ApiConfigEntity?,
        ): Boolean = resolvedConfigIsThinking(assignedUuid, all, active)
    }
}
