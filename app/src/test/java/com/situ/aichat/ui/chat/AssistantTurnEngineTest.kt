package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopStatus
import com.situ.aichat.data.local.entity.OpenLoopType
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.calendar.CalendarActionType
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.StreamToken
import com.situ.aichat.data.remote.llm.ToolCallChunk
import com.situ.aichat.data.remote.llm.ToolDefinitionDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogToolInfo
import com.situ.aichat.offline.OfflineMeetingAction
import com.situ.aichat.offline.OfflineMeetingActionType
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OpenLoopRepository
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.PetWriteLock
import com.situ.aichat.data.repository.StickerRepository
import com.situ.aichat.economy.CharacterEconomicStateService
import com.situ.aichat.foreground.LlmGenerationForegroundController
import com.situ.aichat.gift.GiftHistoryPromptService
import com.situ.aichat.moments.MomentChatContextService
import com.situ.aichat.moments.MomentGenerationService
import com.situ.aichat.network.NetworkMonitor
import com.situ.aichat.notification.NotificationScheduler
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.calendar.CalendarReader
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.tooling.PendingCalendarFailure
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsService
import com.situ.aichat.util.AudioStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * AssistantTurnEngine 行为测试——验证刀8 编排引擎「真的能用」（不止编译过）。命脉路径，配 T5 对抗复核兜底。
 *
 * 走 Robolectric：runAssistantTurn 的 prompt 装配阶段经 PromptStrings(LocaleManager.wrap)/PromptBuilder 真读
 * 字符串资源，纯 JVM 跑不动。手法：instance 依赖全 MockK relaxed；打字/递送/error/info 流用真 MutableStateFlow；
 * **mock replyDeliverer.deliverAssistantReply 的返回值直接驱动编排结果**（非空=投递成功 / 空=空响应），llmClient
 * relaxed 返空流即可（投递结果由 deliverer mock 决定，不依赖真流式内容）；mockkObject(TtsService) 钉 hasAvailableVoice
 * =false 强制文字计划（只 unmockkObject 自己·不 unmockkAll 污染同 JVM）。incrementSceneProgress 回调用计数 spy。
 * 覆盖：断网快速失败 / 会话不存在静默返回 / happy（投递+逐回合维护[记忆/成长/关系/通知/补帖/节拍]+打字槽开关+旗标归位）/
 * 空响应（报可重试错 + 不跑逐回合维护）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantTurnEngineTest {

    private lateinit var messageRepo: MessageRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var characterRepo: CharacterRepository
    private lateinit var scheduleDao: ScheduleDao
    private lateinit var giftDao: GiftDao
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var stickerRepo: StickerRepository
    private lateinit var petRepo: PetRepository
    private lateinit var petWriteLock: PetWriteLock
    private lateinit var petInventoryPromptService: com.situ.aichat.pet.PetInventoryPromptService
    private lateinit var calendarReader: CalendarReader
    private lateinit var llmClient: LlmClient
    private lateinit var vectorMemory: VectorMemoryService
    private lateinit var memoryService: MemoryService
    private lateinit var momentChatContextService: MomentChatContextService
    private lateinit var economicStateService: CharacterEconomicStateService
    private lateinit var ttsConfigRepo: TtsConfigurationRepository
    private lateinit var llmForegroundController: LlmGenerationForegroundController
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var contextLog: ContextLogService
    private lateinit var notificationScheduler: NotificationScheduler
    private lateinit var momentGenerationService: MomentGenerationService
    private lateinit var replyDeliverer: ChatReplyDeliverer
    private lateinit var calendarHandler: ChatCalendarActionHandler
    private lateinit var memoryAnalysisTrigger: MemoryAnalysisTrigger
    private lateinit var relationshipAnalysisTrigger: RelationshipAnalysisTrigger
    private lateinit var meetingDetectionTrigger: MeetingDetectionTrigger
    private lateinit var openLoopDetectionTrigger: OpenLoopDetectionTrigger
    private lateinit var openLoopRepository: OpenLoopRepository
    private lateinit var promiseRepository: com.situ.aichat.data.repository.PromiseRepository
    private lateinit var ourDayRepository: com.situ.aichat.data.repository.OurDayRepository
    private lateinit var errorFlow: MutableStateFlow<String?>
    private lateinit var infoToastFlow: MutableStateFlow<String?>
    private lateinit var isDelivering: MutableStateFlow<Boolean>
    private var sceneProgressCalls = 0
    private lateinit var engine: AssistantTurnEngine

    private val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
    private val config = ApiConfigValues(
        providerType = ApiProviderType.ANTHROPIC, apiKey = "", baseUrl = "", modelName = "m",
    )
    // 关闭日历/宠物/向量检索，让装配走最短路（仍真跑 PromptBuilder）。
    private val settings = AppSettings(
        calendarIntegrationEnabled = false, petSystemEnabled = false, vectorSearchThreshold = 0,
    )

    private fun deliveredTurn(empty: Boolean) = if (empty) {
        DeliveredTurn(emptyList(), false)
    } else {
        DeliveredTurn(
            listOf(MessageEntity(messageUUID = "m1", conversationUuid = "conv-1", roleRaw = "assistant", content = "你好", timestamp = 1L)),
            false,
        )
    }

    @Before
    fun setUp() {
        messageRepo = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        scheduleDao = mockk(relaxed = true)
        giftDao = mockk(relaxed = true)
        apiConfigRepo = mockk(relaxed = true)
        stickerRepo = mockk(relaxed = true)
        petRepo = mockk(relaxed = true)
        petWriteLock = mockk(relaxed = true)
        petInventoryPromptService = mockk(relaxed = true)
        calendarReader = mockk(relaxed = true)
        llmClient = mockk(relaxed = true)
        vectorMemory = mockk(relaxed = true)
        memoryService = mockk(relaxed = true)
        momentChatContextService = mockk(relaxed = true)
        economicStateService = mockk(relaxed = true)
        ttsConfigRepo = mockk(relaxed = true)
        llmForegroundController = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        contextLog = mockk(relaxed = true)
        notificationScheduler = mockk(relaxed = true)
        momentGenerationService = mockk(relaxed = true)
        replyDeliverer = mockk(relaxed = true)
        calendarHandler = mockk(relaxed = true)
        memoryAnalysisTrigger = mockk(relaxed = true)
        relationshipAnalysisTrigger = mockk(relaxed = true)
        meetingDetectionTrigger = mockk(relaxed = true)
        openLoopDetectionTrigger = mockk(relaxed = true)
        openLoopRepository = mockk(relaxed = true)
        promiseRepository = mockk(relaxed = true)
        ourDayRepository = mockk(relaxed = true)
        coEvery { ourDayRepository.injectableForCharacter(any()) } returns emptyList() // 卷二：默认无行 = 装配零变化
        errorFlow = MutableStateFlow(null)
        infoToastFlow = MutableStateFlow(null)
        isDelivering = MutableStateFlow(false)
        sceneProgressCalls = 0

        // 默认：联网 + 会话存在 + 历史为空 + 文字计划（无可用语音）。
        every { networkMonitor.isConnected } returns MutableStateFlow(true)
        coEvery { conversationRepo.get("conv-1") } returns ConversationEntity(
            uuid = "conv-1", title = "标题", characterUuid = "c1", creationDate = 0L,
        )
        coEvery { messageRepo.recentChronological(any(), any()) } returns emptyList()
        coEvery { characterRepo.getMilestones(any()) } returns emptyList()
        mockkObject(TtsService)
        every { TtsService.hasAvailableVoice(any(), any(), any()) } returns false

        engine = AssistantTurnEngine(
            scope = CoroutineScope(Dispatchers.Unconfined),
            appContext = RuntimeEnvironment.getApplication(),
            conversationUuid = "conv-1",
            messageRepo = messageRepo,
            conversationRepo = conversationRepo,
            characterRepo = characterRepo,
            offlineMeetingMemoryRepository = mockk(relaxed = true),
            scheduleDao = scheduleDao,
            giftDao = giftDao,
            apiConfigRepo = apiConfigRepo,
            stickerRepo = stickerRepo,
            petRepo = petRepo,
            petWriteLock = petWriteLock,
            petInventoryPromptService = petInventoryPromptService,
            calendarReader = calendarReader,
            llmClient = llmClient,
            vectorMemory = vectorMemory,
            memoryService = memoryService,
            momentChatContextService = momentChatContextService,
            economicStateService = economicStateService,
            ttsConfigRepo = ttsConfigRepo,
            llmForegroundController = llmForegroundController,
            networkMonitor = networkMonitor,
            contextLog = contextLog,
            notificationScheduler = notificationScheduler,
            momentGenerationService = momentGenerationService,
            replyDeliverer = replyDeliverer,
            calendarHandler = calendarHandler,
            memoryAnalysisTrigger = memoryAnalysisTrigger,
            inSceneRecapCoordinator = mockk(relaxed = true),
            relationshipAnalysisTrigger = relationshipAnalysisTrigger,
            meetingDetectionTrigger = meetingDetectionTrigger,
            openLoopDetectionTrigger = openLoopDetectionTrigger,
            openLoopRepository = openLoopRepository,
            promiseRepository = promiseRepository,
            ourDayRepository = ourDayRepository, // 我们的日子·卷二 T2-4
            meetingAppointmentStore = mockk(relaxed = true),
            // WB4：本测试族不测世界书——stub 成「无书」保持既有断言语义（activateForTurn 恒 null = 装配零变化）。
            worldBookPromptService = mockk {
                coEvery { activateForTurn(any(), any(), any(), any(), any(), any(), any()) } returns null
            },
            // W5：本测试族不测世界联动——relaxed mock 使 forTurn 恒 null（worldContext=null=装配零变化，既有断言不变）。
            worldChatContextProvider = mockk(relaxed = true),
            errorFlow = errorFlow,
            infoToastFlow = infoToastFlow,
            isDelivering = isDelivering,
            incrementSceneProgress = { sceneProgressCalls++ },
        )
    }

    @After
    fun tearDown() {
        unmockkObject(TtsService) // 只清自己 mock 的 object，绝不 unmockkAll 污染同 JVM 后续测试类。
    }

    @Test
    fun 断网_快速失败报错_不流式不投递() = runBlocking {
        every { networkMonitor.isConnected } returns MutableStateFlow(false)
        engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)
        assertEquals(RuntimeEnvironment.getApplication().getString(com.situ.aichat.R.string.chat_no_network), errorFlow.value)
        verify(exactly = 0) { llmClient.streamChat(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun 会话不存在_静默返回_不报错不流式() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns null
        engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)
        assertNull(errorFlow.value)
        verify(exactly = 0) { llmClient.streamChat(any(), any(), any(), any(), any()) }
    }

    @Test
    fun happy_投递成功_跑逐回合维护_打字槽开关_旗标归位() = runBlocking {
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns deliveredTurn(empty = false)

        engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)

        coVerify { replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify { replyDeliverer.openTypingSlot() }
        verify { replyDeliverer.closeTypingSlot() }
        // 逐回合维护（仅成功投递才跑）：记忆 / 成长 / 关系 / 通知 / 补帖 / 节拍。
        coVerify { memoryAnalysisTrigger.checkAndTriggerMemorySummary("c1", any(), any(), any()) }
        // 卷四层 ①：引擎多传本轮 userText / replyText（图纸 §2.2 AssistantTurnEngine +1 行）；MockK 对省略的默认参按 eq("") 匹配会误红，故显式 any()。
        coVerify { relationshipAnalysisTrigger.incrementGrowthRoundAndCheck("c1", any(), any(), any(), any()) }
        coVerify { relationshipAnalysisTrigger.incrementRelationshipRoundAndCheck("c1", any(), any()) }
        coVerify { notificationScheduler.schedule(character) }
        coVerify { momentGenerationService.triggerCatchUpPostIfNeeded("c1", any(), any()) } // now/zone 带默认 → any()
        assertEquals(1, sceneProgressCalls)
        // 前台保活 acquire/release 成对；终态旗标归位。
        verify { llmForegroundController.acquire() }
        verify { llmForegroundController.release() }
        assertFalse(isDelivering.value)
        verify { replyDeliverer.closeTypingSlot() } // S4：打字占位清空=旧布尔断言的等价语义
    }

    @Test
    fun 我们的日子_每回合预取该角色注入候选行_T2_4() = runBlocking {
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns deliveredTurn(empty = false)

        engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)

        // 卷二图纸 §3.3：装配前必经仓库预取（漏传 = 该场景丢日子注入）。
        coVerify(exactly = 1) { ourDayRepository.injectableForCharacter("c1") }
    }

    @Test
    fun 空响应_报可重试错_不跑逐回合维护() = runBlocking {
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns deliveredTurn(empty = true)

        engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)

        // 批4 4-8：文案挪资源 + 不再指向不存在的重试按钮。
        assertEquals(RuntimeEnvironment.getApplication().getString(com.situ.aichat.R.string.chat_error_empty_reply), errorFlow.value)
        coVerify(exactly = 0) { notificationScheduler.schedule(any()) }
        coVerify(exactly = 0) { momentGenerationService.triggerCatchUpPostIfNeeded(any(), any(), any()) }
        assertEquals(0, sceneProgressCalls)
        verify { replyDeliverer.closeTypingSlot() } // 终态仍清打字槽
    }

    // ── 工具主路端到端 wiring（T5 复核盲点：结构化工具分支此前从未被测执行——现有用例 toolCallingEnabled 恒 false） ──

    @Test
    fun 工具路_日历调用_解析并透传给投递() = runBlocking {
        // 模型流里吐一个 calendar_action 工具调用 + 一句正文 → 累积/解析/透传给 deliverAssistantReply.toolCalendarActions。
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any()) } returns flowOf<StreamToken>(
            StreamToken.ToolCallDelta(
                ToolCallChunk(
                    index = 0, id = "c1", functionName = "calendar_action",
                    argumentChunk = """{"action":"create_event","title":"开会","startDate":"2026-06-05T10:00:00"}""",
                ),
            ),
            StreamToken.Content("好的，帮你记上~"),
        )
        val calSlot = slot<List<CalendarAction>>()
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), capture(calSlot), any(), any())
        } returns deliveredTurn(empty = false)

        engine.runAssistantTurn(
            config.copy(toolCallingEnabled = true), character,
            settings.copy(calendarIntegrationEnabled = true), userProfile = null, userMessageForEmbed = null,
        )

        assertTrue(calSlot.isCaptured)
        assertEquals(1, calSlot.captured.size)
        assertEquals("开会", calSlot.captured[0].title)
        assertEquals(CalendarActionType.CREATE_EVENT, calSlot.captured[0].action)
    }

    @Test
    fun 工具路_关日历仍发线下约见面工具_不发日历工具() = runBlocking {
        // H5 解绑端到端：模型支持工具但关了日历 → 下发的 tools 里没有日历工具，线下/约见面工具仍在。
        val toolsSlot = slot<List<ToolDefinitionDto>?>()
        every {
            llmClient.streamChat(any(), any(), any(), any(), any(), captureNullable(toolsSlot), any(), any())
        } returns flowOf<StreamToken>(StreamToken.Content("嗯嗯"))
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns deliveredTurn(empty = false)

        engine.runAssistantTurn(
            config.copy(toolCallingEnabled = true), character,
            settings, // calendarIntegrationEnabled=false（既有夹具）
            userProfile = null, userMessageForEmbed = null,
        )

        val names = toolsSlot.captured?.map { it.function.name } ?: emptyList()
        assertFalse("关日历 → 不发日历工具", names.contains("calendar_action"))
        assertTrue("约见面工具与日历解绑、仍发", names.contains("propose_future_meeting"))
        assertTrue("线下见面工具仍发", names.any { it == "suggest_offline_meeting" || it == "end_offline_meeting" })
    }

    @Test
    fun 工具路_参数解析失败_降级纯文本重发() = runBlocking {
        // 坏参数工具调用 + 无正文 → shouldFallBackToText=true → 清空 + 第二次 streamChat 纯文本重发，投递不带日历动作。
        val badTool = flowOf<StreamToken>(
            StreamToken.ToolCallDelta(
                ToolCallChunk(index = 0, id = "c1", functionName = "calendar_action", argumentChunk = """{"action":"bogus_action","title":"x"}"""),
            ),
        )
        val fallback = flowOf<StreamToken>(StreamToken.Content("抱歉，我直接说哈~"))
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(badTool, fallback)
        val calSlot = slot<List<CalendarAction>>()
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), capture(calSlot), any(), any())
        } returns deliveredTurn(empty = false)

        engine.runAssistantTurn(
            config.copy(toolCallingEnabled = true), character,
            settings.copy(calendarIntegrationEnabled = true), userProfile = null, userMessageForEmbed = null,
        )

        // 解析失败 → 不透传任何日历动作；且发生了第二次 streamChat（纯文本降级重发）。
        assertTrue(calSlot.isCaptured)
        assertTrue(calSlot.captured.isEmpty())
        verify(exactly = 2) { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── 回喂（fetchToolCallFollowUp）端到端：堵 d2f2b55 盲点 + 钉死 f58d60d「绝不谎报已完成」红线 ──

    @Test
    fun 工具路_回喂网络失败_兜底文案不谎报已执行() = runBlocking {
        // 模型只吐一个有效日历工具调用、无正文 → text 空 → 触发 fetchToolCallFollowUp；那次 completion 网络失败
        // → 兜底文案直接作为助手正文投递。红线：此刻动作尚未执行（执行在更后的 ChatReplyDeliverer），绝不能说
        // 「操作已执行」(f58d60d 家族·复核 F5/F6 揪出的漏网缝)。
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any()) } returns flowOf<StreamToken>(
            StreamToken.ToolCallDelta(
                ToolCallChunk(
                    index = 0, id = "c1", functionName = "calendar_action",
                    argumentChunk = """{"action":"create_event","title":"开会","startDate":"2026-06-05T10:00:00"}""",
                ),
            ),
            // 无 Content → text 空 → needsTextFollowUp 成立 → 进 fetchToolCallFollowUp。
        )
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any()) } throws RuntimeException("network down")
        val rawSlot = slot<String>()
        coEvery {
            replyDeliverer.deliverAssistantReply(capture(rawSlot), any(), any(), any(), any(), any(), any(), any(), any())
        } returns deliveredTurn(empty = false)

        engine.runAssistantTurn(
            config.copy(toolCallingEnabled = true), character,
            settings.copy(calendarIntegrationEnabled = true), userProfile = null, userMessageForEmbed = null,
        )

        // 投递的正文 = 兜底资源串（证明真走了 follow-up 网络失败分支）；且该资源串本身绝不含「已执行」（钉死红线）。
        val fallback = RuntimeEnvironment.getApplication().getString(com.situ.aichat.R.string.tool_call_follow_up_failed)
        assertTrue(rawSlot.isCaptured)
        assertEquals(fallback, rawSlot.captured)
        assertFalse("回喂网络失败兜底绝不谎报已完成", fallback.contains("已执行"))
    }

    @Test
    fun 工具路_好坏日历加约见面混调无正文_回喂逐条据实不谎报() = runBlocking {
        // f58d60d P1 + 约见面同类的端到端钉子：好+坏日历 + 约见面 同轮、无正文 → follow-up 对每个 call 就地据实陈述：
        // 好日历=确认卡待确认（尚未执行）/ 坏日历=没能执行 / 约见面=待定提案；任何一条都绝不「操作已执行」。
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any()) } returns flowOf<StreamToken>(
            StreamToken.ToolCallDelta(ToolCallChunk(index = 0, id = "good", functionName = "calendar_action",
                argumentChunk = """{"action":"create_event","title":"开会","startDate":"2026-06-05T10:00:00"}""")),
            StreamToken.ToolCallDelta(ToolCallChunk(index = 1, id = "bad", functionName = "calendar_action",
                argumentChunk = """{"action":"bogus_action","title":"x"}""")),
            StreamToken.ToolCallDelta(ToolCallChunk(index = 2, id = "meet", functionName = "propose_future_meeting",
                argumentChunk = """{"when_text":"周末","activity":"爬山"}""")),
        )
        val followUpSlot = slot<List<ChatMessageDto>>()
        coEvery { llmClient.completion(capture(followUpSlot), any(), any(), any(), any(), any()) } returns "嗯，安排好啦~"
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns deliveredTurn(empty = false)

        engine.runAssistantTurn(
            config.copy(toolCallingEnabled = true), character,
            settings.copy(calendarIntegrationEnabled = true), userProfile = null, userMessageForEmbed = null,
        )

        // 回喂消息里 role=tool 的三条结果文案，逐条据实、零谎报。
        assertTrue(followUpSlot.isCaptured)
        val toolContents = followUpSlot.captured.filter { it.role == "tool" }.mapNotNull { it.content }
        assertEquals(3, toolContents.size)
        val joined = toolContents.joinToString(" | ")
        assertFalse("回喂绝不谎报已完成: $joined", joined.contains("已执行"))
        assertTrue("好日历=待确认尚未执行: $joined", toolContents.any { it.contains("尚未执行") || it.contains("确认卡") })
        assertTrue("坏日历=据实没能执行: $joined", toolContents.any { it.contains("没能执行") })
        assertTrue("约见面=待定提案: $joined", toolContents.any { it.contains("提案") || it.contains("尚未落定") })
    }

    @Test
    fun 工具路_音频降级重试_强制不发工具() = runBlocking {
        // §8.4 backlog：媒体降级(mediaStripped)重试 × 工具路 此前零覆盖。钉死跨特性不变量「去音频降级重试绝不重发工具」
        // ——首发带音频的工具流连同其内部纯文本兜底都失败 → 触发 P13.4b 媒体降级 → 重试回合强制 useToolCalling=false（无 tools）。
        // 历史塞一条用户语音消息 + 桩 AudioStore.load 出字节 → hasAttachedAudio 成立。
        val voice = MessageEntity(
            messageUUID = "v1", conversationUuid = "conv-1", roleRaw = "user",
            content = "", timestamp = 1L, isVoiceMessage = true, audioRelativePath = "a.wav",
        )
        coEvery { messageRepo.recentChronological(any(), any()) } returns listOf(voice)
        mockkObject(AudioStore)
        try {
            coEvery { AudioStore.load(any()) } returns ByteArray(8)
            // 逐次记录每个 streamChat 调用下发的 tools（param index 5）；前两次失败（工具流 + 其内部纯文本兜底）逼出降级。
            val toolsPerCall = mutableListOf<List<ToolDefinitionDto>?>()
            var n = 0
            every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                n++
                toolsPerCall.add(arg<List<ToolDefinitionDto>?>(5))
                if (n <= 2) throw RuntimeException("audio rejected $n") else flowOf<StreamToken>(StreamToken.Content("好的~"))
            }
            coEvery {
                replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns deliveredTurn(empty = false)

            engine.runAssistantTurn(
                config.copy(toolCallingEnabled = true, audioInputEnabled = true), character,
                settings.copy(calendarIntegrationEnabled = true), userProfile = null, userMessageForEmbed = null,
            )

            // 首发工具路带 tools；降级后重试（withAudio=false + useToolCalling=false）绝不重发 tools。
            assertTrue("应发生降级重试（>1 次 streamChat）", toolsPerCall.size >= 2)
            assertTrue("首发工具路带 tools", toolsPerCall.first() != null)
            assertNull("降级重试绝不重发 tools", toolsPerCall.last())
        } finally {
            unmockkObject(AudioStore)
        }
    }

    // ── 线下见面工具端到端 + 工具遥测（上下文日志工具可见性·2026-07-12）──

    @Test
    fun 工具路_线下见面调用_解析透传投递_遥测落库() = runBlocking {
        // 模型流里吐一个 suggest_offline_meeting 调用 + 一句正文 → 解析成线下动作透传投递（此前五段链路
        // 唯一无直测的一段），同时工具遥测（tool 轨 + 下发清单 + 实际调用 + 解析产出）随成功日志落库。
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any()) } returns flowOf<StreamToken>(
            StreamToken.ToolCallDelta(
                ToolCallChunk(
                    index = 0, id = "c1", functionName = "suggest_offline_meeting",
                    argumentChunk = """{"location":"公园","activity":"散步","invitation":"走吧，出来晒晒太阳~",""" +
                        """"hidden_tension":"她今天有心事没说","tension_hint":"她比平时安静"}""",
                ),
            ),
            StreamToken.Content("那就说定啦~"),
        )
        val offSlot = slot<List<OfflineMeetingAction>>()
        val flagSlot = slot<Boolean>()
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), capture(offSlot), capture(flagSlot))
        } returns deliveredTurn(empty = false)
        val infoSlot = slot<LogToolInfo?>()

        engine.runAssistantTurn(
            config.copy(toolCallingEnabled = true), character,
            settings, // characterCanInitiateOfflineMeeting 默认 true → suggest 工具可下发可解析
            userProfile = null, userMessageForEmbed = null,
        )

        // 段5：解析成线下邀约动作 + 旗标，透传给投递层。
        assertTrue(offSlot.isCaptured)
        assertEquals(1, offSlot.captured.size)
        assertEquals(OfflineMeetingActionType.SUGGEST_MEETING, offSlot.captured[0].action)
        assertEquals("公园", offSlot.captured[0].location)
        assertTrue("线下工具调用旗标应透传", flagSlot.captured)
        // 遥测：tool 轨 / 下发清单含线下工具 / 调用与解析产出如实记录 / 未降级。
        verify { contextLog.recordSuccess(any(), any(), any(), any(), any(), any(), any(), any(), captureNullable(infoSlot)) }
        val info = infoSlot.captured
        assertEquals(LogToolInfo.MODE_TOOL, info?.mode)
        assertTrue("下发清单应含 suggest_offline_meeting", info?.sentTools.orEmpty().contains("suggest_offline_meeting"))
        assertEquals(listOf("suggest_offline_meeting"), info?.calls.orEmpty().map { it.name })
        assertEquals(1, info?.parsedOfflineActions)
        assertEquals(false, info?.fellBackToPlainText)
    }

    @Test
    fun 暗号轨_遥测记为marker_不下发工具() = runBlocking {
        // toolCallingEnabled=false（既有夹具默认）→ 纯文本轨；遥测记 marker，让日志页能说清「本轮没带工具」。
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns deliveredTurn(empty = false)
        val infoSlot = slot<LogToolInfo?>()

        engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)

        verify { contextLog.recordSuccess(any(), any(), any(), any(), any(), any(), any(), any(), captureNullable(infoSlot)) }
        assertEquals(LogToolInfo.MODE_MARKER, infoSlot.captured?.mode)
        assertTrue("暗号轨不下发工具", infoSlot.captured?.sentTools.orEmpty().isEmpty())
    }

    // ── ② 执行失败回流：引擎装配前一次性消费日历真失败、注入陪伴口吻提示 ──

    @Test
    fun 工具失败回流_引擎消费失败并把陪伴提示注入装配() = runBlocking {
        // calendarHandler 报「该会话有未消费日历真失败」→ 引擎本轮装配前消费，把【有件小事没办成】注入系统消息。
        every { calendarHandler.consumePendingFailure(any(), any()) } returns
            PendingCalendarFailure(verb = "创建", title = "牙医预约", reason = "没认出你说的时间", recordedAtMillis = 0L)
        val msgSlot = slot<List<ChatMessageDto>>()
        every { llmClient.streamChat(capture(msgSlot), any(), any(), any(), any(), any(), any(), any()) } returns
            flowOf<StreamToken>(StreamToken.Content("嗯嗯"))
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns deliveredTurn(empty = false)

        engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)

        assertTrue("引擎应已消费失败并装配", msgSlot.isCaptured)
        val systemText = msgSlot.captured.filter { it.role == "system" }.joinToString("\n") { it.content.orEmpty() }
        assertTrue("装配应含陪伴口吻失败提示：$systemText", systemText.contains("【有件小事没办成】"))
        assertTrue("应据实带出人话原因", systemText.contains("没认出你说的时间"))
    }

    // ── 活人感二期 M2 · T2-1：长线回访三门控预取 + 回合成功后标记（E4/E5/E6） ──

    private fun revisitLoop() = OpenLoopEntity(
        uuid = "rv1", conversationUuid = "conv-1", characterUuid = "c1",
        content = "上次面试的结果", typeRaw = OpenLoopType.USER_EVENT,
        statusRaw = OpenLoopStatus.RESOLVED, createdAt = 0L, resolvedAt = 1L,
    )

    @Test
    fun 回访_无到期_候选注入prompt_回合成功后标记revisited() = runBlocking {
        val revisit = revisitLoop()
        coEvery { openLoopRepository.openLoopsForCharacter("c1") } returns emptyList() // 无到期 open 项
        coEvery { openLoopRepository.revisitCandidates("c1", any()) } returns listOf(revisit)
        val msgSlot = slot<List<ChatMessageDto>>()
        every { llmClient.streamChat(capture(msgSlot), any(), any(), any(), any(), any(), any(), any()) } returns
            flowOf<StreamToken>(StreamToken.Content("嗯嗯"))
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns deliveredTurn(empty = false)

        engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)

        // 候选传入 prompt（回访注入块含回访项内容）。
        assertTrue(msgSlot.isCaptured)
        val systemText = msgSlot.captured.filter { it.role == "system" }.joinToString("\n") { it.content.orEmpty() }
        assertTrue("回访候选应注入 prompt：$systemText", systemText.contains("上次面试的结果"))
        // 回合成功 → 标记终态一次。
        coVerify(exactly = 1) { openLoopRepository.markRevisited(revisit, any()) }
    }

    @Test
    fun 回访_有到期open项_不取候选不标记() = runBlocking {
        // dueAt=1 ≤ now → 有到期 open 项 → 门控③不过 → 让位（E4）。
        val dueOpen = OpenLoopEntity(
            uuid = "d1", conversationUuid = "conv-1", characterUuid = "c1", content = "今天到期的事",
            typeRaw = OpenLoopType.USER_EVENT, dueAt = 1L, statusRaw = OpenLoopStatus.OPEN, createdAt = 0L,
        )
        coEvery { openLoopRepository.openLoopsForCharacter("c1") } returns listOf(dueOpen)
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns deliveredTurn(empty = false)

        engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)

        coVerify(exactly = 0) { openLoopRepository.revisitCandidates(any(), any()) }
        coVerify(exactly = 0) { openLoopRepository.markRevisited(any(), any()) }
    }

    @Test
    fun 回访_见面中_不取候选不标记() = runBlocking {
        // 门控①不过：线下见面回合不回访（E5）。
        coEvery { conversationRepo.get("conv-1") } returns ConversationEntity(
            uuid = "conv-1", title = "标题", characterUuid = "c1", creationDate = 0L, isInOfflineMode = true,
        )
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns deliveredTurn(empty = false)

        engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)

        coVerify(exactly = 0) { openLoopRepository.revisitCandidates(any(), any()) }
        coVerify(exactly = 0) { openLoopRepository.markRevisited(any(), any()) }
    }

    @Test
    fun 回访_回合空响应_取了候选但不标记() = runBlocking {
        // 门控全过、取到候选，但回合空响应（走不到成功后置块）→ 不标记，下回合重新候选（E6）。
        coEvery { openLoopRepository.openLoopsForCharacter("c1") } returns emptyList()
        coEvery { openLoopRepository.revisitCandidates("c1", any()) } returns listOf(revisitLoop())
        coEvery {
            replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns deliveredTurn(empty = true)

        engine.runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)

        coVerify(exactly = 1) { openLoopRepository.revisitCandidates("c1", any()) } // 确实取了候选
        coVerify(exactly = 0) { openLoopRepository.markRevisited(any(), any()) }     // 但未标记
    }
}
