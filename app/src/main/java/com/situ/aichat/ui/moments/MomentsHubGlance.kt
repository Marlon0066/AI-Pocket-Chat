package com.situ.aichat.ui.moments

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.ui.pet.PetMoodType
import com.situ.aichat.ui.pet.needsAttention
import com.situ.aichat.ui.pet.petStatusKind

/**
 * 圈子枢纽「实时状态卡」纯派生逻辑（契约 FABLE5_MOMENTS_HUB_REDESIGN_PROPOSAL.md §3）。
 *
 * 与 Compose / 字符串资源解耦——只决定「显示哪一种状态」，最终文案 / 上色由 UI 层（N3）映射到 stringResource，
 * 故可纯函数单测（[MomentsHubGlanceTest]）。故事副标题镜像书架 [com.situ.aichat.ui.story.StoryCard] 的
 * detailLine 三段判定（**去 genre 前缀**·Hub 卡更紧）；宠物「最需照顾」复用单一心情判定 [PetMoodType.from]（DRY）。
 */

/** 故事卡副标题状态分支。 */
internal sealed interface StoryHubStatus {
    /** 有最新章 →「第 [number] 话 · [title]」。 */
    data class Chapter(val number: Int, val title: String) : StoryHubStatus
    /** 有故事但还没有章节 →「尚未生成」。 */
    object NoChapter : StoryHubStatus
    /** 还没有任何故事 → 用空态描述。 */
    object None : StoryHubStatus
}

/**
 * 由最新一本故事派生卡片状态（镜像书架 detailLine + 无故事兜底）。
 *
 * 卷二·单模式化：原第三支「有连载上限、暂无章节 →『计划 N 章』」随有限模式退役删除——迁移 39→40 后
 * 全库 maxChapters 恒 null，无章故事一律「尚未生成」。
 */
internal fun storyHubStatus(story: StoryEntity?): StoryHubStatus = when {
    story == null -> StoryHubStatus.None
    story.cachedLatestChapterNumber != null && !story.cachedLatestChapterTitle.isNullOrEmpty() ->
        StoryHubStatus.Chapter(story.cachedLatestChapterNumber, story.cachedLatestChapterTitle)
    else -> StoryHubStatus.NoChapter
}

/**
 * 从所有宠物里挑「最需照顾」的一只（生病 > 饿 > 难过 > 满足 > 开心；契约 §8-4）。空列表返回 null；
 * 单只即返回该只。复用 [PetMoodType.from] 单一心情判定（DRY），不重写阈值。
 */
internal fun pickNeediestPet(pets: List<CharacterPetEntity>): CharacterPetEntity? =
    pets.minByOrNull { petMoodUrgency(PetMoodType.from(it)) }

/** 心情紧迫度排序键（越小越急需照顾）。 */
private fun petMoodUrgency(mood: PetMoodType): Int = when (mood) {
    PetMoodType.SICK -> 0
    PetMoodType.HUNGRY -> 1
    PetMoodType.SAD -> 2
    PetMoodType.CONTENT -> 3
    PetMoodType.HAPPY -> 4
}

// ── 动态页「宠物」条（图纸 2026-09-06-宠物总览页复活 §3.1） ──

/** 精灵排上限 5（与「圈子」条头像排 HERO_AVATAR_MAX 同口径）。 */
internal const val PET_STRIP_SPRITE_MAX = 5

/**
 * 宠物条一览（纯派生·T1 可直接出题）。
 *
 * @property count    宠物总数（0 = 空态：不出精灵排、尾行显「一起养一只宠物吧」）
 * @property sprites  精灵排：adoptedDate 升序前 [PET_STRIP_SPRITE_MAX] 只
 * @property neediest 最需照顾那只（[pickNeediestPet]）；null 仅当无宠物
 * @property allWell  有宠物且**无一**「需要你」→ 尾行显「都好着呢」
 */
internal data class PetStripGlance(
    val count: Int,
    val sprites: List<CharacterPetEntity>,
    val neediest: CharacterPetEntity?,
    val allWell: Boolean,
)

/**
 * 全量宠物 → 宠物条一览。
 *
 * 「需要你」判定复用 [petStatusKind] + [needsAttention] 单源（`ui/pet/PetStatusLine.kt`），
 * 与世界卡信息条同一套 —— 两处**永不**自相矛盾（图纸 §3.2/§3.3）。
 */
internal fun petStripGlance(pets: List<CharacterPetEntity>): PetStripGlance = PetStripGlance(
    count = pets.size,
    sprites = pets.sortedBy { it.adoptedDate }.take(PET_STRIP_SPRITE_MAX),
    neediest = pickNeediestPet(pets),
    allWell = pets.isNotEmpty() && pets.none { petStatusKind(it).needsAttention },
)
