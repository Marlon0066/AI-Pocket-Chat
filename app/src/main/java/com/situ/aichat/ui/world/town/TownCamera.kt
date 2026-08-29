package com.situ.aichat.ui.world.town

import com.situ.aichat.ui.world.gl.FreeRoamInput
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/** 小镇相机瞬时态快照（GL 线程每帧写·Compose 地点投影读·[TownCamera.snapshot]）。[tx]/[ty]/[tz] = 当前 target（W15：可平移）。 */
internal class TownCamSnapshot(
    val yaw: Float, val pitch: Float, val dist: Float,
    val tx: Float, val ty: Float, val tz: Float,
)

/**
 * 小镇盒景「地图式自由漫游」相机（W9c 图纸 §3.3 底座 + W15 图纸 §4A.3 漫游改造·§9 禁改）。
 *
 * **W15 拖动语义变更（与大陆同构）**：单指拖动 = 地面锚定**平移**（target 由恒定改为跟手可平移·带惯性 +
 * 边缘橡皮筋软拦 + 松手弹回）；双指旋转 = 转 yaw、双指同向上下滑 = 调 pitch（均带死区·由 [FreeRoamInput]
 * 累积）；捏合朝两指中点地面点缩。选中地点仍只收距不移 target（demo:L270 机制照旧）。
 *
 * **线程纪律同 [com.situ.aichat.ui.world.continent.ContinentCamera]**：触摸（UI 线程）只累积（synchronized）；
 * 积分只在 GL 线程 [integrate] 每帧执行写 [snapshot]（@Volatile）。intro 俯冲入场为转场缝合层新造。帧率无关
 * 折算 `steps = dt*60`（同 9b）。
 */
internal class TownCamera(reduceMotion: Boolean, tiltDeadzonePx: Float) {

    private companion object {
        const val INITIAL_YAW = 0.7f
        // ── R3 构图定版（用户 2026-08-28「比例要系统设计·不要一个改一个」）：静止构图目标 = 对版稿
        // world_game_art_roadmap_mockup 台阶1/2 画面比例——天空 ≈38% / 远山带 ≈8% / 小镇 ≈54%。
        // 双杆联动达成：退远（24→30·房/地/图整体变小）+ 放平（0.62→0.36·天空约占屏三成·天际线压到屏高约四成）。──
        const val LANDED_PITCH = 0.36f
        const val INTRO_PITCH = 1.15f
        const val FAR_DIST = 38f          // intro 起点（高空）
        const val LANDED_DIST = 30f       // intro 终点 / tDist 初值（R3：24→30 与俯角联动·见上）
        const val PITCH_MIN = 0.28f
        const val PITCH_MAX = 1.25f
        const val TDIST_MIN = 13f
        const val TDIST_MAX = 38f
        const val DAMPING = 0.93f
        const val IDLE_SECONDS = 2.2f
        const val IDLE_SPIN = 0.00035f
        const val INTRO_RATE = 1f / 90f   // introT/帧@60
        const val FOLLOW_BASE = 0.92f     // 跟随 f = 1 − 0.92^steps（= 0.08@60）
        const val SELECT_TDIST = 19f      // 选中地点 tDist = min(tDist, 19)（demo:L270·不改 target）
        const val OVERZOOM_TRIGGER = 1.10f // 顶格外捏累积 ≥ 此 → 回大陆
        const val UP_HINT_DIST = 34f
        const val FOLLOW_DIST_EPS = 0.01f  // W15：跟随热判（同大陆值）
        const val FOLLOW_TARGET_EPS = 0.005f
        // ── W15 自由漫游平移（图纸 §4A.3）──
        const val PAN_BOUND = 16f          // 平移软边界（地面 quad 半宽 26 留 10 余量·建筑群散布 ±20 内）
        const val PAN_OVER_MAX = 4f        // 边缘软拦最大越界量
        const val PAN_RESIST = 0.35f       // 越界段拖动阻尼系数（橡皮筋）
        const val PAN_V_EPS = 0.01f        // 平移惯性热判阈（世界单位/帧）
        const val TILT_SENS = 0.004f       // 双指俯仰灵敏度（= 原 PITCH_SENS 同值·手感连续）
    }

