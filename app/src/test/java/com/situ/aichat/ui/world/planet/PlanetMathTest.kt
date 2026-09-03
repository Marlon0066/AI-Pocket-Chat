package com.situ.aichat.ui.world.planet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * [PlanetMath] T1（W9a 图纸 §5 E1–E5 / §7 T1-1）：金标从 demo 规格**独立反推**——
 * 矩阵按数学定义在测里现算期望、噪声按 GLSL 公式独立复算、映射/搜索/可见性逐点断言。
 * 目的：任何一处常量/索引/表达式漂移（图纸 §9 禁改项）即红。
 */
class PlanetMathTest {

    private val eps = 1e-5f

    // ─────────────────────────── E1 mat4 金标 ───────────────────────────

    @Test
    fun persp_populatesColumnMajorSlots() {
        val m = PlanetMath.persp(0.9f, 1.5f, 0.1f, 30f)
        val t = 1f / tan(0.9f / 2f)
        // 只在这五个列主序槽有值，其余恒 0。
        assertEquals(t / 1.5f, m[0], eps)
        assertEquals(t, m[5], eps)
        assertEquals((30f + 0.1f) / (0.1f - 30f), m[10], eps)
        assertEquals(-1f, m[11], eps)
        assertEquals(2f * 30f * 0.1f / (0.1f - 30f), m[14], eps)
        val nonZero = setOf(0, 5, 10, 11, 14)
        for (i in 0 until 16) if (i !in nonZero) assertEquals("slot $i", 0f, m[i], eps)
    }

    @Test
    fun rotXtimesRotY_composesLikeApplyingRotYthenRotX() {
        val pitch = 0.3f
        val yaw = 0.7f
        val model = PlanetMath.mul(PlanetMath.rotX(pitch), PlanetMath.rotY(yaw))
        val px = 0.2f; val py = 0.5f; val pz = -0.3f
        // 独立：先绕 Y 再绕 X（标准旋转公式）。
        val cy = cos(yaw); val sy = sin(yaw)
        val qx = cy * px + sy * pz
        val qy = py
        val qz = -sy * px + cy * pz
        val cx = cos(pitch); val sx = sin(pitch)
        val ex = qx
        val ey = cx * qy - sx * qz
        val ez = sx * qy + cx * qz
        val out = PlanetMath.v4(model, px, py, pz)
        assertEquals(ex, out[0], eps)
        assertEquals(ey, out[1], eps)
        assertEquals(ez, out[2], eps)
        assertEquals(1f, out[3], eps) // 纯旋转 → w 不变
    }

    @Test
    fun v4_appliesTranslationColumn() {
        val out = PlanetMath.v4(PlanetMath.trans(2.5f), 1f, 2f, 3f)
        assertEquals(1f, out[0], eps)
        assertEquals(2f, out[1], eps)
        assertEquals(3f + 2.5f, out[2], eps)
        assertEquals(1f, out[3], eps)
    }

    // ─────────────────────────── E2 CPU 噪声与 shader 同式 ───────────────────────────

    // 从 NOISE GLSL（demo:L86-94）独立复写的参照实现（float32）。
    private fun refFract(x: Float) = x - floor(x)
    private fun refMix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun refHash(x: Float, y: Float, z: Float): Float =
        refFract(sin(x * 127.1f + y * 311.7f + z * 74.7f) * 43758.5453123f)

    private fun refVnoise(px: Float, py: Float, pz: Float): Float {
        val ix = floor(px); val iy = floor(py); val iz = floor(pz)
        var fx = px - ix; var fy = py - iy; var fz = pz - iz
        fx = fx * fx * (3f - 2f * fx); fy = fy * fy * (3f - 2f * fy); fz = fz * fz * (3f - 2f * fz)
        val n000 = refHash(ix, iy, iz); val n100 = refHash(ix + 1f, iy, iz)
        val n010 = refHash(ix, iy + 1f, iz); val n110 = refHash(ix + 1f, iy + 1f, iz)
        val n001 = refHash(ix, iy, iz + 1f); val n101 = refHash(ix + 1f, iy, iz + 1f)
        val n011 = refHash(ix, iy + 1f, iz + 1f); val n111 = refHash(ix + 1f, iy + 1f, iz + 1f)
        return refMix(
            refMix(refMix(n000, n100, fx), refMix(n010, n110, fx), fy),
            refMix(refMix(n001, n101, fx), refMix(n011, n111, fx), fy),
            fz,
        )
    }

