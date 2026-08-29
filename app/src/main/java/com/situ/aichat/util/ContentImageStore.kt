package com.situ.aichat.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

/**
 * Stores moment / diary photos as downscaled JPEGs under `filesDir/content_images`, mirroring how
 * iOS keeps `MomentPost.imageDataArray` / `DiaryEntry.imageDataArray` as external-storage `[Data]`
 * resized to 1024×1024. The entity holds a JSON list of absolute file paths (see
 * `data/model` accessors), not BLOBs.
 *
 * Shared by both Moments (M06) and Diary (M07). Multi-image: [saveAll] copies a batch of picked
 * URIs, skipping any that fail. Same no-GMS path as [AvatarStore]: bytes read via `ContentResolver`
 * (system Photo Picker → SAF fallback on China ROMs), no storage permission, no 3rd-party library.
 */
object ContentImageStore {
    private const val DIR = "content_images"
    /** 朋友圈 / 日记 / DIY 礼物的长边（iOS 同款 1024）。**公开**给备份还原按 key 前缀路由时引用。 */
    const val MOMENT_DIARY_MAX_EDGE = 1024
    private const val MAX_EDGE = MOMENT_DIARY_MAX_EDGE
    private const val JPEG_QUALITY = 85

    /** 单张落盘 JPEG 的体积兜底上限（契约 §B2·见 [encodeClamped]）。 */
    private const val MAX_FILE_BYTES = 3 * 1024 * 1024

    /** 超限后每轮把长边乘这个系数（LobeChat 同款 0.8：收敛够快，又不会一刀砍到糊）。 */
    private const val SIZE_CLAMP_RATIO = 0.8f

    /** 兜底钳的轮次上限：0.8^5 ≈ 0.33，再缩下去就是在毁图而不是省体积了。 */
    private const val SIZE_CLAMP_MAX_ROUNDS = 5

    /** 缩到这个长边还压不下去就收手——再缩用户就看不清了，宁可留一张偏大的图。 */
    private const val MIN_CLAMP_EDGE = 320

    /**
     * 聊天发图的长边上限。取 **1568px** = Anthropic 官方对标准档视觉模型的推荐上限
     *（超过它服务端会自行降采样，预缩只是省 token 与延迟）；同时高于 OpenAI `detail:high`
     * 的有效分辨率（最短边 768 / 最长边 2048 的方框），故对各家都不欠采样。
     */
    const val CHAT_MAX_EDGE = 1568

    /**
     * 聊天图片气泡用的缩略图长边（列表滚动热路径读它，不解全图）。
     *
     * 取 1024 而非更小：气泡恒按 `rememberBubbleMaxWidth`（≈屏宽 0.74）布局，小米 14 上约 291dp ≈ 873 物理 px，
     * 512 的缩略图会被放大 1.7–2.3× 发糊。1024 仍只有原图（[CHAT_MAX_EDGE] 1568）四成像素，滚动解码开销可接受。
     */
    const val THUMBNAIL_EDGE = 1024

    /** [saveWithThumbnail] 的产物：原图 + 缩略图两条绝对路径。 */
    data class StoredImage(val path: String, val thumbnailPath: String?)

    // 解码内存缓存（P15.2 #1，等价 iOS 外存图解码缓存 + 与 AvatarStore/GiftImageStore 同构）。
    // key = "path@px"：同一图按不同显示尺寸各缓存一份。store 永远 mint 新 UUID 文件名 → 换图即换 path = 自动失效。
    // 故意不在淘汰/删除时 recycle——Compose 可能仍持有显示中（同 AvatarStore/GiftImageStore 既有约束），交给 GC。
    private const val CACHE_BYTES = 32 * 1024 * 1024 // 32MB；内容图 1024px(~4MB)/缩略图(~1MB) 混存
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Copy + downscale one picked image into internal storage. Returns the path, or null on failure. */
    suspend fun save(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull() ?: return@withContext null
        saveBytes(context, bytes)
    }

    /** Copy a batch of picked images, preserving order; failures are dropped (best-effort). */
    suspend fun saveAll(context: Context, uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        uris.mapNotNull { save(context, it) }
    }

