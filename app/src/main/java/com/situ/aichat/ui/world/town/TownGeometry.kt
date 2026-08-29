package com.situ.aichat.ui.world.town

import com.situ.aichat.ui.world.continent.TriStream
import com.situ.aichat.ui.world.continent.rgb
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 材质桶（台阶1 图纸 §3.2 锁定路由·枚举序 = 锁定渲染序 ground→stone→plain→wall→roof→foliage）。分桶只为
 * 「每桶绑各自材质贴图」——顶点格式与坐标/颜色一律零改（顶点数守恒有 T1 钉死）。[PLAIN] 桶不贴图。
 */
internal enum class TownBucket { GROUND, STONE, PLAIN, WALL, ROOF, FOLIAGE }

/**
 * 一座小镇的三角流几何（[TownGeometry.buildTown] 产出·GL 上传用 Float 交错流·9 分量/顶点）：lit 按 §3.2 分成
 * 六桶（水体仍在 lit 侧的 [plain] 桶内·demo 无独立水 pass）+ [emis] 自发光一流。
 */
internal class TownGeometryData(
    val ground: FloatArray,
    val stone: FloatArray,
    val wall: FloatArray,
    val roof: FloatArray,
    val foliage: FloatArray,
    val plain: FloatArray,
    val emis: FloatArray,
) {
    /** lit 六桶按 §3.2 锁定渲染序配对其材质桶（渲染器逐桶换绑贴图用）。 */
    val litByBucket: List<Pair<TownBucket, FloatArray>> = listOf(
        TownBucket.GROUND to ground, TownBucket.STONE to stone, TownBucket.PLAIN to plain,
        TownBucket.WALL to wall, TownBucket.ROOF to roof, TownBucket.FOLIAGE to foliage,
    )
}

/**
 * 小镇盒景几何构建（W9c 图纸 §2/§4.1A-D·demo:L141-188 逐式）：地面 52×52 + 水体（西河/东海）+ 建筑（box+屋顶+
 * 发光窗）+ 填充民居 + 灯柱 + 树 + 环境件（码头/窑/高台/灯塔/滩伞/礁石…）→ lit/emis 两流；另建小镇星点 16 颗
 * （[buildTownStars]·§4.1E）。纯计算（Double 落 Float）·零 Android / 零 DB·Compose 层 Dispatchers.Default 调用。
 * 原语 [TriStream]/[rgb] 复用自 continent 包（图纸 §9 复用不改）。
 */
internal object TownGeometry {

    /** 小镇星点数（demo:L67·`for(s<16)`）。 */
    const val STAR_COUNT = 16

    private val TRUNK = rgb(0x6B5138)
    private val WINDOW = rgb(0xFFD9A0)
    private val LANTERN_POST = rgb(0x4A4038)
    private val RIVER = rgb(0x7FA3AD)
    private val SEA = rgb(0x35787C)
    private val BEACH = rgb(0xEDD9AC)

    /**
     * 街/广场判据（§3.2 锁定）：薄（[TownBox.h] ≤ 0.12）且大面积（sx×sz ≥ 6.0）的环境盒 = 石板铺装 →
     * [TownBucket.STONE]；其余环境盒（码头板/长椅/礁石/窑…）走 [TownBucket.PLAIN]。
     */
    private fun isPaving(b: TownBox) = b.h <= 0.12 && b.sx * b.sz >= 6.0

