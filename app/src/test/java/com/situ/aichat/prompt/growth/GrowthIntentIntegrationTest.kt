package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.GrowthAnalysisMetadata
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.GrowthLogEntry
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentQueueState
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.maintenance.MaintenanceThrottleStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 活人感内核·卷四《意图队列 + 性格复盘》T2-2（图纸 §7.2 · §3.6 · E53 / E56）：**真协调器 + 真 [AffectKernel] + 真 [IntentKernel]（MockK DAO）
 * + MockK 分析服务**，照 [GrowthKernelIntegrationTest] 骨架，验分析通道插入三处后端到端落库：
 * - 一次分析恰 `updateGrowthAnalysis 1 / updateIntentQueue 1 / updateAffectField 1`（写库次数·§3.8）
 * - `analyzeGrowth` 收到的 `intentSection`：队列预置 ACTIVE 道歉 ⇒ 含 `【夏晴子当前挂着的意图】`；空队列 ⇒ `""`
 * - `intentStatus = {wantApologize: resolved}` ⇒ 写出的队列该条 RESOLVED、`trust` 净额 = 基线 + 3、`neg[trust]` 不变、growthLog 含「了结」行
 * - g13 命中 + 投入 50 + 无既有意图 ⇒ 写出 COMFORT + APOLOGIZE 两条 BUDDING、growthLog 两条「萌生」
 * - E56 全 open 零变化；E53 100 条时 trim（性格复盘计数累加已随修缮卷砍除·用户 2026-09-02 拍板）
 *
 * 协调器时钟 = `Clock.fixed(now)`（修缮卷 J11 注入），意图与场列按「相对 now」构造（PITFALLS §1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GrowthIntentIntegrationTest {

    private val service = mockk<GrowthAnalysisService>()
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val milestoneDao = mockk<MilestoneDao>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val throttle = mockk<MaintenanceThrottleStore>(relaxed = true)
    private val affectKernel = AffectKernel(characterDao)
    private val intentKernel = IntentKernel(characterDao)
    private val now = System.currentTimeMillis()
    private val coordinator = GrowthAnalysisCoordinator(
        service, characterDao, milestoneDao, CharacterWriteLock(), settingsRepo, throttle, affectKernel, intentKernel,
        Clock.fixed(Instant.ofEpochMilli(now), ZoneId.systemDefault()),
    )
    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k", baseUrl = "https://x", modelName = "m",
    )
    private val hour = 3_600_000L
    private fun restingField(investment: Int = 30) =
        AffectField(investment = investment, updatedAt = now, budgetDayStart = localDayStart(now, ZoneId.systemDefault()))

    private fun character(
        rounds: Int = 12,
        growthLog: List<GrowthLogEntry> = emptyList(),
    ) = CharacterEntity(
        uuid = "u", name = "夏晴子", creationDate = 0L,
        personalitySpectrumJSON = GrowthJson.encode(PersonalitySpectrum.NEUTRAL),
        relationshipQualityJSON = GrowthJson.encode(RelationshipQuality()),
        growthMetadataJSON = GrowthJson.encode(GrowthAnalysisMetadata(lastAnalysisDate = now - 5 * hour, roundsSinceLastAnalysis = rounds)),
        growthLogJSON = GrowthJson.encodeGrowthLog(growthLog),
    )

    private fun apology(state: IntentState = IntentState.ACTIVE, strength: Int = 50) = CharacterIntent(
        id = "ap", kind = IntentKind.WANT_APOLOGIZE, state = state, strength = strength, bornAt = now - 2 * hour, lastChangeAt = now - 2 * hour,
    )

    private fun result(
        gainHits: List<String> = emptyList(),
        intentStatus: Map<String, String> = emptyMap(),
    ) = GrowthAnalysisResult(
        personalityChanges = emptyMap(), relationshipChanges = emptyMap(), newInterests = emptyList(),
        interestHeatChanges = emptyMap(), events = listOf(GrowthAnalysisResult.GrowthEvent(GrowthEventType.MAJOR_EVENT, "x")),
        narrative = "n", gainHits = gainHits, intentStatus = intentStatus,
    )

    private val sectionSlot = slot<String>()

    private fun stub(character: CharacterEntity, result: GrowthAnalysisResult, queue: IntentQueueState = IntentQueueState(), field: AffectField = restingField()) {
        // 整行（协调器 :130 读 · 意图段取材）与意图列单读（IntentKernel 锁内 fresh 读）是同一份数据，两处都给。
        coEvery { characterDao.getByUuid("u") } returns character.copy(intentQueueJSON = GrowthJson.encode(queue))
        coEvery { characterDao.getAffectFieldJson("u") } returns GrowthJson.encode(field)
        coEvery { characterDao.getIntentQueueJson("u") } returns GrowthJson.encode(queue)
        coEvery { service.collectAnalysisWindow("u", any()) } returns AnalysisWindow(
            leadIn = emptyList(),
            fresh = listOf(MessageEntity(messageUUID = "m1", conversationUuid = "c", roleRaw = "user", content = "hi", timestamp = now - hour)),
        )
        coEvery {
            service.analyzeGrowth(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(sectionSlot))
        } returns result
    }

    private fun run() = runBlocking { coordinator.analyzeAndPersist("u", config, "小明", AppSettings()) }

    private fun writtenQueue(): IntentQueueState {
        val json = slot<String>()
        coVerify(exactly = 1) { characterDao.updateIntentQueue("u", capture(json)) }
        return GrowthJson.decodeIntentQueueOrNull(json.captured) ?: error("写出的不是合法 JSON：${json.captured}")
    }

    private data class Written(val quality: RelationshipQuality, val pressure: RelationshipPressure, val log: List<GrowthLogEntry>)

    private fun writtenGrowth(): Written {
        val qual = slot<String>(); val pres = slot<String>(); val log = slot<String>()
        coVerify(exactly = 1) { characterDao.updateGrowthAnalysis("u", any(), capture(qual), any(), any(), capture(log), capture(pres)) }
        return Written(GrowthJson.decodeRelationshipQuality(qual.captured), GrowthJson.decodeRelationshipPressure(pres.captured), GrowthJson.decodeGrowthLog(log.captured))
    }

    // MARK: - 写库次数（§3.8：分析通道恒 3）

    @Test
    fun oneAnalysis_writesGrowthOnce_intentQueueOnce_fieldOnce() {
        stub(character(), result())
        run()
        coVerify(exactly = 1) { characterDao.updateGrowthAnalysis("u", any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { characterDao.updateIntentQueue("u", any()) }
        coVerify(exactly = 1) { characterDao.updateAffectField("u", any()) }
        coVerify(exactly = 1) { characterDao.getIntentQueueJson("u") }
    }

    // MARK: - intentSection 透传

    @Test
    fun intentSection_isPassedFromLiveQueue_andEmptyForEmptyQueue() {
        stub(character(), result(), queue = IntentQueueState(intents = listOf(apology())))
        run()
        assertTrue(sectionSlot.captured, sectionSlot.captured.startsWith("【夏晴子当前挂着的意图】\n- 夏晴子想向小明道歉（今天萌生，活跃中）[wantApologize]"))

        stub(character(), result())
        run()
        assertEquals("", sectionSlot.captured)
    }

    // MARK: - 层 ② 了结 ⇒ RESOLVED + 关系正压 + growthLog

    @Test
    fun resolvedByLlm_marksResolved_creditsTrustPos_logsResolutionLine() {
        stub(character(), result(intentStatus = mapOf("wantApologize" to "resolved")), queue = IntentQueueState(intents = listOf(apology())))
        run()
        val q = writtenQueue()
        val ap = q.intents.single()
        assertEquals(IntentState.RESOLVED, ap.state)
        assertEquals(0, ap.strength)
        assertTrue("RESOLVED 留队 24h 供冷却判定（K-8）", ap.lastChangeAt >= now)
        val w = writtenGrowth()
        assertEquals("信任净额 = 基线 20 + 3（预算未满）", 23, w.quality.trust)
        assertEquals("只动 pos：neg[trust] 仍 0", 0, w.pressure.neg[1])
        assertEquals(23, w.pressure.pos[1])
        assertTrue(w.log.any { it.summary == "夏晴子想向小明道歉（了结）" && it.type == GrowthEventType.RELATIONSHIP_CHANGE })
    }

    // MARK: - 萌生

    @Test
    fun g13Hit_withInvestment50_bornComfortAndApologize_logsTwoBirthLines() {
        stub(character(), result(gainHits = listOf("g13")), field = restingField(investment = 50))
        run()
        val q = writtenQueue()
        assertEquals(listOf(IntentKind.WANT_COMFORT, IntentKind.WANT_APOLOGIZE), q.intents.map { it.kind })
        assertTrue(q.intents.all { it.state == IntentState.BUDDING && it.strength == 50 })
        val log = writtenGrowth().log.map { it.summary }
        assertTrue(log.contains("夏晴子想被小明哄一哄（萌生）"))
        assertTrue(log.contains("夏晴子想向小明道歉（萌生）"))
    }

    // MARK: - E56 全 open ⇒ 零变化

    @Test
    fun allOpen_leavesQueueUntouched_exceptLifecycle() {
        val before = apology()
        stub(character(), result(intentStatus = emptyMap()), queue = IntentQueueState(intents = listOf(before)))
        run()
        val ap = writtenQueue().intents.single()
        assertEquals(before.state, ap.state)
        assertEquals(before.strength, ap.strength)
        assertEquals(before.lastChangeAt, ap.lastChangeAt)
        assertTrue(writtenGrowth().log.none { it.summary.contains("道歉") })
    }

    // MARK: - E53 growthLog 100 上限

    @Test
    fun growthLogAtCap_intentLinesStillTrimToMax() {
        val full = (1..100).map { GrowthLogEntry(timestamp = now - it * 1000L, type = GrowthEventType.MAJOR_EVENT, summary = "旧$it") }
        stub(character(growthLog = full), result(gainHits = listOf("g13")), field = restingField(investment = 50))
        run()
        val log = writtenGrowth().log
        assertEquals(AppSettings().growthLogMaxCount, log.size)
        assertEquals("夏晴子想向小明道歉（萌生）", log.last().summary)
    }
}