    private fun refFbm(px: Float, py: Float, pz: Float): Float {
        var v = 0f; var a = 0.5f; var x = px; var y = py; var z = pz
        repeat(5) { v += a * refVnoise(x, y, z); x *= 2.03f; y *= 2.03f; z *= 2.03f; a *= 0.5f }
        return v
    }

    @Test
    fun hash_matchesGlslFormula_atThreeAnchors() {
        val anchors = listOf(
            Triple(1f, 2f, 3f),
            Triple(0f, 0f, 0f),
            Triple(-4.2f, 7.1f, 0.5f),
        )
        for ((x, y, z) in anchors) {
            assertEquals("hash($x,$y,$z)", refHash(x, y, z), PlanetMath.hash(x, y, z), eps)
        }
    }

    @Test
    fun fbm_matchesGlslFormula_andIsDeterministic() {
        val pts = listOf(Triple(0.5f, 0.5f, 0.5f), Triple(2.1f, -1.3f, 4.7f), Triple(9.0f, 9.0f, 9.0f))
        for ((x, y, z) in pts) {
            assertEquals("fbm($x,$y,$z)", refFbm(x, y, z), PlanetMath.fbm(x, y, z), eps)
            assertEquals(PlanetMath.fbm(x, y, z), PlanetMath.fbm(x, y, z), 0f) // 同输入恒同输出
        }
        // 5 octave 权重和上界 0.96875·vnoise∈[0,1] → fbm∈[0, 0.96875]。
        val v = PlanetMath.fbm(3.3f, -2.2f, 1.1f)
        assertTrue(v in 0f..0.96876f)
    }

    // ─────────────────────────── E3 图集→球面映射 ───────────────────────────

    @Test
    fun atlasMapping_boundaries() {
        val (lat0, lon0) = PlanetMath.atlasToLatLonDeg(0, 0)
        assertEquals(45.0, lat0, 1e-9)
        assertEquals(-180.0, lon0, 1e-9)
        val (lat1, lon1) = PlanetMath.atlasToLatLonDeg(4800, 2600)
        assertEquals(-39.0, lat1, 1e-9)
        assertEquals(180.0, lon1, 1e-9)
    }

    @Test
    fun latLonToUnit_isUnitLength() {
        val samples = listOf(3.0 to -135.0, 45.0 to -180.0, -39.0 to 180.0, 0.0 to 0.0, 28.0 to 15.0)
        for ((lat, lon) in samples) {
            val v = PlanetMath.latLonToUnit(lat, lon)
            val len = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
            assertEquals("‖($lat,$lon)‖", 1.0, len, 1e-6)
        }
    }

    @Test
    fun homeUnitVector_forCityYunye_matchesMappingThenProjection() {
        // 云野镇图集坐标 (600,1300)（WorldCuratedCities）→ lat 3°, lon -135°。
        val (lat, lon) = PlanetMath.atlasToLatLonDeg(600, 1300)
        assertEquals(3.0, lat, 1e-9)
        assertEquals(-135.0, lon, 1e-9)
        val direct = PlanetMath.homeUnitVector(600, 1300)
        val viaLatLon = PlanetMath.latLonToUnit(lat, lon)
        for (i in 0..2) assertEquals(viaLatLon[i], direct[i], eps)
    }

    // ─────────────────────────── E4 seedOff 陆地搜索 ───────────────────────────

