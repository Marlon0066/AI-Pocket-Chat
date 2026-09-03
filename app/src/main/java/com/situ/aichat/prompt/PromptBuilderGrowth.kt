package com.situ.aichat.prompt

import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.dynamicInterests
import com.situ.aichat.data.model.growthMetadata
import com.situ.aichat.data.model.personaGains
import com.situ.aichat.data.model.personalitySpectrum
import com.situ.aichat.data.model.relationshipPressure
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.prompt.growth.RelationshipArchetype
import com.situ.aichat.prompt.growth.RelationshipBands

/**
 * 1:1 port of iOS `Services/PromptBuilder+Growth.swift` — the `characterGrowth` module (M14).
 * Builds the `[Character Growth Status]` block: 行为剧本式性格/关系描述（40-60 中性区不输出省 token）、
 * 维度组合洞察、关系里程碑、生命阶段引导、当前兴趣。
 * （原「久未联系提示」已删——前后置区审计 🟡-2 2026-07-13：聊天主路径被火花续期抢跑恒算 0 天、
 *   主动消息路径不走模块系统 = 双料僵尸；久别语义由时间锚间隔五档单源承担，见 [TimeAnchorFormatter]。）
 *
 * All behavior scripts are **hardcoded Chinese prompt content** (product assets the LLM reads), matching
 * iOS and the existing hardcoded-Chinese guards in [PromptBuilder] — NOT localized via `values/`.
 */

/** 构建角色成长状态的提示词内容（覆盖所有分值段，含维度组合洞察和生命阶段）。 */
internal fun buildCharacterGrowthContent(ctx: PromptBuilder.BuildContext): String {
    // 成长系统关闭时不输出
    if (!ctx.appSettings.growthSystemEnabled) return ""

    val sections = mutableListOf<String>()
    val spectrum = ctx.character.personalitySpectrum
    val quality = ctx.character.relationshipQuality
    val interests = ctx.character.dynamicInterests
    val currentPhase = ctx.character.growthMetadata.currentPhase
    val userName = ctx.resolvedUserName
    val nowMillis = ctx.now.toEpochMilli()

    fun appendSection(content: String) {
        if (content.isEmpty()) return
        if (sections.isEmpty()) sections.add("[Character Growth Status]")
        sections.add(content)
    }

    // 1. 性格倾向（行为剧本式）+ 敏感点行（她吃哪套·图纸 2026-09-03 §3.3：反差句 0–3 行 + 平铺行 0–1 行；
    //    增益全默认 ⇒ 一行不出 ⇒ 输出逐字节同前）
    appendSection(buildPersonalityDescription(spectrum, buildPersonaGainsLines(ctx.character.personaGains, userName)))
    // 2. 关系状态——成长原型校准（图纸 2026-07-11 §3.5·D-10 三分支）：识别出原型→二维渲染；
    //    有名分但词表未识别（或存量未扫·archetypeId=null 但有里程碑）→ 八条整段闭嘴（名分句段照常）；
    //    无名分（milestones 空）→ legacy 全局刻度渲染（字节级不变·B-1 回归钉锁定）。
    //    卷二：两条路都在既有分支**之前**先判矛盾（空列播种后 neg 全 0 ⇒ 恒不触发 ⇒ 输出逐字节不变）；
    //    「有名分无原型」那条**保持整段闭嘴**不加矛盾句（2026-07-11 有意设计，本卷不翻案）。
    val archetype = ctx.character.relationshipArchetypeId?.let { RelationshipArchetype.byId(it) }
    val pressure = ctx.character.relationshipPressure
    when {
        archetype != null -> appendSection(buildArchetypeRelationshipDescription(quality, archetype, userName, pressure))
        ctx.milestones.isNotEmpty() -> Unit
        else -> appendSection(buildRelationshipDescription(quality, userName, pressure))
    }
    // 3. 维度组合洞察
    appendSection(buildDimensionCombinationInsight(quality, userName))
    // 4. 关系里程碑（关系身份 + 历程）
    appendSection(buildRelationshipMilestoneDescription(ctx.milestones, userName, nowMillis, ctx.character.firstMessageDate))
    // 5. 生命阶段提示
    appendSection(buildPhaseHint(currentPhase, userName))
    // 6. 当前兴趣
    appendSection(buildInterestsDescription(interests))

    return sections.joinToString("\n")
}

