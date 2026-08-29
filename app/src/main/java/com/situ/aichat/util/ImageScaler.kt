package com.situ.aichat.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import kotlin.math.max

/**
 * Shared image downscaling used by both [AvatarStore] (character avatars, 512px) and
 * [ContentImageStore] (moment/diary photos, 1024px). The algorithm is identical — a two-pass
 * `inSampleSize` decode to avoid OOM on huge gallery images, then an exact scale so the longest
 * edge equals `maxEdge` — only the cap differs, so it lives here once (CLAUDE.md §2: one copy).
 *
 * No third-party image library; pure `BitmapFactory`. [computeInSampleSize] is `internal` + pure so
 * it can be unit-tested without a device.
 */
object ImageScaler {

    /** Two-pass decode with `inSampleSize` so a huge image never blows up memory. */
    fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * Largest power-of-two sample factor that keeps both edges >= `maxEdge` (so the decoded bitmap
     * is at least `maxEdge` on its longest side, then [scaleToMaxEdge] trims to exact).
     */
    internal fun computeInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (max(w, h) / 2 >= maxEdge) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * 按 EXIF 方向摆正（手机竖拍的 JPEG 常把「转 90°」写在 EXIF 里而非像素里——不读它，
     * 存下来的图在别处显示就是躺倒的；发给多模态模型时更是直接影响识图结果）。
     *
     * 覆盖 8 种朝向（含三种镜像）。无 EXIF / 已正 / 解码失败 → 原样返回同一个 Bitmap 实例
     * （调用方据此判断是否需要 recycle 中间产物）。
     */
    fun normalizeByExif(src: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrNull() ?: return src
        val matrix = matrixFor(orientation) ?: return src
        return runCatching {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        }.getOrNull() ?: src
    }

    /** null = 无需变换（NORMAL / UNDEFINED / 未知值）。`internal` 便于纯逻辑单测。 */
    internal fun matrixFor(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { postRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { postRotate(270f); postScale(-1f, 1f) }
        else -> null
    }

    /** 该朝向是否需要真的做变换（纯谓词·与 [matrixFor] 同源，便于单测穷举 8 向）。 */
    internal fun needsExifTransform(orientation: Int): Boolean = matrixFor(orientation) != null

    /** Exact downscale so the longest edge == `maxEdge` (only when still larger after sampling). */
    fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