    private fun candidateFor(seed: Long, k: Int): Float =
        ((seed.mod(1000L)) / 100.0 + 3.0 + k * 0.37).toFloat()

    @Test
    fun deriveSeedOff_isDeterministic_andPicksFirstLandCandidate() {
        val home = PlanetMath.homeUnitVector(600, 1300)
        val a = PlanetMath.deriveSeedOff(1234567L, home)
        val b = PlanetMath.deriveSeedOff(1234567L, home)
        assertEquals(a, b, 0f) // 同 seed 恒同 seedOff

        // 独立复算 24 候选，找首个 cont≥0.55；断言 deriveSeedOff 选中它，且该点确在陆地。
        var expected: Float? = null
        for (k in 0 until 24) {
            val cand = candidateFor(1234567L, k)
            val cont = PlanetMath.fbm(home[0] * 2f + cand, home[1] * 2f + cand, home[2] * 2f + cand)
            if (cont >= 0.55f) { expected = cand; break }
        }
        if (expected != null) {
            assertEquals(expected, a, 0f)
            val cont = PlanetMath.fbm(home[0] * 2f + a, home[1] * 2f + a, home[2] * 2f + a)
            assertTrue("winner cont ≥ 0.55", cont >= 0.55f)
        }
    }

    @Test
    fun searchLandOffset_fallsBackToBase_whenSamplerIsAllOcean() {
        // 假采样器恒返 0（全海）→ 24 候选全败 → 回退 base（图纸 E4「构造全败假 fbm 时返 base」）。
        val base = 5.0
        assertEquals(base.toFloat(), PlanetMath.searchLandOffset(base) { 0f }, 0f)
    }

    @Test
    fun searchLandOffset_picksFirstQualifyingCandidate() {
        val base = 5.0
        val step = 0.37
        // 仅 k=2 与 k=4 达标 → 应选首个（k=2）。
        val k2 = (base + 2 * step).toFloat()
        val k4 = (base + 4 * step).toFloat()
        val picked = PlanetMath.searchLandOffset(base) { cand ->
            if (abs(cand - k2) < 1e-4f || abs(cand - k4) < 1e-4f) 0.6f else 0.4f
        }
        assertEquals(k2, picked, 0f)
    }

    // ─────────────────────────── E5 标记可见性阈 ───────────────────────────

    @Test
    fun projectHome_visibilityThreshold_at0_28() {
        val id = PlanetMath.identity()
        val visible = PlanetMath.projectHome(id, id, floatArrayOf(0f, 0f, 0.281f), 1000f, 2000f)
        assertTrue(visible.visible)
        val hidden = PlanetMath.projectHome(id, id, floatArrayOf(0f, 0f, 0.279f), 1000f, 2000f)
        assertFalse(hidden.visible)
    }

    @Test
    fun projectHome_screenCoordsWithinViewport_whenFrontFacing() {
        val m = PlanetMath.sceneMatrices(yaw = 0f, pitch = 0f, dist = 3.1f, aspect = 0.5f)
        // 正对镜头的球面点（+z 面朝观察者）。
        val home = floatArrayOf(0f, 0f, 1f)
        val p = PlanetMath.projectHome(m.model, m.mvp, home, 1080f, 2160f)
        assertTrue(p.visible)
        assertTrue(p.x in 0f..1080f)
        assertTrue(p.y in 0f..2160f)
        assertTrue("屏幕水平约居中", abs(p.x - 540f) < 1f)
    }

    // ─────────────────────────── E9 俯冲目标角（W9b 加法）───────────────────────────

    @Test
    fun diveTarget_forHomeCityYunye_matchesGolden() {
        val home = PlanetMath.homeUnitVector(600, 1300)
        val (yawT, pitchT) = PlanetMath.diveTarget(home)
        assertEquals(2.356194f, yawT, 1e-4f)
        assertEquals(0.052360f, pitchT, 1e-4f)
    }

