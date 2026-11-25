package com.situ.aichat.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * [AppActionIcons] 扩展仓（后 13 枚媒体/状态/资产高频图标），拆分只为保持单文件 ≤ 300 行；
 * 调用方统一从 [AppActionIcons] 访问。
 */
internal object AppActionIconsExtra {

    private val PLACEHOLDER = SolidColor(Color.Black)
    private const val W = 1.7f

    private fun builder(name: String, autoMirror: Boolean = false) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    )

    val Mic: ImageVector by lazy {
        builder("ActionMic").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // 胶囊麦身 (9..15, y 4..14, r=3)
                moveTo(9f, 7f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6f, 0f)
                lineTo(15f, 11f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -6f, 0f)
                close()
                // 底部 U 托架
                moveTo(6.5f, 11.5f)
                arcToRelative(5.5f, 5.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 11f, 0f)
                // 底座立杆 + 横脚
                moveTo(12f, 17f)
                lineTo(12f, 20f)
                moveTo(9f, 20f)
                lineTo(15f, 20f)
            }
        }.build()
    }

    val Play: ImageVector by lazy {
        builder("ActionPlay").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8f, 6.2f)
                lineTo(17.5f, 12f)
                lineTo(8f, 17.8f)
                close()
            }
        }.build()
    }

    val Pause: ImageVector by lazy {
        builder("ActionPause").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8.5f, 6f)
                lineTo(8.5f, 18f)
                moveTo(15.5f, 6f)
                lineTo(15.5f, 18f)
            }
        }.build()
    }

    val Stop: ImageVector by lazy {
        builder("ActionStop").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8f, 6.5f)
                lineTo(16f, 6.5f)
                quadTo(17.5f, 6.5f, 17.5f, 8f)
                lineTo(17.5f, 16f)
                quadTo(17.5f, 17.5f, 16f, 17.5f)
                lineTo(8f, 17.5f)
                quadTo(6.5f, 17.5f, 6.5f, 16f)
                lineTo(6.5f, 8f)
                quadTo(6.5f, 6.5f, 8f, 6.5f)
                close()
            }
        }.build()
    }

    val Pin: ImageVector by lazy {
        builder("ActionPin").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9f, 4.5f)
                lineTo(15f, 4.5f)
                moveTo(10f, 4.5f)
                lineTo(9.5f, 10f)
                lineTo(7f, 13.5f)
                lineTo(17f, 13.5f)
                lineTo(14.5f, 10f)
                lineTo(14f, 4.5f)
                moveTo(12f, 13.5f)
                lineTo(12f, 20f)
            }
        }.build()
    }

    val Lock: ImageVector by lazy {
        builder("ActionLock").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7.5f, 11f)
                lineTo(16.5f, 11f)
                quadTo(18f, 11f, 18f, 12.5f)
                lineTo(18f, 18f)
                quadTo(18f, 19.5f, 16.5f, 19.5f)
                lineTo(7.5f, 19.5f)
                quadTo(6f, 19.5f, 6f, 18f)
                lineTo(6f, 12.5f)
                quadTo(6f, 11f, 7.5f, 11f)
                close()
                moveTo(8.5f, 11f)
                lineTo(8.5f, 8.2f)
                arcToRelative(3.5f, 3.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7f, 0f)
                lineTo(15.5f, 11f)
                moveTo(12f, 14.2f)
                lineTo(12f, 16.4f)
            }
        }.build()
    }

    val Person: ImageVector by lazy {
        builder("ActionPerson").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // 头部圆 (cx=12, cy=8.2, r=3.3)
                moveTo(15.3f, 8.2f)
                arcToRelative(3.3f, 3.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -6.6f, 0f)
                arcToRelative(3.3f, 3.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6.6f, 0f)
                close()
                // 肩线弧
                moveTo(5.5f, 19.2f)
                quadTo(6.5f, 14.5f, 12f, 14.5f)
                quadTo(17.5f, 14.5f, 18.5f, 19.2f)
            }
        }.build()
    }

    val Calendar: ImageVector by lazy {
        builder("ActionCalendar").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.5f, 6.2f)
                lineTo(17.5f, 6.2f)
                quadTo(19f, 6.2f, 19f, 7.7f)
                lineTo(19f, 17.5f)
                quadTo(19f, 19f, 17.5f, 19f)
                lineTo(6.5f, 19f)
                quadTo(5f, 19f, 5f, 17.5f)
                lineTo(5f, 7.7f)
                quadTo(5f, 6.2f, 6.5f, 6.2f)
                close()
                moveTo(8.5f, 4.2f)
                lineTo(8.5f, 7.4f)
                moveTo(15.5f, 4.2f)
                lineTo(15.5f, 7.4f)
                moveTo(5f, 10.2f)
                lineTo(19f, 10.2f)
            }
        }.build()
    }

    val Clock: ImageVector by lazy {
        builder("ActionClock").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(19.5f, 12f)
                arcToRelative(7.5f, 7.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, -15f, 0f)
                arcToRelative(7.5f, 7.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15f, 0f)
                close()
                moveTo(12f, 7.8f)
                lineTo(12f, 12.2f)
                lineTo(15.2f, 14f)
            }
        }.build()
    }

    val Coin: ImageVector by lazy {
        builder("ActionCoin").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(19.5f, 12f)
                arcToRelative(7.5f, 7.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, -15f, 0f)
                arcToRelative(7.5f, 7.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15f, 0f)
                close()
                moveTo(12f, 8.2f)
                lineTo(12f, 15.8f)
                moveTo(9.8f, 10.5f)
                lineTo(14.2f, 10.5f)
                moveTo(9.8f, 13.5f)
                lineTo(14.2f, 13.5f)
            }
        }.build()
    }

    val Gift: ImageVector by lazy {
        builder("ActionGift").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // 盒盖
                moveTo(5.5f, 9.5f)
                lineTo(18.5f, 9.5f)
                quadTo(19.5f, 9.5f, 19.5f, 10.5f)
                lineTo(19.5f, 12f)
                quadTo(19.5f, 13f, 18.5f, 13f)
                lineTo(5.5f, 13f)
                quadTo(4.5f, 13f, 4.5f, 12f)
                lineTo(4.5f, 10.5f)
                quadTo(4.5f, 9.5f, 5.5f, 9.5f)
                close()
                // 盒身
                moveTo(6f, 13f)
                lineTo(6f, 18f)
                quadTo(6f, 19.5f, 7.5f, 19.5f)
                lineTo(16.5f, 19.5f)
                quadTo(18f, 19.5f, 18f, 18f)
                lineTo(18f, 13f)
                // 中缝丝带
                moveTo(12f, 9.5f)
                lineTo(12f, 19.5f)
                // 蝴蝶结双环
                moveTo(12f, 9.5f)
                curveTo(10f, 9.5f, 8.2f, 8.2f, 8.2f, 6.7f)
                curveTo(8.2f, 5.4f, 9.3f, 4.8f, 10.3f, 5.2f)
                curveTo(11.3f, 5.6f, 11.9f, 7.4f, 12f, 9.5f)
                moveTo(12f, 9.5f)
                curveTo(14f, 9.5f, 15.8f, 8.2f, 15.8f, 6.7f)
                curveTo(15.8f, 5.4f, 14.7f, 4.8f, 13.7f, 5.2f)
                curveTo(12.7f, 5.6f, 12.1f, 7.4f, 12f, 9.5f)
            }
        }.build()
    }

    val AddPhoto: ImageVector by lazy {
        builder("ActionAddPhoto").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // 相框（右上角留空给加号）
                moveTo(12.5f, 5.5f)
                lineTo(6.5f, 5.5f)
                quadTo(5f, 5.5f, 5f, 7f)
                lineTo(5f, 17f)
                quadTo(5f, 18.5f, 6.5f, 18.5f)
                lineTo(17f, 18.5f)
                quadTo(18.5f, 18.5f, 18.5f, 17f)
                lineTo(18.5f, 11.5f)
                // 山峰线
                moveTo(5.5f, 16f)
                lineTo(9.8f, 11.8f)
                lineTo(13.2f, 15f)
                lineTo(15.5f, 13f)
                lineTo(18.3f, 15.8f)
                // 右上小加号
                moveTo(17.5f, 4.5f)
                lineTo(17.5f, 9.5f)
                moveTo(15f, 7f)
                lineTo(20f, 7f)
            }
        }.build()
    }

    val Warning: ImageVector by lazy {
        builder("ActionWarning").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10.8f, 5.2f)
                lineTo(4.4f, 16.8f)
                quadTo(3.8f, 18.2f, 5.4f, 18.2f)
                lineTo(18.6f, 18.2f)
                quadTo(20.2f, 18.2f, 19.6f, 16.8f)
                lineTo(13.2f, 5.2f)
                quadTo(12f, 3.8f, 10.8f, 5.2f)
                close()
                moveTo(12f, 9.6f)
                lineTo(12f, 13.2f)
            }
            path(fill = PLACEHOLDER) {
                moveTo(13f, 15.8f)
                arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 0f)
                arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 0f)
                close()
            }
        }.build()
    }
}
