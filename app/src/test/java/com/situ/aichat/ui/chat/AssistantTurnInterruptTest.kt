package com.situ.aichat.ui.chat

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.NotificationLearningService
import com.situ.aichat.offline.OfflineMeetingService
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.recovery.RecoveryClaimTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.coroutineContext

/**
 * 三点态打断的**相位判据**行为钉（图纸 2026-09-04 §3.5 四相位状态机 · 契约
 * FABLE5_CHAT_INPUT_BAR_PROPOSAL §3.2-3）。为什么单开一类：
 *
 * `AssistantTurnController.interruptUndisplayedReplyIfAny` 的身份校验
 * `job === replyDeliverer.deliveringJob` 一旦被未来某次重构（例如在 Engine→Deliverer 调用链里插一个
 * `withContext`）打断，症状只是「偶尔打不断」——没有任何自动化会报警。既有
 * `AssistantTurnControllerTest.发文字_三点态_打断当前回合_新回合接续` 只覆盖「流式期（typing 槽亮）」
 * 一相，且只断言「新回合起来了」，不直接断言旧回合真被取消，更不覆盖递送期 / 身份校验 / 收尾维护期。
 *
 * 手法（照 [AssistantTurnControllerTest] 的组装范式）：MockK 假掉全部协作者；scope = [Dispatchers.Unconfined]
 * （send 的 launch 体同步跑完，断言确定性）；dispatcher = 真实例 + `delayMs = { }`（等待窗即时到期）；
 * typingSlot / isDelivering 注入可操纵 [MutableStateFlow]，`deliveringJob` 用 `every` 直接摆布；
 * `runAssistantTurn` 打桩成 [awaitCancellation]（回合挂住 = 可观测「到底有没有被取消」）。
 *
 * 断言从规格（图纸 §5 的 E6–E9 / E17–E19 / E21）独立反推：钉「取消发生了没有」，不钉实现怎么写的。
 */
class AssistantTurnInterruptTest {

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
    private lateinit var typingSlot: MutableStateFlow<TypingSlot?>
    private lateinit var isSending: MutableStateFlow<Boolean>
    private lateinit var errorFlow: MutableStateFlow<String?>
    private lateinit var isDelivering: MutableStateFlow<Boolean>
    private lateinit var turnScope: CoroutineScope
    private lateinit var controller: AssistantTurnController

    /** 回合观测量：起了几次 / 被取消几次 / 第一回合自身的 Job（喂给 deliveringJob 做身份校验）。 */
    private var turnStarts = 0
    private var turnCancels = 0
    private var firstTurnJob: Job? = null

