package com.situ.aichat.ui.chat

import android.content.Context
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.StickerRepository
import com.situ.aichat.offline.OfflineMeetingAction
import com.situ.aichat.offline.OfflineMeetingActionType
import com.situ.aichat.offline.OfflineMeetingService
import com.situ.aichat.prompt.PromptBuilder.AssistantDeliveryMode
import com.situ.aichat.tts.TtsProviderType
import com.situ.aichat.tts.TtsService
import com.situ.aichat.tts.TtsVoiceProfile
import com.situ.aichat.tts.VoiceResponseChunker
import com.situ.aichat.tts.provider.MiniMaxVoiceTagsCapability
import com.situ.aichat.util.AudioStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ChatReplyDeliverer 行为测试——验证刀7 投递层协作者「真的能用」（不止编译过）。
 *
 * 手法：MockK 假掉 messageRepo/conversationRepo/characterRepo/stickerRepo/ttsService/offlineMeetingService/
 * calendarHandler/appContext；打字/递送/视图可见/错误流用真 MutableStateFlow；ReplyParser/MessageSplitter/
 * ContentFilterService/StickerService/AssistantResponsePreprocessor 用**真实现**（投递的清洗/分段/分发是被测行为）。
 * 语音路用 mockkObject(AudioStore)（只 unmockkObject 自己·绝不 unmockkAll 污染同 JVM 后续测试类）。
 * 覆盖：文字 happy(落库+预览+旗标归位)/空正文返空不落库/线下单段标线下刷活动时间/immediate 兜底单条不记心情/
 * 情绪标签三处写入/语音 happy 落语音条重置轮次/语音合成失败退回文字报错/打字槽开关/线下邀约结构化回合分发卡。
 */
class ChatReplyDelivererTest {

    private lateinit var messageRepo: MessageRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var characterRepo: CharacterRepository
    private lateinit var stickerRepo: StickerRepository
    private lateinit var ttsService: TtsService
    private lateinit var offlineMeetingService: OfflineMeetingService
    private lateinit var calendarHandler: ChatCalendarActionHandler
    private lateinit var appContext: Context
    private lateinit var errorFlow: MutableStateFlow<String?>
    private lateinit var isDelivering: MutableStateFlow<Boolean>
    private lateinit var pendingAssistantSlot: MutableStateFlow<TypingSlot?>
    private lateinit var isViewVisible: MutableStateFlow<Boolean>
    private lateinit var deliverer: ChatReplyDeliverer

