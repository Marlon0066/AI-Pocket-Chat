package com.situ.aichat.data.remote.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探针参数方言（`max_tokens` → `max_completion_tokens`）的 T1。
 *
 * 为什么值得钉死：OpenAI 推理系拒收 `max_tokens` 的 400，被老探针读成「模型不支持视觉」——
 * 用户可见后果 = 聊天「+」面板的「照片」格该出现却不出现。谓词一旦放宽，撞顶类 400
 * （"max_tokens is too large"）会被误当方言问题重试；一旦收紧，推理系又回到误判。
 *
 * 手法：不碰真 HTTP——`postWithFallback` 的三个出口全是注入 lambda，用计数器 + 报文快照断言。
 * 风格对齐 `ToolCallingDetectorTest` 对 `ToolDetectionHttp.requestWithRetry` 的写法。
 */
class ProbeMaxTokensDialectTest {

    // 真实报文（OpenAI 官方原话，重新打字为字面量而非引用实现常量）
    private val openAiRejection =
        """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. """ +
            """Use 'max_completion_tokens' instead.","type":"invalid_request_error","param":"max_tokens"}}"""
    private val visionRejection =
        """{"error":{"message":"Invalid content type image_url for this model.","type":"invalid_request_error"}}"""
    private val overflowRejection =
        """{"error":{"message":"max_tokens is too large: 200000. This model supports at most 8192 completion tokens."}}"""

    // ---------- 谓词真值表 ----------

    @Test
    fun `点名参数方言的 400 为真`() {
        assertTrue(ProbeMaxTokensDialect.isParamRejection(400, openAiRejection))
        // 只提新参数名（未必带 unsupported 字样）的中转改写报文也算点名
        assertTrue(ProbeMaxTokensDialect.isParamRejection(400, "please use max_completion_tokens"))
        // 大小写不敏感
        assertTrue(ProbeMaxTokensDialect.isParamRejection(400, "UNSUPPORTED PARAMETER: MAX_TOKENS"))
    }

    @Test
    fun `不点名的 400 为假`() {
        assertFalse("内容类型被拒不是参数方言问题", ProbeMaxTokensDialect.isParamRejection(400, visionRejection))
        assertFalse("只说 max_tokens 不说 unsupported 不算", ProbeMaxTokensDialect.isParamRejection(400, "max_tokens must be positive"))
    }

    @Test
    fun `撞顶式 400 为假 不与撞顶自愈打架`() {
        // "max_tokens is too large" 归 LlmClient 的撞顶自愈管；本谓词必须放行，否则两套重试互相踩
        assertFalse(ProbeMaxTokensDialect.isParamRejection(400, overflowRejection))
    }

    @Test
    fun `非 400 状态码与空报文一律为假`() {
        assertFalse(ProbeMaxTokensDialect.isParamRejection(422, openAiRejection))
        assertFalse(ProbeMaxTokensDialect.isParamRejection(200, openAiRejection))
        assertFalse(ProbeMaxTokensDialect.isParamRejection(500, openAiRejection))
        assertFalse(ProbeMaxTokensDialect.isParamRejection(400, null))
    }

    // ---------- swapParam ----------

    @Test
    fun `换名去旧键加新键同值且其余键原样`() {
        val body = buildJsonObject {
            put("model", "gpt-5")
            put("stream", false)
            put("max_tokens", 16)
            put("temperature", 0.1)
        }
        val swapped = ProbeMaxTokensDialect.swapParam(body)
        assertNull("旧键必须去掉", swapped["max_tokens"])
        assertEquals("新键必须同值", JsonPrimitive(16), swapped["max_completion_tokens"])
        assertEquals(JsonPrimitive("gpt-5"), swapped["model"])
        assertEquals(JsonPrimitive(false), swapped["stream"])
        assertEquals(JsonPrimitive(0.1), swapped["temperature"])
        assertEquals("键数不变（换名不增不减）", body.size, swapped.size)
    }

    @Test
    fun `无 max_tokens 键则原样返回`() {
        val body = buildJsonObject {
            put("model", "gpt-5")
            put("stream", false)
        }
        assertSame(body, ProbeMaxTokensDialect.swapParam(body))
    }

    // ---------- postWithFallback 编排 ----------

    private data class Resp(val code: Int, val body: String?)

    private fun probeBody(): JsonObject = buildJsonObject {
        put("model", "gpt-5")
        put("max_tokens", 16)
    }

    private suspend fun run(vararg responses: Resp): Pair<Resp, List<JsonObject>> {
        val queue = ArrayDeque(responses.toList())
        val sent = mutableListOf<JsonObject>()
        val result = ProbeMaxTokensDialect.postWithFallback(
            body = probeBody(),
            statusOf = Resp::code,
            bodyTextOf = Resp::body,
            send = { body ->
                sent += body
                queue.removeFirst()
            },
        )
        return result to sent
    }

    @Test
    fun `首发成功只发一次`() = runBlocking {
        val (result, sent) = run(Resp(200, """{"choices":[{"message":{"content":"ok"}}]}"""))
        assertEquals(200, result.code)
        assertEquals(1, sent.size)
        assertEquals("首发必须用老参数名", JsonPrimitive(16), sent[0]["max_tokens"])
    }

    @Test
    fun `点名 400 恰重发一次且第二发已换名`() = runBlocking {
        val (result, sent) = run(
            Resp(400, openAiRejection),
            Resp(200, """{"choices":[{"message":{"content":"ok"}}]}"""),
        )
        assertEquals("必须返回第二发的结果", 200, result.code)
        assertEquals(2, sent.size)
        assertNull("第二发不许再带旧键", sent[1]["max_tokens"])
        assertEquals(JsonPrimitive(16), sent[1]["max_completion_tokens"])
    }

    @Test
    fun `不点名的 400 不重试`() = runBlocking {
        val (result, sent) = run(Resp(400, visionRejection))
        assertEquals(400, result.code)
        assertEquals("模型真不支持视觉：一次就该判 0，不浪费第二发", 1, sent.size)
    }

    @Test
    fun `重试后仍点名也不三试`() = runBlocking {
        val (result, sent) = run(
            Resp(400, openAiRejection),
            Resp(400, openAiRejection),
        )
        assertEquals(400, result.code)
        assertEquals("恰两次——第二次仍拒说明真不支持，判 0", 2, sent.size)
    }
}
