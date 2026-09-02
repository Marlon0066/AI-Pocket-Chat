package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跑飞棘轮止血的长程模拟（活人感内核卷零 chunk2·图纸 §3.2 推演表的可执行版）。
 *
 * **被诊断的病**：规则1（亲近+依恋高 → 张力+1）与规则4（张力高 → 依恋+1）构成正反馈环，而关系淡化
 * 对活跃用户（≤3 天必聊）整个不生效（图纸 Z-F3）⇒ 所有久处的角色都在漂向张力 100 / 依恋 100。
 *
 * **本例的模拟条件**：`closeness=80, attachment=76` 让规则1 常燃、`trust=75` 让规则3 常燃，
 * **LLM 报的变化恒为 0**（即完全没有真实事件），反复跑「跷跷板 + 泄压」500 次。
 * 若止血有效，纯联动**无法自我维持**：张力必须掉头向下并停在规则4 触发线（60）之下。
 */
class InterplayRunawaySimulationTest {

    private val rounds = 500

    private data class Trace(
        val tension: List<Int>,
        val attachment: List<Int>,
        val openness: List<Int>,
    )

    /** 跑 [rounds] 轮「跷跷板 → 泄压」，记录每轮结束后的三维轨迹（含起点）。 */
    private fun simulate(): Trace {
        var q = RelationshipQuality(
            familiarity = 10, trust = 75, closeness = 80, rapport = 10,
            respect = 35, funValue = 20, tension = 59, attachment = 76,
        )
        var s = PersonalitySpectrum.NEUTRAL
        val tension = mutableListOf(q.tension)
        val attachment = mutableListOf(q.attachment)
        val openness = mutableListOf(s.openness)
        repeat(rounds) {
            val interplay = applyDimensionInterplay(q, s)
            q = applyTensionRelief(interplay.first)
            s = interplay.second
            tension.add(q.tension)
            attachment.add(q.attachment)
            openness.add(s.openness)
        }
        return Trace(tension, attachment, openness)
    }

    /**
     * ① 张力自第 2 次分析起**严格单调不增**。
     * （第 1 次允许上升：起点 59 被规则1 顶到封顶 60 后才回落，正是图纸推演表的第 N 行。）
     */
    @Test fun tension_isMonotonicNonIncreasingFromSecondAnalysis() {
        val t = simulate().tension
        for (i in 1 until t.size - 1) {
            assertTrue("第 $i → ${i + 1} 轮张力回升：${t[i]} → ${t[i + 1]}", t[i] >= t[i + 1])
        }
    }

    /**
     * ② 终值必须**低于规则4 的触发线 60** —— 这是止血成立的判据：联动自身再也点不着规则4。
     *
     * 终值的精确落点由两条规格相除得出：泄压恒 −2（卷零图纸 §3.2·到地板 5 停），而规则1 的推力自**卷三**起走
     * [saturate]`(1.0) = 1`——恒 +1、无 `scaledDelta` 的 `<20` 档 1.5× 低端加速（卷零时代 `ceil(1.5)=2` 恰好抵消泄压，
     * 张力因此停在不动点 19；卷二 R1-4 判定那是「随废 ±1 保底」要一并消除的）。
     * 故张力每轮净 −1 一路下行，到泄压地板 [RelationshipBands.TENSION_RELIEF_FLOOR] = **5** 后：规则1 +1 ⇒ 6，泄压 −2 钳到 5 ⇒ 恒 5。
     */
    @Test fun tension_settlesBelowRule4Threshold() {
        val t = simulate().tension
        assertTrue("终值 ${t.last()} 仍够得着规则4 触发线 60", t.last() < 60)
        assertEquals(RelationshipBands.TENSION_RELIEF_FLOOR, t.last())
        assertEquals(5, t.last())
    }

    /** ③ 依恋总增量 ≤ 1：规则4 只在第 1 轮（张力被顶到 60 那一次）点燃过一次，此后再无无源推高。 */
    @Test fun attachment_gainsAtMostOneAcrossFiveHundredAnalyses() {
        val a = simulate().attachment
        assertTrue("依恋在 $rounds 轮里涨了 ${a.last() - a.first()}", a.last() - a.first() <= 1)
    }

    /** ④ 坦诚度终值 ≤ 天花板 70（= 出厂初值 50 + 20），且确实被推到了天花板（证明规则3 全程常燃）。 */
    @Test fun openness_stopsAtCeiling() {
        val o = simulate().openness
        assertTrue("坦诚度终值 ${o.last()} 越过天花板", o.last() <= RelationshipBands.OPENNESS_INTERPLAY_CAP)
        assertEquals(RelationshipBands.OPENNESS_INTERPLAY_CAP, o.last())
    }

    /**
     * 对照组（证明本例真的在测「止血」而不是测「什么都没发生」）：**若拿掉泄压**，同样条件下
     * 张力会被规则1 顶到封顶并**永久停在 60** ⇒ 规则4 每轮点燃 ⇒ 依恋一路涨到 100。
     * 这正是封顶单独存在时残留的跑飞——泄压是必需的第二把闸。
     */
    @Test fun withoutRelief_ratchetWouldStillRunAway() {
        var q = RelationshipQuality(
            familiarity = 10, trust = 75, closeness = 80, rapport = 10,
            respect = 35, funValue = 20, tension = 59, attachment = 76,
        )
        var s = PersonalitySpectrum.NEUTRAL
        repeat(rounds) {
            val interplay = applyDimensionInterplay(q, s)
            q = interplay.first
            s = interplay.second
        }
        assertEquals(60, q.tension)
        assertEquals(100, q.attachment)
    }
}
