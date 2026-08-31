package com.situ.aichat.data.remote.llm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首调 400 重试分类（[FirstCall400RetryPlan]）与推理系方言编码的 T1（2026-08-31 图纸 C·C1；
 * 2026-09-01「记忆与防污染加固批」件② 扩 DROP_TEMPERATURE 一档并改今名）。
 *
 * 为什么值得钉死：旁路（通知 1000 / 主动消息 200 / 故事 800–2800）打 OpenAI 官方推理系时，
 * `max_tokens` 被 400 拒收——旧版三条件（>8192）压根不触发 → 直连硬失败；而超长章 12000 会
 * 触发 clamp，clamp 换值不换名 → 二连 400。故优先级必须 SWAP > CLAMP，任一侧写反都是
 * 「用户发现某中转永远发不出通知 / 生成不了章节」的静默故障。
 *
 * 手法：报文字面量在此**重新打字**（不引实现常量），分类只打纯函数 [LlmClient.firstCall400RetryPlan]；
 * 编码断言直接打 [ChatRequestDto] 序列化产物，Json 配置与 NetworkModule.provideJson 同款。
 */
class FirstCall400RetryPlanTest {

    /** OpenAI 官方推理系原话（gpt-5.x / o 系 chat completions）。 */
    private val paramRejection =
        """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. """ +
            """Use 'max_completion_tokens' instead.","type":"invalid_request_error","param":"max_tokens"}}"""

    /** 撞顶报文（deepseek-chat 类硬顶 8192）——点名 max_tokens 但**不**含 unsupported。 */
    private val overflowRejection =
        """{"error":{"message":"max_tokens is too large: 12000. This model supports at most 8192 completion tokens."}}"""

    /** 既有真值表默认「我方发了 0.8 的温度」——温度谓词还要求报文点名 temperature，故对老用例零影响。 */
    private fun plan(code: Int, body: String?, requested: Int?, sentTemperature: Double? = 0.8) =
        LlmClient.firstCall400RetryPlan(LlmError.Http(code, body), requested, sentTemperature)

    // ---------- T1-1 分类真值表 ----------

    @Test
    fun `点名参数方言且传值不超顶_SWAP`() {
        // 旁路典型值：通知 1000 / 主动消息 200 / 故事 800–2800——旧版全落空，这是本件的主战场。
        assertEquals(FirstCall400RetryPlan.SWAP_PARAM_NAME, plan(400, paramRejection, 200))
        assertEquals(FirstCall400RetryPlan.SWAP_PARAM_NAME, plan(400, paramRejection, 1_000))
        assertEquals(FirstCall400RetryPlan.SWAP_PARAM_NAME, plan(400, paramRejection, 2_800))
    }

    @Test
    fun `点名参数方言且传值超顶_SWAP胜CLAMP`() {
        // E4：故事超长章 12000 打推理系——两谓词同时成立时换名必须赢，clamp 换值不换名注定二连 400。
        assertEquals(FirstCall400RetryPlan.SWAP_PARAM_NAME, plan(400, paramRejection, 12_000))
    }

    @Test
    fun `撞顶报文且传值超顶_CLAMP`() {
        // E3：既有降额路语义不变。
        assertEquals(FirstCall400RetryPlan.CLAMP, plan(400, overflowRejection, 12_000))
    }

    @Test
    fun `撞顶报文但传值未超顶_NONE`() {
        // 值本来不超 8192 → 降额无意义（400 另有原因），且报文不点名方言 → 不换名。
        assertEquals(FirstCall400RetryPlan.NONE, plan(400, overflowRejection, 200))
        assertEquals(FirstCall400RetryPlan.NONE, plan(400, overflowRejection, 8_192))
    }

    @Test
    fun `未传maxTokens_任何报文都是NONE`() {
        // E6：聊天主路 / 语音通话不传上限——没有可换名、可降额的值，行为必须字节级不变。
        assertEquals(FirstCall400RetryPlan.NONE, plan(400, paramRejection, null))
        assertEquals(FirstCall400RetryPlan.NONE, plan(400, overflowRejection, null))
    }

    @Test
    fun `非400状态码一律NONE`() {
        // E5 同构：422（能力不支持）/ 500 / 401 都与 max_tokens 无关，重试注定白烧。
        assertEquals(FirstCall400RetryPlan.NONE, plan(422, paramRejection, 1_000))
        assertEquals(FirstCall400RetryPlan.NONE, plan(500, paramRejection, 12_000))
        assertEquals(FirstCall400RetryPlan.NONE, plan(401, overflowRejection, 12_000))
    }

    @Test
    fun `400无报文一律NONE`() {
        assertEquals(FirstCall400RetryPlan.NONE, plan(400, null, 1_000))
        assertEquals(FirstCall400RetryPlan.NONE, plan(400, null, 12_000))
    }

    @Test
    fun `与方言无关的400_NONE`() {
        val modelNotExist = """{"error":{"message":"Model Not Exist","type":"invalid_request_error"}}"""
        assertEquals(FirstCall400RetryPlan.NONE, plan(400, modelNotExist, 1_000))
        assertEquals(FirstCall400RetryPlan.NONE, plan(400, modelNotExist, 12_000))
    }

    // ---------- T1-4 温度方言真值表（图纸件②·E9/E10/E11）----------

