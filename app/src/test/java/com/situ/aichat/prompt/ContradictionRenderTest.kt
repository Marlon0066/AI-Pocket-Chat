package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.fromQuality
import com.situ.aichat.prompt.growth.RelationshipArchetype
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 活人感内核·卷二《正负双压》T1-7–T1-11（图纸 §7.2 · P-E10–P-E14）：矛盾句渲染。
 *
 * 断言从图纸 §4.0/§4.1/§4.2 独立反推（锁定文案在测试里**重新打字**为字面量，防自证·PITFALLS §1e）：
 * - **T1-7** 正 80 / 负 75（净额 5，旧结构下恰落静默区）⇒ 输出矛盾句，且**不含**旧的「没有依恋」那行
 * - **T1-8** 8 维全矛盾 ⇒ 恰 2 条，按 `min(pos,neg)` 降序；其余 6 维**回落**既有渲染
 * - **T1-9** ⭐ 零矛盾 ⇒ 两条渲染路输出与「不带压强的旧实现」**字符串相等**（同输入喂两版比对）
 * - **T1-10** 闭嘴分支（有名分无原型）+ 有矛盾维 ⇒ **仍整段闭嘴**（2026-07-11 有意设计，本卷不翻案）
 * - **T1-11** 卷零拉回后的角色（只减正压、负压 0）⇒ **不触发任何矛盾句**
 */
class ContradictionRenderTest {

    private val fixedNow = Instant.ofEpochMilli(1_700_000_000_000L)

    /** 依恋维矛盾句（图纸 §4.1 第 8 行·逐字重打，不引用实现常量）。 */
    private val attachmentLine =
        "- 你离不开小明，同时又觉得这样很累——这两股劲同时在你身上，你自己也说不清哪个更真。"

    /** 旧的依恋低档行为剧本行——正是那个「一个正在挣扎的人被说成无所谓」的输出。 */
    private val attachmentSilentEra = "你对ta没有依恋"

    private fun character(
        q: RelationshipQuality,
        pressure: RelationshipPressure? = null,
        archetypeId: String? = null,
    ) = CharacterEntity(
        uuid = "u", name = "小雨", creationDate = 0L,
        relationshipQualityJSON = GrowthJson.encode(q),
        relationshipPressureJSON = pressure?.let { GrowthJson.encode(it) } ?: "",
        relationshipArchetypeId = archetypeId,
    )

    private fun render(
        q: RelationshipQuality,
        pressure: RelationshipPressure? = null,
        archetypeId: String? = null,
        milestones: List<MilestoneEntity> = emptyList(),
    ): String {
        val ctx = mockk<PromptBuilder.BuildContext>()
        every { ctx.appSettings } returns AppSettings()
        every { ctx.character } returns character(q, pressure, archetypeId)
        every { ctx.resolvedUserName } returns "小明"
        every { ctx.now } returns fixedNow
        every { ctx.milestones } returns milestones
        return buildCharacterGrowthContent(ctx)
    }

    // MARK: - T1-7：静默区里的矛盾终于说得出口

    @Test
    fun `T1-7 正80负75 净额落静默区 - 输出矛盾句而不是被吃掉`() {
        // 依恋净额 5：旧结构下这维只有一个数，5 落在低档 ⇒ 渲染成「你对ta没有依恋」。
        val q = RelationshipQuality(attachment = 5)
        val pressure = RelationshipPressure(
            pos = listOf(10, 20, 10, 10, 35, 20, 5, 80),
            neg = listOf(0, 0, 0, 0, 0, 0, 0, 75),
        )
        val out = render(q, pressure)

        assertTrue("必须说出那句矛盾：\n$out", out.contains(attachmentLine))
        assertFalse("绝不能再把一个正在挣扎的人描述成无所谓：\n$out", out.contains(attachmentSilentEra))
    }