    @Test
    fun diveTarget_rotatesHomeToFacingCamera_atSamples() {
        // model(yawT,pitchT)·home ≈ (0,0,1)：把家乡点转正对镜头（+z）。三样本 float32 容差 1e-4。
        val samples = listOf(600 to 1300, 1070 to 640, 3650 to 1450)
        for ((ax, ay) in samples) {
            val home = PlanetMath.homeUnitVector(ax, ay)
            val (yawT, pitchT) = PlanetMath.diveTarget(home)
            val m = PlanetMath.sceneMatrices(yawT, pitchT, dist = 3.1f, aspect = 1f)
            val out = PlanetMath.v4(m.model, home[0], home[1], home[2])
            assertEquals("($ax,$ay).x→0", 0f, out[0], 1e-4f)
            assertEquals("($ax,$ay).y→0", 0f, out[1], 1e-4f)
            assertEquals("($ax,$ay).z→1", 1f, out[2], 1e-4f)
        }
    }

    @Test
    fun nearestYaw_wrapsToEquivalentAngleNearestCurrent() {
        assertEquals(8.639380f, PlanetMath.nearestYaw(7.0f, 2.356194f), 1e-3f)
        // 已是最近等价角 → 原样返回。
        assertEquals(2.356194f, PlanetMath.nearestYaw(2.4f, 2.356194f), 1e-4f)
        // 反向：current 远小于 target → 减一整圈。
        val twoPi = (2.0 * Math.PI).toFloat()
        assertEquals(2.356194f - twoPi, PlanetMath.nearestYaw(-4.0f, 2.356194f), 1e-3f)
    }

    // ─────────────────────────── W15.2 隔球望乡（2026-07-06 拍板·加法）───────────────────────────

    @Test
    fun projectHomeFull_frontPoint_matchesProjectHomeCoordinates() {
        // 正面中心点 (0,0,1)：与锁死版 projectHome 坐标一致 + facingZ=1。
        val home = floatArrayOf(0f, 0f, 1f)
        val m = PlanetMath.sceneMatrices(yaw = 0f, pitch = 0f, dist = 3.1f, aspect = 0.5f)
        val old = PlanetMath.projectHome(m.model, m.mvp, home, 1080f, 2160f)
        val full = PlanetMath.projectHomeFull(m.model, m.mvp, home, 1080f, 2160f)
        assertTrue(old.visible)
        assertEquals(old.x, full.x, 1e-3f)
        assertEquals(old.y, full.y, 1e-3f)
        assertEquals(1f, full.facingZ, 1e-5f)
    }

    @Test
    fun projectHomeFull_backPoint_stillYieldsCoordinates() {
        // 背面中心点 (0,0,-1)：锁死版不可见，但全量版给出屏幕坐标（隔球透视语义）+ facingZ=-1。
        val home = floatArrayOf(0f, 0f, -1f)
        val m = PlanetMath.sceneMatrices(yaw = 0f, pitch = 0f, dist = 3.1f, aspect = 0.5f)
        val old = PlanetMath.projectHome(m.model, m.mvp, home, 1080f, 2160f)
        val full = PlanetMath.projectHomeFull(m.model, m.mvp, home, 1080f, 2160f)
        assertTrue(!old.visible)
        assertEquals(-1f, full.facingZ, 1e-5f)
        assertEquals("轴上点水平居中", 540f, full.x, 1e-2f)
        assertEquals("轴上点垂直居中", 1080f, full.y, 1e-2f)
    }