    /**
     * Store raw image bytes (e.g. restored from a backup) the same way as a picked image.
     * [maxEdge] 默认沿用 1024（朋友圈 / 日记 / DIY 礼物既有口径不变），聊天发图传 [CHAT_MAX_EDGE]。
     * 落盘前按 EXIF 摆正（竖拍照片以前会躺倒——朋友圈 / 日记既有隐患随本次一并修好）。
     */
    suspend fun saveBytes(context: Context, bytes: ByteArray, maxEdge: Int = MAX_EDGE): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val prepared = decodeUpright(bytes, maxEdge) ?: return@runCatching null
                val path = writeJpeg(context, prepared)
                prepared.recycle()
                path
            }.getOrNull()
        }

    /**
     * 聊天发图专用：一次解码同时落「原图（[CHAT_MAX_EDGE]）+ 缩略图（[THUMBNAIL_EDGE]）」。
     * 缩略图给气泡与列表滚动用，原图给全屏查看器与多模态报文用。缩略图写失败不影响原图（返回 null 缩略图）。
     */
    suspend fun saveWithThumbnail(
        context: Context,
        uri: Uri,
        maxEdge: Int = CHAT_MAX_EDGE,
        thumbnailEdge: Int = THUMBNAIL_EDGE,
    ): StoredImage? = withContext(Dispatchers.IO) {
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull() ?: return@withContext null
        runCatching {
            val prepared = decodeUpright(bytes, maxEdge) ?: return@runCatching null
            val path = writeJpeg(context, prepared)
            val thumb = ImageScaler.scaleToMaxEdge(prepared, thumbnailEdge)
            val thumbPath = runCatching { writeJpeg(context, thumb) }.getOrNull()
            if (thumb !== prepared) thumb.recycle()
            prepared.recycle()
            StoredImage(path = path, thumbnailPath = thumbPath)
        }.getOrNull()
    }

    /** 采样解码 → EXIF 摆正 → 精确缩到 maxEdge。中间产物就地回收，返回可直接写盘的位图。 */
    private fun decodeUpright(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val decoded = ImageScaler.decodeSampled(bytes, maxEdge) ?: return null
        val upright = ImageScaler.normalizeByExif(decoded, bytes)
        if (upright !== decoded) decoded.recycle()
        val scaled = ImageScaler.scaleToMaxEdge(upright, maxEdge)
        if (scaled !== upright) upright.recycle()
        return scaled
    }

    private fun writeJpeg(context: Context, bitmap: Bitmap): String {
        val file = File(dir(context), "${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { it.write(encodeClamped(bitmap)) }
        return file.absolutePath
    }

    /**
     * 编码成 JPEG，并把体积**兜底钳**在 [MAX_FILE_BYTES] 内（契约 §B2·LobeChat 手法：
     * 超了就把长边 ×[SIZE_CLAMP_RATIO] 再压一轮）。
     *
     * 为什么是「兜底」而不是主力：长边已先钳在 1568（≈2.5MP）+ q85，实测一张照片通常不到 1MB，
     * 这条循环平时**一轮都不会跑**。它防的是病态输入——极高熵的噪点图 / 截图拼接图 / 某些相机的
     * 高保真输出，这类图会把一次请求的 base64 顶到几 MB，既拖慢发送也可能被服务端拒收。
     *
     * 契约实现前这半句是**零实现却已打勾**的（R3 🔵-1 揪出·用户 2026-08-29 拍板「实现它」）。
     *
     * 入参位图**不回收**（所有权在调用方）；循环中产生的缩小副本就地回收。
     * 缩不动了（[ImageScaler.scaleToMaxEdge] 原样返回）或到轮次上限就收手——宁可存一张偏大的图，
     * 也不能在这里空转或丢图。
     */
    internal fun encodeClamped(bitmap: Bitmap, maxBytes: Int = MAX_FILE_BYTES): ByteArray {
        var current = bitmap
        var encoded = compressJpeg(current)
        var rounds = 0
        while (encoded.size > maxBytes && rounds < SIZE_CLAMP_MAX_ROUNDS) {
            val nextEdge = (max(current.width, current.height) * SIZE_CLAMP_RATIO).toInt()
            if (nextEdge < MIN_CLAMP_EDGE) break
            val smaller = ImageScaler.scaleToMaxEdge(current, nextEdge)
            if (smaller === current) break
            if (current !== bitmap) current.recycle()
            current = smaller
            encoded = compressJpeg(current)
            rounds++
        }
        if (current !== bitmap) current.recycle()
        return encoded
    }

    private fun compressJpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }

    /**
     * Decode a stored image for display, downsampled to ~[targetPx] on the longest edge and held in
     * an [LruCache] keyed `path@px` (same construct as [AvatarStore] / GiftImageStore; previously a
     * bare per-call `BitmapFactory.decodeFile` of the full 1024px JPEG — the hot path that moment
     * grids / diary thumbnails re-decoded on every scroll). [targetPx] defaults to [MAX_EDGE] (full
     * stored size) so non-thumbnail callers are unchanged but now memory-cached. `decodeSampled`
     * never upscales, so a thumbnail caller passing its small cell px gets a real memory win.
     * Returns null for null/blank/missing path. Evicted bitmaps are NOT recycled (Compose may hold them).
     */
    suspend fun load(path: String?, targetPx: Int = MAX_EDGE): Bitmap? {
        if (path.isNullOrEmpty()) return null
        val key = "$path@$targetPx"
        cache.get(key)?.let { return it } // 命中即同步返回，省线程切换 + 磁盘解码（滚动最热路径）
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = File(path).takeIf { it.exists() }?.readBytes() ?: return@runCatching null
                val decoded = ImageScaler.decodeSampled(bytes, targetPx) ?: return@runCatching null
                val scaled = ImageScaler.scaleToMaxEdge(decoded, targetPx)
                if (scaled !== decoded) decoded.recycle()
                cache.put(key, scaled)
                scaled
            }.getOrNull()
        }
    }

    /**
     * 读一张已存图片并编码成多模态报文用的 **data URI**（`data:image/jpeg;base64,…`）。
     * 读盘与 base64 全在 IO 线程（一张 1568px JPEG ≈ 200KB，base64 后 ≈ 270KB——绝不能压在主线程上）。
     * 文件缺失 / 读失败 → null，调用方据此退回语义占位，整条请求不受影响。
     * MIME 恒 `image/jpeg`：本仓所有内容图落盘即转 JPEG（见 [writeJpeg]），与实际字节一致。
     */
    suspend fun loadAsDataUri(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = File(path).takeIf { it.exists() }?.readBytes() ?: return@runCatching null
                "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }.getOrNull()
        }
    }

    /** Delete one image file (best-effort) and evict any cached decodes of it. */
    fun delete(path: String?) {
        if (path.isNullOrEmpty()) return
        evictPath(path)
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    /** Delete a batch of image files (best-effort), e.g. when a moment/diary entry is removed. */
    fun delete(paths: List<String>) {
        paths.forEach { delete(it) }
    }

    /** Drop all cached `path@*` decodes for one path (called on [delete]; keys differ only by px). */
    private fun evictPath(path: String) {
        val prefix = "$path@"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }

    /** Shrink the decode cache under system memory pressure (called by AIChatApplication.onTrimMemory). */
    fun onTrimMemory(level: Int) = cache.trimForMemoryLevel(level)
}
