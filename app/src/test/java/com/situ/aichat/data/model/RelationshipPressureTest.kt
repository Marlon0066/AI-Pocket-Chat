package com.situ.aichat.data.model

import com.situ.aichat.data.local.entity.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活人感内核·卷二《正负双压》T1-1–T1-5（图纸 §7.2）：[RelationshipPressure] 的四条不变式与三个写口。
 *
 * 断言从图纸 §3.1 的规格**独立反推**，不照抄实现输出：
 * - **I-1** `quality[i] == (pos[i] - neg[i]).coerceIn(0,100)`；播种 = `pos 取当前净额, neg = 0`
 * - **I-2** 写后 `min(pos,neg) > 100` ⇒ 两侧同减 `min - 100`，**净额恒等**
 * - **I-3** 每项 `0..200`
 * - **I-4** 解码长度 <8 右补 0、>8 截前 8
 * - **防漂移**（P-E3）：净额越 `[0,100]` 时下一次增量以**钳位后的净额**为基准
 */
class RelationshipPressureTest {

    private fun quality(vararg values: Int): RelationshipQuality {
        var q = RelationshipQuality()
        values.forEachIndexed { i, v -> q = q.setValue(i, v) }
        return q
    }

    /** I-1 的机器判据：逐维核对净额恒等于「正压减负压再钳 [0,100]」。 */
    private fun assertInvariantI1(p: RelationshipPressure) {
        val q = p.toQuality()
        for (i in 0 until RelationshipPressure.DIM_COUNT) {
            assertEquals(
                "维 $i 违反 I-1：pos=${p.pos[i]} neg=${p.neg[i]} 却派生出 ${q.values[i]}",
                (p.pos[i] - p.neg[i]).coerceIn(0, 100),
                q.values[i],
            )
        }
    }

    // MARK: - T1-1 播种

    @Test
    fun `T1-1 fromQuality 播种 pos 取净额 neg 归零且满足 I-1`() {
        val q = quality(72, 55, 40, 33, 61, 20, 5, 88)
        val seeded = RelationshipPressure.fromQuality(q)

        assertEquals("正压逐维等于当前净额", q.values, seeded.pos)
        assertEquals("负压全 0", List(8) { 0 }, seeded.neg)
        assertInvariantI1(seeded)
        assertEquals("播种后反推回来的净额必须一字不差", q, seeded.toQuality())
    }

    @Test
    fun `T1-1 空压强列的访问器兜底等价于播种`() {
        val qualityJson = GrowthJson.encode(quality(72, 55, 40, 33, 61, 20, 5, 88))
        val old = CharacterEntity(
            uuid = "c1",
            name = "林晚",
            creationDate = 0L,
            relationshipQualityJSON = qualityJson,
            relationshipPressureJSON = "",      // 老角色：迁移后落 ''
        )

        assertEquals(RelationshipPressure.fromQuality(old.relationshipQuality), old.relationshipPressure)
        assertEquals("兜底后净额与库里那一列完全一致（渲染天然正确）", old.relationshipQuality, old.relationshipPressure.toQuality())
    }

    @Test
    fun `T1-1 非空压强列走解码而不是兜底`() {
        val stored = RelationshipPressure(pos = List(8) { 80 }, neg = List(8) { 75 })
        val entity = CharacterEntity(
            uuid = "c1",
            name = "林晚",
            creationDate = 0L,
            relationshipQualityJSON = GrowthJson.encode(quality(5, 5, 5, 5, 5, 5, 5, 5)),
            relationshipPressureJSON = GrowthJson.encode(stored),
        )

        assertEquals(stored, entity.relationshipPressure)
        assertNotEquals("绝不能回落成播种值", RelationshipPressure.fromQuality(entity.relationshipQuality), entity.relationshipPressure)
    }

