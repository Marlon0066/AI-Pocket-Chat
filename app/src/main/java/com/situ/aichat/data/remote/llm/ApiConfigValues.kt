package com.situ.aichat.data.remote.llm

import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.MaxOutputLength
import com.situ.aichat.data.model.ThinkingBudgetLevel

/**
 * Immutable snapshot of an API configuration used to make one request
 * (mirrors iOS `APIConfigValues`). The resolved apiKey is passed in explicitly
 * (read from the encrypted key store), never persisted alongside the config row.
 */
data class ApiConfigValues(
    val providerType: ApiProviderType,
    val apiKey: String,
    val baseUrl: String,
    val modelName: String,
    val thinkingBudgetLevel: ThinkingBudgetLevel = ThinkingBudgetLevel.AUTO,
    val isThinkingModel: Boolean = false,
    val maxOutputLength: MaxOutputLength = MaxOutputLength.AUTO,
    /**
     * 该配置是否应启用结构化工具调用（双轨结构化路；模式 + 能力检测综合结果，在 resolve 时从
     * `ApiConfigEntity.effectiveToolCallingEnabled()` 快照而来，1:1 iOS `config.effectiveToolCallingEnabled`）。
     */
    val toolCallingEnabled: Boolean = false,
    /**
     * 该配置是否应把语音消息的音频直发给模型（多模态 input_audio；模式 + 能力检测综合结果，在 resolve 时从
     * `ApiConfigEntity.effectiveAudioInputEnabled()` 快照而来，1:1 iOS `config.effectiveAudioInputEnabled`）。
     * false → 语音消息走端侧 STT 转写当纯文本发（见 PromptBuilder 音频段挂载 gate）。
     */
    val audioInputEnabled: Boolean = false,
    /**
     * 该配置是否应把图片直发给模型（多模态 image_url；模式 + 能力检测综合结果，在 resolve 时从
     * `ApiConfigEntity.effectiveVisionEnabled()` 快照而来，与 [audioInputEnabled] 完全同构）。
     * false → 图片消息只走语义占位文本（`renderMemoryContent` 的「发送了一张图片：{摘要}」），
     * 用户仍可正常发图（拍板②：入口常开），AI 靠摘要回应。
     */
    val visionEnabled: Boolean = false,
)
