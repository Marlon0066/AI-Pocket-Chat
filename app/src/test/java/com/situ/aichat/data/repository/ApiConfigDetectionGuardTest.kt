package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.ApiConfigDao
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AudioInputMode
import com.situ.aichat.data.model.ThinkingModelMode
import com.situ.aichat.data.model.ToolCallingMode
import com.situ.aichat.data.model.VisionMode
import com.situ.aichat.data.remote.llm.ApiBalanceService
import com.situ.aichat.data.remote.llm.CapabilityDetector
import com.situ.aichat.security.ApiKeyStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 探针写回护栏的**接线**行为测试（T2·图纸 2026-08-31 §7）——纯函数真值表在
 * `VisionCapabilityTaggingTest`，这里只证「`runCapabilityDetections` 真的按护栏后的值写库」。
 *
 * 为什么必须在 Repository 这层测：护栏纯函数再对，接线接错（写回用了原始探针值、或护栏放在
 * `-1 不覆盖` 判断的错误一侧）照样把用户的发图按钮关掉。断言直接钉 `dao.update*Detection` 收到的**那个数**。
 *
 * 手法：MockK 假掉五个构造依赖（dao relaxed，探针按用例桩）。T2-4 用**真** [CapabilityDetector]——
 * ANTHROPIC 音频跳过分支在发请求之前就 return，不联网。
 */
class ApiConfigDetectionGuardTest {

    private val uuid = "cfg-1"
    private lateinit var dao: ApiConfigDao
    private lateinit var keyStore: ApiKeyStore
    private lateinit var detector: CapabilityDetector
    private lateinit var balanceService: ApiBalanceService
    private lateinit var functionRouter: ApiFunctionRouter

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        keyStore = mockk(relaxed = true)
        detector = mockk(relaxed = true)
        balanceService = mockk(relaxed = true)
        functionRouter = mockk(relaxed = true)
        coEvery { keyStore.get(any()) } returns ""
    }

    private fun repo(capabilityDetector: CapabilityDetector = detector) =
        ApiConfigRepository(dao, keyStore, capabilityDetector, balanceService, functionRouter)

    /** 只开一项待测能力的配置：其余能力走手动档，免得别的探针跟着跑进来。 */
    private fun entity(
        model: String,
        provider: ApiProviderType = ApiProviderType.OPENAI_COMPATIBLE,
        vision: VisionMode = VisionMode.AUTO,
        audio: AudioInputMode = AudioInputMode.DISABLED,
    ) = ApiConfigEntity(
        uuid = uuid,
        providerName = "p",
        providerTypeRaw = provider.raw,
        apiKeyId = "k",
        baseURL = "https://h.com/v1",
        modelName = model,
        isActive = true,
        creationDate = 0L,
        toolCallingModeRaw = ToolCallingMode.DISABLED.raw,
        visionModeRaw = vision.raw,
        detectedVisionSupport = -1,
        audioInputModeRaw = audio.raw,
        detectedAudioInputSupport = -1,
        thinkingModelModeRaw = ThinkingModelMode.STANDARD.raw,
        detectedThinkingModelType = -1,
    )

    // ---------- T2-1：名字表正断言压过探针 0（E6） ----------

    @Test
    fun `强制流式模型探针判 0 时写回名字表的 1`() = runBlocking {
        // qwen3-omni 系强制 stream=true → 非流式探针必被拒 → 探针恒 0，救不回来，只能靠表 + 护栏
        coEvery { dao.getByUuid(uuid) } returns entity("qwen3-omni-flash")
        coEvery { detector.detectVisionSupport(any()) } returns 0

        repo().runCapabilityDetections(uuid)

        coVerify(exactly = 1) { dao.updateVisionDetection(uuid, 1) }
        coVerify(exactly = 0) { dao.updateVisionDetection(uuid, 0) }
    }

    // ---------- T2-2：名字表查不到时护栏不干预（E7） ----------

    @Test
    fun `名字表查不到时探针 0 原样写回`() = runBlocking {
        coEvery { dao.getByUuid(uuid) } returns entity("totally-unknown-model")
        coEvery { detector.detectVisionSupport(any()) } returns 0

        repo().runCapabilityDetections(uuid)

        coVerify(exactly = 1) { dao.updateVisionDetection(uuid, 0) }
    }

    // ---------- T2-3：-1 不覆盖已有预填（E5 / B-2） ----------

    @Test
    fun `探针未判定时不覆盖已预填的支持`() = runBlocking {
        // gpt-4o 预填 1；探针 -1（网络错误/无 key）绝不能把它抹成「不确定」= 按钮消失
        coEvery { dao.getByUuid(uuid) } returns entity("gpt-4o")
        coEvery { detector.detectVisionSupport(any()) } returns -1

        val anyUndetermined = repo().runCapabilityDetections(uuid)

        coVerify(exactly = 0) { dao.updateVisionDetection(any(), any()) }
        assertTrue("anyUndetermined 判的是护栏前的原始探针值", anyUndetermined)
    }

    // ---------- T2-4：ANTHROPIC 音频恒 0 走完整写回链（E9） ----------

    @Test
    fun `Anthropic 音频不发探针且写回 0`() = runBlocking {
        coEvery { dao.getByUuid(uuid) } returns entity(
            model = "claude-opus-5",
            provider = ApiProviderType.ANTHROPIC,
            vision = VisionMode.DISABLED,
            audio = AudioInputMode.AUTO,
        )
        // 真探测器：ANTHROPIC 分支在发请求之前 return 0；其余探针都被手动档挡住，一个都不会跑
        val realDetector = CapabilityDetector(OkHttpClient(), Json { ignoreUnknownKeys = true })

        repo(realDetector).runCapabilityDetections(uuid)

        // 名字表 claude-opus-5 的 hasAudioInput=false → 护栏不干预 → 0 原样落库
        coVerify(exactly = 1) { dao.updateAudioDetection(uuid, 0) }
    }
}