    @Test
    fun `T5 F-1 坏 JSON 压强列与空列同路回落播种而不是全零`() {
        // 前置：非空且解不开的压强列（现树没有写者会产出，唯一入口是手改的备份文件）。
        // 回落全零的后果 = ①⑦⑧ 三条「从压强派生净额」的写口把 8 维零净额写回 relationshipQualityJSON，整段相处史归零。
        val quality = quality(72, 55, 40, 33, 61, 20, 5, 88)
        val broken = CharacterEntity(
            uuid = "c1",
            name = "林晚",
            creationDate = 0L,
            relationshipQualityJSON = GrowthJson.encode(quality),
            relationshipPressureJSON = "{ 这不是 json",
        )

        assertNull(GrowthJson.decodeRelationshipPressureOrNull("{ 这不是 json"))
        assertNull(GrowthJson.decodeRelationshipPressureOrNull(""))
        assertEquals(RelationshipPressure.fromQuality(quality), broken.relationshipPressure)
        assertEquals("⑦ 只改性格时透传：派生净额必须仍等于库里那一列", quality, broken.relationshipPressure.resetChangedDims(quality, quality).toQuality())
        assertNotEquals("绝不能回落成全零", RelationshipPressure(), broken.relationshipPressure)
    }

    // MARK: - 修缮卷 T1-5（E11）访问器交叉校验：解得开但派生净额 ≠ 净额列 ⇒ 回落播种

    @Test
    fun `修缮卷 E11 空壳压强列与净额列不符时回落播种净额保留`() {
        val quality = quality(72, 55, 40, 33, 61, 20, 5, 88)
        for (shell in listOf("{}", """{"pos":[],"neg":[]}""")) {
            val entity = CharacterEntity(
                uuid = "c1", name = "林晚", creationDate = 0L,
                relationshipQualityJSON = GrowthJson.encode(quality),
                relationshipPressureJSON = shell,
            )
            assertEquals("`$shell` 解得开（全零）", RelationshipPressure(), GrowthJson.decodeRelationshipPressureOrNull(shell))
            assertEquals("`$shell` 派生全零 ≠ 净额列 ⇒ 播种", RelationshipPressure.fromQuality(quality), entity.relationshipPressure)
            assertEquals("净额保留", quality, entity.relationshipPressure.toQuality())
        }
    }

    @Test
    fun `修缮卷 E11 合法列派生净额等于净额列时不回落`() {
        val stored = RelationshipPressure(pos = listOf(80, 55, 40, 33, 61, 20, 5, 88), neg = listOf(8, 0, 0, 0, 0, 0, 0, 0), relaxedAt = 123L)
        val entity = CharacterEntity(
            uuid = "c1", name = "林晚", creationDate = 0L,
            relationshipQualityJSON = GrowthJson.encode(stored.toQuality()),
            relationshipPressureJSON = GrowthJson.encode(stored),
        )
        assertEquals("合法列原样（含 relaxedAt）", stored, entity.relationshipPressure)
    }

    // MARK: - T1-2 归一化（I-2 / I-3）

    @Test
    fun `T1-2 双高同减后净额恒等`() {
        val raw = GrowthJson.encode(RelationshipPressure(pos = List(8) { 180 }, neg = List(8) { 170 }))
        val normalized = GrowthJson.decodeRelationshipPressure(raw)

        // 规格：d = min(180,170) - 100 = 70 ⇒ 两侧同减 70。
        assertEquals(List(8) { 110 }, normalized.pos)
        assertEquals(List(8) { 100 }, normalized.neg)
        assertEquals("净额恒 10（归一化只泄压不改净额）", List(8) { 10 }, normalized.toQuality().values)
        assertInvariantI1(normalized)
    }

    @Test
    fun `T1-2 单侧不过阈时一个字节不动`() {
        val raw = GrowthJson.encode(RelationshipPressure(pos = List(8) { 150 }, neg = List(8) { 60 }))
        val normalized = GrowthJson.decodeRelationshipPressure(raw)

        // min = 60 ≤ 100 ⇒ I-2 不触发；但净额 90 在 [0,100] 内，防漂移也不该动它。
        assertEquals(List(8) { 150 }, normalized.pos)
        assertEquals(List(8) { 60 }, normalized.neg)
        assertEquals(List(8) { 90 }, normalized.toQuality().values)
    }

