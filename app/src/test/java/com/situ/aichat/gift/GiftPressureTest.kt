package com.situ.aichat.gift

import com.situ.aichat.data.model.GiftRelationshipImpact
import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.fromQuality
import com.situ.aichat.data.model.syncedTo
import com.situ.aichat.data.model.toQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感内核·卷二《正负双压》T2-7（图纸 §7.2 · P-E21 · §6.2）：礼物三个写入点 ②③④ 的**压强翻译**。
 *
 * 三处写入点做的事一模一样，且都是同一行代码：
 * `character.relationshipPressure.syncedTo(GiftRelationshipImpactService.apply(impact, quality))`
 * ——本测试用**真实的** [GiftRelationshipImpactService]（§6.2 零碰件）算 impact，钉住那一行的三条性质：
 *
 * - impact 恒 ≥0 ⇒ 只加**正压**，负压一个字节不动（礼物不该凭空造出「负向力」）
 * - impact 为 0 的维 ⇒ **零差异**（`applyNetDelta(0)` 原样返回，不产生任何写入差异）
 * - 落库净额 == 旧实现 `apply(impact, quality)` 的结果**逐值相同**（钱路手感一个字节不变）
 *
 * ⚠️ 本测试**不碰** `compute`/`apply` 本体（那是 `GiftRelationshipImpactTest` 的活，且 §6.2 规定零改）。
 * 三处调用点的持锁范围、`affinityToUser` 递增与 `currencyService.*` 调用序的不变性，由 §7.5 的 T5
 * 对抗复核逐行核 diff 承担——`sendInChat` / 礼物店事务在本仓无编排测试基建（见本卷 §11 偏差 D-3）。
 */
class GiftPressureTest {

    /** 一份「相处出来的」压强：亲近上已经攒了负压（正 40 / 负 30 ⇒ 净额 10）。 */
    private fun stored() = RelationshipPressure(
        pos = listOf(62, 58, 40, 30, 35, 20, 5, 25),
        neg = listOf(0, 0, 30, 0, 0, 0, 0, 0),
    )

    private fun quality() = stored().toQuality()

    @Test
    fun `impact 全正 - 只加正压负压逐值不变`() {
        // 真礼物：马卡龙（cute + refined 之类），affinityGain 12 ⇒ 若干维 +1/+2，恒非负。
        val impact = GiftRelationshipImpactService.compute(GiftCatalog.find("gift_macaron")!!, affinityGain = 12)
        assertTrue("前提自检：礼物 impact 恒 ≥0", (0 until 8).all { impact.value(it) >= 0 })

        val before = stored()
        val after = before.syncedTo(GiftRelationshipImpactService.apply(impact, quality()))

        assertEquals("负压一个字节不动", before.neg, after.neg)
        for (i in 0 until 8) {
            assertEquals("维 $i 的正压恰好加了 impact 那么多", before.pos[i] + impact.value(i), after.pos[i])
        }
    }

    @Test
    fun `落库净额与旧实现逐值相同`() {
        val impact = GiftRelationshipImpactService.compute(GiftCatalog.find("gift_steak")!!, affinityGain = 15)
        val legacy = GiftRelationshipImpactService.apply(impact, quality())   // 旧实现直接落这个
        val viaPressure = stored().syncedTo(legacy).toQuality()               // 新实现经压强单向派生

        assertEquals("钱路手感一个字节不变", legacy, viaPressure)
    }

    @Test
    fun `impact 全零 - 压强对象原样返回零写入差异`() {
        val empty = GiftRelationshipImpact()   // 八维全 0
        val before = stored()
        val after = before.syncedTo(GiftRelationshipImpactService.apply(empty, quality()))

        assertEquals(before, after)
        assertSame("连新对象都不该造（applyNetDelta(0) 原样返回）", before, after)
    }

    @Test
    fun `单维 impact 为 0 时该维压强零差异其余维照加`() {
        // 手搓一个只推亲近（+3）的 impact：其余七维必须一个字节不动。
        val impact = GiftRelationshipImpact(closeness = 3)
        val before = stored()
        val after = before.syncedTo(GiftRelationshipImpactService.apply(impact, quality()))

        assertEquals(before.pos[0], after.pos[0])
        assertEquals(before.pos[1], after.pos[1])
        assertEquals("亲近正压 +3", before.pos[2] + 3, after.pos[2])
        assertEquals("亲近的历史负压 30 原封不动", 30, after.neg[2])
        assertEquals("净额 10 + 3 = 13", 13, after.toQuality().closeness)
        assertEquals(before.pos.drop(3), after.pos.drop(3))
        assertEquals(before.neg, after.neg)
    }

    @Test
    fun `老角色空压强列 - 播种后送礼等价于对净额直接加`() {
        val impact = GiftRelationshipImpactService.compute(GiftCatalog.find("gift_macaron")!!, affinityGain = 12)
        val oldQuality = RelationshipQuality(familiarity = 62, trust = 58, closeness = 40)
        val seeded = RelationshipPressure.fromQuality(oldQuality)   // 访问器兜底给的就是这个

        val after = seeded.syncedTo(GiftRelationshipImpactService.apply(impact, oldQuality))

        assertEquals(GiftRelationshipImpactService.apply(impact, oldQuality), after.toQuality())
        assertTrue("老角色送一次礼不该冒出任何负压", after.neg.all { it == 0 })
    }
}