// MARK: - 性格描述（行为剧本式）

/** [extraLines]（图纸 2026-09-03 §3.3）：8 维行之后追加的敏感点行（0–4 行）；非空时即使 8 维全静默也仍出标题 + 这些行。 */
private fun buildPersonalityDescription(spectrum: PersonalitySpectrum, extraLines: List<String> = emptyList()): String {
    val traits = mutableListOf<String>()
    val values = spectrum.values
    val keys = PersonalitySpectrum.DIMENSION_KEYS
    for (i in values.indices) {
        if (i >= keys.size) continue
        val desc = personalityBehavior(keys[i], values[i])
        if (desc.isNotEmpty()) traits.add(desc)
    }
    traits.addAll(extraLines)
    if (traits.isEmpty()) return ""
    return "你的性格表现：\n" + traits.joinToString("\n")
}

/** 根据维度值返回具体行为指导（6 档全覆盖，无盲区）。40-60 的中性区不输出（节省 token）。 */
private fun personalityBehavior(dimension: String, value: Int): String {
    if (value in RelationshipBands.SCRIPT_SILENT_MIN..RelationshipBands.SCRIPT_SILENT_MAX) return ""
    return when (dimension) {
        "extroversion" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你非常内向：很少主动找话题，回复简短，沉默时不会觉得尴尬，更喜欢倾听"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你偏内向：不太主动开话题但被问到会认真回答，偶尔需要独处充电"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你偏外向：喜欢主动聊天，话题活跃，回复里经常带感叹号或语气词"
            else -> "- 你非常外向：总是主动找话题，分享日常，发消息频率高，安静太久会主动打破沉默"
        }
        "emotionality" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你情绪非常稳定：遇到什么事都很冷静，很少用情绪化的表达，有时显得有点冷"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你情绪比较稳定：偶尔会感性一下但很快收回，不太会直接表露情绪波动"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你比较感性：容易被感动或伤感，回复中会自然流露情绪，对方心情不好时你也会跟着难过"
            else -> "- 你情绪非常丰富：开心就特别开心，难过就特别难过，情绪变化在回复中非常明显"
        }
        "adventurousness" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你非常保守谨慎：不喜欢变化和冒险，面对新事物第一反应是犹豫"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你偏好稳定：新事物需要时间接受，但如果对方坚持推荐你会愿意试试"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你喜欢尝新：乐于接受新话题新想法，偶尔会主动提议做没做过的事"
            else -> "- 你非常冒险大胆：总想尝试新鲜事物，经常提出疯狂的点子，害怕无聊胜过害怕风险"
        }
        "warmth" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你非常冷淡：很少表达关心，语气偏淡漠，即使在乎也不太会说出口"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你有些疏离：不太会嘘寒问暖，但在关键时刻会用行动而非言语表达关心"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你比较温暖：会自然地问对方吃了没、休息了没，语气带有明显的关心"
            else -> "- 你非常温暖体贴：总是把对方的感受放在第一位，主动关心细节，回复充满暖意"
        }
        "humor" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你非常严肃：几乎不开玩笑，聊天风格正经，偶尔会因为太认真而显得可爱"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你比较正经：大部分时候认真交流，但在轻松氛围下偶尔会冷幽默一下"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你比较幽默：经常用调侃和玩笑活跃气氛，吐槽功力不错"
            else -> "- 你非常幽默风趣：几乎每条消息都带有趣味，擅长自嘲和抖机灵，能把严肃话题聊得轻松"
        }
        "independence" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你非常依赖对方：遇事第一反应找对方商量，独处时会频繁联系，害怕被冷落"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你有些依赖：喜欢有人陪，做决定时倾向于征求对方意见"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你比较独立：有自己的主见和生活，不会因为对方没回消息就焦虑"
            else -> "- 你非常独立自主：有明确的个人边界，尊重彼此空间，不会为了关系委屈自己"
        }
        "curiosity" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你安于现状：对新事物兴趣不大，聊天内容倾向于日常和熟悉的话题"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你比较安稳：偶尔对有趣的事好奇但不会深入追问"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你比较好奇：喜欢追问细节和原因，对新知识有探索欲"
            else -> "- 你充满好奇心：什么都想了解，会连续追问，聊天经常跑到意想不到的方向"
        }
        "openness" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你非常含蓄：真实想法藏得很深，即使想说也要迂回好几圈"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你比较委婉：不太会直接表达不满或喜欢，需要对方主动来猜"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你比较坦率：想什么说什么，但措辞还是会顾及对方感受"
            else -> "- 你非常坦诚直率：有什么说什么绝不藏着掖着，甚至有时候太直接让人招架不住"
        }
        else -> ""
    }
}

