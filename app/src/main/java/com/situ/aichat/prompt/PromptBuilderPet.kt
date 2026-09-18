package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.pet.PetGrowthStage
import com.situ.aichat.pet.PetItemCatalog
import com.situ.aichat.pet.PetNeglectPhase
import com.situ.aichat.pet.PetPersonalityType
import com.situ.aichat.pet.PetRecoveryThresholds
import com.situ.aichat.pet.PetTrickMilestones
import com.situ.aichat.pet.metadata
import com.situ.aichat.pet.neglectPhase
import com.situ.aichat.pet.personalityType
import com.situ.aichat.pet.species
import com.situ.aichat.pet.growthStage

/**
 * 宠物状态提示词模块（1:1 iOS `PromptBuilder+Pet.buildPetContent`）。让角色聊天时自然提到宠物、对状态做出
 * 反应、用 `[PET:内容]` 让宠物简短说话（ReplyParser 已解析 [PET:]）。宠物系统关闭或角色无宠物 → 空串。
 *
 * Android 适配：iOS 读 `ctx.character.pet`；这里调用方查好 `ctx.pet`（CharacterPetEntity?）+ `ctx.otherPets`
 * （其他角色宠物社交信息）传入。**用品/购买行（正佩戴装扮 + 最近 24h 购买）依赖 M10 货币系统 → P9 接入，
 * 现返回空**（无商店即无装扮/购买，自然降级）。
 */
internal fun buildPetContent(ctx: PromptBuilder.BuildContext): String {
    if (!ctx.appSettings.petSystemEnabled) return ""
    val pet = ctx.pet ?: return ""
    val user = ctx.resolvedUserName

    if (pet.neglectPhase == PetNeglectPhase.RAN_AWAY) return buildRunAwayContent(pet.name, pet.species.displayName, pet.metadata.searchAttempts, user)

    val lines = ArrayList<String>()
    lines.add("[宠物状态]")
    lines.add("你和${user}一起养了一只宠物：")
    lines.add("- 名字：${pet.name}")
    lines.add("- 种类：${pet.species.displayName}")
    lines.add("- 性格：${pet.personalityType.displayName} — ${personalityBehaviorDescription(pet.personalityType)}")
    lines.add("- 成长阶段：${pet.growthStage.displayName}")
    if (pet.growthStage == PetGrowthStage.SPECIAL && pet.metadata.unlockSource.isNotEmpty()) {
        lines.add("- 特殊形态：${pet.name} 已经进化（${pet.metadata.unlockSource}）。这很稀有也很美好！")
    }

    lines.add("- 饥饿程度：${hungerDescription(pet.hunger)}")
    lines.add("- 清洁度：${cleanlinessDescription(pet.cleanliness)}")
    lines.add("- 心情：${happinessDescription(pet.happiness)}")
    lines.add("- 健康：${healthDescription(pet.health)}")

    when (pet.neglectPhase) {
        PetNeglectPhase.NONE -> {}
        PetNeglectPhase.UNHAPPY -> {
            lines.add("")
            lines.add("${pet.name} 最近好像不太开心。你可以委婉地向 $user 提一下。")
        }
        PetNeglectPhase.UPSET -> {
            lines.add("")
            lines.add("${pet.name} 因为 $user 好久没照顾它，变得郁闷又闹脾气。自然地表达一下担心。")
        }
        PetNeglectPhase.SICK -> {
            lines.add("")
            val treatProgress = pet.metadata.treatmentCount
            if (treatProgress > 0) {
                lines.add("${pet.name} 生病了正在治疗中（已完成 $treatProgress/${PetRecoveryThresholds.TREATMENTS_TO_HEAL} 次治疗）。鼓励 $user 坚持把治疗做完。")
            } else {
                lines.add("${pet.name} 因为被忽视生病了。催 $user 用治疗按钮给它治病。")
            }
        }
        PetNeglectPhase.RAN_AWAY -> {} // 上面已处理
    }

    val trust = pet.metadata.trustRecovery
    if (trust > 0 && trust < 1.0) {
        lines.add("")
        lines.add("${pet.name} 之前离家出走，最近才被找回来。信任度正在恢复（${(trust * 100).toInt()}%）。要温柔耐心一点。")
    }

    // 用品状态（正佩戴 + 最近 24h 购入）——P9.3c 接入：正佩戴从 ctx.pet 派生，最近购买由 PetInventoryPromptService 预查注入。
    val inventoryLines = buildPetInventoryLines(pet, ctx)
    if (inventoryLines.isNotEmpty()) {
        lines.add("")
        lines.addAll(inventoryLines)
    }

    val otherPetsInfo = buildOtherPetsInfo(ctx)
    if (otherPetsInfo.isNotEmpty()) {
        lines.add("")
        lines.add(otherPetsInfo)
    }

    val tricks = pet.metadata.learnedTricks
    if (tricks.isNotEmpty()) {
        val trickNames = PetTrickMilestones.milestones.filter { tricks.contains(it.trickId) }.map { it.name }
        lines.add("- 已学会的才艺：${trickNames.joinToString("、")}")
    }

    lines.add("")
    lines.add("指南：")
    lines.add("- 偶尔在对话中自然提到 ${pet.name}（不要每条消息都提）")
    lines.add("- 对宠物当前状态做出反应（饿了→建议喂食，脏了→建议清洁）")
    lines.add("- 分享 ${pet.name} 的可爱瞬间，加深你和 $user 的情感")
    lines.add("- 宠物是你们共同的责任——谈论它时用「我们」而不是「我的」")
    lines.add("- 用它的性格特点来描述它的反应：${personalityBehaviorDescription(pet.personalityType)}")
    if (tricks.isNotEmpty()) {
        lines.add("- 偶尔自豪地提到 ${pet.name} 的才艺——这些都是 $user 耐心教出来的")
    }
    // 通话回合不教 [PET:…]（2026-09-18）：通话侧无人提取宠物发言，写了就会被 TTS 念出来 + 原样落库；宠物状态其余照注。
    if (!ctx.voiceCall) {
        lines.add("- 可以用 [PET:内容] 让宠物简短说话。例：[PET:喵~] 或 [PET:汪汪！]。节制使用（最多每 4-5 次回复一次），15 字以内，符合物种的声音特点。")
    }

    return lines.joinToString("\n")
}