    /** 「不认 temperature」的典型拒收报文（重新打字，不引实现常量）。 */
    private val temperatureRejection =
        """{"error":{"message":"Unsupported value: 'temperature' does not support 0.8 with this model.","param":"temperature"}}"""

    @Test
    fun `点名temperature且我方确实发了温度_DROP_TEMPERATURE`() {
        // E9：非思考模型撞上不认温度的方言 → 去温度同参重试一次。
        assertEquals(FirstCall400RetryPlan.DROP_TEMPERATURE, plan(400, temperatureRejection, null, 0.8))
        assertEquals(FirstCall400RetryPlan.DROP_TEMPERATURE, plan(400, temperatureRejection, 1_000, 0.8))
        // MiniMax 映射后的小值同样算「发了」。
        assertEquals(FirstCall400RetryPlan.DROP_TEMPERATURE, plan(400, temperatureRejection, null, 0.01))
    }

    @Test
    fun `我方没发温度时点名temperature的400_NONE`() {
        // E10：思考模型恒不发 temperature（resolveEffectiveTemperature 返 null）——重试注定白烧。
        assertEquals(FirstCall400RetryPlan.NONE, plan(400, temperatureRejection, null, null))
        assertEquals(FirstCall400RetryPlan.NONE, plan(400, temperatureRejection, 1_000, null))
    }

    @Test
    fun `同报文同时点名max_tokens与temperature_上限档胜出`() {
        // E11：各类自愈互不链式，同时点名时先救上限（SWAP/CLAMP 更常见且更致命）。
        val both = """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. """ +
            """Use 'max_completion_tokens' instead. Also 'temperature' is not supported."}}"""
        assertEquals(FirstCall400RetryPlan.SWAP_PARAM_NAME, plan(400, both, 1_000, 0.8))
        val overflowPlusTemp =
            """{"error":{"message":"max_tokens is too large: 12000 (max 8192); temperature is also invalid."}}"""
        assertEquals(FirstCall400RetryPlan.CLAMP, plan(400, overflowPlusTemp, 12_000, 0.8))
    }

    @Test
    fun `温度档也受状态码与报文约束`() {
        // 非 400 / 报文不点名 temperature 一律不救——回归钉：既有「与方言无关的 400 原样抛」不许被新档吃掉。
        assertEquals(FirstCall400RetryPlan.NONE, plan(422, temperatureRejection, null, 0.8))
        assertEquals(FirstCall400RetryPlan.NONE, plan(400, null, null, 0.8))
        val modelNotExist = """{"error":{"message":"Model Not Exist","type":"invalid_request_error"}}"""
        assertEquals(FirstCall400RetryPlan.NONE, plan(400, modelNotExist, null, 0.8))
    }

    // ---------- T1-2 报文编码互斥 ----------

    /** 与 `NetworkModule.provideJson` 同款配置（explicitNulls=false → null 字段不上线）。 */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
    }

    private fun encode(req: ChatRequestDto) = json.encodeToString(ChatRequestDto.serializer(), req)

    private fun baseRequest() = ChatRequestDto(
        model = "gpt-5",
        messages = listOf(ChatMessageDto(role = "user", content = "你好")),
        stream = false,
    )

    @Test
    fun `方言档报文只含max_completion_tokens`() {
        // E9 上半：换名后老键必须彻底消失，否则推理系照旧 400。
        val body = encode(baseRequest().copy(maxTokens = null, maxCompletionTokens = 64))
        assertTrue(body, body.contains("\"max_completion_tokens\":64"))
        assertFalse("换名档不许再带 max_tokens", body.contains("\"max_tokens\""))
    }

    @Test
    fun `常规档报文只含max_tokens`() {
        // E9 下半 + C-1：新字段缺省 null → 不上线，既有请求报文字节级不变。
        val body = encode(baseRequest().copy(maxTokens = 64))
        assertTrue(body, body.contains("\"max_tokens\":64"))
        assertFalse("缺省不许泄漏新键", body.contains("max_completion_tokens"))
    }

    @Test
    fun `两个上限键都不传时报文都不含`() {
        val body = encode(baseRequest())
        assertFalse(body, body.contains("max_tokens"))
        assertFalse(body, body.contains("max_completion_tokens"))
    }

    // ---------- T1-3 探针换名的 copy 语义 ----------

    @Test
    fun `copy换名只动两个上限键_其余字段逐个不变`() {
        // E10：thinking 探针重试用 req.copy(maxTokens=null, maxCompletionTokens=32) 重建——
        // model/messages/stream/temperature 任一被顺手改掉，第二发就不再是「同一发只换参数名」。
        val original = ChatRequestDto(
            model = "o4-mini",
            messages = listOf(ChatMessageDto(role = "user", content = "1+1")),
            stream = true,
            temperature = 0.7,
            maxTokens = 32,
        )
        val swapped = original.copy(maxTokens = null, maxCompletionTokens = 32)
        assertEquals(original.model, swapped.model)
        assertEquals(original.messages, swapped.messages)
        assertEquals(original.stream, swapped.stream)
        assertEquals(original.temperature, swapped.temperature)
        assertEquals(null, swapped.maxTokens)
        assertEquals(32, swapped.maxCompletionTokens)
    }
}
