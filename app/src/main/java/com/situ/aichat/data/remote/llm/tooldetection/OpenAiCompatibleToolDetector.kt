package com.situ.aichat.data.remote.llm.tooldetection

import com.situ.aichat.data.model.CapabilitySupportState
import com.situ.aichat.data.model.ToolDetectionResult
import com.situ.aichat.data.model.ToolProtocolFamily
import com.situ.aichat.data.model.ToolSupportLevel
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.LlmHttp
import com.situ.aichat.data.remote.llm.ProbeMaxTokensDialect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

/**
 * OpenAI-compatible tool-calling probe — faithful port of iOS `OpenAICompatibleToolDetector`.
 * Two-step: (1) declare a `test_ping` tool; (2) replay a tool result to verify the follow-up
 * round-trip. 200 on both → full; only the first → basic.
 */
class OpenAiCompatibleToolDetector : ToolCallingDetector {

    override suspend fun detect(
        config: ApiConfigValues,
        isThinkingModel: Boolean,
        client: OkHttpClient,
        json: Json,
    ): ToolDetectionResult {
        return try {
            val url = LlmHttp.buildChatCompletionsUrl(config.baseUrl)
            val headers = LlmHttp.authHeaders(config)

            val basicBody = buildJsonObject {
                put("model", config.modelName)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", "Please call test_ping and then respond with done.")
                    }
                }
                put("stream", false)
                put("max_tokens", 64)
                putJsonArray("tools") { add(testToolDefinition) }
            }

            // 两发都经 ProbeMaxTokensDialect：OpenAI 推理系拒收 `max_tokens` 的 400 会被 statusCode != 200
            // 直接读成「基础工具请求被拒绝」→ 整族误判不支持工具调用。点名才换 `max_completion_tokens`
            // 重发恰一次，其余情形（含退避重试）行为不变。
            val basic = ProbeMaxTokensDialect.postWithFallback<ToolDetectionHttpResponse>(
                body = basicBody,
                statusOf = { it.statusCode },
                bodyTextOf = { it.bodyText },
                send = { sent -> ToolDetectionHttp.jsonRequest(client, url, headers = headers, body = sent, json = json) },
            )
            if (basic.statusCode != 200) {
                return ToolDetectionResult.unsupported(
                    protocolFamily = ToolProtocolFamily.OPENAI_COMPATIBLE,
                    summary = "基础工具请求被拒绝：HTTP ${basic.statusCode}，${ToolDetectionHttp.summarizeBody(basic.bodyText)}",
                )
            }

            val followUpBody = buildJsonObject {
                put("model", config.modelName)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", "Please call test_ping and then answer with done.")
                    }
                    addJsonObject {
                        put("role", "assistant")
                        put("content", "")
                        putJsonArray("tool_calls") {
                            addJsonObject {
                                put("id", "call_test_ping")
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", "test_ping")
                                    put("arguments", "{}")
                                }
                            }
                        }
                    }
                    addJsonObject {
                        put("role", "tool")
                        put("tool_call_id", "call_test_ping")
                        put("content", "pong")
                    }
                }
                put("stream", false)
                put("max_tokens", 64)
            }

            val followUp = ProbeMaxTokensDialect.postWithFallback<ToolDetectionHttpResponse>(
                body = followUpBody,
                statusOf = { it.statusCode },
                bodyTextOf = { it.bodyText },
                send = { sent -> ToolDetectionHttp.jsonRequest(client, url, headers = headers, body = sent, json = json) },
            )

            val level = if (followUp.statusCode == 200) ToolSupportLevel.FULL else ToolSupportLevel.BASIC
            val streamingState =
                if (level == ToolSupportLevel.FULL) CapabilitySupportState.SUPPORTED else CapabilitySupportState.UNKNOWN
            val thinkingState =
                if (isThinkingModel) CapabilitySupportState.UNKNOWN else CapabilitySupportState.UNSUPPORTED
            val summary = if (level == ToolSupportLevel.FULL) {
                "已验证 OpenAI-compatible 工具声明与工具结果续传闭环。"
            } else {
                "已接受 tools 参数，但工具结果续传未验证通过：HTTP ${followUp.statusCode}。"
            }

            ToolDetectionResult(
                level = level,
                protocolFamily = ToolProtocolFamily.OPENAI_COMPATIBLE,
                streamingState = streamingState,
                thinkingState = thinkingState,
                summary = summary,
                checkedAt = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            ToolDetectionResult.unknown(
                protocolFamily = ToolProtocolFamily.OPENAI_COMPATIBLE,
                summary = "OpenAI-compatible 工具检测失败：${e.message ?: e.toString()}",
            )
        }
    }

    companion object {
        val testToolDefinition: JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "test_ping")
                put("description", "Test detector tool.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {}
                }
            }
        }
    }
}
