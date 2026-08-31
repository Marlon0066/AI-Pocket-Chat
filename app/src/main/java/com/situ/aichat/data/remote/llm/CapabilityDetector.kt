package com.situ.aichat.data.remote.llm

import android.util.Log
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.ToolDetectionResult
import com.situ.aichat.data.remote.llm.tooldetection.ToolCallingDetectorFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Runtime LLM capability probes — faithful port of iOS `LLMService+Detection.swift`.
 * Each probe sends a minimal request and maps the outcome to the detection convention used by
 * [com.situ.aichat.data.local.entity.ApiConfigEntity]: -1 = undetermined, 0 = unsupported,
 * 1 = supported (tool detection returns a [ToolDetectionResult]).
 *
 * Tool-calling detection delegates to per-protocol detectors (see the `tooldetection` package).
 */
class CapabilityDetector(
    private val client: OkHttpClient,
    private val json: Json,
) {

    // MARK: - Thinking model detection

    /**
     * 1 = thinking signal detected, 0 = normal content but no thinking signal, -1 = undetermined.
     * Tries streaming first, then falls back to non-stream (covers relays without streaming).
     */
    suspend fun detectThinkingModelType(config: ApiConfigValues): Int {
        val streamResult = detectThinkingViaStream(config)
        if (streamResult != -1) return streamResult
        return detectThinkingNonStream(config)
    }

    private suspend fun detectThinkingViaStream(config: ApiConfigValues): Int = withContext(Dispatchers.IO) {
        try {
            val url = LlmHttp.buildChatCompletionsUrl(config.baseUrl)
            val req = ChatRequestDto(
                model = config.modelName,
                messages = listOf(ChatMessageDto(role = "user", content = THINKING_PROMPT)),
                stream = true,
                temperature = 0.1,
                maxTokens = 32,
            )
            val bodyJson = json.encodeToString(ChatRequestDto.serializer(), req)
            val streamClient = client.newBuilder()
                .callTimeout(0, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            streamClient.newCall(postRequest(url, config, bodyJson)).execute().use { resp ->
                if (resp.code != 200) {
                    // 推理系方言（gpt-5.x / o 系拒收 max_tokens）：非 200 才读错误体（此时是普通 JSON 非 SSE，安全），
                    // 点名则以**同值** max_completion_tokens 重发恰一次；第二发仍非 200 → -1，不点名亦照旧 -1。
                    val errorBody = runCatching { resp.body.string() }.getOrNull()
                    if (!ProbeMaxTokensDialect.isParamRejection(resp.code, errorBody)) return@withContext -1
                    val retryJson = json.encodeToString(
                        ChatRequestDto.serializer(),
                        req.copy(maxTokens = null, maxCompletionTokens = 32),
                    )
                    return@withContext streamClient.newCall(postRequest(url, config, retryJson)).execute().use { retry ->
                        if (retry.code != 200) -1 else readThinkingStream(retry)
                    }
                }
                readThinkingStream(resp)
            }
        } catch (e: Exception) {
            // -1（未定）会被探测当作「未检出」，但也可能只是临时网络错误 → 记一笔便于区分（无 API key/无 body）。
            Log.w(TAG, "能力探测失败·thinking-stream model=${config.modelName} base=${config.baseUrl}: ${e.message}")
            -1
        }
    }

    /**
     * 消费 thinking 探针的 SSE 流并判定：1 = 见到思考信号 / 0 = 有正文但无思考信号 / -1 = 未判定。
     * 抽成独立函数只为让方言换名的第二发走同一份判定（**只搬不改**·逐行原样），不改任何行为。
     */
    private suspend fun readThinkingStream(resp: Response): Int {
        val source = resp.body.source()
        var sawContent = false
        val parser = ThinkTagParser()
        while (true) {
            coroutineContext.ensureActive()
            val line = source.readUtf8Line() ?: break
            val payload = sseData(line) ?: continue
            if (payload == "[DONE]") break
            val chunk = runCatching {
                json.decodeFromString(StreamChunkDto.serializer(), payload)
            }.getOrNull() ?: continue
            val delta = chunk.choices.firstOrNull()?.delta ?: continue
            val reasoning = delta.reasoningContent ?: delta.reasoning
            if (!reasoning.isNullOrEmpty()) return 1
            val content = delta.content
            if (!content.isNullOrEmpty()) {
                sawContent = true
                if (parser.parse(content).any { it is StreamToken.Reasoning }) return 1
            }
        }
        if (parser.flush().any { it is StreamToken.Reasoning }) return 1
        return if (sawContent) 0 else -1
    }

    private suspend fun detectThinkingNonStream(config: ApiConfigValues): Int {
        return try {
            val url = LlmHttp.buildChatCompletionsUrl(config.baseUrl)
            val req = ChatRequestDto(
                model = config.modelName,
                messages = listOf(ChatMessageDto(role = "user", content = THINKING_PROMPT)),
                stream = false,
                temperature = 0.1,
                maxTokens = 64,
            )
            val firstTry = post(config, url, json.encodeToString(ChatRequestDto.serializer(), req), timeoutSec = 20)
            // 推理系方言：点名拒收 max_tokens 则换名**同值**重发恰一次，第二发的结果走既有判定；
            // 不点名则原样用首发结果 → 其余路径字节不变。
            val (code, body) = if (ProbeMaxTokensDialect.isParamRejection(firstTry.first, firstTry.second)) {
                val retryJson = json.encodeToString(
                    ChatRequestDto.serializer(),
                    req.copy(maxTokens = null, maxCompletionTokens = 64),
                )
                post(config, url, retryJson, timeoutSec = 20)
            } else {
                firstTry
            }
            if (code != 200 || body == null) return -1
            val decoded = runCatching {
                json.decodeFromString(CompletionResponseDto.serializer(), body)
            }.getOrNull() ?: return -1
            val message = decoded.choices.firstOrNull()?.message ?: return -1
            val reasoning = message.reasoningContent ?: message.reasoning
            if (!reasoning.isNullOrEmpty()) return 1
            val content = message.content
            if (content != null) {
                val lower = content.lowercase()
                if (lower.contains("<think>") || lower.contains("<thinking>") ||
                    lower.contains("<|think|>") || lower.contains("<thought>") ||
                    lower.contains("<reasoning>")
                ) {
                    return 1
                }
                if (content.trim().isNotEmpty()) return 0
            }
            -1
        } catch (e: Exception) {
            // 同上：网络错误与「模型无思考能力」都落 -1，记一笔便于区分（无 API key/无 body）。
            Log.w(TAG, "能力探测失败·thinking-nonstream model=${config.modelName} base=${config.baseUrl}: ${e.message}")
            -1
        }
    }

    // MARK: - Vision detection

    /**
     * Sends a 1x1 PNG (image_url part) and checks the status: 200 with content → 1,
     * 400/422 → 0, else → -1。
     *
     * **不再对 DeepSeek / MiniMax 硬编码「不支持」**：DeepSeek 已有视觉模型
     * （`deepseek-v4-flash-vision-exp`）、MiniMax-M3 亦支持图片输入；且这两档常被用户拿来接中转站，
     * 硬编码会把真正支持视觉的端点强标成不支持。统一按真实探测结果说话。
     */
    suspend fun detectVisionSupport(config: ApiConfigValues): Int {
        val body = buildJsonObject {
            put("model", config.modelName)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "describe")
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", TINY_PNG_DATA_URL) }
                        }
                    }
                }
            }
            put("stream", false)
            put("max_tokens", PROBE_MAX_TOKENS)
        }
        return probeMultimodal(config, body)
    }

    // MARK: - Audio input detection

    /**
     * Sends a minimal silent WAV (input_audio part) and checks the status the same way as vision.
     * DeepSeek / MiniMax / Anthropic skip the probe.
     *
     * **ANTHROPIC 恒返 0（2026-08-31 加）**：Claude **原生 API 就没有「音频输入」这个内容类型**，
     * 而官方 OpenAI 兼容层对 `input_audio` 的处理是逐字段支持表里明文的「Ignored」——**静默剥掉音频
     * 却照样返 200**。探针那个 200 因此是**假阳性**：会把「不支持」错标成「支持」，之后每条语音消息
     * 都白传一趟音频、模型却什么都没听见。与当年拆掉 DeepSeek / MiniMax 的视觉硬编码不是一回事
     * （那两家后来真出了多模态），这里是协议层压根没有这项输入。
     * **拆除条件**：Anthropic（原生 API 或其兼容层）开始真正接收音频输入之日，删掉这一档即可。
     */
    suspend fun detectAudioInputSupport(config: ApiConfigValues): Int {
        if (config.providerType == ApiProviderType.DEEPSEEK ||
            config.providerType == ApiProviderType.MINIMAX ||
            config.providerType == ApiProviderType.ANTHROPIC
        ) {
            return 0
        }
        val body = buildJsonObject {
            put("model", config.modelName)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "请简单确认你收到了这段音频。")
                        }
                        addJsonObject {
                            put("type", "input_audio")
                            putJsonObject("input_audio") {
                                put("data", tinyWavBase64())
                                put("format", "wav")
                            }
                        }
                    }
                }
            }
            put("stream", false)
            put("max_tokens", PROBE_MAX_TOKENS)
        }
        return probeMultimodal(config, body)
    }

    /**
     * Shared 200/400-422/else outcome handling for the vision & audio probes.
     *
     * 报文经 [ProbeMaxTokensDialect]：只有服务端 400 **点名** `max_tokens` 参数方言时，才以
     * `max_completion_tokens` 同值重发恰一次（OpenAI 推理系拒收老参数名，否则整族被误判「不支持视觉」）。
     * 其余一切照旧——判定基线（200+content→1 / 400·422→0 / else→-1）与报文字节均不变。
     */
    private suspend fun probeMultimodal(config: ApiConfigValues, body: JsonObject): Int {
        return try {
            val url = LlmHttp.buildChatCompletionsUrl(config.baseUrl)
            val (code, respBody) = ProbeMaxTokensDialect.postWithFallback<Pair<Int, String?>>(
                body = body,
                statusOf = { it.first },
                bodyTextOf = { it.second },
                send = { sent -> post(config, url, json.encodeToString(JsonObject.serializer(), sent), timeoutSec = 15) },
            )
            when (code) {
                200 -> {
                    val content = runCatching {
                        json.decodeFromString(CompletionResponseDto.serializer(), respBody ?: "")
                            .choices.firstOrNull()?.message?.content
                    }.getOrNull()
                    if (!content.isNullOrBlank()) 1 else -1
                }
                400, 422 -> 0
                else -> -1
            }
        } catch (e: Exception) {
            // 视觉/音频共用此探测：网络错误与「模型不支持」都落 -1，记一笔便于区分（无 API key/无 body）。
            Log.w(TAG, "能力探测失败·multimodal(vision/audio) model=${config.modelName} base=${config.baseUrl}: ${e.message}")
            -1
        }
    }

    // MARK: - Tool calling detection

    suspend fun detectToolCallingSupport(
        config: ApiConfigValues,
        isThinkingModel: Boolean,
    ): ToolDetectionResult {
        val detector = ToolCallingDetectorFactory.make(config.providerType)
        return detector.detect(config, isThinkingModel, client, json)
    }

    // MARK: - HTTP helpers

    private fun postRequest(url: String, config: ApiConfigValues, bodyJson: String): Request {
        val builder = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .addHeader("Content-Type", "application/json")
        for ((k, v) in LlmHttp.authHeaders(config)) builder.addHeader(k, v)
        return builder.build()
    }

    private suspend fun post(
        config: ApiConfigValues,
        url: String,
        bodyJson: String,
        timeoutSec: Long,
    ): Pair<Int, String?> = withContext(Dispatchers.IO) {
        val timedClient = client.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).build()
        timedClient.newCall(postRequest(url, config, bodyJson)).execute().use { resp ->
            resp.code to runCatching { resp.body.string() }.getOrNull()
        }
    }

    private fun sseData(line: String): String? {
        if (line.startsWith(":")) return null
        if (!line.startsWith("data:")) return null
        return line.substringAfter("data:").trim().ifEmpty { null }
    }

    /** 16kHz / 16-bit / mono / 1 frame silent WAV, base64-encoded — matches iOS tinyWAVBase64(). */
    private fun tinyWavBase64(): String {
        val wavBytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x26, 0x00, 0x00, 0x00,
            0x57, 0x41, 0x56, 0x45,
            0x66, 0x6D, 0x74, 0x20,
            0x10, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x01, 0x00,
            0x80.toByte(), 0x3E, 0x00, 0x00,
            0x00, 0x7D, 0x00, 0x00,
            0x02, 0x00,
            0x10, 0x00,
            0x64, 0x61, 0x74, 0x61,
            0x02, 0x00, 0x00, 0x00,
            0x00, 0x00,
        )
        return Base64.getEncoder().encodeToString(wavBytes)
    }

    private companion object {
        const val TAG = "CapabilityDetector"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val THINKING_PROMPT = "请先思考，再用一句话回答：13 和 17 的乘积是多少？"
        // 1x1 transparent PNG。**data URL 前缀必须与真实字节一致**（原先照抄 iOS 挂的是
        // image/jpeg，MIME 与内容不符，严格校验的服务商直接 400 → 被误判成「不支持视觉」）。
        const val TINY_PNG_DATA_URL =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        /** 探针输出上限：给 1 会让很多模型返回空串/length 截断 → 落「未判定」，给 16 足够判成功。 */
        const val PROBE_MAX_TOKENS = 16
    }
}
