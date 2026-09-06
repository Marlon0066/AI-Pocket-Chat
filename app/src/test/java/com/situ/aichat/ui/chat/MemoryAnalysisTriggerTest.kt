package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.StructuredMemoryMetadata
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.prompt.memory.MemoryDigestCoordinator
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.memory.MemorySummaryError
import com.situ.aichat.prompt.memory.StructuredMemoryCoordinator
import com.situ.aichat.prompt.memory.StructuredMemoryError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * MemoryAnalysisTrigger 行为测试——验证刀4a 记忆簇协作者「真的能用」（不止编译过）。
 *
 * 手法：MockK 假掉 6 个依赖；`withCharacterLock` 被 stub 为真正执行其 block（否则递增/触发逻辑跑不到）；
 * Unconfined 让 fire-and-forget 协程同步跑完。覆盖：摘要间隔守卫 + 结构化记忆三道守卫 + 真触发路径(递增→达阈值抽取 / 未达只递增)。
 */
class MemoryAnalysisTriggerTest {

    private lateinit var characterRepo: CharacterRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var characterWriteLock: CharacterWriteLock
    private lateinit var memoryService: MemoryService
    private lateinit var digestCoordinator: MemoryDigestCoordinator
    private lateinit var structuredCoordinator: StructuredMemoryCoordinator
    private lateinit var onStructuredMemoryExtracted: (String) -> Unit
    private lateinit var trigger: MemoryAnalysisTrigger
    private val config = mockk<ApiConfigValues>(relaxed = true)

    @Before
    fun setUp() {
        characterRepo = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        characterWriteLock = mockk(relaxed = true)
        memoryService = mockk(relaxed = true)
        digestCoordinator = mockk(relaxed = true)
        structuredCoordinator = mockk(relaxed = true)
        onStructuredMemoryExtracted = mockk(relaxed = true)
        // withCharacterLock 真正执行其 block（否则"重读→+1→写回→触发判定"逻辑不会跑）。
        coEvery { characterWriteLock.withCharacterLock<StructuredMemoryMetadata?>(any(), any()) } coAnswers {
            secondArg<suspend () -> StructuredMemoryMetadata?>().invoke()
        }
        trigger = newTrigger(CoroutineScope(Dispatchers.Unconfined))
    }

    /** 同一组假件、只换调度作用域——T2-3 需要虚拟时钟跳过重试间的 delay(2s)。 */
    private fun newTrigger(scope: CoroutineScope) = MemoryAnalysisTrigger(
        scope = scope,
        conversationUuid = "conv-1",
        characterRepo = characterRepo,
        conversationRepo = conversationRepo,
        characterWriteLock = characterWriteLock,
        memoryService = memoryService,
        digestCoordinator = digestCoordinator,
        structuredCoordinator = structuredCoordinator,
        apiConfigRepo = mockk(relaxed = true),
        settingsRepo = mockk(relaxed = true),
        userProfileDao = mockk(relaxed = true),
        onStructuredMemoryExtracted = onStructuredMemoryExtracted,
    )

    // ---- 记忆摘要守卫 ----

    @Test
    fun 摘要_间隔为0_不触发总结() {
        trigger.checkAndTriggerMemorySummary("c1", config, AppSettings(autoSummarizeInterval = 0), "用户")
        coVerify(exactly = 0) {
            digestCoordinator.digestAndReconcile(any(), any(), any(), any(), any(), any())
        }
    }

    // ---- 记忆摘要真触发路径（图纸 2026-09-05 §7 T2-1…T2-4）----

    /** 摘要路的三件预设：角色 / 会话（可指定成功时间戳）/ 窗口外 user 消息条数。 */
    private fun stubSummaryPath(outsideUserRounds: Int, lastSuccessDate: Long? = null) {
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        coEvery { conversationRepo.get("conv-1") } returns ConversationEntity(
            uuid = "conv-1", title = "", characterUuid = "c1", creationDate = 0L,
            lastMemorySummarySuccessDate = lastSuccessDate,
        )
        coEvery { memoryService.collectMessagesOutsideWindow(any(), any(), any()) } returns
            (1..outsideUserRounds).map {
                MessageEntity(messageUUID = "m$it", conversationUuid = "conv-1", roleRaw = "user", content = "第 $it 句", timestamp = it.toLong())
            }
    }

