package com.situ.aichat.ui.moments

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.StoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圈子枢纽实时状态派生纯函数单测（契约 §3 规格反推）：
 * 故事三段判定 + 无故事兜底；宠物「最需照顾」优先级（生病>饿>难过>满足>开心）。
 */
class MomentsHubGlanceTest {

    // ── storyHubStatus ──
    @Test fun story_null_isNone() {
        assertEquals(StoryHubStatus.None, storyHubStatus(null))
    }

    @Test fun story_withLatestChapter_isChapter() {
        val s = StoryEntity(cachedLatestChapterNumber = 12, cachedLatestChapterTitle = "雨夜的来信")
        assertEquals(StoryHubStatus.Chapter(12, "雨夜的来信"), storyHubStatus(s))
    }

    @Test fun story_blankChapterTitle_fallsThrough() {
        // 有章号但标题空 → 不算 Chapter → NoChapter。
        val s = StoryEntity(cachedLatestChapterNumber = 3, cachedLatestChapterTitle = "")
        assertEquals(StoryHubStatus.NoChapter, storyHubStatus(s))
    }

    @Test fun story_noChapter_isNoChapter() {
        val s = StoryEntity(cachedLatestChapterNumber = null)
        assertEquals(StoryHubStatus.NoChapter, storyHubStatus(s))
    }

    /**
     * 卷二·单模式化：原第三支「有连载上限、暂无章节 →『计划 N 章』」随有限模式退役删除（原例
     * `story_noChapter_withLimit_isPlanned` 删除属预期）。迁移 39→40 后 maxChapters 恒 null，
     * 但即便脏数据残留一个值，也一律落 NoChapter——此例即该退役口径的看门狗。
     */
    @Test fun story_noChapter_evenWithStaleMaxChapters_isNoChapter() {
        val s = StoryEntity(cachedLatestChapterNumber = null, maxChapters = 10)
        assertEquals(StoryHubStatus.NoChapter, storyHubStatus(s))
    }

    // ── pickNeediestPet ──（pet() 默认 happiness=80 → HAPPY；hunger=0 / health=100 / neglect=none）
    private fun pet(name: String, hunger: Int = 0, happiness: Int = 80, neglect: String = "none") =
        CharacterPetEntity(name = name, hunger = hunger, happiness = happiness, neglectPhaseRaw = neglect)

    @Test fun pet_empty_isNull() {
        assertNull(pickNeediestPet(emptyList()))
    }

    @Test fun pet_single_isThatPet() {
        val p = pet("开开")
        assertSame(p, pickNeediestPet(listOf(p)))
    }

    @Test fun pet_hungryBeatsHappy() {
        val happy = pet("开开")
        val hungry = pet("饿饿", hunger = 80)
        assertSame(hungry, pickNeediestPet(listOf(happy, hungry)))
    }

    @Test fun pet_sickBeatsHungry() {
        val hungry = pet("饿饿", hunger = 80)
        val sick = pet("病病", neglect = "sick")
        assertSame(sick, pickNeediestPet(listOf(hungry, sick)))
    }

    @Test fun pet_sadBeatsContent() {
        val content = pet("安安", happiness = 50)
        val sad = pet("丧丧", happiness = 10)
        assertSame(sad, pickNeediestPet(listOf(content, sad)))
    }

    // ── petStripGlance（图纸 2026-09-06-宠物总览页复活 V2/V3·断言从 §3.1 规格独立反推） ──

    /** 带领养时刻的宠物（精灵排按 adoptedDate 升序取前 5）。 */
    private fun petAt(name: String, adoptedDate: Long, hunger: Int = 0, happiness: Int = 80, neglect: String = "none") =
        CharacterPetEntity(name = name, hunger = hunger, happiness = happiness, neglectPhaseRaw = neglect, adoptedDate = adoptedDate)

    @Test fun strip_empty_countZero_noSprites_noNeediest_notAllWell() {
        val g = petStripGlance(emptyList())
        assertEquals(0, g.count)
        assertTrue(g.sprites.isEmpty())
        assertNull(g.neediest)
        assertFalse("无宠物不是「都好着呢」，是空态", g.allWell)
    }

    @Test fun strip_singleHealthy_allWell() {
        val g = petStripGlance(listOf(pet("开开")))          // happiness 80 → HAPPY
        assertEquals(1, g.count)
        assertTrue(g.allWell)
    }

    @Test fun strip_singleHungry_notAllWell_neediestIsIt() {
        val hungry = pet("饿饿", hunger = 80)
        val g = petStripGlance(listOf(hungry))
        assertFalse(g.allWell)
        assertSame(hungry, g.neediest)
    }

    @Test fun strip_mixed_notAllWell_neediestIsTheUrgentOne() {
        val happy = pet("开开")
        val sick = pet("病病", neglect = "sick")
        val g = petStripGlance(listOf(happy, sick))
        assertFalse(g.allWell)
        assertSame(sick, g.neediest)
    }

    /** CONTENT（happiness 50·不饿不病）不属「需要你」→ 仍算都好着呢。 */
    @Test fun strip_contentOnly_isAllWell() {
        val g = petStripGlance(listOf(pet("安安", happiness = 50)))
        assertTrue(g.allWell)
    }

    @Test fun strip_sixPets_countSix_spritesCappedAtFive_byAdoptedDateAsc() {
        // 倒序喂入，验证函数自己按 adoptedDate 升序排；总数**不**被截（缺口正在此：家内站位才截 3）
        val pets = (6 downTo 1).map { petAt("宠$it", adoptedDate = it * 1000L) }
        val g = petStripGlance(pets)
        assertEquals(6, g.count)
        assertEquals(5, g.sprites.size)
        assertEquals(listOf("宠1", "宠2", "宠3", "宠4", "宠5"), g.sprites.map { it.name })
    }

    /**
     * V3 单源一致性：`petStripGlance` 的 neediest 与世界卡信息条 `WorldCardInfo.buildSegments`
     * 选中的**必须是同一只**——两处若各写一套 when，同屏会自相矛盾（图纸 §3.2/§3.3）。
     */
    @Test fun strip_neediest_agreesWith_worldCardInfoBar() {
        val happy = pet("开开")
        val sad = pet("丧丧", happiness = 10)
        val hungry = pet("饿饿", hunger = 80)
        val pets = listOf(happy, sad, hungry)
        val g = petStripGlance(pets)
        val seg = WorldCardInfo.buildSegments(joined = 0, pending = 0, pet = g.neediest)
            .filterIsInstance<InfoSegment.PetNeeds>()
            .single()
        assertEquals("饿饿", seg.name)                       // 饿 > 难过（petMoodUrgency 序）
        assertSame(hungry, g.neediest)
        assertEquals(PetNeedKind.HUNGRY, seg.kind)
    }

    /** 全健康时信息条不出宠物段（决策 41⑥ quiet），与 allWell=true 一致。 */
    @Test fun strip_allWell_worldCardEmitsNoPetSegment() {
        val pets = listOf(pet("开开"), pet("安安", happiness = 50))
        val g = petStripGlance(pets)
        assertTrue(g.allWell)
        val segs = WorldCardInfo.buildSegments(joined = 0, pending = 0, pet = g.neediest)
        assertTrue("都好着呢时信息条不该顶宠物段", segs.filterIsInstance<InfoSegment.PetNeeds>().isEmpty())
    }
}
