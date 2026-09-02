package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.random.Random

/**
 * 活人感内核·修缮卷 T1-4（图纸 §7 · E8 / E9 / E10）：[relaxOverlap] 重叠泄放。
 *
 * 断言从图纸 §3.3 **独立反推**（不照抄实现）：
 * - E8：`pos 155 / neg 55`、30 天 ⇒ 重叠 55 × 0.5 = 27.5 → round 28 保留 ⇒ 泄 27 ⇒ `pos 128 / neg 28`，净额恒 100，`relaxedAt` 更新
 * - E9：`relaxedAt == 0` 首次只登记不泄；同日第二次（dt < 24h）原样返回、`relaxedAt` 不动
 * - E10：老 JSON 无 `relaxedAt` ⇒ 0；`withDim`（经 applyNetDelta）/ `resetChangedDims` / `normalized` 后字段保留
 * - 净额恒等：8 维随机 200 组（**有种子**·§9.5）`toQuality()` 前后逐维相等
 */
class PressureOverlapDecayTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    private fun quality(vararg values: Int): RelationshipQuality {
        var q = RelationshipQuality()
        values.forEachIndexed { i, v -> q = q.setValue(i, v) }
        return q
    }

    @Test
    fun `E8 三十天后重叠部分泄一半 净额恒等 relaxedAt 更新`() {
        val before = RelationshipPressure(pos = List(8) { 155 }, neg = List(8) { 55 }, relaxedAt = t0)
        val after = before.relaxOverlap(t0 + 30 * day)
        assertEquals(List(8) { 128 }, after.pos)
        assertEquals(List(8) { 28 }, after.neg)
        assertEquals("净额恒 100", List(8) { 100 }, after.toQuality().values)
        assertEquals(t0 + 30 * day, after.relaxedAt)
    }

    @Test
    fun `E9 首次只登记不泄 同日第二次原样返回`() {
        val fresh = RelationshipPressure(pos = List(8) { 155 }, neg = List(8) { 55 })
        assertEquals(0L, fresh.relaxedAt)
        val registered = fresh.relaxOverlap(t0)
        assertEquals("首次只登记", t0, registered.relaxedAt)
        assertEquals(fresh.pos, registered.pos)
        assertEquals(fresh.neg, registered.neg)

        val sameDay = registered.relaxOverlap(t0 + 23 * 3_600_000L)
        assertSame("dt < 24h ⇒ 原样返回同一实例", registered, sameDay)
        assertEquals("relaxedAt 不动", t0, sameDay.relaxedAt)
    }

    @Test
    fun `E10 老 JSON 缺 relaxedAt 回 0 且三个写口都保留 relaxedAt`() {
        val legacy = GrowthJson.decodeRelationshipPressure("""{"pos":[80,0,0,0,0,0,0,0],"neg":[60,0,0,0,0,0,0,0]}""")
        assertEquals(0L, legacy.relaxedAt)

        val stamped = legacy.copy(relaxedAt = t0)
        assertEquals("applyNetDelta（withDim）保留", t0, stamped.applyNetDelta(0, +3).relaxedAt)
        assertEquals("applyPressureDelta（withDim）保留", t0, stamped.applyPressureDelta(1, 2, 1).relaxedAt)
        assertEquals("setNetKeepingNeg（withDim）保留", t0, stamped.setNetKeepingNeg(0, 10).relaxedAt)
        assertEquals("resetChangedDims 保留", t0, stamped.resetChangedDims(quality(20), quality(50)).relaxedAt)
        assertEquals("normalized 保留", t0, stamped.normalized().relaxedAt)
        assertEquals("编解码往返保留", t0, GrowthJson.decodeRelationshipPressure(GrowthJson.encode(stamped)).relaxedAt)
        assertEquals("fromQuality 播种 relaxedAt = 0（有意）", 0L, RelationshipPressure.fromQuality(quality(20)).relaxedAt)
    }

    @Test
    fun `净额恒等 八维随机两百组`() {
        val rng = Random(20260902)
        repeat(200) {
            val pos = List(8) { rng.nextInt(0, 201) }
            val neg = List(8) { rng.nextInt(0, 201) }
            val before = GrowthJson.decodeRelationshipPressure(GrowthJson.encode(RelationshipPressure(pos, neg, relaxedAt = t0)))
            val dt = rng.nextLong(day, 120 * day)
            val after = before.relaxOverlap(t0 + dt)
            assertEquals("第 $it 组 dt=$dt 净额变了", before.toQuality(), after.toQuality())
            for (i in 0 until 8) {
                assertEquals("第 $it 组维 $i：两侧同减同一个 d", before.pos[i] - after.pos[i], before.neg[i] - after.neg[i])
            }
        }
    }

    @Test
    fun `重叠为零的维一个字节不动`() {
        val before = RelationshipPressure(pos = listOf(90, 0, 40, 0, 0, 0, 0, 0), neg = listOf(0, 0, 40, 0, 0, 0, 0, 0), relaxedAt = t0)
        val after = before.relaxOverlap(t0 + 30 * day)
        assertEquals(90, after.pos[0])
        assertEquals(0, after.neg[0])
        assertNotEquals("有重叠的维必泄", 40, after.pos[2])
        assertEquals(after.pos[2], after.neg[2])
    }
}
