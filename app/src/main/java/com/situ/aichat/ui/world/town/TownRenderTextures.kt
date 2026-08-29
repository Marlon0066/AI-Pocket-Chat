package com.situ.aichat.ui.world.town

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils

/**
 * 小镇渲染贴图管家（R2 自 [TownRenderer] 拆出·图纸 §9 行数硬顶执行·**只搬不改**）：材质五桶（§3.5）与
 * 画层天空两相位（R2）的位图注入 + GL 线程懒上传。位图 @Volatile 发布（任意线程注入·GL 线程消费）；
 * 上下文重建时 [invalidate] 只清 id 不删（旧 id 已随上下文失效·删会误伤新上下文同号缓冲——J6/D-3 同理）。
 */
internal class TownRenderTextures {

    @Volatile private var detailBitmaps: Map<TownBucket, Bitmap> = emptyMap()
    private val detailTex = HashMap<TownBucket, Int>()

    @Volatile private var bmpSkyDusk: Bitmap? = null
    @Volatile private var bmpSkyNight: Bitmap? = null
    private var texSkyDusk = 0
    private var texSkyNight = 0

    fun submitDetail(bitmaps: Map<TownBucket, Bitmap>) {
        detailBitmaps = bitmaps
    }

    fun submitSkies(dusk: Bitmap?, night: Bitmap?) {
        bmpSkyDusk = dusk; bmpSkyNight = night
    }

    /** 该相位的天空位图（cover 裁切算纵横比用）。 */
    fun skyBitmap(phase: Int): Bitmap? = if (phase == 1) bmpSkyDusk else bmpSkyNight

    /** 上下文重建：全部纹理 id 归零（由各 ensure 重传）。 */
    fun invalidate() {
        detailTex.clear()
        texSkyDusk = 0; texSkyNight = 0
    }

    /** 该桶的材质纹理 id（GL 线程·首用懒上传·REPEAT+mipmap）；无图返 0 → 调用方置 uTexMix=0。 */
    fun ensureDetailTex(bucket: TownBucket): Int {
        detailTex[bucket]?.let { return it }
        val bmp = detailBitmaps[bucket] ?: return 0
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        detailTex[bucket] = ids[0]
        return ids[0]
    }

    /** 该相位的天空纹理 id（GL 线程·首用懒上传）；NPOT（2048×1152）→ CLAMP_TO_EDGE + LINEAR 无 mipmap（ES2 核心保证面）。 */
    fun ensureSkyTex(phase: Int): Int {
        if (phase == 1 && texSkyDusk != 0) return texSkyDusk
        if (phase == 2 && texSkyNight != 0) return texSkyNight
        val bmp = (if (phase == 1) bmpSkyDusk else bmpSkyNight) ?: return 0
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        if (phase == 1) texSkyDusk = ids[0] else texSkyNight = ids[0]
        return ids[0]
    }
}
