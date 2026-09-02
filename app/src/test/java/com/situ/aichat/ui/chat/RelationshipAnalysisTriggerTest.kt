package com.situ.aichat.ui.chat

import android.util.Log
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.GrowthAnalysisMetadata
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.prompt.growth.AffectKernel
import com.situ.aichat.prompt.growth.GrowthAnalysisCoordinator
import com.situ.aichat.prompt.growth.GrowthAnalysisError
import com.situ.aichat.prompt.growth.GrowthAnalysisResult
import com.situ.aichat.prompt.growth.IntentKernel
import com.situ.aichat.prompt.growth.RelationshipAnalysisCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RelationshipAnalysisTrigger 测试——验证刀4b 成长+关系簇协作者（成长命脉）「真的能用」（不止编译过）。
 *
 * T1：3 个 companion 纯函数（[RelationshipAnalysisTrigger.relationshipBand] /
 * [RelationshipAnalysisTrigger.detectRelationshipBandCrossing] / [RelationshipAnalysisTrigger.shouldTriggerRelationshipFallback]）
 * 断言从规格独立反推（阶段线 / 7 天 / 100 轮+24h），非照搬实现。
 *
 * T2（行为）：MockK 假掉 5 个依赖；`withCharacterLock` 被 stub 为真正执行其 block（否则递增/触发逻辑跑不到）；
 * Unconfined 让 fire-and-forget 协程同步跑完。覆盖成长两守卫 + 真触发/未达只递增、关系自动推进守卫 + 保底命中真触发、
 * 以及**成长完成→链式触发关系评估的命脉路径**（显著事件 + ≥30 轮）。
 */
class RelationshipAnalysisTriggerTest {

    // ---- T1：companion 纯函数（无需依赖、无需协程） ----

    @Test
    fun 阶段段位_落在正确区间() {
        val b = intArrayOf(10, 20, 30, 50, 70, 85, 95, 100)
        // 边界归属左闭右含上界：value <= boundary → 该段。
        assertEquals(0, RelationshipAnalysisTrigger.relationshipBand(0, b))
        assertEquals(0, RelationshipAnalysisTrigger.relationshipBand(10, b))   // 10 仍属第 0 段
        assertEquals(1, RelationshipAnalysisTrigger.relationshipBand(11, b))   // 越过 10 → 第 1 段
        assertEquals(3, RelationshipAnalysisTrigger.relationshipBand(50, b))
        assertEquals(7, RelationshipAnalysisTrigger.relationshipBand(100, b))  // 末段 100 防饱和死锁
    }

    @Test
    fun 跨段检测_同段或无变化为false_跨段为true() {
        val before = RelationshipQuality() // 默认 familiarity=10 → 第 0 段
        // 完全相同 → false
        assertFalse(RelationshipAnalysisTrigger.detectRelationshipBandCrossing(before, before))
        // familiarity 10→5：仍在第 0 段（≤10），其余维度不变 → false
        assertFalse(
            RelationshipAnalysisTrigger.detectRelationshipBandCrossing(before, before.setValue(0, 5)),
        )
        // familiarity 10→25：第 0 段(≤10)跨到第 2 段(≤30) → true
        assertTrue(
            RelationshipAnalysisTrigger.detectRelationshipBandCrossing(before, before.setValue(0, 25)),
        )
    }

    @Test
    fun 保底触发_七天线与百轮24h线() {
        val now = 1_000_000_000_000L
        val day = 86_400_000L
        // ① 距参考时间 ≥7 天 → true（无视轮数）
        assertTrue(RelationshipAnalysisTrigger.shouldTriggerRelationshipFallback(0, now - 8 * day, 0L, now))
        // <7 天且轮数 <100 → false
        assertFalse(RelationshipAnalysisTrigger.shouldTriggerRelationshipFallback(50, now - 1 * day, 0L, now))
        // ② ≥100 轮且 ≥24h → true
        assertTrue(RelationshipAnalysisTrigger.shouldTriggerRelationshipFallback(100, now - 25 * 3_600_000L, 0L, now))
        // ≥100 轮但 <24h → false（24h 冷却防反复触发）
        assertFalse(RelationshipAnalysisTrigger.shouldTriggerRelationshipFallback(150, now - 1 * 3_600_000L, 0L, now))
        // lastAnalysisDate 为 null 时回退用 creationDate
        assertTrue(RelationshipAnalysisTrigger.shouldTriggerRelationshipFallback(0, null, now - 8 * day, now))
    }