    // ── target（W15：由恒定改可平移·初值沿用 demo:L231）──
    private val target = floatArrayOf(-1.5f, 0.8f, -1.0f)
    private val tTarget = floatArrayOf(-1.5f, 0.8f, -1.0f)

    // ── GL 线程私有态 ──
    private var yaw = INITIAL_YAW
    private var pitch = INTRO_PITCH
    private var dist = FAR_DIST
    @Volatile private var vpanX = 0f       // 平移惯性（UI 线程 catch 时清零 → @Volatile 保可见）
    @Volatile private var vpanZ = 0f
    private var idleT = 0f
    private var introT = 0f
    private var tDist = LANDED_DIST
    private var overZoom = 1f
    private var returnArmed = false
    private var cinPitch = 0f
    private var cinDist = 0f
    private var focalX = 0f                  // 捏合焦点地面点镜像（每帧从 frame 更新）
    private var focalZ = 0f
    private var hasFocal = false

    // ── UI 线程 → GL 线程 输入 ──
    private val input = FreeRoamInput(tiltDeadzonePx)
    private val lock = Any()
    private var pendingSelect = false

    @Volatile private var pointerDown = false
    @Volatile private var pinching = false
    @Volatile private var cinematic = false
    @Volatile private var velocityHot = false
    @Volatile private var followHot = false
    @Volatile private var introDone = false
    @Volatile private var returnRequested = false

    @Volatile
    var snapshot: TownCamSnapshot = TownCamSnapshot(INITIAL_YAW, INTRO_PITCH, FAR_DIST, -1.5f, 0.8f, -1.0f)
        private set

    init {
        if (reduceMotion) { pitch = LANDED_PITCH; dist = LANDED_DIST; introT = 1f }
        introDone = introT >= 1f
        snapshot = TownCamSnapshot(yaw, pitch, dist, target[0], target[1], target[2])
    }

    // ── 输入事件（UI 线程·薄委托到 [input]）──
    /** 单指平移增量（世界系·地面锚定·由 GLView 反投影算出）。 */
    fun onPanBy(wdx: Float, wdz: Float) = input.onPanBy(wdx, wdz)

    fun onPinchBy(ratio: Float) = input.onPinchBy(ratio)

    /** 双指旋转增量（rad·过死区后才计）。 */
    fun onTwistBy(dAngle: Float) = input.onTwistBy(dAngle)

    /** 双指同向上下滑增量（px·过死区后才计）。 */
    fun onTiltBy(dyPx: Float) = input.onTiltBy(dyPx)

    /** 捏合焦点地面点（两指中点反投影）。 */
    fun setPinchFocal(x: Float, z: Float) = input.setPinchFocal(x, z)

    fun clearPinchFocal() = input.clearPinchFocal()

    fun setPointerDown(down: Boolean) {
        pointerDown = down
        if (down) { vpanX = 0f; vpanZ = 0f } // catch：接住惯性滑行中的地图
    }

    fun setPinching(active: Boolean) {
        pinching = active
        if (active) input.beginTwoFinger() else input.endTwoFinger()
    }

    /** 选中地点（demo:L270·tDist = min(tDist, 19)·不改 target）。 */
    fun onSelectPlace() = synchronized(lock) { pendingSelect = true }

    /** cinematic 覆写（回大陆逆放·pitch/dist 覆写·yaw/target 冻结·忽略输入）。 */
    fun setCinematicPose(pitch: Float, dist: Float) = synchronized(lock) {
        cinematic = true; cinPitch = pitch; cinDist = dist
    }

    fun clearCinematic() = synchronized(lock) { cinematic = false }

