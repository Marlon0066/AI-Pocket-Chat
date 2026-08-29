package com.situ.aichat.ui.world.town

import kotlin.math.max

/**
 * 两条覆盖流的几何（台阶1 图纸 §3.4·[TownOverlayGeometry.build] 产出）：
 * - [shadows] 软影：平放于 y=[SHADOW_Y] 的 quad·每顶点 `pos3 + uv2`（5 float）；
 * - [glows] 窗火光晕：billboard·每顶点 `center3 + 角uv2 + 尺寸1`（6 float），朝向由渲染器每帧的相机右/上向量在
 *   顶点着色器里展开（故 CPU 侧只存中心与尺寸，转相机不需重传）。
 */
internal class TownOverlayData(val shadows: FloatArray, val glows: FloatArray)

/**
 * 软影 + 窗火光晕两条覆盖流的**纯计算发射器**（消费 [TownLayoutSpec]·零 Android / 零 GL / 零随机）。
 * 发射表 = 图纸 §3.4 锁定：软影落在建筑 / 填充民居 / 树 / 灯柱 / 落地语法墙体下；光晕落在建筑窗 / 填充民居窗 /
 * 灯头 / 环境 emis 盒上（语法窗不发光晕——其朝向数据缺失·§0-③ 有意不做）。
 *
 * 水域上方的软影由深度自然遮蔽（水面 quad y = 0.02/0.03 > [SHADOW_Y]），故无需额外剔除。
 */
internal object TownOverlayGeometry {

    /** 软影平面高度（贴地不与地面 z-fight·低于水面故被水遮蔽）。 */
    const val SHADOW_Y = 0.015f

    /** 顶点跨距（字节）：软影 pos3+uv2 = 20 · 光晕 center3+uv2+size1 = 24。 */
    const val SHADOW_STRIDE = 5 * 4
    const val GLOW_STRIDE = 6 * 4

    // 光晕尺寸（§3.4 锁定）。
    private const val WINDOW_GLOW_SIZE = 2.2f
    private const val LANTERN_GLOW_SIZE = 2.6f
    private const val EMIS_BOX_GLOW_MIN = 1.8f
    private const val EMIS_BOX_GLOW_MAX = 3.2f

    fun build(spec: TownLayoutSpec): TownOverlayData {
        val shadows = Buf(5, 2048)
        val glows = Buf(6, 2048)

        // ── 软影 ──
        for (b in spec.buildings) shadow(shadows, b.cx, b.cz, b.sx / 2 + 0.6, b.sz / 2 + 0.6)
        for (f in spec.fillers) shadow(shadows, f.cx, f.cz, 2.4 / 2 + 0.6, 2.1 / 2 + 0.6)
        for (t in spec.trees) shadow(shadows, t.cx, t.cz, 0.85 * t.s + 0.5, 0.85 * t.s + 0.5)
        for (l in spec.lanterns) shadow(shadows, l.cx, l.cz, 0.5, 0.5)
        for (p in spec.grammar) {
            if (p !is GrammarPart.LitBox || p.y != 0.0 || p.h < 1.2) continue
            shadow(shadows, p.x + p.sx / 2, p.z + p.sz / 2, p.sx / 2 + 0.5, p.sz / 2 + 0.5)
        }

        // ── 光晕（窗中心 = [TownGeometry.windows] 同式复算·外推 0.20 悬在墙面前）──
        for (b in spec.buildings) {
            windowGlows(glows, b.cx, 0.8, b.cz + b.sz / 2, b.windows)
        }
        for (f in spec.fillers) {
            windowGlows(glows, f.cx, 0.7, f.cz + 1.05, 1)
        }
        for (l in spec.lanterns) {
            billboard(glows, l.cx, l.baseY + 1.6 + 0.15, l.cz, LANTERN_GLOW_SIZE)
        }
        for (bx in spec.emisBoxes) {
            val size = (max(bx.sx, bx.sz) * 2.5).coerceIn(EMIS_BOX_GLOW_MIN.toDouble(), EMIS_BOX_GLOW_MAX.toDouble())
            billboard(glows, bx.cx, bx.y0 + bx.h / 2 + 0.1, bx.cz, size.toFloat())
        }

        return TownOverlayData(shadows.toFloatArray(), glows.toFloatArray())
    }

    /** 一栋建筑 / 一棵树 / 一根灯柱的软影 quad（中心 [cx],[cz]·半宽 [hx]×[hz]·平放 y=[SHADOW_Y]）。 */
    private fun shadow(out: Buf, cx: Double, cz: Double, hx: Double, hz: Double) {
        val x0 = (cx - hx).toFloat(); val x1 = (cx + hx).toFloat()
        val z0 = (cz - hz).toFloat(); val z1 = (cz + hz).toFloat()
        fun v(x: Float, z: Float, u: Float, w: Float) = out.put(x, SHADOW_Y, z, u, w)
        v(x0, z0, 0f, 0f); v(x1, z0, 1f, 0f); v(x1, z1, 1f, 1f)
        v(x0, z0, 0f, 0f); v(x1, z1, 1f, 1f); v(x0, z1, 0f, 1f)
    }

    /** 一排窗的光晕（[n] 扇·横向间距 0.9·= [TownGeometry.windows] 的中心式 + 半窗高 0.275 + 外推 0.20）。 */
    private fun windowGlows(out: Buf, cx: Double, y: Double, czFront: Double, n: Int) {
        for (i in 0 until n) {
            val wx = cx + (i - (n - 1) / 2.0) * 0.9
            billboard(out, wx, y + 0.275, czFront + 0.20, WINDOW_GLOW_SIZE)
        }
    }

    /** 一枚朝向相机的光晕方片（中心 + 四角 uv + 边长·真实展开在顶点着色器里做）。 */
    private fun billboard(out: Buf, cx: Double, cy: Double, cz: Double, size: Float) {
        val x = cx.toFloat(); val y = cy.toFloat(); val z = cz.toFloat()
        fun v(u: Float, w: Float) = out.put(x, y, z, u, w, size)
        v(0f, 0f); v(1f, 0f); v(1f, 1f)
        v(0f, 0f); v(1f, 1f); v(0f, 1f)
    }

    /** 简易可增长 Float 缓冲（[comps] = 每顶点分量数·只为避免逐顶点装箱）。 */
    private class Buf(private val comps: Int, initial: Int) {
        private var buf = FloatArray(initial)
        private var size = 0
        fun put(vararg v: Float) {
            require(v.size == comps)
            if (size + comps > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + comps))
            for (f in v) buf[size++] = f
        }
        fun toFloatArray(): FloatArray = buf.copyOf(size)
    }
}