    private val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)

    private fun convo() = ConversationEntity(
        uuid = "conv-1", title = "标题", characterUuid = "c1", creationDate = 0L,
        lastMessageRole = "assistant", lastMessagePreview = "上一条",
    )

    /** 内存持久层（dispatcher 用）。 */
    private class FakeDispatcherPersistence : ChatMessageDispatcher.Persistence {
        private var waitSeconds: Float? = null
        private var timestamps: List<Long> = emptyList()
        override suspend fun loadWaitSeconds(): Float? = waitSeconds
        override suspend fun saveWaitSeconds(value: Float) { waitSeconds = value }
        override suspend fun loadSendTimestamps(): List<Long> = timestamps
        override suspend fun saveSendTimestamps(values: List<Long>) { timestamps = values }
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
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
        coEvery { conversationRepo.get("conv-1") } returns convo()
        coEvery { characterRepo.get("c1") } returns character
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) } returns
            mockk<ApiConfigValues>(relaxed = true) { every { audioInputEnabled } returns false }
        every { recoveryClaimTracker.tryBegin(any()) } returns true
        every { replyDeliverer.lastOutputJob } returns null // 默认「本回合尚未产出」= 起步相位
        every { voiceController.voiceDraft } returns MutableStateFlow(null)
        typingSlot = MutableStateFlow(null)
        isSending = MutableStateFlow(false)
        errorFlow = MutableStateFlow(null)
        isDelivering = MutableStateFlow(false)
        // 回合挂住不返回 = 可观测相位；取消时记一笔再原样抛出（协程取消语义不许吞）。
        coEvery { assistantTurnEngine.runAssistantTurn(any(), any(), any(), any(), any()) } coAnswers {
            turnStarts++
            if (turnStarts == 1) firstTurnJob = coroutineContext[Job]
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                turnCancels++
                throw e
            }
        }
        turnScope = CoroutineScope(Dispatchers.Unconfined)
        controller = AssistantTurnController(
            scope = turnScope,
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
            dispatcher = ChatMessageDispatcher(turnScope, FakeDispatcherPersistence(), delayMs = { }),
            typingSlot = typingSlot,
            conversationFlow = MutableStateFlow(convo()),
            isSending = isSending,
            errorFlow = errorFlow,
            isDelivering = isDelivering,
            replyTarget = MutableStateFlow<MessageEntity?>(null),
        )
    }

    @After
    fun tearDown() {
        turnScope.cancel() // 挂住的回合在此收尾，绝不把孤儿协程漏给同 JVM 后续测试类（PITFALLS §1e）
        unmockkStatic(Log::class) // 只卸自己 mock 的，绝不 unmockkAll
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    /** 起一个挂住的回合，并确认它真的在跑（后续用例的公共前置）。 */
    private fun startHangingTurn() {
        controller.send("第一句")
        assertEquals("第一回合应已起（窗同步到期）", 1, turnStarts)
        assertNotNull("第一回合的 Job 应已捕获", firstTurnJob)
        assertEquals(0, turnCancels)
    }

    /** T2-6（E7）：流式生成期（打字槽亮）用户再发 → 打断，新回合接续。 */
    @Test
    fun 流式期发消息_打断在跑回合_新回合接续() = runBlocking {
        startHangingTurn()
        typingSlot.value = TypingSlot("slot-1") // 引擎 openTypingSlot 后的状态
        isDelivering.value = false

        controller.send("第二句")

        assertEquals("流式期必须打断（契约 §3.2-3）", 1, turnCancels)
        assertEquals("打断后窗到期新回合接续", 2, turnStarts)
    }

    /** T2-7（E8）：分段递送期（递送旗标真 + deliveringJob 身份匹配）用户再发 → 打断。 */
    @Test
    fun 递送期发消息_身份匹配_打断在跑回合() = runBlocking {
        startHangingTurn()
        typingSlot.value = null // 末段已落地（V1 后收尾/递送都可能为 null，靠 isDelivering 区分）
        isDelivering.value = true
        every { replyDeliverer.deliveringJob } returns firstTurnJob

        controller.send("第二句")

        assertEquals("递送期必须打断", 1, turnCancels)
        assertEquals("打断后窗到期新回合接续", 2, turnStarts)
    }

    /** T2-8（E6）：收尾维护期（三点未亮、未在递送）用户再发 → 不打断，维护相位跑完。 */
    @Test
    fun 收尾维护期发消息_不打断维护相位() = runBlocking {
        startHangingTurn()
        typingSlot.value = null
        isDelivering.value = false
        // V3 后收尾与起步的 typingSlot/isDelivering 输入完全相同，唯一区别 = 本回合产出过内容（末段已落库）。
        every { replyDeliverer.lastOutputJob } returns firstTurnJob

        controller.send("第二句")

        assertEquals("收尾维护期不在可打断相位内（契约 §3.2-3 只列流式中/分段间隙）", 0, turnCancels)
        assertEquals("新回合排在 previousJob.join() 后，旧回合未结束前不起", 1, turnStarts)
    }

    /**
     * T2-9（E9）：递送旗标为真、但 `deliveringJob` 是**另一个** Job（排队的后继回合登记过）→ 不打断。
     * 这条钉的就是身份校验本身：去掉 `job === replyDeliverer.deliveringJob` 后本例立刻转红。
     */
    @Test
    fun 递送旗标为真但身份不符_不误杀在跑回合() = runBlocking {
        startHangingTurn()
        typingSlot.value = null
        isDelivering.value = true
        every { replyDeliverer.deliveringJob } returns Job() // 与在跑回合无关的 Job
        every { replyDeliverer.lastOutputJob } returns firstTurnJob // 已产出 → 排除 starting，单独逼身份校验这一条

        controller.send("第二句")

        assertEquals("身份不符不得误杀", 0, turnCancels)
        assertEquals(1, turnStarts)
    }

    // ────────────── chunk 4 · 起步相位纳入可打断（V3·用户 2026-09-04 拍板）──────────────

    /**
     * T2-13（E17）：起步相位（窗到期 → 打字槽亮起之间）用户再发 → 打断。
     * 改前这一段两个判据皆假 = 打不断，角色会拿着不含第二句的材料自说自话答完整轮（用户可见：「她像没听见我第二句」）。
     * 一并钉占坑归位：`launchWindowTurn` 的 finally 里 `recoveryClaimTracker.end` 在取消路径上照常执行（图纸 F21）。
     */
    @Test
    fun 起步相位发消息_打断_且占坑归位() = runBlocking {
        startHangingTurn()
        typingSlot.value = null // 三点还没亮
        isDelivering.value = false // 还没开始递送
        // lastOutputJob 保持 setUp 的 null = 本回合从未产出过内容

        controller.send("第二句")

        assertEquals("起步相位应打断（V3）", 1, turnCancels)
        assertEquals("取消后新窗回合接手，一次回答两句", 2, turnStarts)
        // 占坑归位：`launchWindowTurn` 的 finally 非挂起，取消路径照常执行（图纸 F21）。
        // 注：isSending 此刻恒为 true 且**应当**如此——被取消回合的 finally 置了 false，紧接着新窗回合又置回 true，
        // 故它不是本相位的可断言量（F21 的 isSending 归位由「新回合能正常起来」间接证明 = 上面的 turnStarts==2）。
        verify(atLeast = 1) { recoveryClaimTracker.end("conv-1") }
    }

    /**
     * T2-15（E19）：`lastOutputJob` 是**另一个** Job（前一回合已产出、当前这个是排队的后继回合）→ 打断的是排队的
     * 那个。用 Job 引用而非布尔就是为了这条：布尔会因前一回合的产出把排队回合误判成「已产出」而放过它。
     */
    @Test
    fun 排队的后继回合_前一回合已产出_照样可打断() = runBlocking {
        startHangingTurn()
        typingSlot.value = null
        isDelivering.value = false
        every { replyDeliverer.lastOutputJob } returns Job() // 前一回合的产出记账，与在跑的这个回合无关

        controller.send("第二句")

        assertEquals("排队回合仍在起步相位，应打断", 1, turnCancels)
        assertEquals(2, turnStarts)
    }

    /**
     * T2-16（E21）：连发三句、每句都落在起步相位 → 每次都取消上一个起步中的回合，停手后剩下的那个回合
     * 读全历史一次答完三句（契约 §3.2-1 合并等待窗语义）。
     */
    @Test
    fun 连发三句_每次都取消起步中的回合_末次存活() = runBlocking {
        controller.send("第一句")
        controller.send("第二句")
        controller.send("第三句")

        assertEquals("每句都起了一个窗回合", 3, turnStarts)
        assertEquals("前两个起步中的回合被取消，末次存活", 2, turnCancels)
        val stored = mutableListOf<MessageEntity>()
        coVerify { messageRepo.upsert(capture(stored)) }
        assertEquals(listOf("第一句", "第二句", "第三句"), stored.map { it.content }) // 三句都已落库，一条不丢
    }
}
