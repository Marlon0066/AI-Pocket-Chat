package com.situ.aichat.prompt.persona

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.CustomGain
import com.situ.aichat.data.model.GrowthAnalysisMetadata
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.PersonaCompileMeta
import com.situ.aichat.data.model.PersonaGains
import com.situ.aichat.data.model.PersonaOperator
import com.situ.aichat.data.model.PersonaVocab
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 活人感内核·卷一《人设编译器》T2-1–T2-3（图纸 §7.2）：落库协调器的写序与 **Y-3 判据**。
 *
 * 断言从图纸 §3.5（落库顺序）与 §5（Y-E1 / Y-E2 / Y-E11 / Y-E12 / Y-E26）**独立反推**：
 * - 人设为空 / 角色不存在 / 无配置 ⇒ **零 LLM 调用、零写库**
 * - 编译失败 ⇒ `updatePersonaCompile` 里另三列**原值回传**、只有 meta 的 `lastFailedAt` 变；
 *   `updateGrowthDimensions` **零调用**（数值一个字节不动）
 * - `totalAnalysisCount > 0` ⇒ 现值列**零调用**；`== 0` ⇒ **恰一次**，且关系质感入参 = 锁内 fresh 读到的值
 * - 「重新生成」覆盖手改值，`source` 回 `compiled`
 *
 * 时钟注入 `Clock.fixed`，`compiledAt` / `lastFailedAt` 才不随「测试几点跑」漂移（PITFALLS §1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonaCompileCoordinatorTest {

    private val service = mockk<PersonaCompileService>()
    private val dao = mockk<CharacterDao>(relaxed = true)
    private val apiConfigRepo = mockk<ApiConfigRepository>()
    private val now = 1_700_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k",
        baseUrl = "https://example.invalid/v1", modelName = "m",
    )

    private fun coordinator(lock: CharacterWriteLock = CharacterWriteLock()) = PersonaCompileCoordinator(
        context = RuntimeEnvironment.getApplication(),
        service = service,
        characterDao = dao,
        characterWriteLock = lock,
        apiConfigRepo = apiConfigRepo,
        clock = clock,
    )

    /** 「相处过一阵」的角色：现值已被成长分析改过、关系质感也涨过。 */
    private fun character(
        analysisCount: Int,
        persona: String = "高冷毒舌、嘴硬心软、怕黑",
        anchorJson: String = "",
        metaJson: String = "",
        gainsJson: String = "",
        operatorsJson: String = "",
        pressureJson: String = "",
    ) = CharacterEntity(
        uuid = UUID,
        name = "林晚",
        creationDate = 0L,
        personalityDescription = persona,
        personalitySpectrumJSON = GrowthJson.encode(PersonalitySpectrum(extroversion = 44, warmth = 41)),
        relationshipQualityJSON = GrowthJson.encode(RelationshipQuality(familiarity = 62, trust = 58)),
        relationshipPressureJSON = pressureJson,
        growthMetadataJSON = GrowthJson.encode(GrowthAnalysisMetadata(totalAnalysisCount = analysisCount)),
        personalityAnchorJSON = anchorJson,
        personaCompileMetaJSON = metaJson,
        personaGainsJSON = gainsJson,
        personaOperatorsJSON = operatorsJson,
    )

    private fun compiled() = PersonaCompileResult(
        anchors = mapOf("extroversion" to 30, "warmth" to 25, "humor" to 70),
        basis = mapOf("warmth" to "表面高冷"),
        gains = PersonaGains(
            system = mapOf("g02" to 2, "g25" to 2),
            custom = listOf(CustomGain(id = "u1", label = "被叫全名", level = 2)),
        ),
        operators = listOf(PersonaOperator(id = "o1", condition = "c01", action = "a01")),
        droppedCount = 3,
        notes = "外冷内热",
    )

    private fun stubHappyPath() {
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.PERSONA_COMPILE) } returns config
        coEvery { service.compile(any(), any(), any()) } returns compiled()
        coEvery { service.personaHash(any()) } returns HASH
    }

    // MARK: - T2-1 不该跑的时候一次也别跑

    @Test
    fun blankPersona_makesZeroLlmCallsAndZeroWrites() = runBlocking {
        coEvery { dao.getByUuid(UUID) } returns character(analysisCount = 0, persona = "   ")

        val outcome = coordinator().compileAndPersist(UUID)

        assertTrue(outcome is PersonaCompileOutcome.Failed)
        coVerify(exactly = 0) { service.compile(any(), any(), any()) }
        coVerify(exactly = 0) { dao.updatePersonaCompile(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.updateGrowthDimensions(any(), any(), any(), any()) }
    }

    @Test
    fun missingCharacter_makesZeroLlmCallsAndZeroWrites() = runBlocking {
        coEvery { dao.getByUuid(UUID) } returns null

        assertTrue(coordinator().compileAndPersist(UUID) is PersonaCompileOutcome.Failed)
        coVerify(exactly = 0) { service.compile(any(), any(), any()) }
        coVerify(exactly = 0) { dao.updatePersonaCompile(any(), any(), any(), any(), any()) }
    }

    @Test
    fun noApiConfig_failsGracefullyWithoutWriting() = runBlocking {
        coEvery { dao.getByUuid(UUID) } returns character(analysisCount = 0)
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.PERSONA_COMPILE) } returns null

        assertTrue(coordinator().compileAndPersist(UUID) is PersonaCompileOutcome.Failed)
        coVerify(exactly = 0) { service.compile(any(), any(), any()) }
        coVerify(exactly = 0) { dao.updatePersonaCompile(any(), any(), any(), any(), any()) }
    }

    // MARK: - T2-2 失败路径（D-5：数值一个字节不动）

    @Test
    fun compileFailure_keepsOtherThreeColumnsVerbatim_andOnlyStampsLastFailedAt() = runBlocking {
        val priorMeta = PersonaCompileMeta(
            source = PersonaCompileMeta.SOURCE_COMPILED,
            compiledAt = 111L,
            personaHash = "0123456789abcdef",
            lastFailedAt = 0L,
            droppedCount = 1,
        )
        val existing = character(
            analysisCount = 0,
            anchorJson = """{"warmth":25}""",
            metaJson = GrowthJson.encode(priorMeta),
            gainsJson = """{"system":{"g02":2}}""",
            operatorsJson = """[{"id":"o1","condition":"c01","action":"a01","enabled":true}]""",
        )
        coEvery { dao.getByUuid(UUID) } returns existing
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.PERSONA_COMPILE) } returns config
        coEvery { service.compile(any(), any(), any()) } throws
            PersonaCompileError.InvalidResponse("anchors / gains / operators 三者全空")

        val outcome = coordinator().compileAndPersist(UUID)
        assertTrue(outcome is PersonaCompileOutcome.Failed)

        val anchor = slot<String>(); val meta = slot<String>()
        val gains = slot<String>(); val operators = slot<String>()
        coVerify(exactly = 1) {
            dao.updatePersonaCompile(UUID, capture(anchor), capture(meta), capture(gains), capture(operators))
        }
        assertEquals("锚点列原值回传", existing.personalityAnchorJSON, anchor.captured)
        assertEquals("增益列原值回传", existing.personaGainsJSON, gains.captured)
        assertEquals("算子列原值回传", existing.personaOperatorsJSON, operators.captured)

        val written = GrowthJson.decodePersonaCompileMeta(meta.captured)
        assertEquals("只盖失败戳", now, written.lastFailedAt)
        assertEquals("来源不变", priorMeta.source, written.source)
        assertEquals("人设 hash 不变（没编译成功就没有新指纹）", priorMeta.personaHash, written.personaHash)
        assertEquals("上次成功时间不变", priorMeta.compiledAt, written.compiledAt)
        assertEquals("上次的丢弃数不被清", priorMeta.droppedCount, written.droppedCount)

        coVerify(exactly = 0) { dao.updateGrowthDimensions(any(), any(), any(), any()) }
    }

    // MARK: - T2-3 Y-3 判据

    @Test
    fun neverAnalyzed_syncsCurrentSpectrum_withFreshRelationshipQuality() = runBlocking {
        val existing = character(analysisCount = 0)
        coEvery { dao.getByUuid(UUID) } returns existing
        stubHappyPath()

        val outcome = coordinator().compileAndPersist(UUID)

        assertTrue(outcome is PersonaCompileOutcome.Success)
        assertTrue((outcome as PersonaCompileOutcome.Success).syncedCurrentSpectrum)

        val spectrum = slot<String>(); val quality = slot<String>()
        coVerify(exactly = 1) { dao.updateGrowthDimensions(UUID, capture(spectrum), capture(quality), any()) }
        // 现值 = 编译出的锚点。
        val written = GrowthJson.decodePersonalitySpectrum(spectrum.captured)
        assertEquals(30, written.extroversion)
        assertEquals(25, written.warmth)
        assertEquals(70, written.humor)
        assertEquals("LLM 没给的维度保持 50，不强填", 50, written.curiosity)
        // ⭐ 该 UPDATE 同时写关系质感列：入参必须是锁内 fresh 读到的当前值，传旧快照会把关系分覆盖掉。
        assertEquals(existing.relationshipQualityJSON, quality.captured)
        assertEquals(62, GrowthJson.decodeRelationshipQuality(quality.captured).familiarity)
    }

    /**
     * 卷二《正负双压》T2-3（图纸 §7.2 · P-E17）：⑨ 的关系两列**双双原样透传**。
     *
     * 这条写口只为同步性格现值，它碰关系两列纯粹因为该 UPDATE 一条管三列——语义上一个字节都不该动。
     * 压强列若在这里被「顺手播种/重算」，用户只是编译了一次人设，相处攒下的正负两股力就没了。
     */
    @Test
    fun neverAnalyzed_syncsCurrentSpectrum_passesRelationshipPressureThroughVerbatim() = runBlocking {
        val stored = """{"pos":[70,80,10,10,35,20,5,5],"neg":[8,75,0,0,0,0,0,0]}"""
        val existing = character(analysisCount = 0, pressureJson = stored)
        coEvery { dao.getByUuid(UUID) } returns existing
        stubHappyPath()

        coordinator().compileAndPersist(UUID)

        val quality = slot<String>(); val pressure = slot<String>()
        coVerify(exactly = 1) { dao.updateGrowthDimensions(UUID, any(), capture(quality), capture(pressure)) }
        assertEquals("压强列逐字节透传", stored, pressure.captured)
        assertEquals("净额列同样一个字节不动", existing.relationshipQualityJSON, quality.captured)
    }

    @Test
    fun neverAnalyzed_emptyPressureColumn_staysEmpty_noSeedingWrite() = runBlocking {
        // 老角色空列：⑨ 不是播种点，透传 "" 即可（真正的播种在各自有语义的写者那里·P-8）。
        val existing = character(analysisCount = 0)
        coEvery { dao.getByUuid(UUID) } returns existing
        stubHappyPath()

        coordinator().compileAndPersist(UUID)

        val pressure = slot<String>()
        coVerify(exactly = 1) { dao.updateGrowthDimensions(UUID, any(), any(), capture(pressure)) }
        assertEquals("", pressure.captured)
    }

    @Test
    fun alreadyAnalyzed_neverTouchesCurrentSpectrum() = runBlocking {
        coEvery { dao.getByUuid(UUID) } returns character(analysisCount = 7)
        stubHappyPath()

        val outcome = coordinator().compileAndPersist(UUID)

        assertTrue(outcome is PersonaCompileOutcome.Success)
        assertTrue("相处过的角色不同步现值", !(outcome as PersonaCompileOutcome.Success).syncedCurrentSpectrum)
        // 本性照写，现值一个字节不动——那是用户攒下来的关系史。
        coVerify(exactly = 1) { dao.updatePersonaCompile(UUID, any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.updateGrowthDimensions(any(), any(), any(), any()) }
    }

    @Test
    fun regenerate_overwritesAnchor_butKeepsUserDecisions_andResetsSourceToCompiled() = runBlocking {
        // 修缮卷 J7：用户拖过滑杆（锚点 90）⇒ 重新生成仍以编译为准（锚点 30、source 回 compiled）；
        // 但用户决定——手调档位 manualSystem、删算子墓碑 suppressedOperators——必须原样保留。
        val manual = character(
            analysisCount = 7,
            anchorJson = GrowthJson.encode(PersonalitySpectrum(extroversion = 90)),
            metaJson = GrowthJson.encode(
                PersonaCompileMeta(source = PersonaCompileMeta.SOURCE_MANUAL, compiledAt = 111L, personaHash = "old", suppressedOperators = listOf("c09|a02")),
            ),
            gainsJson = GrowthJson.encode(PersonaGains(system = mapOf("g02" to 0), manualSystem = listOf("g02"))),
        )
        coEvery { dao.getByUuid(UUID) } returns manual
        stubHappyPath()

        coordinator().compileAndPersist(UUID)

        val anchor = slot<String>(); val meta = slot<String>(); val gains = slot<String>()
        coVerify(exactly = 1) { dao.updatePersonaCompile(UUID, capture(anchor), capture(meta), capture(gains), any()) }
        assertEquals("手改的 90 被编译结果覆盖", 30, GrowthJson.decodePersonalitySpectrum(anchor.captured).extroversion)

        val written = GrowthJson.decodePersonaCompileMeta(meta.captured)
        assertEquals(PersonaCompileMeta.SOURCE_COMPILED, written.source)
        assertEquals(now, written.compiledAt)
        assertEquals(HASH, written.personaHash)
        assertEquals("成功即清失败戳", 0L, written.lastFailedAt)
        assertEquals("本次丢弃数照实记", 3, written.droppedCount)
        assertEquals("墓碑保留", listOf("c09|a02"), written.suppressedOperators)
        val g = GrowthJson.decodePersonaGains(gains.captured)
        assertEquals("手调的 g02=0 压住编译的 2；g25 照编译", mapOf("g02" to 0, "g25" to 2), g.system)
        assertEquals(listOf("g02"), g.manualSystem)
    }

    // MARK: - 修缮卷 T2-5 / T2-6（E27 / E29 / B🔵-5）

    @Test
    fun preserveAnchor_keepsAnchorAndCurrent_writesGainsAndOperators_sourceManual_E27() = runBlocking {
        val dragged = GrowthJson.encode(PersonalitySpectrum(extroversion = 15))
        coEvery { dao.getByUuid(UUID) } returns character(analysisCount = 0, anchorJson = dragged).copy(personalitySpectrumJSON = dragged)
        stubHappyPath()

        val outcome = coordinator().compileAndPersist(UUID, preserveAnchor = true)

        val anchor = slot<String>(); val meta = slot<String>(); val gains = slot<String>(); val operators = slot<String>()
        coVerify(exactly = 1) { dao.updatePersonaCompile(UUID, capture(anchor), capture(meta), capture(gains), capture(operators)) }
        assertEquals("锚点列原串", dragged, anchor.captured)
        assertEquals(PersonaCompileMeta.SOURCE_MANUAL, GrowthJson.decodePersonaCompileMeta(meta.captured).source)
        assertEquals(mapOf("g02" to 2, "g25" to 2), GrowthJson.decodePersonaGains(gains.captured).system)
        assertEquals(listOf("c01"), GrowthJson.decodePersonaOperators(operators.captured).map { it.condition })
        coVerify(exactly = 0) { dao.updateGrowthDimensions(any(), any(), any(), any()) }
        assertTrue(outcome is PersonaCompileOutcome.Success)
        assertEquals(15, (outcome as PersonaCompileOutcome.Success).anchor.extroversion)
        assertEquals(false, outcome.syncedCurrentSpectrum)
    }

    @Test
    fun regenerate_keepsDisabledOperator_neverRevivesDeleted_keepsManualLevel_E29() = runBlocking {
        val existing = character(
            analysisCount = 3,
            metaJson = GrowthJson.encode(PersonaCompileMeta(source = PersonaCompileMeta.SOURCE_COMPILED, suppressedOperators = listOf("c09|a02"))),
            gainsJson = GrowthJson.encode(PersonaGains(system = mapOf("g05" to 0, "g02" to 2), manualSystem = listOf("g05"))),
            operatorsJson = GrowthJson.encodePersonaOperators(
                listOf(PersonaOperator(id = "o4", condition = "c04", action = "a04", enabled = false)),
            ),
        )
        coEvery { dao.getByUuid(UUID) } returns existing
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.PERSONA_COMPILE) } returns config
        coEvery { service.personaHash(any()) } returns HASH
        coEvery { service.compile(any(), any(), any()) } returns PersonaCompileResult(
            anchors = mapOf("warmth" to 25), basis = emptyMap(),
            gains = PersonaGains(system = mapOf("g05" to 2, "g02" to 2, "g25" to 2)),
            operators = listOf(
                PersonaOperator(id = "n1", condition = "c01", action = "a01"),
                PersonaOperator(id = "n4", condition = "c04", action = "a04"),
                PersonaOperator(id = "n9", condition = "c09", action = "a02"),
            ),
            droppedCount = 0, notes = "",
        )

        coordinator().compileAndPersist(UUID)

        val gains = slot<String>(); val operators = slot<String>()
        coVerify(exactly = 1) { dao.updatePersonaCompile(UUID, any(), any(), capture(gains), capture(operators)) }
        val ops = GrowthJson.decodePersonaOperators(operators.captured)
        assertEquals("c09|a02 不复活；顺序 = 编译序", listOf("c01|a01", "c04|a04"), ops.map { "${it.condition}|${it.action}" })
        assertEquals("c04|a04 沿用既有 id 与 enabled=false", "o4", ops[1].id)
        assertEquals(false, ops[1].enabled)
        assertEquals("n1", ops[0].id)
        assertEquals(mapOf("g05" to 0, "g02" to 2, "g25" to 2), GrowthJson.decodePersonaGains(gains.captured).system)
    }

    @Test
    fun mergeOperators_and_mergeGains_pureFunctions() {
        val existing = listOf(PersonaOperator("o4", "c04", "a04", enabled = false), PersonaOperator("o7", "c07", "a05"))
        val compiled = listOf(PersonaOperator("n4", "c04", "a04"), PersonaOperator("n9", "c09", "a02"), PersonaOperator("n7", "c07", "a05"))
        val merged = mergeOperators(existing, compiled, suppressed = setOf("c09|a02"))
        assertEquals(listOf(existing[0], existing[1]), merged)

        val g = mergeGains(
            existing = PersonaGains(system = mapOf("g05" to 1, "g02" to 0), manualSystem = listOf("g05", "g02")),
            compiled = PersonaGains(system = mapOf("g05" to 2, "g06" to 2)),
        )
        assertEquals("手调成 1 的显式项也压住编译的 2", mapOf("g05" to 1, "g06" to 2, "g02" to 0), g.system)
        assertEquals(listOf("g05", "g02"), g.manualSystem)
    }

    @Test
    fun llmCall_happensOutsideTheCharacterLock_B5() = runBlocking {
        coEvery { dao.getByUuid(UUID) } returns character(analysisCount = 0)
        stubHappyPath()
        val lock = spyk(CharacterWriteLock())

        coordinator(lock).compileAndPersist(UUID)

        coVerify(exactly = 1) { lock.withCharacterLock<PersonaCompileOutcome>(UUID, any()) }
        coVerifyOrder {
            service.compile(any(), any(), any())
            lock.withCharacterLock<PersonaCompileOutcome>(UUID, any())
        }
        coVerify(exactly = 2) { dao.getByUuid(UUID) }   // 锁外快照 + 锁内 fresh
    }

    @Test
    fun success_writesAllFourColumnsInOneUpdate() = runBlocking {
        coEvery { dao.getByUuid(UUID) } returns character(analysisCount = 0)
        stubHappyPath()

        coordinator().compileAndPersist(UUID)

        val gains = slot<String>(); val operators = slot<String>()
        coVerify(exactly = 1) { dao.updatePersonaCompile(UUID, any(), any(), capture(gains), capture(operators)) }
        val decodedGains = GrowthJson.decodePersonaGains(gains.captured)
        assertEquals(mapOf("g02" to 2, "g25" to 2), decodedGains.system)
        assertEquals(listOf("被叫全名"), decodedGains.custom.map { it.label })
        assertEquals(listOf("c01"), GrowthJson.decodePersonaOperators(operators.captured).map { it.condition })
    }

    // MARK: - 图纸 2026-09-03 T2-3：编译成功日志**分敏感/无感打点**（§4.4 · E18）

    /**
     * 混在一个数里（旧口径「增益 N 项」）看不出模型是不是只挑敏感的写；分档打点是把「不吃这套」命中率
     * 从估算变实测的唯一途径——引导句（§4.3）的效果全靠它验收，所以这行格式是**承诺不是建议**。
     */
    @Test
    fun successLog_countsSensitiveAndNumbSeparately_T2_3() = runBlocking {
        ShadowLog.clear()
        coEvery { dao.getByUuid(UUID) } returns character(analysisCount = 0)
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.PERSONA_COMPILE) } returns config
        coEvery { service.personaHash(any()) } returns HASH
        coEvery { service.compile(any(), any(), any()) } returns compiled().copy(
            // 很敏感 2（g02 / g25）· 不吃这套 2（g04 / g16）· 正常档 1（g10·两个数都不该算它）· 专属 1
            gains = PersonaGains(
                system = mapOf("g02" to 2, "g25" to 2, "g04" to 0, "g16" to 0, "g10" to 1),
                custom = listOf(CustomGain(id = "u1", label = "被叫全名", level = 2)),
            ),
        )

        coordinator().compileAndPersist(UUID)

        val line = ShadowLog.getLogsForTag("PersonaCompile").map { it.msg }.single { it.startsWith("✓ ") }
        assertEquals(
            "✓ 林晚: 锚点 3 维 / 增益 很敏感2 不吃这套2 专属1 / 算子 1 条 / 丢弃 3 条",
            line,
        )
    }

    /** E18：`gains.system` 为空 map ⇒ 打点为「很敏感0 不吃这套0 专属N」，不抛异常。 */
    @Test
    fun successLog_emptySystemMap_printsZeros_E18() = runBlocking {
        ShadowLog.clear()
        coEvery { dao.getByUuid(UUID) } returns character(analysisCount = 0)
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.PERSONA_COMPILE) } returns config
        coEvery { service.personaHash(any()) } returns HASH
        coEvery { service.compile(any(), any(), any()) } returns compiled().copy(
            gains = PersonaGains(custom = listOf(CustomGain(id = "u1", label = "被叫全名", level = 2))),
        )

        coordinator().compileAndPersist(UUID)

        val line = ShadowLog.getLogsForTag("PersonaCompile").map { it.msg }.single { it.startsWith("✓ ") }
        assertTrue(line, line.contains("增益 很敏感0 不吃这套0 专属1 /"))
    }

    @Test
    fun compileReceivesPersonaFieldsAndSystemLabels_neverConversation() = runBlocking {
        coEvery { dao.getByUuid(UUID) } returns character(analysisCount = 0)
        stubHappyPath()

        val input = slot<PersonaCompileInput>(); val labels = slot<Set<String>>()
        coordinator().compileAndPersist(UUID)
        coVerify(exactly = 1) { service.compile(capture(input), config, capture(labels)) }

        assertEquals("林晚", input.captured.name)
        assertEquals("高冷毒舌、嘴硬心软、怕黑", input.captured.personalityDescription)
        // 27 项标签全部传下去供 custom 查重，且取的是**当前语言**的资源值（Robolectric 默认 en，
        // 真机 zh 即中文标签）——这里按资源反查，不写死语言，否则测试只是在钉运行环境的 locale。
        val app = RuntimeEnvironment.getApplication()
        assertEquals("27 项系统标签全部传下去供查重", 27, labels.captured.size)
        assertEquals(
            PersonaVocab.GAINS.values.mapTo(mutableSetOf()) { app.getString(it) },
            labels.captured,
        )
        assertTrue("g04「被夸奖肯定」在内", labels.captured.contains(app.getString(PersonaVocab.GAINS.getValue("g04"))))
    }

    // MARK: - R1 复核 🔴-1：重新生成不得吃掉用户手写的专属项

    private fun gain(label: String, origin: String, level: Int = 2) =
        CustomGain(id = "id-$label", label = label, level = level, origin = origin)

    /** 手写项**全部保留且排在前**；编译项按顺序补位。 */
    @Test
    fun mergeManualCustoms_keepsManualFirstAndAppendsCompiled() {
        val existing = PersonaGains(
            custom = listOf(
                gain("编译出来的旧项", CustomGain.ORIGIN_COMPILED),
                gain("被叫全名", CustomGain.ORIGIN_MANUAL),
            ),
        )
        val compiled = PersonaGains(system = mapOf("g04" to 2), custom = listOf(gain("被提起姐姐", CustomGain.ORIGIN_COMPILED)))

        val merged = mergeManualCustoms(existing, compiled)

        assertEquals(listOf("被叫全名", "被提起姐姐"), merged.custom.map { it.label })
        assertEquals("手写项必须排在最前", CustomGain.ORIGIN_MANUAL, merged.custom.first().origin)
        assertEquals("system 档位是编译产物，照旧整体替换", mapOf("g04" to 2), merged.system)
    }

    /** 无手写项时原样返回编译结果（不引入任何多余拷贝语义）。 */
    @Test
    fun mergeManualCustoms_noManual_returnsCompiledUnchanged() {
        val existing = PersonaGains(custom = listOf(gain("旧编译项", CustomGain.ORIGIN_COMPILED)))
        val compiled = PersonaGains(custom = listOf(gain("新编译项", CustomGain.ORIGIN_COMPILED)))
        assertEquals(compiled, mergeManualCustoms(existing, compiled))
    }

    /** 编译项与已保留的手写项重名 ⇒ 丢弃编译那份（比较口径 = 去空白 + 全小写，与解析端一致）。 */
    @Test
    fun mergeManualCustoms_dropsCompiledDuplicateOfManualLabel() {
        val existing = PersonaGains(custom = listOf(gain("被叫全名", CustomGain.ORIGIN_MANUAL)))
        val compiled = PersonaGains(custom = listOf(gain("  被叫全名 ", CustomGain.ORIGIN_COMPILED), gain("怕黑", CustomGain.ORIGIN_COMPILED)))
        assertEquals(listOf("被叫全名", "怕黑"), mergeManualCustoms(existing, compiled).custom.map { it.label })
    }

    /** 上限 10 恒不越：手写占满即编译项一条都进不来。 */
    @Test
    fun mergeManualCustoms_neverExceedsCap_manualWins() {
        val existing = PersonaGains(custom = (1..PersonaGains.MAX_CUSTOM).map { gain("手写$it", CustomGain.ORIGIN_MANUAL) })
        val compiled = PersonaGains(custom = listOf(gain("编译项", CustomGain.ORIGIN_COMPILED)))

        val merged = mergeManualCustoms(existing, compiled)

        assertEquals(PersonaGains.MAX_CUSTOM, merged.custom.size)
        assertTrue("满额时编译项一条都不该挤进来", merged.custom.all { it.origin == CustomGain.ORIGIN_MANUAL })
    }

    private companion object {
        const val UUID = "char-1"
        const val HASH = "a1b2c3d4e5f60718"
    }
}
