package com.situ.aichat.ui.world.town

import com.situ.aichat.ui.world.continent.rgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TownOverlayGeometry] T1（台阶1 图纸 §7 T1-3）：软影 / 光晕两条覆盖流的发射位置与跨距。期望值按图纸 §3.4
 * 发射表**独立推算**（半宽 = 半尺寸 + 外扩、窗中心 = [TownGeometry] 的 windows 同式 + 半窗高 + 外推），
 * 不读实现常量。
 */
class TownOverlayGeometryTest {

    private val WALLC = rgb(0xC99A86)
    private val ROOFC = rgb(0x9A5B3E)
    private val LEAF = rgb(0x7E926E)
    private val eps = 1e-5f

    private fun spec(
        buildings: List<TownBuilding> = emptyList(),
        fillers: List<TownFiller> = emptyList(),
        lanterns: List<TownLantern> = emptyList(),
        trees: List<TownTree> = emptyList(),
        emisBoxes: List<TownBox> = emptyList(),
        grammar: List<GrammarPart> = emptyList(),
    ) = TownLayoutSpec(
        rgb(0xC7A987), TownWater.NONE, buildings, fillers, lanterns, trees,
        emptyList(), emisBoxes, emptyList(), grammar,
    )

    /** 软影流按 5 float/顶点切成 quad 的 (x, y, z) 三元组。 */
    private fun shadowVerts(a: FloatArray): List<Triple<Float, Float, Float>> =
        (0 until a.size / 5).map { Triple(a[it * 5], a[it * 5 + 1], a[it * 5 + 2]) }

    /** 光晕流按 6 float/顶点切成 (中心 xyz, size)。 */
    private fun glowVerts(a: FloatArray): List<Pair<Triple<Float, Float, Float>, Float>> =
        (0 until a.size / 6).map { Triple(a[it * 6], a[it * 6 + 1], a[it * 6 + 2]) to a[it * 6 + 5] }

    // ─────────────────────────── 跨距与顶点数 ───────────────────────────

    @Test
    fun strideContract() {
        assertEquals("软影 pos3+uv2 = 20 字节", 20, TownOverlayGeometry.SHADOW_STRIDE)
        assertEquals("光晕 center3+uv2+size1 = 24 字节", 24, TownOverlayGeometry.GLOW_STRIDE)
    }

    @Test
    fun eachShadowIsOneQuadOfSixVertices() {
        val d = TownOverlayGeometry.build(
            spec(buildings = listOf(TownBuilding(0.0, 0.0, 3.2, 2.4, 2.8, WALLC, ROOFC, 2))),
        )
        assertEquals("一栋建筑 = 一片影 = 6 顶点 × 5 float", 30, d.shadows.size)
    }

    // ─────────────────────────── 软影落位 ───────────────────────────

    @Test
    fun shadowsAreFlatAtGroundHeight() {
        val d = TownOverlayGeometry.build(
            spec(
                buildings = listOf(TownBuilding(0.0, 0.0, 3.2, 2.4, 2.8, WALLC, ROOFC, 2)),
                trees = listOf(TownTree(5.0, 5.0, 1.0, LEAF, 0.7, 1.5)),
                lanterns = listOf(TownLantern(-4.0, 2.0)),
            ),
        )
        assertTrue("软影流非空", d.shadows.isNotEmpty())
        for ((_, y, _) in shadowVerts(d.shadows)) assertEquals("软影恒平放在 y=0.015", 0.015f, y, eps)
    }

    @Test
    fun buildingShadowHalfExtentIsHalfSizePlus0_6() {
        val d = TownOverlayGeometry.build(
            spec(buildings = listOf(TownBuilding(2.0, -3.0, 3.2, 2.4, 2.8, WALLC, ROOFC, 2))),
        )
        val xs = shadowVerts(d.shadows).map { it.first }
        val zs = shadowVerts(d.shadows).map { it.third }
        assertEquals("x 半宽 = 3.2/2 + 0.6", (2.0 - (3.2 / 2 + 0.6)).toFloat(), xs.min(), eps)
        assertEquals((2.0 + (3.2 / 2 + 0.6)).toFloat(), xs.max(), eps)
        assertEquals("z 半宽 = 2.8/2 + 0.6", (-3.0 - (2.8 / 2 + 0.6)).toFloat(), zs.min(), eps)
        assertEquals((-3.0 + (2.8 / 2 + 0.6)).toFloat(), zs.max(), eps)
    }

    @Test
    fun treeShadowRadiusScalesWithTreeSize() {
        val d = TownOverlayGeometry.build(spec(trees = listOf(TownTree(0.0, 0.0, 1.6, LEAF, 0.7, 1.5))))
        val xs = shadowVerts(d.shadows).map { it.first }
        assertEquals("树影半径 = 0.85s + 0.5", (0.85 * 1.6 + 0.5).toFloat(), xs.max(), eps)
    }

