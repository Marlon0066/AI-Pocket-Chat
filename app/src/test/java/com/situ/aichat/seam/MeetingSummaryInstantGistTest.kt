package com.situ.aichat.seam

import android.content.Context
import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.offline.OfflineMeetingSessionExtractor
import com.situ.aichat.offline.OfflineMeetingService
import com.situ.aichat.offline.OfflineSummaryHealStore
import com.situ.aichat.offline.OfflineSummaryRetryCoordinator
import com.situ.aichat.promise.PromiseLedgerService
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.ui.chat.ChatOfflineController
import com.situ.aichat.util.StringListJson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T2-K1（卷二 G1·图纸 §7）：**见面结束瞬间的「即时要点」行**行为测试。
 *
 * 手法：协调器真跑（MockK 假掉会话/消息/角色/API/提取器），见面回忆仓库用**真 Repository + 内存假 DAO**
 * ——「替换」这一步是 [OfflineMeetingMemoryRepository.upsertMeeting] 的 E6 幂等语义（保 uuid/createdAt 覆盖其余），
 * 用 mock 仓库断言等于什么都没证明，故此处让它真跑。
 *
 * 覆盖：落 instant 行（source/正文骨架逐字·**断言从图纸 §3.1 独立反推**，不照抄实现）/ 行已存在零写 /
 * E2 llm 成功后同 uuid 原位覆盖且 embedding 置空 / E3 5 败 applyFallback 覆写 instant 行 /
 * E1 即时要点抛异常被 runCatching 吞、重试链照跑 / 不碰 pending 与 fallback 列表（J2）。
 */
class MeetingSummaryInstantGistTest {

    private val NOW = 2_000_000L

    private lateinit var conversationRepo: ConversationRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var characterRepo: CharacterRepository
    private lateinit var contextLog: ContextLogService
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var sessionExtractor: OfflineMeetingSessionExtractor
    private lateinit var healStore: OfflineSummaryHealStore
    private lateinit var promiseLedgerService: PromiseLedgerService
    private lateinit var userProfileDao: UserProfileDao

    /** 内存假 DAO：只实现本测用到的四口（其余 relaxed）。 */
    private lateinit var dao: OfflineMeetingMemoryDao
    private lateinit var rows: MutableMap<String, OfflineMeetingMemoryEntity>
    private lateinit var memoryRepo: OfflineMeetingMemoryRepository

    /** 见面消息：user 1_000ms → assistant 3_600_000ms（= 59 分钟整）。 */
    private val sessionMsgs = listOf(
        MessageEntity(messageUUID = "m1", conversationUuid = "conv", roleRaw = "user", content = "你好呀", timestamp = 1_000L),
        MessageEntity(messageUUID = "m2", conversationUuid = "conv", roleRaw = "assistant", content = "见到你真开心", timestamp = 3_600_000L),
    )
    private val meta = OfflineMeetingSessionExtractor.FallbackMetadata(
        startMillis = 1_000L, location = "咖啡馆", activity = "喝咖啡", finalMood = "warm", initiatedByUser = true,
    )
    private val validJson =
        """{"summary":"今天我们在江边散步聊了很多，从工作聊到理想，还说好下次一起去看海，整个下午都很温柔很放松惬意。","highlights":["记得那杯焦糖拿铁"],"promises":["下次一起去看海"],"mood":"warm"}"""

    /**
     * 期望骨架正文——**从图纸 §3.1 + buildFallbackBody 规格独立反推**：
     * 发起方(true→「一次你主动约的见面」) → 时长(durationText(1_000, 3_600_000)=59 分钟→「约59分钟」) →
     * 「主要是」+活动 → 「共 N 轮对话」(N=msgs.size=2) → 「整体氛围」+情绪(warm→温暖)；半角逗号分隔、全角句号收尾。
     */
    private val expectedInstantBody = "一次你主动约的见面,约59分钟,主要是喝咖啡,共 2 轮对话,整体氛围温暖。"

