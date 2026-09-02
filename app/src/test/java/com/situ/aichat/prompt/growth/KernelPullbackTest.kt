package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.fromQuality
import com.situ.aichat.data.repository.CharacterWriteLock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2-1（活人感内核卷零图纸 §7.2）+ 修缮卷 T2-9（E33–E36 / J9）：老角色一次性拉回扫 [KernelPullback] 的行为测试。
 * MockK 假掉 CharacterDao / MilestoneDao + **真 [CharacterWriteLock] + 真 [AffectKernel]**（标记经场内核落 `pullbackDone`）。
 *
 * 期望值一律从图纸独立算出再 encode 比对（不照抄实现输出）：
 * `tension` 上限 60、`openness` 上限 70（**仅 trust ≥ 70 才拉**·D-10）、`attachment` 上限 = 名分平衡点；
 * 拉回后 = `(cur + cap) / 2` **整数除法**；`cur <= cap` 一动不动；其余 13 维零改。
 * 幂等按角色：`creationDate ≥ 2026-09-01` 跳过、`pullbackDone` 跳过；处理过的角色恰写 1 次场列标记。
 */
class KernelPullbackTest {

    private val dao = mockk<CharacterDao>(relaxed = true)
    private val milestoneDao = mockk<MilestoneDao>(relaxed = true)
    private val pullback = KernelPullback(dao, milestoneDao, CharacterWriteLock(), AffectKernel(dao))

    private fun char(
        uuid: String = "u",
        quality: RelationshipQuality = RelationshipQuality(),
        spectrum: PersonalitySpectrum = PersonalitySpectrum.NEUTRAL,
        pressure: RelationshipPressure? = null,   // null = 老角色空列（走播种兜底）
        creationDate: Long = 0L,
        affectJson: String = "",
    ) = CharacterEntity(
        uuid = uuid, name = "n", creationDate = creationDate,
        relationshipQualityJSON = GrowthJson.encode(quality),
        relationshipPressureJSON = pressure?.let { GrowthJson.encode(it) } ?: "",
        personalitySpectrumJSON = GrowthJson.encode(spectrum),
        affectFieldJSON = affectJson,
    )

    private fun milestoneNamed(relationshipName: String) = MilestoneEntity(
        uuid = "m", characterUuid = "u", relationshipName = relationshipName, establishedDate = 0L,
    )

    private fun stubOne(c: CharacterEntity) {
        coEvery { dao.getAll() } returns listOf(c)
        coEvery { dao.getByUuid(c.uuid) } returns c
        coEvery { dao.getAffectFieldJson(c.uuid) } returns c.affectFieldJSON
    }

    private fun markedField(uuid: String = "u"): AffectField {
        val json = slot<String>()
        coVerify(exactly = 1) { dao.updateAffectField(uuid, capture(json)) }
        return GrowthJson.decodeAffectFieldOrNull(json.captured)!!
    }

    // MARK: - 修缮卷 E33–E36：按角色标记 + 坦诚 trust 门

    @Test fun `E33 - tension 回拉 openness 因 trust 不足不动 且打标记`() = runTest {
        stubOne(char(quality = RelationshipQuality(tension = 80, trust = 60), spectrum = PersonalitySpectrum(openness = 90)))
        coEvery { milestoneDao.getForCharacter("u") } returns emptyList()

        pullback.runOnceIfNeeded()

        val quality = slot<String>(); val spectrum = slot<String>()
        coVerify(exactly = 1) { dao.updateGrowthDimensions("u", capture(spectrum), capture(quality), any()) }
        assertEquals("(80+60)/2", 70, GrowthJson.decodeRelationshipQuality(quality.captured).tension)
        assertEquals("trust 60 < 70 ⇒ 坦诚不动", 90, GrowthJson.decodePersonalitySpectrum(spectrum.captured).openness)
        assertTrue("标记恰写 1 次", markedField().pullbackDone)
    }

    @Test fun `E33 - trust 达 70 时 openness 照拉`() = runTest {
        stubOne(char(quality = RelationshipQuality(trust = 70), spectrum = PersonalitySpectrum(openness = 90)))
        coEvery { milestoneDao.getForCharacter("u") } returns emptyList()
        pullback.runOnceIfNeeded()
        val spectrum = slot<String>()
        coVerify(exactly = 1) { dao.updateGrowthDimensions("u", capture(spectrum), any(), any()) }
        assertEquals(80, GrowthJson.decodePersonalitySpectrum(spectrum.captured).openness)
    }