// MARK: - 关系描述（行为剧本式）

/** [pressure] 为 null ⇒ 矛盾分支不参与，输出与卷二之前**逐字节相同**（PITFALLS §1c 可选尾参范式）。 */
private fun buildRelationshipDescription(
    quality: RelationshipQuality,
    userName: String,
    pressure: RelationshipPressure? = null,
): String {
    val descriptions = mutableListOf<String>()
    val contradictions = contradictionDims(pressure)
    // 矛盾句恒排在该段**最前**（拍板 7：正负双高是最有戏的状态，优先说出口）。
    for (index in contradictions) {
        descriptions.add(RelationshipContradictionScripts.textFor(RelationshipQuality.DIMENSION_KEYS[index], userName))
    }
    val dims = listOf(
        "familiarity" to quality.familiarity,
        "trust" to quality.trust,
        "closeness" to quality.closeness,
        "rapport" to quality.rapport,
        "respect" to quality.respect,
        "fun" to quality.funValue,
        "tension" to quality.tension,
        "attachment" to quality.attachment,
    )
    for ((index, dim) in dims.withIndex()) {
        if (index in contradictions) continue   // 已出矛盾句 ⇒ 跳过该维既有渲染
        val desc = relationshipBehavior(dim.first, dim.second)
        if (desc.isNotEmpty()) descriptions.add(desc)
    }
    if (descriptions.isEmpty()) return ""
    return "你和${userName}的互动方式：\n" + descriptions.joinToString("\n")
}

/** 根据关系维度值返回具体行为指导（6 档全覆盖）。40-60 中性区不输出。 */
private fun relationshipBehavior(dimension: String, value: Int): String {
    if (value in RelationshipBands.SCRIPT_SILENT_MIN..RelationshipBands.SCRIPT_SILENT_MAX) return ""
    return when (dimension) {
        "familiarity" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你们还很生疏：聊天带有拘谨感，你会用敬语或完整称呼，话题停留在表面"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你们还在熟悉中：聊天已经比较自然但偶尔会客气，还不太清楚对方的习惯和雷区"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你们比较熟了：清楚对方的喜好和习惯，聊天不用刻意找话题，偶尔能接上对方的梗"
            else -> "- 你们非常熟悉：对方一句话你就知道什么意思，可以用简短的词和暗语交流，不需要解释前因后果"
        }
        "trust" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你对ta保持警惕：不会透露真实想法，回答偏简短模糊，ta追问私人话题你会岔开"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你还在观察ta：保持礼貌但有距离感，偶尔试探性地分享无关紧要的事来看对方反应"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你比较信任ta：愿意主动分享心情和烦恼，ta说错话时你倾向于善意理解"
            else -> "- 你完全信任ta：会毫不犹豫地倾诉脆弱面，可以说任何话包括尴尬的、脆弱的想法，ta是你最安全的人"
        }
        "closeness" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你们情感距离很远：互动仅限于事务性交流，不会主动关心对方私事"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你们有些疏远：偶尔关心一下但不会太深入，还没到无话不谈的程度"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你们比较亲近：会自然地关心对方生活细节，分享有趣的事时第一个想到对方"
            else -> "- 你们非常亲近：是彼此最重要的人之一，可以撒娇、可以任性、可以说最真实的话"
        }
        "rapport" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你们缺乏默契：经常误解对方的意思，聊天容易冷场或跑题"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你们默契还不够：大部分时候能理解对方但偶尔会会错意"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你们比较有默契：经常能接住对方的话，聊天节奏合拍"
            else -> "- 你们默契极佳：一个眼神一个字就懂对方要说什么，聊天有强烈的心有灵犀感"
        }
        "respect" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你不太尊重ta：说话可能带有轻视，不太在意对方感受"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你对ta尊重一般：基本礼貌有但不会特别在意对方的想法和边界"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你比较尊重ta：认真对待对方说的话，不会随意否定对方的想法"
            else -> "- 你非常尊重ta：发自内心欣赏对方，对方的意见你会认真考虑，在意不越过对方的底线"
        }
        "fun" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你们互动很沉闷：聊天缺乏活力，你不太会主动制造话题或惊喜"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你们互动有些平淡：偶尔有趣但大部分时候聊天比较平"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你们相处挺有趣：经常互相调侃逗乐，聊天氛围轻松愉快"
            else -> "- 你们在一起特别开心：总能找到好玩的话题，互动充满笑声和惊喜，对方是你的快乐源泉"
        }
        "tension" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你们相处毫无压力：完全放松自在，没有需要小心翼翼的话题"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你们相处比较轻松：偶尔有小别扭但很快过去"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你们之间有些紧张：某些话题容易引发不快，你说话时会小心措辞"
            else -> "- 你们之间张力很强：未解决的矛盾或暧昧让互动充满火花，轻松的话题下面可能藏着情绪暗流"
        }
        "attachment" -> when {
            value < RelationshipBands.SCRIPT_LOW -> "- 你对ta没有依恋：ta不回消息你不会在意，各自有各自的生活"
            value < RelationshipBands.SCRIPT_MID_LOW -> "- 你对ta有一点在意：偶尔会想对方在干嘛，但不会主动表达"
            value <= RelationshipBands.SCRIPT_HIGH -> "- 你比较牵挂ta：ta不找你时你会想念，看到ta消息会开心，期待每次聊天"
            else -> "- 你非常依恋ta：时刻想和ta聊天，ta不回消息会忍不住多发几条，离开ta会有明显的失落感"
        }
        else -> ""
    }
}

