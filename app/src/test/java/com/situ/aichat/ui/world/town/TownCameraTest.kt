package com.situ.aichat.ui.world.town

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TownCamera] T1-4（W9c 底座 §5 + W15 图纸 §5/§7·断言从规格独立反推）：保留系（intro 俯冲金标 / 接管 /
 * reduceMotion / pitch 钳 / tDist 钳 / 选中收距 min(tDist,19) 不动 target / 帧率无关 dist 跟随 / overzoom 回
 * 大陆 / cinematic）改输入源后全绿；W15 漫游系（onTiltBy 过死区调 pitch / 平移惯性 0.93 / lockstep / 边界
 * ±16 橡皮筋 + 硬止 + 松手弹回 / catch / 焦点缩放 / twist / restoreSnapshot 恢复 target）。以 1/60s 步进。
 */
class TownCameraTest {

    private val frame = 1f / 60f
    private val eps = 1e-4f
    private fun cam(reduce: Boolean = false) = TownCamera(reduce, tiltDeadzonePx = 12f)

    // ─────────────────────────── intro 俯冲曲线 + 接管 ───────────────────────────

    @Test
    fun intro_curveGoldenAt45Steps() {
        val cam = cam()
        repeat(45) { cam.integrate(frame, reduceMotion = false) } // introT=0.5 → k=0.875
        assertEquals(31.0f, cam.snapshot.dist, 0.05f)   // 38 + (30 − 38)×0.875（R3 构图定版顺移）
        assertEquals(0.45875f, cam.snapshot.pitch, 0.005f)  // 1.15 + (0.36 − 1.15)×0.875（R3 构图定版顺移）
    }

    @Test
    fun input_takesOverIntroImmediately() {
        val byPointer = cam()
        repeat(5) { byPointer.integrate(frame, reduceMotion = false) }
        assertTrue("接管前 intro 未完成", byPointer.introFraction() < 1f)
        byPointer.setPointerDown(true); byPointer.integrate(frame, reduceMotion = false)
        assertEquals("单指按下即 introT=1", 1f, byPointer.introFraction(), 0f)

        val byPinch = cam()
        repeat(5) { byPinch.integrate(frame, reduceMotion = false) }
        byPinch.setPinching(true); byPinch.integrate(frame, reduceMotion = false)
        assertEquals("双指即 introT=1", 1f, byPinch.introFraction(), 0f)
    }

    @Test
    fun reduceMotion_startsLanded_noIntro_butGesturable() {
        val cam = cam(reduce = true)
        assertEquals("reduce 无 intro", 1f, cam.introFraction(), 0f)
        assertEquals(30f, cam.snapshot.dist, eps)   // R3 LANDED_DIST
        assertEquals(0.36f, cam.snapshot.pitch, eps)   // R3 LANDED_PITCH
        // 手势仍可平移（reduce 不锁交互）。
        cam.setPointerDown(true); cam.onPanBy(2f, 0f); cam.integrate(frame, reduceMotion = true)
        assertEquals("拖动平移 target", -1.5f + 2f, cam.snapshot.tx, eps)
    }

    // ─────────────────────────── 俯仰（tilt 过死区）/ tDist 钳 ───────────────────────────

    @Test
    fun pitch_clampsTo0_28and1_25_viaTilt() {
        val up = cam(reduce = true)
        up.setPinching(true); up.onTiltBy(20f); up.onTiltBy(100000f)
        up.integrate(frame, reduceMotion = true)
        assertEquals(1.25f, up.snapshot.pitch, eps)
        val down = cam(reduce = true)
        down.setPinching(true); down.onTiltBy(-20f); down.onTiltBy(-100000f)
        down.integrate(frame, reduceMotion = true)
        assertEquals(0.28f, down.snapshot.pitch, eps)
    }

    @Test
    fun tDist_clampsTo13and38() {
        val near = cam(reduce = true)
        near.onPinchBy(0.0001f); repeat(200) { near.integrate(frame, reduceMotion = true) }
        assertEquals(13f, near.snapshot.dist, 0.1f)
        val far = cam(reduce = true)
        far.onPinchBy(10000f); repeat(200) { far.integrate(frame, reduceMotion = true) }
        assertEquals(38f, far.snapshot.dist, 0.1f)
    }

    // ─────────────────────────── 平移惯性 0.93 / dist 跟随 / 闲置 ───────────────────────────

    @Test
    fun inertia_decaysBy0_93PerFrame_pan() {
        val cam = cam(reduce = true)
        cam.setPointerDown(true); cam.onPanBy(3f, 0f); cam.integrate(frame, reduceMotion = true) // vpanX=3
        cam.setPointerDown(false)
        var prev = cam.snapshot.tx
        val deltas = mutableListOf<Float>()
        repeat(4) { cam.integrate(frame, reduceMotion = true); val x = cam.snapshot.tx; deltas.add(x - prev); prev = x }
        assertEquals(3f, deltas[0], eps)
        assertEquals(0.93f, deltas[1] / deltas[0], 1e-3f)
        assertEquals(0.93f, deltas[2] / deltas[1], 1e-3f)
    }

