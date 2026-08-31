package com.situ.aichat.data.remote.llm

import android.util.Log
import com.situ.aichat.data.model.ApiProviderType
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
import org.junit.Before
import org.junit.Test

/**
 * thinking 探针的推理系参数方言重试 T2（2026-08-31 图纸 C·C2）。
 *
 * 为什么值得钉死：探针把「参数名不对」的 400 读成「模型不会思考」，结果会被写回配置
 * （非 -1 即覆盖预填）——未收录的推理系中转从此被永久标成非思考模型。重试必须**恰一次**、
 * 且第二发只换参数名不换值（32/64），否则第二发测的就不是同一件事。
 *
 * 手法照搬 [LlmMaxTokensClampTest]：OkHttp 拦截器按序吐编排响应并录请求体（零真网络、零新依赖）。
 * `detectThinkingModelType` 先流式后非流式，故请求序列覆盖两个探针。
 */
class CapabilityDetectorThinkingDialectTest {

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "o4-mini",
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
    private val modelNotExist = """{"error":{"message":"Model Not Exist","type":"invalid_request_error"}}"""

    private val sseWithReasoning =
        "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先算 13×17\"}}]}\n\n" +
            "data: [DONE]\n\n"
    private val nonStreamWithReasoning =
        """{"choices":[{"message":{"role":"assistant","content":"221","reasoning_content":"13×17=221"}}]}"""

    private fun detectorResponding(vararg responses: Pair<Int, String>): CapabilityDetector {
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
        return CapabilityDetector(ok, Json { ignoreUnknownKeys = true })
    }

    @Test
    fun `流式探针_400点名参数方言_换名同值重发一次后判成思考模型`() = runBlocking {
        val detector = detectorResponding(
            400 to paramRejection,
            200 to sseWithReasoning,
        )
        assertEquals(1, detector.detectThinkingModelType(config))
        assertEquals("首发+换名重发，恰两次", 2, requests.size)
        assertTrue(requests[0], requests[0].contains("\"max_tokens\":32"))
        assertTrue("重试必须同值 32", requests[1].contains("\"max_completion_tokens\":32"))
        assertFalse("换名后老键必须消失", requests[1].contains("\"max_tokens\""))
        assertTrue("只换参数名，stream 档不变", requests[1].contains("\"stream\":true"))
    }

    @Test
    fun `非流式探针_400点名参数方言_换名同值重发一次后判成思考模型`() = runBlocking {
        val detector = detectorResponding(
            500 to """{"error":"upstream"}""", // 流式落 -1（非方言问题）→ 回退非流式
            400 to paramRejection,
            200 to nonStreamWithReasoning,
        )
        assertEquals(1, detector.detectThinkingModelType(config))
        assertEquals(3, requests.size)
        assertTrue(requests[1], requests[1].contains("\"max_tokens\":64"))
        assertTrue("重试必须同值 64", requests[2].contains("\"max_completion_tokens\":64"))
        assertFalse("换名后老键必须消失", requests[2].contains("\"max_tokens\""))
        assertTrue("只换参数名，stream 档不变", requests[2].contains("\"stream\":false"))
    }

    @Test
    fun `不点名的400_两个探针都不重试_落未判定`() = runBlocking {
        // C-3：探针判定基线不变——与方言无关的 400 照旧一发定音，绝不多烧一轮。
        val detector = detectorResponding(
            400 to modelNotExist,
            400 to modelNotExist,
        )
        assertEquals(-1, detector.detectThinkingModelType(config))
        assertEquals("流式一发 + 非流式一发", 2, requests.size)
    }

    @Test
    fun `换名重发仍被拒_恰一次不三试_落未判定`() = runBlocking {
        val detector = detectorResponding(
            400 to paramRejection, 400 to paramRejection, // 流式：首发 + 唯一一次换名重发
            400 to paramRejection, 400 to paramRejection, // 非流式：同上
        )
        assertEquals(-1, detector.detectThinkingModelType(config))
        assertEquals("每个探针最多两发", 4, requests.size)
    }
}
