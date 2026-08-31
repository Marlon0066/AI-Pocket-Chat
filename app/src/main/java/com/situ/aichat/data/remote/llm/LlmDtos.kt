package com.situ.aichat.data.remote.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI-compatible chat/completions wire types (mirrors iOS LLMServiceTypes).
 * content 支持「裸字符串（纯文本）OR 数组式（多模态：text + input_audio/image_url）」——见 [ChatMessageDto] /
 * [ChatContentPart] / [ChatMessageDtoSerializer]（P13.4b 多模态地基，与 vision 共用）。
 * The shared Json is configured with explicitNulls=false so null fields are omitted
 * (matches iOS `encodeIfPresent`).
 */
@Serializable
data class ChatRequestDto(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** 推理系方言（OpenAI gpt-5.x/o 系拒收 max_tokens 时的换名重发）：与 [maxTokens] 互斥，恒只发一个。 */
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    /** 结构化工具调用定义（1:1 iOS ChatRequest.tools）；null=不发工具（走文本标记）。 */
    val tools: List<ToolDefinitionDto>? = null,
    val reasoning: ReasoningParamDto? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val thinking: ThinkingParamDto? = null,
    @SerialName("response_format") val responseFormat: ResponseFormatDto? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("stream_options") val streamOptions: StreamOptionsDto? = null,
)

