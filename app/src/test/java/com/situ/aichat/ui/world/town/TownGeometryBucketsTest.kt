package com.situ.aichat.ui.world.town

import com.situ.aichat.ui.world.continent.TriStream
import com.situ.aichat.ui.world.continent.rgb
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TownGeometry] 分桶路由 T1（台阶1 图纸 §7 T1-2·E10/E12）。断言从图纸 §3.2 路由表与 [TriStream] 原语规则
 * **独立反推**：顶点数期望在此按「quad=6 顶点 / box(无底)=5quad=30 / roof=2quad+2tri=18 / cone=4tri=12 /
 * pyramid=12 / parapet=3box=90」重新推算，不读实现输出。
 *
 * 核心不变式（E12「分桶 = 只搬不改」）：**七流顶点总数恒等于分桶前 lit+emis 总数**——即同一批发射件按原语
 * 规则算出的总顶点数，一个不多一个不少。
 */
class TownGeometryBucketsTest {

    private val WALLC = rgb(0xC99A86)
    private val ROOFC = rgb(0x9A5B3E)
    private val LEAF = rgb(0x7E926E)
    private val PAVE = rgb(0xD9C3A3)

    private fun verts(a: FloatArray) = a.size / 9

    // ── 原语顶点数（TriStream 规则·独立打字）──
    private val QUAD = 6
    private val BOX = 5 * QUAD          // 无底盒 = 顶 + 四壁
    private val ROOF = 2 * QUAD + 2 * 3 // 两坡面 + 两山墙三角
    private val CONE = 4 * 3
    private val PYRAMID = 4 * 3
    private val PARAPET = 3 * BOX

    private fun spec(
        water: TownWater = TownWater.NONE,
        buildings: List<TownBuilding> = emptyList(),
        fillers: List<TownFiller> = emptyList(),
        lanterns: List<TownLantern> = emptyList(),
        trees: List<TownTree> = emptyList(),
        litBoxes: List<TownBox> = emptyList(),
        emisBoxes: List<TownBox> = emptyList(),
        cones: List<TownCone> = emptyList(),
        grammar: List<GrammarPart> = emptyList(),
    ) = TownLayoutSpec(rgb(0xC7A987), water, buildings, fillers, lanterns, trees, litBoxes, emisBoxes, cones, grammar)

    /** 七流顶点总数（守恒不变式的被测量）。 */
    private fun total(d: TownGeometryData) =
        verts(d.ground) + verts(d.stone) + verts(d.wall) + verts(d.roof) +
            verts(d.foliage) + verts(d.plain) + verts(d.emis)

    // ─────────────────────────── 路由表逐行 ───────────────────────────

    @Test
    fun ground_getsOnlyTheGroundQuad() {
        val d = TownGeometry.buildTown(spec())
        assertEquals("地面 52×52 = 一个 quad", QUAD, verts(d.ground))
        assertEquals("空镇其余桶全空", QUAD, total(d))
    }

    @Test
    fun water_and_beach_goToPlain_notGround() {
        val river = TownGeometry.buildTown(spec(water = TownWater.WEST_RIVER))
        assertEquals("西河 = 1 quad → plain", QUAD, verts(river.plain))
        assertEquals("地面仍只有自己那一片", QUAD, verts(river.ground))
        val sea = TownGeometry.buildTown(spec(water = TownWater.EAST_SEA))
        assertEquals("东海 + 滩 = 2 quad → plain", 2 * QUAD, verts(sea.plain))
        assertEquals("水不进 stone 桶", 0, verts(sea.stone))
    }

    @Test
    fun building_boxToWall_roofToRoof_windowsToEmis() {
        val d = TownGeometry.buildTown(
            spec(buildings = listOf(TownBuilding(0.0, 0.0, 3.2, 2.4, 2.8, WALLC, ROOFC, windows = 3))),
        )
        assertEquals("建筑主体盒 → wall", BOX, verts(d.wall))
        assertEquals("建筑屋顶 → roof", ROOF, verts(d.roof))
        assertEquals("3 扇发光窗 → emis", 3 * QUAD, verts(d.emis))
        assertEquals("建筑不落 plain", 0, verts(d.plain))
    }

