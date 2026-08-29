package com.situ.aichat.data.local.entity

import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AudioInputMode
import com.situ.aichat.data.model.CapabilitySupportState
import com.situ.aichat.data.model.KnownModelCapabilityTable
import com.situ.aichat.data.model.ThinkingModelMode
import com.situ.aichat.data.model.ToolCallingMode
import com.situ.aichat.data.model.ToolDetectionResult
import com.situ.aichat.data.model.ToolProtocolFamily
import com.situ.aichat.data.model.ToolSupportLevel
import com.situ.aichat.data.model.VisionMode

/**
 * Capability convenience accessors over [ApiConfigEntity] — faithful port of the
 * `@Transient` computed properties + apply/reset/prefill helpers in iOS
 * `APIConfiguration.swift`.
 *
 * Room entities are immutable data classes, so the mutating iOS helpers become pure
 * functions that return an updated copy.
 *
 * Detection convention (mirrors iOS): -1 = undetected, 0 = unsupported, 1 = supported.
 */

// MARK: - Mode accessors

val ApiConfigEntity.toolCallingMode: ToolCallingMode
    get() = ToolCallingMode.fromRaw(toolCallingModeRaw)

val ApiConfigEntity.visionMode: VisionMode
    get() = VisionMode.fromRaw(visionModeRaw)

val ApiConfigEntity.audioInputMode: AudioInputMode
    get() = AudioInputMode.fromRaw(audioInputModeRaw)

val ApiConfigEntity.thinkingModelMode: ThinkingModelMode
    get() = ThinkingModelMode.fromRaw(thinkingModelModeRaw)

// MARK: - Detected tool accessors

val ApiConfigEntity.detectedToolSupportLevel: ToolSupportLevel
    get() = ToolSupportLevel.fromRaw(detectedToolSupportLevelRaw)

val ApiConfigEntity.detectedToolProtocolFamily: ToolProtocolFamily
    get() = ToolProtocolFamily.fromRaw(detectedToolProtocolFamilyRaw)

val ApiConfigEntity.detectedStreamingToolSupport: CapabilitySupportState
    get() = CapabilitySupportState.fromRaw(detectedStreamingToolSupportRaw)

val ApiConfigEntity.detectedThinkingToolSupport: CapabilitySupportState
    get() = CapabilitySupportState.fromRaw(detectedThinkingToolSupportRaw)

// MARK: - Effective capability (mode + detection combined)

fun ApiConfigEntity.effectiveIsThinkingModel(): Boolean = when (thinkingModelMode) {
    ThinkingModelMode.THINKING -> true
    ThinkingModelMode.STANDARD -> false
    ThinkingModelMode.AUTO -> detectedThinkingModelType == 1
}

/**
 * 某个功能当前解析到的配置是否思考模型（各设置屏「温度对思考模型不生效」提示行的共用谓词）。
 *
 * 回退语义对齐 [com.situ.aichat.data.repository.ApiConfigRepository.resolveConfigValues]：
 * 显式分配且该配置仍存在 → 用它；分配失效或未分配 → 用默认（active）配置；一个配置都没有 → false。
 *
 * @param assignedUuid 该功能在 `ApiFunctionRouter.assignments` 里的分配（无则 null）
 */
fun resolvedConfigIsThinking(
    assignedUuid: String?,
    all: List<ApiConfigEntity>,
    active: ApiConfigEntity?,
): Boolean = resolvedConfigOrNull(assignedUuid, all, active)?.effectiveIsThinkingModel() ?: false

/**
 * 某个功能当前会解析到哪个配置（回退语义同 [resolvedConfigIsThinking] 的说明，两者共用本函数当单源）。
 * null = 一个配置都没有 → 该功能这会儿不可用（UI 据此藏掉「要联网才办得到」的入口）。
 */
fun resolvedConfigOrNull(
    assignedUuid: String?,
    all: List<ApiConfigEntity>,
    active: ApiConfigEntity?,
): ApiConfigEntity? = assignedUuid?.let { uuid -> all.firstOrNull { it.uuid == uuid } } ?: active

/**
 * 某个功能当前解析到的配置**是否看得懂图**（回退语义同 [resolvedConfigIsThinking]，共用 [resolvedConfigOrNull]）。
 *
 * 承重用途：聊天「+」面板的「照片」入口据此显隐——`ApiFunction.CHAT` 解析到的模型没有视觉能力时
 * 根本不给发图按钮，免得用户发出去却只换来一句读不懂图的回复。
 *
 * 「不确定」(detectedVisionSupport == -1) 一律按 **false** 处理（[effectiveVisionEnabled] 的 AUTO 语义）：
 * 宁可少给一个按钮，也不让人发了图才发现对方看不见。用户确知模型支持时，可在该配置的
 * 「图片理解」里手动选「开启」强制打开。
 */