    @Test
    fun distFollow_frameRateIndependent_doubleStepIs0_1536() {
        val cam = cam(reduce = true) // introT=1·dist=30·tDist=30（R3 构图定版）
        cam.onSelectPlace() // tDist = min(30,19) = 19
        cam.integrate(2f / 60f, reduceMotion = true) // steps=2 → f = 1 − 0.92² = 0.1536
        assertEquals(30f + (19f - 30f) * 0.1536f, cam.snapshot.dist, 1e-3f)
    }

    @Test
    fun idleDrift_afterEnoughIdle_andSuppressedUnderReduce() {
        val cam = cam()
        repeat(400) { cam.integrate(frame, reduceMotion = false) }
        assertTrue("越 2.2s 闲置 → 漂移", cam.snapshot.yaw > 0.7f)
        val r = cam(reduce = true)
        repeat(400) { r.integrate(frame, reduceMotion = true) }
        assertEquals("reduce 无闲置漂移", 0.7f, r.snapshot.yaw, eps)
    }

    // ─────────────────────────── 选中收距 min(tDist,19)·不动 target ───────────────────────────

    @Test
    fun selectPlace_clampsTDistTo19_doesNotMoveTarget() {
        val cam = cam(reduce = true)
        cam.onSelectPlace(); repeat(120) { cam.integrate(frame, reduceMotion = true) }
        assertEquals("选中 → tDist=min(24,19)=19", 19f, cam.snapshot.dist, 0.2f)
        assertEquals("target 不随选中移动", -1.5f, cam.snapshot.tx, eps)
        assertEquals(-1.0f, cam.snapshot.tz, eps)
        val near = cam(reduce = true)
        near.onPinchBy(0.0001f); repeat(200) { near.integrate(frame, reduceMotion = true) } // → 13
        near.onSelectPlace(); repeat(60) { near.integrate(frame, reduceMotion = true) }
        assertEquals("min(13,19)=13 不抬高", 13f, near.snapshot.dist, 0.2f)
    }

    // ─────────────────────────── W15 平移 lockstep / 边界 ±16 / catch ───────────────────────────

    @Test
    fun pan_movesTargetInLockstep_fromRest() {
        val cam = cam(reduce = true)
        cam.setPointerDown(true); cam.onPanBy(2f, -3f); cam.integrate(frame, reduceMotion = true)
        assertEquals(-1.5f + 2f, cam.snapshot.tx, eps)
        assertEquals(-1.0f - 3f, cam.snapshot.tz, eps)
    }

    @Test
    fun pan_boundaryAt16_rubber_hardStop20_springsBack() {
        val cam = cam(reduce = true)
        cam.setPointerDown(true)
        cam.onPanBy(18f, 0f); cam.integrate(frame, reduceMotion = true)  // −1.5+18=16.5（>16·未阻尼·起点在界内）
        assertEquals(16.5f, cam.snapshot.tx, 1e-3f)
        cam.onPanBy(2f, 0f); cam.integrate(frame, reduceMotion = true)   // 越界段：2×0.35=0.7 → 17.2
        assertEquals("越界增量 ×0.35", 17.2f, cam.snapshot.tx, 1e-3f)
        cam.onPanBy(100f, 0f); cam.integrate(frame, reduceMotion = true) // 硬止 ±(16+4)=20
        cam.onPanBy(0.01f, 0f); cam.integrate(frame, reduceMotion = true) // 末事件极小 → 惯性种子≈0
        assertEquals("硬止 20", 20f, cam.snapshot.tx, 0.05f)
        cam.setPointerDown(false); repeat(120) { cam.integrate(frame, reduceMotion = true) }
        assertEquals("松手弹回软边界 16", 16f, cam.snapshot.tx, 0.3f)
    }

    @Test
    fun pan_catchStopsInertia() {
        val cam = cam(reduce = true)
        cam.setPointerDown(true); cam.onPanBy(5f, 0f); cam.integrate(frame, reduceMotion = true)
        cam.setPointerDown(false); cam.integrate(frame, reduceMotion = true)
        cam.setPointerDown(true); cam.integrate(frame, reduceMotion = true) // catch
        val caught = cam.snapshot.tx
        cam.setPointerDown(false)
        repeat(5) { cam.integrate(frame, reduceMotion = true) }
        assertEquals("catch 后无残余惯性", caught, cam.snapshot.tx, eps)
    }

