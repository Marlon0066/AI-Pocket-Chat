package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.RedPacketEventSenderRole
import com.situ.aichat.data.model.SystemEventJson
import com.situ.aichat.data.model.SystemEventType
import com.situ.aichat.data.model.ThinkingBudgetLevel
import com.situ.aichat.data.model.makeRedPacketSystemEventData
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.StreamToken
import com.situ.aichat.data.remote.llm.ToolCallChunk
import com.situ.aichat.data.remote.llm.ToolDefinitionDto
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.network.NetworkMonitor
import com.situ.aichat.tts.TtsService
import com.situ.aichat.worldbook.AtDepthInjection
import com.situ.aichat.worldbook.WorldInfoActivationResult
import com.situ.aichat.worldbook.WorldInfoDiagnostics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
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
 * T2（图纸 2026-09-26-末尾助手消息补空思考）：跑真 [AssistantTurnEngine.runAssistantTurn] 工具路（Robolectric·装配真跑
 * PromptBuilder），历史末尾是「角色这边」的记录（红包收下事件 / 世界书第 0 层角色条目）→ 断言带 tools 的流请求里
 * 那条 assistant 带 `reasoning_content = ""`（DeepSeek 思考模式缺了必 400）；非 DeepSeek / 思考关 / 降级纯文本路不动。
 * 手法同 [AssistantTurnToolFollowUpReasoningTest]（AssistantTurnEngineTest 已近 800 行红线，故另起一类）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantTurnTrailingAssistantReasoningTest {

    private lateinit var llmClient: LlmClient
    private lateinit var replyDeliverer: ChatReplyDeliverer
    private lateinit var messageRepo: MessageRepository
    private var worldInfo: WorldInfoActivationResult? = null
    private lateinit var engine: AssistantTurnEngine

    /** 每次 streamChat 调用：发出去的消息表 + 是否带了 tools。 */
    private val streamCalls = mutableListOf<Pair<List<ChatMessageDto>, Boolean>>()

    private val character = CharacterEntity(uuid = "c1", name = "夏晴子", creationDate = 0L)
    private val deepSeek = ApiConfigValues(
        providerType = ApiProviderType.DEEPSEEK, apiKey = "", baseUrl = "https://api.deepseek.com",
        modelName = "deepseek-v4-flash", isThinkingModel = true, toolCallingEnabled = true,
    )
    private val settings = AppSettings(calendarIntegrationEnabled = false, petSystemEnabled = false, vectorSearchThreshold = 0)
    private val now = System.currentTimeMillis()

    private val userText = MessageEntity(messageUUID = "u1", conversationUuid = "conv-1", roleRaw = "user", content = "我给你发了个红包～", timestamp = now - 60_000L)
    private val acceptedEvent = MessageEntity(
        messageUUID = "e1", conversationUuid = "conv-1", roleRaw = "system", timestamp = now - 50_000L,
        messageKindRaw = MessageKind.SYSTEM_EVENT_CARD.raw,
        content = SystemEventJson.encode(
            makeRedPacketSystemEventData(
                eventType = SystemEventType.RED_PACKET_ACCEPTED, amount = 52, blessingText = "开心", rejectionReason = null,
                senderRole = RedPacketEventSenderRole.USER, characterName = "夏晴子", timestampMillis = now - 50_000L,
            ),
        ),
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
        messageRepo = mockk(relaxed = true)
        coEvery { messageRepo.quotedRefs(any()) } returns emptyMap()
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
                coEvery { activateForTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers { worldInfo }
            },
            worldChatContextProvider = mockk(relaxed = true),
            errorFlow = MutableStateFlow(null), infoToastFlow = MutableStateFlow(null), isDelivering = MutableStateFlow(false),
        )
    }

    @After
    fun tearDown() {
        unmockkObject(TtsService) // 只清自己 mock 的 object，绝不 unmockkAll 污染同 JVM 后续测试类。
    }

    /**
     * 跑一回合：[toolStream] = 带 tools 那次流吐什么，纯文本流恒吐一句正文。返回回喂 completion 收到的消息表（没发则 null）。
     */
    private fun runTurn(
        history: List<MessageEntity>,
        config: ApiConfigValues = deepSeek,
        toolStream: () -> Flow<StreamToken> = { flowOf(StreamToken.Content("谢谢你呀~")) },
    ): List<ChatMessageDto>? {
        coEvery { messageRepo.recentChronological(any(), any()) } returns history
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any(), sessionKey = any()) } answers {
            val withTools = arg<List<ToolDefinitionDto>?>(5) != null
            streamCalls += firstArg<List<ChatMessageDto>>() to withTools
            if (withTools) toolStream() else flowOf(StreamToken.Content("谢谢你呀~"))
        }
        val followUp = slot<List<ChatMessageDto>>()
        coEvery { llmClient.completion(capture(followUp), any(), any(), any(), any(), any(), sessionKey = any()) } returns "记下啦~"
        runBlocking { engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null) }
        return if (followUp.isCaptured) followUp.captured else null
    }

    private fun toolRequest(): List<ChatMessageDto> = streamCalls.single { it.second }.first

    /** 红包收下事件渲染成的那条（归角色桶）。 */
    private fun eventMessage(messages: List<ChatMessageDto>): ChatMessageDto =
        messages.single { it.content?.contains("你收下了") == true }

    @Test
    fun 末尾是红包收下事件_DeepSeek_带工具的请求里那条补空思考() {
        runTurn(listOf(userText, acceptedEvent))
        val sent = toolRequest()
        val event = eventMessage(sent)
        assertEquals("场景成立：事件按角色身份发出", "assistant", event.role)
        assertEquals("场景成立：它是最后一条非 system 消息", event, sent.last { it.role != "system" })
        assertEquals("", event.reasoningContent)
        assertTrue("user 消息不碰", sent.filter { it.role == "user" }.all { it.reasoningContent == null })
    }

    @Test
    fun 世界书第0层角色条目_DeepSeek_补空思考() {
        worldInfo = WorldInfoActivationResult(
            before = "", after = "", suffix = "",
            atDepth = listOf(AtDepthInjection(depth = 0, role = 2, content = "（她轻轻哼着歌）")),
            newTimedStates = emptyList(), expiredTimedStates = emptyList(),
            diagnostics = WorldInfoDiagnostics(emptyList(), emptyList(), emptyList(), 0, 0),
        )
        runTurn(listOf(userText))
        val injected = toolRequest().single { it.content == "（她轻轻哼着歌）" }
        assertEquals("assistant", injected.role)
        assertEquals("", injected.reasoningContent)
    }

    @Test
    fun 非DeepSeek服务商_请求原样不补() {
        val openAi = deepSeek.copy(providerType = ApiProviderType.OPENAI_COMPATIBLE, baseUrl = "https://api.openai.com/v1", modelName = "gpt-5")
        runTurn(listOf(userText, acceptedEvent), config = openAi)
        assertTrue(toolRequest().all { it.reasoningContent == null })
    }

    @Test
    fun DeepSeek思考被关掉_请求原样不补() {
        runTurn(listOf(userText, acceptedEvent), config = deepSeek.copy(thinkingBudgetLevel = ThinkingBudgetLevel.OFF))
        assertTrue(toolRequest().all { it.reasoningContent == null })
    }

    @Test
    fun 只回工具_回喂请求里历史那条也是空思考_工具那条带同轮真思考() {
        val followUp = runTurn(listOf(userText, acceptedEvent)) {
            flowOf(
                StreamToken.Reasoning("先记下。", fromReasoningContent = true),
                StreamToken.ToolCallDelta(
                    ToolCallChunk(index = 0, id = "call_1", functionName = "record_promise", argumentChunk = """{"content":"下次请她喝奶茶","evidence":"下次我请你喝奶茶"}"""),
                ),
            )
        }
        requireNotNull(followUp) { "只回工具、正文空 → 必须发回喂" }
        assertEquals("", eventMessage(followUp).reasoningContent)
        assertEquals("先记下。", followUp.single { it.toolCalls != null }.reasoningContent)
    }

    @Test
    fun 工具流失败降级纯文本_不带工具的那次不补() {
        runTurn(listOf(userText, acceptedEvent)) { flow { throw java.io.IOException("boom") } }
        val plain = streamCalls.single { !it.second }.first
        assertNull(eventMessage(plain).reasoningContent)
    }
}
