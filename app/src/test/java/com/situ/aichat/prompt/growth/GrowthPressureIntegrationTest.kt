package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.fromQuality
import com.situ.aichat.data.model.syncedTo
import com.situ.aichat.data.model.toQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感内核·卷二《正负双压》T2-5（图纸 §7.2 · P-E19）：⑥ 闲置淡化落成**负压**而不是「正压消失」。
 *
 * 目标净额由**真实的** [computeDecayedQuality]（§P-N3 零碰件）算出，压强侧只钉那一步翻译：
 * 淡化是净额下降 ⇒ 经 `applyNetDelta` 加负压。这是有意的（图纸表1 ⑥ / W3）——久不聊天累积的是
 * 「疏远压」，而不是「亲近压凭空蒸发」；久别重逢后关系恢复得更慢一点，正是活人感。
 */
class GrowthPressureIntegrationTest {

    private val lover = RelationshipArchetype.byId("LOVER")!!   // 地板[55,30,50,45,45,40,0,0]

    @Test
    fun `T2-5 淡化让负压增长而正压一个字节不动`() {
        // 一个活跃过的角色：亲近 60，压强是纯正压（老角色播种后的典型形态）。
        val before = RelationshipQuality(familiarity = 80, trust = 60, closeness = 60, rapport = 55, respect = 50, funValue = 50)
        val pressureBefore = RelationshipPressure.fromQuality(before)

        // 真淡化：闲置 10 天、衰减 5 点 ⇒ closeness 60 → 55（到 LOVER 地板 50 之上）。
        val decayed = computeDecayedQuality(before, inactiveDays = 10, newDecayDays = 5, dynamicFloor = 99, archetype = lover)
        assertTrue("前提自检：这次淡化确实让亲近降了", decayed.closeness < before.closeness)

        val after = pressureBefore.syncedTo(decayed)

        assertEquals("正压逐维一个字节不动——淡化不是「亲近压消失」", pressureBefore.pos, after.pos)
        for (i in 0 until RelationshipPressure.DIM_COUNT) {
            val drop = before.values[i] - decayed.values[i]
            assertEquals("维 $i 的负压恰好等于净额跌幅", drop, after.neg[i])
        }
        assertEquals("派生净额与淡化算出来的目标逐值相同", decayed, after.toQuality())
    }

    @Test
    fun `T2-5 具体落点 - 亲近 60 降到 55 时负压加 5`() {
        val before = RelationshipQuality(closeness = 60)
        val after = RelationshipPressure.fromQuality(before).syncedTo(before.setValue(2, 55))

        assertEquals("正压仍是 60（不是 55）", 60, after.pos[2])
        assertEquals("负压 0 + 5", 5, after.neg[2])
        assertEquals(55, after.toQuality().closeness)
    }

    @Test
    fun `T2-5 淡化后再回暖 - 负压先被正压抵消关系恢复得更慢`() {
        // 疏远压攒下来的后果：久别重逢后同样的 +5 只把净额推回 55，而不是一步回到 60。
        val decayedPressure = RelationshipPressure.fromQuality(RelationshipQuality(closeness = 60))
            .syncedTo(RelationshipQuality(closeness = 50))          // 淡化 10 ⇒ neg = 10
        assertEquals(10, decayedPressure.neg[2])

        val warmedBack = decayedPressure.applyNetDeltaAt(2, 5)
        assertEquals("正压 65 / 负压 10 ⇒ 净额 55", 55, warmedBack.toQuality().closeness)
        assertEquals("那 10 点疏远压还记着，没被抹掉", 10, warmedBack.neg[2])
    }

    /** 让上面那条读起来像句话（净额语义写者的调用形态）。 */
    private fun RelationshipPressure.applyNetDeltaAt(dim: Int, delta: Int) =
        syncedTo(toQuality().setValue(dim, toQuality().values[dim] + delta))

    // MARK: - R1 复核 O-1：软上限校正只动正压，neg 恒 = LLM 报的数（系统调整不是角色身上的力）

    @Test
    fun `O-1 高分段涨得慢 - 打掉的涨幅不记成负压`() {
        // 95 报 pos=5 neg=0：目标 100 → 软化到 97；旧第 4 步会把差额 3 记进 neg。
        val (net, p) = pressurePathNet(current = 95, pos = 5, neg = 0)
        assertEquals(97, net)
        assertEquals("LLM 报 neg=0 ⇒ 落库 neg 必须为 0", 0, p.neg[0])
        assertEquals(97, p.pos[0])
    }

