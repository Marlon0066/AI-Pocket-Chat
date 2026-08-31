package com.situ.aichat.data.remote.llm

import android.util.Log
import com.situ.aichat.data.model.ApiProviderType
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 推理系参数方言**换名接线**的端到端 T2（图纸 C·§7 T2-8·R1 🟡-1 返工补）。
 *
 * 为什么单钉接线而非分类：`FirstCall400RetryPlanTest` 只证「该不该换名」，`ChatRequestDto` 编码断言只证
 * 「换了名的报文长什么样」——**两者都不会因为某一跳漏传 flag 而变红**。真正会静默坏掉的是接线：
 * 换名那发若忘传 `useMaxCompletionTokens = true`，或升额那发忘了继承 `useNewName`，纯函数测试照样全绿，
 * 而线上表现是「推理系旁路一直 400」或「升额白烧一轮」。故本文件的断言一律落在**真实发出的报文**上。
 *
 * 手法照搬 [LlmMaxTokensClampTest]：OkHttp 拦截器按序吐编排响应并录请求体（零真网络、零新依赖）。
 */
class LlmClientDialectWiringTest {

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "gpt-5",
    )

    private val requests = mutableListOf<String>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        requests.clear()
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    /** OpenAI 官方推理系原话（重新打字为字面量，不引实现常量）。 */
    private val paramRejection =
        """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. """ +
            """Use 'max_completion_tokens' instead.","type":"invalid_request_error","param":"max_tokens"}}"""

    private fun completionJson(content: String, finishReason: String) =
        """{"choices":[{"message":{"content":"$content"},"finish_reason":"$finishReason"}]}"""

    private fun sseBody(content: String) =
        "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"

    private fun clientResponding(vararg responses: Pair<Int, String>): LlmClient {
        val queue = responses.toMutableList()
        val ok = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            requests.add(Buffer().also { req.body?.writeTo(it) }.readUtf8())
            val (code, body) = queue.removeAt(0)
            Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Bad Request")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return LlmClient(ok, Json { ignoreUnknownKeys = true })
    }

    private fun msgs() = listOf(ChatMessageDto(role = "user", content = "写条通知"))

    /** 换名档的报文断言（规格 §3.1：同值搬到新键，老键彻底消失）。 */
    private fun assertSwapped(body: String, value: Int) {
        assertTrue("换名那发应带 max_completion_tokens=$value：$body", body.contains("\"max_completion_tokens\":$value"))
        assertFalse("换名那发绝不能再带 max_tokens：$body", body.contains("\"max_tokens\""))
    }

    @Test
    fun `非流式_首发400点名参数方言_换名同值重发一次并采用第二发结果`() = runBlocking {
        // 旁路典型值 1000（通知模板）——旧版三条件（>8192）压根不触发，此路曾直连硬失败。
        val client = clientResponding(
            400 to paramRejection,
            200 to completionJson("提醒文案", "stop"),
        )
        val result = client.completion(messages = msgs(), config = config, maxTokens = 1_000)
        assertEquals("返回值必须来自第二发", "提醒文案", result)
        assertEquals("首发 + 换名重发，恰两次", 2, requests.size)
        assertTrue(requests[0], requests[0].contains("\"max_tokens\":1000"))
        assertSwapped(requests[1], 1_000)
    }

    @Test
    fun `流式_首发400点名参数方言_换名同值重发一次后正常收流`() = runBlocking {
        val client = clientResponding(
            400 to paramRejection,
            200 to sseBody("她推开门"),
        )
        val contents = client.streamChat(messages = msgs(), config = config, maxTokens = 1_000)
            .filterIsInstance<StreamToken.Content>().toList()
        assertEquals("她推开门", contents.joinToString("") { it.text })
        assertEquals(2, requests.size)
        assertTrue(requests[0], requests[0].contains("\"max_tokens\":1000"))
        assertSwapped(requests[1], 1_000)
        assertTrue("只换参数名，stream 档不变", requests[1].contains("\"stream\":true"))
    }

    @Test
    fun `非流式_换名后撞限升额_第三发继承新参数名而非退回旧名`() = runBlocking {
        // E7：换名成功后 finish_reason=length 触发升额 ×3 = 3000。若升额那发忘了继承 useNewName，
        // 报文会退回 max_tokens → 推理系必再 400 → 白烧一轮后退回首轮截断内容（用户看到半截）。
        val client = clientResponding(
            400 to paramRejection,
            200 to completionJson("半截内容", "length"),
            200 to completionJson("完整内容", "stop"),
        )
        val result = client.completion(messages = msgs(), config = config, maxTokens = 1_000)
        assertEquals("完整内容", result)
        assertEquals(3, requests.size)
        assertSwapped(requests[1], 1_000)
        assertSwapped(requests[2], 3_000)
    }

    // ---------- T2-3 温度方言去温重试接线（图纸 2026-09-01 件②·E9）----------

    /** 「不认 temperature」的拒收报文（重新打字，不引实现常量）。 */
    private val temperatureRejection =
        """{"error":{"message":"Unsupported value: 'temperature' does not support 0.8 with this model.","param":"temperature"}}"""

    @Test
    fun `非流式_首发400点名temperature_去温度重发一次并采用第二发结果`() = runBlocking {
        val client = clientResponding(
            400 to temperatureRejection,
            200 to completionJson("整理好的记忆", "stop"),
        )
        val result = client.completion(messages = msgs(), config = config, temperature = 0.8, maxTokens = 1_000)
        assertEquals("返回值必须来自第二发", "整理好的记忆", result)
        assertEquals("首发 + 去温重发，恰两次", 2, requests.size)
        assertTrue("首发应带温度：${requests[0]}", requests[0].contains("\"temperature\":0.8"))
        assertFalse("去温那发绝不能再带 temperature：${requests[1]}", requests[1].contains("temperature"))
        assertTrue("去温只砍温度，上限原值照发：${requests[1]}", requests[1].contains("\"max_tokens\":1000"))
    }

    @Test
    fun `流式_首发400点名temperature_去温度重发一次后正常收流`() = runBlocking {
        val client = clientResponding(
            400 to temperatureRejection,
            200 to sseBody("她推开门"),
        )
        val contents = client.streamChat(messages = msgs(), config = config, temperature = 0.8)
            .filterIsInstance<StreamToken.Content>().toList()
        assertEquals("她推开门", contents.joinToString("") { it.text })
        assertEquals(2, requests.size)
        assertTrue(requests[0], requests[0].contains("\"temperature\":0.8"))
        assertFalse("去温那发绝不能再带 temperature：${requests[1]}", requests[1].contains("temperature"))
        assertTrue("只去温度，stream 档不变", requests[1].contains("\"stream\":true"))
    }

    @Test
    fun `非流式_去温重试自身再400_原样抛不再链式自愈`() = runBlocking {
        // 各类 400 自愈每次调用各恰一次：第二发的 400 不再进任何分类，直接上抛（绝不无限救）。
        val client = clientResponding(
            400 to temperatureRejection,
            400 to temperatureRejection,
        )
        try {
            client.completion(messages = msgs(), config = config, temperature = 0.8)
            fail("应抛 LlmError.Http")
        } catch (e: LlmError.Http) {
            assertEquals(400, e.statusCode)
        }
        assertEquals("恰两发，不许有第三发", 2, requests.size)
    }

    @Test
    fun `不点名的400_不换名恰一发即抛`() = runBlocking {
        // 回归钉：与方言无关的 400（模型名错等）行为必须与改前一致——原样抛，绝不多烧一发。
        val client = clientResponding(
            400 to """{"error":{"message":"Model Not Exist","type":"invalid_request_error"}}""",
        )
        try {
            client.completion(messages = msgs(), config = config, maxTokens = 1_000)
            fail("应抛 LlmError.Http")
        } catch (e: LlmError.Http) {
            assertEquals(400, e.statusCode)
        }
        assertEquals(1, requests.size)
    }
}