    fun buildTown(spec: TownLayoutSpec): TownGeometryData {
        val ground = TriStream(1024)
        val stone = TriStream(2048)
        val wall = TriStream(1 shl 14)
        val roof = TriStream(1 shl 14)
        val foliage = TriStream(4096)
        val plain = TriStream(4096)
        val emis = TriStream(2048)

        // ── 地面 52×52（demo:L144·顶点序 = 面法线朝上）→ ground 桶；水体（西河/东海·demo:L146）→ plain（不贴图·半透水观感零变）──
        ground.quad(v(-26.0, 0.0, 26.0), v(26.0, 0.0, 26.0), v(26.0, 0.0, -26.0), v(-26.0, 0.0, -26.0), spec.ground)
        when (spec.water) {
            TownWater.WEST_RIVER ->
                plain.quad(v(-16.5, 0.03, 26.0), v(-11.5, 0.03, 26.0), v(-11.5, 0.03, -26.0), v(-16.5, 0.03, -26.0), RIVER)
            TownWater.EAST_SEA -> {
                plain.quad(v(13.0, 0.03, 26.0), v(26.0, 0.03, 26.0), v(26.0, 0.03, -26.0), v(13.0, 0.03, -26.0), SEA)
                plain.quad(v(10.5, 0.02, 26.0), v(13.0, 0.02, 26.0), v(13.0, 0.02, -26.0), v(10.5, 0.02, -26.0), BEACH)
            }
            TownWater.NONE -> Unit
        }

        // ── 建筑（box → wall + roof(sx×1.12, 1.1, sz×1.16) → roof + 发光窗 → emis·demo:L169-171）──
        for (b in spec.buildings) {
            wall.box(b.cx, 0.0, b.cz, b.sx, b.h, b.sz, b.wall)
            roof.roof(b.cx, b.h, b.cz, b.sx * 1.12, 1.1, b.sz * 1.16, b.roof)
            windows(emis, b.cx, 0.8, b.cz + b.sz / 2, b.windows)
        }

        // ── 填充民居（2.4×1.8×2.1 → wall + roof 2.7×0.9×2.45 #6B5A50 → roof + 1 窗·demo:L184-186）──
        for (f in spec.fillers) {
            wall.box(f.cx, 0.0, f.cz, 2.4, 1.8, 2.1, f.wall)
            roof.roof(f.cx, 1.8, f.cz, 2.7, 0.9, 2.45, ROOF_FILLER)
            windows(emis, f.cx, 0.7, f.cz + 1.05, 1)
        }

        // ── 灯柱（柱 0.14×1.6×0.14 #4A4038 → plain + emis 灯头 0.3³·demo:L138-139·[TownLantern.baseY] 支持台上灯）──
        for (l in spec.lanterns) {
            plain.box(l.cx, l.baseY, l.cz, 0.14, 1.6, 0.14, LANTERN_POST)
            emis.box(l.cx, l.baseY + 1.6, l.cz, 0.3, 0.3, 0.3, WINDOW)
        }

        // ── 树（trunk box → plain + 四面锥 r0.85s×coneH·s → foliage·demo:L125-132·普通/椰树变体）──
        for (t in spec.trees) {
            plain.box(t.cx, 0.0, t.cz, 0.28 * t.s, t.trunkH * t.s, 0.28 * t.s, TRUNK)
            foliage.cone(t.cx, t.trunkH * t.s, t.cz, 0.85 * t.s, t.coneH * t.s, t.leaf)
        }

        // ── 环境件（街/广场石板 → stone·其余码头/窑/高台/灯塔/长椅/礁石/栈道 → plain·窑口/顶灯 = emis·滩伞 cone → plain）──
        for (bx in spec.litBoxes) (if (isPaving(bx)) stone else plain).box(bx.cx, bx.y0, bx.cz, bx.sx, bx.h, bx.sz, bx.col)
        for (bx in spec.emisBoxes) emis.box(bx.cx, bx.y0, bx.cz, bx.sx, bx.h, bx.sz, bx.col)
        for (c in spec.cones) plain.cone(c.cx, c.y, c.cz, c.r, c.h, c.col)

        // ── 语法建筑原语件（§3.1·[TownGrammar] 产出·corner-anchored → center 换算发射；程序城/精修补充段填充·精修主体恒空）──
        emitGrammar(wall, roof, plain, emis, spec.grammar)

        return TownGeometryData(
            ground.toFloatArray(), stone.toFloatArray(), wall.toFloatArray(), roof.toFloatArray(),
            foliage.toFloatArray(), plain.toFloatArray(), emis.toFloatArray(),
        )
    }

