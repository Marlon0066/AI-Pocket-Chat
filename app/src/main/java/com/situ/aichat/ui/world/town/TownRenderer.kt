package com.situ.aichat.ui.world.town

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.situ.aichat.ui.world.continent.ContinentShaders
import com.situ.aichat.ui.world.continent.SkyStop
import com.situ.aichat.ui.world.planet.PlanetShaders
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** 一座小镇的天空/辉光参数（7 停靠色 + 位置 + 辉光 α·由 [TownData.sky]/[TownData.glowA] 组装·§4.1E）。 */
internal class TownSkyParams(val colors: FloatArray, val pos: FloatArray, val glowA: Float) {
    companion object {
        fun of(sky: List<SkyStop>, glowA: Float): TownSkyParams {
            val colors = FloatArray(21)
            for (i in 0..6) {
                val c = sky[i].color
                colors[i * 3] = c[0]; colors[i * 3 + 1] = c[1]; colors[i * 3 + 2] = c[2]
            }
            return TownSkyParams(colors, FloatArray(7) { sky[it].pos }, glowA)
        }
    }
}

/**
 * 小镇盒景 GLES2 渲染器（W9c 图纸 §2/§4.1E·三 pass：背景→星点→lit→emis·**禁背面剔除**·水体在 lit 流内故无
 * 独立水 pass·demo:L275-296）。装载时 [submitTown] 缓冲上传（GL 线程 queueEvent）。首帧成功后 [onFirstFrame]
 * （揭幕）；编译/链接失败 → [onGlError]（皆由 GLView 封装为主线程回调·不崩·§5 E11·统一走 WorldScreen.onSceneGlError）。
 * GL 上下文重建（onSurfaceCreated 再回调）→ 重编译 + 用 CPU 几何副本重传（§3.6）。
 */