    @Test
    fun `O-1 高分段跌得快 - 放大的跌幅从正压扣而不是叠进负压`() {
        // 85 报 pos=0 neg=5：目标 80 → 软化（×2.0）到 75；neg 只能是 LLM 报的 5。
        val (net, p) = pressurePathNet(current = 85, pos = 0, neg = 5)
        assertEquals(75, net)
        assertEquals("neg 恒 = LLM 报的 5，放大部分不许进 neg", 5, p.neg[0])
        assertEquals(80, p.pos[0])
    }

    @Test
    fun `O-1 穷举 - 任意档位任意报值 neg 恒等于报的 neg`() {
        for (current in listOf(5, 15, 25, 50, 65, 75, 85, 95)) {
            for (pos in 0..5) for (neg in 0..5) {
                val (_, p) = pressurePathNet(current, pos, neg)
                assertEquals("current=$current pos=$pos neg=$neg", neg, p.neg[0])
            }
        }
    }

    // MARK: - 卷二 T2-4（图纸 §7.2 · P-E18）：① 双压路径的**净额结果与旧净额实现逐值相同**

    /**
     * 旧实现（本卷之前 `applyRelationshipChanges` 的那一行，逐字重打）：
     * 单个净额值直接过 [scaledDelta] 软上限。这是本组用例的**独立参照系**。
     */
    private fun legacyNet(current: Int, singleDelta: Int): Int =
        (current + scaledDelta(current, singleDelta)).coerceIn(0, 100)


    /** 走**生产码**的 ① 路径（图纸 P-7 四步），拿 familiarity 那一维出结果。 */
    private fun pressurePathNet(current: Int, pos: Int, neg: Int): Pair<Int, RelationshipPressure> {
        val quality = RelationshipQuality().setValue(0, current)
        val (q, p) = applyRelationshipChanges(
            changes = mapOf("familiarity" to GrowthAnalysisResult.PressureDelta(pos, neg)),
            quality = quality,
            pressure = RelationshipPressure.fromQuality(quality),
        )
        assertEquals("生产码必须自己维持 I-1（净额恒由压强派生）", p.toQuality(), q)
        return q.values[0] to p
    }

    @Test
    fun `T2-4 报 pos3 neg2 时净额与旧实现报 +1 完全相同`() {
        // P-E18：旧 trust=70，LLM 双压 3/2 ⇒ 净额语义等价于旧实现收到「+1」。
        val (net, pressure) = pressurePathNet(current = 70, pos = 3, neg = 2)

        assertEquals("净额与旧实现逐值相同（高段位涨得慢的手感一个字节不变）", legacyNet(70, +1), net)
        assertEquals("而压强把那两股力分开记下来了", 70 + 3, pressure.pos[0])
        assertEquals(2, pressure.neg[0])
    }

    @Test
    fun `T2-4 各档位穷举 - 双压净额恒等于旧实现`() {
        // scaledDelta 四个正向档（<20 / 20-59 / 60-79 / 80+）与四个负向档全覆盖。
        val currents = listOf(5, 15, 19, 20, 45, 59, 60, 70, 79, 80, 95, 100)
        for (current in currents) {
            for (pos in 0..5) {
                for (neg in 0..5) {
                    val (net, _) = pressurePathNet(current, pos, neg)
                    assertEquals(
                        "current=$current pos=$pos neg=$neg 时净额必须与旧实现收到 ${pos - neg} 时相同",
                        legacyNet(current, pos - neg),
                        net,
                    )
                }
            }
        }
    }

    @Test
    fun `T2-4 净额相抵为 0 时旧实现零位移新实现也零位移`() {
        val (net, pressure) = pressurePathNet(current = 70, pos = 3, neg = 3)

        assertEquals("净额一动不动 —— 这正是旧结构下「什么都没发生」的那种局面", 70, net)
        assertEquals(legacyNet(70, 0), net)
        // ⭐ 但这一次，那两股力被记住了：旧结构此处丢掉的信息，双压留下了。
        assertEquals(73, pressure.pos[0])
        assertEquals(3, pressure.neg[0])
        assertTrue("矛盾的种子已在账上", pressure.neg[0] > 0 && pressure.pos[0] > pressure.neg[0])
    }
}