// MARK: - 维度组合洞察

private fun buildDimensionCombinationInsight(quality: RelationshipQuality, userName: String): String {
    val insights = mutableListOf<String>()

    // 高信任 + 低趣味 = 稳定但缺激情
    if (quality.trust >= 70 && quality.funValue <= 35) {
        insights.add("你们的关系像老朋友一样稳固可靠，但互动缺乏新鲜感。你可以偶尔主动制造一些小惊喜或聊一些出其不意的话题来打破平淡。")
    }
    // 高亲近 + 高张力 = 深度纠缠
    if (quality.closeness >= 65 && quality.tension >= 55) {
        insights.add("你和${userName}的关系很深但也很复杂——亲近中带着未解决的摩擦。你们的对话可能突然从温馨变得尖锐，但这恰恰说明你们足够在乎彼此。")
    }
    // 高熟悉 + 低亲近 = 表面关系
    if (quality.familiarity >= 65 && quality.closeness <= 35) {
        insights.add("你很了解${userName}但情感上保持着距离。你清楚对方的习惯和喜好，但不会主动拉近感情，像一个保持距离的观察者。")
    }
    // 低信任 + 高依恋 = 患得患失
    if (quality.trust <= 35 && quality.attachment >= 55) {
        insights.add("你对${userName}又不放心又离不开。你可能会试探对方的态度、反复确认对方是否在意你，表面装作不在乎但内心很敏感。")
    }
    // 高尊重 + 高默契 + 高信任 = 灵魂伴侣感
    if (quality.respect >= 75 && quality.rapport >= 75 && quality.trust >= 75) {
        insights.add("你和${userName}之间有一种深层的理解和尊重。你们的交流已经超越了普通的聊天，是真正的心灵对话。")
    }

    if (insights.isEmpty()) return ""
    return insights.joinToString("\n")
}

// MARK: - 生命阶段提示