    /**
     * 发射一批语法原语件（§3.1）。§3.2 分桶路由：LitBox 高 ≥ 1.2 = 墙体 → [wall]，其余小件（烟囱/门廊柱/
     * 门廊顶板/招牌）→ [plain]；屋顶三式（含 FLAT 的 parapet 三盒）→ [roof]；窗 → [emis]。
     */
    private fun emitGrammar(wall: TriStream, roof: TriStream, plain: TriStream, emis: TriStream, parts: List<GrammarPart>) {
        for (p in parts) when (p) {
            is GrammarPart.LitBox ->
                (if (p.h >= 1.2) wall else plain).box(p.x + p.sx / 2, p.y, p.z + p.sz / 2, p.sx, p.h, p.sz, p.col)
            is GrammarPart.EmisBox -> emis.box(p.x + p.sx / 2, p.y, p.z + p.sz / 2, p.sx, p.h, p.sz, p.col)
            is GrammarPart.Roof -> when (p.style) {
                RoofStyle.GABLE -> gable(roof, p.x, p.y, p.z, p.sx, p.h, p.sz, p.col)
                RoofStyle.PYRAMID -> pyramid(roof, p.x, p.y, p.z, p.sx, p.h, p.sz, p.col)
                RoofStyle.FLAT -> parapet(roof, p.x, p.y, p.z, p.sx, p.h, p.sz, p.col)
            }
        }
    }

    /** 双坡脊顶（board:L77·脊沿 x）——几何 = 复用的 continent [TriStream.roof]（两坡面 + 两山墙三角）·corner→center 换算。 */
    private fun gable(out: TriStream, x: Double, y: Double, z: Double, sx: Double, h: Double, sz: Double, col: DoubleArray) {
        out.roof(x + sx / 2, y, z + sz / 2, sx, h, sz, col)
    }

    /**
     * 四坡锥顶（board:L85·矩形底 + 单顶点）——continent `cone` 仅方底故此处新增矩形版：四面三角，绕序沿用 `cone`
     * （外向法线·参 [TriStream.cone]），顶点在底面中心正上方 `y+h`。
     */
    private fun pyramid(out: TriStream, x: Double, y: Double, z: Double, sx: Double, h: Double, sz: Double, col: DoubleArray) {
        val x1 = x + sx; val z1 = z + sz
        val apex = v(x + sx / 2, y + h, z + sz / 2)
        out.tri(v(x, y, z1), v(x1, y, z1), apex, col)   // +Z 面
        out.tri(v(x1, y, z1), v(x1, y, z), apex, col)   // +X 面
        out.tri(v(x1, y, z), v(x, y, z), apex, col)     // -Z 面
        out.tri(v(x, y, z), v(x, y, z1), apex, col)     // -X 面
    }

    /** 平顶女儿墙（board:L124·底板 + 两侧墙 = 三盒·[h] = 底板厚 0.18·侧墙 0.14×0.3×sz·右墙贴 [sx] 右缘）。 */
    private fun parapet(out: TriStream, x: Double, y: Double, z: Double, sx: Double, h: Double, sz: Double, col: DoubleArray) {
        out.box(x + sx / 2, y, z + sz / 2, sx, h, sz, col)                        // 底板
        out.box(x + 0.07, y + h, z + sz / 2, 0.14, 0.3, sz, col)                  // 左女儿墙
        out.box(x + sx - 0.07, y + h, z + sz / 2, 0.14, 0.3, sz, col)             // 右女儿墙
    }

    /** 发光窗（demo:L133-137·前面 z+·发光窗 quad 0.56 宽×0.55 高·间距 0.9·贴 z 面 +0.011）。 */
    private fun windows(emis: TriStream, cx: Double, y: Double, czFront: Double, n: Int) {
        val z = czFront + 0.011
        for (i in 0 until n) {
            val wx = cx + (i - (n - 1) / 2.0) * 0.9
            emis.quad(v(wx - 0.28, y, z), v(wx + 0.28, y, z), v(wx + 0.28, y + 0.55, z), v(wx - 0.28, y + 0.55, z), WINDOW)
        }
    }

