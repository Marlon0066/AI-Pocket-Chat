package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.GrowthAnalysisMetadata
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.toQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.maintenance.MaintenanceThrottleStore
import com.situ.aichat.ui.chat.RelationshipAnalysisTrigger
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 活人感内核·卷三《场内核与渲染收编》T2-1（图纸 §7.2 · E9 / E27）：**真协调器 + 真 [AffectKernel]（MockK DAO）+ MockK 分析服务**，
 * 验 §3.4 新序端到端落库的几件硬事：
 * - 一次分析恰 1 次 `updateGrowthAnalysis` + 恰 1 次 `updateAffectField`（写库次数·总图纸 §3.9）
 * - 泄压后 `neg[tension]` **不变**（K-6：卷二那句整体 `syncedTo` 会把泄压 −2 记成负压，本卷删除）
 * - 空锚点列：规则 3 天花板仍是 70（E27）；有锚点：天花板 `anchor.openness + 20` 且弹簧把现值往锚点拉、带守卫不外移
 * - 跨档 ⇒ `hits` 落 `bandUp`；投影 / 扩散端到端进场、进维（数值全部按 §3.5 表手算）
 * - 日预算：8 维各 +10 ⇒ 饱和 6×8=48 > 40 ⇒ 等比缩到各 +5、`budgetUsed = 40`（E9）
 * - `relationshipBandOf` 与 `RelationshipAnalysisTrigger.relationshipBand` 0..100 逐值相等（分档单源锁）
 *
 * 修缮卷 T2-1 追加：E4 慢场日帽（+12 已用 10 ⇒ 落 +5 / 再来一次落 0）· E37 负向命中 ⇒ 张力不泄压 · E38 跨午夜（LLM 桩拨钟 ⇒ writeNow 在次日：
 * rollDay 只倾一次、预算记在新日）。
 *
 * 协调器时钟 = [MutableClock]（修缮卷 J11 注入·钉在 `now`），场列按「同一本地日 + updatedAt = now」构造，避免日倾与松弛混进断言（PITFALLS §1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GrowthKernelIntegrationTest {

    private val service = mockk<GrowthAnalysisService>()
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val milestoneDao = mockk<MilestoneDao>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val throttle = mockk<MaintenanceThrottleStore>(relaxed = true)
    private val kernel = AffectKernel(characterDao)
    private val now = System.currentTimeMillis()
    private val clock = MutableClock(now)
    private val coordinator = GrowthAnalysisCoordinator(
        service, characterDao, milestoneDao, CharacterWriteLock(), settingsRepo, throttle, kernel, IntentKernel(characterDao), clock,
    )
    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k", baseUrl = "https://x", modelName = "m",
    )
    private val restingField = AffectField(updatedAt = now, budgetDayStart = localDayStart(now, ZoneId.systemDefault()))

    private fun character(
        quality: RelationshipQuality = RelationshipQuality(),
        pressure: RelationshipPressure? = null,
        spectrum: PersonalitySpectrum = PersonalitySpectrum.NEUTRAL,
        anchor: PersonalitySpectrum? = null,
    ) = CharacterEntity(
        uuid = "u", name = "夏晴子", creationDate = 0L,
        personalitySpectrumJSON = GrowthJson.encode(spectrum),
        relationshipQualityJSON = GrowthJson.encode(quality),
        relationshipPressureJSON = pressure?.let { GrowthJson.encode(it) } ?: "",
        personalityAnchorJSON = anchor?.let { GrowthJson.encode(it) } ?: "",
        growthMetadataJSON = GrowthJson.encode(GrowthAnalysisMetadata(lastAnalysisDate = 5_000L)),
    )

    private fun result(
        personality: Map<String, Int> = emptyMap(),
        relationship: Map<String, GrowthAnalysisResult.PressureDelta> = emptyMap(),
        gainHits: List<String> = emptyList(),
    ) = GrowthAnalysisResult(
        personalityChanges = personality, relationshipChanges = relationship, newInterests = emptyList(),
        interestHeatChanges = emptyMap(), events = listOf(GrowthAnalysisResult.GrowthEvent(GrowthEventType.MAJOR_EVENT, "x")),
        narrative = "n", gainHits = gainHits,
    )

    private fun stub(character: CharacterEntity, result: GrowthAnalysisResult, field: AffectField = restingField) {
        coEvery { characterDao.getByUuid("u") } returns character
        coEvery { characterDao.getAffectFieldJson("u") } returns GrowthJson.encode(field)
        coEvery { service.collectAnalysisWindow("u", any()) } returns AnalysisWindow(
            leadIn = emptyList(),
            fresh = listOf(MessageEntity(messageUUID = "m1", conversationUuid = "c", roleRaw = "user", content = "hi", timestamp = 1_000L)),
        )
        coEvery {
            service.analyzeGrowth(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns result
    }

    private fun run() = runBlocking { coordinator.analyzeAndPersist("u", config, "小明", AppSettings()) }

    private data class Written(val spectrum: PersonalitySpectrum, val quality: RelationshipQuality, val pressure: RelationshipPressure, val field: AffectField)

    private fun written(): Written {
        val spec = slot<String>(); val qual = slot<String>(); val pres = slot<String>(); val fld = slot<String>()
        coVerify(exactly = 1) { characterDao.updateGrowthAnalysis("u", capture(spec), capture(qual), any(), any(), any(), capture(pres)) }
        coVerify(exactly = 1) { characterDao.updateAffectField("u", capture(fld)) }
        return Written(
            GrowthJson.decodePersonalitySpectrum(spec.captured),
            GrowthJson.decodeRelationshipQuality(qual.captured),
            GrowthJson.decodeRelationshipPressure(pres.captured),
            GrowthJson.decodeAffectFieldOrNull(fld.captured)!!,
        )
    }

    // MARK: - 写库次数

    @Test
    fun oneAnalysis_writesGrowthOnce_andFieldOnce_readsFieldColumnOnce() {
        stub(character(), result())
        run()
        coVerify(exactly = 1) { characterDao.updateGrowthAnalysis("u", any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { characterDao.updateAffectField("u", any()) }
        coVerify(exactly = 1) { characterDao.getAffectFieldJson("u") }
    }

    // MARK: - K-6：泄压不再伪造负压

    @Test
    fun tensionRelief_keepsNegUntouched_onlyPosMoves() {
        // 张力净额 40 = 正 45 / 负 5；亲近 10 关掉规则 1，LLM 零变化 ⇒ 唯一动张力的是泄压 −2。
        val pressure = RelationshipPressure(pos = listOf(10, 20, 10, 10, 35, 20, 45, 5), neg = listOf(0, 0, 0, 0, 0, 0, 5, 0))
        stub(character(quality = pressure.toQuality(), pressure = pressure), result())
        run()
        val w = written()
        assertEquals("张力 40 → 38", 38, w.quality.tension)
        assertEquals("负压一个字节不动（卷二会记成 7）", 5, w.pressure.neg[6])
        assertEquals("净额 38 由 pos 校正：5 + 38", 43, w.pressure.pos[6])
        assertEquals(w.pressure.toQuality(), w.quality)
    }

    // MARK: - E27 / 锚点天花板与弹簧

    @Test
    fun emptyAnchor_opennessCapStaysSeventy() {
        stub(character(quality = RelationshipQuality(trust = 75), spectrum = PersonalitySpectrum(openness = 69)), result())
        run()
        assertEquals(70, written().spectrum.openness)
    }

    @Test
    fun emptyAnchor_opennessAlreadyAtSeventy_doesNotMove() {
        stub(character(quality = RelationshipQuality(trust = 75), spectrum = PersonalitySpectrum(openness = 70)), result())
        run()
        assertEquals(70, written().spectrum.openness)
    }

    @Test
    fun anchoredCharacter_capIsAnchorPlusTwenty_thenSpringPullsBack() {
        // 锚点坦诚 40 ⇒ 天花板 60：规则 3 把 59 推到 60；弹簧 springStep(60, 40) = −round(3.0) = −3 ⇒ 57；
        // 带 [20, 60]，起点 59 在界内 ⇒ 57 直接落用。其余 7 维锚 == 现值 ⇒ 弹簧 0。
        stub(
            character(
                quality = RelationshipQuality(trust = 75),
                spectrum = PersonalitySpectrum(openness = 59),
                anchor = PersonalitySpectrum(openness = 40),
            ),
            result(),
        )
        run()
        val s = written().spectrum
        assertEquals(57, s.openness)
        assertEquals(50, s.warmth)
        assertEquals(50, s.extroversion)
    }

    @Test
    fun anchoredCharacter_outsideBand_onlyMovesInward_noTeleport() {
        // E28：现值 70、用户把锚点拖到 20 ⇒ 离锚 50、界外；弹簧 −round(0.15×50)=−8 ⇒ 62（往里，允许）；绝不瞬移到 40。
        stub(character(spectrum = PersonalitySpectrum(warmth = 70), anchor = PersonalitySpectrum(warmth = 20)), result())
        run()
        assertEquals(62, written().spectrum.warmth)
    }

    // MARK: - 命中落场 + 投影 / 扩散端到端

    @Test
    fun bandCrossing_andGainHit_landInField_andDiffuseIntoDims() {
        // 熟悉 10 + LLM 正 5：卷二四步 ⇒ 10 + scaledDelta(10, 5)=8 ⇒ 18，档 0 → 1 = bandUp。
        // g04 正常档：效价 +4 / 激活 +2 / 投入 +1；扩散（§3.5 B 手算）：warmth 0.8→+1、humor 0.6→+1、extroversion 0.4+0.4→+1、
        // fun 1.2+0.3→+1、closeness 0.2+0.4→+1（tension −0.8+0.3=−0.5 ⇒ 0），其余 0；预算池 = |rΔ| 8 + 扩散 5 = 13 ≤ 40 ⇒ 不缩放。
        stub(
            character(),
            result(relationship = mapOf("familiarity" to GrowthAnalysisResult.PressureDelta(5, 0)), gainHits = listOf("g04")),
        )
        run()
        val w = written()
        assertEquals(18, w.quality.familiarity)
        assertEquals(listOf("g04", AffectField.BAND_UP), w.field.hits)
        assertTrue(w.field.hitsAt > 0L)
        assertEquals(4, w.field.valence)
        assertEquals(32, w.field.arousal)
        assertEquals(31, w.field.investment)
        assertEquals(50, w.field.security)
        assertEquals(13, w.field.budgetUsed)
        assertEquals(11, w.quality.closeness)
        assertEquals(51, w.spectrum.warmth)
        assertEquals(51, w.spectrum.humor)
        assertEquals(51, w.spectrum.extroversion)
        assertEquals(21, w.quality.funValue)
        assertEquals("扩散进关系维只动 pos：neg 仍 0", 0, w.pressure.neg[5])
    }

    // MARK: - E9 日预算

    @Test
    fun budget_scalesSixteenDimShift_toForty() {
        val allTen = PersonalitySpectrum.DIMENSION_KEYS.associateWith { 10 }
        stub(character(), result(personality = allTen))
        run()
        val w = written()
        // saturate(10) = 6 × 8 维 = 48 > 40 ⇒ factor 40/48 ⇒ 6 × 0.8333 = 5.0 向零截断 5 ⇒ 各 55；用量 40
        for (v in w.spectrum.values) assertEquals(55, v)
        assertEquals(40, w.field.budgetUsed)
    }

    @Test
    fun budget_alreadyExhausted_zeroesSixteenDims_butFieldStillProjects() {
        stub(
            character(),
            result(personality = mapOf("warmth" to 10), gainHits = listOf("g10")),
            field = restingField.copy(budgetUsed = 40),
        )
        run()
        val w = written()
        assertEquals("剩余 0 ⇒ 性格位移全 0", 50, w.spectrum.warmth)
        assertEquals("场照投影（g10 效价 +5）", 5, w.field.valence)
        assertEquals(40, w.field.budgetUsed)
    }

    // MARK: - 修缮卷 E4：慢场日帽（每场每日 |Δ| ≤ 15）

    @Test
    fun slowFieldDayCap_clipsSecurityShift_toRemainingCap() {
        // g20 (+0.6→6→5) + g07 (+0.5→5→4) + g21 (+0.4→4→3) + g09 (+0.4→4→3) 安全感投影 15 ⇒ 单次钳 12；当日已用 10 ⇒ 帽剩 5 ⇒ 落 +5。
        stub(character(), result(gainHits = listOf("g20", "g07", "g21", "g09")), field = restingField.copy(slowDayUsed = listOf(10, 0)))
        run()
        val w = written()
        assertEquals(55, w.field.security)
        assertEquals(15, w.field.slowDayUsed[0])
        assertEquals("投影后的参考时刻 = 本次落库时刻", now, w.field.slowRefAt)
    }

    @Test
    fun slowFieldDayCap_exhausted_landsZero_butFastFieldsStillMove() {
        // 当日已用 15 ⇒ 帽剩 0 ⇒ 安全感 +9 落 0；效价 / 激活无帽照落。
        stub(character(), result(gainHits = listOf("g20", "g07")), field = restingField.copy(slowDayUsed = listOf(15, 0)))
        run()
        val w = written()
        assertEquals(50, w.field.security)
        assertEquals(15, w.field.slowDayUsed[0])
        assertTrue("效价照投影（g20 +3 / g07 +4 ⇒ 3+3=6…只钉方向）", w.field.valence > 0)
    }

    // MARK: - 修缮卷 E37：负向命中 ⇒ 张力不恒定泄压

    @Test
    fun negativeHit_skipsTensionRelief() {
        // 与 tensionRelief_keepsNegUntouched_onlyPosMoves 同起点（张力 40 = 正 45 / 负 5），本次命中 g13：
        // 投影 (−3, 0, −5, +5) ⇒ 扩散进张力 = −0.25×(−3) + −0.2×(−5) + 0.15×5 = 2.5 ⇒ saturate 2 ⇒ 42；**不再** −2。
        val pressure = RelationshipPressure(pos = listOf(10, 20, 10, 10, 35, 20, 45, 5), neg = listOf(0, 0, 0, 0, 0, 0, 5, 0))
        stub(character(quality = pressure.toQuality(), pressure = pressure), result(gainHits = listOf("g13")))
        run()
        val w = written()
        assertEquals(42, w.quality.tension)
        assertEquals("负压仍一个字节不动", 5, w.pressure.neg[6])
        assertEquals(47, w.field.security)
    }

    @Test
    fun negativeCustomHit_alsoSkipsTensionRelief() {
        val pressure = RelationshipPressure(pos = listOf(10, 20, 10, 10, 35, 20, 45, 5), neg = listOf(0, 0, 0, 0, 0, 0, 5, 0))
        stub(
            character(quality = pressure.toQuality(), pressure = pressure),
            result().copy(customHits = listOf(GrowthAnalysisResult.CustomHit(label = "怕黑", positive = false))),
        )
        run()
        // custom·neg 投影 (−2, 0, −4, +2) 逐场饱和 ⇒ (−2, 0, −3, +2) ⇒ 张力扩散 0.5 + 0.6 + 0.3 = 1.4 ⇒ saturate 1 ⇒ 41；
        // 泄压若仍发生会是 39——41 即证「专属负向命中同样跳过泄压」。
        assertEquals(41, written().quality.tension)
    }

    // MARK: - 修缮卷 E38：跨午夜分析——writeNow 的 rollDay 只倾一次、预算记在新日

    @Test
    fun analysisAcrossMidnight_rollsDayOnce_andBooksBudgetOnNewDay() {
        val zone = ZoneId.systemDefault()
        val before = LocalDateTime.of(2026, 9, 2, 23, 59).atZone(zone).toInstant().toEpochMilli()
        clock.set(before)
        val field = AffectField(updatedAt = before, budgetDayStart = localDayStart(before, zone), budgetUsed = 30)
        stub(character(), result(personality = mapOf("warmth" to 10)), field = field)
        // LLM 桩里拨钟两分钟 ⇒ 协调器 now = 23:59、writeNow = 00:01
        coEvery {
            service.analyzeGrowth(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { clock.advance(2 * 60_000L); result(personality = mapOf("warmth" to 10)) }
        run()
        val w = written()
        val tilt = dailyTilt("u", LocalDate.of(2026, 9, 3))
        assertEquals("预算日起点 = 次日零点", localDayStart(before + 2 * 60_000L, zone), w.field.budgetDayStart)
        assertEquals("预算记在新日：30 归零后只记本次 saturate(10)=6", 6, w.field.budgetUsed)
        assertEquals("日倾只倾一次（效价 = 0 + 次日倾 + 0 投影）", tilt.valence, w.field.valence)
        assertEquals("updatedAt / hitsAt = writeNow", before + 2 * 60_000L, w.field.updatedAt)
        assertEquals(before + 2 * 60_000L, w.field.hitsAt)
        assertEquals(56, w.spectrum.warmth)
    }

    // MARK: - 分档单源锁

    @Test
    fun relationshipBandOf_matchesTriggerBand_forAllValues() {
        val boundaries = intArrayOf(10, 20, 30, 50, 70, 85, 95, 100)
        for (v in 0..100) {
            assertEquals("v=$v", RelationshipAnalysisTrigger.relationshipBand(v, boundaries), relationshipBandOf(v))
        }
    }
}
