package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.StreamToken
import com.situ.aichat.data.remote.llm.ToolCallChunk
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.network.NetworkMonitor
import com.situ.aichat.tts.TtsService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T2（图纸 2026-09-26-工具回喂回传思考内容）：跑真 [AssistantTurnEngine.runAssistantTurn] 工具路（Robolectric·
 * 装配真跑 PromptBuilder），假 [LlmClient] 吐「思考片段 + 只有工具调用、没正文」→ 断言回喂 completion 收到的
 * assistant tool_calls 消息带回同轮 reasoning_content（DeepSeek 思考模式缺了必 400、角色发假网络道歉）。
 * 手法同 [AssistantTurnEngineTest]（那边已近 800 行红线，故另起一类·只装本族要的桩）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantTurnToolFollowUpReasoningTest {

    private lateinit var llmClient: LlmClient
    private lateinit var replyDeliverer: ChatReplyDeliverer
    private lateinit var engine: AssistantTurnEngine

    private val character = CharacterEntity(uuid = "c1", name = "夏晴子", creationDate = 0L)
    private val config = ApiConfigValues(
        providerType = ApiProviderType.DEEPSEEK, apiKey = "", baseUrl = "https://api.deepseek.com",
        modelName = "deepseek-v4-flash", isThinkingModel = true, toolCallingEnabled = true,
    )
    private val settings = AppSettings(calendarIntegrationEnabled = false, petSystemEnabled = false, vectorSearchThreshold = 0)

    private val promiseCall = StreamToken.ToolCallDelta(
        ToolCallChunk(index = 0, id = "call_1", functionName = "record_promise", argumentChunk = """{"content":"明天下午三点阿远来店里","evidence":"明天下午三点我去店里找你"}"""),
    )

    @Before
    fun setUp() {
        llmClient = mockk(relaxed = true)
        replyDeliverer = mockk(relaxed = true)
        coEvery { replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            DeliveredTurn(listOf(MessageEntity(messageUUID = "m1", conversationUuid = "conv-1", roleRaw = "assistant", content = "好", timestamp = 1L)), false)
        val networkMonitor = mockk<NetworkMonitor> { every { isConnected } returns MutableStateFlow(true) }
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        coEvery { conversationRepo.get("conv-1") } returns ConversationEntity(uuid = "conv-1", title = "t", characterUuid = "c1", creationDate = 0L)
        val messageRepo = mockk<MessageRepository>(relaxed = true) { coEvery { recentChronological(any(), any()) } returns emptyList() }
        mockkObject(TtsService)
        every { TtsService.hasAvailableVoice(any(), any(), any()) } returns false

        engine = AssistantTurnEngine(
            scope = CoroutineScope(Dispatchers.Unconfined), appContext = RuntimeEnvironment.getApplication(), conversationUuid = "conv-1",
            messageRepo = messageRepo, conversationRepo = conversationRepo,
            characterRepo = mockk(relaxed = true) { coEvery { getMilestones(any()) } returns emptyList() },
            offlineMeetingMemoryRepository = mockk(relaxed = true), scheduleDao = mockk(relaxed = true), giftDao = mockk(relaxed = true),
            apiConfigRepo = mockk(relaxed = true), stickerRepo = mockk(relaxed = true), petRepo = mockk(relaxed = true),
            petWriteLock = mockk(relaxed = true), petInventoryPromptService = mockk(relaxed = true), calendarReader = mockk(relaxed = true),
            llmClient = llmClient, vectorMemory = mockk(relaxed = true), memoryService = mockk(relaxed = true),
            momentChatContextService = mockk(relaxed = true), economicStateService = mockk(relaxed = true), ttsConfigRepo = mockk(relaxed = true),
            llmForegroundController = mockk(relaxed = true), networkMonitor = networkMonitor, contextLog = mockk(relaxed = true),
            notificationScheduler = mockk(relaxed = true), momentGenerationService = mockk(relaxed = true), replyDeliverer = replyDeliverer,
            calendarHandler = mockk(relaxed = true), memoryAnalysisTrigger = mockk(relaxed = true), inSceneRecapCoordinator = mockk(relaxed = true),
            relationshipAnalysisTrigger = mockk(relaxed = true), meetingDetectionTrigger = mockk(relaxed = true),
            openLoopDetectionTrigger = mockk(relaxed = true), openLoopRepository = mockk(relaxed = true),
            promiseRepository = mockk(relaxed = true), promiseToolHandler = mockk(relaxed = true),
            ourDayRepository = mockk(relaxed = true) { coEvery { injectableForCharacter(any()) } returns emptyList() },
            meetingAppointmentStore = mockk(relaxed = true),
            worldBookPromptService = mockk {
                coEvery { activateForTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns null
            },
            worldChatContextProvider = mockk(relaxed = true),
            errorFlow = MutableStateFlow(null), infoToastFlow = MutableStateFlow(null), isDelivering = MutableStateFlow(false),
        )
    }

    @After
    fun tearDown() {
        unmockkObject(TtsService) // 只清自己 mock 的 object，绝不 unmockkAll 污染同 JVM 后续测试类。
    }

    /** 非 DeepSeek 官方地址（补空串门控不过）→ 回喂请求字节与上一卷一致。 */
    private val openRouter = config.copy(providerType = ApiProviderType.OPENROUTER, baseUrl = "https://openrouter.ai/api/v1", modelName = "deepseek/deepseek-v4-flash")

    /** 跑一回合，返回回喂 completion 收到的消息表 + 投递出去的正文。 */
    private fun runTurnWith(vararg tokens: StreamToken, turnConfig: ApiConfigValues = config): Pair<List<ChatMessageDto>, String> {
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any(), sessionKey = any()) } returns flowOf(*tokens)
        val followUp = slot<List<ChatMessageDto>>()
        coEvery { llmClient.completion(capture(followUp), any(), any(), any(), any(), any(), sessionKey = any()) } returns "好呀，明天等你~"
        val delivered = slot<String>()
        coEvery { replyDeliverer.deliverAssistantReply(capture(delivered), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            DeliveredTurn(listOf(MessageEntity(messageUUID = "m1", conversationUuid = "conv-1", roleRaw = "assistant", content = "好", timestamp = 1L)), false)
        runBlocking { engine.runAssistantTurn(turnConfig, character, settings, userProfile = null, userMessageForEmbed = null) }
        assertTrue("只回工具、正文空 → 必须发回喂", followUp.isCaptured)
        return followUp.captured to delivered.captured
    }

    @Test
    fun 思考模式只回工具_回喂的assistant消息带回同轮reasoning_content() {
        val (followUp, delivered) = runTurnWith(
            StreamToken.Reasoning("用户约了明天下午三点，", fromReasoningContent = true),
            promiseCall,
            StreamToken.Reasoning("先记下。", fromReasoningContent = true),
        )
        val assistant = followUp.single { it.role == "assistant" && it.toolCalls != null }
        assertEquals("同轮片段按到达顺序原样拼接", "用户约了明天下午三点，先记下。", assistant.reasoningContent)
        assertEquals(listOf("call_1"), assistant.toolCalls!!.map { it.id })
        assertEquals("回喂成功 → 投递真回复（不是假网络道歉）", "好呀，明天等你~", delivered)
    }

    @Test
    fun 非reasoning_content来源的思考_不回传() {
        // OpenRouter `reasoning` / 内联 <think> 剥出的片段旗标为 false → 不挂（这些服务商不走该协议）。
        val (followUp, _) = runTurnWith(StreamToken.Reasoning("<think>里的想法"), promiseCall, turnConfig = openRouter)
        assertNull(followUp.single { it.role == "assistant" && it.toolCalls != null }.reasoningContent)
    }

    @Test
    fun 无思考_非DeepSeek官方_回喂消息不带reasoning_content() {
        val (followUp, _) = runTurnWith(promiseCall, turnConfig = openRouter)
        assertNull(followUp.single { it.role == "assistant" && it.toolCalls != null }.reasoningContent)
    }

    @Test
    fun 无reasoning_content片段_DeepSeek官方_回喂那条补空串_不外带别的来源思考() {
        // 图纸 2026-09-26-末尾助手消息补空思考 §9（用户拍板修订上一卷 §4「空 → null」）：官方 DeepSeek 缺了必 400 → 如实补 ""。
        val (followUp, delivered) = runTurnWith(StreamToken.Reasoning("<think>里的想法"), promiseCall)
        assertEquals("", followUp.single { it.role == "assistant" && it.toolCalls != null }.reasoningContent)
        assertEquals("回喂成功 → 投递真回复（不是假网络道歉）", "好呀，明天等你~", delivered)
    }
}