@Serializable(with = ChatMessageDtoSerializer::class)
data class ChatMessageDto(
    val role: String,
    val content: String? = null,
    /**
     * 多模态分段（语音/图片消息用，1:1 iOS `MessageContent.multimodal`）；非空时 [content] 字符串被忽略，
     * 由 [ChatMessageDtoSerializer] 编码为 OpenAI `content` 数组式。纯文本消息留 null、走 [content] 裸字符串。
     */
    val contentParts: List<ChatContentPart>? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    /** assistant 回合返回的工具调用（follow-up 时回传，1:1 iOS ChatMessage.tool_calls）；null 时省略。 */
    @SerialName("tool_calls") val toolCalls: List<RequestToolCallDto>? = null,
    /** tool 角色消息归属的工具调用 ID（follow-up 工具结果用，1:1 iOS ChatMessage.tool_call_id）；null 时省略。 */
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

/**
 * 请求侧工具调用消息体（assistant 回合回传的 tool_calls 数组元素，1:1 iOS `LLMService.ToolCallMsg`）。
 * 与流式增量 [StreamToolCallDeltaDto] 不同：无 index，id/type/function 均完整。
 * [type] 无 Kotlin 默认值——encodeDefaults=false 会丢弃等于默认值的字段，故 "function" 由调用方显式传。
 */
@Serializable
data class RequestToolCallDto(
    val id: String,
    val type: String, // "function"
    val function: RequestToolCallFunctionDto,
)

/** 请求侧工具调用函数信息（1:1 iOS `LLMService.ToolCallFunction`）。 */
@Serializable
data class RequestToolCallFunctionDto(
    val name: String,
    val arguments: String,
)

@Serializable
data class ReasoningParamDto(
    val effort: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val exclude: Boolean? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class ThinkingParamDto(
    val type: String,
    @SerialName("budget_tokens") val budgetTokens: Int? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
data class ResponseFormatDto(val type: String)

@Serializable
data class StreamOptionsDto(@SerialName("include_usage") val includeUsage: Boolean)

// MARK: - 工具调用定义（请求侧，1:1 iOS LLMService.ToolDefinition 等）
// type 字段无 Kotlin 默认值——encodeDefaults=false 会丢弃等于默认值的字段，故 "function"/"object" 由调用方显式传；
// 可空字段（description/enum/required）经 explicitNulls=false 在 null 时自动省略（= iOS encodeIfPresent）。

@Serializable
data class ToolDefinitionDto(
    val type: String, // "function"
    val function: FunctionDefinitionDto,
)

@Serializable
data class FunctionDefinitionDto(
    val name: String,
    val description: String,
    val parameters: FunctionParametersDto,
)

@Serializable
data class FunctionParametersDto(
    val type: String, // "object"
    val properties: Map<String, ParameterPropertyDto>,
    val required: List<String>? = null,
)

/**
 * 工具参数属性 schema（JSON Schema 子集，1:1 iOS InputSchema 风格）。**可递归**——支持嵌套对象 / 对象数组：
 * - [items]：当 [type] = "array" 时，描述数组元素的 schema（递归自引用）。
 * - [properties] / [required]：当 [type] = "object" 时，描述嵌套对象的字段 schema 与必填项（递归自引用）。
 *
 * ④（嵌套表单 schema）前置基建：当前 3 个工具全是扁平参数、[items]/[properties]/[required] 恒 null →
 * 经 explicitNulls=false 自动省略 → 序列化**字节不变**（Phase 0-2 golden 看门）。kotlinx.serialization
 * 原生支持递归 @Serializable data class 自引用（见 NestedSchemaDtoTest 往返证明）。
 * 新字段一律追加在 [enumValues] 之后 → 既有按位构造（`ParameterPropertyDto("string", "...", enumList)`）零改。
 */
@Serializable
data class ParameterPropertyDto(
    val type: String,
    val description: String? = null,
    @SerialName("enum") val enumValues: List<String>? = null,
    /** 数组元素 schema（type="array"·对应 JSON schema 的 `items`）。 */
    val items: ParameterPropertyDto? = null,
    /** 嵌套对象的属性 schema（type="object"·对应 JSON schema 的 `properties`）。 */
    val properties: Map<String, ParameterPropertyDto>? = null,
    /** 嵌套对象的必填字段（type="object"·对应 JSON schema 的 `required`）。 */
    val required: List<String>? = null,
)

// MARK: - Streaming response

@Serializable
data class StreamChunkDto(
    val choices: List<StreamChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
)

@Serializable
data class StreamChoiceDto(
    val delta: StreamDeltaDto = StreamDeltaDto(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class StreamDeltaDto(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    // OpenRouter / LiteLLM / some relays use this field name
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<StreamToolCallDeltaDto>? = null,
)

/**
 * 流式工具调用增量（SSE delta 内 tool_calls 数组元素，1:1 iOS StreamToolCallDelta）。
 * [index] 必须可空：OpenAI/DeepSeek 恒带，但部分中转省略它；若设为必填 `Int`，缺 index 会让**整个
 * [StreamChunkDto]** 解码抛 MissingFieldException → 在 `LlmClient.parseSseLine` 被吞成 Skip → 该块连带
 * 正常 content 一起被静默丢弃（回复无声变短）。可空后由 [ToolCallAccumulator] 兜底归并、绝不丢片。
 */
@Serializable
data class StreamToolCallDeltaDto(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: StreamFunctionDeltaDto? = null,
)

/** 流式函数调用增量（1:1 iOS StreamFunctionDelta）。 */
@Serializable
data class StreamFunctionDeltaDto(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    // DeepSeek cache accounting (hit price ~1/10 of miss).
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: CompletionTokensDetailsDto? = null,
)

@Serializable
data class CompletionTokensDetailsDto(
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null,
)

// MARK: - Non-streaming response

@Serializable
data class CompletionResponseDto(
    val choices: List<CompletionChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
)

@Serializable
data class CompletionChoiceDto(
    val message: CompletionMessageDto,
    /** 停止原因（"stop"=自然写完 / "length"=撞 max_tokens 被掐断）——升额重试与记忆摘要拒收半份结果的事实信号，别靠猜。 */
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class CompletionMessageDto(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
)

// MARK: - Error JSON embedded in an SSE stream or response body

@Serializable
data class StreamErrorResponseDto(val error: StreamErrorDetailDto? = null)

@Serializable
data class StreamErrorDetailDto(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