    @Test
    fun homeMarkerVisual_zonesFromSpec() {
        // 规格（W15.3）：hz = 1/dist；front=smoothstep(hz−0.05,hz+0.05,z)；label=smoothstep(hz+0.04,hz+0.14,z)；
        // ghost=1−front（边缘雪佛龙强度·与 front 严格互补）。dist=3.1 → hz≈0.322581。期望值按规格独立算。
        val dist = 3.1f
        val hz = 1f / dist
        // 深正面：满/满/零。
        val front = PlanetMath.homeMarkerVisual(0.8f, dist)
        assertEquals(1f, front.front, 1e-5f); assertEquals(1f, front.label, 1e-5f); assertEquals(0f, front.ghost, 1e-5f)
        // 恰在地平线：front=smoothstep 中点=0.5 → ghost=0.5；标签带（起点 hz+0.04）尚未进入 → 0。
        val atHorizon = PlanetMath.homeMarkerVisual(hz, dist)
        assertEquals(0.5f, atHorizon.front, 1e-4f)
        assertEquals(0f, atHorizon.label, 1e-5f)
        assertEquals(0.5f, atHorizon.ghost, 1e-4f)
        // 深背面：0/0/1。
        val back = PlanetMath.homeMarkerVisual(-0.5f, dist)
        assertEquals(0f, back.front, 1e-5f); assertEquals(0f, back.label, 1e-5f); assertEquals(1f, back.ghost, 1e-5f)
        // 标签带上缘：z = hz+0.14 → label=1；带内中点 z = hz+0.09 → smoothstep(0.5)=0.5。
        assertEquals(1f, PlanetMath.homeMarkerVisual(hz + 0.14f, dist).label, 1e-4f)
        assertEquals(0.5f, PlanetMath.homeMarkerVisual(hz + 0.09f, dist).label, 1e-4f)
    }

    // ─────────────────── W15.3 真·抓取（射线/锚点/反解）+ 边缘指路（规格独立反推）───────────────────

    @Test
    fun screenToSphere_centerHitsFrontPole_andYAxisPointsUp() {
        // 屏幕正中 → 正面极点 (0,0,1)；上半屏 → y>0（NDC 上正·与投影矩阵约定一致）。
        val c = PlanetMath.screenToSphere(640f, 1428f, 1280f, 2856f, 3.1f)
        assertTrue(c != null)
        assertEquals(0f, c!![0], 1e-4f); assertEquals(0f, c[1], 1e-4f); assertEquals(1f, c[2], 1e-4f)
        val up = PlanetMath.screenToSphere(640f, 800f, 1280f, 2856f, 3.1f)
        assertTrue(up != null && up[1] > 0f && up[2] > 0f)
    }

    @Test
    fun screenToSphere_missReturnsNull_atFarZoom() {
        // dist=6.4 时星球剪影半径 ≈ 0.73 NDC（横向）→ 屏幕角落射线打在星空 → null。
        assertTrue(PlanetMath.screenToSphere(10f, 10f, 1280f, 2856f, 6.4f) == null)
        // 但屏幕正中仍命中。
        assertTrue(PlanetMath.screenToSphere(640f, 1428f, 1280f, 2856f, 6.4f) != null)
    }

    @Test
    fun grabPose_roundtrip_recoversOriginalPose() {
        // a = M⁻¹·q 后再解 (yaw,pitch) 使 M·a = q：最近解应恢复原姿态（三组姿态×三个触点）。
        val poses = listOf(0.6f to -0.25f, 2.0f to 0.8f, -1.2f to -1.0f)
        val touches = listOf(640f to 1428f, 300f to 900f, 1000f to 2000f)
        for ((yaw, pitch) in poses) {
            for ((tx, ty) in touches) {
                val q = PlanetMath.screenToSphere(tx, ty, 1280f, 2856f, 3.1f) ?: continue
                val a = PlanetMath.modelAnchor(q, yaw, pitch)
                val (y2, p2) = PlanetMath.solveGrabPose(a, q, yaw, pitch, 1.25f)
                assertEquals("yaw@($tx,$ty)", yaw, y2, 1e-3f)
                assertEquals("pitch@($tx,$ty)", pitch, p2, 1e-3f)
            }
        }
    }