    @Test
    fun `T1-7 矛盾句恒排在该段最前`() {
        val q = RelationshipQuality(familiarity = 10, attachment = 5)
        val pressure = RelationshipPressure(
            pos = listOf(10, 20, 10, 10, 35, 20, 5, 80),
            neg = listOf(0, 0, 0, 0, 0, 0, 0, 75),
        )
        val body = render(q, pressure).substringAfter("你和小明的互动方式：\n")

        assertEquals("段内第一行就是矛盾句", attachmentLine, body.lineSequence().first())
    }

    // MARK: - T1-8：上限 2 条 + 排序

    @Test
    fun `T1-8 八维全矛盾 - 恰 2 条按 min 降序其余六维回落既有渲染`() {
        // min(pos,neg) 逐维：依恋 75 最大、张力 70 次之，其余 60。⇒ 只该说依恋与张力两句。
        val pressure = RelationshipPressure(
            pos = listOf(60, 60, 60, 60, 60, 60, 70, 80),
            neg = listOf(60, 60, 60, 60, 60, 60, 72, 75),
        )
        val q = pressure.let { RelationshipQuality(0, 0, 0, 0, 0, 0, 0, 5) }
        val out = render(q, pressure)

        val contradictionLines = out.lines().filter { it.contains("这两股劲同时在你身上") }
        assertEquals("恰 2 条（8 句同尾巴 = 稀释）：\n$out", 2, contradictionLines.size)
        assertTrue("第 1 条是 min 最大的依恋", contradictionLines[0].contains("你离不开小明"))
        assertTrue("第 2 条是张力", contradictionLines[1].contains("绷着一根弦"))
        // 第 3 名及以后的矛盾维不是被丢掉，而是回落既有渲染（净额 0 ⇒ 各自最低档行照常出）。
        assertTrue("熟悉度回落既有渲染：\n$out", out.contains("你们还很生疏"))
    }

    @Test
    fun `T1-8 同值时按维度固定序取前两个`() {
        // 8 维 min 全相等 ⇒ 必须取 DIMENSION_KEYS 的前两个（熟悉度、信任感），且每次都一样。
        val pressure = RelationshipPressure(pos = List(8) { 60 }, neg = List(8) { 60 })
        val out = render(RelationshipQuality(0, 0, 0, 0, 0, 0, 0, 0), pressure)
        val lines = out.lines().filter { it.contains("这两股劲同时在你身上") }

        assertEquals(2, lines.size)
        assertTrue("第一条是熟悉度", lines[0].contains("你太清楚小明是什么样的人了"))
        assertTrue("第二条是信任感", lines[1].contains("你愿意把心里话交给小明"))
    }

    @Test
    fun `T1-8 阈值是双侧同时到 55 - 单侧到阈不算矛盾`() {
        val onlyPos = RelationshipPressure(pos = List(8) { 80 }, neg = List(8) { 54 })
        assertTrue("负压差 1 点就不算：负压 54", contradictionDims(onlyPos).isEmpty())

        val bothAtEdge = RelationshipPressure(pos = List(8) { 55 }, neg = List(8) { 55 })
        assertEquals("55 是含端点的", 2, contradictionDims(bothAtEdge).size)
    }

    // MARK: - T1-9 ⭐ 零矛盾 ⇒ 与旧实现逐字节相同

    @Test
    fun `T1-9 legacy 路零矛盾 - 与不带压强的旧实现字符串相等`() {
        val cases = listOf(
            RelationshipQuality(),
            RelationshipQuality(familiarity = 70, trust = 80, closeness = 70, rapport = 80, respect = 80, funValue = 30, tension = 60, attachment = 60),
            RelationshipQuality(familiarity = 50, trust = 50, closeness = 15, rapport = 85, respect = 95, funValue = 5, tension = 45, attachment = 75),
        )
        for (q in cases) {
            // 「旧实现」= 压强列为空（访问器播种 ⇒ neg 全 0 ⇒ 判定恒不触发）。
            val legacy = render(q, pressure = null)
            // 「新实现」= 显式带一份播种压强（正压 = 净额、负压 0）——同一份数据的两种存法。
            val withPressure = render(q, pressure = RelationshipPressure.fromQuality(q))

            assertEquals("零矛盾时两者必须逐字节相同（$q）", legacy, withPressure)
            assertFalse("不该冒出任何矛盾句", legacy.contains("这两股劲同时在你身上"))
        }
    }

