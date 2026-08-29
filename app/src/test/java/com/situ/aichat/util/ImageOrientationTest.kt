package com.situ.aichat.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * 甲 0·T1-A1：[ImageOrientation] 端到端八向。
 *
 * 断言从**用户遭遇**独立反推，不照搬实现：竖拍照片（EXIF 说「转 90°」而像素是横的）解出来必须是竖的
 * ——即宽高互换（E3）；读不到方向的图**绝不能丢**，原样出图（E2）。
 *
 * ⚠️ **断言必须落在像素位置上，不能只断尺寸**（R3 🟡-3 金丝雀实证）：上一版三条镜像用例断的全是尺寸
 * ——`FLIP_*` 断 `40 to 20`（= 完全不变换时的尺寸）、`TRANSPOSE/TRANSVERSE` 断 `20 to 40`（= 纯旋转
 * 就能满足），夹具还是纯色图。把 [ImageScaler.matrixFor] 的四支退回「只认 90/180/270」的旧行为，
 * **三条全绿**。所以这里改用左右/上下都不对称的夹具，断「那块绿角落最后跑到了哪个角」——
 * (尺寸, 绿角) 这一对能把八个朝向**两两区分开**，任何一支退回旧行为或写错方向都必红。
 *
 * Robolectric 4.16 默认 NATIVE 图形模式 = 真 Skia，故 JPEG 编解码、Matrix 变换、宽高互换都是真实现，
 * 不是影子桩（同 [com.situ.aichat.ui.story.StoryShareCardRendererTest] 的既有先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageOrientationTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private companion object {
        const val W = 40
        const val H = 20
        /** 绿色定位块的边长（取样点要落在它内部，故不能太小）。 */
        const val MARK = 8
        const val TL = "左上"
        const val TR = "右上"
        const val BL = "左下"
        const val BR = "右下"
    }

    /**
     * 造一张**横**图（宽 > 高），像素本身不转——正是相机竖拍时的真实产物。
     *
     * 左右不对称（左半红 / 右半蓝）、上下也不对称（左上角一块绿）：只有这样，镜像才在像素上可观测。
     * 上一版用的是 `eraseColor(Color.RED)` 纯色图——镜像前后一模一样，断什么都是绿的。
     */
    private fun asymmetricJpegBytes(width: Int = W, height: Int = H): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
        paint.color = Color.BLUE
        canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.GREEN
        canvas.drawRect(0f, 0f, MARK.toFloat(), MARK.toFloat(), paint) // 左上角定位块
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, out) // q100：块内取样点不受色度压缩干扰
            out.toByteArray()
        }
    }

    /** 把 EXIF 方向标签写进 JPEG 字节（经临时文件，ExifInterface 的落盘 API 只认 File/FD）。 */
    private fun withExifOrientation(bytes: ByteArray, orientation: Int): ByteArray {
        val f = File.createTempFile("exif", ".jpg")
        f.writeBytes(bytes)
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return f.readBytes().also { f.delete() }
    }

    /** 端到端解一张带该 EXIF 朝向的图，返回 (宽 to 高) 与「绿块落在哪个角」。 */
    private fun decodeAndLocate(orientation: Int?): Pair<Pair<Int, Int>, String> {
        val base = asymmetricJpegBytes()
        val bytes = if (orientation == null) base else withExifOrientation(base, orientation)
        val uri = android.net.Uri.parse("content://test/o_${orientation ?: "none"}.jpg")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        val out = ImageOrientation.decodeOriented(context, uri, maxEdge = 2048)
        assertNotNull("朝向 $orientation 应能解出图", out)
        return (out!!.width to out.height) to greenCorner(out)
    }

    /** 四个角各取一点，找出绿块在哪个角（JPEG 有噪声，故按「哪个通道最大」判色而非等值比较）。 */
    private fun greenCorner(bmp: Bitmap): String {
        val ix = max(2, bmp.width / 8)
        val iy = max(2, bmp.height / 8)
        val corners = listOf(
            TL to (ix to iy),
            TR to (bmp.width - 1 - ix to iy),
            BL to (ix to bmp.height - 1 - iy),
            BR to (bmp.width - 1 - ix to bmp.height - 1 - iy),
        )
        val hits = corners.filter { (_, p) -> isGreenish(bmp.getPixel(p.first, p.second)) }.map { it.first }
        assertEquals("恰好一个角是绿的（实测 $hits）", 1, hits.size)
        return hits.first()
    }

    private fun isGreenish(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return g > r && g > b
    }

    // ---- 八向端到端：(尺寸, 绿角) 两两可区分 ----
    // 源图 40×20，绿块在左上。Matrix 语义手算（不是跑一遍抄输出）：
    //   ROTATE_90  (x,y)→(-y, x)  顺时针 90° → 20×40，绿块到**右上**
    //   ROTATE_180 (x,y)→(-x,-y)                → 40×20，绿块到**右下**
    //   ROTATE_270 (x,y)→( y,-x)                → 20×40，绿块到**左下**
    //   FLIP_H     (x,y)→(-x, y)  左右翻        → 40×20，绿块到**右上**
    //   FLIP_V     (x,y)→( x,-y)  上下翻        → 40×20，绿块到**左下**
    //   TRANSPOSE  = 90° 后再左右翻 →(x,y)→(y,x) 主对角翻 → 20×40，绿块**留在左上**
    //   TRANSVERSE = 270° 后再左右翻→(x,y)→(-y,-x) 副对角翻 → 20×40，绿块到**右下**

    @Test
    fun E3_端到端_竖拍图从uri解出即已扶正() {
        val (size, corner) = decodeAndLocate(ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals("EXIF 说转 90°，出图必须是竖的", 20 to 40, size)
        assertEquals("顺时针 90°：原左上角转到右上", TR, corner)
    }

    @Test
    fun 旋转180_宽高不变但整幅倒过来() {
        val (size, corner) = decodeAndLocate(ExifInterface.ORIENTATION_ROTATE_180)
        assertEquals(40 to 20, size)
        assertEquals(BR, corner)
    }

    @Test
    fun 旋转270_宽高互换且方向与90相反() {
        val (size, corner) = decodeAndLocate(ExifInterface.ORIENTATION_ROTATE_270)
        assertEquals(20 to 40, size)
        assertEquals(BL, corner)
    }

    // ---- 2026-08-29 收单源后的新能力：三种镜像也扶正（此前 ImageOrientation 只认 90/180/270） ----

    @Test
    fun 水平镜像_尺寸不变但像素真的左右翻了() {
        // 这是 D-1 想根治的那件用户可见的事：部分前置摄像头 / 修图 app 产出 FLIP_HORIZONTAL 的自拍，
        // 以前发聊天被镜像回来、拿去做头像却没有，同一张图左右相反。
        // 断在绿角上而不是尺寸上——尺寸对「不变换」和「转 180°」都成立，区分不出对错。
        val (size, corner) = decodeAndLocate(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        assertEquals(40 to 20, size)
        assertEquals("左右翻：原左上角应到右上（若仍在左上 = 整条被忽略；若在右下 = 错写成转 180°）", TR, corner)
    }

    @Test
    fun 垂直镜像_尺寸不变但像素真的上下翻了() {
        val (size, corner) = decodeAndLocate(ExifInterface.ORIENTATION_FLIP_VERTICAL)
        assertEquals(40 to 20, size)
        assertEquals(BL, corner)
    }

    @Test
    fun 转置_主对角翻转_宽高互换且绿角留在左上() {
        // 与 ROTATE_90 同为 20×40：**只有绿角能把两者分开**（旧实现对 TRANSPOSE 不变换，也曾被尺寸断言放过）。
        val (size, corner) = decodeAndLocate(ExifInterface.ORIENTATION_TRANSPOSE)
        assertEquals(20 to 40, size)
        assertEquals(TL, corner)
    }

    @Test
    fun 反转置_副对角翻转_宽高互换且绿角到右下() {
        val (size, corner) = decodeAndLocate(ExifInterface.ORIENTATION_TRANSVERSE)
        assertEquals(20 to 40, size)
        assertEquals(BR, corner)
    }

    @Test
    fun E2_端到端_无标签图原样出_不丢图() {
        val (size, corner) = decodeAndLocate(null)
        assertEquals("读不到方向也绝不能丢图，更不能擅自变换", 40 to 20, size)
        assertEquals(TL, corner)
    }

    @Test
    fun 标签为NORMAL时不做任何变换() {
        val (size, corner) = decodeAndLocate(ExifInterface.ORIENTATION_NORMAL)
        assertEquals(40 to 20, size)
        assertEquals(TL, corner)
    }

    @Test
    fun E1_端到端_坏图解不出_返回null走取消路() {
        val uri = android.net.Uri.parse("content://test/broken.jpg")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArray(32) { 0x11 }.inputStream())

        assertEquals(null, ImageOrientation.decodeOriented(context, uri, maxEdge = 2048))
    }
}