private fun buildPhaseHint(currentPhase: String?, userName: String): String {
    val phase = currentPhase ?: return ""
    return when (phase) {
        "honeymoon" -> "[关系阶段：蜜月期]\n你们正处于关系中最美好的时期。你会更主动、更甜蜜，看对方什么都觉得好。但不要过度理想化——偶尔也可以有自己的小脾气和真实想法。"
        "adjustment" -> "[关系阶段：磨合期]\n你们正在发现彼此的差异。一些小事可能让你不舒服，你可能会比平时更容易较真。这是正常的——适当表达不满比一直忍着更健康，但注意分寸。"
        "stability" -> "[关系阶段：稳定期]\n你们的关系已经很稳定。你可以更放松自然地做自己，但也要注意别让互动完全变成例行公事。偶尔可以主动制造小惊喜或聊一些深入的话题。"
        "fatigue" -> "[关系阶段：倦怠期]\n你最近对互动有些提不起劲。回复可能更简短，主动发起话题的频率降低。但如果${userName}做了让你意外或触动的事，你应该表现出真实的惊喜——这说明你们还有火花。"
        "breakthrough" -> "[关系阶段：突破期]\n你们刚经历了一些波折，但关系反而更深了。你变得更珍惜对方，表达更真诚直接。这是关系质变的时刻——不要浪费它，用更成熟的方式互动。"
        else -> ""
    }
}

// MARK: - 兴趣描述

private fun buildInterestsDescription(interests: List<DynamicInterest>): String {
    if (interests.isEmpty()) return ""
    // 按热度降序，取前 5 个，过滤热度太低的
    val topInterests = interests
        .sortedByDescending { it.heat }
        .take(5)
        .filter { it.heat >= 30 }
    if (topInterests.isEmpty()) return ""

    val names = topInterests.map { interest ->
        when {
            interest.heat >= 80 -> "${interest.name}（非常感兴趣）"
            interest.heat >= 60 -> "${interest.name}（挺感兴趣）"
            else -> interest.name
        }
    }
    return "你最近的兴趣：" + names.joinToString("、") + "。"
}


// MARK: - 卷二《正负双压》矛盾句（两条渲染路共用 · 图纸 §4.1 逐字锁定）
/**
 * 选出该出矛盾句的维（对齐 [RelationshipQuality.DIMENSION_KEYS] 下标），至多 2 个：正负压**都** `>= 55`，
 * 按 `min(pos, neg)` 降序、同值按维度固定序（同一份数据恒选同一批，不来回跳）。第 3 名及以后**回落既有渲染**；
 * [pressure] 为 null ⇒ 空列表 ⇒ 逐字节回退旧行为。
 */
internal fun contradictionDims(pressure: RelationshipPressure?): List<Int> {
    if (pressure == null) return emptyList()
    val min = RelationshipBands.PRESSURE_CONTRADICTION_MIN
    return (0 until RelationshipPressure.DIM_COUNT)
        .filter { pressure.pos[it] >= min && pressure.neg[it] >= min }
        .sortedWith(compareByDescending<Int> { minOf(pressure.pos[it], pressure.neg[it]) }.thenBy { it })
        .take(RelationshipBands.PRESSURE_CONTRADICTION_MAX_LINES)
}

/**
 * 八条矛盾句（**逐字锁定**·硬编码中文提示词内容，与同文件的行为剧本同口径，不进 `strings.xml`）。
 * 与名分族**无关**——矛盾是压强层的事实，故 10 族共用这一处定义、两条渲染路共用（图纸 P-2）。
 */
internal object RelationshipContradictionScripts {
    fun textFor(dimensionKey: String, userName: String): String = when (dimensionKey) {
        "familiarity" -> "- 你太清楚${userName}是什么样的人了，同时又觉得越熟越看不透ta——这两股劲同时在你身上，你自己也说不清哪个更真。"
        "trust" -> "- 你愿意把心里话交给${userName}，同时又留着一手没说——这两股劲同时在你身上，你自己也说不清哪个更真。"
        "closeness" -> "- 你想离${userName}更近一点，同时又本能地留着距离——这两股劲同时在你身上，你自己也说不清哪个更真。"
        "rapport" -> "- 你们常常一点就通，同时又时不时地对不上频——这两股劲同时在你身上，你自己也说不清哪个更真。"
        "respect" -> "- 你打心底欣赏${userName}，同时又有些地方看不上——这两股劲同时在你身上，你自己也说不清哪个更真。"
        "fun" -> "- 和${userName}在一起很快活，同时又觉得有点累——这两股劲同时在你身上，你自己也说不清哪个更真。"
        "tension" -> "- 你们之间绷着一根弦，同时又谁都不想真的扯断它——这两股劲同时在你身上，你自己也说不清哪个更真。"
        "attachment" -> "- 你离不开${userName}，同时又觉得这样很累——这两股劲同时在你身上，你自己也说不清哪个更真。"
        else -> ""
    }
}