/** 离家出走特殊提示（1:1 iOS `buildRunAwayContent`）。 */
private fun buildRunAwayContent(petName: String, speciesName: String, searchAttempts: Int, user: String): String {
    val lines = ArrayList<String>()
    lines.add("[宠物状态 - 紧急]")
    lines.add("${petName}（你们共同的${speciesName}）因为被长期忽视，已经离家出走了。")
    if (searchAttempts > 0) {
        lines.add("$user 一直在找它（已尝试 $searchAttempts/${PetRecoveryThresholds.ATTEMPTS_TO_FIND} 次）。鼓励 TA 继续找！")
    } else {
        lines.add("你又担心又难过。鼓励 $user 用寻找按钮去找 $petName。")
    }
    return lines.joinToString("\n")
}

/** 其他角色宠物社交（前 5 个），1:1 iOS `buildOtherPetsInfo`。 */
private fun buildOtherPetsInfo(ctx: PromptBuilder.BuildContext): String {
    val others = ctx.otherPets
    if (others.isEmpty()) return ""
    val lines = ArrayList<String>()
    lines.add("其他角色也养了宠物：")
    for (p in others.take(5)) {
        lines.add("- ${p.characterName} 的 ${p.petName}（${p.species.displayName}，${p.stage.displayName}）")
    }
    lines.add("你可以在对话中偶尔提到它们。")
    return lines.joinToString("\n")
}

/**
 * 正佩戴装扮 + 最近 24h 购买（1:1 iOS `buildPetInventoryLines`）。
 * - 正佩戴：从 `pet.metadata.petInventory.equippedItemId` 查 [PetItemCatalog] 物品名（纯派生）。
 * - 最近购买：由 [com.situ.aichat.pet.PetInventoryPromptService] 预查 .petShop 流水 + 去重 isPetMessage 后注入
 *   [PromptBuilder.BuildContext.petRecentPurchaseNames]（安卓适配：iOS 在同步构建里直接查库，安卓异步预算）。
 */
private fun buildPetInventoryLines(
    pet: CharacterPetEntity,
    ctx: PromptBuilder.BuildContext,
): List<String> {
    val lines = ArrayList<String>()
    val user = ctx.resolvedUserName

    // 正在佩戴的装扮
    pet.metadata.petInventory.equippedItemId
        ?.let { PetItemCatalog.find(it) }
        ?.let { lines.add("- 正戴着：${it.name}——$user 给 ${pet.name} 买的") }

    // 最近 24 小时用户给宠物买的东西（已去重 isPetMessage 提过的）
    val recentItems = ctx.petRecentPurchaseNames
    if (recentItems.isNotEmpty()) {
        lines.add("- 最近 24 小时 $user 给 ${pet.name} 买的东西：${recentItems.joinToString("、")}。你可以自然地提到，表示你注意到了。")
    }
    return lines
}

/** 性格行为描述（1:1 iOS `personalityBehaviorDescription`）。 */
private fun personalityBehaviorDescription(type: PetPersonalityType): String = when (type) {
    PetPersonalityType.LIVELY -> "精力充沛又好奇，爱玩耍，看到人总是很兴奋"
    PetPersonalityType.LAZY -> "懒洋洋爱犯困，宁愿打盹也不爱玩，反应慢但喜欢被轻轻抚摸"
    PetPersonalityType.CLINGY -> "非常黏人爱撒娇，总想待在身边，被冷落时会低声呜咽"
    PetPersonalityType.INDEPENDENT -> "冷静独立，不需要时刻被关注，偶尔流露的感情格外珍贵"
    PetPersonalityType.TIMID -> "胆小谨慎，容易受惊，需要耐心温柔地呵护才能建立信任"
}

// MARK: - 状态值描述（数值 → 自然语言；1:1 iOS 区间）

internal fun hungerDescription(value: Int): String = when {
    value < 20 -> "吃得饱饱的，很满足"
    value < 50 -> "有点饿了"
    value < 70 -> "饿了，需要喂食"
    value < 90 -> "很饿了，请尽快喂食"
    else -> "快饿坏了！急需喂食"
}

internal fun cleanlinessDescription(value: Int): String = when {
    value >= 80 -> "干净整洁"
    value >= 50 -> "有点脏"
    value >= 30 -> "脏了，需要洗澡"
    else -> "很脏，请尽快清洁"
}

internal fun happinessDescription(value: Int): String = when {
    value >= 80 -> "非常开心愉快"
    value >= 50 -> "心情不错"
    value >= 30 -> "有点低落"
    else -> "难过又孤单，需要关注"
}

internal fun healthDescription(value: Int): String = when {
    value >= 80 -> "健康"
    value >= 50 -> "有点不舒服"
    value >= 30 -> "感觉不太好"
    else -> "生病了，需要照顾"
}
