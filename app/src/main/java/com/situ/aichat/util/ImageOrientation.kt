package com.situ.aichat.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri

/**
 * 「解出一张**摆正**的位图」的单一入口（甲 0）。
 *
 * 为什么需要：相机拍竖图时通常**不转像素**，只在 JPEG 里写一个 EXIF 方向标签，看图 app 负责按标签转。
 * [ImageScaler.decodeSampled] 走裸 `BitmapFactory`，不读该标签 → 竖拍照片解出来是横躺的。头像裁剪屏与
 * 壁纸裁剪屏都吃这一口：取景时横躺，裁出来的成品同错。
 *
 * 职责边界：只管「解码 + 扶正 + 回收中间产物」，**不落盘**（落盘归 [AvatarStore]/[WallpaperStore]），
 * 也**不自己算方向矩阵**——那是 [ImageScaler.matrixFor] 的活（纯几何·8 向含镜像·单源）。
 *
 * ⚠️ 2026-08-29 收单源：本文件原先自带一份只认 90/180/270 三种**旋转**的实现，与图片多模态卷新增的
 * 8 向版本并存且行为不一致——同一张 `FLIP_HORIZONTAL` 的照片（部分前置摄像头 / 修图 app 产出）
 * 发聊天会被正确镜像回来、拿去做头像却不会，用户看到同一张图左右相反。现统一走 [ImageScaler]，
 * 头像与壁纸**首次获得镜像矫正**。
 */
object ImageOrientation {

    /**
     * 从 [uri] 解出一张已按 EXIF 扶正的位图，长边不超过 [maxEdge]。
     *
     * 失败（读流异常 / 解码不出 / 坏图）一律返回 null 交调用方走取消路（E1），不抛。
     * EXIF 无标签、标签为 0、或读取异常 → 原样返回解码结果（E2：绝不因为读不到方向就丢图）。
     */
    fun decodeOriented(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val decoded = ImageScaler.decodeSampled(bytes, maxEdge) ?: return null
        // 矩阵单源在 [ImageScaler.matrixFor]（纯几何·8 向含三种镜像）。本函数只补它**不管**的那一半：
        // 回收中间产物——这是本对象既有的契约（调用方 AvatarCropScreen / WallpaperCropScreen 只拿一张图，
        // 不负责回收上一张），而 `normalizeByExif` 有意不回收（由 ContentImageStore 那条链自己管）。
        val upright = ImageScaler.normalizeByExif(decoded, bytes)
        if (upright !== decoded) decoded.recycle()
        return upright
    }
}