    @Test
    fun filler_boxToWall_roofToRoof() {
        val d = TownGeometry.buildTown(spec(fillers = listOf(TownFiller(2.0, 2.0, WALLC))))
        assertEquals(BOX, verts(d.wall))
        assertEquals(ROOF, verts(d.roof))
        assertEquals("填充民居 1 窗", QUAD, verts(d.emis))
    }

    @Test
    fun tree_trunkToPlain_coneToFoliage() {
        val d = TownGeometry.buildTown(spec(trees = listOf(TownTree(1.0, 1.0, 1.0, LEAF, 0.7, 1.5))))
        assertEquals("树干盒 → plain", BOX, verts(d.plain))
        assertEquals("树冠锥 → foliage", CONE, verts(d.foliage))
        assertEquals("树不进 wall", 0, verts(d.wall))
    }

    @Test
    fun lantern_postToPlain_headToEmis() {
        val d = TownGeometry.buildTown(spec(lanterns = listOf(TownLantern(0.0, 0.0))))
        assertEquals("灯柱 → plain", BOX, verts(d.plain))
        assertEquals("灯头 → emis", BOX, verts(d.emis))
    }

    // ─────────────────────────── E10 街/广场判据 ───────────────────────────

    @Test
    fun mainStreetSx23_landsInStone() {
        // 程序城主街（TownBlockPlan ①）：长 23.0 × 厚 0.06 × 宽 1.2 → 0.06 ≤ 0.12 且 27.6 ≥ 6.0。
        val street = TownBox(0.5, 0.01, 0.0, 23.0, 0.06, 1.2, PAVE)
        val d = TownGeometry.buildTown(spec(litBoxes = listOf(street)))
        assertEquals("主街 → stone", BOX, verts(d.stone))
        assertEquals("主街不落 plain", 0, verts(d.plain))
    }

    @Test
    fun plazaAndAlleyAlsoStone_butThickOrSmallBoxesDoNot() {
        val plaza = TownBox(0.0, 0.02, 5.0, 4.6, 0.1, 4.6, PAVE)     // 21.16 ≥ 6·薄
        val alley = TownBox(3.0, 0.01, -4.0, 1.0, 0.06, 12.0, PAVE)  // 12.0 ≥ 6·薄
        val dock = TownBox(-9.0, 0.06, 5.0, 4.2, 0.18, 1.1, PAVE)    // 厚 0.18 > 0.12 → plain
        val bench = TownBox(1.0, 0.0, 1.0, 1.4, 0.42, 0.5, PAVE)     // 厚且小 → plain
        val thinButSmall = TownBox(2.0, 0.0, 2.0, 2.0, 0.1, 2.0, PAVE) // 薄但 4.0 < 6 → plain
        val d = TownGeometry.buildTown(spec(litBoxes = listOf(plaza, alley, dock, bench, thinButSmall)))
        assertEquals("广场 + 支巷 = 2 盒 → stone", 2 * BOX, verts(d.stone))
        assertEquals("码头板 + 长椅 + 小薄板 = 3 盒 → plain", 3 * BOX, verts(d.plain))
    }

    @Test
    fun standaloneConeGoesToPlain_notFoliage() {
        val d = TownGeometry.buildTown(spec(cones = listOf(TownCone(0.0, 1.4, 0.0, 1.1, 0.5, LEAF))))
        assertEquals("滩伞面 → plain", CONE, verts(d.plain))
        assertEquals("非树冠不进 foliage", 0, verts(d.foliage))
    }

    // ─────────────────────────── E10 语法件路由 ───────────────────────────

    @Test
    fun grammarLitBox_tallToWall_shortToPlain() {
        val tall = GrammarPart.LitBox(0.0, 0.0, 0.0, 2.0, 1.4, 1.6, WALLC, GrammarPart.BoxRole.WALL)
        val exactly12 = GrammarPart.LitBox(5.0, 0.0, 0.0, 2.0, 1.2, 1.6, WALLC, GrammarPart.BoxRole.WALL_UPPER)
        val chimney = GrammarPart.LitBox(9.0, 2.0, 0.0, 0.32, 0.7, 0.32, WALLC, GrammarPart.BoxRole.CHIMNEY)
        val sign = GrammarPart.LitBox(12.0, 1.0, 0.0, 0.08, 0.5, 0.5, WALLC, GrammarPart.BoxRole.SIGN)
        val d = TownGeometry.buildTown(spec(grammar = listOf(tall, exactly12, chimney, sign)))
        assertEquals("高 ≥ 1.2 的两件 → wall", 2 * BOX, verts(d.wall))
        assertEquals("烟囱 + 招牌 → plain", 2 * BOX, verts(d.plain))
    }