    @Test
    fun `T1-3 I-3 取值域 0 到 200`() {
        val raw = GrowthJson.encode(RelationshipPressure(pos = listOf(400, -30, 0, 0, 0, 0, 0, 0), neg = List(8) { 0 }))
        val normalized = GrowthJson.decodeRelationshipPressure(raw)

        assertTrue("每项都必须落进 0..200", normalized.pos.all { it in 0..RelationshipPressure.MAX_PRESSURE })
        assertTrue(normalized.neg.all { it in 0..RelationshipPressure.MAX_PRESSURE })
        assertEquals("负值钳成 0", 0, normalized.pos[1])
        assertInvariantI1(normalized)
    }

    // MARK: - T1-3 applyNetDelta 连续施加不漂移（P-E3）

    @Test
    fun `T1-3 净额增量连续施加每步都与直接算相同`() {
        var pressure = RelationshipPressure.fromQuality(quality(5))
        // 「直接算」= 旧实现的净额语义：old + delta 再钳 [0,100]。
        var direct = 5

        for (delta in listOf(+5, -10, +3)) {
            pressure = pressure.applyNetDelta(0, delta)
            direct = (direct + delta).coerceIn(0, 100)
            assertEquals("delta=$delta 之后净额必须与直接算一致", direct, pressure.toQuality().familiarity)
            assertInvariantI1(pressure)
        }
        assertEquals("终值 3（5 → 10 → 0 → 3）", 3, pressure.toQuality().familiarity)
    }

    @Test
    fun `T1-3 净额越上界后下一次增量以钳位后的净额为基准`() {
        // 封顶维再涨也不许攒虚高：否则负向增量得先啃完虚高才开始掉分（正是卷零拉回要撤销的棘轮）。
        var pressure = RelationshipPressure.fromQuality(quality(98))
        pressure = pressure.applyNetDelta(0, +20)
        assertEquals("净额钳在 100", 100, pressure.toQuality().familiarity)

        pressure = pressure.applyNetDelta(0, -10)
        assertEquals("以 100 为基准掉 10 ⇒ 90，而不是 118-10=100", 90, pressure.toQuality().familiarity)
    }

    @Test
    fun `T1-3 净额越下界后下一次增量以 0 为基准`() {
        var pressure = RelationshipPressure.fromQuality(quality(5))
        pressure = pressure.applyNetDelta(0, -30)
        assertEquals(0, pressure.toQuality().familiarity)

        pressure = pressure.applyNetDelta(0, +3)
        assertEquals("以 0 为基准涨 3 ⇒ 3，而不是被 25 点负压吃掉", 3, pressure.toQuality().familiarity)
    }

