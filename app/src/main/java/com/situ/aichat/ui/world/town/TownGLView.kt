package com.situ.aichat.ui.world.town

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.situ.aichat.ui.world.gl.MsaaConfigChooser
import com.situ.aichat.ui.world.gl.WorldCameraMath
import com.situ.aichat.ui.world.gl.WorldFramePump
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 小镇盒景 GLSurfaceView（W9c 图纸 §2/§3.6 + W15 §4A.5 自由漫游触摸重写）：共享 EGL 配置（[MsaaConfigChooser]）
 * + DPR≤2 封顶 + 触摸（**单指=地面锚定平移·双指=捏合/旋转/俯仰 + 焦点地面点**·tap 检测 moved<7dp）+ 共享帧泵
 * （[WorldFramePump]）。相机 [TownCamera] 与渲染器 [TownRenderer] 内部持有。对外 API：几何/天空提交、快照、
 * tap/首帧/回大陆回调、输入锁、cinematic、选中收距透传。
 */
internal class TownGLView(
    context: Context,
    worldSeed: Long,
    reduceMotion: Boolean,
    onGlError: () -> Unit,
    onFirstFrame: () -> Unit,
    private val onTap: (Float, Float) -> Unit,
    private val onReturnGesture: () -> Unit,
    initialSnapshot: Pair<TownCamSnapshot, Float>? = null, // W9d 加法：从室内返回时恢复姿态（§4.9）
) : GLSurfaceView(context) {

    private val camera = TownCamera(
        reduceMotion,
        tiltDeadzonePx = 12f * resources.displayMetrics.density, // 12dp：两指同向滑动意图确认距离
    ).apply { initialSnapshot?.let { restoreSnapshot(it.first, it.second) } }
    private val renderer: TownRenderer

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = 0f
    private var prevSpan = 0f
    private var prevAngle = 0f
    private var prevMidX = 0f
    private var prevMidY = 0f
    private val tapSlopPx = 7f * resources.displayMetrics.density // moved<7dp = tap

    @Volatile private var inputLocked = false

    private val framePump = WorldFramePump(
        frozen = { renderer.reduceMotion || renderer.staticMode },
        gesturing = { camera.isGesturing() },
        highFps = { camera.wantsHighFps() },
        render = { requestRender() },
        onTick = { if (camera.consumeReturnRequested()) onReturnGesture() },
    )

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MsaaConfigChooser())
        val pointScale = resources.displayMetrics.density.coerceAtMost(2f)
        renderer = TownRenderer(
            camera, worldSeed, pointScale,
            onGlError = { post { onGlError() } },
            onFirstFrame = { post { onFirstFrame() } },
        )
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun cameraSnapshot(): TownCamSnapshot = camera.snapshot

    fun setRenderFlags(reduceMotion: Boolean, staticMode: Boolean) {
        renderer.reduceMotion = reduceMotion
        renderer.staticMode = staticMode
    }

    /** 提交小镇几何 + 软影/光晕覆盖流 + 天空（几何在 Default 线程建好后传入·上传排到 GL 线程）。 */
    fun submitTown(data: TownGeometryData, overlay: TownOverlayData, sky: TownSkyParams) {
        queueEvent { renderer.submitTown(data, overlay, sky) }
        requestRender()
    }

    /** 注入材质贴图（宿主在 IO 线程解码后调·§3.5·缺图桶自动回落无贴图路径）。 */
    fun submitDetailTextures(bitmaps: Map<TownBucket, android.graphics.Bitmap>) {
        renderer.submitDetailTextures(bitmaps)
        requestRender()
    }

    /** 画层天空注入（R2·宿主 IO 解码后调·GL 线程首用懒上传·缺图自动回落渐变天空）。 */
    fun submitPaintedSkies(dusk: android.graphics.Bitmap?, night: android.graphics.Bitmap?) {
        renderer.submitPaintedSkies(dusk, night)
        requestRender()
    }

    fun setInputLocked(locked: Boolean) { inputLocked = locked }

    fun setCinematicPose(pitch: Float, dist: Float) { camera.setCinematicPose(pitch, dist); requestRender() }
    fun clearCinematic() { camera.clearCinematic() }

    /** 选中地点收距（demo:L270·tDist=min(tDist,19)·由 [com.situ.aichat.ui.world.TownSceneView] pick 后调）。 */
    fun focusSelected() { camera.onSelectPlace(); requestRender() }

    /** up-hint 显示判据（dist>34 && intro 完成·§4.3）。 */
    fun wantsUpHint(): Boolean = camera.wantsUpHint()

    fun resumeWorld() {
        onResume(); framePump.start(); requestRender()
    }

    fun pauseWorld() {
        framePump.stop(); onPause()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        if (density > 2f && w > 0 && h > 0) {
            holder.setFixedSize((w * 2f / density).toInt(), (h * 2f / density).toInt())
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (inputLocked) return true // 转场期间吞掉一切触摸（无 pick·相机不动·E11）。
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                camera.setPointerDown(true) // 现连带清平移惯性（catch）
                lastX = event.x; lastY = event.y; downX = event.x; downY = event.y; moved = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    camera.setPinching(true)
                    prevSpan = spanOf(event)
                    prevAngle = angleOf(event)
                    prevMidX = (event.getX(0) + event.getX(1)) * 0.5f
                    prevMidY = (event.getY(0) + event.getY(1)) * 0.5f
                    moved = Float.MAX_VALUE // 捏合禁 tap
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val span = spanOf(event)
                    val angle = angleOf(event)
                    val midX = (event.getX(0) + event.getX(1)) * 0.5f
                    val midY = (event.getY(0) + event.getY(1)) * 0.5f
                    camera.onPinchBy(prevSpan / span.coerceAtLeast(1f))
                    camera.onTwistBy(WorldCameraMath.wrapPi(angle - prevAngle))
                    camera.onTiltBy(midY - prevMidY)
                    val snap = cameraSnapshot()
                    val g = WorldCameraMath.groundPoint(
                        snap.yaw, snap.pitch, snap.dist, snap.tx, snap.ty, snap.tz,
                        midX, midY, width.toFloat(), height.toFloat(), FOV,
                    )
                    if (g != null) camera.setPinchFocal(g[0], g[1]) else camera.clearPinchFocal()
                    prevSpan = span; prevAngle = angle; prevMidX = midX; prevMidY = midY
                } else {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    moved += abs(dx) + abs(dy)
                    if (width > 0 && height > 0) {
                        val snap = cameraSnapshot()
                        val g0 = WorldCameraMath.groundPoint(
                            snap.yaw, snap.pitch, snap.dist, snap.tx, snap.ty, snap.tz,
                            lastX, lastY, width.toFloat(), height.toFloat(), FOV,
                        )
                        val g1 = WorldCameraMath.groundPoint(
                            snap.yaw, snap.pitch, snap.dist, snap.tx, snap.ty, snap.tz,
                            event.x, event.y, width.toFloat(), height.toFloat(), FOV,
                        )
                        if (g0 != null && g1 != null) {
                            var wdx = g0[0] - g1[0]; var wdz = g0[1] - g1[1]
                            val maxLen = 0.12f * snap.dist // 贴地平线反投影距离爆炸的保险丝
                            val len = hypot(wdx.toDouble(), wdz.toDouble()).toFloat()
                            if (len > maxLen && len > 0f) { val s = maxLen / len; wdx *= s; wdz *= s }
                            camera.onPanBy(wdx, wdz)
                        }
                    }
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                camera.setPinching(false)
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
            }
            MotionEvent.ACTION_UP -> {
                if (moved < tapSlopPx && event.pointerCount == 1) onTap(event.x, event.y)
                camera.setPointerDown(false); camera.setPinching(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                camera.setPointerDown(false); camera.setPinching(false)
            }
        }
        requestRender()
        return true
    }

    private fun spanOf(e: MotionEvent): Float =
        hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat()

    private fun angleOf(e: MotionEvent): Float =
        atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))

    private companion object {
        const val FOV = 0.85f // 反投影 fov（TownMath 投影同值·§4A.5）
    }
}
