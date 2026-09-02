package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.applyPressureDelta
import com.situ.aichat.data.model.setNetKeepingNeg
import com.situ.aichat.data.model.toQuality

// MARK: - 关系双压投影（活人感内核卷三 §3.0 · 从 GrowthAnalysisCoordinator 只搬不改拆出）
//
// 本文件只住卷二四步 [applyRelationshipChanges]：LLM 分开报的两股力入账 → 软上限作用在净额差 → 校正只动 pos。
// 原为 GrowthAnalysisCoordinator 的类内 internal 方法，卷三 chunk 0 按卷二 R1-5 的强制前置动作**只搬不改**
// 搬成同包顶层函数（函数体与 KDoc 逐字不动，仅去掉一层类体缩进；协调器调用点同包解析、无需改字）。

// internal（非 private）：图纸 §7.2 T2-4 明令验「落库净额与旧净额实现逐值相同」，而 Kotlin private 对同模块
// 测试不可见 ⇒ 提可见性是该要求的必然推论（卷零 D-1 先例）。函数体不因测试而改一个字。
internal fun applyRelationshipChanges(
    changes: Map<String, GrowthAnalysisResult.PressureDelta>,
    quality: RelationshipQuality,
    pressure: RelationshipPressure,
): Pair<RelationshipQuality, RelationshipPressure> {
    if (changes.isEmpty()) return quality to pressure
    var q = quality
    var p = pressure
    val keys = RelationshipQuality.DIMENSION_KEYS
    for ((key, delta) in changes) {
        val index = keys.indexOf(key)
        if (index < 0) continue
        val current = q.values[index]
        // ① 两股力各自入账（这是双压唯一的信息源：LLM 分开报的那两个数）。
        p = p.applyPressureDelta(index, delta.pos, delta.neg)
        // ②③ 软上限**作用在净额差**上，不作用在压强（图纸 P-7）——「高段位涨得慢」的既有手感
        //     因此一个字节不变，GrowthMathTest 保持绿。
        val target = p.toQuality().values[index]
        val softened = current + scaledDelta(current, target - current)
        // ④ 把软化后的净额校正回压强（净额单向派生 · 图纸 §9.4）。
        //    R1 复核 O-1：校正**只动 pos**——软上限是系统侧调整，不是角色身上的力，打掉的涨幅
        //    不许记成负压（否则长期高分关系会凭空攒出矛盾）。neg 恒 = LLM 这次报的那个数。
        p = p.setNetKeepingNeg(index, softened)
        q = q.setValue(index, p.toQuality().values[index])
    }
    return q to p
}
