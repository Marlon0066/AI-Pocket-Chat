package com.situ.aichat.ui.chat

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.notification.NotificationLearningService
import com.situ.aichat.offline.OfflineMeetingService
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.recovery.RecoveryClaimTracker
import com.situ.aichat.util.ContentImageStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * AssistantTurnController 行为测试——验证「助手回合生命周期 + 发送入口」命脉协作者「真的能用」（不止编译过）。
 * C1（输入排契约 §3.2）后发送编排 = 受理落库 → 合并等待窗 → 窗到期回合；三点态打断丢弃。
 *
 * 手法：MockK 假掉全部协作者；scope 用 [Dispatchers.Unconfined]（发送 launch 体同步跑完，确定性断言并发/落库/触发）；
 * dispatcher 用**真** [ChatMessageDispatcher]（delayMs 注入空实现 = 窗同步到期，合并/重置语义在 ChatMessageDispatcherTest 单测）；
 * isSending/errorFlow/isDelivering/replyTarget/typingSlot 用真 MutableStateFlow；StreakManager 纯逻辑放真跑。
 *
 * 覆盖：发文字（空文本拒/空闲落库+受理即嵌+窗到期回合+isSending 复位/无 API 消息仍落库+窗回合报错不触发[U1 健壮]/
 * 忙碌收纳不入窗/维护相位发送=受理不打断/三点态发送=打断+丢弃旗标+新回合）、发表情（落贴纸+入窗）、
 * 发语音草稿（无草稿空操作/无 API 报错/happy 落语音消息+预览+入窗）、重新生成（删尾段重跑/取消存活/无尾段不动）、
 * 串行化包装（发送中跳过/空闲跑+复位）、当前会话回合（无配置报错/happy）、断网重试（线下跳过/末条 user 触发）。
 */
class AssistantTurnControllerTest {

    private lateinit var db: AppDatabase
    private lateinit var messageRepo: MessageRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var characterRepo: CharacterRepository
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var userProfileDao: UserProfileDao
    private lateinit var notificationLearningService: NotificationLearningService
    private lateinit var offlineMeetingService: OfflineMeetingService
    private lateinit var recoveryClaimTracker: RecoveryClaimTracker
    private lateinit var assistantTurnEngine: AssistantTurnEngine
    private lateinit var replyDeliverer: ChatReplyDeliverer
    private lateinit var voiceController: ChatVoiceController
    private lateinit var vectorMemory: VectorMemoryService
    private lateinit var appContext: Context
    private lateinit var conversationFlow: MutableStateFlow<ConversationEntity?>
    private lateinit var typingSlot: MutableStateFlow<TypingSlot?>
    private lateinit var isSending: MutableStateFlow<Boolean>
    private lateinit var errorFlow: MutableStateFlow<String?>
    private lateinit var isDelivering: MutableStateFlow<Boolean>
    private lateinit var replyTarget: MutableStateFlow<MessageEntity?>
    private lateinit var controller: AssistantTurnController