fun resolvedConfigHasVision(
    assignedUuid: String?,
    all: List<ApiConfigEntity>,
    active: ApiConfigEntity?,
): Boolean = resolvedConfigOrNull(assignedUuid, all, active)?.effectiveVisionEnabled() ?: false

fun ApiConfigEntity.effectiveVisionEnabled(): Boolean = when (visionMode) {
    VisionMode.ENABLED -> true
    VisionMode.DISABLED -> false
    VisionMode.AUTO -> detectedVisionSupport == 1
}

fun ApiConfigEntity.effectiveAudioInputEnabled(): Boolean = when (audioInputMode) {
    AudioInputMode.ENABLED -> true
    AudioInputMode.DISABLED -> false
    AudioInputMode.AUTO -> detectedAudioInputSupport == 1
}

/** For thinking models, tool support requires a verified thinking-tool round-trip (or full level). */
val ApiConfigEntity.supportsThinkingToolCalling: Boolean
    get() = when (detectedThinkingToolSupport) {
        CapabilitySupportState.SUPPORTED -> true
        CapabilitySupportState.UNSUPPORTED -> false
        CapabilitySupportState.UNKNOWN -> detectedToolSupportLevel == ToolSupportLevel.FULL
    }

fun ApiConfigEntity.effectiveToolCallingEnabled(): Boolean = when (toolCallingMode) {
    ToolCallingMode.ENABLED -> true
    ToolCallingMode.DISABLED -> false
    ToolCallingMode.AUTO ->
        if (effectiveIsThinkingModel()) supportsThinkingToolCalling
        else detectedToolSupportLevel.enablesBasicToolCalling
}

// MARK: - Apply / reset / prefill (return updated copies)

fun ApiConfigEntity.applyingToolDetectionResult(result: ToolDetectionResult): ApiConfigEntity = copy(
    detectedToolSupportLevelRaw = result.level.raw,
    detectedToolProtocolFamilyRaw = result.protocolFamily.raw,
    detectedStreamingToolSupportRaw = result.streamingState.raw,
    detectedThinkingToolSupportRaw = result.thinkingState.raw,
    toolDetectionSummary = result.summary,
    toolDetectionCheckedAt = result.checkedAt,
)

fun ApiConfigEntity.resettingCapabilityDetectionResults(): ApiConfigEntity {
    val family = ApiProviderType.fromRaw(providerTypeRaw).toolProtocolFamily
    return copy(
        detectedThinkingModelType = -1,
        detectedToolSupportLevelRaw = ToolSupportLevel.UNKNOWN.raw,
        detectedToolProtocolFamilyRaw = family.raw,
        detectedStreamingToolSupportRaw = CapabilitySupportState.UNKNOWN.raw,
        detectedThinkingToolSupportRaw = CapabilitySupportState.UNKNOWN.raw,
        toolDetectionSummary = "",
        toolDetectionCheckedAt = null,
        detectedVisionSupport = -1,
        detectedAudioInputSupport = -1,
    )
}

/**
 * Prefill thinking/vision/audio detection from the static known-model table when in auto
 * mode and not yet detected. Mirrors iOS `prefillFromKnownCapabilities`; tool calling is
 * never prefilled (it is always probed). Returns the entity unchanged if the model is unknown.
 */
fun ApiConfigEntity.prefilledFromKnownCapabilities(): ApiConfigEntity {
    val known = KnownModelCapabilityTable.lookup(modelName) ?: return this
    var result = this
    if (thinkingModelMode == ThinkingModelMode.AUTO && result.detectedThinkingModelType == -1) {
        result = result.copy(detectedThinkingModelType = if (known.isThinking) 1 else 0)
    }
    if (visionMode == VisionMode.AUTO && result.detectedVisionSupport == -1) {
        result = result.copy(detectedVisionSupport = if (known.hasVision) 1 else 0)
    }
    if (audioInputMode == AudioInputMode.AUTO && result.detectedAudioInputSupport == -1) {
        result = result.copy(detectedAudioInputSupport = if (known.hasAudioInput) 1 else 0)
    }
    return result
}