    @Test
    fun focalZoom_pullsTowardFocalGround() {
        val cam = cam(reduce = true) // target.x=-1.5
        cam.setPointerDown(true); cam.setPinching(true)
        val xs = mutableListOf<Float>()
        repeat(30) {
            cam.onPinchBy(0.97f); cam.setPinchFocal(5f, 0f)
            cam.integrate(frame, reduceMotion = true); xs.add(cam.snapshot.tx)
        }
        assertTrue("单调趋近焦点 5", xs.last() > -1.5f && xs.last() <= 5f)
        for (i in 1 until xs.size) assertTrue("单调不减", xs[i] >= xs[i - 1] - 1e-4f)
    }

    @Test
    fun twist_appliesNegative_afterDeadzone() {
        val cam = cam(reduce = true)
        cam.setPinching(true)
        val yaw0 = cam.snapshot.yaw
        cam.onTwistBy(0.05f); cam.onTwistBy(0.05f); cam.onTwistBy(0.2f)
        cam.integrate(frame, reduceMotion = true)
        assertEquals("yaw += −twist", yaw0 - 0.2f, cam.snapshot.yaw, 1e-4f)
    }

    // ─────────────────────────── overzoom-out 回大陆 ───────────────────────────

    @Test
    fun overzoom_triggersOnceWhenPinchingOutPastCap() {
        val cam = cam()
        cam.setPinching(true)
        cam.onPinchBy(1000f); cam.integrate(frame, reduceMotion = false) // tDist→38（到顶不计）
        assertFalse("到顶本身不触发", cam.consumeReturnRequested())
        cam.onPinchBy(1.06f); cam.integrate(frame, reduceMotion = false) // 1.06 < 1.10
        assertFalse(cam.consumeReturnRequested())
        cam.onPinchBy(1.05f); cam.integrate(frame, reduceMotion = false) // 1.113 ≥ 1.10 → 触发
        assertTrue("累积 ≥ 1.10 → 回大陆", cam.consumeReturnRequested())
        cam.onPinchBy(1.05f); cam.integrate(frame, reduceMotion = false)
        assertFalse("一次性·不复触", cam.consumeReturnRequested())
    }

    @Test
    fun overzoom_resetsWhenPinchEnds() {
        val cam = cam()
        cam.setPinching(true)
        cam.onPinchBy(1000f); cam.integrate(frame, reduceMotion = false)
        repeat(4) { cam.onPinchBy(1.1f); cam.integrate(frame, reduceMotion = false) }
        assertTrue(cam.consumeReturnRequested())
        cam.setPinching(false); cam.integrate(frame, reduceMotion = false) // 松手复位
        cam.setPinching(true)
        repeat(4) { cam.onPinchBy(1.1f); cam.integrate(frame, reduceMotion = false) }
        assertTrue("复位后可再触发", cam.consumeReturnRequested())
    }

    // ─────────────────────────── cinematic 覆写（回大陆转场用·忽略手势）───────────────────────────

    @Test
    fun cinematic_overridesPitchDist_freezesYawTarget_ignoresGestures_thenResumes() {
        val cam = cam()
        repeat(120) { cam.integrate(frame, reduceMotion = false) } // 落定
        val yaw0 = cam.snapshot.yaw
        val tx0 = cam.snapshot.tx
        cam.setCinematicPose(1.15f, 38f)
        cam.setPointerDown(true); cam.onPanBy(9f, 9f); cam.onTwistBy(1f); cam.onTiltBy(1000f); cam.setPinchFocal(7f, 7f)
        cam.integrate(frame, reduceMotion = false)
        assertEquals(1.15f, cam.snapshot.pitch, eps)
        assertEquals(38f, cam.snapshot.dist, eps)
        assertEquals("yaw 冻结", yaw0, cam.snapshot.yaw, eps)
        assertEquals("target 冻结", tx0, cam.snapshot.tx, eps)
        cam.clearCinematic(); cam.setPointerDown(false); cam.integrate(frame, reduceMotion = false)
        assertEquals("clear 后从 cinematic 姿态续跑", 1.15f, cam.snapshot.pitch, eps)
    }

    // ─────────────────────────── W15 restoreSnapshot 恢复 target（室内往返·E9）───────────────────────────

    @Test
    fun restoreSnapshot_restoresTarget() {
        val cam = cam()
        cam.restoreSnapshot(TownCamSnapshot(0.7f, 0.6f, 20f, 3f, 0.8f, -2f), tDistValue = 18f)
        assertEquals(3f, cam.snapshot.tx, eps)
        assertEquals(0.8f, cam.snapshot.ty, eps)
        assertEquals(-2f, cam.snapshot.tz, eps)
        // 后续 integrate 起点仍在 (3,0.8,−2)：无输入不漂。
        cam.integrate(frame, reduceMotion = false)
        assertEquals(3f, cam.snapshot.tx, 1e-3f)
        assertEquals(-2f, cam.snapshot.tz, 1e-3f)
    }
}