    @Test
    fun `T1-3 零增量原样返回不产生任何写入差异`() {
        val before = RelationshipPressure(pos = listOf(80, 1, 2, 3, 4, 5, 6, 7), neg = listOf(75, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(before, before.applyNetDelta(0, 0))
    }

    @Test
    fun `T1-3 正负增量各自入账并各钳 0 到 5`() {
        val start = RelationshipPressure.fromQuality(quality(70))
        val after = start.applyPressureDelta(0, posDelta = 9, negDelta = -1)   // 越界 ⇒ 钳成 5 / 0

        assertEquals(75, after.pos[0])
        assertEquals(0, after.neg[0])

        val both = start.applyPressureDelta(0, posDelta = 3, negDelta = 2)
        assertEquals("正压 70+3", 73, both.pos[0])
        assertEquals("负压 0+2", 2, both.neg[0])
        assertEquals("净额 = 73-2", 71, both.toQuality().familiarity)
        assertInvariantI1(both)
    }

    // MARK: - T1-4 shrinkPositive（⑧ 专用）

    @Test
    fun `T1-4 拉回只减正压负压一个字节不动`() {
        val before = RelationshipPressure(
            pos = listOf(0, 0, 0, 0, 0, 0, 97, 0),
            neg = listOf(0, 0, 0, 0, 0, 0, 0, 0),
        )
        val after = before.shrinkPositive(6, targetNet = 78)

        assertEquals("正压落到 neg + targetNet = 78", 78, after.pos[6])
        assertEquals("负压绝不因为系统拉回而增加（否则伪造出「又想又不敢」）", 0, after.neg[6])
        assertEquals(78, after.toQuality().tension)
        assertEquals("其余七维一个字节不动", before.pos.filterIndexed { i, _ -> i != 6 }, after.pos.filterIndexed { i, _ -> i != 6 })
    }

    @Test
    fun `T1-4 带负压时拉回仍只动正压`() {
        val before = RelationshipPressure(pos = listOf(90, 0, 0, 0, 0, 0, 0, 0), neg = listOf(20, 0, 0, 0, 0, 0, 0, 0))
        val after = before.shrinkPositive(0, targetNet = 50)

        assertEquals("pos = neg + targetNet = 70", 70, after.pos[0])
        assertEquals(20, after.neg[0])
        assertEquals(50, after.toQuality().familiarity)
    }

    @Test
    fun `T1-4 目标净额超出可表达范围时钳位且净额取实际值`() {
        val before = RelationshipPressure(pos = listOf(10, 0, 0, 0, 0, 0, 0, 0), neg = listOf(150, 0, 0, 0, 0, 0, 0, 0))
        val after = before.shrinkPositive(0, targetNet = 90)   // 需要 pos = 240 > 200

        assertTrue("正压钳在域顶", after.pos[0] <= RelationshipPressure.MAX_PRESSURE)
        assertEquals("净额取钳后实际值，不假装成功", (after.pos[0] - after.neg[0]).coerceIn(0, 100), after.toQuality().familiarity)
        assertInvariantI1(after)
    }

    // MARK: - T1-5 解码容错（I-4）

    @Test
    fun `T1-5 短列表右侧补零长列表截前八`() {
        val short = GrowthJson.decodeRelationshipPressure("""{"pos":[10,20,30,40,50,60],"neg":[1,2]}""")
        assertEquals(listOf(10, 20, 30, 40, 50, 60, 0, 0), short.pos)
        assertEquals(listOf(1, 2, 0, 0, 0, 0, 0, 0), short.neg)
        assertInvariantI1(short)

        val long = GrowthJson.decodeRelationshipPressure(
            """{"pos":[1,2,3,4,5,6,7,8,9,10],"neg":[0,0,0,0,0,0,0,0,99,99]}""",
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), long.pos)
        assertEquals(List(8) { 0 }, long.neg)
    }

    @Test
    fun `T1-5 空串与坏 JSON 都回落全零而不是抛`() {
        assertEquals(RelationshipPressure(), GrowthJson.decodeRelationshipPressure(""))
        assertEquals(RelationshipPressure(), GrowthJson.decodeRelationshipPressure("{ 这不是 json"))
    }

    @Test
    fun `T1-5 编解码往返相等`() {
        // 每维净额都在 [0,100] 内 ⇒ 归一化三步都不触发 ⇒ 往返必须逐字节相等。
        val p = RelationshipPressure(pos = listOf(80, 12, 9, 3, 44, 5, 97, 60), neg = listOf(75, 0, 9, 0, 2, 0, 0, 58))
        assertEquals(p, GrowthJson.decodeRelationshipPressure(GrowthJson.encode(p)))
    }

    @Test
    fun `T1-5 解码把净额为负的坏数据normalize回零`() {
        // 库里若混进 pos<neg 的维（手改/半写/未来 bug），解码即修：把负压压回 pos，净额落 0 而不是负数。
        val fixed = GrowthJson.decodeRelationshipPressure("""{"pos":[0,0,0,0,0,0,0,0],"neg":[9,0,0,0,0,0,0,0]}""")
        assertEquals(0, fixed.neg[0])
        assertEquals(0, fixed.toQuality().familiarity)
        assertInvariantI1(fixed)
    }
}