    @Test
    fun grammarRoofs_allThreeStylesGoToRoof() {
        val g = GrammarPart.Roof(RoofStyle.GABLE, 0.0, 1.4, 0.0, 2.3, 0.7, 1.8, ROOFC)
        val p = GrammarPart.Roof(RoofStyle.PYRAMID, 5.0, 4.6, 0.0, 2.1, 1.4, 2.1, ROOFC)
        val f = GrammarPart.Roof(RoofStyle.FLAT, 10.0, 2.0, 0.0, 2.4, 0.18, 2.2, ROOFC)
        val d = TownGeometry.buildTown(spec(grammar = listOf(g, p, f)))
        assertEquals("gable + pyramid + parapet 三盒 → roof", ROOF + PYRAMID + PARAPET, verts(d.roof))
        assertEquals("屋顶不落 wall", 0, verts(d.wall))
        assertEquals("屋顶不落 plain", 0, verts(d.plain))
    }

    @Test
    fun grammarEmisBox_goesToEmis() {
        val w = GrammarPart.EmisBox(0.0, 0.6, 0.0, 0.28, 0.34, 0.06, rgb(0xFFD9A0))
        val d = TownGeometry.buildTown(spec(grammar = listOf(w)))
        assertEquals(BOX, verts(d.emis))
    }

    // ─────────────────────────── E12 顶点数守恒 ───────────────────────────

    @Test
    fun vertexCountIsConservedOnAMixedTown() {
        val s = spec(
            water = TownWater.EAST_SEA,
            buildings = listOf(
                TownBuilding(0.0, 0.0, 3.2, 2.4, 2.8, WALLC, ROOFC, windows = 3),
                TownBuilding(4.0, 0.0, 2.8, 2.2, 2.4, WALLC, ROOFC, windows = 2),
            ),
            fillers = listOf(TownFiller(-3.0, 1.0, WALLC), TownFiller(6.0, -2.0, WALLC)),
            lanterns = listOf(TownLantern(1.0, 1.0), TownLantern(-2.0, 3.0, baseY = 1.5)),
            trees = listOf(TownTree(2.0, 2.0, 1.0, LEAF, 0.7, 1.5), TownTree(-4.0, -4.0, 0.8, LEAF, 0.6, 1.0)),
            litBoxes = listOf(
                TownBox(0.5, 0.01, 0.0, 23.0, 0.06, 1.2, PAVE),
                TownBox(-9.0, 0.06, 5.0, 4.2, 0.18, 1.1, PAVE),
            ),
            emisBoxes = listOf(TownBox(3.0, 1.2, 3.0, 0.3, 0.3, 0.3, rgb(0xFFD9A0))),
            cones = listOf(TownCone(7.0, 1.4, 7.0, 1.1, 0.5, LEAF)),
            grammar = listOf(
                GrammarPart.LitBox(10.0, 0.0, 5.5, 2.0, 1.4, 1.6, WALLC, GrammarPart.BoxRole.WALL),
                GrammarPart.Roof(RoofStyle.GABLE, 9.85, 1.4, 5.35, 2.3, 0.7, 1.8, ROOFC),
                GrammarPart.EmisBox(10.4, 0.6, 7.11, 0.28, 0.34, 0.06, rgb(0xFFD9A0)),
            ),
        )
        // 独立推算：地面 1quad + 海/滩 2quad + 建筑(2box+2roof+5窗quad) + 填充(2box+2roof+2窗quad)
        //          + 灯(2box柱+2box头) + 树(2box干+2cone冠) + 环境(2box+1emis box+1cone) + 语法(1box+1gable+1emis box)
        val expected =
            QUAD + 2 * QUAD +
                (2 * BOX + 2 * ROOF + 5 * QUAD) +
                (2 * BOX + 2 * ROOF + 2 * QUAD) +
                (2 * BOX + 2 * BOX) +
                (2 * BOX + 2 * CONE) +
                (2 * BOX + BOX + CONE) +
                (BOX + ROOF + BOX)
        assertEquals("七流顶点总数 = 同一批发射件的原语顶点总数（只搬不改）", expected, total(TownGeometry.buildTown(s)))
    }