    // ── 帧泵 / 覆盖层查询 ──
    fun wantsHighFps(): Boolean = pointerDown || pinching || velocityHot || followHot || !introDone
    fun isGesturing(): Boolean = pointerDown || pinching
    fun wantsUpHint(): Boolean = snapshot.dist > UP_HINT_DIST && introDone
    fun consumeReturnRequested(): Boolean {
        if (!returnRequested) return false
        returnRequested = false
        return true
    }

    /** 测试可观测：intro 进度（0..1）。 */
    fun introFraction(): Float = introT

    /**
     * 回小镇恢复姿态（W9d 加法·§4.9·[com.situ.aichat.ui.world.continent.ContinentCamera].restoreSnapshot 同模式）：
     * 从室内返回时把进室内前保存的相机快照 + tDist 灌回·跳过 intro（introT=1·姿态即到）·清惯性防解锁突跳。
     * W15：连 target/tTarget 一起恢复（快照 tx/ty/tz），室内往返回来平移位置不丢。
     */
    fun restoreSnapshot(s: TownCamSnapshot, tDistValue: Float) {
        yaw = s.yaw; pitch = s.pitch; dist = s.dist
        target[0] = s.tx; target[1] = s.ty; target[2] = s.tz
        tTarget[0] = s.tx; tTarget[1] = s.ty; tTarget[2] = s.tz
        tDist = tDistValue; overZoom = 1f; returnArmed = false
        introT = 1f; introDone = true; vpanX = 0f; vpanZ = 0f
        snapshot = TownCamSnapshot(yaw, pitch, dist, target[0], target[1], target[2])
    }