    // ---- T2：行为测试（MockK + Unconfined + withCharacterLock 真执行 block） ----

    private lateinit var characterRepo: CharacterRepository
    private lateinit var characterWriteLock: CharacterWriteLock
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var growthCoordinator: GrowthAnalysisCoordinator
    private lateinit var relationshipCoordinator: RelationshipAnalysisCoordinator
    private lateinit var affectKernel: AffectKernel
    private lateinit var intentKernel: IntentKernel
    private lateinit var trigger: RelationshipAnalysisTrigger
    private val config = mockk<ApiConfigValues>(relaxed = true)

    @Before
    fun setUp() {
        // performGrowthAnalysis/performRelationshipAnalysis 首行 Log.d 在 try 之外；纯 JVM 单测里 android.util.Log 默认抛
        // "not mocked" 会在 coordinator 调用前中断 → 静态假掉 Log（本测不验日志副作用，保持纯 JVM 快测，不升级 Robolectric）。
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0   // 修缮卷 D-13：失败路径打 Log.w（T2-4 断言其文案）
        characterRepo = mockk(relaxed = true)
        characterWriteLock = mockk(relaxed = true)
        apiConfigRepo = mockk(relaxed = true)
        growthCoordinator = mockk(relaxed = true)
        relationshipCoordinator = mockk(relaxed = true)
        affectKernel = mockk(relaxed = true)
        intentKernel = mockk(relaxed = true)
        // withCharacterLock 真正执行其 block（否则"重读→+1→写回→触发判定"逻辑不会跑）。
        // 成长递增 block 返回 GrowthAnalysisMetadata?，关系递增 block 返回 CharacterEntity? → 两个 reified 类型各 stub 一次。
        coEvery { characterWriteLock.withCharacterLock<GrowthAnalysisMetadata?>(any(), any()) } coAnswers {
            secondArg<suspend () -> GrowthAnalysisMetadata?>().invoke()
        }
        coEvery { characterWriteLock.withCharacterLock<CharacterEntity?>(any(), any()) } coAnswers {
            secondArg<suspend () -> CharacterEntity?>().invoke()
        }
        // relaxed mock 对可空返回类型给 null → 关系评估 prompt 配置会被 `?: return` 截断；显式给非空，确保触发路径能走完。
        coEvery { apiConfigRepo.resolveConfigValues(any()) } returns mockk<ApiConfigValues>(relaxed = true)
        trigger = RelationshipAnalysisTrigger(
            scope = CoroutineScope(Dispatchers.Unconfined),
            characterRepo = characterRepo,
            characterWriteLock = characterWriteLock,
            apiConfigRepo = apiConfigRepo,
            growthCoordinator = growthCoordinator,
            relationshipCoordinator = relationshipCoordinator,
            affectKernel = affectKernel,
            intentKernel = intentKernel,
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class) // 防静态假泄漏到其他测试类
    }

    // ---- 成长守卫 ----

    @Test
    fun 成长_系统关_不递增不分析() {
        trigger.incrementGrowthRoundAndCheck("c1", config, AppSettings(growthSystemEnabled = false), "用户")
        coVerify(exactly = 0) { characterWriteLock.withCharacterLock<GrowthAnalysisMetadata?>(any(), any()) }
        coVerify(exactly = 0) { growthCoordinator.analyzeAndPersist(any(), any(), any(), any()) }
    }