    @Test
    fun vertexCountIsConservedOnEveryCuratedCity() {
        for (id in listOf("city_yunye", "city_taoqiu", "city_xiyu")) {
            val table = requireNotNull(TownLayout.tableOf(id)) { "缺精修城 $id" }
            val d = TownGeometry.buildTown(table.spec)
            assertEquals("$id 顶点守恒", expectedVerts(table.spec), total(d))
            assertTrue("$id 应有实际几何", total(d) > 1000)
        }
    }

    /** 按 §3.2 发射清单 + [TriStream] 原语规则独立推算一份布局的总顶点数（不看分桶实现）。 */
    private fun expectedVerts(spec: TownLayoutSpec): Int {
        var n = QUAD // 地面
        n += when (spec.water) {
            TownWater.NONE -> 0
            TownWater.WEST_RIVER -> QUAD
            TownWater.EAST_SEA -> 2 * QUAD
        }
        for (b in spec.buildings) n += BOX + ROOF + b.windows * QUAD
        n += spec.fillers.size * (BOX + ROOF + QUAD)
        n += spec.lanterns.size * (BOX + BOX)
        n += spec.trees.size * (BOX + CONE)
        n += spec.litBoxes.size * BOX
        n += spec.emisBoxes.size * BOX
        n += spec.cones.size * CONE
        for (p in spec.grammar) n += when (p) {
            is GrammarPart.LitBox -> BOX
            is GrammarPart.EmisBox -> BOX
            is GrammarPart.Roof -> when (p.style) {
                RoofStyle.GABLE -> ROOF
                RoofStyle.PYRAMID -> PYRAMID
                RoofStyle.FLAT -> PARAPET
            }
        }
        return n
    }

    // ─────────────────────────── emis 流内容零变 ───────────────────────────

    @Test
    fun emisStreamIsByteIdenticalToIndependentEmission() {
        val s = spec(
            buildings = listOf(TownBuilding(0.0, 0.0, 3.2, 2.4, 2.8, WALLC, ROOFC, windows = 3)),
            fillers = listOf(TownFiller(-3.0, 1.0, WALLC)),
            lanterns = listOf(TownLantern(1.0, 1.0, baseY = 1.5)),
            emisBoxes = listOf(TownBox(3.0, 1.2, 3.0, 0.3, 0.3, 0.3, rgb(0xFFD9A0))),
            grammar = listOf(GrammarPart.EmisBox(10.4, 0.6, 7.11, 0.28, 0.34, 0.06, rgb(0xFFD9A0))),
        )
        // 独立重发：按 §3.2「全部 emis 流不变」的发射序（建筑窗 → 填充窗 → 灯头 → emisBoxes → 语法窗）复算。
        val window = rgb(0xFFD9A0)
        val ref = TriStream(2048)
        fun win(cx: Double, y: Double, czFront: Double, n: Int) {
            val z = czFront + 0.011
            for (i in 0 until n) {
                val wx = cx + (i - (n - 1) / 2.0) * 0.9
                ref.quad(
                    doubleArrayOf(wx - 0.28, y, z), doubleArrayOf(wx + 0.28, y, z),
                    doubleArrayOf(wx + 0.28, y + 0.55, z), doubleArrayOf(wx - 0.28, y + 0.55, z), window,
                )
            }
        }
        win(0.0, 0.8, 0.0 + 2.8 / 2, 3)
        win(-3.0, 0.7, 1.0 + 1.05, 1)
        ref.box(1.0, 1.5 + 1.6, 1.0, 0.3, 0.3, 0.3, window)
        ref.box(3.0, 1.2, 3.0, 0.3, 0.3, 0.3, window)
        ref.box(10.4 + 0.28 / 2, 0.6, 7.11 + 0.06 / 2, 0.28, 0.34, 0.06, window)
        assertArrayEquals("emis 流内容逐字节零变", ref.toFloatArray(), TownGeometry.buildTown(s).emis, 0f)
    }
}
