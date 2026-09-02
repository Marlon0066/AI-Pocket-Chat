package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import kotlin.math.ceil
import kotlin.math.floor

// MARK: - 成长纯数学（活人感内核卷三 §3.0 · 从 GrowthAnalysisCoordinator 只搬不改拆出）
//
// 本文件是成长系统「纯数学段」的家：软上限 [scaledDelta] + 关系淡化 [computeDecayedQuality] + 维度跷跷板
// [applyDimensionInterplay] + 张力泄压 [applyTensionRelief] + 平衡点 [equilibriumPoint]。五者原先是协调器文件末尾的
// 同包顶层函数，卷三 chunk 0 按卷二 R1-5 强制前置**只搬不改**迁来（函数体/KDoc/可见性字节级不变，调用方零改动）。

// MARK: - 纯数学（internal，便于单测；对齐 iOS GrowthAnalysisCoordinator 的 static 方法）

/**
 * 根据当前维度值缩放 LLM 给出的 delta，实现「高段位增长慢、跌落快」的软上限。缩放后绝对值至少为 1。
 * 性格/关系/兴趣热度共用。
 */
internal fun scaledDelta(current: Int, rawDelta: Int): Int {
    if (rawDelta == 0) return 0
    val scale: Double = if (rawDelta > 0) {
        when {
            current >= 80 -> 0.3
            current >= 60 -> 0.6
            current < 20 -> 1.5  // 低端加速恢复
            else -> 1.0
        }
    } else {
        when {
            current >= 80 -> 2.0
            current >= 60 -> 1.5
            current < 20 -> 0.5  // 低端保护
            else -> 1.0
        }
    }
    val scaled = rawDelta * scale
    return if (scaled > 0) maxOf(1, ceil(scaled).toInt()) else minOf(-1, floor(scaled).toInt())
}

/**
 * 关系淡化的纯计算（图纸 D-6·T2-3 直测）：4 衰减维（熟悉/亲近/默契/趣味）startDay=3 + 张力 startDay=7；
 * **「界内才衰、到界停、界外不动」守卫**（V-b：修旧 `maxOf(floor, current-days)` 把手调界外低值抬回地板的反向涨分缺陷）；
 * 地板 = 识别出原型时 `max(该维原型地板, 5)`，未识别时 legacy [dynamicFloor]（张力恒固定地板 5·独立不吃原型）；
 * 依恋规则不变（3–7 天想念 +1/天、>7 天 −1/天至 5）；trust/respect 恒不衰（不在衰减维内）。
 */
internal fun computeDecayedQuality(
    quality: RelationshipQuality,
    inactiveDays: Int,
    newDecayDays: Int,
    dynamicFloor: Int,
    archetype: RelationshipArchetype?,
): RelationshipQuality {
    var q = quality
    val keys = RelationshipQuality.DIMENSION_KEYS
    fun decayDim(dim: String, startDay: Int, floor: Int) {
        if (inactiveDays <= startDay) return
        val i = keys.indexOf(dim)
        val current = q.values[i]
        if (current <= floor) return // 到界停 / 界外不动（不抬回地板）
        q = q.setValue(i, maxOf(floor, current - newDecayDays))
    }
    for (dim in listOf("familiarity", "closeness", "rapport", "fun")) {
        val floor = if (archetype != null) maxOf(archetype.floors[keys.indexOf(dim)], 5) else dynamicFloor
        decayDim(dim, startDay = 3, floor = floor)
    }
    decayDim("tension", startDay = 7, floor = 5)
    // 依恋特殊（规则不变）：3–7 天想念累加，>7 天淡化至 5。
    val ai = keys.indexOf("attachment")
    val cur = q.values[ai]
    val newAtt = if (inactiveDays <= 7) minOf(cur + newDecayDays, 100) else maxOf(5, cur - newDecayDays)
    if (newAtt != cur) q = q.setValue(ai, newAtt)
    return q
}

/**
 * 维度跷跷板（顺序求值：后规则看见前规则的修改，对齐 iOS）。**纯函数**（零类状态），
 * 卷零 chunk2 从类内私有搬到纯数学段并设 internal——原本零测试覆盖（图纸 Z-F5），
 * H1/H2 两处封顶必须可直接单测（`DimensionInterplayCapTest`）。
 *
 * **卷三 §3.3 两处改动**（图纸 K-1 / R1-4）：① 四条规则的推力从 [scaledDelta]`(cur, ±1)` 改为 [saturate]`(±1.0)` ——
 * 恒 ±1、无 `<20` 档的 1.5× 低端加速（那正是张力不动点 19 的根源：`ceil(1.5)=2` 恰好抵消泄压 −2），
 * 也无 `fun ≥70` 档的 ×1.5/×2.0 放大；② 规则 3 的天花板改为尾参 [opennessCap]（协调器传 `anchor.openness + 20`，
 * 锚点列为空时仍传 [RelationshipBands.OPENNESS_INTERPLAY_CAP]·K-4）。其余一字不动。
 */
