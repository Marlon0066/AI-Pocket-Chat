package com.situ.aichat.ui.moments

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.ui.pet.PetStatusKind
import com.situ.aichat.ui.pet.petStatusKind

/**
 * 世界卡信息条纯派生逻辑（W11 图纸 §3·契约 FABLE5_WORLD_SYSTEM_PROPOSAL.md §16）。
 *
 * 与 Compose / 字符串资源解耦——只决定「显示哪几段」，最终文案由 VM 映射到 stringResource（段间「 · 」连接·
 * 全空 → world_card_info_quiet），故可纯函数单测（[WorldCardInfoTest]）。段序锁死：Around → Pending → PetNeeds/EggHatchable。
 */

/** 信息条段（§3 段语义·锁死）。 */
internal sealed interface InfoSegment {
    /** N 位在你身边（n>0 才出·文案 = world_starmap_tag_around 复用）。 */
    data class Around(val count: Int) : InfoSegment
    /** M 位待相识（m>0 才出·文案 = world_starmap_tag_pending）。 */
    data class Pending(val count: Int) : InfoSegment
    /** 宠物「需要你」（名字 + 对应 pet_hub_* 词·VM 映射）。 */
    data class PetNeeds(val name: String, val kind: PetNeedKind) : InfoSegment
    /** 「蛋要孵出来了」（W12.5 决策 42④·可孵化才出·仅 PetNeeds 缺席时·目标 uuid 由 VM 从 Hatchable 态取）。 */
    data object EggHatchable : InfoSegment
}

/** 宠物「需要你」的态（由 [com.situ.aichat.ui.pet.PetStatusKind] 派生·单源见 `ui/pet/PetStatusLine.kt`）。 */
internal enum class PetNeedKind { RAN_AWAY, SICK, HUNGRY, SAD }

/**
 * 由活数据派生信息条段（§3）：人数段（>0 才出）+ 宠物「需要你」段（仅 SICK/HUNGRY/SAD/RAN_AWAY 出·
 * HAPPY/CONTENT 不出——信息条只顶「需要你」的事）。宠物映射**单源** = [petStatusKind]
 * （`ui/pet/PetStatusLine.kt`·同时供动态页宠物条与宠物总览页消费·DRY）。
 */
internal object WorldCardInfo {

    fun buildSegments(joined: Int, pending: Int, pet: CharacterPetEntity?, eggHatchable: Boolean = false): List<InfoSegment> = buildList {
        if (joined > 0) add(InfoSegment.Around(joined))
        if (pending > 0) add(InfoSegment.Pending(pending))
        val kind = pet?.let { petNeedKind(it) }
        // 单可点段（§3/§5 E9）：needs-attention 宠物优先；无饿宠才让位「蛋要孵出来了」；两者绝不叠段。
        if (pet != null && kind != null) add(InfoSegment.PetNeeds(pet.name, kind))
        else if (eggHatchable) add(InfoSegment.EggHatchable)
    }

    /** 宠物态 → 「需要你」段类（HAPPY/CONTENT → null 不出段）。判定**不在本文件**，一律经 [petStatusKind] 单源。 */
    private fun petNeedKind(pet: CharacterPetEntity): PetNeedKind? = when (petStatusKind(pet)) {
        PetStatusKind.RAN_AWAY -> PetNeedKind.RAN_AWAY
        PetStatusKind.SICK -> PetNeedKind.SICK
        PetStatusKind.HUNGRY -> PetNeedKind.HUNGRY
        PetStatusKind.SAD -> PetNeedKind.SAD
        PetStatusKind.HAPPY, PetStatusKind.CONTENT -> null
    }
}