internal class TownRenderer(
    private val camera: TownCamera,
    private val worldSeed: Long,
    private val pointScale: Float,
    private val onGlError: () -> Unit,
    private val onFirstFrame: () -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile var reduceMotion: Boolean = false
    @Volatile var staticMode: Boolean = false

    private var glReady = false
    private var aspect = 1f
    private var startNanos = 0L
    private var lastNanos = 0L
    private var firstFrameSent = false

    // 程序
    private var bgProg = 0; private var bgAPos = 0; private var bgUSky = 0; private var bgUSkyPos = 0; private var bgUGlow = 0
    private var starProg = 0; private var starAStar = 0; private var starUTime = 0; private var starUAnim = 0; private var starUScale = 0

    /** lit/emis 各自 attrib/uniform 句柄（同 C_VS·分程序取·emis 无 uSun→-1）。 */
    private class TownProg(val prog: Int) {
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aNor = GLES20.glGetAttribLocation(prog, "aNor")
        val aCol = GLES20.glGetAttribLocation(prog, "aCol")
        val uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        val uSun = GLES20.glGetUniformLocation(prog, "uSun")
        val uFogCol = GLES20.glGetUniformLocation(prog, "uFogCol")
        val uSceneTint = GLES20.glGetUniformLocation(prog, "uSceneTint")
        val uTex = GLES20.glGetUniformLocation(prog, "uTex")
        val uTexScale = GLES20.glGetUniformLocation(prog, "uTexScale")
        val uTexMix = GLES20.glGetUniformLocation(prog, "uTexMix")
        val uDuskSec = GLES20.glGetUniformLocation(prog, "uDuskSec")
        val uLampT = GLES20.glGetUniformLocation(prog, "uLampT")
    }
    private var litP: TownProg? = null

    /** 小镇窗火（错峰 + 熄灭态·[TownShaders.T_FS_EMIS_GLOW]）。 */
    private var emisGlowP: TownProg? = null

    /** 远景层专用（R2·撤 J8 豁免：`vC×1.15` 语义不变 + 接场景色温——深夜随场景转靛蓝衬画层星空·不接错峰）。 */
    private var farP: TownProg? = null

    /** 软影程序（pos3+uv2·标准 alpha 混合·深度测开写关）。 */
    private class ShadowProg(val prog: Int) {
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aUv = GLES20.glGetAttribLocation(prog, "aUv")
        val uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
    }
    private var shadowP: ShadowProg? = null

    /** 窗火光晕程序（billboard·加色混合·深度测开写关）。 */
    private class GlowProg(val prog: Int) {
        val aCenter = GLES20.glGetAttribLocation(prog, "aCenter")
        val aUv = GLES20.glGetAttribLocation(prog, "aUv")
        val aSize = GLES20.glGetAttribLocation(prog, "aSize")
        val uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        val uCamRight = GLES20.glGetUniformLocation(prog, "uCamRight")
        val uCamUp = GLES20.glGetUniformLocation(prog, "uCamUp")
        val uDuskSec = GLES20.glGetUniformLocation(prog, "uDuskSec")
        val uLampT = GLES20.glGetUniformLocation(prog, "uLampT")
    }
    private var glowP: GlowProg? = null

    /** 一条几何流：GPU 缓冲 + CPU 副本（上下文重建时按副本重传·§3.6）。 */
    private inner class Stream(private val comps: Int = 9) {
        var vbo = 0
        var count = 0
        @Volatile var cpu: FloatArray? = null
        fun upload() { cpu?.let { vbo = replaceBuffer(vbo, it); count = it.size / comps } }
        /** 上下文重建：旧 id 已随上下文失效，清 0 后由 [upload] 重建（勿删旧 id·会误删新上下文里复用同号的缓冲）。 */
        fun invalidate() { vbo = 0; count = 0 }
    }

    // 缓冲：lit 六桶（[TownBucket] 锁定渲染序）+ emis 一流
    private val litStreams: List<Pair<TownBucket, Stream>> = TownBucket.values().map { it to Stream() }
    private val emisStream = Stream()
    // 覆盖流（§3.4）：软影 pos3+uv2 · 光晕 center3+uv2+size1
    private val shadowStream = Stream(5)
    private val glowStream = Stream(6)
    private var quadVbo = 0
    private var starVbo = 0
    private var farVbo = 0; private var farCount = 0

    private val stars = TownGeometry.buildTownStars(worldSeed)
    private val farScenery = TownGeometry.buildFarScenery(worldSeed)   // 远景层（§3.3·与场景独立·随 worldSeed 确定）

    @Volatile private var sky: TownSkyParams? = null

    /** 白天基准太阳（demo:L214）。黄昏/深夜由 [TownAmbience] 接管方向。 */
    private var lastAmb: TownAmbience.Snapshot? = null

    // ── 贴图（材质五桶 §3.5 + 画层天空 R2）：注入/懒上传/上下文失效全权委托 [TownRenderTextures]（§9 行数硬顶拆出）──
    private val textures = TownRenderTextures()
    private var skyProg = 0; private var skyAPos = 0; private var skyUTex = 0; private var skyUUvSX = 0; private var skyUAlpha = 0
    private var skyAlpha = 0f       // 渐显进度（0..1·逐帧 dt/2.5s 推进·冻结直切）
    private var skyPhaseShown = 0   // 正在展示的贴图相位（1 黄昏 / 2 深夜·切相位重起渐显）

    /** 注入材质位图（任意线程·@Volatile 发布；上传推迟到 GL 线程首次用到该桶时）。 */
    fun submitDetailTextures(bitmaps: Map<TownBucket, Bitmap>) = textures.submitDetail(bitmaps)

    /** 注入画层天空位图（任意线程·@Volatile 发布）。 */
    fun submitPaintedSkies(dusk: Bitmap?, night: Bitmap?) = textures.submitSkies(dusk, night)

    /** 提交小镇几何 + 覆盖流 + 天空（GL 线程·queueEvent 调）。 */
    fun submitTown(data: TownGeometryData, overlay: TownOverlayData, skyParams: TownSkyParams) {
        for ((i, pair) in data.litByBucket.withIndex()) litStreams[i].second.cpu = pair.second
        emisStream.cpu = data.emis
        shadowStream.cpu = overlay.shadows
        glowStream.cpu = overlay.glows
        sky = skyParams
        if (glReady) uploadTown()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 上下文重建：一切 GL 句柄（缓冲 / 纹理）随旧上下文失效 → 先清 0 再重建（§3.6·J6）。
        for ((_, st) in litStreams) st.invalidate()
        emisStream.invalidate()
        shadowStream.invalidate()
        glowStream.invalidate()
        textures.invalidate()
        try {
            bgProg = link(PlanetShaders.BG_VS, TownShaders.T_BG_FS)
            skyProg = link(PlanetShaders.BG_VS, TownShaders.T_SKY_TEX_FS)
            starProg = link(PlanetShaders.STAR_VS, PlanetShaders.STAR_FS)
            litP = TownProg(link(TownShaders.T_VS_WORLD, TownShaders.T_FS_LIT))
            emisGlowP = TownProg(link(TownShaders.T_VS_WORLD, TownShaders.T_FS_EMIS_GLOW))
            farP = TownProg(link(ContinentShaders.C_VS, TownShaders.T_FS_FAR))
            shadowP = ShadowProg(link(TownShaders.T_VS_SHADOW, TownShaders.T_FS_SHADOW))
            glowP = GlowProg(link(TownShaders.T_VS_GLOW, TownShaders.T_FS_GLOW))
        } catch (e: RuntimeException) {
            glReady = false
            onGlError()
            return
        }
        bgAPos = GLES20.glGetAttribLocation(bgProg, "aPos")
        bgUSky = GLES20.glGetUniformLocation(bgProg, "uSky[0]")
        bgUSkyPos = GLES20.glGetUniformLocation(bgProg, "uSkyPos[0]")
        bgUGlow = GLES20.glGetUniformLocation(bgProg, "uGlowA")
        skyAPos = GLES20.glGetAttribLocation(skyProg, "aPos")
        skyUTex = GLES20.glGetUniformLocation(skyProg, "uSkyTex")
        skyUUvSX = GLES20.glGetUniformLocation(skyProg, "uUvSX")
        skyUAlpha = GLES20.glGetUniformLocation(skyProg, "uAlpha")
        starAStar = GLES20.glGetAttribLocation(starProg, "aStar")
        starUTime = GLES20.glGetUniformLocation(starProg, "uTime")
        starUAnim = GLES20.glGetUniformLocation(starProg, "uAnim")
        starUScale = GLES20.glGetUniformLocation(starProg, "uPointScale")

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
        quadVbo = arrayBuffer(floatBuf(quad), quad.size * 4)
        starVbo = arrayBuffer(floatBuf(stars), stars.size * 4)
        farVbo = arrayBuffer(floatBuf(farScenery), farScenery.size * 4); farCount = farScenery.size / 9

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        startNanos = System.nanoTime()
        lastNanos = startNanos
        glReady = true
        uploadTown() // 上下文重建后用 CPU 副本重传（首建时副本可能为 null → 待 submitTown）
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height == 0) 1f else width.toFloat() / height.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!glReady) return

        val now = System.nanoTime()
        val dt = ((now - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastNanos = now
        val frozen = reduceMotion || staticMode
        val uTime = if (frozen) 0f else ((now - startNanos) / 1_000_000_000.0).toFloat()

        camera.integrate(dt, reduceMotion = frozen)
        val cam = camera.snapshot
        val mvp = TownMath.townMvp(cam.yaw, cam.pitch, cam.dist, cam.tx, cam.ty, cam.tz, aspect)
        val s = sky

        // ── 昼夜天色氛围（阶段3.1 参数层）：白天沿用城市天空；黄昏/深夜换拍板色板+压低太阳。
        // reduceMotion/staticMode 冻结在最后快照（静态单帧降级·管线约定 §3）。──
        val amb = if (frozen) lastAmb ?: TownAmbience.current(reduceMotion = true)
        else TownAmbience.current().also { lastAmb = it }
        val sEff = if (amb.skyColors != null && s != null) TownSkyParams(amb.skyColors, s.pos, amb.glowA) else s
        val sunArr = amb.sun

        // ── ① 背景（关深度）② 星点（关深度写）── demo:L289-296 无剔除
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        // ── 背景：常驻 7 停靠渐变 quad 打底（白天主视觉 / 画层渐显期与缺图兜底）──
        if (sEff != null) {
            GLES20.glUseProgram(bgProg)
            bindAttrib(quadVbo, bgAPos, 2, 0, 0)
            GLES20.glUniform3fv(bgUSky, 7, sEff.colors, 0)
            GLES20.glUniform1fv(bgUSkyPos, 7, sEff.pos, 0)
            GLES20.glUniform1f(bgUGlow, sEff.glowA)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        }
        // ── ①b 画层天空（R2·天空入 GL）：黄昏/深夜把水彩天空整幅画在几何之前——屋顶/山影经深度遮挡它，
        // 「房子抠出来在天空前面」的正确图层序；渐显 2.5s（冻结直切）·缺图 α=0 恒回落渐变（双轨兜底）。──
        val skyTarget = amb.paintedPhase
        if (skyTarget != 0 && skyTarget != skyPhaseShown) { skyPhaseShown = skyTarget; skyAlpha = 0f }
        val skyTex = if (skyPhaseShown > 0) textures.ensureSkyTex(skyPhaseShown) else 0
        val skyIn = skyTarget == skyPhaseShown && skyTarget != 0
        skyAlpha = when {
            skyTex == 0 -> 0f
            frozen -> if (skyIn) 1f else 0f
            else -> (skyAlpha + (if (skyIn) dt else -dt) / 2.5f).coerceIn(0f, 1f)
        }
        if (skyTex != 0 && skyAlpha > 0f) {
            val skyBmp = textures.skyBitmap(skyPhaseShown)
            val imgAspect = if (skyBmp != null && skyBmp.height > 0) skyBmp.width.toFloat() / skyBmp.height else 16f / 9f
            GLES20.glUseProgram(skyProg)
            bindAttrib(quadVbo, skyAPos, 2, 0, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, skyTex)
            GLES20.glUniform1i(skyUTex, 0)
            GLES20.glUniform1f(skyUUvSX, (aspect / imgAspect).coerceAtMost(1f))
            GLES20.glUniform1f(skyUAlpha, skyAlpha)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        }
        GLES20.glUseProgram(starProg)
        bindAttrib(starVbo, starAStar, 4, 0, 0)
        GLES20.glUniform1f(starUTime, uTime)
        GLES20.glUniform1f(starUAnim, if (frozen) 0f else 1f)
        GLES20.glUniform1f(starUScale, pointScale)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, TownGeometry.STAR_COUNT)

        // ── ②b 远景层（山剪影 + 邻村灯火·emis flat·世界锚定于盒景北缘外·同场景 MVP 贴地平线·深度仍关=背景层·§3.3）──
        if (farCount > 0) drawScene(farP!!, farVbo, farCount, mvp, sunArr, amb)

        // ── ③ lit 六桶（深度测试开·禁背面剔除·§3.2 锁定序 ground→stone→plain→wall→roof→foliage·同一程序逐桶绘）──
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        var litDrawn = 0
        if (sEff != null) {
            for ((bucket, st) in litStreams) {
                if (st.count <= 0) continue
                drawScene(litP!!, st.vbo, st.count, mvp, sunArr, amb, bucket)
                litDrawn += st.count
            }
        }

        // ── ③b 软影（万物落影·深度测开、深度写关·标准 alpha 混合）──
        if (shadowStream.count > 0 && sEff != null) {
            val sp = shadowP!!
            GLES20.glDepthMask(false)
            GLES20.glUseProgram(sp.prog)
            bindAttrib(shadowStream.vbo, sp.aPos, 3, TownOverlayGeometry.SHADOW_STRIDE, 0)
            bindAttrib(shadowStream.vbo, sp.aUv, 2, TownOverlayGeometry.SHADOW_STRIDE, 12)
            GLES20.glUniformMatrix4fv(sp.uMVP, 1, false, mvp, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, shadowStream.count)
            GLES20.glDepthMask(true)
        }

        // ── ④ emis（窗/灯头·黄昏起按世界坐标哈希错峰点亮·白天为冷玻璃熄灭态·§3.3/§4.2）──
        if (emisStream.count > 0 && sEff != null) drawScene(emisGlowP!!, emisStream.vbo, emisStream.count, mvp, sunArr, amb)

        // ── ⑤ 窗火光晕（billboard·加色混合·深度测开写关·与同位窗同刻点亮）──
        if (glowStream.count > 0 && sEff != null) {
            val gp = glowP!!
            GLES20.glDepthMask(false)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
            GLES20.glUseProgram(gp.prog)
            bindAttrib(glowStream.vbo, gp.aCenter, 3, TownOverlayGeometry.GLOW_STRIDE, 0)
            bindAttrib(glowStream.vbo, gp.aUv, 2, TownOverlayGeometry.GLOW_STRIDE, 12)
            bindAttrib(glowStream.vbo, gp.aSize, 1, TownOverlayGeometry.GLOW_STRIDE, 20)
            GLES20.glUniformMatrix4fv(gp.uMVP, 1, false, mvp, 0)
            // 相机基向量 = 视图旋转 rotX(pitch)·rotY(yaw) 的前两行（billboard 恒正对屏幕）。
            val cy = kotlin.math.cos(cam.yaw); val sy = kotlin.math.sin(cam.yaw)
            val cp = kotlin.math.cos(cam.pitch); val sp2 = kotlin.math.sin(cam.pitch)
            GLES20.glUniform3f(gp.uCamRight, cy, 0f, sy)
            GLES20.glUniform3f(gp.uCamUp, sp2 * sy, cp, -sp2 * cy)
            GLES20.glUniform1f(gp.uDuskSec, amb.duskSec)
            GLES20.glUniform1f(gp.uLampT, amb.lampT)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, glowStream.count)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(true)
        }

        // 首帧揭幕：几何已上传且真正画出后才触发（避免揭在空场景上）。
        if (!firstFrameSent && litDrawn > 0 && sEff != null) { firstFrameSent = true; onFirstFrame() }
    }

    private fun drawScene(
        sp: TownProg, vbo: Int, count: Int, mvp: FloatArray, sunArr: FloatArray, amb: TownAmbience.Snapshot,
        bucket: TownBucket? = null,
    ) {
        GLES20.glUseProgram(sp.prog)
        bindAttrib(vbo, sp.aPos, 3, 36, 0)
        bindAttrib(vbo, sp.aNor, 3, 36, 12)
        bindAttrib(vbo, sp.aCol, 3, 36, 24)
        GLES20.glUniformMatrix4fv(sp.uMVP, 1, false, mvp, 0)
        if (sp.uSun >= 0) GLES20.glUniform3fv(sp.uSun, 1, sunArr, 0)
        // 场景色温 + 距离雾色：值单源 = 本帧氛围快照（§4.1·emis/远景程序无这两个 uniform → 句柄 -1 跳过）。
        if (sp.uSceneTint >= 0) GLES20.glUniform3fv(sp.uSceneTint, 1, amb.sceneTint, 0)
        if (sp.uFogCol >= 0) GLES20.glUniform3fv(sp.uFogCol, 1, amb.fog, 0)
        // 窗火错峰主控（emis 辉光程序独有·lit / 远景层句柄为 -1 跳过）。
        if (sp.uDuskSec >= 0) GLES20.glUniform1f(sp.uDuskSec, amb.duskSec)
        if (sp.uLampT >= 0) GLES20.glUniform1f(sp.uLampT, amb.lampT)
        // 材质：该桶有图才混（§3.5 双轨兜底·无图 uTexMix=0 = 数学恒等回落无贴图现状）。
        if (sp.uTexMix >= 0) {
            val tex = bucket?.let { textures.ensureDetailTex(it) } ?: 0
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glUniform1i(sp.uTex, 0)
            GLES20.glUniform1f(sp.uTexScale, if (bucket == null) 0f else TownTextures.texScaleOf(bucket))
            GLES20.glUniform1f(sp.uTexMix, if (tex == 0) 0f else TownTextures.TEX_MIX)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
    }

    private fun uploadTown() {
        for ((_, st) in litStreams) st.upload()
        emisStream.upload()
        shadowStream.upload()
        glowStream.upload()
    }

    private fun replaceBuffer(old: Int, data: FloatArray): Int {
        if (old != 0) GLES20.glDeleteBuffers(1, intArrayOf(old), 0)
        return arrayBuffer(floatBuf(data), data.size * 4)
    }

    private fun bindAttrib(vbo: Int, loc: Int, size: Int, stride: Int, offset: Int) {
        if (loc < 0) return // 共用 C_VS 时 emis 程序会剔除未用的 aNor → 跳过（demo 同款无害）
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, size, GLES20.GL_FLOAT, false, stride, offset)
    }

    private fun arrayBuffer(buffer: FloatBuffer, byteSize: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, byteSize, buffer, GLES20.GL_STATIC_DRAW)
        return ids[0]
    }

    private fun floatBuf(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(sh)
            GLES20.glDeleteShader(sh)
            throw RuntimeException("town shader compile failed: $log")
        }
        return sh
    }

    private fun link(vs: String, fs: String): Int {
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("town program link failed: $log")
        }
        return prog
    }
}
