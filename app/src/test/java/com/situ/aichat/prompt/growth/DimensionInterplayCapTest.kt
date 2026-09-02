package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 维度跷跷板封顶与张力泄压的纯函数单测（活人感内核卷零 chunk2·补图纸 Z-F5 记录的「零测试覆盖」）。
 *
 * 断言值从图纸 §3.2 规格 + [scaledDelta] 的软上限规格**独立反推**，不照抄实现输出：
 * - 规则1 触发条件 = `closeness≥75 && attachment≥75`，卷零加闸 `tension < TENSION_INTERPLAY_CAP(60)`
 * - 规则3 触发条件 = `trust≥70`，卷零加闸 `openness < OPENNESS_INTERPLAY_CAP(70)`
 * - 规则4 触发条件 = `tension≥60`（**卷零不动**，图纸 Z-N3/§9.5）
 * - 泄压 = `tension>5` 时 `max(5, tension-2)`
 *
 * 各用例只点燃被测那一条规则：`familiarity=10 / fun=20` 恒关规则2；不测规则1 时压低
 * `closeness`；不测规则3 时压低 `trust`。
 */
class DimensionInterplayCapTest {

    private val neutralSpectrum = PersonalitySpectrum.NEUTRAL

    /** 亲密压力常燃的底子（规则1 前两个条件成立）；tension 由各用例指定。 */
    private fun intimateQuality(tension: Int, attachment: Int = 76) = RelationshipQuality(
        familiarity = 10, trust = 20, closeness = 80, rapport = 10,
        respect = 35, funValue = 20, tension = tension, attachment = attachment,
    )

    // MARK: - T1-1 规则1 封顶 / 规则4 触发线

    /** tension=59 < 60 → 规则1 点燃；59 属 20..59 档 ⇒ scaledDelta=+1 ⇒ 恰好抵达 CAP 60。 */
    @Test fun rule1_firesBelowCap_andLandsOnCap() {
        val (q, _) = applyDimensionInterplay(intimateQuality(tension = 59), neutralSpectrum)
        assertEquals(60, q.tension)
    }

    /** tension=60 已在 CAP ⇒ 规则1 不燃，张力一动不动。 */
    @Test fun rule1_doesNotFireAtCap() {
        val (q, _) = applyDimensionInterplay(intimateQuality(tension = 60), neutralSpectrum)
        assertEquals(60, q.tension)
    }

    /** tension=61 已越 CAP ⇒ 规则1 不燃，且**绝不下拉**（封顶只是停推）。 */
    @Test fun rule1_doesNotPullDownAboveCap() {
        val (q, _) = applyDimensionInterplay(intimateQuality(tension = 61), neutralSpectrum)
        assertEquals(61, q.tension)
    }

    /** 不变量：规则1 常燃条件下，任何起点跑一轮后张力都不得越过 CAP（起点本就界外者除外）。 */
    @Test fun rule1_neverPushesPastCap() {
        for (start in 0..59) {
            val (q, _) = applyDimensionInterplay(intimateQuality(tension = start), neutralSpectrum)
            assertTrue("起点 $start 推后 ${q.tension} 越过了 CAP", q.tension <= RelationshipBands.TENSION_INTERPLAY_CAP)
        }
    }

    /** 规则4 的触发线是 60（卷零零改）：59 不燃、60 燃。attachment=76 属 60..79 档 ⇒ +1。 */
    @Test fun rule4_firesAtSixtyNotFiftyNine() {
        // closeness=10 关掉规则1，令 tension 不被规则1 先改写
        val base = RelationshipQuality(
            familiarity = 10, trust = 20, closeness = 10, rapport = 10,
            respect = 35, funValue = 20, tension = 59, attachment = 76,
        )
        assertEquals(76, applyDimensionInterplay(base, neutralSpectrum).first.attachment)
        assertEquals(77, applyDimensionInterplay(base.copy(tension = 60), neutralSpectrum).first.attachment)
    }

    // MARK: - T1-2 张力恒定回落

    @Test fun tensionRelief_stopsAtFloorAndNeverUndershoots() {
        assertEquals(5, applyTensionRelief(intimateQuality(tension = 5)).tension)   // 已在地板：原样
        assertEquals(5, applyTensionRelief(intimateQuality(tension = 6)).tension)   // 差 1 不足回落量：钳到 5 不到 4
        assertEquals(5, applyTensionRelief(intimateQuality(tension = 7)).tension)   // 恰好落到地板
        assertEquals(58, applyTensionRelief(intimateQuality(tension = 60)).tension) // 常规：−2
    }

    /** 地板以下的手调界外值**不抬回地板**（与 [computeDecayedQuality] 的「界外不动」同口径）。 */
    @Test fun tensionRelief_leavesBelowFloorValuesUntouched() {
        assertEquals(3, applyTensionRelief(intimateQuality(tension = 3)).tension)
        assertEquals(0, applyTensionRelief(intimateQuality(tension = 0)).tension)
    }

    /** 泄压只碰张力一维，其余 7 维逐字节不变。 */
    @Test fun tensionRelief_touchesOnlyTension() {
        val before = intimateQuality(tension = 40)
        val after = applyTensionRelief(before)
        assertEquals(before.copy(tension = 38), after)
    }

    // MARK: - T1-3 规则3 天花板

    /** trust≥70 且 openness=69 < 70 → 点燃；69 属 60..79 档 ⇒ scaledDelta=+1 ⇒ 恰好抵达 CAP 70。 */
    @Test fun rule3_firesBelowCap_andLandsOnCap() {
        val q = RelationshipQuality(
            familiarity = 10, trust = 70, closeness = 10, rapport = 10,
            respect = 35, funValue = 20, tension = 5, attachment = 5,
        )
        val (_, s) = applyDimensionInterplay(q, PersonalitySpectrum(openness = 69))
        assertEquals(70, s.openness)
    }

    @Test fun rule3_doesNotFireAtCap() {
        val q = RelationshipQuality(trust = 70)
        val (_, s) = applyDimensionInterplay(q, PersonalitySpectrum(openness = 70))
        assertEquals(70, s.openness)
    }

    /** 用户手调到 85（> CAP）：规则3 **不推也不拉**，值恒 85（图纸 Z-1 负向锁）。 */
    @Test fun rule3_neverPullsDownAboveCap() {
        val q = RelationshipQuality(trust = 70)
        val (_, s) = applyDimensionInterplay(q, PersonalitySpectrum(openness = 85))
        assertEquals(85, s.openness)
    }

    /** 规则3 只碰坦诚度一维，性格其余 7 维不变。 */
    @Test fun rule3_touchesOnlyOpenness() {
        val before = PersonalitySpectrum(openness = 60)
        val (_, after) = applyDimensionInterplay(RelationshipQuality(trust = 70), before)
        assertEquals(before.copy(openness = 61), after)
    }
}