    private fun convo(failCount: Int = 0, fallbackIds: String = "") = ConversationEntity(
        uuid = "conv", title = "t", characterUuid = "char", creationDate = 0L, isInOfflineMode = false,
        pendingOfflineSummarySessionId = "sess", pendingOfflineSummaryFailCount = failCount,
        offlineSummaryFallbackSessionIds = fallbackIds,
    )

    @Before
    fun setUp() {
        conversationRepo = mockk(relaxed = true)
        messageRepo = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        contextLog = mockk(relaxed = true)
        apiConfigRepo = mockk(relaxed = true)
        sessionExtractor = mockk(relaxed = true)
        healStore = mockk(relaxed = true)
        promiseLedgerService = mockk(relaxed = true)
        userProfileDao = mockk(relaxed = true)

        rows = mutableMapOf()
        dao = mockk(relaxed = true)
        coEvery { dao.findBySessionId(any()) } answers { rows.values.firstOrNull { it.sessionId == firstArg() } }
        coEvery { dao.upsert(any()) } answers { rows[firstArg<OfflineMeetingMemoryEntity>().uuid] = firstArg() }
        coEvery { dao.countByCharacter(any()) } answers { rows.values.count { it.characterUuid == firstArg<String>() } }
        memoryRepo = OfflineMeetingMemoryRepository(dao, mockk(relaxed = true), mockk(relaxed = true))

        coEvery { userProfileDao.get() } returns UserProfileEntity(nickname = "阿泽")
        coEvery { conversationRepo.get("conv") } returns convo()
        coEvery { characterRepo.get("char") } returns CharacterEntity(uuid = "char", name = "小雨", creationDate = 0L)
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.MEMORY_SUMMARY) } returns mockk(relaxed = true)
        coEvery { messageRepo.offlineSessionMessages("conv", "sess") } returns sessionMsgs
        coEvery { sessionExtractor.extractFallbackMetadata("conv", "sess", any()) } returns meta

        // 见 OfflineSummaryRetryCoordinatorTest：formatMessages 内部 SimpleDateFormat 在裸 JVM 会 NPE，其产出只喂 prompt。
        mockkObject(MemoryService.Companion)
        every { MemoryService.formatMessages(any(), any(), any()) } returns "（会话记录）"
    }

    @After fun tearDown() = unmockkAll()

    private fun coordinator() = OfflineSummaryRetryCoordinator(
        conversationRepo, messageRepo, characterRepo, memoryRepo, contextLog,
        apiConfigRepo, sessionExtractor, healStore, promiseLedgerService, userProfileDao,
    )

    /** completion 有默认参 → 生产侧经 $default 路由：stub 须给全 9 个匹配器（同既有协调器测）。 */
    private fun stubLlm(reply: String) {
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns reply
    }

    private fun onlyRow(): OfflineMeetingMemoryEntity {
        assertEquals("同一 sessionId 恒只有一行（E6 幂等）", 1, rows.size)
        return rows.values.first()
    }

    // ══════ 落行 ══════

    /** 见面结束瞬间 → 落一行 source=instant、正文=骨架配方、硬事实（地点/活动/发起方）随 meta。 */
    @Test
    fun 结束即落即时要点行_骨架配方逐字() = runBlocking {
        coordinator().applyInstantGist("conv", "sess", NOW)

        val row = onlyRow()
        assertEquals("instant", row.sourceRaw)
        assertEquals(expectedInstantBody, row.summary)
        assertEquals("咖啡馆", row.location)
        assertEquals("喝咖啡", row.activity)
        assertEquals("warm", row.moodRaw)
        assertEquals(true, row.initiatedByUser)
        assertEquals(2, row.messageCount)
        assertEquals(1_000L, row.startedAtMillis)
        assertEquals(3_600_000L, row.endedAtMillis) // = 末条消息时间戳
        assertTrue("即时要点无亮点", StringListJson.decode(row.highlightsJson).isEmpty())
        assertTrue("即时要点不产约定（承诺账本便车零碰）", StringListJson.decode(row.promisesJson).isEmpty())
        assertNull("新行不带向量，等 backfill 重嵌", row.embedding)
    }

    /** J2：即时要点**绝不碰** pending 字段与 fallback 列表——重试链语义原样。 */
    @Test
    fun 即时要点不碰pending也不碰fallback列表() = runBlocking {
        coordinator().applyInstantGist("conv", "sess", NOW)

        coVerify(exactly = 0) { conversationRepo.clearPendingOfflineSummary(any()) }
        coVerify(exactly = 0) { conversationRepo.appendFallbackSessionId(any(), any()) }
        coVerify(exactly = 0) { conversationRepo.removeFallbackSessionId(any(), any()) }
        coVerify(exactly = 0) { conversationRepo.incrementOfflineSummaryFailCount(any()) }
    }

    /** 行已存在（重入 / 已有 llm 行）→ 零写早退，绝不把成品覆盖回骨架。 */
    @Test
    fun 行已存在_零写早退() = runBlocking {
        val existing = OfflineMeetingMemoryEntity(
            uuid = "row-old", characterUuid = "char", conversationUuid = "conv", sessionId = "sess",
            startedAtMillis = 1_000L, summary = "完整的 LLM 摘要", sourceRaw = "llm",
            createdAtMillis = 5L, updatedAtMillis = 5L,
        )
        rows[existing.uuid] = existing

        coordinator().applyInstantGist("conv", "sess", NOW)

        val row = onlyRow()
        assertEquals("llm", row.sourceRaw)
        assertEquals("完整的 LLM 摘要", row.summary)
        assertEquals(5L, row.updatedAtMillis) // 一个字节都没动
    }

    /** 会话不存在 → 零写（characterUuid 无从取得）。 */
    @Test
    fun 会话不存在_零写() = runBlocking {
        coEvery { conversationRepo.get("conv") } returns null
        coordinator().applyInstantGist("conv", "sess", NOW)
        assertTrue(rows.isEmpty())
    }

    /** 消息已删光 → 零写（照 retryOne 同语义：没有材料就不造行）。 */
    @Test
    fun 消息删光_零写() = runBlocking {
        coEvery { messageRepo.offlineSessionMessages("conv", "sess") } returns emptyList()
        coordinator().applyInstantGist("conv", "sess", NOW)
        assertTrue(rows.isEmpty())
    }

    /**
     * D-1 复核裁决（R1）：`messageCount` 与 applyFallback **同源**——retryOne 同款谓词剔除入/离场 marker
     * 与空消息后取数（即时行=「提前的兜底行」，同一句「共 N 轮对话」两种来源必须同一个 N）。
     * 夹具 = 1 枚入场 marker + 2 条真实消息 → N=2（裁决前的全量取数会得 3）。
     */
    @Test
    fun 含marker时轮数与fallback同源_D1复核裁决() = runBlocking {
        coEvery { messageRepo.offlineSessionMessages("conv", "sess") } returns listOf(
            MessageEntity(
                messageUUID = "s", conversationUuid = "conv", roleRaw = "system", content = "{}",
                timestamp = 500L, messageKindRaw = MessageKind.OFFLINE_MARKER_START.raw,
            ),
        ) + sessionMsgs

        coordinator().applyInstantGist("conv", "sess", NOW)

        assertEquals(2, onlyRow().messageCount)
        assertTrue(onlyRow().summary.contains("共 2 轮对话"))
    }

    // ══════ E2：LLM 成功 → 原位替换 ══════

    /** E2：先 instant 后 llm 成功 → **同一行**（uuid/createdAt 保留）翻成 llm，embedding 归空待重嵌。 */
    @Test
    fun e2_llm成功后同uuid原位覆盖且向量置空() = runBlocking {
        val c = coordinator()
        c.applyInstantGist("conv", "sess", NOW)
        val instantRow = onlyRow()
        // 模拟向量回填后又来了完整摘要：旧向量必须失效。
        rows[instantRow.uuid] = instantRow.copy(embedding = byteArrayOf(1, 2, 3))
        stubLlm(validJson)

        val outcome = c.retryOne("conv", NOW)

        assertEquals(OfflineSummaryRetryCoordinator.RetryOutcome.SUCCESS, outcome)
        val row = onlyRow()
        assertEquals(instantRow.uuid, row.uuid) // 同一行原位替换，不产生第二行
        assertEquals(instantRow.createdAtMillis, row.createdAtMillis)
        assertEquals("llm", row.sourceRaw)
        assertTrue(row.summary.startsWith("今天我们在江边散步"))
        assertNull("正文换了 → 旧向量必须失效", row.embedding)
    }

    // ══════ E3：5 败兜底覆写 ══════

    /** E3：instant 行在位时走到第 5 次失败 → applyFallback 覆写成 fallback 行 + 登记列表 + 清 pending。 */
    @Test
    fun e3_五败兜底覆写instant行并登记列表() = runBlocking {
        val c = coordinator()
        c.applyInstantGist("conv", "sess", NOW)
        val instantRow = onlyRow()
        coEvery { conversationRepo.get("conv") } returns convo(failCount = 4) // 本次失败 = 第 5 次 → 兜底
        stubLlm("抱歉，我暂时无法生成") // 非 JSON → schema 恒失败 → throw → 兜底

        val outcome = c.retryOne("conv", NOW)

        assertEquals(OfflineSummaryRetryCoordinator.RetryOutcome.FELL_BACK, outcome)
        val row = onlyRow()
        assertEquals(instantRow.uuid, row.uuid)
        assertEquals("fallback", row.sourceRaw)
        coVerify(exactly = 1) { conversationRepo.appendFallbackSessionId("conv", "sess") }
        coVerify(exactly = 1) { conversationRepo.clearPendingOfflineSummary("conv") }
    }

    // ══════ E1：即时要点异常不拖垮重试链 ══════

    /** E1：即时要点抛异常（提取/DB 故障）→ runCatching 吞掉，retryOne 照常跑（重试链不受牵连）。 */
    @Test
    fun e1_即时要点抛异常_被吞且重试链照跑() {
        val coordinatorMock = mockk<OfflineSummaryRetryCoordinator>(relaxed = true)
        coEvery { coordinatorMock.applyInstantGist(any(), any(), any()) } throws IllegalStateException("DB 炸了")
        coEvery { coordinatorMock.retryOne(any(), any(), any()) } returns OfflineSummaryRetryCoordinator.RetryOutcome.SUCCESS
        val offlineMeetingService = mockk<OfflineMeetingService>(relaxed = true)
        coEvery { offlineMeetingService.finalizeOfflineMode(any(), any()) } returns true
        coEvery { conversationRepo.get("conv-1") } returns ConversationEntity(
            uuid = "conv-1", title = "t", characterUuid = "char", creationDate = 0L,
            pendingOfflineSummarySessionId = "sess",
        )
        val appContext = mockk<Context>(relaxed = true)
        every { appContext.getString(any<Int>()) } returns "提示"
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val controller = ChatOfflineController(
            scope = scope,
            appContext = appContext,
            conversationUuid = "conv-1",
            infoToastFlow = MutableStateFlow(null),
            recoveryPromptVisibleFlow = MutableStateFlow(false),
            messageRepo = messageRepo,
            conversationRepo = conversationRepo,
            settingsRepo = mockk(relaxed = true),
            offlineMeetingService = offlineMeetingService,
            offlineSummaryRetryCoordinator = coordinatorMock,
            meetingAppointmentStore = mockk(relaxed = true),
            meetingFulfillmentService = mockk(relaxed = true),
            runAssistantTurn = {},
            serialize = { block -> scope.launch { block() } },
            cancelActiveTurn = {},
            afterOfflineMemorySummary = {},
            scheduleOfflineAfterglow = {},
            scheduleMeetingMomentEcho = {},
            proactiveGiftMaintenanceService = mockk(relaxed = true),
        )

        controller.exitOfflineMode()

        coVerify(exactly = 1) { coordinatorMock.applyInstantGist("conv-1", "sess", any()) }
        coVerify(exactly = 1) { coordinatorMock.retryOne("conv-1", any(), any()) } // 异常没拖垮重试链
    }

    /** 结束时会话没有 pending session（罕见）→ 不调即时要点，仍照常跑重试链。 */
    @Test
    fun 无pendingSession_不落即时要点仍跑重试链() {
        val coordinatorMock = mockk<OfflineSummaryRetryCoordinator>(relaxed = true)
        val offlineMeetingService = mockk<OfflineMeetingService>(relaxed = true)
        coEvery { offlineMeetingService.finalizeOfflineMode(any(), any()) } returns true
        coEvery { conversationRepo.get("conv-1") } returns ConversationEntity(
            uuid = "conv-1", title = "t", characterUuid = "char", creationDate = 0L,
            pendingOfflineSummarySessionId = "",
        )
        val appContext = mockk<Context>(relaxed = true)
        every { appContext.getString(any<Int>()) } returns "提示"
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val controller = ChatOfflineController(
            scope = scope,
            appContext = appContext,
            conversationUuid = "conv-1",
            infoToastFlow = MutableStateFlow(null),
            recoveryPromptVisibleFlow = MutableStateFlow(false),
            messageRepo = messageRepo,
            conversationRepo = conversationRepo,
            settingsRepo = mockk(relaxed = true),
            offlineMeetingService = offlineMeetingService,
            offlineSummaryRetryCoordinator = coordinatorMock,
            meetingAppointmentStore = mockk(relaxed = true),
            meetingFulfillmentService = mockk(relaxed = true),
            runAssistantTurn = {},
            serialize = { block -> scope.launch { block() } },
            cancelActiveTurn = {},
            afterOfflineMemorySummary = {},
            scheduleOfflineAfterglow = {},
            scheduleMeetingMomentEcho = {},
            proactiveGiftMaintenanceService = mockk(relaxed = true),
        )

        controller.exitOfflineMode()

        coVerify(exactly = 0) { coordinatorMock.applyInstantGist(any(), any(), any()) }
        coVerify(exactly = 1) { coordinatorMock.retryOne("conv-1", any(), any()) }
    }

    // ══════ 谓词单源 ══════

    /** J3 统一谓词：无行 / instant → 未熟；fallback / manual / llm → 熟。 */
    @Test
    fun 未熟谓词_只把无行与instant判为未熟() {
        fun row(source: String) = OfflineMeetingMemoryEntity(
            uuid = "u", characterUuid = "char", sessionId = "sess",
            startedAtMillis = 0L, sourceRaw = source, createdAtMillis = 0L, updatedAtMillis = 0L,
        )
        assertTrue(OfflineSummaryRetryCoordinator.summaryStillPending(null))
        assertTrue(OfflineSummaryRetryCoordinator.summaryStillPending(row("instant")))
        assertEquals(false, OfflineSummaryRetryCoordinator.summaryStillPending(row("llm")))
        assertEquals(false, OfflineSummaryRetryCoordinator.summaryStillPending(row("fallback")))
        assertEquals(false, OfflineSummaryRetryCoordinator.summaryStillPending(row("manual")))
        assertNotNull(OfflineSummaryRetryCoordinator.SOURCE_INSTANT)
        assertEquals("instant", OfflineSummaryRetryCoordinator.SOURCE_INSTANT)
    }
}
