package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.network.NetworkMonitor
import com.situ.aichat.sticker.DisabledBuiltInStickerStore
import com.situ.aichat.testutil.PrefsThreadRecordingContext
import com.situ.aichat.tts.TtsConfiguration
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsService
import com.situ.aichat.tts.provider.MiniMaxVoiceTagsSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 聊天一轮主线程读盘清零（稳定性防线 B·时间感知四期图纸四 §11 D-9 登记的两处）：表情包禁用表（`sticker_prefs`）与
 * MiniMax 语气标签开关（`tts_minimax_voice_tags`）不再在调用方线程（生产里 = viewModelScope 的 Main.immediate）上读，
 * 读到的值照旧、仍在本轮装配前读完。
 *
 * 夹具同 [AssistantTurnEngineLogTraceTest] 的最短装配路（依赖全 relaxed、投递结果由 deliverer mock 驱动）；appContext 换成
 * [PrefsThreadRecordingContext] 记下「谁在哪个线程取 prefs」，底下仍是 Robolectric 的真 SharedPreferences。
 * 预置数据一律经**未包装**的 Application 写，不污染线程记录。线程按对象比，不按名字比（协程调试模式会改线程名）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatTurnPrefsReadThreadTest {

    private val app = RuntimeEnvironment.getApplication()
    private val context = PrefsThreadRecordingContext(app)
    private val conversationRepo = mockk<ConversationRepository>(relaxed = true)
    private val llmClient = mockk<LlmClient>(relaxed = true)
    private val replyDeliverer = mockk<ChatReplyDeliverer>(relaxed = true)

    private val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
    private val convo = ConversationEntity(uuid = "conv-1", title = "标题", characterUuid = "c1", creationDate = 0L)
    private val config = ApiConfigValues(providerType = ApiProviderType.ANTHROPIC, apiKey = "", baseUrl = "", modelName = "m")
    // 打开「角色能发表情包」，让禁用表的值落进提示词可观测；其余同最短装配路。
    private val settings = AppSettings(
        calendarIntegrationEnabled = false, petSystemEnabled = false, vectorSearchThreshold = 0,
        characterCanSendStickersEnabled = true,
    )

    @Before
    fun setUp() {
        coEvery { conversationRepo.get("conv-1") } returns convo
        mockkObject(TtsService)
        every { TtsService.hasAvailableVoice(any(), any(), any()) } returns false
    }

    @After
    fun tearDown() = unmockkObject(TtsService) // 只清自己 mock 的 object，绝不 unmockkAll

    @Test
    fun 聊天一轮_两个开关都不在调用方线程读_禁用表的值照旧进提示词() = runBlocking {
        DisabledBuiltInStickerStore.disable(app, "大笑_1")
        coEvery { replyDeliverer.deliverAssistantReply(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns DeliveredTurn(
            listOf(MessageEntity(messageUUID = "m1", conversationUuid = "conv-1", roleRaw = "assistant", content = "你好", timestamp = 1L)),
            false,
        )
        val caller = Thread.currentThread()

        engine().runAssistantTurn(config, character, settings, userProfile = null, userMessageForEmbed = null)

        for (name in listOf(STICKER_PREFS, VOICE_TAGS_PREFS)) {
            val touched = context.threadsFor(name)
            assertTrue("$name 应当读过", touched.isNotEmpty())
            assertTrue("$name 不许在调用方线程上读：$touched", touched.none { it === caller })
        }
        val sent = slot<List<ChatMessageDto>>()
        verify(exactly = 1) { llmClient.streamChat(capture(sent), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        val prompt = sent.captured.joinToString("\n") { it.content.orEmpty() }
        assertTrue("未隐藏的内置表情照常进清单", prompt.contains("- 开心_1："))
        assertFalse("被隐藏的内置表情不进清单（禁用表的值照旧生效）", prompt.contains("- 大笑_1："))
    }

    @Test
    fun 语音预判_语气标签开关不在调用方线程读_默认开_关掉读到关() = runBlocking {
        val ttsConfigRepo = mockk<TtsConfigurationRepository> {
            coEvery { getConfiguration() } returns TtsConfiguration()
            coEvery { getApiKey() } returns ""
        }
        suspend fun plan() = resolveVoicePlan(
            character, convo.copy(voiceNextThreshold = 3), emptyList(), settings, "conv-1", ttsConfigRepo, conversationRepo, context,
        )
        val caller = Thread.currentThread()

        assertTrue("没写过开关 = 默认开（同 iOS「没写过 → true」）", plan().capability.userToggleEnabled)
        MiniMaxVoiceTagsSettings.setEnabled(app, false)
        assertFalse("关掉后读到关", plan().capability.userToggleEnabled)

        val touched = context.threadsFor(VOICE_TAGS_PREFS)
        assertEquals("每次预判各读一次", 2, touched.size)
        assertTrue("开关不许在调用方线程上读：$touched", touched.none { it === caller })
    }

    private fun engine() = AssistantTurnEngine(
        scope = CoroutineScope(Dispatchers.Unconfined),
        appContext = context,
        conversationUuid = "conv-1",
        messageRepo = mockk<MessageRepository>(relaxed = true) { coEvery { recentChronological(any(), any()) } returns emptyList() },
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
        llmClient = llmClient,
        vectorMemory = mockk(relaxed = true),
        memoryService = mockk(relaxed = true),
        momentChatContextService = mockk(relaxed = true),
        economicStateService = mockk(relaxed = true),
        ttsConfigRepo = mockk(relaxed = true),
        llmForegroundController = mockk(relaxed = true),
        networkMonitor = mockk<NetworkMonitor>(relaxed = true) { every { isConnected } returns MutableStateFlow(true) },
        contextLog = mockk(relaxed = true),
        notificationScheduler = mockk(relaxed = true),
        momentGenerationService = mockk(relaxed = true),
        replyDeliverer = replyDeliverer,
        calendarHandler = mockk(relaxed = true),
        memoryAnalysisTrigger = mockk(relaxed = true),
        inSceneRecapCoordinator = mockk(relaxed = true),
        relationshipAnalysisTrigger = mockk(relaxed = true),
        meetingDetectionTrigger = mockk(relaxed = true),
        openLoopDetectionTrigger = mockk(relaxed = true),
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

    private companion object {
        /** 与两个 store 的 prefs 文件名同值（它们是 private，这里照抄；改名会让「应当读过」那条先红）。 */
        const val STICKER_PREFS = "sticker_prefs"
        const val VOICE_TAGS_PREFS = "tts_minimax_voice_tags"
    }
}
