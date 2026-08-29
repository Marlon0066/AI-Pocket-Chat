package com.situ.aichat.util

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * T1：契约 §B2 的**兜底体积钳**（超限就把长边 ×0.8 再压一轮·LobeChat 手法）。
 *
 * 这半句在契约里写了、账本打了勾、代码零实现（R3 🔵-1），用户 2026-08-29 拍板「实现它」。
 *
 * 真实阈值是 3MB，日常一轮都跑不到（1568px + q85 通常 < 1MB）——所以这里**注入一个小阈值**来验循环本身，
 * 否则就得造一张真·3MB 的病态图，慢且脆。夹具用随机噪点：JPEG 压不动它，才逼得出多轮收缩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContentImageSizeClampTest {

    /**
     * 高熵噪点图：纯色/渐变会被 JPEG 压到几 KB，那样测不出「压不下去时会不会缩边」。
     *
     * 默认边长取 1024 是有讲究的：兜底钳有一道 `MIN_CLAMP_EDGE = 320` 的底线，从 1024 起算
     * （1024→819→655→524→419→335）五轮都在底线之上，与产线的真实起点 1568 同构。
     * 用小夹具（如 480）会在第二轮就撞上底线提前收手，测出来的是底线不是循环。
     */
    private fun noiseBitmap(size: Int = 1024): Bitmap {
        val rnd = Random(20260829)
        val pixels = IntArray(size * size) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun `超限的图会被逐轮缩边直到压进阈值`() {
        val src = noiseBitmap()
        val unclamped = ContentImageStore.encodeClamped(src, maxBytes = Int.MAX_VALUE)
        val target = unclamped.size / 4 // 必然触发多轮
        val clamped = ContentImageStore.encodeClamped(src, maxBytes = target)

        assertTrue("钳后应真的变小（钳前 ${unclamped.size}B / 钳后 ${clamped.size}B）", clamped.size < unclamped.size)
        assertTrue("应压进阈值 ${target}B，实测 ${clamped.size}B", clamped.size <= target)
    }

    @Test
    fun `没超限的图一个像素都不动`() {
        // 反向钉：日常路径（远低于 3MB）必须原样输出，不能因为加了这道钳就白缩一轮画质
        val src = noiseBitmap(size = 64)
        val once = ContentImageStore.encodeClamped(src, maxBytes = Int.MAX_VALUE)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(once, 0, once.size)
        assertEquals("尺寸不该变", 64, decoded.width)
        assertEquals(64, decoded.height)
    }

    @Test
    fun `入参位图绝不被回收_所有权在调用方`() {
        // 循环里会产生并回收缩小副本；若顺手把入参也回收了，saveWithThumbnail 里紧跟着的
        // 「原图写完再缩缩略图」就会拿到一张已回收的位图直接崩。
        val src = noiseBitmap()
        ContentImageStore.encodeClamped(src, maxBytes = 1024)
        assertEquals("入参必须还活着", false, src.isRecycled)
    }

    @Test
    fun `阈值小到不可能达成时_收手而不是无限缩或丢图`() {
        // 3 字节谁也压不到；此时宁可返回一张偏大的图，也不能空转、更不能返回空
        val src = noiseBitmap()
        val out = ContentImageStore.encodeClamped(src, maxBytes = 3)
        assertTrue("必须仍返回一张能解出来的图", out.isNotEmpty())
        val decoded = android.graphics.BitmapFactory.decodeByteArray(out, 0, out.size)
        assertTrue("缩到底线就收手，不该缩成 0", decoded.width >= 1 && decoded.height >= 1)
    }
}
