package com.situ.aichat.data.remote.llm.tooldetection

import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ProbeMaxTokensDialect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * H6 检测可靠性：
 * - #8 探针对齐运行时：运行时只发 OpenAI 形状到 /v1/chat/completions，故原生 Anthropic/Gemini 探针退役，
 *   ANTHROPIC/GEMINI 改走 `OpenAiCompatibleToolDetector`（DeepSeek 保留专属探针）。
 * - #2 瞬时失败重试：`requestWithRetry` 对 429/5xx/网络异常退避重试，2xx 及其它 4xx 立即返回不浪费重试。
 * - 参数方言（2026-08-31）：工具探针两发都经 `ProbeMaxTokensDialect`——OpenAI 推理系拒收 `max_tokens`
 *   的 400 若不认，`statusCode != 200` 会把整族读成「基础工具请求被拒绝」。两套重试互不认识：方言管
 *   参数名（点名才动·恰一次），`requestWithRetry` 管瞬时网络（退避）。
 */
class ToolCallingDetectorTest {

    // ── #8 探针选择 ──

    @Test fun anthropic_and_gemini_route_through_openai_compatible_probe() {
        assertTrue(ToolCallingDetectorFactory.make(ApiProviderType.ANTHROPIC) is OpenAiCompatibleToolDetector)
        assertTrue(ToolCallingDetectorFactory.make(ApiProviderType.GEMINI) is OpenAiCompatibleToolDetector)
    }

    @Test fun deepseek_keeps_own_probe_others_openai_compatible() {
        assertTrue(ToolCallingDetectorFactory.make(ApiProviderType.DEEPSEEK) is DeepSeekToolDetector)
        assertTrue(ToolCallingDetectorFactory.make(ApiProviderType.OPENAI_COMPATIBLE) is OpenAiCompatibleToolDetector)
        assertTrue(ToolCallingDetectorFactory.make(ApiProviderType.OPENROUTER) is OpenAiCompatibleToolDetector)
        assertTrue(ToolCallingDetectorFactory.make(ApiProviderType.MINIMAX) is OpenAiCompatibleToolDetector)
    }

    // ── #2 瞬时失败重试 ──

    private fun resp(code: Int) = ToolDetectionHttpResponse(statusCode = code, bodyText = "")

    @Test fun retries_transient_then_returns_success() = runBlocking {
        val codes = ArrayDeque(listOf(429, 503, 200))
        var calls = 0
        val r = ToolDetectionHttp.requestWithRetry(maxAttempts = 3, backoff = {}) {
            calls++
            resp(codes.removeFirst())
        }
        assertEquals(200, r.statusCode)
        assertEquals(3, calls)
    }

    @Test fun retries_408_and_425_as_transient() = runBlocking {
        // 408 Request Timeout / 425 Too Early 本质瞬时（§8.4 backlog）：与 429/5xx 同样退避重试，
        // 不再被当确定性失败立即落 UNSUPPORTED。
        val codes = ArrayDeque(listOf(408, 425, 200))
        var calls = 0
        val r = ToolDetectionHttp.requestWithRetry(maxAttempts = 3, backoff = {}) {
            calls++
            resp(codes.removeFirst())
        }
        assertEquals(200, r.statusCode)
        assertEquals(3, calls) // 408 → 425 → 200：前两次都退避重试
    }

    @Test fun definitive_4xx_returns_immediately_no_retry() = runBlocking {
        var calls = 0
        val r = ToolDetectionHttp.requestWithRetry(maxAttempts = 3, backoff = {}) {
            calls++
            resp(401)
        }
        assertEquals(401, r.statusCode)
        assertEquals(1, calls) // 401 = 确定性（非 408/425/429），不重试
    }

    @Test fun exhausted_transient_returns_last_response() = runBlocking {
        var calls = 0
        val r = ToolDetectionHttp.requestWithRetry(maxAttempts = 2, backoff = {}) {
            calls++
            resp(500)
        }
        assertEquals(500, r.statusCode)
        assertEquals(2, calls)
    }

    // ── 参数方言（点名才重试）──

    @Test fun param_dialect_rejection_recognized_for_tool_probe() {
        // OpenAI 官方原话（重新打字为字面量，不引实现常量）
        val rejection = """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with """ +
            """this model. Use 'max_completion_tokens' instead.","param":"max_tokens"}}"""
        assertTrue(ProbeMaxTokensDialect.isParamRejection(400, rejection))
        // 工具探针体的 64（§9② 锁定值）换名后必须**同值**搬过去，不许趁机改上限
        val swapped = ProbeMaxTokensDialect.swapParam(
            buildJsonObject {
                put("model", "gpt-5")
                put("max_tokens", 64)
            },
        )
        assertNull(swapped["max_tokens"])
        assertEquals(JsonPrimitive(64), swapped["max_completion_tokens"])
    }

    @Test fun ioexception_retried_then_rethrown_after_exhaustion() = runBlocking {
        var calls = 0
        try {
            ToolDetectionHttp.requestWithRetry(maxAttempts = 2, backoff = {}) {
                calls++
                throw IOException("net")
            }
            fail("应在耗尽重试后抛 IOException")
        } catch (e: IOException) {
            assertEquals(2, calls)
        }
        Unit
    }
}