    @Test
    fun `T1-9 archetype 路零矛盾 - 与不带压强的旧实现字符串相等`() {
        val lover = RelationshipArchetype.byId("LOVER")!!
        val cases = listOf(
            lover.floors.let { RelationshipQuality(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7]) },
            RelationshipQuality(familiarity = 90, trust = 85, closeness = 88, rapport = 80, respect = 75, funValue = 70, tension = 40, attachment = 85),
        )
        for (q in cases) {
            val legacy = buildArchetypeRelationshipDescription(q, lover, "小明")
            val withPressure = buildArchetypeRelationshipDescription(q, lover, "小明", RelationshipPressure.fromQuality(q))
            assertEquals("零矛盾时 archetype 路也必须逐字节相同", legacy, withPressure)
        }
    }

    @Test
    fun `T1-9 pressure 为 null 与播种压强等价`() {
        val lover = RelationshipArchetype.byId("LOVER")!!
        val q = RelationshipQuality(familiarity = 70, trust = 60, closeness = 65, rapport = 55, respect = 60, funValue = 50, tension = 20, attachment = 60)
        assertEquals(
            buildArchetypeRelationshipDescription(q, lover, "小明", null),
            buildArchetypeRelationshipDescription(q, lover, "小明", RelationshipPressure.fromQuality(q)),
        )
    }

    // MARK: - T1-10 闭嘴分支不翻案

    @Test
    fun `T1-10 有名分无原型 + 有矛盾维 - 仍整段闭嘴`() {
        val pressure = RelationshipPressure(pos = List(8) { 80 }, neg = List(8) { 75 })
        val milestone = mockk<MilestoneEntity>(relaxed = true)
        every { milestone.relationshipName } returns "恋人"
        every { milestone.establishedDate } returns fixedNow.toEpochMilli()
        every { milestone.triggerTypeRaw } returns "userAdvance"
        every { milestone.reason } returns "关系调整"

        val out = render(RelationshipQuality(attachment = 5), pressure, archetypeId = null, milestones = listOf(milestone))

        assertFalse("词表未识别时宁可不说 —— 本卷不翻这个案：\n$out", out.contains("这两股劲同时在你身上"))
        assertFalse("互动方式整段都不该出现", out.contains("你和小明的互动方式："))
    }

    // MARK: - T1-11 拉回过的角色不该冒出矛盾

    @Test
    fun `T1-11 卷零拉回后的角色 - 不触发任何矛盾句`() {
        // 拉回只减正压、负压恒 0（P-1）⇒ 判定的另一半永远不成立。
        val pulledBack = RelationshipPressure(
            pos = listOf(88, 77, 92, 66, 71, 64, 75, 84),
            neg = List(8) { 0 },
        )
        val out = render(pulledBack.let { RelationshipQuality(88, 77, 92, 66, 71, 64, 75, 84) }, pulledBack)

        assertTrue("负压全 0 ⇒ 零矛盾维", contradictionDims(pulledBack).isEmpty())
        assertFalse("系统撤销虚高绝不能被渲染成「又想又不敢」：\n$out", out.contains("这两股劲同时在你身上"))
    }

    @Test
    fun `T1-11 若拉回错用了净额写口就会伪造出矛盾 - 反证`() {
        // 这条是 P-1 的反证：假如 ⑧ 走 applyNetDelta，tension 97→78 会凭空多出 19 点负压；
        // 再攒几次就够阈了。此处直接构造那个「错误世界」，证明它确实会说出矛盾句。
        val ifWrong = RelationshipPressure(
            pos = listOf(60, 60, 60, 60, 60, 60, 97, 60),
            neg = listOf(56, 56, 56, 56, 56, 56, 56, 56),
        )
        assertTrue("错误世界里矛盾句会被误触发", contradictionDims(ifWrong).isNotEmpty())
    }
}