    private val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)

    private fun convo(offline: Boolean = false, sessionId: String? = null) = ConversationEntity(
        uuid = "conv-1", title = "标题", characterUuid = "c1", creationDate = 0L,
        isInOfflineMode = offline, currentOfflineSessionId = sessionId,
    )

    /** 默认单段（min=max=1）便于确定性断言；个别用例覆盖。 */
    private fun settings(min: Int = 1, max: Int = 1) = AppSettings(replySegmentMin = min, replySegmentMax = max)

    private fun voicePlan(isVoice: Boolean) = VoicePlan(
        plan = AssistantDeliveryPlan(
            if (isVoice) AssistantDeliveryMode.VOICE else AssistantDeliveryMode.TEXT,
            if (isVoice) AssistantDeliveryReason.SCHEDULED_VOICE else AssistantDeliveryReason.TEXT,
        ),
        capability = MiniMaxVoiceTagsCapability(
            providerType = TtsProviderType.SYSTEM, modelName = "m", characterHasRemoteVoice = false,
            userToggleEnabled = false, isVoiceMode = isVoice, isOfflineMode = false,
        ),
        config = null,
        profile = TtsVoiceProfile(),
        apiKey = "",
        roundsSinceLastVoice = 0,
        threshold = 3,
    )

    @Before
    fun setUp() {
        messageRepo = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        stickerRepo = mockk(relaxed = true)
        ttsService = mockk(relaxed = true)
        offlineMeetingService = mockk(relaxed = true)
        calendarHandler = mockk(relaxed = true)
        appContext = mockk(relaxed = true)
        every { appContext.getString(any<Int>()) } returns "语音合成失败"
        coEvery { stickerRepo.getAllForPrompt() } returns emptyList()
        coEvery { conversationRepo.get("conv-1") } returns convo()
        errorFlow = MutableStateFlow(null)
        isDelivering = MutableStateFlow(false)
        pendingAssistantSlot = MutableStateFlow(null)
        isViewVisible = MutableStateFlow(false) // 不可见 → 跳过打字延迟，测试秒级跑完
        deliverer = ChatReplyDeliverer(
            appContext = appContext,
            conversationUuid = "conv-1",
            messageRepo = messageRepo,
            conversationRepo = conversationRepo,
            characterRepo = characterRepo,
            stickerRepo = stickerRepo,
            ttsService = ttsService,
            offlineMeetingService = offlineMeetingService,
            calendarHandler = calendarHandler,
            errorFlow = errorFlow,
            isDelivering = isDelivering,
            pendingAssistantSlot = pendingAssistantSlot,
            isViewVisible = isViewVisible,
        )
    }

    @Test
    fun 文字happy_落库一条_写会话预览_递送旗标归位() = runBlocking {
        deliverer.openTypingSlot() // F15 修假绿：不开槽则下方 assertNull 恒真（本用例此前从未验过清理行为）
        val slot = mutableListOf<MessageEntity>()
        val result = deliverer.deliverAssistantReply(
            "你好呀今天过得怎么样", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = null,
        )
        coVerify { messageRepo.upsert(capture(slot)) }
        assertEquals(1, slot.size)
        assertEquals("你好呀今天过得怎么样", slot[0].content)
        assertEquals("assistant", slot[0].roleRaw)
        assertEquals(1, result.messages.size)
        coVerify { conversationRepo.recordLastMessageIfNewer("conv-1", any(), "assistant", any()) } // V2：收尾走单调口
        assertFalse(isDelivering.value)
        assertNull(pendingAssistantSlot.value) // 打字占位=渲染层唯一「打字中」信号（S4：旧布尔链已删）
        coVerify(exactly = 0) { conversationRepo.recordMood(any(), any(), any(), any()) } // 无情绪标签 → 不记心情
    }

    @Test
    fun 空正文_返回空_不落库() = runBlocking {
        deliverer.openTypingSlot()
        val result = deliverer.deliverAssistantReply(
            "", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = null,
        )
        assertTrue(result.messages.isEmpty())
        assertFalse(result.deliveredStructuredAction)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
        // E3：正文清洗后为空 = 提前 return，不进 deliverTextReply 的 try/finally → 槽不清，留给 Engine 兜底
        //（空响应重试期间打字三点须继续显示）。
        assertNotNull(pendingAssistantSlot.value)
        assertNull(deliverer.lastOutputJob) // 负向锚：零产出的回合绝不记账，否则起步相位又变回打不断
    }

    @Test
    fun 线下模式_单段_标线下_刷活动时间不写列表预览() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns convo(offline = true, sessionId = "sess-1")
        val slot = mutableListOf<MessageEntity>()
        deliverer.deliverAssistantReply(
            "我们走在街上。一起聊着天。", character, settings(min = 2, max = 4),
            dotsAppearMillis = 0L, immediate = false, voicePlan = null,
        )
        coVerify { messageRepo.upsert(capture(slot)) }
        assertEquals(1, slot.size) // 线下恒单段（不分句），无论分段范围
        assertTrue(slot[0].isOfflineMode)
        assertEquals("sess-1", slot[0].offlineSessionId)
        coVerify { conversationRepo.touchLastMessageDate("conv-1", any()) }
        coVerify(exactly = 0) { conversationRepo.recordLastMessage(any(), any(), any(), any()) }
        coVerify(exactly = 0) { conversationRepo.recordLastMessageIfNewer(any(), any(), any(), any()) } // E13：线下不经预览口
    }

    @Test
    fun immediate兜底_整段单条不分句_不记心情() = runBlocking {
        val slot = mutableListOf<MessageEntity>()
        deliverer.deliverAssistantReply(
            "第一句。第二句。第三句。", character, settings(min = 2, max = 4),
            dotsAppearMillis = 0L, immediate = true, voicePlan = null,
        )
        coVerify { messageRepo.upsert(capture(slot)) }
        assertEquals(1, slot.size) // immediate=取消兜底 → 整条合并单泡
        coVerify(exactly = 0) { conversationRepo.recordMood(any(), any(), any(), any()) }
    }

    @Test
    fun 含情绪标签_记录心情到会话和角色() = runBlocking {
        deliverer.deliverAssistantReply(
            "你好呀[情绪:😊|yellow|开心]", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = null,
        )
        coVerify { conversationRepo.recordMood("conv-1", "😊", "开心", any()) }
        coVerify { characterRepo.updateMood("c1", "😊", "开心", any()) }
        coVerify { characterRepo.appendMoodHistory("c1", any(), 200) } // moodHistoryMaxCount 默认 200
    }

    @Test
    fun 语音happy_合成成功_落语音条_轮次清零() = runBlocking {
        // VoiceResponseChunker 内部用 android.icu（真 Android 框架），纯 JVM 跑不动 → mockkObject 隔离（chunker 自有测试覆盖）。
        mockkObject(AudioStore)
        mockkObject(VoiceResponseChunker)
        deliverer.openTypingSlot()
        try {
            every { VoiceResponseChunker.chunkForVoice(any(), any(), any()) } returns listOf("用语音说句话")
            coEvery { ttsService.synthesize(any(), any(), any(), any(), any()) } returns ByteArray(10)
            coEvery { AudioStore.saveBytes(any(), any(), any()) } returns "audio/x.mp3"
            coEvery { AudioStore.durationSeconds(any()) } returns 3.0
            val slot = mutableListOf<MessageEntity>()
            deliverer.deliverAssistantReply(
                "用语音说句话", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = voicePlan(isVoice = true),
            )
            coVerify { messageRepo.upsert(capture(slot)) }
            val voiceMsg = slot.first { it.isVoiceMessage }
            assertEquals("audio/x.mp3", voiceMsg.audioRelativePath)
            assertEquals(3.0, voiceMsg.audioDuration!!, 0.0001)
            coVerify { conversationRepo.updateVoiceRounds("conv-1", 0, any()) } // 语音发出 → 轮次清 0
            assertNull(pendingAssistantSlot.value) // E5：语音末条落地即清槽（开槽见用例首行，非假绿）
            assertNotNull(deliverer.lastOutputJob) // J8 第 2 记账点：语音路同样记「已产出」
        } finally {
            unmockkObject(AudioStore)
            unmockkObject(VoiceResponseChunker)
        }
    }

    @Test
    fun 语音合成失败_退回文字_置错误提示() = runBlocking {
        mockkObject(VoiceResponseChunker)
        deliverer.openTypingSlot()
        val reservedUuid = pendingAssistantSlot.value!!.uuid
        try {
            every { VoiceResponseChunker.chunkForVoice(any(), any(), any()) } returns listOf("用语音说句话")
            coEvery { ttsService.synthesize(any(), any(), any(), any(), any()) } returns null // 合成失败
            val slot = mutableListOf<MessageEntity>()
            deliverer.deliverAssistantReply(
                "用语音说句话", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = voicePlan(isVoice = true),
            )
            coVerify { messageRepo.upsert(capture(slot)) }
            assertTrue(slot.none { it.isVoiceMessage }) // 退回纯文字
            assertEquals("语音合成失败", errorFlow.value)
            // E4/J2：回落路径上预留 uuid 必须还在（清槽带 stored 非空条件的意义）——首条取的就是开槽时那个 uuid，
            // 否则同 key 原地变身失效，退回「删一行插一行」跳动（契约 B1 根因）。
            assertEquals(reservedUuid, slot.first().messageUUID)
        } finally {
            unmockkObject(VoiceResponseChunker)
        }
    }

    /** T2-1（E1）：末段落地即清打字槽——不再等整个回合 finally，收尾维护期的 typing 判据因此为假（V1）。 */
    @Test
    fun 末段落地即清打字槽_落库uuid取自开槽预留() = runBlocking {
        deliverer.openTypingSlot()
        val reservedUuid = pendingAssistantSlot.value!!.uuid
        val slot = mutableListOf<MessageEntity>()
        deliverer.deliverAssistantReply(
            "今天天气真好", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = null,
        )
        coVerify { messageRepo.upsert(capture(slot)) }
        assertEquals(1, slot.size)
        assertEquals(reservedUuid, slot[0].messageUUID) // 变身链未断：末段用的就是打字槽预留的 uuid
        assertNull(pendingAssistantSlot.value) // 槽已清（改前要等 AssistantTurnEngine 的回合 finally）
        assertNotNull(deliverer.lastOutputJob) // J8 第 1 记账点：本回合已产出 → 收尾相位不再被误判成起步相位
    }

    /**
     * T2-2（E2）：递送中途被取消——已插段定局保留、余段丢弃不落库，且仍翻转会话预览
     * （否则 last 停在 user，恢复系统会把已答会话再答一轮）。
     * 手法：dotsAppearMillis 传 5 秒前 → 首段延迟落到 `maxOf(0.3, minTyping - elapsed)` 的 0.3s 分支，
     * 段间「阅读停顿」2~3s → 700ms 时必然停在「已插 1 段、还在停顿里」，取消点确定。
     */
    @Test
    fun 递送中途取消_已插段保留_余段丢弃_仍翻转预览() = runBlocking {
        isViewVisible.value = true // 可见才有打字延迟；不可见路径 3 段会瞬间落完
        val slot = mutableListOf<MessageEntity>()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch {
            deliverer.deliverAssistantReply(
                "第一句。第二句。第三句。", character, settings(min = 3, max = 3),
                dotsAppearMillis = System.currentTimeMillis() - 5000L, immediate = false, voicePlan = null,
            )
        }
        delay(700)
        job.cancelAndJoin()

        coVerify { messageRepo.upsert(capture(slot)) }
        assertTrue("已插段定局保留", slot.size >= 1)
        assertTrue("未递送段彻底丢弃不落库（REDLINES 三点态打断语义）", slot.size < 3)
        coVerify { conversationRepo.recordLastMessageIfNewer("conv-1", any(), "assistant", any()) }
    }

    /**
     * T2-10/T2-11（E10/E11）：AI 递送收尾的预览写入走**单调**方法——打断瞬间用户新消息（ts 更晚）
     * 的快照可能已落库，绝不用已插段的旧时刻覆写回去。条件真值由真 SQLite 的 T3 覆盖（本层只钉「走哪个口」）。
     */
    @Test
    fun AI递送收尾_预览走单调方法_不走无条件覆写() = runBlocking {
        deliverer.deliverAssistantReply(
            "今天真不错", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = null,
        )
        coVerify(exactly = 1) { conversationRepo.recordLastMessageIfNewer("conv-1", any(), "assistant", any()) }
        coVerify(exactly = 0) { conversationRepo.recordLastMessage(any(), any(), any(), any()) }
    }

    @Test
    fun 打字槽_开则发布占位_关则清空() {
        deliverer.openTypingSlot()
        assertNotNull(pendingAssistantSlot.value)
        deliverer.closeTypingSlot()
        assertNull(pendingAssistantSlot.value)
    }

    @Test
    fun 线下邀约动作_分发卡_算结构化回合_无正文不落库() = runBlocking {
        val action = OfflineMeetingAction(
            action = OfflineMeetingActionType.SUGGEST_MEETING, location = "咖啡馆", activity = "喝咖啡",
        )
        val result = deliverer.deliverAssistantReply(
            "", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = null,
            toolOfflineActions = listOf(action), hasOfflineMeetingToolCall = true,
        )
        assertTrue(result.deliveredStructuredAction)
        assertTrue(result.messages.isEmpty())
        coVerify { offlineMeetingService.handleSuggestMeeting("conv-1", action, emotionTag = null) }
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
        // T2-14（E18）：卡片即回复——正文空也要记「本回合已产出」（J8 第 3 记账点），
        // 否则纯卡片回合的收尾维护期会被误判成起步相位而被用户下一句打断。
        assertNotNull(deliverer.lastOutputJob)
    }

    // ── T2-7（图纸 2026-09-06 约定工具调用化）：暗号动作透传 + immediate 兜底不记账 ──

    @Test
    fun 约定暗号_正常投递时透传动作_标记不进气泡() = runBlocking {
        val raw = "好呀，说定啦～" +
            """[promise]{"action":"record","content":"周六一起去看展","evidence":"那就周六一起去看展吧"}"""
        val slot = mutableListOf<MessageEntity>()
        val result = deliverer.deliverAssistantReply(
            raw, character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = null,
        )
        assertEquals(1, result.promiseMarkerActions.size)
        coVerify { messageRepo.upsert(capture(slot)) }
        assertTrue("标记绝不进气泡", slot.none { it.content.contains("[promise]") })
    }

    @Test
    fun 约定暗号_immediate兜底_不透传动作但仍剥标记() = runBlocking {
        val raw = "好呀，说定啦～" +
            """[promise]{"action":"record","content":"周六一起去看展","evidence":"那就周六一起去看展吧"}"""
        val slot = mutableListOf<MessageEntity>()
        val result = deliverer.deliverAssistantReply(
            raw, character, settings(), dotsAppearMillis = 0L, immediate = true, voicePlan = null,
        )
        assertTrue("取消兜底不从半截回复记账", result.promiseMarkerActions.isEmpty())
        coVerify { messageRepo.upsert(capture(slot)) }
        assertTrue("标记无论如何都已剥离", slot.none { it.content.contains("[promise]") })
    }
}