    @Test
    fun T2_1摘要_攒够10轮且从未成功_总结一次并记成功() {
        stubSummaryPath(outsideUserRounds = 10)
        trigger.checkAndTriggerMemorySummary("c1", config, AppSettings(autoSummarizeInterval = 10), "用户")
        coVerify(exactly = 1) { digestCoordinator.digestAndReconcile(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { conversationRepo.recordMemorySummaryResult("conv-1", success = true, now = any()) }
    }

    @Test
    fun T2_2摘要_确定性失败SuspiciouslyShort_不重试且记失败() {
        stubSummaryPath(outsideUserRounds = 10)
        coEvery { digestCoordinator.digestAndReconcile(any(), any(), any(), any(), any(), any()) } throws
            MemorySummaryError.SuspiciouslyShort
        trigger.checkAndTriggerMemorySummary("c1", config, AppSettings(autoSummarizeInterval = 10), "用户")
        coVerify(exactly = 1) { digestCoordinator.digestAndReconcile(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { conversationRepo.recordMemorySummaryResult("conv-1", success = false, now = any()) }
        coVerify(exactly = 0) { conversationRepo.recordMemorySummaryResult("conv-1", success = true, now = any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun T2_3摘要_瞬态异常_重试满2次后记失败() = runTest {
        // 瞬态失败之间有 delay(2_000)：换 StandardTestDispatcher 让虚拟时钟跳过等待。
        val scopedTrigger = newTrigger(CoroutineScope(StandardTestDispatcher(testScheduler)))
        stubSummaryPath(outsideUserRounds = 10)
        coEvery { digestCoordinator.digestAndReconcile(any(), any(), any(), any(), any(), any()) } throws
            RuntimeException("网络抖动")
        scopedTrigger.checkAndTriggerMemorySummary("c1", config, AppSettings(autoSummarizeInterval = 10), "用户")
        advanceUntilIdle()
        coVerify(exactly = 2) { digestCoordinator.digestAndReconcile(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { conversationRepo.recordMemorySummaryResult("conv-1", success = false, now = any()) }
    }

    @Test
    fun T2_4摘要_间隔设为不限_刚成功过也照总结() {
        // 字段接线证据：cooldown=0 → 时间轨恒就绪；同参 cooldown=30 时 1 秒前刚成功、攒 10 轮 <2×10 → 不触发。
        stubSummaryPath(outsideUserRounds = 10, lastSuccessDate = System.currentTimeMillis() - 1_000L)
        trigger.checkAndTriggerMemorySummary(
            "c1", config, AppSettings(autoSummarizeInterval = 10, memorySummaryCooldownMinutes = 0), "用户",
        )
        coVerify(exactly = 1) { digestCoordinator.digestAndReconcile(any(), any(), any(), any(), any(), any()) }

        val waiting = newTrigger(CoroutineScope(Dispatchers.Unconfined))
        waiting.checkAndTriggerMemorySummary(
            "c1", config, AppSettings(autoSummarizeInterval = 10, memorySummaryCooldownMinutes = 30), "用户",
        )
        coVerify(exactly = 1) { digestCoordinator.digestAndReconcile(any(), any(), any(), any(), any(), any()) }
    }

    // ---- 结构化记忆守卫 ----

    @Test
    fun 结构化_成长系统关_不递增不抽取() {
        trigger.incrementStructuredMemoryRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = false), "用户",
        )
        coVerify(exactly = 0) { characterWriteLock.withCharacterLock<StructuredMemoryMetadata?>(any(), any()) }
        coVerify(exactly = 0) { structuredCoordinator.extractAndPersist(any(), any(), any()) }
    }

    @Test
    fun 结构化_间隔为0_不抽取() {
        trigger.incrementStructuredMemoryRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, structuredMemoryInterval = 0), "用户",
        )
        coVerify(exactly = 0) { structuredCoordinator.extractAndPersist(any(), any(), any()) }
    }

    // ---- 结构化记忆真触发路径 ----

    @Test
    fun 结构化_达阈值_递增并触发抽取() {
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        // 新角色 rounds=0；interval=1 → 递增到 1 ≥ 1 且无上次提取时间 → 触发。
        trigger.incrementStructuredMemoryRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, structuredMemoryInterval = 1), "用户",
        )
        coVerify { characterRepo.updateStructuredMemoryMetadata("c1", any()) } // 递增已写回
        coVerify { structuredCoordinator.extractAndPersist(characterUuid = "c1", config = config, userName = "用户") }
    }

    @Test
    fun 结构化_未达阈值_只递增不抽取() {
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        // 新角色 rounds=0；interval=10 → 递增到 1 < 10 → 不触发，但递增照常。
        trigger.incrementStructuredMemoryRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, structuredMemoryInterval = 10), "用户",
        )
        coVerify { characterRepo.updateStructuredMemoryMetadata("c1", any()) }
        coVerify(exactly = 0) { structuredCoordinator.extractAndPersist(any(), any(), any()) }
    }

    // ---- 活人感一期 P3 · T2-6：结构化记忆首次门槛 min(10, interval)、之后回 interval（默认 30，E13）----

    /** 构造带指定结构化记忆元数据的角色。 */
    private fun charWithStructured(rounds: Int, lastExtraction: Long? = null): CharacterEntity =
        CharacterEntity(
            uuid = "c1",
            name = "测试",
            creationDate = 0L,
            structuredMemoryMetadataJSON = StructuredMemoryMetadata(
                lastExtractionDate = lastExtraction,
                roundsSinceLastExtraction = rounds,
            ).encode(),
        )

    @Test
    fun P3结构化_全新角色第10轮触发_默认interval30本不会触发() {
        // 从未抽取过（lastExtractionDate=null）：rounds 9→10，首次门槛=min(10,30)=10 → 触发；双轨守卫 lastDate=null 不生效。
        coEvery { characterRepo.get("c1") } returns charWithStructured(rounds = 9)
        trigger.incrementStructuredMemoryRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, structuredMemoryInterval = 30), "用户",
        )
        coVerify { structuredCoordinator.extractAndPersist(characterUuid = "c1", config = config, userName = "用户") }
    }

    @Test
    fun P3结构化_全新角色第9轮不触发() {
        coEvery { characterRepo.get("c1") } returns charWithStructured(rounds = 8) // 8→9 <10
        trigger.incrementStructuredMemoryRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, structuredMemoryInterval = 30), "用户",
        )
        coVerify(exactly = 0) { structuredCoordinator.extractAndPersist(any(), any(), any()) }
    }

    @Test
    fun P3结构化_抽取过的角色第10轮不触发_回30门槛() {
        // 已抽取过（lastExtractionDate 非 null，40 分钟前）：首次门槛不再适用，回 interval=30；rounds 9→10 <30 → 不触发。
        val fortyMinAgo = System.currentTimeMillis() - 40 * 60_000L
        coEvery { characterRepo.get("c1") } returns charWithStructured(rounds = 9, lastExtraction = fortyMinAgo)
        trigger.incrementStructuredMemoryRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, structuredMemoryInterval = 30), "用户",
        )
        coVerify(exactly = 0) { structuredCoordinator.extractAndPersist(any(), any(), any()) }
    }

    @Test
    fun P3结构化_抽取过的角色第30轮触发_双轨时间就绪() {
        // 已抽取过（40 分钟前 ≥30min → 双轨 timeReady）：rounds 29→30 = interval → 触发。
        val fortyMinAgo = System.currentTimeMillis() - 40 * 60_000L
        coEvery { characterRepo.get("c1") } returns charWithStructured(rounds = 29, lastExtraction = fortyMinAgo)
        trigger.incrementStructuredMemoryRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, structuredMemoryInterval = 30), "用户",
        )
        coVerify { structuredCoordinator.extractAndPersist(characterUuid = "c1", config = config, userName = "用户") }
    }

    // ---- 活人感二期 M3 · T2-3：结构化抽取成功后重烤回调（E10）----

    @Test
    fun 二期M3_抽取成功_重烤回调触发一次() {
        // rounds=0 + interval=1 → 触发抽取；extractAndPersist relaxed 成功（不抛）→ 成功后回调一次。
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        trigger.incrementStructuredMemoryRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, structuredMemoryInterval = 1), "用户",
        )
        coVerify { structuredCoordinator.extractAndPersist(characterUuid = "c1", config = config, userName = "用户") }
        verify(exactly = 1) { onStructuredMemoryExtracted("c1") }
    }

    @Test
    fun 二期M3_抽取确定性失败_不触发重烤回调() {
        // extractAndPersist 抛 StructuredMemoryError（确定性·无消息/解析失败）→ 不重试、不回调（E10）。
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        coEvery { structuredCoordinator.extractAndPersist(any(), any(), any()) } throws StructuredMemoryError.NoMessages
        trigger.incrementStructuredMemoryRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, structuredMemoryInterval = 1), "用户",
        )
        verify(exactly = 0) { onStructuredMemoryExtracted(any()) }
    }
}
