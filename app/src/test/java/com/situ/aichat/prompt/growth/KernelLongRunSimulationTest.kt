package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.fromQuality
import com.situ.aichat.data.model.toQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.maintenance.MaintenanceThrottleStore
import com.situ.aichat.prompt.InnerStateRenderer
import com.situ.aichat.prompt.IntentExitRenderer
import com.situ.aichat.prompt.IntentScripts
import com.situ.aichat.prompt.contradictionDims
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 活人感内核·修缮卷 T2-SIM（图纸 §7 · E44）：**90 天三档用户假 LLM 模拟**——真 [GrowthAnalysisCoordinator] + 真 [AffectKernel] +
 * 真 [IntentKernel]，MockK DAO 用 [Row] 持有八列 JSON（读写 answers），MockK 分析服务按脚本回结果，[MutableClock] 推进时间。
 *
 * 三档：LIGHT 5 轮/天（08:00 08:05 12:30 19:00 22:00）· MEDIUM 30 轮/天（20:00 起每 4 分钟）· HEAVY 200 轮/天（09:00 / 14:00 / 20:00 三段各 67 轮每 2 分钟）；
 * 每轮 `affectKernel.tick + intentKernel.tick(uuid, t, "")` + 轮次 +1，达 `AnalysisPacing.growthInterval` 且距上次 ≥ 1h 即 `analyzeAndPersist`。
 * 脚本 GOOD =（g01 g04 g07 · closeness pos 3 · warmth +2）· BAD =（g05 g08 · closeness pos 1 neg 2 · tension pos 2）· CONFLICT =（g13 · tension pos 3 · closeness neg 3）。
 * 断言 S1–S8 从图纸 §3 反推（各 @Test 独立跑一份 90 天）；S6 不变式在**每次**分析后核。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KernelLongRunSimulationTest {

    private enum class Profile { LIGHT, MEDIUM, HEAVY }

    /** 角色行的八列（分析通道恒 3 写：updateGrowthAnalysis / updateIntentQueue / updateAffectField）。 */
    private class Row(quality: RelationshipQuality) {
        var spectrum = ""
        var quality = GrowthJson.encode(quality)
        var pressure = ""
        var interests = ""
        var metadata = ""
        var log = ""
        var affect = ""
        var intent = ""
        fun entity() = CharacterEntity(
            uuid = UUID, name = "夏晴子", creationDate = 0L,
            personalitySpectrumJSON = spectrum, relationshipQualityJSON = quality, relationshipPressureJSON = pressure,
            dynamicInterestsJSON = interests, growthMetadataJSON = metadata, growthLogJSON = log,
            affectFieldJSON = affect, intentQueueJSON = intent,
        )
    }

    private val zone: ZoneId = ZoneId.systemDefault()
    private val day0: LocalDate = LocalDate.of(2026, 9, 3)
    private fun at(day: Int, h: Int, m: Int): Long = day0.plusDays(day.toLong()).atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    private val service = mockk<GrowthAnalysisService>()
    private val dao = mockk<CharacterDao>(relaxed = true)
    private val affectKernel = AffectKernel(dao)
    private val intentKernel = IntentKernel(dao)
    private val clock = MutableClock(at(0, 8, 0), zone)
    private val coordinator = GrowthAnalysisCoordinator(
        service, dao, mockk<MilestoneDao>(relaxed = true), CharacterWriteLock(), mockk<SettingsRepository>(relaxed = true),
        mockk<MaintenanceThrottleStore>(relaxed = true), affectKernel, intentKernel, clock,
    )
    private val config = ApiConfigValues(providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k", baseUrl = "https://x", modelName = "m")
    private val settings = AppSettings()

    private lateinit var row: Row
    private var analyses = 0
    private var growthWrites = 0
    private var script: () -> GrowthAnalysisResult = { GOOD }
    private var maxNegCloseness = 0

    private fun wire(quality: RelationshipQuality = RelationshipQuality()) {
        row = Row(quality)
        coEvery { dao.getByUuid(UUID) } answers { row.entity() }
        coEvery { dao.getAffectFieldJson(UUID) } answers { row.affect }
        coEvery { dao.updateAffectField(UUID, any()) } answers { row.affect = secondArg() }
        coEvery { dao.getIntentQueueJson(UUID) } answers { row.intent }
        coEvery { dao.updateIntentQueue(UUID, any()) } answers { row.intent = secondArg() }
        coEvery { dao.updateGrowthAnalysis(UUID, any(), any(), any(), any(), any(), any()) } answers {
            row.spectrum = arg(1); row.quality = arg(2); row.interests = arg(3); row.metadata = arg(4); row.log = arg(5); row.pressure = arg(6)
            growthWrites++
        }
        coEvery { service.collectAnalysisWindow(UUID, any()) } returns AnalysisWindow(
            leadIn = emptyList(),
            fresh = listOf(MessageEntity(messageUUID = "m", conversationUuid = "c", roleRaw = "user", content = "hi", timestamp = 1L)),
        )
        coEvery { service.analyzeGrowth(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers { script() }
    }

    private fun times(profile: Profile, day: Int): List<Long> = when (profile) {
        Profile.LIGHT -> listOf(at(day, 8, 0), at(day, 8, 5), at(day, 12, 30), at(day, 19, 0), at(day, 22, 0))
        Profile.MEDIUM -> (0 until 30).map { at(day, 20, 0) + it * 4 * 60_000L }
        Profile.HEAVY -> listOf(9, 14, 20).flatMap { h -> (0 until 67).map { at(day, h, 0) + it * 2 * 60_000L } }
    }

    private fun affect() = GrowthJson.decodeAffectFieldOrNull(row.affect) ?: AffectField()
    private fun quality() = GrowthJson.decodeRelationshipQuality(row.quality)
    private fun pressure() = row.entity().let { GrowthJson.decodeRelationshipPressureOrNull(it.relationshipPressureJSON) ?: RelationshipPressure.fromQuality(quality()) }
    private fun securityRead(t: Long) = fieldForRead(affect(), t, zone).security

    /** 一轮：tick 场 → tick 意图 → 轮次 +1 → 达门槛且 ≥1h 即分析（照 RelationshipAnalysisTrigger 的判定序）。 */
    private suspend fun round(t: Long) {
        clock.set(t)
        affectKernel.tick(UUID, t, zone)
        intentKernel.tick(UUID, t, "")
        val meta = GrowthJson.decodeGrowthMetadata(row.metadata)
        val inc = meta.copy(roundsSinceLastAnalysis = meta.roundsSinceLastAnalysis + 1)
        row.metadata = GrowthJson.encode(inc)
        if (inc.roundsSinceLastAnalysis < AnalysisPacing.growthInterval(inc.totalAnalysisCount, settings.growthAnalysisInterval)) return
        val last = inc.lastAnalysisDate
        if (last != null && t - last < 3_600_000L) return
        coordinator.analyzeAndPersist(UUID, config, "小明", settings)
        analyses++
        checkInvariants(t)
    }

    /** S6：每次分析后 净额 == 压强派生 · 四场在域内 · 预算 ≤ 40 · 慢场日帽 ≤ 15。 */
    private fun checkInvariants(t: Long) {
        assertEquals("第 $analyses 次分析后净额列 ≠ 压强派生", pressure().toQuality(), quality())
        val f = affect()
        assertTrue(f.security in 0..100 && f.investment in 0..100 && f.valence in -100..100 && f.arousal in 0..100)
        assertTrue("budgetUsed ${f.budgetUsed}", f.budgetUsed <= AffectField.DAILY_BUDGET)
        assertTrue("slowDayUsed ${f.slowDayUsed}", f.slowDayUsed.all { it in 0..AffectField.FIELD_DAY_CAP })
        assertEquals("S6 写次数 == 分析次数（第 $analyses 次）", analyses, growthWrites)
        assertFalse(
            "S7 队列从无 WANT_SHARE 残留（第 $analyses 次分析后）",
            (GrowthJson.decodeIntentQueueOrNull(row.intent)?.intents ?: emptyList()).any { it.kind == IntentKind.WANT_SHARE && it.residue },
        )
        maxNegCloseness = maxOf(maxNegCloseness, pressure().neg[RelationshipQuality.DIMENSION_KEYS.indexOf("closeness")])
    }

    private fun runDays(profile: Profile, days: IntRange, onDayEnd: (Int) -> Unit = {}) = runBlocking {
        for (d in days) {
            for (t in times(profile, d)) round(t)
            onDayEnd(d)
        }
    }

    // MARK: - S1 / S7 LIGHT · GOOD

    @Test
    fun s1_s7_lightGood_securityRisesSteadily_shareIntentBornWithoutResidue() {
        wire(); script = { GOOD }
        var s30 = 0
        runDays(Profile.LIGHT, 0 until 90) { d -> if (d == 29) s30 = securityRead(at(29, 23, 0)) }
        val s90 = securityRead(at(89, 23, 0))
        assertTrue("S1 security(90d)=$s90", s90 >= 70)
        assertTrue("S1 单调：90d $s90 > 30d $s30 > 50", s90 > s30 && s30 > 50)
        val log = GrowthJson.decodeGrowthLog(row.log).map { it.summary }
        assertTrue("S7 无了结行", log.none { it.contains("（了结）") })
        assertTrue("S7 有想分享萌生行", log.any { it == IntentScripts.thirdPerson(IntentKind.WANT_SHARE, "夏晴子", "小明") + "（萌生）" })
    }

    // MARK: - S2 HEAVY · GOOD：慢场日帽

    @Test
    fun s2_heavyGood_dailySecurityRiseCappedAt15() {
        wire(); script = { GOOD }
        val dayEnd = mutableListOf<Int>()
        runDays(Profile.HEAVY, 0 until 10) { d -> dayEnd += securityRead(at(d, 23, 30)) }
        assertTrue("S2 security(1d)=${dayEnd[0]} ≤ 66", dayEnd[0] <= 66)
        for (i in 1 until dayEnd.size) assertTrue("S2 第 $i 日增幅 ${dayEnd[i] - dayEnd[i - 1]} > 16", dayEnd[i] - dayEnd[i - 1] <= 16)
        assertTrue("HEAVY 十天已明显上升：${dayEnd.last()}", dayEnd.last() >= 70)
    }

    // MARK: - S3 MEDIUM · GOOD⇄BAD：重叠泄放让负压不攒到矛盾阈

    @Test
    fun s3_mediumAlternating_negClosenessStaysBelow55_noContradictionAtEnd() {
        wire(); script = { if (analyses % 2 == 0) GOOD else BAD }
        runDays(Profile.MEDIUM, 0 until 90)
        assertTrue("S3 max neg[closeness]=$maxNegCloseness", maxNegCloseness < RelationshipBands.PRESSURE_CONTRADICTION_MIN)
        assertTrue("S3 末日无矛盾维", contradictionDims(pressure()).isEmpty())
        assertTrue("交替脚本真跑了两种", analyses >= 40)
    }

    // MARK: - S4 MEDIUM · CONFLICT：矛盾维（施工偏差 D-S4·见图纸 §11）

    /**
     * **施工偏差 D-S4（图纸 §11）**：图纸 §7 原断言「第 30 日与第 90 日 contradictionDims 含 closeness」在 §3 机制下**不可达**。
     * 纯冲突脚本只给 closeness 负压、正压零输入：实测（起点 closeness 90）正压每日 −2 左右（LLM 负压走净额路 + g13 扩散）、负压每日 +2.4，
     * 第 20 日两侧在 50 交汇（都没到 55）⇒ I-2「净额下溢 ⇒ n = p」把两侧钳成同值，再由 §3.3 重叠泄放一路磨到 0（第 30 日 32/32、第 90 日 0/0）；
     * 起点默认 10 更从未 ≥ 55。矛盾判据要求**两股力同时** ≥ 55——「只挨打不被爱」在 §3 口径下不是矛盾、是受伤。
     * R1 裁决：交汇高度 ≈ 90·b/(a+b) 随分析节奏变（MEDIUM ≈ 50 不成矛盾，HEAVY 可能短暂越 55），故「零矛盾日」不钉；
     * 本例只钉机制必然的形状（净额归零 / I-2 钳等 / 泄放磨低 / 张力不泄压 / 矛盾不永久），D-3 的「出现→消退」由 [s4b_…] 守。
     */
    @Test
    fun s4_mediumConflict_closenessCollapsesToZero_pressuresConverge_noContradiction_tensionSaturates() {
        wire(RelationshipQuality(closeness = 90)); script = { CONFLICT }
        val closenessIdx = RelationshipQuality.DIMENSION_KEYS.indexOf("closeness")
        var contradictionDays = 0
        var netZeroSince = -1
        var t30 = 0
        runDays(Profile.MEDIUM, 0 until 90) { d ->
            if (closenessIdx in contradictionDims(pressure())) contradictionDays++
            if (quality().closeness == 0 && netZeroSince < 0) netZeroSince = d
            if (d == 29) t30 = quality().tension
            if (netZeroSince >= 0 && d > netZeroSince) {
                val p = pressure()
                assertEquals("净额归零后 I-2 下溢钳位：pos == neg（第 $d 日）", p.pos[closenessIdx], p.neg[closenessIdx])
            }
        }
        val p90 = pressure()
        println("S4 netZeroSince=$netZeroSince contradictionDays=$contradictionDays day90=${p90.pos[closenessIdx]}/${p90.neg[closenessIdx]} tension30=$t30 tension90=${quality().tension}")
        assertTrue("S4 closeness 净额在第 30 日前归零（实测第 $netZeroSince 日）", netZeroSince in 0..29)
        assertEquals("S4 第 90 日净额仍 0", 0, quality().closeness)
        assertTrue("S4 第 90 日两侧被重叠泄放磨到 < 20：${p90.pos[closenessIdx]}", p90.pos[closenessIdx] < 20)
        assertFalse("S4 第 90 日无矛盾（D-3：矛盾不许永久；矛盾日数 $contradictionDays 只作观察——MEDIUM 节奏两侧在 ~50 交汇，HEAVY 节奏可能短暂 1–2 日）", closenessIdx in contradictionDims(p90))
        assertTrue("S4 张力不被泄压、第 30 日已 ≥ 90：$t30", t30 >= 90)
        assertEquals(analyses, growthWrites)
    }

    // MARK: - S4b MEDIUM · 真矛盾：pos5/neg5 30 天点亮 → 转 GOOD 60 天消退（D-3 反向守卫·R1 B🟡-1）

    @Test
    fun s4b_mediumAmbivalent30d_contradictionLights_thenClearsWithin60dOfGood() {
        wire(); script = { if (analyses < 30) AMBI else GOOD }
        val closenessIdx = RelationshipQuality.DIMENSION_KEYS.indexOf("closeness")
        var litByDay30 = false
        runDays(Profile.MEDIUM, 0 until 90) { d -> if (d == 29) litByDay30 = closenessIdx in contradictionDims(pressure()) }
        val p90 = pressure()
        assertTrue("S4b 第 30 日矛盾点亮（两股力同时 ≥ 55；J2 泄放不许把真矛盾泄没）", litByDay30)
        assertTrue("S4b 第 90 日矛盾已消退：neg=${p90.neg[closenessIdx]}", closenessIdx !in contradictionDims(p90))
        assertTrue("S4b 消退靠重叠泄放（30 天半衰·60 天 ≈ ×0.25）：neg < 55", p90.neg[closenessIdx] < RelationshipBands.PRESSURE_CONTRADICTION_MIN)
    }

    // MARK: - S5 HEAVY · CONFLICT：张力不被泄压抵消

    @Test
    fun s5_heavyConflict_tensionAt30dAtLeast30() {
        wire(); script = { CONFLICT }
        runDays(Profile.HEAVY, 0 until 30)
        assertTrue("S5 tension(30d)=${quality().tension}", quality().tension >= 30)
        assertEquals(analyses, growthWrites)
    }

    // MARK: - S8 MEDIUM · GOOD 30 天 → 停 30 天 → 回来

    @Test
    fun s8_mediumGood_thenAway30Days_noResidueLine_securityHalvedTowardBaseline() {
        wire(); script = { GOOD }
        runDays(Profile.MEDIUM, 0 until 30)
        val leaveAt = at(29, 22, 0)
        val v = securityRead(leaveAt)
        val back = at(59, 20, 0)
        runBlocking { round(back) }   // 回来首 tick（同时可能触发分析——此处轮次未达门槛）
        val q = GrowthJson.decodeIntentQueueOrNull(row.intent)!!
        assertNull("S8 回来首 tick 内心行无意图句 / 残留句", IntentExitRenderer.chatCandidate(q.intents, "小明", back))
        val expect = 50 + (v - 50) / 2
        val got = securityRead(back)
        assertTrue("S8 securityRead=$got 应在 [${expect - 2}, ${expect + 2}]（离开时 $v）", got in (expect - 2)..(expect + 2))
    }

    // MARK: - S9 MEDIUM · GOOD 90 天：内心行换气（微图纸 2026-09-02 §5·治 R1 探针 N1「90 天只有 1 种句子」）

    @Test
    fun s9_mediumGood_innerLineBreathes_atLeastFiveDistinct_noLineRepeatsMoreThanThreeConsecutiveDays() {
        wire(); script = { GOOD }
        val lines = mutableListOf<String>()
        var securityHighDays = 0   // R1 A-1：资格门的模拟层判别——安全感封顶后「踏实句」只许出现在跨档后 3 天
        val securityHigh = (0 until RelationshipBands.SCRIPT_VARIANTS).map { com.situ.aichat.prompt.InnerStateScripts.securityHigh("小明", it) }
        runDays(Profile.MEDIUM, 0 until 90) { d ->
            val t = at(d, 23, 0)
            val q = GrowthJson.decodeIntentQueueOrNull(row.intent)?.intents ?: emptyList()
            val line = InnerStateRenderer.render(fieldForRead(affect(), t, zone), pressure(), emptyList(), "小明", 23, t, q, zone)
            lines += line
            if (securityHigh.any { line.contains(it) }) securityHighDays++
        }
        var longest = 1
        var run = 1
        for (i in 1 until lines.size) {
            run = if (lines[i] == lines[i - 1]) run + 1 else 1
            longest = maxOf(longest, run)
        }
        println("S9 distinct=${lines.toSet().size} longestRun=$longest empty=${lines.count { it.isEmpty() }} first12=${lines.take(12)}")
        assertTrue("S9 90 天内心行至少 5 种（实际 ${lines.toSet().size}）", lines.toSet().size >= 5)
        assertTrue("S9 同一行连续出现 ≤ 3 天（实际 $longest）", longest <= 3)
        assertTrue("S9 安全感封顶后踏实句只出现在跨档后 3 天内（实际 $securityHighDays 天·资格门·R1 A-1）", securityHighDays in 1..3)
        assertEquals(analyses, growthWrites)
    }

    private companion object {
        const val UUID = "u"
        fun result(hits: List<String>, rel: Map<String, GrowthAnalysisResult.PressureDelta>, personality: Map<String, Int> = emptyMap()) = GrowthAnalysisResult(
            personalityChanges = personality, relationshipChanges = rel, newInterests = emptyList(), interestHeatChanges = emptyMap(),
            events = listOf(GrowthAnalysisResult.GrowthEvent(GrowthEventType.MAJOR_EVENT, "x")), narrative = "n", gainHits = hits,
        )
        val GOOD = result(listOf("g01", "g04", "g07"), mapOf("closeness" to GrowthAnalysisResult.PressureDelta(3, 0)), mapOf("warmth" to 2))
        val BAD = result(listOf("g05", "g08"), mapOf("closeness" to GrowthAnalysisResult.PressureDelta(1, 2), "tension" to GrowthAnalysisResult.PressureDelta(2, 0)))
        val CONFLICT = result(listOf("g13"), mapOf("tension" to GrowthAnalysisResult.PressureDelta(3, 0), "closeness" to GrowthAnalysisResult.PressureDelta(0, 3)))
        /** 真矛盾：同一次相处两股力都强（说了心里话 + 被误解）。 */
        val AMBI = result(listOf("g07", "g08"), mapOf("closeness" to GrowthAnalysisResult.PressureDelta(5, 5)))
    }
}