    /** GL 线程每帧积分（demo:L285-303 语义 + W15 §4A.3 自由漫游）。返回后 [snapshot] 已更新。 */
    fun integrate(dtSeconds: Float, reduceMotion: Boolean) {
        val steps = dtSeconds * 60f
        // cinematic 覆写（回大陆转场·yaw/target 冻结·清空输入防解锁突跳）。
        if (cinematic) {
            val cp: Float; val cd: Float
            synchronized(lock) {
                pendingSelect = false
                cp = cinPitch; cd = cinDist
            }
            input.clear()
            pitch = cp; dist = cd; vpanX = 0f; vpanZ = 0f
            velocityHot = false; followHot = false
            snapshot = TownCamSnapshot(yaw, pitch, dist, target[0], target[1], target[2])
            return
        }

        val frame = input.consume()
        focalX = frame.focalX; focalZ = frame.focalZ; hasFocal = frame.hasFocal
        val select: Boolean
        synchronized(lock) {
            select = pendingSelect
            pendingSelect = false
        }
        val down = pointerDown
        if (down || pinching) introT = 1f // 任何输入即接管 intro。

        // 选中地点收距（demo:L270·不改 target）。
        if (select) tDist = min(tDist, SELECT_TDIST)

        // 捏合 → tDist（demo:L243）+ overzoom-out（顶格外捏累积 ≥1.10 → 回大陆·§3.3）。
        val tDistAtCapBefore = tDist >= TDIST_MAX
        if (frame.pinch != 1f) {
            tDist = (tDist * frame.pinch).coerceIn(TDIST_MIN, TDIST_MAX)
            idleT = 0f
        }
        if (pinching && tDistAtCapBefore && frame.pinch > 1f) {
            overZoom *= frame.pinch
            if (overZoom >= OVERZOOM_TRIGGER && !returnArmed) { returnRequested = true; returnArmed = true }
        } else if (!pinching || tDist < TDIST_MAX) {
            overZoom = 1f; returnArmed = false
        }

        // 旋转（双指转 yaw）/ 俯仰（双指上下滑·过 PITCH 钳）。
        if (frame.twist != 0f) { yaw += -frame.twist; idleT = 0f }
        if (frame.tiltDy != 0f) {
            pitch = (pitch + frame.tiltDy * TILT_SENS).coerceIn(PITCH_MIN, PITCH_MAX); idleT = 0f
        }

        // 平移（拖动中·地面锚定·惯性种子 = 末事件增量）。
        if (down && (frame.panDx != 0f || frame.panDz != 0f)) {
            applyPan(frame.panDx, frame.panDz)
            vpanX = frame.lastPanDx; vpanZ = frame.lastPanDz
            idleT = 0f
        }

        // intro 俯冲 / 平移惯性 / 闲置慢旋 / 松手回弹 / dist 跟随（demo:L290-293 + intro + W15）。
        if (introT < 1f) {
            introT = minOf(1f, introT + INTRO_RATE * steps)
            val k = 1f - (1f - introT).pow(3)
            dist = FAR_DIST + (LANDED_DIST - FAR_DIST) * k
            pitch = INTRO_PITCH + (LANDED_PITCH - INTRO_PITCH) * k
        } else {
            if (!down) {
                applyPan(vpanX * steps, vpanZ * steps)
                vpanX *= DAMPING.pow(steps); vpanZ *= DAMPING.pow(steps)
                idleT += dtSeconds
                if (!reduceMotion && idleT > IDLE_SECONDS) yaw += IDLE_SPIN * steps
                tTarget[0] = tTarget[0].coerceIn(-PAN_BOUND, PAN_BOUND)
                tTarget[2] = tTarget[2].coerceIn(-PAN_BOUND, PAN_BOUND)
            }
            val distBefore = dist
            dist += (tDist - dist) * (1f - FOLLOW_BASE.pow(steps))
            if (pinching && hasFocal && distBefore > 0f) {
                val r = dist / distBefore
                val k = 1f - r
                val moveX = (focalX - target[0]) * k; val moveZ = (focalZ - target[2]) * k
                target[0] += moveX; target[2] += moveZ; tTarget[0] += moveX; tTarget[2] += moveZ
                hardClampPan()
            }
        }
        introDone = introT >= 1f

        // 跟随 target（W15：恒执行·服务平移回弹与焦点补偿·帧率无关）。
        val ft = 1f - FOLLOW_BASE.pow(steps)
        for (i in 0..2) target[i] += (tTarget[i] - target[i]) * ft

        velocityHot = abs(vpanX) > PAN_V_EPS || abs(vpanZ) > PAN_V_EPS
        followHot = abs(tDist - dist) > FOLLOW_DIST_EPS ||
            abs(tTarget[0] - target[0]) > FOLLOW_TARGET_EPS ||
            abs(tTarget[1] - target[1]) > FOLLOW_TARGET_EPS ||
            abs(tTarget[2] - target[2]) > FOLLOW_TARGET_EPS
        snapshot = TownCamSnapshot(yaw, pitch, dist, target[0], target[1], target[2])
    }

    /** 地面锚定平移（拖动与惯性共用·逐轴橡皮筋 + 硬止·图纸 §4A.3）。y 轴永不动。 */
    private fun applyPan(dx: Float, dz: Float) {
        var mx = dx; var mz = dz
        if ((tTarget[0] > PAN_BOUND && mx > 0f) || (tTarget[0] < -PAN_BOUND && mx < 0f)) mx *= PAN_RESIST
        if ((tTarget[2] > PAN_BOUND && mz > 0f) || (tTarget[2] < -PAN_BOUND && mz < 0f)) mz *= PAN_RESIST
        target[0] += mx; target[2] += mz; tTarget[0] += mx; tTarget[2] += mz
        hardClampPan()
    }

    /** 平移四值硬止在 ±(PAN_BOUND+PAN_OVER_MAX)。 */
    private fun hardClampPan() {
        val lim = PAN_BOUND + PAN_OVER_MAX
        target[0] = target[0].coerceIn(-lim, lim)
        target[2] = target[2].coerceIn(-lim, lim)
        tTarget[0] = tTarget[0].coerceIn(-lim, lim)
        tTarget[2] = tTarget[2].coerceIn(-lim, lim)
    }
}
