package com.situ.aichat.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把 App 私有目录里的一张图片存进系统相册（聊天图片长按菜单「保存到相册」）。
 *
 * 用 MediaStore 的 `RELATIVE_PATH` 写进 `Pictures/AI Pocket Chat/`：**minSdk 29 起写自己插入的这条记录
 * 不需要任何存储权限**，也不碰 GMS——符合铁律 #3/#4。写入期间挂 `IS_PENDING`，成功才翻开可见，
 * 中途失败不会在相册里留半张图。
 */
object GallerySaver {

    private const val ALBUM = "AI Pocket Chat"
    private const val MIME_JPEG = "image/jpeg"

    /** @return true=已存进相册。文件不存在、写入失败一律 false（调用方给提示，不崩）。 */
    suspend fun saveImage(context: Context, sourcePath: String?): Boolean = withContext(Dispatchers.IO) {
        val source = sourcePath?.let { File(it) }?.takeIf { it.exists() } ?: return@withContext false
        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, MIME_JPEG)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + ALBUM,
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            val copied = runCatching {
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } } != null
            }.getOrDefault(false)
            if (!copied) {
                resolver.delete(uri, null, null) // 别在相册里留一条空记录
                return@runCatching false
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        }.getOrDefault(false)
    }
}