internal fun applyDimensionInterplay(
    quality: RelationshipQuality,
    spectrum: PersonalitySpectrum,
    opennessCap: Int = RelationshipBands.OPENNESS_INTERPLAY_CAP,
): Pair<RelationshipQuality, PersonalitySpectrum> {
    var q = quality
    var s = spectrum
    val rKeys = RelationshipQuality.DIMENSION_KEYS
    val pKeys = PersonalitySpectrum.DIMENSION_KEYS

    // 规则1：亲密压力——亲近+依恋均高 → 张力 +1（卷零 H1：到 CAP 即不再推，且推后再钳一层）
    if (q.closeness >= 75 && q.attachment >= 75 && q.tension < RelationshipBands.TENSION_INTERPLAY_CAP) {
        val idx = rKeys.indexOf("tension")
        if (idx >= 0) q = q.setValue(idx, minOf(RelationshipBands.TENSION_INTERPLAY_CAP, q.values[idx] + saturate(1.0)))
    }
    // 规则2：新鲜感衰退——熟悉度高+趣味性高 → 趣味性 -1
    if (q.familiarity >= 80 && q.funValue >= 70) {
        val idx = rKeys.indexOf("fun")
        if (idx >= 0) q = q.setValue(idx, q.values[idx] + saturate(-1.0))
    }
    // 规则3：信任-坦诚联动——信任高 → 坦诚度 +1（卷零 H2：到 CAP 即不再推，**界外高值只是不推、绝不下拉**）
    if (q.trust >= RelationshipBands.OPENNESS_INTERPLAY_TRUST_MIN && s.openness < opennessCap) {
        val idx = pKeys.indexOf("openness")
        if (idx >= 0) s = s.setValue(idx, minOf(opennessCap, s.values[idx] + saturate(1.0)))
    }
    // 规则4：高张力催化依恋——张力高 → 依恋 +1（相爱相杀）
    if (q.tension >= 60) {
        val idx = rKeys.indexOf("attachment")
        if (idx >= 0) q = q.setValue(idx, q.values[idx] + saturate(1.0))
    }
    return q to s
}

/**
 * 卷零止血 H3：每次成长分析后张力恒定回落（图纸 §3.2）。
 *
 * **为什么需要它**：[GrowthAnalysisCoordinator] 的关系淡化首行 `inactiveDays <= 3` 早返回 ⇒ 活跃用户
 * （≤3 天必聊）永远够不着淡化，而跷跷板规则1（亲密压力 → 张力）与规则4（张力 → 依恋）构成正反馈环
 * ⇒ 张力只涨不落、依恋被无源推高。本函数是活跃用户唯一的张力泄压口：与规则1 的
 * [RelationshipBands.TENSION_INTERPLAY_CAP] 封顶合起来，联动自身无法维持张力（严格单调下降），
 * 张力只能被真实事件顶上去。
 *
 * **有意不改 [computeDecayedQuality]**（图纸 Z-2：它被 GrowthDecayArchetypeFloorTest 8 例钉住，
 * 且淡化逻辑本身没有缺陷——缺陷是「活跃用户够不着它」）。
 */
internal fun applyTensionRelief(quality: RelationshipQuality): RelationshipQuality {
    val idx = RelationshipQuality.DIMENSION_KEYS.indexOf("tension")
    val current = quality.values[idx]
    if (current <= RelationshipBands.TENSION_RELIEF_FLOOR) return quality // 到界停 / 界外不动
    return quality.setValue(
        idx,
        maxOf(RelationshipBands.TENSION_RELIEF_FLOOR, current - RelationshipBands.TENSION_RELIEF_PER_ANALYSIS),
    )
}

/** 根据当前关系名称计算维度的自然平衡点（淡化时维度向此值回落而非跌到底）。关键词表与取值住 [RelationshipBands]。 */
internal fun equilibriumPoint(currentRelationship: String?): Int {
    val rel = currentRelationship?.lowercase() ?: return RelationshipBands.EQUILIBRIUM_DEFAULT_VALUE
    if (RelationshipBands.EQUILIBRIUM_INTIMATE.any { rel.contains(it) }) return RelationshipBands.EQUILIBRIUM_INTIMATE_VALUE
    if (RelationshipBands.EQUILIBRIUM_CLOSE.any { rel.contains(it) }) return RelationshipBands.EQUILIBRIUM_CLOSE_VALUE
    if (RelationshipBands.EQUILIBRIUM_FRIEND.any { rel.contains(it) }) return RelationshipBands.EQUILIBRIUM_FRIEND_VALUE
    if (RelationshipBands.EQUILIBRIUM_DISTANT.any { rel.contains(it) }) return RelationshipBands.EQUILIBRIUM_DISTANT_VALUE
    return RelationshipBands.EQUILIBRIUM_DEFAULT_VALUE
}
