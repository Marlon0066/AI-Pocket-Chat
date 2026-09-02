package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.fromQuality
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.maintenance.MaintenanceThrottleStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

/**
 * T2-1（图纸 §7）：[RelationshipArchetypeCalibrator] 棘轮/圣旨/回拉/清列/幂等。
 * MockK CharacterDao/MilestoneDao/ThrottleStore + **真 Lexicon（fromRawText 真资产）+ 真锁**。
 * 期望 quality 从棘轮/回拉规格独立算出后 encode，coVerify 列级方法与参数（PITFALLS §3.22）。
 */
class RelationshipArchetypeCalibratorTest {

    private val realLexicon = RelationshipLexicon.fromRawText(
        File("src/main/assets/growth/relationship_lexicon.tsv").readText(),
    )
    private val dao = mockk<CharacterDao>(relaxed = true)
    private val milestoneDao = mockk<MilestoneDao>(relaxed = true)
    private val throttle = mockk<MaintenanceThrottleStore>(relaxed = true)
    private val calibrator = RelationshipArchetypeCalibrator(dao, CharacterWriteLock(), realLexicon, throttle, milestoneDao, apkLastUpdateTime = { 0L })

    private fun char(q: RelationshipQuality, archetypeId: String? = null) = CharacterEntity(
        uuid = "u", name = "n", creationDate = 0L,
        relationshipQualityJSON = GrowthJson.encode(q),
        relationshipArchetypeId = archetypeId,
    )

    @Test fun `棘轮只升不降 - 低值抬到地板高值不动`() = runTest {
        // 输入 fam90(>LOVER 地板55→不动) trust20(<30→抬) 余低。LOVER 地板=[55,30,50,45,45,40,0,0]。
        coEvery { dao.getByUuid("u") } returns char(
            RelationshipQuality(familiarity = 90, trust = 20, closeness = 10, rapport = 10, respect = 35, funValue = 20, tension = 5, attachment = 5),
        )
        calibrator.calibrate("u", "恋人", applyFloors = true, applyCeilings = false)
        val expectedQuality = RelationshipQuality(familiarity = 90, trust = 30, closeness = 50, rapport = 45, respect = 45, funValue = 40, tension = 5, attachment = 5)
        val expected = GrowthJson.encode(expectedQuality)
        // 卷二 T2-6（P-E20）：抬地板全是**正向**位移 ⇒ 只加正压，负压恒 0（等价于按新净额重新播种）。
        val expectedPressure = GrowthJson.encode(RelationshipPressure.fromQuality(expectedQuality))
        coVerify(exactly = 1) { dao.updateArchetypeCalibration("u", "LOVER", expected, expectedPressure) }
    }

    @Test fun `圣旨跳过抬分但仍写 id`() = runTest {
        coEvery { dao.getByUuid("u") } returns char(RelationshipQuality()) // INITIAL·archetypeId=null
        calibrator.calibrate("u", "恋人", applyFloors = false, applyCeilings = false)
        coVerify(exactly = 1) { dao.updateRelationshipArchetypeId("u", "LOVER") }
        coVerify(exactly = 0) { dao.updateArchetypeCalibration(any(), any(), any(), any()) }
    }

    @Test fun `E5 手动前任回拉天花板`() = runTest {
        // 输入 fam10(<EX地板60) trust80/close80/fun80(>天花板)。EX 地板[60,15,10,35,20,5,0,0]·天花板[-,45,45,-,-,55,-,-]。
        coEvery { dao.getByUuid("u") } returns char(
            RelationshipQuality(familiarity = 10, trust = 80, closeness = 80, rapport = 50, respect = 30, funValue = 80, tension = 10, attachment = 20),
        )
        calibrator.calibrate("u", "前任", applyFloors = true, applyCeilings = true)
        // 先回拉 trust80→45/close80→45/fun80→55，再抬地板 fam10→60。**计算顺序不变**（先压后抬）。
        val expected = GrowthJson.encode(
            RelationshipQuality(familiarity = 60, trust = 45, closeness = 45, rapport = 50, respect = 30, funValue = 55, tension = 10, attachment = 20),
        )
        // 卷二 T2-6（P-E20）：抬地板 ⇒ 加正压（fam 10→60，正压 +50）；压天花板 ⇒ 加**负压**而不是削正压
        // （trust 正压仍是 80、负压 +35 ⇒ 净额 45）——「被名分压着」这股力就此有了自己的账，能参与矛盾判定。
        val expectedPressure = GrowthJson.encode(
            RelationshipPressure(
                pos = listOf(60, 80, 80, 50, 30, 80, 10, 20),
                neg = listOf(0, 35, 35, 0, 0, 25, 0, 0),
            ),
        )
        coVerify(exactly = 1) { dao.updateArchetypeCalibration("u", "EX", expected, expectedPressure) }
    }

    @Test fun `E5 AI 前任不回拉 - 仅抬地板`() = runTest {
        coEvery { dao.getByUuid("u") } returns char(
            RelationshipQuality(familiarity = 10, trust = 80, closeness = 80, rapport = 50, respect = 30, funValue = 80, tension = 10, attachment = 20),
        )
        calibrator.calibrate("u", "前任", applyFloors = true, applyCeilings = false)
        // 无回拉：trust/close/fun 保持 80；仅 fam10→60。
        val expectedQuality = RelationshipQuality(familiarity = 60, trust = 80, closeness = 80, rapport = 50, respect = 30, funValue = 80, tension = 10, attachment = 20)
        val expected = GrowthJson.encode(expectedQuality)
        // 只有一维动且是正向 ⇒ 负压全 0（不回拉就不该凭空产生任何负压）。
        val expectedPressure = GrowthJson.encode(RelationshipPressure.fromQuality(expectedQuality))
        coVerify(exactly = 1) { dao.updateArchetypeCalibration("u", "EX", expected, expectedPressure) }
    }

    @Test fun `解析 null 清陈旧 id 列`() = runTest {
        coEvery { dao.getByUuid("u") } returns char(RelationshipQuality(), archetypeId = "LOVER")
        calibrator.calibrate("u", "zzzzz", applyFloors = true, applyCeilings = false) // 词表认不出→null
        coVerify(exactly = 1) { dao.updateRelationshipArchetypeId("u", null) }
        coVerify(exactly = 0) { dao.updateArchetypeCalibration(any(), any(), any(), any()) }
    }

    @Test fun `幂等 - 已校准零写`() = runTest {
        coEvery { dao.getByUuid("u") } returns char(
            RelationshipQuality(familiarity = 55, trust = 30, closeness = 50, rapport = 45, respect = 45, funValue = 40, tension = 5, attachment = 5),
            archetypeId = "LOVER",
        )
        calibrator.calibrate("u", "恋人", applyFloors = true, applyCeilings = false)
        coVerify(exactly = 0) { dao.updateArchetypeCalibration(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.updateRelationshipArchetypeId(any(), any()) }
    }

    @Test fun `角色不存在 - 零写`() = runTest {
        coEvery { dao.getByUuid("u") } returns null
        calibrator.calibrate("u", "恋人", applyFloors = true, applyCeilings = false)
        coVerify(exactly = 0) { dao.updateArchetypeCalibration(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.updateRelationshipArchetypeId(any(), any()) }
    }
}