    /**
     * 小镇星点 16 颗（§4.1E·vec4：x_ndc, y_ndc, 基础尺寸 px, 相位 rad）。位置 y 落屏上 30%（demo:L69）·尺寸
     * 30% 概率 2px 其余 1.4px·由世界 [seed] 派生（盐 `xor 0x9C`·与星球/大陆星空不同一片）·闪烁复用 STAR_FS。
     */
    fun buildTownStars(seed: Long): FloatArray {
        val rnd = Random(seed xor 0x9CL)
        val out = FloatArray(STAR_COUNT * 4)
        for (s in 0 until STAR_COUNT) {
            val size = if (rnd.nextFloat() < 0.3f) 2f else 1.4f
            val x = rnd.nextFloat() * 2f - 1f
            val topFrac = rnd.nextFloat() * 0.30f
            val y = 1f - 2f * topFrac
            val phase = rnd.nextFloat() * 2f * PI.toFloat()
            out[s * 4] = x; out[s * 4 + 1] = y; out[s * 4 + 2] = size; out[s * 4 + 3] = phase
        }
        return out
    }

    // ─────────────────────────── 远景层（§3.3 修订·世界锚定环带·emis 程序 flat 上色）───────────────────────────

    private const val FAR_SEG = 48        // 剪影环分段
    private const val FAR_BASE_Y = -2.0   // 剪影带基高（世界·§11 视觉可调）

    /**
     * 远景层几何（图纸 §3.3）：双层山剪影环（远 r38 `#3A3330` / 近 r35 `#4A4038`·世界夜面族色）+ 邻村灯火 5–8
     * （`#FFD9A0`·α0.7 静态混入近层剪影脚——[ContinentShaders.C_FS_EMIS] 强制 α1.0 故静态 bake 近层底色·见 §11）。
     * 高 2.4–5.2 确定性折点（[seed] 盐 `0x5A` 派生）。全色 ÷1.15 抵消 emis 的 ×1.15 → 落 §3.3 精确色。经 emis
     * 程序以场景 MVP 绘于 lit 之前（背景层·深度关·[TownRenderer]）。返回 9 分量交错流（法线 emis 忽略）。
     */
    fun buildFarScenery(seed: Long): FloatArray {
        val s = TriStream(4096)
        val rnd = Random(seed xor 0x5AL)
        silhouetteRing(s, 38.0, dim(rgb(0x3A3330)), rnd)   // 远层
        silhouetteRing(s, 35.0, dim(rgb(0x4A4038)), rnd)   // 近层
        val nLight = 5 + rnd.nextInt(4)                    // 5..8
        val lightCol = dim(mix(rgb(0x4A4038), rgb(0xFFD9A0), 0.7))
        for (i in 0 until nLight) {
            val a = rnd.nextDouble() * 2 * PI
            val x = 35.0 * sin(a); val z = -35.0 * cos(a); val y = FAR_BASE_Y + 0.3
            s.quad(v(x - 0.28, y, z), v(x + 0.28, y, z), v(x + 0.28, y + 0.34, z), v(x - 0.28, y + 0.34, z), lightCol)
        }
        return s.toFloatArray()
    }

    /** 一条山剪影环（[FAR_SEG] 段实心带·基 [FAR_BASE_Y]·顶 = 基 + 高 2.4–5.2·随环闭合）。 */
    private fun silhouetteRing(s: TriStream, r: Double, col: DoubleArray, rnd: Random) {
        val h = DoubleArray(FAR_SEG + 1) { 2.4 + rnd.nextDouble() * 2.8 }   // [2.4, 5.2]
        h[FAR_SEG] = h[0]
        for (i in 0 until FAR_SEG) {
            val a0 = i.toDouble() / FAR_SEG * 2 * PI; val a1 = (i + 1).toDouble() / FAR_SEG * 2 * PI
            val x0 = r * sin(a0); val z0 = -r * cos(a0); val x1 = r * sin(a1); val z1 = -r * cos(a1)
            s.quad(v(x0, FAR_BASE_Y, z0), v(x1, FAR_BASE_Y, z1), v(x1, FAR_BASE_Y + h[i + 1], z1), v(x0, FAR_BASE_Y + h[i], z0), col)
        }
    }

    private fun dim(c: DoubleArray) = doubleArrayOf(c[0] / 1.15, c[1] / 1.15, c[2] / 1.15)
    private fun mix(a: DoubleArray, b: DoubleArray, t: Double) =
        doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)

    private val ROOF_FILLER = rgb(0x6B5A50)
    private fun v(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
}
