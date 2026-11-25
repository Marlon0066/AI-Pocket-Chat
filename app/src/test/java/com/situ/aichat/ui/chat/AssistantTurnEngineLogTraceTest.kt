package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.LlmRequestCapture
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogTrace
import com.situ.aichat.network.NetworkMonitor
import com.situ.aichat.prompt.memory.InSceneRecapCoordinator
import com.situ.aichat.tts.TtsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T2-4（时间感知四期·图纸三 §3.11 / E8 / E9）：引擎给本轮日志记录与回合后触发器带上**同一个** [LogTrace]，
 * 每次流式尝试各自一个 [LlmRequestCapture]。
 *
 * 图纸原写「AssistantTurnEngineTest 追加」——那个文件已 787 行，追加即越 800 绝对红线（CLAUDE.md §2），
 * 故另起本文件（夹具同 [AssistantTurnEngineTest] 的最短装配路：依赖全 relaxed、投递结果由 deliverer mock 驱动）。偏差登记见图纸 §11。
 *
 * 读调用方上下文一律用 `currentCoroutineContext()`：在 `runBlocking {}` 里写 `coroutineContext` 会解析成外层
 * runBlocking 作用域的属性（隐式接收者优先于顶层导入），读到的是测试自己的上下文。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantTurnEngineLogTraceTest {

    private val messageRepo = mockk<MessageRepository>(relaxed = true)
    private val conversationRepo = mockk<ConversationRepository>(relaxed = true)
    private val networkMonitor = mockk<NetworkMonitor>(relaxed = true)
    private val contextLog = mockk<ContextLogService>(relaxed = true)
    private val replyDeliverer = mockk<ChatReplyDeliverer>(relaxed = true)
    private val memoryAnalysisTrigger = mockk<MemoryAnalysisTrigger>(relaxed = true)
    private val relationshipAnalysisTrigger = mockk<RelationshipAnalysisTrigger>(relaxed = true)
    private val meetingDetectionTrigger = mockk<MeetingDetectionTrigger>(relaxed = true)
    private val openLoopDetectionTrigger = mockk<OpenLoopDetectionTrigger>(relaxed = true)
    private val inSceneRecapCoordinator = mockk<InSceneRecapCoordinator>(relaxed = true)
    private lateinit var engine: AssistantTurnEngine

    private val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
    private val config = ApiConfigValues(providerType = ApiProviderType.ANTHROPIC, apiKey = "", baseUrl = "", modelName = "m")
    private val settings = AppSettings(calendarIntegrationEnabled = false, petSystemEnabled = false, vectorSearchThreshold = 0)

    private fun delivered(empty: Boolean) = if (empty) DeliveredTurn(emptyList(), false) else DeliveredTurn(
        listOf(MessageEntity(messageUUID = "m1", conversationUuid = "conv-1", roleRaw = "assistant", content = "你好", timestamp = 1L)),
        false,
    )

    private fun msg(uuid: String, role: String, ts: Long) = MessageEntity(messageUUID = uuid, conversationUuid = "conv-1", roleRaw = role, content = "嗯", timestamp = ts)

    @Before
    fun setUp() {
        every { networkMonitor.isConnected } returns MutableStateFlow(true)
        coEvery { conversationRepo.get("conv-1") } returns ConversationEntity(uuid = "conv-1", title = "标题", characterUuid = "c1", creationDate = 0L)
        coEvery { messageRepo.recentChronological(any(), any()) } returns emptyList()
        mockkObject(TtsService)
        every { TtsService.hasAvailableVoice(any(), any(), any()) } returns false
        engine = AssistantTurnEngine(
            scope = CoroutineScope(Dispatchers.Unconfined),
            appContext = RuntimeEnvironment.getApplication(),
            conversationUuid = "conv-1",
            messageRepo = messageRepo,
            conversationRepo = conversationRepo,
            characterRepo = mockk(relaxed = true) { coEvery { getMilestones(any()) } returns emptyList() },
            offlineMeetingMemoryRepository = mockk(relaxed = true),
            scheduleDao = mockk(relaxed = true),
            giftDao = mockk(relaxed = true),
            apiConfigRepo = mockk(relaxed = true),
            stickerRepo = mockk(relaxed = true),
            petRepo = mockk(relaxed = true),
            petWriteLock = mockk(relaxed = true),
            petInventoryPromptService = mockk(relaxed = true),
            calendarReader = mockk(relaxed = true),
            llmClient = mockk(relaxed = true),
            vectorMemory = mockk(relaxed = true),
            memoryService = mockk(relaxed = true),
            momentChatContextService = mockk(relaxed = true),
            economicStateService = mockk(relaxed = true),
            ttsConfigRepo = mockk(relaxed = true),
            llmForegroundController = mockk(relaxed = true),
            networkMonitor = networkMonitor,
            contextLog = contextLog,
            notificationScheduler = mockk(relaxed = true),
            momentGenerationService = mockk(relaxed = true),
            replyDeliverer = replyDeliverer,
            calendarHandler = mockk(relaxed = true),
            memoryAnalysisTrigger = memoryAnalysisTrigger,
            inSceneRecapCoordinator = inSceneRecapCoordinator,
            relationshipAnalysisTrigger = relationshipAnalysisTrigger,
            meetingDetectionTrigger = meetingDetectionTrigger,
            openLoopDetectionTrigger = openLoopDetectionTrigger,
            openLoopRepository = mockk(relaxed = true),
            promiseRepository = mockk(relaxed = true),
            promiseToolHandler = mockk(relaxed = true),
            ourDayRepository = mockk(relaxed = true) { coEvery { injectableForCharacter(any()) } returns emptyList() },
            meetingAppointmentStore = mockk(relaxed = true),
            worldBookPromptService = mockk {
                coEvery { activateForTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns null
            },
            worldChatContextProvider = mockk(relaxed = true),
            errorFlow = MutableStateFlow(null),
            infoToastFlow = MutableStateFlow(null),
            isDelivering = MutableStateFlow(false),
        )
    }

    @After
    fun tearDown() = unmockkObject(TtsService)

    @Test
    fun 一轮成功_主记录与六个触发器与场内前情共用同一个trace() = runBlocking {
        coEvery { replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns delivered(empty = false)
        coEvery { messageRepo.recentChronological(any(), any()) } returns listOf(msg("u0", "user", 1), msg("a0", "assistant", 2), msg("u1", "user", 3), msg("u2", "user", 4))
        var recapTrace: LogTrace? = null
        coEvery { inSceneRecapCoordinator.checkMeetingRecap(any()) } coAnswers { recapTrace = currentCoroutineContext()[LogTrace] }
        val main = slot<LogTrace?>()
        val s1 = slot<LogTrace?>(); val s2 = slot<LogTrace?>(); val s3 = slot<LogTrace?>()
        val s4 = slot<LogTrace?>(); val s5 = slot<LogTrace?>(); val s6 = slot<LogTrace?>()

        engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)

        verify {
            contextLog.recordSuccess(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                trace = match { it.conversationUuid == "conv-1" && it.characterUuid == "c1" && it.turnId != null },
                capture = any(),
            )
        }
        verify { contextLog.recordSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), captureNullable(main), any()) }
        val trace = main.captured!!
        assertEquals("锚点 = 本轮尾部用户消息里时间最早的一条", "u1", trace.anchorMessageUuid)
        coVerify { memoryAnalysisTrigger.checkAndTriggerMemorySummary("c1", any(), any(), any(), captureNullable(s1)) }
        coVerify { memoryAnalysisTrigger.incrementStructuredMemoryRoundAndCheck("c1", any(), any(), any(), captureNullable(s2)) }
        coVerify { relationshipAnalysisTrigger.incrementGrowthRoundAndCheck("c1", any(), any(), any(), any(), captureNullable(s3)) }
        coVerify { relationshipAnalysisTrigger.incrementRelationshipRoundAndCheck("c1", any(), any(), captureNullable(s4)) }
        coVerify { meetingDetectionTrigger.checkAndTrigger(any(), any(), any(), captureNullable(s5)) }
        coVerify { openLoopDetectionTrigger.checkAndTrigger(any(), any(), any(), captureNullable(s6)) }
        for ((name, s) in listOf("记忆总结" to s1, "结构化记忆" to s2, "成长" to s3, "关系兜底" to s4, "约见面扫描" to s5, "惦记扫描" to s6)) {
            assertSame("$name 收到的必须是同一个 trace", trace, s.captured)
        }
        assertSame("场内前情提要在 trace 上下文里跑", trace, recapTrace)
    }

    @Test
    fun 空响应重试两次_两条记录同turnId_各自一个捕获() = runBlocking {
        coEvery { replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns delivered(empty = true)
        val traces = mutableListOf<LogTrace?>()
        val captures = mutableListOf<LlmRequestCapture?>()

        engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)

        verify(exactly = 2) { contextLog.recordSuccess(any(), any(), any(), any(), any(), any(), any(), any(), any(), captureNullable(traces), captureNullable(captures)) }
        assertEquals(2, traces.size)
        assertNotNull(traces[0]?.turnId)
        assertEquals("同一轮的两次尝试共用 turnId（E8）", traces[0]?.turnId, traces[1]?.turnId)
        assertNotNull(captures[0])
        assertNotSame("每次尝试各自一个捕获", captures[0], captures[1])
    }
}