    @Test
    fun grammarWallCastsShadowOnlyWhenGroundedAndTall() {
        val grounded = GrammarPart.LitBox(0.0, 0.0, 0.0, 2.0, 1.4, 1.6, WALLC, GrammarPart.BoxRole.WALL)
        val upperFloor = GrammarPart.LitBox(0.0, 1.4, 0.0, 1.8, 1.3, 1.4, WALLC, GrammarPart.BoxRole.WALL_UPPER)
        val porch = GrammarPart.LitBox(6.0, 0.0, 0.0, 0.5, 0.9, 1.0, WALLC, GrammarPart.BoxRole.PORCH_POST)
        val d = TownOverlayGeometry.build(spec(grammar = listOf(grounded, upperFloor, porch)))
        assertEquals("只有落地且高 ≥ 1.2 的墙体投影 = 1 片 quad", 30, d.shadows.size)
        val xs = shadowVerts(d.shadows).map { it.first }
        // corner-anchored → 中心 x = 0 + 2.0/2 = 1.0；半宽 = 2.0/2 + 0.5。
        assertEquals((1.0 - (2.0 / 2 + 0.5)).toFloat(), xs.min(), eps)
        assertEquals((1.0 + (2.0 / 2 + 0.5)).toFloat(), xs.max(), eps)
    }

    // ─────────────────────────── 光晕落位 ───────────────────────────

    @Test
    fun windowGlowCentersMatchWindowEmissionFormula() {
        val b = TownBuilding(1.0, -2.0, 3.2, 2.4, 2.8, WALLC, ROOFC, windows = 3)
        val d = TownOverlayGeometry.build(spec(buildings = listOf(b)))
        val centers = glowVerts(d.glows).map { it.first }.distinct()
        assertEquals("3 扇窗 = 3 枚光晕", 3, centers.size)
        for (i in 0 until 3) {
            val wx = 1.0 + (i - (3 - 1) / 2.0) * 0.9          // = TownGeometry.windows 同式
            val c = centers[i]
            assertEquals("窗 $i 横向中心", wx.toFloat(), c.first, eps)
            assertEquals("窗高 0.55 的中点 = 0.8 + 0.275", (0.8 + 0.275).toFloat(), c.second, eps)
            assertEquals("外推 0.20 悬在墙前", (-2.0 + 2.8 / 2 + 0.20).toFloat(), c.third, eps)
        }
        assertEquals("窗光晕尺寸", 2.2f, glowVerts(d.glows).first().second, eps)
    }

    @Test
    fun fillerWindowGlowUsesFillerAnchors() {
        val d = TownOverlayGeometry.build(spec(fillers = listOf(TownFiller(4.0, 5.0, WALLC))))
        val c = glowVerts(d.glows).first().first
        assertEquals(4.0f, c.first, eps)
        assertEquals((0.7 + 0.275).toFloat(), c.second, eps)
        assertEquals((5.0 + 1.05 + 0.20).toFloat(), c.third, eps)
    }

    @Test
    fun lanternGlowSitsAtBaseYPlus1_75() {
        val d = TownOverlayGeometry.build(spec(lanterns = listOf(TownLantern(-1.0, 2.0, baseY = 1.5))))
        val (c, size) = glowVerts(d.glows).first()
        assertEquals(-1.0f, c.first, eps)
        assertEquals("baseY + 1.6 + 0.15", (1.5 + 1.75).toFloat(), c.second, eps)
        assertEquals(2.0f, c.third, eps)
        assertEquals("灯头光晕尺寸", 2.6f, size, eps)
    }

    @Test
    fun emisBoxGlowIsSizeClampedAndSitsAboveCenter() {
        val small = TownBox(0.0, 1.2, 0.0, 0.3, 0.3, 0.3, WALLC)     // 0.3×2.5 = 0.75 → 钳到 1.8
        val mid = TownBox(9.0, 0.0, 0.0, 1.0, 0.4, 0.6, WALLC)       // 1.0×2.5 = 2.5
        val big = TownBox(18.0, 0.0, 0.0, 2.0, 0.4, 1.0, WALLC)      // 2.0×2.5 = 5.0 → 钳到 3.2
        val d = TownOverlayGeometry.build(spec(emisBoxes = listOf(small, mid, big)))
        val g = glowVerts(d.glows).distinct()
        assertEquals(3, g.size)
        assertEquals("下限钳位", 1.8f, g[0].second, eps)
        assertEquals(2.5f, g[1].second, eps)
        assertEquals("上限钳位", 3.2f, g[2].second, eps)
        assertEquals("盒中心上方 0.1", (1.2 + 0.3 / 2 + 0.1).toFloat(), g[0].first.second, eps)
    }

    @Test
    fun grammarWindowsGetNoGlow() {
        val d = TownOverlayGeometry.build(
            spec(grammar = listOf(GrammarPart.EmisBox(0.0, 0.6, 0.0, 0.28, 0.34, 0.06, WALLC))),
        )
        assertEquals("语法窗有意不发光晕（缺朝向数据·后续台阶补）", 0, d.glows.size)
    }

    @Test
    fun eachGlowIsOneBillboardQuadWithFourDistinctCorners() {
        val d = TownOverlayGeometry.build(spec(lanterns = listOf(TownLantern(0.0, 0.0))))
        assertEquals("一枚光晕 = 6 顶点 × 6 float", 36, d.glows.size)
        val uvs = (0 until 6).map { d.glows[it * 6 + 3] to d.glows[it * 6 + 4] }.toSet()
        assertEquals("四角 uv 齐备", setOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f), uvs)
    }

    @Test
    fun emptyTownProducesEmptyStreams() {
        val d = TownOverlayGeometry.build(spec())
        assertEquals(0, d.shadows.size)
        assertEquals(0, d.glows.size)
    }
}