    @Test
    fun 成长_间隔为0_不递增() {
        trigger.incrementGrowthRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, growthAnalysisInterval = 0), "用户",
        )
        coVerify(exactly = 0) { characterWriteLock.withCharacterLock<GrowthAnalysisMetadata?>(any(), any()) }
    }

    // ---- 卷三 T2-2（Trigger 部分·E19 / E24 / E35）：每轮回合尾恰 tick 一次场内核，且不拿 CharacterWriteLock ----

    @Test
    fun 成长_每轮递增_恰tick场内核一次() {
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        trigger.incrementGrowthRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, growthAnalysisInterval = 10), "用户",
        )
        coVerify(exactly = 1) { affectKernel.tick("c1", any(), any()) }
        // tick 不进角色写锁：写锁只被「递增」那一次拿到（若 tick 也拿，就是 2 次）。
        coVerify(exactly = 1) { characterWriteLock.withCharacterLock<GrowthAnalysisMetadata?>(any(), any()) }
    }

    @Test
    fun 成长_系统关_不tick() {
        trigger.incrementGrowthRoundAndCheck("c1", config, AppSettings(growthSystemEnabled = false), "用户")
        coVerify(exactly = 0) { affectKernel.tick(any(), any(), any()) }
    }

    @Test
    fun 成长_tick抛异常_递增与分析照常() {
        coEvery { affectKernel.tick(any(), any(), any()) } throws IllegalStateException("boom")
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        trigger.incrementGrowthRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, growthAnalysisInterval = 1), "用户",
        )
        coVerify { characterRepo.updateGrowthMetadata("c1", any()) }
        coVerify { growthCoordinator.analyzeAndPersist("c1", config, "用户", any()) }
    }

    // ---- 卷四 T2-3（tick 部分·K-15 / K-16 / N-5）：每轮递增恰 tick 意图内核一次、文本透传、两 tick 顺序不嵌套、系统关不 tick、抛异常不影响 ----

    @Test
    fun 卷四_成长_每轮递增_恰tick意图内核一次_文本透传_场先意图后() {
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        trigger.incrementGrowthRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, growthAnalysisInterval = 10), "用户",
            userText = "用户文本",
        )
        coVerify(exactly = 1) { intentKernel.tick("c1", any(), "用户文本") }
        coVerifyOrder {
            affectKernel.tick("c1", any(), any())
            intentKernel.tick("c1", any(), any())
        }
        // 两个 tick 都不进角色写锁：写锁仍只被「递增」那一次拿到
        coVerify(exactly = 1) { characterWriteLock.withCharacterLock<GrowthAnalysisMetadata?>(any(), any()) }
    }

    @Test
    fun 卷四_成长_不传文本_tick收到空串() {
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        trigger.incrementGrowthRoundAndCheck("c1", config, AppSettings(growthSystemEnabled = true, growthAnalysisInterval = 10), "用户")
        coVerify(exactly = 1) { intentKernel.tick("c1", any(), "") }
    }

    @Test
    fun 卷四_成长_系统关_不tick意图() {
        trigger.incrementGrowthRoundAndCheck("c1", config, AppSettings(growthSystemEnabled = false), "用户", userText = "x")
        coVerify(exactly = 0) { intentKernel.tick(any(), any(), any()) }
    }

    @Test
    fun 卷四_成长_意图tick抛异常_递增与分析照常() {
        coEvery { intentKernel.tick(any(), any(), any()) } throws IllegalStateException("boom")
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        trigger.incrementGrowthRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, growthAnalysisInterval = 1), "用户",
        )
        coVerify { characterRepo.updateGrowthMetadata("c1", any()) }
        coVerify { growthCoordinator.analyzeAndPersist("c1", config, "用户", any()) }
    }

    // ---- 修缮卷 T2-4（E25 / D-13）：分析失败 ⇒ 零写 + 恰一条 Log.w ----

    @Test
    fun 修缮卷_分析抛确定性错误_打一条确定性失败日志() {
        coEvery { growthCoordinator.analyzeAndPersist(any(), any(), any(), any()) } throws GrowthAnalysisError.NoMessages
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        trigger.incrementGrowthRoundAndCheck("c1", config, AppSettings(growthSystemEnabled = true, growthAnalysisInterval = 1), "用户")
        verify(exactly = 1) { Log.w("GrowthAnalysis", match<String> { it.startsWith("成长分析确定性失败：NoMessages") }) }
        verify(exactly = 0) { Log.w("GrowthAnalysis", match<String> { it.startsWith("成长分析瞬态失败") }) }
        coVerify(exactly = 0) { relationshipCoordinator.analyzeAndPersist(any(), any(), any(), any()) }
    }

    @Test
    fun 修缮卷_分析抛瞬态错误_打一条瞬态失败日志() {
        coEvery { growthCoordinator.analyzeAndPersist(any(), any(), any(), any()) } throws IllegalStateException("网络")
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        trigger.incrementGrowthRoundAndCheck("c1", config, AppSettings(growthSystemEnabled = true, growthAnalysisInterval = 1), "用户")
        verify(exactly = 1) { Log.w("GrowthAnalysis", "成长分析瞬态失败：IllegalStateException") }
    }

    // ---- 成长真触发路径 ----

    @Test
    fun 成长_达阈值_递增并触发成长分析() {
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        // 新角色 rounds=0；interval=1 → 递增到 1 ≥ 1 且无上次分析时间 → 触发。
        trigger.incrementGrowthRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, growthAnalysisInterval = 1), "用户",
        )
        coVerify { characterRepo.updateGrowthMetadata("c1", any()) }                  // 递增已写回
        coVerify { growthCoordinator.analyzeAndPersist("c1", config, "用户", any()) }  // 成长分析已触发
    }

    @Test
    fun 成长_未达阈值_只递增不分析() {
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        // 新角色 rounds=0；interval=10 → 递增到 1 < 10 → 不触发，但递增照常。
        trigger.incrementGrowthRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, growthAnalysisInterval = 10), "用户",
        )
        coVerify { characterRepo.updateGrowthMetadata("c1", any()) }
        coVerify(exactly = 0) { growthCoordinator.analyzeAndPersist(any(), any(), any(), any()) }
    }

    // ---- 关系守卫 + 保底真触发 ----

    @Test
    fun 关系_自动推进关_不递增不分析() {
        trigger.incrementRelationshipRoundAndCheck("c1", AppSettings(relationshipAutoAdvanceEnabled = false), "用户")
        coVerify(exactly = 0) { characterWriteLock.withCharacterLock<CharacterEntity?>(any(), any()) }
        coVerify(exactly = 0) { relationshipCoordinator.analyzeAndPersist(any(), any(), any(), any()) }
    }

    @Test
    fun 关系_保底命中_递增并触发关系评估() {
        // 创建时间设为 epoch 0（远早于 7 天）→ shouldTriggerRelationshipFallback 第①条命中。
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L)
        trigger.incrementRelationshipRoundAndCheck("c1", AppSettings(relationshipAutoAdvanceEnabled = true), "用户")
        coVerify { characterRepo.updateRelationshipMessageCount("c1", 1) }                       // 递增已写回
        coVerify { relationshipCoordinator.analyzeAndPersist("c1", any(), "用户", "aiAutomatic") } // 保底触发关系评估
    }

    // ---- 成长命脉：成长完成 → 链式触发关系评估 ----

    @Test
    fun 命脉_成长有显著事件且达30轮_链式触发关系评估() {
        // 已聊 30 轮的角色：成长分析返回含 RELATIONSHIP_CHANGE 事件 → 满足链式触发条件。
        coEvery { characterRepo.get("c1") } returns
            CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L).copy(relationshipMessageCount = 30)
        coEvery { growthCoordinator.analyzeAndPersist(any(), any(), any(), any()) } returns GrowthAnalysisResult(
            personalityChanges = emptyMap(),
            relationshipChanges = emptyMap(),
            newInterests = emptyList(),
            interestHeatChanges = emptyMap(),
            events = listOf(GrowthAnalysisResult.GrowthEvent(GrowthEventType.RELATIONSHIP_CHANGE, "关系升温")),
            narrative = "",
        )
        trigger.incrementGrowthRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, growthAnalysisInterval = 1), "用户",
        )
        coVerify { growthCoordinator.analyzeAndPersist("c1", config, "用户", any()) }              // 成长先跑
        coVerify { relationshipCoordinator.analyzeAndPersist("c1", any(), "用户", "aiAutomatic") } // 命脉：链式触发关系评估
    }

    @Test
    fun 命脉_成长无事件且轮数不足_不链式触发关系评估() {
        // 仅聊 5 轮（<30）+ 成长无显著事件 → 链式条件不满足，不触发关系评估。
        coEvery { characterRepo.get("c1") } returns
            CharacterEntity(uuid = "c1", name = "测试", creationDate = 0L).copy(relationshipMessageCount = 5)
        coEvery { growthCoordinator.analyzeAndPersist(any(), any(), any(), any()) } returns GrowthAnalysisResult(
            personalityChanges = emptyMap(),
            relationshipChanges = emptyMap(),
            newInterests = emptyList(),
            interestHeatChanges = emptyMap(),
            events = emptyList(),
            narrative = "",
        )
        trigger.incrementGrowthRoundAndCheck(
            "c1", config, AppSettings(growthSystemEnabled = true, growthAnalysisInterval = 1), "用户",
        )
        coVerify { growthCoordinator.analyzeAndPersist("c1", config, "用户", any()) }
        coVerify(exactly = 0) { relationshipCoordinator.analyzeAndPersist(any(), any(), any(), any()) }
    }

    // ---- 活人感一期 P3 · T2-6：成长阶梯 首次 10 / 第二次 25 / 老角色回 30（默认 interval=30，E13）----

    /** 构造带指定成长元数据的角色（2h 前分析 → 跳过 1h 最小间隔守卫，隔离阶梯门槛这一变量）。 */
    private fun charWithGrowth(rounds: Int, totalCount: Int, lastAnalysis: Long? = null): CharacterEntity =
        CharacterEntity(
            uuid = "c1",
            name = "测试",
            creationDate = 0L,
            growthMetadataJSON = GrowthJson.encode(
                GrowthAnalysisMetadata(
                    lastAnalysisDate = lastAnalysis,
                    roundsSinceLastAnalysis = rounds,
                    totalAnalysisCount = totalCount,
                ),
            ),
        )

    private fun defaultGrowthSettings() =
        AppSettings(growthSystemEnabled = true, growthAnalysisInterval = 30)

    @Test
    fun P3成长_全新角色第10轮触发_默认门槛30本不会触发() {
        // totalAnalysisCount=0：rounds 9→10，首档=10 → 触发（旧固定门槛 30 此刻不会触发）。
        coEvery { characterRepo.get("c1") } returns charWithGrowth(rounds = 9, totalCount = 0)
        trigger.incrementGrowthRoundAndCheck("c1", config, defaultGrowthSettings(), "用户")
        coVerify { growthCoordinator.analyzeAndPersist("c1", config, "用户", any()) }
    }

    @Test
    fun P3成长_全新角色第9轮不触发() {
        coEvery { characterRepo.get("c1") } returns charWithGrowth(rounds = 8, totalCount = 0) // 8→9 <10
        trigger.incrementGrowthRoundAndCheck("c1", config, defaultGrowthSettings(), "用户")
        coVerify(exactly = 0) { growthCoordinator.analyzeAndPersist(any(), any(), any(), any()) }
    }

    @Test
    fun P3成长_第二次第25轮触发() {
        val twoHoursAgo = System.currentTimeMillis() - 7_200_000L
        coEvery { characterRepo.get("c1") } returns charWithGrowth(rounds = 24, totalCount = 1, lastAnalysis = twoHoursAgo)
        trigger.incrementGrowthRoundAndCheck("c1", config, defaultGrowthSettings(), "用户")
        coVerify { growthCoordinator.analyzeAndPersist("c1", config, "用户", any()) }
    }

    @Test
    fun P3成长_第二次仅第10轮不触发() {
        val twoHoursAgo = System.currentTimeMillis() - 7_200_000L
        coEvery { characterRepo.get("c1") } returns charWithGrowth(rounds = 9, totalCount = 1, lastAnalysis = twoHoursAgo) // 9→10 <25
        trigger.incrementGrowthRoundAndCheck("c1", config, defaultGrowthSettings(), "用户")
        coVerify(exactly = 0) { growthCoordinator.analyzeAndPersist(any(), any(), any(), any()) }
    }

    @Test
    fun P3成长_老角色回30轮门槛_第30轮触发() {
        val twoHoursAgo = System.currentTimeMillis() - 7_200_000L
        coEvery { characterRepo.get("c1") } returns charWithGrowth(rounds = 29, totalCount = 2, lastAnalysis = twoHoursAgo)
        trigger.incrementGrowthRoundAndCheck("c1", config, defaultGrowthSettings(), "用户")
        coVerify { growthCoordinator.analyzeAndPersist("c1", config, "用户", any()) }
    }

    @Test
    fun P3成长_老角色第25轮不触发() {
        val twoHoursAgo = System.currentTimeMillis() - 7_200_000L
        coEvery { characterRepo.get("c1") } returns charWithGrowth(rounds = 24, totalCount = 2, lastAnalysis = twoHoursAgo) // 24→25 <30
        trigger.incrementGrowthRoundAndCheck("c1", config, defaultGrowthSettings(), "用户")
        coVerify(exactly = 0) { growthCoordinator.analyzeAndPersist(any(), any(), any(), any()) }
    }
}