    private val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)

    private fun convo(offlineSessionId: String? = null, lastRole: String = "assistant", lastPreview: String = "上一条") =
        ConversationEntity(
            uuid = "conv-1", title = "标题", characterUuid = "c1", creationDate = 0L,
            isInOfflineMode = offlineSessionId != null, currentOfflineSessionId = offlineSessionId,
            lastMessageRole = lastRole, lastMessagePreview = lastPreview,
        )

    private fun userMsg(content: String = "嗨") = MessageEntity(
        messageUUID = "u1", conversationUuid = "conv-1", roleRaw = "user", content = content, timestamp = 1L,
    )

    private fun assistantMsg(uuid: String) = MessageEntity(
        messageUUID = uuid, conversationUuid = "conv-1", roleRaw = "assistant", content = "回", timestamp = 2L,
    )

    @Before
    fun setUp() {
        // maybeAutoRetryAfterReconnect 体内有 android.util.Log.d（try 外），纯 JVM 须 mockkStatic 假掉否则未 mock 抛错中断。
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        // 2-4+3-1：db.withTransaction 扩展函数可桩（同 MeetingMissedReactionServiceTest 打法）——
        // 单测里事务=同步跑 block（验证编排，不验真原子性）。
        mockkStatic("androidx.room.RoomDatabaseKt")
        db = mockk()
        coEvery { db.withTransaction<Unit>(any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
        messageRepo = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        apiConfigRepo = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        userProfileDao = mockk(relaxed = true)
        notificationLearningService = mockk(relaxed = true)
        offlineMeetingService = mockk(relaxed = true)
        recoveryClaimTracker = mockk(relaxed = true)
        assistantTurnEngine = mockk(relaxed = true)
        replyDeliverer = mockk(relaxed = true)
        voiceController = mockk(relaxed = true)
        vectorMemory = mockk(relaxed = true)
        appContext = mockk(relaxed = true)
        every { appContext.getString(any()) } returns "语音转写失败"
        conversationFlow = MutableStateFlow(convo())
        typingSlot = MutableStateFlow(null)
        isSending = MutableStateFlow(false)
        errorFlow = MutableStateFlow(null)
        isDelivering = MutableStateFlow(false)
        replyTarget = MutableStateFlow(null)
        // 默认：会话/角色存在、API 已配置（文字路）、空闲。
        coEvery { conversationRepo.get("conv-1") } returns convo()
        coEvery { characterRepo.get("c1") } returns character
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) } returns
            mockk<ApiConfigValues>(relaxed = true) { every { audioInputEnabled } returns false }
        // 2-10：窗到期回合占坑互斥——默认坑空（占到），冲突用例单独改 false。
        every { recoveryClaimTracker.tryBegin(any()) } returns true
        every { voiceController.voiceDraft } returns MutableStateFlow(null)
        controller = buildController(CoroutineScope(Dispatchers.Unconfined))
    }

    /** 内存持久层（dispatcher 用·默认等待值走 DEFAULT 单源）。 */
    private class FakeDispatcherPersistence : ChatMessageDispatcher.Persistence {
        var waitSeconds: Float? = null
        var timestamps: List<Long> = emptyList()
        override suspend fun loadWaitSeconds(): Float? = waitSeconds
        override suspend fun saveWaitSeconds(value: Float) { waitSeconds = value }
        override suspend fun loadSendTimestamps(): List<Long> = timestamps
        override suspend fun saveSendTimestamps(values: List<Long>) { timestamps = values }
    }

    /** 同一套 mock 组一个 controller；scope 外提供「取消存活」类测试注入可取消的 Job（审计 R1）。
     *  dispatcher = 真实例 + delayMs 空实现：enqueue 即同步到期（Unconfined），窗语义单测在 ChatMessageDispatcherTest。 */
    private fun buildController(scope: CoroutineScope) = AssistantTurnController(
        scope = scope,
        appContext = appContext,
        conversationUuid = "conv-1",
        db = db,
        messageRepo = messageRepo,
        conversationRepo = conversationRepo,
        characterRepo = characterRepo,
        apiConfigRepo = apiConfigRepo,
        settingsRepo = settingsRepo,
        userProfileDao = userProfileDao,
        notificationLearningService = notificationLearningService,
        offlineMeetingService = offlineMeetingService,
        recoveryClaimTracker = recoveryClaimTracker,
        assistantTurnEngine = assistantTurnEngine,
        replyDeliverer = replyDeliverer,
        voiceController = voiceController,
        vectorMemory = vectorMemory,
        imageMemorySummaryService = mockk(relaxed = true),
        dispatcher = ChatMessageDispatcher(scope, FakeDispatcherPersistence(), delayMs = { }),
        typingSlot = typingSlot,
        conversationFlow = conversationFlow,
        isSending = isSending,
        errorFlow = errorFlow,
        isDelivering = isDelivering,
        replyTarget = replyTarget,
    )

    /** 2-5b 用例专用：controller 与 dispatcher 各自作用域（模拟「回合=应用级、计时器=viewModelScope」的生死分离）。 */
    private fun buildControllerWith(scope: CoroutineScope, dispatcher: ChatMessageDispatcher) = AssistantTurnController(
        scope = scope,
        appContext = appContext,
        conversationUuid = "conv-1",
        db = db,
        messageRepo = messageRepo,
        conversationRepo = conversationRepo,
        characterRepo = characterRepo,
        apiConfigRepo = apiConfigRepo,
        settingsRepo = settingsRepo,
        userProfileDao = userProfileDao,
        notificationLearningService = notificationLearningService,
        offlineMeetingService = offlineMeetingService,
        recoveryClaimTracker = recoveryClaimTracker,
        assistantTurnEngine = assistantTurnEngine,
        replyDeliverer = replyDeliverer,
        voiceController = voiceController,
        vectorMemory = vectorMemory,
        imageMemorySummaryService = mockk(relaxed = true),
        dispatcher = dispatcher,
        typingSlot = typingSlot,
        conversationFlow = conversationFlow,
        isSending = isSending,
        errorFlow = errorFlow,
        isDelivering = isDelivering,
        replyTarget = replyTarget,
    )

    @After
    fun tearDown() {
        unmockkStatic(Log::class) // 只卸自己 mock 的 Log，绝不 unmockkAll 污染同 JVM 后续测试类。
        unmockkStatic("androidx.room.RoomDatabaseKt")
        unmockkObject(ContentImageStore) // 发图用例才 mock 它；没 mock 过时本调用是空操作。
    }

    // ────────────────── 健康线 2-5b（IM 语义·用户拍板 2026-07-03）──────────────────

    /** 2-5b：等待窗未到期就退出会话 → disposeOnCleared 立即起窗回合（回合作用域独立存活，回复照常落库）。 */
    @Test
    fun `等待窗未到期退出_dispose立即起窗回合`() = runBlocking {
        val dispatcherScope = CoroutineScope(Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val dispatcher = ChatMessageDispatcher(dispatcherScope, FakeDispatcherPersistence(), delayMs = { gate.await() })
        controller = buildControllerWith(CoroutineScope(Dispatchers.Unconfined), dispatcher)

        assertTrue(controller.send("在吗"))
        assertTrue("窗挂起中", dispatcher.windowArmed)
        coVerify(exactly = 0) { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }

        dispatcherScope.cancel() // 模拟 VM 清理：viewModelScope 先死
        controller.disposeOnCleared()

        coVerify(exactly = 1) { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
        assertFalse("回合收尾复位", isSending.value)
        verify { recoveryClaimTracker.tryBegin("conv-1") }
        verify { recoveryClaimTracker.end("conv-1") }
    }

    /** 2-5b 秒退兜底：发送流程晚于 VM 清理走到入窗（计时器已死）→ 不等窗直接起回合。 */
    @Test
    fun `计时器已死时受理_直接起回合`() = runBlocking {
        val deadScope = CoroutineScope(Dispatchers.Unconfined).also { it.cancel() }
        val dispatcher = ChatMessageDispatcher(deadScope, FakeDispatcherPersistence(), delayMs = { })
        controller = buildControllerWith(CoroutineScope(Dispatchers.Unconfined), dispatcher)

        assertTrue(controller.send("在吗"))

        coVerify(exactly = 1) { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
        coVerify { messageRepo.upsert(match { it.roleRaw == "user" && it.content == "在吗" }) }
    }

    /** 2-5b：窗已正常开火（未武装）时退出 → dispose 不再多起回合。 */
    @Test
    fun `窗已开火后退出_dispose不重复起回合`() = runBlocking {
        controller.send("在吗") // 默认夹具 delayMs={} → 窗同步到期，回合已跑
        coVerify(exactly = 1) { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }

        controller.disposeOnCleared()

        coVerify(exactly = 1) { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
    }

    // ────────────────── 健康线 2-4 + 3-1（跨线对表落地·CHAT_CORE_HEALTH_PLAN）──────────────────

    /** 2-4：发送后秒退（scope 取消）——NonCancellable 落库段照常走完，消息与预览快照不丢。 */
    @Test
    fun `send后scope取消_落库段不可取消仍完成`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        coEvery { messageRepo.upsert(any()) } coAnswers { gate.await() }
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val c = buildController(scope)
        assertTrue(c.send("你好"))
        scope.cancel() // 秒退：VM 清理取消 viewModelScope（落库悬在 upsert 挂起点上）
        gate.complete(Unit) // 无 NonCancellable 此处恢复即抛取消、预览翻转丢失；有则事务走完
        coVerify(exactly = 1) { conversationRepo.recordLastMessage("conv-1", "你好", "user", any()) }
    }

    /** 3-1：用户消息落库与会话预览快照包同一事务（db.withTransaction 单笔·中途死不裂快照）。 */
    @Test
    fun `send_落库与预览同一事务`() = runBlocking {
        controller.send("嗨")
        coVerify(exactly = 1) { db.withTransaction<Unit>(any()) }
        coVerify(exactly = 1) { messageRepo.upsert(match { it.content == "嗨" && it.roleRaw == "user" }) }
        coVerify(exactly = 1) { conversationRepo.recordLastMessage("conv-1", "嗨", "user", any()) }
    }

    /** 2-10：无头回合在飞（占坑失败）→ 窗回合让位重排，坑释放后下一窗接管作答——绝不并发双答。 */
    @Test
    fun `窗回合_无头占坑时让位重排_坑释放后接管`() = runBlocking {
        every { recoveryClaimTracker.tryBegin("conv-1") } returns false andThen true // 首窗冲突→重排窗占到
        controller.send("嗨")
        coVerify(exactly = 1) { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), userMessageForEmbed = null) }
        verify(exactly = 2) { recoveryClaimTracker.tryBegin("conv-1") } // 让位一次+接管一次
        verify(exactly = 1) { recoveryClaimTracker.end("conv-1") } // 只有真跑的那窗释放
    }

    /** 2-10：窗回合正常跑完释放占坑（finally 兜异常/取消）。 */
    @Test
    fun `窗回合_跑完释放占坑`() = runBlocking {
        controller.send("嗨")
        verify(exactly = 1) { recoveryClaimTracker.tryBegin("conv-1") }
        verify(exactly = 1) { recoveryClaimTracker.end("conv-1") }
    }

    // ────────────────────────── 发文字 ──────────────────────────

    @Test
    fun 发文字_空文本_拒绝不落库() {
        assertFalse(controller.send("   "))
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    @Test
    fun 发文字_空闲_落库用户消息_触发回合_isSending复位() = runBlocking {
        val slot = mutableListOf<MessageEntity>()
        assertTrue(controller.send("你好呀"))
        coVerify { messageRepo.upsert(capture(slot)) }
        assertEquals("你好呀", slot.last().content)
        assertEquals("user", slot.last().roleRaw)
        coVerify { conversationRepo.recordLastMessage("conv-1", "你好呀", "user", any()) }
        coVerify { vectorMemory.embedMessageIfNeeded(any()) } // C1 受理即嵌（窗回合 userMessageForEmbed=null）
        coVerify { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
        assertFalse(isSending.value)
    }

    /** 卷一 A1：见面中用户消息不顶列表预览（方案 A 同源），只刷新最后活动时间；消息本体照常落库并打线下标。 */
    @Test
    fun 发文字_见面中_只刷新时间不写预览() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns convo(offlineSessionId = "sess-1")
        assertTrue(controller.send("你好呀"))
        coVerify(exactly = 1) { messageRepo.upsert(match { it.content == "你好呀" && it.isOfflineMode }) }
        coVerify(exactly = 1) { conversationRepo.touchLastMessageDate("conv-1", any()) }
        coVerify(exactly = 0) { conversationRepo.recordLastMessage(any(), any(), any(), any()) }
    }

    /** 卷一 A1：表情包路同源（三入口共用 storeUserMessage）。 */
    @Test
    fun 发表情_见面中_只刷新时间不写预览() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns convo(offlineSessionId = "sess-1")
        controller.sendStickerMessage("s1")
        coVerify(exactly = 1) { conversationRepo.touchLastMessageDate("conv-1", any()) }
        coVerify(exactly = 0) { conversationRepo.recordLastMessage(any(), any(), any(), any()) }
    }

    @Test
    fun 发文字_无API配置_用户消息仍落库_报错_不触发回合() = runBlocking {
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) } returns null
        assertTrue(controller.send("你好"))
        coVerify { messageRepo.upsert(any()) } // U1：消息先落库，闸门在落库之后，不丢输入
        assertEquals("请先在「我 → API 配置」添加并启用一个 API", errorFlow.value)
        coVerify(exactly = 0) { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
    }

    @Test
    fun 发文字_维护相位无三点_受理入窗_不打断() = runBlocking {
        // C1：isSending=true 但三点未亮（收尾维护相位）→ 受理照常，不再拒发；无活动 job 可 join，窗回合直接跑。
        isSending.value = true
        assertTrue(controller.send("x"))
        coVerify { messageRepo.upsert(any()) }
        coVerify { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
    }

    @Test
    fun 发文字_三点态_打断当前回合_新回合接续() = runBlocking {
        // C1（契约 §3.2-3）：AI 话没说完（typing 槽亮）再发 → 取消在跑回合（健康线 2-5 后取消语义
        // 全局统一=丢弃未出现内容，无需旗标），随后窗到期起新回合统一作答。
        val firstTurnGate = CompletableDeferred<Unit>()
        var engineRuns = 0
        coEvery { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) } coAnswers {
            engineRuns++
            if (engineRuns == 1) firstTurnGate.await() // 第一回合挂住模拟流式中
        }
        assertTrue(controller.send("第一条")) // 起第一回合（窗同步到期）→ 引擎挂在 gate
        assertEquals(1, engineRuns)
        typingSlot.value = TypingSlot("slot-1") // 模拟引擎 openTypingSlot（三点亮）

        assertTrue(controller.send("第二条")) // 三点态再发 → 打断 + 受理 + 新窗到期新回合
        assertEquals(2, engineRuns) // 新回合已跑（join 已取消的旧回合后接续）
        val stored = mutableListOf<MessageEntity>()
        coVerify { messageRepo.upsert(capture(stored)) }
        assertEquals(listOf("第一条", "第二条"), stored.map { it.content })
    }

    // ────────────────────────── 发表情 ──────────────────────────

    @Test
    fun 发表情_落贴纸消息_触发回合() = runBlocking {
        val slot = mutableListOf<MessageEntity>()
        controller.sendStickerMessage("sticker_42")
        coVerify { messageRepo.upsert(capture(slot)) }
        assertTrue(slot.last().content.contains("sticker_42"))
        coVerify { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
    }

    // ────────────────────────── 发语音草稿 ──────────────────────────

    @Test
    fun 发语音草稿_无草稿_空操作() = runBlocking {
        every { voiceController.voiceDraft } returns MutableStateFlow(null)
        controller.sendVoiceDraft()
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
        assertFalse(isSending.value)
    }

    @Test
    fun 发语音草稿_无API配置_报错() = runBlocking {
        every { voiceController.voiceDraft } returns
            MutableStateFlow(VoiceDraftState("d1", "a.mp3", 2.0, "你好", isTranscriptPending = false))
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) } returns null
        controller.sendVoiceDraft()
        assertEquals("请先在「我 → API 配置」添加并启用一个 API", errorFlow.value)
        coVerify(exactly = 0) { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
    }

    @Test
    fun 发语音草稿_happy_落语音消息_预览带语音前缀_入窗触发() = runBlocking {
        every { voiceController.voiceDraft } returns
            MutableStateFlow(VoiceDraftState("d1", "a.mp3", 2.0, "早上好", isTranscriptPending = false))
        controller.sendVoiceDraft()
        val stored = mutableListOf<MessageEntity>()
        coVerify { messageRepo.upsert(capture(stored)) }
        assertTrue(stored.last().isVoiceMessage)
        assertEquals("早上好", stored.last().content)
        coVerify { conversationRepo.recordLastMessage("conv-1", "[语音] 早上好", "user", any()) }
        coVerify { voiceController.consumeDraftOnSend(any()) }
        coVerify { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
    }

    // ────────────────────────── 重新生成 ──────────────────────────

    @Test
    fun 重新生成_删尾段assistant_重跑回合() = runBlocking {
        coEvery { messageRepo.recentChronological("conv-1", any()) } returns
            listOf(userMsg(), assistantMsg("a1"), assistantMsg("a2"))
        controller.regenerate()
        coVerify { messageRepo.deleteByUuid("a1") }
        coVerify { messageRepo.deleteByUuid("a2") }
        coVerify(exactly = 0) { messageRepo.deleteByUuid("u1") } // 用户消息不删
        coVerify { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
    }

    @Test
    fun 重新生成_删除中途VM清理取消_仍删净并重算快照() = runBlocking {
        // 审计 R1：点「重新生成」后立刻退出会话（VM 清理取消 scope）——NonCancellable 保证尾段删净 + 快照重算，
        // 绝不留「删一半 + 列表预览停在已删消息」的半截状态；新回合则不再跑（用户已离开）。
        val scopeJob = Job()
        val cancellable = buildController(CoroutineScope(scopeJob + Dispatchers.Unconfined))
        val gate = CompletableDeferred<Unit>()
        coEvery { messageRepo.recentChronological("conv-1", any()) } returns
            listOf(userMsg(), assistantMsg("a1"), assistantMsg("a2"))
        coEvery { messageRepo.deleteByUuid("a1") } coAnswers { gate.await() }

        cancellable.regenerate() // Unconfined：同步跑到首个删除处挂起（gate）
        scopeJob.cancel() // 模拟删除中途退出会话
        gate.complete(Unit) // 放行首个删除 → NonCancellable 体内继续跑完

        coVerify(exactly = 1) { messageRepo.deleteByUuid("a2") } // 尾段删净（无 R1 修复时此处永不执行）
        coVerify(exactly = 1) { messageRepo.latestVisibleMessage("conv-1") } // 快照重算真的跑了
        coVerify(exactly = 0) { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
    }

    @Test
    fun 重新生成_无尾段assistant_不删不跑() = runBlocking {
        coEvery { messageRepo.recentChronological("conv-1", any()) } returns
            listOf(assistantMsg("a1"), userMsg()) // 末条是 user
        controller.regenerate()
        coVerify(exactly = 0) { messageRepo.deleteByUuid(any()) }
        coVerify(exactly = 0) { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
    }

    // ────────────────────────── 串行化包装 ──────────────────────────

    @Test
    fun 串行化_发送中_跳过block() {
        isSending.value = true
        var ran = false
        controller.launchSerializedTurn { ran = true }
        assertFalse(ran) // 并发闸：已在发送中 → 丢弃本次
    }

    @Test
    fun 串行化_空闲_跑block_isSending复位() = runBlocking {
        var ran = false
        controller.launchSerializedTurn { ran = true }
        assertTrue(ran)
        assertFalse(isSending.value) // finally 复位
    }

    // ────────────────────────── 当前会话回合 ──────────────────────────

    @Test
    fun 当前会话回合_无配置_报错不触发() = runBlocking {
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) } returns null
        controller.runAssistantTurnForCurrentConversation()
        assertEquals("请先在「我 → API 配置」添加并启用一个 API", errorFlow.value)
        coVerify(exactly = 0) { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
    }

    @Test
    fun 当前会话回合_happy_触发引擎() = runBlocking {
        controller.runAssistantTurnForCurrentConversation()
        coVerify { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
    }

    // ────────────────────────── 断网重试 ──────────────────────────

    @Test
    fun 断网重试_线下会话_跳过() {
        conversationFlow.value = convo(offlineSessionId = "sess-1")
        controller.maybeAutoRetryAfterReconnect()
        coVerify(exactly = 0) { messageRepo.recentChronological(any(), any()) } // 线下有独立恢复逻辑，直接 return
    }

    @Test
    fun 断网重试_末条用户消息_触发回合() = runBlocking {
        conversationFlow.value = convo(offlineSessionId = null)
        coEvery { messageRepo.recentChronological("conv-1", 1) } returns listOf(userMsg())
        coEvery { recoveryClaimTracker.tryBegin("conv-1") } returns true
        controller.maybeAutoRetryAfterReconnect()
        coVerify { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
        coVerify { recoveryClaimTracker.end("conv-1") }
    }

    // ────────────────── D3 线下重进分档（autoRecoverUnansweredMessage·2026-07-07）──────────────────
    // 旧缺陷：见面期间 convo.lastMessageRole 恒为入场 hint 的 "user"，每次进屏都被当「未答」无条件推进一拍。

    /** 线下 session 消息：距现在 [ageMs] 毫秒前落库。 */
    private fun offlineMsg(role: String, ageMs: Long) = MessageEntity(
        messageUUID = "om-$role-$ageMs", conversationUuid = "conv-1", roleRaw = role, content = "内容",
        timestamp = System.currentTimeMillis() - ageMs, isOfflineMode = true, offlineSessionId = "sess-1",
    )

    private fun stubOfflineConversation(last: MessageEntity) {
        coEvery { conversationRepo.get("conv-1") } returns
            convo(offlineSessionId = "sess-1", lastRole = "user", lastPreview = "正在见面中…")
        coEvery { messageRepo.offlineSessionMessages("conv-1", "sess-1") } returns listOf(last)
    }

    @Test
    fun 线下重进_已答且离开不足3分钟_静默不推进() = runBlocking {
        stubOfflineConversation(offlineMsg("assistant", ageMs = 60_000L))
        controller.autoRecoverUnansweredMessage(startDelayMs = 0)
        coVerify(exactly = 0) { offlineMeetingService.insertReturnAfterAwayHint(any(), any()) }
        coVerify(exactly = 0) { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
    }

    @Test
    fun 线下重进_已答离开5分钟_插归来hint并推进一拍() = runBlocking {
        stubOfflineConversation(offlineMsg("assistant", ageMs = 5 * 60_000L))
        coEvery { settingsRepo.getAppSettings() } returns AppSettings()
        coEvery { offlineMeetingService.insertReturnAfterAwayHint(any(), any()) } returns true
        controller.autoRecoverUnansweredMessage(startDelayMs = 0)
        coVerify { offlineMeetingService.insertReturnAfterAwayHint("conv-1", 5L) }
        coVerify { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
        verify { recoveryClaimTracker.end("conv-1") }
    }

    @Test
    fun 线下重进_已答离开超10分钟_交恢复弹窗不自动推进() = runBlocking {
        stubOfflineConversation(offlineMsg("assistant", ageMs = 30 * 60_000L))
        controller.autoRecoverUnansweredMessage(startDelayMs = 0)
        coVerify(exactly = 0) { offlineMeetingService.insertReturnAfterAwayHint(any(), any()) }
        coVerify(exactly = 0) { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
    }

    @Test
    fun 线下重进_末条未获回答_任意时长照旧恢复() = runBlocking {
        stubOfflineConversation(offlineMsg("user", ageMs = 30 * 60_000L))
        coEvery { settingsRepo.getAppSettings() } returns AppSettings()
        controller.autoRecoverUnansweredMessage(startDelayMs = 0)
        // 真未答（被杀的线下回合）→ 通用恢复照跑；不插归来 hint。
        coVerify(exactly = 0) { offlineMeetingService.insertReturnAfterAwayHint(any(), any()) }
        coVerify { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) }
        verify { recoveryClaimTracker.end("conv-1") }
    }

    // ────────────── 发图链接线（R4 🔵-3）──────────────

    /**
     * 摘要落库后**必须**走 `embedImageMessageAfterSummary`（跳过推迟闸）那个入口，而不是常规的
     * `embedMessageIfNeeded`。
     *
     * 为什么值得单钉一条：`ChatImageSenderTest` 验的是「sender 会调注入的 lambda」（用的是假 lambda），
     * `ImageEmbeddingDeferralTest` 验的是「两个入口口径不同」——**中间这一行「lambda 到底指向哪个方法」
     * 两头都没碰**，改回 `embedMessageIfNeeded` 全绿（R4 🔵-3）。改回去的后果：摘要为空的兜底路径上
     * 推迟闸 100% 挡住，这条消息要等冷启动回填才有机会进索引。
     */
    @Test
    fun `发图_摘要跑完走跳过推迟闸的那个入口`() = runBlocking {
        mockkObject(ContentImageStore)
        coEvery { ContentImageStore.saveWithThumbnail(any(), any(), any(), any()) } returns
            ContentImageStore.StoredImage(path = "/img/1.jpg", thumbnailPath = "/img/1_t.jpg")
        // 摘要落库后按 uuid 重取最新实体：给一条带图消息，否则 `?.let` 整段跳过、断言测了个寂寞
        coEvery { messageRepo.get(any()) } answers {
            MessageEntity(
                messageUUID = firstArg(), conversationUuid = "conv-1", roleRaw = "user",
                content = "[图片]", timestamp = 1L, imageRelativePath = "/img/1.jpg",
                mediaMemorySummary = "海边的黄昏",
            )
        }

        controller.sendImages(listOf(mockk<android.net.Uri>()))

        coVerify(exactly = 1) { vectorMemory.embedImageMessageAfterSummary(match { it.imageRelativePath == "/img/1.jpg" }) }
    }
}
