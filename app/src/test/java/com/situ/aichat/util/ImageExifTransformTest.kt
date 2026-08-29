package com.situ.aichat.util

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：EXIF 方向矫正的 8 向判定（[ImageScaler.matrixFor] / [ImageScaler.needsExifTransform]）。
 *
 * 为什么要做：手机竖拍的 JPEG 常把「转 90°」写在 EXIF 里而不是像素里。不读它，
 * ① 存进 App 的照片在气泡/九宫格里是躺倒的（朋友圈 / 日记既有隐患，随本卷一并修）；
 * ② 发给多模态模型时更直接影响识图结果（模型看到的是一张躺倒的图）。
 */
class ImageExifTransformTest {

    @Test
    fun `正向与未定义不做变换`() {
        assertNull(ImageScaler.matrixFor(ExifInterface.ORIENTATION_NORMAL))
        assertNull(ImageScaler.matrixFor(ExifInterface.ORIENTATION_UNDEFINED))
        assertFalse(ImageScaler.needsExifTransform(ExifInterface.ORIENTATION_NORMAL))
    }

    @Test
    fun `三个旋转向都要变换`() {
        listOf(
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_ROTATE_270,
        ).forEach { assertTrue("朝向 $it 应需要变换", ImageScaler.needsExifTransform(it)) }
    }

    @Test
    fun `三个镜像向与两个转置向都要变换`() {
        listOf(
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
        ).forEach { assertNotNull("朝向 $it 应给出矩阵", ImageScaler.matrixFor(it)) }
    }

    @Test
    fun `八个合法朝向里恰有七个需要变换`() {
        val all = listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
        assertTrue(all.count { ImageScaler.needsExifTransform(it) } == 7)
    }

    @Test
    fun `未知值当作无需变换`() {
        assertFalse(ImageScaler.needsExifTransform(99))
    }
}
