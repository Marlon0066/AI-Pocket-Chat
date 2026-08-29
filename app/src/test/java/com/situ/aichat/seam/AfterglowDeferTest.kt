package com.situ.aichat.seam

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.notification.Notifier
import com.situ.aichat.offline.OfflineAfterglowPromptAssembler
import com.situ.aichat.offline.OfflineAfterglowService
import com.situ.aichat.proactive.ProactiveReplyDeliverer
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.OfflineAfterglowWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T2-K2（卷二 G2·图纸 §7）：**余温未熟改延后重排**行为测试。
 *
 * 两层：服务层验「了结方式」三态（HANDLED / DEFER_SUMMARY·守卫序不变），worker 层验自链重排的
 * 排程参数（uniqueName / 30 分钟 / REPLACE / deferCount+1）与到顶兜底档。断言从图纸 §3.2 独立反推。
 * Robolectric 只为构造 [OfflineAfterglowWorker]（CoroutineWorker 需真 Context）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AfterglowDeferTest {

    private lateinit var context: Context
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var characterRepo: CharacterRepository
    private lateinit var memoryRepo: OfflineMeetingMemoryRepository
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var contextLog: ContextLogService
    private lateinit var db: AppDatabase
    private lateinit var assembler: OfflineAfterglowPromptAssembler
    private lateinit var userProfileDao: UserProfileDao
    private lateinit var service: OfflineAfterglowService

    private val character = CharacterEntity(uuid = "char", name = "小雨", creationDate = 0L)

    /** 即时要点骨架行（卷二 G1 落的那种）。 */
    private fun instantRow() = row(source = "instant", summary = "一次你主动约的见面,约59分钟,主要是喝咖啡,共 2 轮对话,整体氛围温暖。")

    private fun row(source: String, summary: String = "一起喝了咖啡，聊得很开心", endedAt: Long = 1_000L) =
        OfflineMeetingMemoryEntity(
            uuid = "r1", characterUuid = "char", conversationUuid = "conv", sessionId = "sess",
            startedAtMillis = 500L, endedAtMillis = endedAt, location = "咖啡馆", summary = summary,
            sourceRaw = source, createdAtMillis = 0L, updatedAtMillis = 0L,
        )

    private fun lifecycleOwner(foreground: Boolean): LifecycleOwner {
        val lc = mockk<Lifecycle>()
        every { lc.currentState } returns if (foreground) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        return mockk { every { lifecycle } returns lc }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        conversationRepo = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        memoryRepo = mockk(relaxed = true)
        apiConfigRepo = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        messageRepo = mockk(relaxed = true)
        contextLog = mockk(relaxed = true)
        db = mockk()
        assembler = mockk()
        userProfileDao = mockk(relaxed = true)

        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { db.withTransaction<Unit>(any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
        mockkObject(Notifier)
        every { Notifier.post(any(), any()) } returns Unit
        mockkObject(ProcessLifecycleOwner.Companion)
        every { ProcessLifecycleOwner.get() } returns lifecycleOwner(foreground = false)

        coEvery { settingsRepo.getAppSettings() } returns AppSettings(offlineAfterglowEnabled = true, notificationsEnabled = true)
        coEvery { conversationRepo.get("conv") } returns
            ConversationEntity(uuid = "conv", title = "t", characterUuid = "char", creationDate = 0L)
        coEvery { characterRepo.get("char") } returns character
        coEvery { messageRepo.latestVisibleMessage("conv") } returns null
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) } returns mockk(relaxed = true)
        coEvery { assembler.assemble(any(), any(), any(), any()) } returns
            listOf(ChatMessageDto(role = "system", content = "人设"))

        val deliverer = ProactiveReplyDeliverer(
            context, conversationRepo, messageRepo, mockk<VectorMemoryService>(relaxed = true), db,
        )
        service = OfflineAfterglowService(
            conversationRepo, characterRepo, memoryRepo, apiConfigRepo, settingsRepo,
            messageRepo, contextLog, deliverer, assembler, userProfileDao,
        )
    }

    @After fun tearDown() = unmockkAll()

    private fun stubLlm(reply: String) {
        coEvery {
            contextLog.completion(eq(LogSource.OFFLINE_AFTERGLOW), any(), any(), any(), any(), any(), any(), any(), any())
        } returns reply
    }

    private fun captureInstruction(): MutableList<List<ChatMessageDto>> {
        val sent = mutableListOf<List<ChatMessageDto>>()
        coEvery {
            contextLog.completion(eq(LogSource.OFFLINE_AFTERGLOW), any(), any(), capture(sent), any(), any(), any(), any(), any())
        } returns "今天真开心～"
        return sent
    }

    private fun verifyCompletion(times: Int) =
        coVerify(exactly = times) {
            contextLog.completion(eq(LogSource.OFFLINE_AFTERGLOW), any(), any(), any(), any(), any(), any(), any(), any())
        }

    // ══════════ 服务层：了结方式三态 ══════════

    /** E4：到点时行还是即时要点 → DEFER_SUMMARY，一个字不发。 */
    @Test fun e4_instantRow_defersWithoutSending() = runBlocking {
        coEvery { memoryRepo.bySessionId("sess") } returns instantRow()

        val outcome = service.maybeGenerate("conv", "char", "sess")

        assertEquals(OfflineAfterglowService.AfterglowOutcome.DEFER_SUMMARY, outcome)
        verifyCompletion(0)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    /** 摘要熟了（llm 行）→ 照常生成发出，HANDLED。 */
    @Test fun llmRow_generatesAndHandles() = runBlocking {
        coEvery { memoryRepo.bySessionId("sess") } returns row(source = "llm")
        stubLlm("今天在咖啡馆聊得好开心～")

        val outcome = service.maybeGenerate("conv", "char", "sess")

        assertEquals(OfflineAfterglowService.AfterglowOutcome.HANDLED, outcome)
        coVerify(exactly = 1) { messageRepo.upsert(match { it.content == "今天在咖啡馆聊得好开心～" }) }
    }

    /** fallback / manual 行同样算「熟」（重试链已尽力，等它无意义）→ 不再延后。 */
    @Test fun fallbackAndManualRows_countAsRipe() = runBlocking {
        stubLlm("今天真开心～")
        listOf("fallback", "manual").forEach { source ->
            coEvery { memoryRepo.bySessionId("sess") } returns row(source = source)
            assertEquals(
                "source=$source 应算已熟",
                OfflineAfterglowService.AfterglowOutcome.HANDLED,
                service.maybeGenerate("conv", "char", "sess"),
            )
        }
    }

    /** 兜底档（acceptInstantRow=true）：即时要点也认，**正文取自骨架行**（有时段/地点/时长可回味）。 */
    @Test fun bottomOut_acceptsInstantRow_andFeedsSkeletonSummary() = runBlocking {
        coEvery { memoryRepo.bySessionId("sess") } returns instantRow()
        val sent = captureInstruction()

        val outcome = service.maybeGenerate("conv", "char", "sess", acceptInstantRow = true)

        assertEquals(OfflineAfterglowService.AfterglowOutcome.HANDLED, outcome)
        val instruction = sent.last().last { it.role == "system" }.content.orEmpty()
        assertTrue("余温指令应吃骨架摘要：$instruction", instruction.contains("共 2 轮对话,整体氛围温暖。"))
        assertTrue("硬事实地点仍在：$instruction", instruction.contains("在咖啡馆见了面"))
        coVerify(exactly = 1) { messageRepo.upsert(any()) }
    }

    /** E6：历史遗留 pending（升级前旧会话·压根没有行）走到兜底档 → 维持旧静默，不发。 */
    @Test fun e6_noRowAtBottomOut_staysSilent() = runBlocking {
        coEvery { memoryRepo.bySessionId("sess") } returns null

        val outcome = service.maybeGenerate("conv", "char", "sess", acceptInstantRow = true)

        assertEquals(OfflineAfterglowService.AfterglowOutcome.HANDLED, outcome)
        verifyCompletion(0)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    /** E5：延后期间用户回来聊了别的 → 摘要熟了这一跳守卫③命中，HANDLED 不发（守卫③每次重估）。 */
    @Test fun e5_userCameBack_guard3TakesOverOnceRipe() = runBlocking {
        coEvery { memoryRepo.bySessionId("sess") } returns row(source = "llm", endedAt = 1_000L)
        coEvery { messageRepo.latestVisibleMessage("conv") } returns MessageEntity(
            messageUUID = "m", conversationUuid = "conv", roleRaw = "user", content = "又聊起别的", timestamp = 6_000L,
        )

        val outcome = service.maybeGenerate("conv", "char", "sess")

        assertEquals(OfflineAfterglowService.AfterglowOutcome.HANDLED, outcome)
        verifyCompletion(0)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    /** E5 兜底档同理：到顶了但用户已回来聊过 → 照样不打扰。 */
    @Test fun e5_userCameBack_bottomOutStillSilent() = runBlocking {
        coEvery { memoryRepo.bySessionId("sess") } returns instantRow()
        coEvery { messageRepo.latestVisibleMessage("conv") } returns MessageEntity(
            messageUUID = "m", conversationUuid = "conv", roleRaw = "user", content = "又聊起别的", timestamp = 6_000L,
        )

        val outcome = service.maybeGenerate("conv", "char", "sess", acceptInstantRow = true)

        assertEquals(OfflineAfterglowService.AfterglowOutcome.HANDLED, outcome)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    /** 守卫①②在守卫④之前：关开关 / 又在见面中 → HANDLED（不自链空转）。 */
    @Test fun guards1And2_beforeSummaryCheck_returnHandled() = runBlocking {
        coEvery { memoryRepo.bySessionId("sess") } returns instantRow()
        coEvery { settingsRepo.getAppSettings() } returns AppSettings(offlineAfterglowEnabled = false)
        assertEquals(
            OfflineAfterglowService.AfterglowOutcome.HANDLED,
            service.maybeGenerate("conv", "char", "sess"),
        )
        coEvery { settingsRepo.getAppSettings() } returns AppSettings(offlineAfterglowEnabled = true)
        coEvery { conversationRepo.get("conv") } returns ConversationEntity(
            uuid = "conv", title = "t", characterUuid = "char", creationDate = 0L, isInOfflineMode = true,
        )
        assertEquals(
            OfflineAfterglowService.AfterglowOutcome.HANDLED,
            service.maybeGenerate("conv", "char", "sess"),
        )
    }

    // ══════════ worker 层：自链重排 ══════════

    private fun worker(serviceMock: OfflineAfterglowService, scheduler: BackgroundScheduler, deferCount: Int?) =
        OfflineAfterglowWorker(
            appContext = context,
            params = mockk<WorkerParameters>(relaxed = true).also { params ->
                val base = mutableListOf<Pair<String, Any>>(
                    OfflineAfterglowWorker.KEY_CONVERSATION_UUID to "conv",
                    OfflineAfterglowWorker.KEY_CHARACTER_UUID to "char",
                    OfflineAfterglowWorker.KEY_SESSION_ID to "sess",
                )
                if (deferCount != null) base.add(OfflineAfterglowWorker.KEY_DEFER_COUNT to deferCount)
                every { params.inputData } returns workDataOf(*base.toTypedArray())
            },
            service = serviceMock,
            backgroundScheduler = scheduler,
        )

    /** 未熟 → 同 uniqueName、30 分钟、REPLACE、deferCount+1 自链重排；首排（无 KEY_DEFER_COUNT）视作 0。 */
    @Test fun defer_reschedulesWith30MinReplaceAndIncrementedCount() = runBlocking {
        val serviceMock = mockk<OfflineAfterglowService>(relaxed = true)
        coEvery { serviceMock.maybeGenerate(any(), any(), any(), any()) } returns
            OfflineAfterglowService.AfterglowOutcome.DEFER_SUMMARY
        val scheduler = mockk<BackgroundScheduler>(relaxed = true)
        val data = slot<Data>()

        val result = worker(serviceMock, scheduler, deferCount = null).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        verify(exactly = 1) {
            scheduler.scheduleOneShot(
                uniqueName = eq("offline_afterglow_sess"),
                workerClass = eq(OfflineAfterglowWorker::class.java),
                initialDelay = eq(java.time.Duration.ofMinutes(30L)),
                requireNetwork = eq(true),
                existingPolicy = eq(ExistingWorkPolicy.REPLACE),
                inputData = capture(data),
            )
        }
        assertEquals(1, data.captured.getInt(OfflineAfterglowWorker.KEY_DEFER_COUNT, -1))
        assertEquals("conv", data.captured.getString(OfflineAfterglowWorker.KEY_CONVERSATION_UUID))
        assertEquals("char", data.captured.getString(OfflineAfterglowWorker.KEY_CHARACTER_UUID))
        assertEquals("sess", data.captured.getString(OfflineAfterglowWorker.KEY_SESSION_ID))
        coVerify(exactly = 1) { serviceMock.maybeGenerate("conv", "char", "sess", false) }
    }

    /** 第 6 次仍未熟（count=5）→ 还能再排一次（count=6 = 上限）。 */
    @Test fun defer_atCountFive_stillReschedulesToSix() = runBlocking {
        val serviceMock = mockk<OfflineAfterglowService>(relaxed = true)
        coEvery { serviceMock.maybeGenerate(any(), any(), any(), any()) } returns
            OfflineAfterglowService.AfterglowOutcome.DEFER_SUMMARY
        val scheduler = mockk<BackgroundScheduler>(relaxed = true)
        val data = slot<Data>()

        worker(serviceMock, scheduler, deferCount = 5).doWork()

        verify(exactly = 1) {
            scheduler.scheduleOneShot<OfflineAfterglowWorker>(any(), any(), any(), any(), any(), capture(data))
        }
        assertEquals(6, data.captured.getInt(OfflineAfterglowWorker.KEY_DEFER_COUNT, -1))
    }

    /** 到顶（count=6）→ 不再排，改带 acceptInstantRow=true 再跑一次（简版照发）。 */
    @Test fun bottomOut_atMaxDefers_retriesWithAcceptInstantAndStopsChain() = runBlocking {
        val serviceMock = mockk<OfflineAfterglowService>(relaxed = true)
        coEvery { serviceMock.maybeGenerate(any(), any(), any(), any()) } returns
            OfflineAfterglowService.AfterglowOutcome.DEFER_SUMMARY
        val scheduler = mockk<BackgroundScheduler>(relaxed = true)

        val result = worker(serviceMock, scheduler, deferCount = 6).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        verify(exactly = 0) { scheduler.scheduleOneShot<OfflineAfterglowWorker>(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { serviceMock.maybeGenerate("conv", "char", "sess", true) }
    }

    /** 已了结（HANDLED）→ 绝不自链（不空转排队）。 */
    @Test fun handled_neverReschedules() = runBlocking {
        val serviceMock = mockk<OfflineAfterglowService>(relaxed = true)
        coEvery { serviceMock.maybeGenerate(any(), any(), any(), any()) } returns
            OfflineAfterglowService.AfterglowOutcome.HANDLED
        val scheduler = mockk<BackgroundScheduler>(relaxed = true)

        worker(serviceMock, scheduler, deferCount = 0).doWork()

        verify(exactly = 0) { scheduler.scheduleOneShot<OfflineAfterglowWorker>(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { serviceMock.maybeGenerate(any(), any(), any(), any()) }
    }

    /** 生成抛异常 → 吞掉、恒 success、且不触发自链（异常不是「未熟」）。 */
    @Test fun serviceThrows_swallowedAndNoChain() = runBlocking {
        val serviceMock = mockk<OfflineAfterglowService>(relaxed = true)
        coEvery { serviceMock.maybeGenerate(any(), any(), any(), any()) } throws IllegalStateException("网络炸了")
        val scheduler = mockk<BackgroundScheduler>(relaxed = true)

        val result = worker(serviceMock, scheduler, deferCount = 0).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        verify(exactly = 0) { scheduler.scheduleOneShot<OfflineAfterglowWorker>(any(), any(), any(), any(), any(), any()) }
    }
}