    @Test fun `E34 - creationDate 在截止之后的角色 不动不标记`() = runTest {
        stubOne(char(quality = RelationshipQuality(tension = 80), creationDate = KernelPullback.PULLBACK_CUTOFF_MS))
        pullback.runOnceIfNeeded()
        coVerify(exactly = 0) { dao.getByUuid(any()) }
        coVerify(exactly = 0) { dao.updateGrowthDimensions(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.updateAffectField(any(), any()) }
        // 截止前一毫秒建的仍处理
        stubOne(char(quality = RelationshipQuality(tension = 80), creationDate = KernelPullback.PULLBACK_CUTOFF_MS - 1))
        pullback.runOnceIfNeeded()
        coVerify(exactly = 1) { dao.updateGrowthDimensions("u", any(), any(), any()) }
    }

    @Test fun `E35 - 已标记的角色 不再遍历其维度`() = runTest {
        stubOne(char(quality = RelationshipQuality(attachment = 90), affectJson = GrowthJson.encode(AffectField(pullbackDone = true))))
        pullback.runOnceIfNeeded()
        coVerify(exactly = 0) { dao.getByUuid(any()) }
        coVerify(exactly = 0) { dao.updateGrowthDimensions(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.updateAffectField(any(), any()) }
    }

    @Test fun `E36 - 老备份导入（场列空）被处理一次并标记`() = runTest {
        stubOne(char(quality = RelationshipQuality(tension = 90), affectJson = ""))
        coEvery { milestoneDao.getForCharacter("u") } returns emptyList()
        pullback.runOnceIfNeeded()
        coVerify(exactly = 1) { dao.updateGrowthDimensions("u", any(), any(), any()) }
        assertTrue(markedField().pullbackDone)
    }

    @Test fun `界内角色 - 零维度写但仍打标记（下次不再遍历）`() = runTest {
        stubOne(char(quality = RelationshipQuality(tension = 60, attachment = 30), spectrum = PersonalitySpectrum.NEUTRAL))
        coEvery { milestoneDao.getForCharacter("u") } returns emptyList()
        pullback.runOnceIfNeeded()
        coVerify(exactly = 0) { dao.updateGrowthDimensions(any(), any(), any(), any()) }
        assertTrue(markedField().pullbackDone)
    }

    // MARK: - 三维拉回（Z-E6 / Z-E8）

    @Test fun `三维越顶 - 各拉到中点且其余 13 维逐字节不变`() = runTest {
        val before = RelationshipQuality(
            familiarity = 88, trust = 77, closeness = 92, rapport = 66,
            respect = 71, funValue = 64, tension = 90, attachment = 98,
        )
        val beforeSpectrum = PersonalitySpectrum(
            extroversion = 61, emotionality = 62, adventurousness = 63, warmth = 64,
            humor = 65, independence = 66, curiosity = 67, openness = 96,
        )
        stubOne(char(quality = before, spectrum = beforeSpectrum))
        coEvery { milestoneDao.getForCharacter("u") } returns listOf(milestoneNamed("恋人")) // 平衡点 70

        pullback.runOnceIfNeeded()

        // tension (90+60)/2=75；attachment (98+70)/2=84；openness (96+70)/2=83（trust 77 ≥ 70）；其余全原值。
        val expectedQuality = GrowthJson.encode(before.copy(tension = 75, attachment = 84))
        val expectedSpectrum = GrowthJson.encode(beforeSpectrum.copy(openness = 83))
        // 卷二 T2-1（P-E14）：压强侧只减**正压**——空列播种后 neg 全 0，拉回后必须**仍然**全 0。
        val expectedPressure = GrowthJson.encode(RelationshipPressure.fromQuality(before.copy(tension = 75, attachment = 84)))
        coVerify(exactly = 1) { dao.updateGrowthDimensions("u", expectedSpectrum, expectedQuality, expectedPressure) }
    }

    @Test fun `卷二 T2-1 - 已有负压的角色被拉回时负压一个字节不动`() = runTest {
        val before = RelationshipQuality(tension = 90, attachment = 20)
        // 张力上已经攒了 30 点负压（正 120 / 负 30 ⇒ 净额 90）。
        val storedPressure = RelationshipPressure(
            pos = listOf(10, 20, 10, 10, 35, 20, 120, 20),
            neg = listOf(0, 0, 0, 0, 0, 0, 30, 0),
        )
        stubOne(char(quality = before, pressure = storedPressure))
        coEvery { milestoneDao.getForCharacter("u") } returns emptyList()

        pullback.runOnceIfNeeded()

        // tension 拉到 (90+60)/2 = 75 ⇒ pos = neg + 75 = 105；neg 恒 30；attachment 20 <= 35 不动。
        val expected = GrowthJson.encode(storedPressure.copy(pos = listOf(10, 20, 10, 10, 35, 20, 105, 20)))
        coVerify(exactly = 1) { dao.updateGrowthDimensions("u", any(), GrowthJson.encode(before.copy(tension = 75)), expected) }
    }

    @Test fun `无 milestone 的角色 - 依恋上限取默认平衡点 35`() = runTest {
        val before = RelationshipQuality(tension = 10, attachment = 90)
        stubOne(char(quality = before))
        coEvery { milestoneDao.getForCharacter("u") } returns emptyList()

        pullback.runOnceIfNeeded()

        // attachment (90+35)/2 = 62（整除，非 62.5 四舍五入到 63）；tension 10 <= 60 不动。
        coVerify(exactly = 1) {
            dao.updateGrowthDimensions(
                "u",
                GrowthJson.encode(PersonalitySpectrum.NEUTRAL),
                GrowthJson.encode(before.copy(attachment = 62)),
                GrowthJson.encode(RelationshipPressure.fromQuality(before.copy(attachment = 62))),
            )
        }
    }

    // MARK: - 韧性（Z-E7 / Z-E17）

    @Test fun `单角色抛异常 - 整批继续`() = runTest {
        val bad = char(uuid = "bad"); val good = char(uuid = "good", quality = RelationshipQuality(tension = 90))
        coEvery { dao.getAll() } returns listOf(bad, good)
        coEvery { dao.getByUuid("bad") } throws IllegalStateException("boom")
        coEvery { dao.getByUuid("good") } returns good
        coEvery { dao.getAffectFieldJson(any()) } returns ""
        coEvery { milestoneDao.getForCharacter(any()) } returns emptyList()

        pullback.runOnceIfNeeded()

        coVerify(exactly = 1) { dao.updateGrowthDimensions("good", any(), any(), any()) } // 后一个照常处理
        assertTrue(markedField("good").pullbackDone)
        coVerify(exactly = 0) { dao.updateAffectField("bad", any()) }
    }

    @Test fun `首装零角色 - 遍历空列表不崩不写`() = runTest {
        coEvery { dao.getAll() } returns emptyList()
        pullback.runOnceIfNeeded()
        coVerify(exactly = 0) { dao.updateGrowthDimensions(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.updateAffectField(any(), any()) }
    }
}
