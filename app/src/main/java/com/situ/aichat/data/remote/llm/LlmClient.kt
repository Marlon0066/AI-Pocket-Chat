package com.situ.aichat.data.remote.llm

import android.util.Log
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.redirectDeprecatedModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.pow

/**
 * OpenAI-compatible chat client with SSE streaming. Faithful port of the iOS `LLMService`:
 * single request shape for all providers + small per-provider tweaks (headers, MiniMax temp,
 * response_format / stream_options filtering, thinking payload). Connection phase retries on
 * 429/5xx/network errors (respecting Retry-After); the SSE receive phase does not retry.
 */
class LlmClient(
    private val baseClient: OkHttpClient,
    private val json: Json,
) {

    /** Stream visible content + thinking deltas as they arrive. */
    fun streamChat(
        messages: List<ChatMessageDto>,
        config: ApiConfigValues,
        temperature: Double? = null,
        maxTokens: Int? = null,
        responseFormat: ResponseFormatDto? = null,
        /** 结构化工具调用定义；非 null 时随请求下发 tools，流里会产出 [StreamToken.ToolCallDelta]（双轨结构化路）。 */
        tools: List<ToolDefinitionDto>? = null,
        idleTimeoutSec: Long = if (config.isThinkingModel) THINKING_SSE_IDLE_TIMEOUT_SEC else SSE_IDLE_TIMEOUT_SEC,
        /** 末帧 usage 回调（批 D·上下文日志）：provider 返回 token 用量时回传，供记录器存精确值；不影响流内容。 */
        onUsage: ((UsageDto) -> Unit)? = null,
        /** 末帧 finish_reason 回调（卷一 V8·可选尾参默认 null 零波及）："length"/"max_tokens" = 输出撞上限被掐断。每个非空值都会回调，调用方保留最后一个。 */
        onFinishReason: ((String?) -> Unit)? = null,
        /** SSE 活性回调（通话看门狗喂狗·可选尾参默认 null 零波及）：每读到一行原始 SSE
         *  （数据行、`:` 开头的 keep-alive 注释行、空分隔行）回调一次；IO 线程调用，
         *  实现须线程安全、不得阻塞、不得抛异常。 */
        onSseLine: (() -> Unit)? = null,
    ): Flow<StreamToken> = flow {
        val bodyJson = buildRequestJson(messages, config, stream = true, temperature, maxTokens, responseFormat, tools)
        val client = baseClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS) // no overall cap; streams can be long
            .readTimeout(idleTimeoutSec, TimeUnit.SECONDS) // per-read idle guard
            .build()

        // 首调 400 自愈（2026-07-27 超长章档捆绑 + 2026-08-31 推理系参数方言 + 2026-09-01 温度方言）：
        // 分类见 [firstCall400RetryPlan]——换名（推理系拒收 max_tokens）> 降额（我方值超服务商硬顶，
        // clamp SAFE_RETRY_MAX_TOKENS）> 去温度（方言不认 temperature）> 不重试。各类重试各恰一次
        // （catch 只包首调，重试自身的异常自然上抛，故互不链式）；其余 400（模型名错等）原样抛。
        val response = try {
            connectWithRetry(client, config, bodyJson)
        } catch (e: LlmError.Http) {
            val sentTemperature = resolveEffectiveTemperature(temperature, config.providerType == ApiProviderType.MINIMAX, config.isThinkingModel)
            when (firstCall400RetryPlan(e, maxTokens, sentTemperature)) {
                FirstCall400RetryPlan.SWAP_PARAM_NAME -> {
                    Log.w(TAG, "流式首调 max_tokens 参数名被拒（推理系方言），换 max_completion_tokens 同值重试一次")
                    connectWithRetry(client, config, buildRequestJson(messages, config, stream = true, temperature, maxTokens, responseFormat, tools, useMaxCompletionTokens = true))
                }
                FirstCall400RetryPlan.CLAMP -> {
                    Log.w(TAG, "流式首调 maxTokens=$maxTokens 被 400 拒（超服务商硬顶），clamp $SAFE_RETRY_MAX_TOKENS 重试一次")
                    connectWithRetry(client, config, buildRequestJson(messages, config, stream = true, temperature, SAFE_RETRY_MAX_TOKENS, responseFormat, tools))
                }
                FirstCall400RetryPlan.DROP_TEMPERATURE -> {
                    Log.w(TAG, "流式首调 temperature 被 400 拒（方言不认），去温度重试一次")
                    connectWithRetry(client, config, buildRequestJson(messages, config, stream = true, temperature, maxTokens, responseFormat, tools, dropTemperature = true))
                }
                FirstCall400RetryPlan.NONE -> throw e
            }
        }
        response.use { resp ->
            val source = resp.body.source()
            // M03 内联思考标签实时剥离：content 经 ThinkTagParser 过滤 <think>，思考内容转 Reasoning，不漏进气泡。
            val thinkParser = ThinkTagParser()
            // 批3 3-9：取消即断开底层连接——readUtf8Line() 阻塞读不响应协程取消（逐行 ensureActive 只在行间生效），
            // 旧行为=停止生成后 IO 线程与连接滞留最长一个空闲超时（45s/120s）。watcher 在外层协程被取消时 close 响应
            // → 阻塞读立刻抛 IOException 解锁；正常/异常收尾由 finally 撤销 watcher（scope 仍活跃 → 不误关）。
            coroutineScope {
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!this@coroutineScope.isActive) runCatching { resp.close() }
                    }
                }
                try {
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = source.readUtf8Line() ?: break
                        onSseLine?.invoke()
                        when (val result = parseSseLine(line)) {
                            SseResult.Skip -> Unit
                            SseResult.Done -> break
                            is SseResult.Chunk -> {
                                // Usage rides the final chunk (often choice-less) → log before the delta guard.
                                result.chunk.usage?.let {
                                    UsageLogger.log(it, config.providerType, config.modelName)
                                    onUsage?.invoke(it)
                                }
                                // finish_reason 常骑在「delta 空」的末帧上 → 必须在下面的 delta 卫兵之前回调，否则永远收不到。
                                result.chunk.choices.firstOrNull()?.finishReason?.takeIf { it.isNotEmpty() }?.let { onFinishReason?.invoke(it) }
                                val delta = result.chunk.choices.firstOrNull()?.delta ?: continue
                                // 优先 reasoning_content（DeepSeek）回退 reasoning（OpenRouter）——已是思考字段，直发。
                                (delta.reasoningContent ?: delta.reasoning)?.takeIf { it.isNotEmpty() }
                                    ?.let { emit(StreamToken.Reasoning(it)) }
                                // content 可能内联 <think> 标签（开源模型）→ 经解析器切分。
                                delta.content?.takeIf { it.isNotEmpty() }?.let { content ->
                                    thinkParser.parse(content).forEach { emit(it) }
                                }
                                // 工具调用增量（仅在请求带 tools 时出现，1:1 iOS toolCallDelta yield）。
                                delta.toolCalls?.forEach { tc ->
                                    emit(
                                        StreamToken.ToolCallDelta(
                                            ToolCallChunk(
                                                index = tc.index,
                                                id = tc.id,
                                                functionName = tc.function?.name,
                                                argumentChunk = tc.function?.arguments,
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    // 流结束，清空 parser 缓冲区
                    thinkParser.flush().forEach { emit(it) }
                } catch (e: SocketTimeoutException) {
                    throw LlmError.Timeout
                } catch (e: IOException) {
                    // 接收阶段不重试：wifi 掉线 / 服务端中途关连接 → 原样抛出，回复会被截断。
                    // 记一笔（仅 host + 异常信息）让「网络中断截断」可与「模型自然停」区分，绝不记内容。
                    Log.w(TAG, "SSE 流中断 host=${hostOf(config.baseUrl)}: ${e.message}")
                    throw e
                } finally {
                    watcher.cancel()
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Non-streaming completion (used by memory summary / background tasks).
     *
     * 撞限升额重试（2026-07-11 用户拍板）：`finish_reason=length` 是「输出撞 max_tokens 被掐断」的事实信号
     * （思考模型思考+正文同吃预算，光思考就可能耗尽写死的上限）。仅当上限是**我们传的**（[maxTokens] 非 null）
     * 才能升——升 [LENGTH_ESCALATION_FACTOR] 倍重试一次；再截断则原样返回，交下游剥标签/剥空守卫处理。
     * 普通模型永不触发，零额外成本。
     */
    suspend fun completion(
        messages: List<ChatMessageDto>,
        config: ApiConfigValues,
        temperature: Double? = null,
        maxTokens: Int? = null,
        responseFormat: ResponseFormatDto? = null,
        /** usage 回调（批 D·上下文日志）：响应带 token 用量时回传，供记录器存精确值。 */
        onUsage: ((UsageDto) -> Unit)? = null,
        /** finish_reason 回调（记忆护栏 G2·可选尾参默认 null 零波及）："length" = 输出被掐断，调用方据此拒收半份结果。 */
        onFinishReason: ((String?) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        // 首调 400 自愈（与 streamChat 同款分类 [maxTokensRetryPlan]）：clamp 后升额基数随之收窄为生效值——
        // 否则下方升额重试乘回超顶原值，必然再 400 白烧一轮；换名命中则 useNewName 贯穿本次调用的后续 attempt
        // （升额仍用老参数名必然白烧一轮）。
        var effectiveMaxTokens = maxTokens
        var useNewName = false
        var dropTemp = false
        val first = try {
            completionAttempt(messages, config, temperature, maxTokens, responseFormat, onUsage)
        } catch (e: LlmError.Http) {
            val sentTemperature = resolveEffectiveTemperature(temperature, config.providerType == ApiProviderType.MINIMAX, config.isThinkingModel)
            when (firstCall400RetryPlan(e, maxTokens, sentTemperature)) {
                FirstCall400RetryPlan.SWAP_PARAM_NAME -> {
                    Log.w(TAG, "非流式首调 max_tokens 参数名被拒（推理系方言），换 max_completion_tokens 同值重试一次")
                    useNewName = true
                    completionAttempt(messages, config, temperature, maxTokens, responseFormat, onUsage, useMaxCompletionTokens = true)
                }
                FirstCall400RetryPlan.CLAMP -> {
                    Log.w(TAG, "非流式首调 maxTokens=$maxTokens 被 400 拒（超服务商硬顶），clamp $SAFE_RETRY_MAX_TOKENS 重试一次")
                    effectiveMaxTokens = SAFE_RETRY_MAX_TOKENS
                    completionAttempt(messages, config, temperature, SAFE_RETRY_MAX_TOKENS, responseFormat, onUsage)
                }
                FirstCall400RetryPlan.DROP_TEMPERATURE -> {
                    Log.w(TAG, "非流式首调 temperature 被 400 拒（方言不认），去温度重试一次")
                    dropTemp = true
                    completionAttempt(messages, config, temperature, maxTokens, responseFormat, onUsage, dropTemperature = true)
                }
                FirstCall400RetryPlan.NONE -> throw e
            }
        }
        val escalationBase = effectiveMaxTokens
        val outcome = if (escalationBase != null && isLengthTruncated(first.finishReason)) {
            // 仅记上限与信号，绝不记内容。
            Log.w(TAG, "非流式输出撞 maxTokens=$escalationBase（finish_reason=${first.finishReason}），升额 ×$LENGTH_ESCALATION_FACTOR 重试一次")
            try {
                completionAttempt(
                    messages, config, temperature, escalationBase * LENGTH_ESCALATION_FACTOR, responseFormat, onUsage,
                    useMaxCompletionTokens = useNewName, dropTemperature = dropTemp,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 升额可能超服务商自身输出硬顶被拒（如 400 invalid max_tokens）——退回首轮截断结果而非整调用报错
                // （半份到手不白丢），finish_reason 如实保持撞限信号交调用方守卫。
                Log.w(TAG, "升额重试失败（${httpCodeOf(e)}），退回首轮截断结果")
                first
            }
        } else {
            first
        }
        // 合并语义（升额重试 × 记忆护栏 G2）：回调**最终一次尝试**的 finish_reason——升额后仍截断也如实上报，调用方各自守卫。
        onFinishReason?.invoke(outcome.finishReason)
        outcome.content
    }

    private class CompletionOutcome(val content: String, val finishReason: String?)

    /** 单次非流式请求（HTTP 429/5xx/网络错误的既有退避重试在内层，与撞限升额是两个正交维度）。 */
    private suspend fun completionAttempt(
        messages: List<ChatMessageDto>,
        config: ApiConfigValues,
        temperature: Double?,
        maxTokens: Int?,
        responseFormat: ResponseFormatDto?,
        onUsage: ((UsageDto) -> Unit)?,
        useMaxCompletionTokens: Boolean = false,
        dropTemperature: Boolean = false,
    ): CompletionOutcome {
        val bodyJson = buildRequestJson(
            messages, config, stream = false, temperature, maxTokens, responseFormat,
            useMaxCompletionTokens = useMaxCompletionTokens, dropTemperature = dropTemperature,
        )
        val client = baseClient.newBuilder()
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
        val url = buildUrl(config.baseUrl)

        var lastError: Exception = LlmError.InvalidResponse
        var retryAfterMs: Long? = null
        for (attempt in 0 until MAX_RETRIES) {
            if (attempt > 0) {
                // 决定重试：仅记 attempt / code / retryAfter（绝不记 prompt / completion / body）。
                Log.w(TAG, "LLM 重试 attempt=$attempt/${MAX_RETRIES - 1} code=${httpCodeOf(lastError)} retryAfterMs=$retryAfterMs")
                delay(retryAfterMs ?: backoffMs(attempt))
                retryAfterMs = null
            }
            try {
                val request = buildPostRequest(url, config, bodyJson)
                client.newCall(request).execute().use { resp ->
                    when {
                        resp.code == 200 -> {
                            val raw = resp.body.string()
                            val decoded = runCatching {
                                json.decodeFromString(CompletionResponseDto.serializer(), raw)
                            }.getOrNull() ?: throw LlmError.DecodingError
                            decoded.usage?.let {
                                UsageLogger.log(it, config.providerType, config.modelName)
                                onUsage?.invoke(it)
                            }
                            val choice = decoded.choices.firstOrNull() ?: throw LlmError.DecodingError
                            return CompletionOutcome(
                                content = choice.message.content ?: throw LlmError.DecodingError,
                                finishReason = choice.finishReason,
                            )
                        }
                        resp.code == 429 || resp.code in 500..599 -> {
                            if (resp.code == 429) retryAfterMs = parseRetryAfterMs(resp)
                            lastError = LlmError.Http(resp.code, readErrorBody(resp))
                        }
                        else -> throw LlmError.Http(resp.code, readErrorBody(resp))
                    }
                }
            } catch (e: SocketTimeoutException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError
    }

    // MARK: - Connection (streaming) with retry

    private suspend fun connectWithRetry(
        client: OkHttpClient,
        config: ApiConfigValues,
        bodyJson: String,
    ): Response {
        val url = buildUrl(config.baseUrl)
        var lastError: Exception = LlmError.InvalidResponse
        var retryAfterMs: Long? = null
        for (attempt in 0 until MAX_RETRIES) {
            if (attempt > 0) {
                // 决定重试：仅记 attempt / code / retryAfter（绝不记 prompt / completion / body）。
                Log.w(TAG, "LLM 重试 attempt=$attempt/${MAX_RETRIES - 1} code=${httpCodeOf(lastError)} retryAfterMs=$retryAfterMs")
                delay(retryAfterMs ?: backoffMs(attempt))
                retryAfterMs = null
            }
            try {
                val request = buildPostRequest(url, config, bodyJson)
                val response = client.newCall(request).execute()
                when {
                    response.code == 200 -> return response
                    response.code == 429 || response.code in 500..599 -> {
                        if (response.code == 429) retryAfterMs = parseRetryAfterMs(response)
                        lastError = LlmError.Http(response.code, readErrorBody(response))
                        response.close()
                    }
                    else -> {
                        val err = LlmError.Http(response.code, readErrorBody(response))
                        response.close()
                        throw err
                    }
                }
            } catch (e: SocketTimeoutException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError
    }

    // MARK: - Request building

    private fun buildPostRequest(url: String, config: ApiConfigValues, bodyJson: String): Request {
        val builder = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON_MEDIA))
        for ((key, value) in LlmHttp.authHeaders(config)) {
            builder.addHeader(key, value)
        }
        return builder.build()
    }

    private fun buildRequestJson(
        messages: List<ChatMessageDto>,
        config: ApiConfigValues,
        stream: Boolean,
        temperature: Double?,
        maxTokens: Int?,
        responseFormat: ResponseFormatDto?,
        tools: List<ToolDefinitionDto>? = null,
        /** true = 走推理系方言：同值发 `max_completion_tokens`，不发 `max_tokens`（两者恒互斥）。 */
        useMaxCompletionTokens: Boolean = false,
        /** true = 本发彻底不带 temperature（图纸件②：服务商方言不认该参数的 400 自愈重试用）。 */
        dropTemperature: Boolean = false,
    ): String {
        val payload = ReasoningPayloadMapper.payload(config)
        val effectiveResponseFormat = if (config.providerType.supportsResponseFormat) responseFormat else null

        // MiniMax: map temperature 0..2 -> (0, 1.0]; strip reasoning_content (DeepSeek-only field).
        val isMiniMax = config.providerType == ApiProviderType.MINIMAX
        val effectiveTemperature = if (dropTemperature) null else resolveEffectiveTemperature(temperature, isMiniMax, config.isThinkingModel)
        val effectiveMessages = if (isMiniMax) {
            messages.map { if (it.reasoningContent != null) it.copy(reasoningContent = null) else it }
        } else {
            messages
        }

        val streamOptions = if (stream && config.providerType.supportsStreamUsage) {
            StreamOptionsDto(includeUsage = true)
        } else {
            null
        }

        val request = ChatRequestDto(
            // Third-layer defense: auto-redirect deprecated DeepSeek models (e.g. deepseek-chat/
            // reasoner → deepseek-v4-flash). No-op for other providers / non-deprecated models.
            model = redirectDeprecatedModel(config.modelName, config.providerType),
            messages = effectiveMessages,
            stream = stream,
            temperature = effectiveTemperature,
            maxTokens = if (useMaxCompletionTokens) null else maxTokens,
            maxCompletionTokens = if (useMaxCompletionTokens) maxTokens else null,
            tools = tools,
            reasoning = payload.reasoning,
            reasoningEffort = payload.reasoningEffort,
            thinking = payload.thinking,
            responseFormat = effectiveResponseFormat,
            streamOptions = streamOptions,
        )
        return json.encodeToString(ChatRequestDto.serializer(), request)
    }

    // MARK: - URL normalization (shared with capability probes via LlmHttp)

    private fun buildUrl(baseUrl: String): String = LlmHttp.buildChatCompletionsUrl(baseUrl)

    // MARK: - SSE parsing

    private sealed interface SseResult {
        data class Chunk(val chunk: StreamChunkDto) : SseResult
        data object Skip : SseResult
        data object Done : SseResult
    }

    private fun parseSseLine(line: String): SseResult {
        if (line.startsWith(":")) return SseResult.Skip
        if (!line.startsWith("data:")) return SseResult.Skip
        val payload = line.substringAfter("data:").trim()
        if (payload == "[DONE]") return SseResult.Done
        if (payload.isEmpty()) return SseResult.Skip

        // Server-side error JSON: {"error": {"message": "..."}}
        runCatching {
            json.decodeFromString(StreamErrorResponseDto.serializer(), payload)
        }.getOrNull()?.error?.message?.let { throw LlmError.Stream(it) }

        return runCatching {
            SseResult.Chunk(json.decodeFromString(StreamChunkDto.serializer(), payload))
        }.getOrElse {
            // 畸形 data: 块被静默跳过会让回复无声变短；记异常类型（绝不记 payload，可能含内容片段）。
            Log.w(TAG, "SSE 数据块解析失败已跳过: ${it.javaClass.simpleName}")
            SseResult.Skip
        }
    }

    // MARK: - Helpers

    private fun readErrorBody(response: Response, limit: Int = 240): String? {
        val text = runCatching { response.body.string() }.getOrNull()?.trim()
        if (text.isNullOrEmpty()) return null
        return if (text.length <= limit) text else text.take(limit) + "..."
    }

    private fun parseRetryAfterMs(response: Response): Long? {
        val seconds = response.header("Retry-After")?.trim()?.toDoubleOrNull() ?: return null
        val clamped = seconds.coerceIn(0.0, 30.0)
        return (clamped * 1000).toLong()
    }

    /** attempt 1 -> 1s, 2 -> 2s (2^(attempt-1) seconds). */
    private fun backoffMs(attempt: Int): Long = (2.0.pow(attempt - 1) * 1000).toLong()

    /** 仅用于日志：从 baseUrl 取 host（取不到回退占位），绝不含 path/query/凭据。 */
    private fun hostOf(baseUrl: String): String =
        runCatching { URI(baseUrl.trim()).host }.getOrNull() ?: "?"

    /** 仅用于日志：HTTP 错误取状态码，网络异常取类名（绝不含 body）。 */
    private fun httpCodeOf(error: Exception): String = when (error) {
        is LlmError.Http -> error.statusCode.toString()
        else -> error.javaClass.simpleName
    }

    companion object {
        private const val TAG = "LlmClient"
        const val SSE_IDLE_TIMEOUT_SEC = 45L
        const val THINKING_SSE_IDLE_TIMEOUT_SEC = 120L
        private const val MAX_RETRIES = 3
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 撞限升额倍数（与故事正章 preferredCreationMaxTokens 的思考模型 ×3 同口径）。 */
        const val LENGTH_ESCALATION_FACTOR = 3

        /** finish_reason 是否为「撞 max_tokens 被掐断」（标准 "length"；部分中转/兼容层用 "max_tokens"）。 */
        fun isLengthTruncated(finishReason: String?): Boolean =
            finishReason?.lowercase() in setOf("length", "max_tokens")

        /** 首调撞服务商输出硬顶的降额重试上限（主流最低硬顶 = deepseek-chat 类 8192）。 */
        const val SAFE_RETRY_MAX_TOKENS = 8_192

        /**
         * 首调 400 是否为「我方 max_tokens 超服务商输出硬顶」被拒（故事超长章档 12000/思考36000 起会踩到）。
         * 三条件同时成立才认：HTTP 400 ∧ 报文点名 max_tokens ∧ 我方传值确实超过 clamp 目标——
         * 其余 400 与降额无关，重试注定无效，原样抛保持旧行为。
         */
        internal fun isMaxTokensRejection(error: LlmError.Http, requestedMaxTokens: Int?): Boolean =
            error.statusCode == 400 &&
                (requestedMaxTokens ?: 0) > SAFE_RETRY_MAX_TOKENS &&
                error.bodySummary?.contains("max_tokens", ignoreCase = true) == true

        /**
         * 首调 400 是否为「服务商不认 temperature」被拒（图纸 2026-09-01 件②）：400 ∧ 我方确实发了温度
         * （[resolveEffectiveTemperature] 之后非 null）∧ 报文点名 temperature。吃「发出去的值」而非入参——
         * 思考模型本就恒不发温度，撞上点名 temperature 的 400 时重试注定白烧。
         */
        internal fun isTemperatureRejection(error: LlmError.Http, sentTemperature: Double?): Boolean =
            error.statusCode == 400 && sentTemperature != null &&
                error.bodySummary?.contains("temperature", ignoreCase = true) == true

        /**
         * 首调 400 的重试分类。SWAP 优先于 CLAMP：参数名拒收（报文点名 unsupported /
         * max_completion_tokens）的正解是换名保值——clamp 换值不换名注定二连 400。
         * 去温度殿后：同一响应同时点名 max_tokens 与 temperature 时先救上限（更常见且更致命）。
         * 各类自愈互不链式，每次调用每类至多救一次。
         * 谓词复用 [ProbeMaxTokensDialect.isParamRejection]（单源·图纸 A），不写第二份。
         */
        internal fun firstCall400RetryPlan(
            error: LlmError.Http,
            requestedMaxTokens: Int?,
            sentTemperature: Double?,
        ): FirstCall400RetryPlan = when {
            requestedMaxTokens != null &&
                ProbeMaxTokensDialect.isParamRejection(error.statusCode, error.bodySummary) -> FirstCall400RetryPlan.SWAP_PARAM_NAME
            isMaxTokensRejection(error, requestedMaxTokens) -> FirstCall400RetryPlan.CLAMP
            isTemperatureRejection(error, sentTemperature) -> FirstCall400RetryPlan.DROP_TEMPERATURE
            else -> FirstCall400RetryPlan.NONE
        }

        /**
         * 请求体温度决策（CREATIVITY_RELOCATION D-4）：思考模型不发 temperature（DeepSeek 思考模式本就忽略、
         * Anthropic 思考模式发非 1 值会报错——省略最安全），优先于 MiniMax 0..2→(0,1.0] 映射；
         * 非思考模型路径与旧行为字节级一致。
         */
        internal fun resolveEffectiveTemperature(
            temperature: Double?,
            isMiniMax: Boolean,
            isThinkingModel: Boolean,
        ): Double? = when {
            isThinkingModel -> null
            isMiniMax && temperature != null -> max(0.01, temperature / 2.0)
            else -> temperature
        }
    }
}

/** 首调 400 的重试分类：换名（推理系参数方言）> 降额（超服务商硬顶）> 去温度（方言不认 temperature）> 不重试。纯函数便于 T1 真值表。 */
internal enum class FirstCall400RetryPlan { SWAP_PARAM_NAME, CLAMP, DROP_TEMPERATURE, NONE }
