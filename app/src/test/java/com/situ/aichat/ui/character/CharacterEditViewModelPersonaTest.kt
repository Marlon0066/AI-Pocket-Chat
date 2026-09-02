package com.situ.aichat.ui.character

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.GrowthAnalysisMetadata
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.PersonaCompileMeta
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.toQuality
import com.situ.aichat.data.model.PersonaGains
import com.situ.aichat.data.model.PersonaOperator
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.prompt.persona.PersonaCompileCoordinator
import com.situ.aichat.prompt.persona.PersonaCompileOutcome
import com.situ.aichat.work.NotificationTemplateWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 活人感内核·卷一《人设编译器》T2-5 + T2-1 的重入半条（图纸 §7.2 · Y-E20 / Y-E21 / Y-E23）。
 *
 * 断言从图纸 §3.6（拖锚点的落库）与 §5 独立反推：
 * - 新建角色**首次保存**自动编译恰一次；人设为空则零次（Y-E21 / Y-E23）
 * - `isCompiling` 拦重入：连点两次只跑一次（Y-E20 / I-4——**这半条住在 VM**，故落此文件而非协调器测试）
 * - 拖「本性」滑杆保存 ⇒ 四列写口写锚点 + `source` 转 manual；Y-3 判据决定现值跟不跟
 * - D-2 判据：人设文本改过而 hash 对不上 ⇒ `personaStale` 为真
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacterEditViewModelPersonaTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private lateinit var characterRepo: CharacterRepository

    /** 协调器假掉（锁 / LLM / 落库四列已由 PersonaCompileCoordinatorTest 钉），但 use case 用**真实例**
     *  ——保存分支的四列写口与 Y-3 判据正是它在编排，假掉就测不到了。 */
    private lateinit var coordinator: PersonaCompileCoordinator
    private lateinit var compiler: PersonaCompileUseCase

    @Before
    fun setUp() {
        mockkObject(NotificationTemplateWorker.Companion)
        every { NotificationTemplateWorker.enqueueForCharacter(any(), any()) } returns Unit
        characterRepo = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)
        compiler = PersonaCompileUseCase(coordinator = coordinator, characterRepo = characterRepo)
    }

    @After
    fun tearDown() = unmockkObject(NotificationTemplateWorker.Companion)

    private fun vm(editingUuid: String? = null) = CharacterEditViewModel(
        characterRepo = characterRepo,
        conversationRepo = mockk(relaxed = true),
        settingsRepo = mockk(relaxed = true),
        currencyService = mockk(relaxed = true),
        economyMaintenance = mockk(relaxed = true),
        ttsConfigRepo = mockk(relaxed = true),
        previewer = mockk(relaxed = true),
        membershipService = mockk(relaxed = true),
        personaCompiler = compiler,
        characterWriteLock = CharacterWriteLock(),
        appContext = RuntimeEnvironment.getApplication(),
        savedStateHandle = if (editingUuid == null) SavedStateHandle() else SavedStateHandle(mapOf("characterUuid" to editingUuid)),
    )

    private fun character(
        analysisCount: Int = 0,
        anchorJson: String = "",
        metaJson: String = "",
        persona: String = "高冷毒舌、嘴硬心软、怕黑",
        pressureJson: String = "",
    ) = CharacterEntity(
        uuid = UUID,
        name = "林晚",
        creationDate = 0L,
        personalityDescription = persona,
        personalitySpectrumJSON = GrowthJson.encode(PersonalitySpectrum(extroversion = 44, warmth = 41)),
        relationshipQualityJSON = GrowthJson.encode(RelationshipQuality(familiarity = 62)),
        relationshipPressureJSON = pressureJson,
        growthMetadataJSON = GrowthJson.encode(GrowthAnalysisMetadata(totalAnalysisCount = analysisCount)),
        personalityAnchorJSON = anchorJson,
        personaCompileMetaJSON = metaJson,
        personaGainsJSON = """{"system":{"g02":2}}""",
        personaOperatorsJSON = """[{"id":"o1","condition":"c01","action":"a01","enabled":true}]""",
    )

    // MARK: - Y-E21 / Y-E23 新建首存自动编译

    @Test
    fun createWithPersona_triggersAutoCompileExactlyOnce() {
        val vm = vm()
        vm.update { it.copy(name = "林晚", personalityDescription = "高冷毒舌") }
        vm.save {}
        idle()

        val uuid = slot<String>()
        coVerify(exactly = 1) { coordinator.compileForNewCharacter(capture(uuid), false) }
        assertTrue("编译的是刚插进去的那个角色", uuid.captured.isNotEmpty())
    }

    @Test
    fun createWithDraggedAnchor_autoCompilesWithPreserveAnchor_E27() {
        val vm = vm()
        vm.update { it.copy(name = "林晚", personalityDescription = "高冷毒舌", personalityAnchor = it.personalityAnchor.setValue(0, 15)) }
        vm.save {}
        idle()

        coVerify(exactly = 1) { coordinator.compileForNewCharacter(any(), true) }
        val entity = slot<CharacterEntity>()
        coVerify(exactly = 1) { characterRepo.insert(capture(entity), any(), any()) }
        assertEquals("锚点列与现值列都是手拖的 15", 15, GrowthJson.decodePersonalitySpectrum(entity.captured.personalityAnchorJSON).extroversion)
        assertEquals(15, GrowthJson.decodePersonalitySpectrum(entity.captured.personalitySpectrumJSON).extroversion)
    }

    @Test
    fun createWithBlankPersona_stillDelegatesButCompilerSkipsInside() {
        // 人设为空时「零 LLM 调用」由协调器内部守卫（PersonaCompileCoordinatorTest 已钉）；
        // VM 这层只保证不会因为人设空就漏掉建角色其余步骤，也不会跑第二次。
        val vm = vm()
        vm.update { it.copy(name = "林晚", personalityDescription = "") }
        vm.save {}
        idle()

        coVerify(exactly = 1) { coordinator.compileForNewCharacter(any(), any()) }
    }

    @Test
    fun editMode_saveDoesNotAutoCompile() {
        coEvery { characterRepo.get(UUID) } returns character()
        val vm = vm(editingUuid = UUID)
        idle()
        vm.update { it.copy(name = "林晚") }
        vm.save {}
        idle()

        coVerify(exactly = 0) { coordinator.compileForNewCharacter(any(), any()) }
    }

    // MARK: - Y-E20 / I-4 重入拦截

    @Test
    fun compilePersona_secondTapWhileRunning_isIgnored() {
        coEvery { characterRepo.get(UUID) } returns character()
        // 用一个不完成的 Deferred 把第一次编译挂住，模拟「还在跑」。
        val gate = CompletableDeferred<PersonaCompileOutcome>()
        coEvery { coordinator.compileAndPersist(UUID) } coAnswers { gate.await() }

        val vm = vm(editingUuid = UUID)
        idle()
        vm.compilePersona()
        idle()
        assertTrue("第一次点击后进入编译中", vm.compiling.value)

        vm.compilePersona() // 连点第二下
        idle()
        coVerify(exactly = 1) { coordinator.compileAndPersist(UUID) }

        gate.complete(PersonaCompileOutcome.Failed("测试放行"))
        idle()
        assertFalse("跑完复位，按钮恢复可点", vm.compiling.value)
    }

    @Test
    fun compilePersona_withBlankPersona_makesZeroCalls() {
        coEvery { characterRepo.get(UUID) } returns character(persona = "")
        val vm = vm(editingUuid = UUID)
        idle()
        vm.compilePersona()
        idle()

        coVerify(exactly = 0) { coordinator.compileAndPersist(any()) }
        assertFalse(vm.compiling.value)
    }

    @Test
    fun compilePersona_inCreateMode_makesZeroCalls() {
        val vm = vm()
        vm.update { it.copy(personalityDescription = "高冷毒舌") }
        vm.compilePersona()
        idle()

        coVerify(exactly = 0) { coordinator.compileAndPersist(any()) }
    }

    @Test
    fun compilePersona_reloadsFormFromDatabaseAfterwards() {
        val before = character()
        val after = character(
            anchorJson = GrowthJson.encode(PersonalitySpectrum(extroversion = 30, warmth = 25)),
            metaJson = GrowthJson.encode(
                PersonaCompileMeta(source = PersonaCompileMeta.SOURCE_COMPILED, compiledAt = 5L, droppedCount = 2),
            ),
        )
        coEvery { characterRepo.get(UUID) } returnsMany listOf(before, after)
        coEvery { coordinator.compileAndPersist(UUID) } returns
            PersonaCompileOutcome.Success(PersonaCompileMeta(), PersonalitySpectrum(), syncedCurrentSpectrum = true)

        val vm = vm(editingUuid = UUID)
        idle()
        vm.compilePersona()
        idle()

        assertEquals("编译结果当场回灌表单", 30, vm.state.value.personalityAnchor.extroversion)
        assertEquals(2, vm.state.value.personaCompileMeta.droppedCount)
    }

    @Test
    fun compilePersona_withUnsavedGainOrOperatorEdits_showsNeedsSave_makesZeroCalls_E30() {
        coEvery { characterRepo.get(UUID) } returns character()
        val vm = vm(editingUuid = UUID)
        idle()
        vm.update { it.copy(personaGains = it.personaGains.copy(system = it.personaGains.system + ("g05" to 2))) }
        vm.compilePersona()
        idle()

        assertTrue("提示条亮", vm.personaNeedsSave.value)
        coVerify(exactly = 0) { coordinator.compileAndPersist(any(), any()) }
        assertFalse(vm.compiling.value)
    }

    // MARK: - §3.6 拖锚点保存

    @Test
    fun draggingAnchor_writesAnchorColumnAndFlipsSourceToManual() {
        val existing = character(analysisCount = 7, anchorJson = GrowthJson.encode(PersonalitySpectrum(warmth = 25)))
        coEvery { characterRepo.get(UUID) } returns existing
        val vm = vm(editingUuid = UUID)
        idle()

        vm.update { it.copy(personalityAnchor = it.personalityAnchor.setValue(3, 80)) } // warmth 25 → 80
        vm.save {}
        idle()

        val anchor = slot<String>(); val meta = slot<String>()
        val gains = slot<String>(); val operators = slot<String>()
        coVerify(exactly = 1) {
            characterRepo.updatePersonaCompile(UUID, capture(anchor), capture(meta), capture(gains), capture(operators))
        }
        assertEquals(80, GrowthJson.decodePersonalitySpectrum(anchor.captured).warmth)
        assertEquals(PersonaCompileMeta.SOURCE_MANUAL, GrowthJson.decodePersonaCompileMeta(meta.captured).source)
        assertEquals("没动的增益列原样回传", existing.personaGainsJSON, gains.captured)
        assertEquals("没动的算子列原样回传", existing.personaOperatorsJSON, operators.captured)
        // 已相处过（totalAnalysisCount = 7）⇒ 现值一个字节不动（C4 / Y-E11）。
        coVerify(exactly = 0) { characterRepo.updateGrowthDimensions(any(), any(), any(), any()) }
    }

    @Test
    fun draggingAnchor_onNeverAnalyzedCharacter_syncsCurrentSpectrum() {
        coEvery { characterRepo.get(UUID) } returns
            character(analysisCount = 0, anchorJson = GrowthJson.encode(PersonalitySpectrum(warmth = 25)))
        val vm = vm(editingUuid = UUID)
        idle()

        vm.update { it.copy(personalityAnchor = it.personalityAnchor.setValue(3, 80)) }
        vm.save {}
        idle()

        val spectrum = slot<String>(); val quality = slot<String>()
        coVerify(exactly = 1) { characterRepo.updateGrowthDimensions(UUID, capture(spectrum), capture(quality), any()) }
        assertEquals("未分析过 ⇒ 现值跟着本性走（Y-3）", 80, GrowthJson.decodePersonalitySpectrum(spectrum.captured).warmth)
        assertEquals("关系质感取表单当前值，不凭空清零", 62, GrowthJson.decodeRelationshipQuality(quality.captured).familiarity)
    }

    @Test
    fun saveDuringBackgroundAnalysis_keepsFreshUntouchedDims_writesUserDims_E31() {
        // 开屏快照：锚点 = 现值 = (ext 44, warmth 41)，从未分析；用户把 warmth 锚点拖到 80（Y-3 ⇒ 现值跟着走）。
        // 保存期间后台把现值 humor 50 → 70（用户没碰）⇒ 写出的现值 = warmth 80（用户）+ humor 70（fresh）。
        val snapshot = character(analysisCount = 0, anchorJson = GrowthJson.encode(PersonalitySpectrum(extroversion = 44, warmth = 41)))
        val fresh = snapshot.copy(personalitySpectrumJSON = GrowthJson.encode(PersonalitySpectrum(extroversion = 44, warmth = 41, humor = 70)))
        var reads = 0
        coEvery { characterRepo.get(UUID) } answers { if (reads++ == 0) snapshot else fresh }
        val vm = vm(editingUuid = UUID)
        idle()

        vm.update { it.copy(personalityAnchor = it.personalityAnchor.setValue(3, 80)) }
        vm.save {}
        idle()

        val spectrum = slot<String>()
        coVerify(exactly = 1) { characterRepo.updateGrowthDimensions(UUID, capture(spectrum), any(), any()) }
        val written = GrowthJson.decodePersonalitySpectrum(spectrum.captured)
        assertEquals("用户拖的维 = 用户值", 80, written.warmth)
        assertEquals("用户没碰的维 = 后台新值，不被开屏快照打回 50", 70, written.humor)
        assertEquals(44, written.extroversion)
    }

    @Test
    fun saveOnlyOneRelationshipDim_resetsThatDim_keepsFreshOthers_E32() {
        // 快照：familiarity 62（默认串）；fresh 里后台把 trust 20 → 35（正 45/负 10）；用户只把 closeness 10 → 55。
        val snapshot = character(analysisCount = 7, pressureJson = GrowthJson.encode(storedPressure()))
        val freshPressure = storedPressure().copy(pos = storedPressure().pos.toMutableList().also { it[1] = 45 })
        val fresh = snapshot.copy(
            relationshipQualityJSON = GrowthJson.encode(freshPressure.toQuality()),
            relationshipPressureJSON = GrowthJson.encode(freshPressure),
            personalitySpectrumJSON = GrowthJson.encode(PersonalitySpectrum(extroversion = 44, warmth = 41, humor = 70)),
        )
        var reads = 0
        coEvery { characterRepo.get(UUID) } answers { if (reads++ == 0) snapshot else fresh }
        val vm = vm(editingUuid = UUID)
        idle()

        vm.update { it.copy(relationshipQuality = it.relationshipQuality.setValue(2, 55)) }
        vm.save {}
        idle()

        val spectrum = slot<String>(); val quality = slot<String>(); val pressure = slot<String>()
        coVerify(exactly = 1) { characterRepo.updateGrowthDimensions(UUID, capture(spectrum), capture(quality), capture(pressure)) }
        val q = GrowthJson.decodeRelationshipQuality(quality.captured)
        val p = GrowthJson.decodeRelationshipPressure(pressure.captured)
        assertEquals("改过的维重置：pos 55 / neg 0", 55, q.closeness)
        assertEquals(55, p.pos[2]); assertEquals(0, p.neg[2])
        assertEquals("没碰的 trust 取后台新值 35（正 45/负 10 原封）", 35, q.trust)
        assertEquals(45, p.pos[1]); assertEquals(10, p.neg[1])
        assertEquals("性格列取 fresh（含后台的 humor 70）", 70, GrowthJson.decodePersonalitySpectrum(spectrum.captured).humor)
        coVerify(exactly = 0) { characterRepo.updatePersonaCompile(any(), any(), any(), any(), any()) }
    }

    @Test
    fun editingGainsAndDeletingOperator_registersManualSystemAndTombstone_J7() {
        coEvery { characterRepo.get(UUID) } returns character(analysisCount = 3)
        val vm = vm(editingUuid = UUID)
        idle()
        // g02 2 → 1（UI 会把正常档从 map 里删掉）、g05 → 0；删掉算子 o1
        vm.update {
            it.copy(
                personaGains = it.personaGains.copy(system = mapOf("g05" to 0)),
                personaOperators = emptyList(),
            )
        }
        vm.save {}
        idle()

        val meta = slot<String>(); val gains = slot<String>(); val operators = slot<String>()
        coVerify(exactly = 1) { characterRepo.updatePersonaCompile(UUID, any(), capture(meta), capture(gains), capture(operators)) }
        val g = GrowthJson.decodePersonaGains(gains.captured)
        assertEquals("手调过的两项登记；g02 改成 1 也写显式 1 才压得住编译值", mapOf("g05" to 0, "g02" to 1), g.system)
        assertEquals(setOf("g02", "g05"), g.manualSystem.toSet())
        assertEquals(listOf("c01|a01"), GrowthJson.decodePersonaCompileMeta(meta.captured).suppressedOperators)
        assertEquals("[]", operators.captured)
        assertEquals("没拖锚点 ⇒ source 不动", PersonaCompileMeta.SOURCE_DEFAULT, GrowthJson.decodePersonaCompileMeta(meta.captured).source)
    }

    @Test
    fun notTouchingAnchor_writesNothingToPersonaColumns() {
        coEvery { characterRepo.get(UUID) } returns character(analysisCount = 0)
        val vm = vm(editingUuid = UUID)
        idle()

        vm.update { it.copy(occupation = "调酒师") } // 只改了别的字段
        vm.save {}
        idle()

        coVerify(exactly = 0) { characterRepo.updatePersonaCompile(any(), any(), any(), any(), any()) }
    }

    // MARK: - D-2 判据

    @Test
    fun personaStale_isTrueOnlyWhenCompiledAndHashDiffers() {
        val fresh = CharacterEditState(
            personalityDescription = "高冷毒舌",
            personaCompileMeta = PersonaCompileMeta(
                source = PersonaCompileMeta.SOURCE_COMPILED,
                personaHash = com.situ.aichat.prompt.persona.personaTextHash("高冷毒舌"),
            ),
        )
        assertFalse("hash 对得上 ⇒ 不提醒", fresh.personaStale)
        assertTrue("改了一个字 ⇒ 提醒", fresh.copy(personalityDescription = "高冷毒舌。").personaStale)
        assertFalse(
            "从未编译过 ⇒ 永不提醒（状态 A 卡本就在劝你生成）",
            CharacterEditState(personalityDescription = "高冷毒舌").personaStale,
        )
    }

    private companion object {
        const val UUID = "char-1"
    }

    // MARK: - 卷二《正负双压》T2-2（图纸 §7.2 · P-E15 / P-E16）：⑦ 手拖滑杆的两条分支

    /** 库里那份「相处出来的」压强：familiarity 正 70/负 8（净额 62），trust 正 30/负 10（净额 20）。 */
    private fun storedPressure() = RelationshipPressure(
        pos = listOf(70, 30, 10, 10, 35, 20, 5, 5),
        neg = listOf(8, 10, 0, 0, 0, 0, 0, 0),
    )

    @Test
    fun `T2-2 只改性格没动关系滑杆 - 压强列原样透传`() {
        val stored = GrowthJson.encode(storedPressure())
        coEvery { characterRepo.get(UUID) } returns
            character(analysisCount = 0, anchorJson = GrowthJson.encode(PersonalitySpectrum(warmth = 25)), pressureJson = stored)
        val vm = vm(editingUuid = UUID)
        idle()

        vm.update { it.copy(personalityAnchor = it.personalityAnchor.setValue(3, 80)) }   // 只拖了性格锚点
        vm.save {}
        idle()

        val pressure = slot<String>()
        coVerify(exactly = 1) { characterRepo.updateGrowthDimensions(UUID, any(), any(), capture(pressure)) }
        // P-E15：这条写口同时管性格与关系两列，只改性格时关系侧是**透传**——那一刻若顺手重置双压，
        // 就是趁用户改职业/改性格的时候静默清空她的相处史。
        assertEquals("压强列必须逐字节原样透传", stored, pressure.captured)
    }

    @Test
    fun `T2-2 改了三个关系维 - 那三维重置另五维原样保留`() {
        coEvery { characterRepo.get(UUID) } returns
            character(analysisCount = 7, pressureJson = GrowthJson.encode(storedPressure()))
        val vm = vm(editingUuid = UUID)
        idle()

        // 手拖：familiarity 62→90、closeness 10→55、tension 5→40（其余五维一动不动）。
        vm.update {
            it.copy(
                relationshipQuality = it.relationshipQuality.setValue(0, 90).setValue(2, 55).setValue(6, 40),
            )
        }
        vm.save {}
        idle()

        val quality = slot<String>(); val pressure = slot<String>()
        coVerify(exactly = 1) { characterRepo.updateGrowthDimensions(UUID, any(), capture(quality), capture(pressure)) }
        val written = GrowthJson.decodeRelationshipPressure(pressure.captured)

        // P-E16 改了的三维：手调 = 圣旨 ⇒ pos = 目标值、neg 清零（历史那两股力不作数了）。
        assertEquals(listOf(90, 55, 40), listOf(written.pos[0], written.pos[2], written.pos[6]))
        assertEquals(listOf(0, 0, 0), listOf(written.neg[0], written.neg[2], written.neg[6]))
        // 没改的五维：连负压都必须原封不动（trust 的 30/10 是她攒出来的，跟这次编辑无关）。
        assertEquals(30, written.pos[1])
        assertEquals(10, written.neg[1])
        assertEquals(listOf(10, 35, 20, 5), listOf(written.pos[3], written.pos[4], written.pos[5], written.pos[7]))
        assertEquals("净额列必须由压强单向派生，与表单值一致", 90, GrowthJson.decodeRelationshipQuality(quality.captured).familiarity)
        assertEquals(20, GrowthJson.decodeRelationshipQuality(quality.captured).trust)
    }
}