    @Test
    fun grabPose_pinsAnchorUnderFinger_afterMove() {
        // 抓取核心性质：按下 T1 锚定 → 移到 T2 反解 → 前向验证 M(新姿态)·anchor ≈ T2 命中的球面点。
        val yaw = 0.6f; val pitch = -0.25f; val dist = 3.1f
        val q1 = PlanetMath.screenToSphere(384f, 1428f, 1280f, 2856f, dist)!!
        val a = PlanetMath.modelAnchor(q1, yaw, pitch)
        val q2 = PlanetMath.screenToSphere(768f, 1571f, 1280f, 2856f, dist)!!
        val (y2, p2) = PlanetMath.solveGrabPose(a, q2, yaw, pitch, 1.25f)
        val m = PlanetMath.sceneMatrices(y2, p2, dist, 1280f / 2856f)
        val out = PlanetMath.v4(m.model, a[0], a[1], a[2])
        assertEquals(q2[0], out[0], 1e-3f)
        assertEquals(q2[1], out[1], 1e-3f)
        assertEquals(q2[2], out[2], 1e-3f)
    }

    @Test
    fun grabPose_clampsPitch_andStillSolvesYaw() {
        // 把正面锚点拽向需要俯仰 >1.25 的目标：俯仰钳在 1.25，偏航仍有解（纵向打滑=地球仪标准行为）。
        val a = floatArrayOf(0f, 0f, 1f)
        val q = floatArrayOf(0f, -0.99f, 0.141f) // 需 sin(p)·cos(y)≈0.99 → p≈1.43 > 1.25
        val (_, p2) = PlanetMath.solveGrabPose(a, q, 0f, 0f, 1.25f)
        assertEquals(1.25f, p2, 1e-4f)
    }

    @Test
    fun homeChevron_backSide_pointsTowardComeAroundSide() {
        // 家在背面偏左（wpX<0）→ alpha=1、雪佛龙钉在左缘、角度 = π（方向取世界系环绕侧·非镜像投影）。
        val c = PlanetMath.homeChevron(HomeProjection(300f, 1400f, -0.4f, -0.9f, 0f), 3.1f, 1280f, 2856f, 50f)
        assertEquals(50f, c[0], 1e-2f)          // 640 − (640−50)
        assertEquals(1428f, c[1], 1e-2f)
        assertEquals(Math.PI.toFloat(), c[2], 1e-4f)
        assertEquals(1f, c[3], 1e-5f)
        // 家在背面偏上（wpY>0 → 屏幕 ny<0）→ 钉在上方、角度 = −π/2。
        val cUp = PlanetMath.homeChevron(HomeProjection(640f, 1400f, -0.2f, 0f, 0.8f), 3.1f, 1280f, 2856f, 50f)
        assertTrue(cUp[1] < 1428f)
        assertEquals((-Math.PI / 2).toFloat(), cUp[2], 1e-4f)
        assertEquals(1f, cUp[3], 1e-5f)
    }

    @Test
    fun homeChevron_frontOnScreen_isHidden_frontOffScreen_showsTowardProjection() {
        // 正面且投影在屏内 → alpha=0（不出现）。
        val onScreen = PlanetMath.homeChevron(HomeProjection(640f, 1428f, 0.9f, 0f, 0f), 3.1f, 1280f, 2856f, 50f)
        assertEquals(0f, onScreen[3], 1e-5f)
        // 正面但投影出右屏 200px → alpha = smoothstep(0,80,200)=1、方向 = 投影方向（右缘·角度 0）。
        val offRight = PlanetMath.homeChevron(HomeProjection(1480f, 1428f, 0.9f, 0.8f, 0f), 3.1f, 1280f, 2856f, 50f)
        assertEquals(1f, offRight[3], 1e-5f)
        assertEquals(1230f, offRight[0], 1e-2f) // 640 + (640−50)
        assertEquals(0f, offRight[2], 1e-4f)
        // 出屏 40px（半程带内）→ alpha = smoothstep(0,80,40) = 0.5。
        val half = PlanetMath.homeChevron(HomeProjection(1320f, 1428f, 0.9f, 0.8f, 0f), 3.1f, 1280f, 2856f, 50f)
        assertEquals(0.5f, half[3], 1e-4f)
    }
}
