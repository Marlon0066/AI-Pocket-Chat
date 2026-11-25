package com.situ.aichat.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Fable-5 高频通用自绘线性图标族（去谷歌化批一过审·30 枚），取代高频 `Icons.Filled.*`。
 *
 * 与 [AppTopBarIcons] / [AppNavIcons] / [AppProfileIcons] 同范式：24×24 网格、圆头圆角描边、
 * 主线宽 [W] = 1.7f（折线雪佛龙走 [WBOLD] = 2f 光学补偿）、纯单色占位 [PLACEHOLDER]，由调用侧 `tint` 重染。
 */
object AppActionIcons {

    private val PLACEHOLDER = SolidColor(Color.Black)
    private const val W = 1.7f
    private const val WBOLD = 2f

    private fun builder(name: String, autoMirror: Boolean = false) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    )

    val Close: ImageVector by lazy {
        builder("ActionClose").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.5f, 6.5f)
                lineTo(17.5f, 17.5f)
                moveTo(17.5f, 6.5f)
                lineTo(6.5f, 17.5f)
            }
        }.build()
    }

    val Check: ImageVector by lazy {
        builder("ActionCheck").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = WBOLD, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5.5f, 12.4f)
                lineTo(10f, 16.8f)
                lineTo(18.8f, 7.6f)
            }
        }.build()
    }

    val DoneAll: ImageVector by lazy {
        builder("ActionDoneAll").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3.8f, 12.5f)
                lineTo(7.6f, 16.3f)
                lineTo(15.2f, 8.2f)
                moveTo(9.4f, 12.5f)
                lineTo(13.2f, 16.3f)
                lineTo(20.6f, 8.2f)
            }
        }.build()
    }

    val Send: ImageVector by lazy {
        builder("ActionSend", autoMirror = true).apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.5f, 11.4f)
                lineTo(19.5f, 4.8f)
                lineTo(14.5f, 19.5f)
                lineTo(11.3f, 13.2f)
                close()
                moveTo(11.3f, 13.2f)
                lineTo(19.5f, 4.8f)
            }
        }.build()
    }

    val Copy: ImageVector by lazy {
        builder("ActionCopy").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // 前卡
                moveTo(10f, 8.5f)
                lineTo(17.5f, 8.5f)
                quadTo(19f, 8.5f, 19f, 10f)
                lineTo(19f, 17.5f)
                quadTo(19f, 19f, 17.5f, 19f)
                lineTo(10f, 19f)
                quadTo(8.5f, 19f, 8.5f, 17.5f)
                lineTo(8.5f, 10f)
                quadTo(8.5f, 8.5f, 10f, 8.5f)
                close()
                // 后卡露边
                moveTo(15.5f, 8.5f)
                lineTo(15.5f, 6.5f)
                quadTo(15.5f, 5f, 14f, 5f)
                lineTo(6.5f, 5f)
                quadTo(5f, 5f, 5f, 6.5f)
                lineTo(5f, 14f)
                quadTo(5f, 15.5f, 6.5f, 15.5f)
                lineTo(8.5f, 15.5f)
            }
        }.build()
    }

    val Delete: ImageVector by lazy {
        builder("ActionDelete").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 7f)
                lineTo(19f, 7f)
                moveTo(9.2f, 7f)
                lineTo(9.8f, 4.8f)
                lineTo(14.2f, 4.8f)
                lineTo(14.8f, 7f)
                moveTo(6.8f, 7f)
                lineTo(7.6f, 17.8f)
                quadTo(7.8f, 19.3f, 9.3f, 19.3f)
                lineTo(14.7f, 19.3f)
                quadTo(16.2f, 19.3f, 16.4f, 17.8f)
                lineTo(17.2f, 7f)
                moveTo(10.2f, 10.6f)
                lineTo(10.2f, 15.6f)
                moveTo(13.8f, 10.6f)
                lineTo(13.8f, 15.6f)
            }
        }.build()
    }

    val Edit: ImageVector by lazy {
        builder("ActionEdit").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15.2f, 5.2f)
                lineTo(18.8f, 8.8f)
                lineTo(9.2f, 18.4f)
                lineTo(5.2f, 19f)
                lineTo(5.8f, 15f)
                close()
                moveTo(13.2f, 7.2f)
                lineTo(16.8f, 10.8f)
            }
        }.build()
    }

    val Search: ImageVector by lazy {
        builder("ActionSearch").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(16.5f, 10.8f)
                arcToRelative(5.7f, 5.7f, 0f, isMoreThanHalf = false, isPositiveArc = true, -11.4f, 0f)
                arcToRelative(5.7f, 5.7f, 0f, isMoreThanHalf = false, isPositiveArc = true, 11.4f, 0f)
                close()
                moveTo(15f, 15f)
                lineTo(19.2f, 19.2f)
            }
        }.build()
    }

    val Quote: ImageVector by lazy {
        builder("ActionQuote").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 11.5f)
                lineTo(10f, 11.5f)
                lineTo(10f, 16.5f)
                lineTo(6f, 16.5f)
                close()
                moveTo(6f, 11.5f)
                quadTo(6f, 7.5f, 9.5f, 6.8f)
                moveTo(14f, 11.5f)
                lineTo(18f, 11.5f)
                lineTo(18f, 16.5f)
                lineTo(14f, 16.5f)
                close()
                moveTo(14f, 11.5f)
                quadTo(14f, 7.5f, 17.5f, 6.8f)
            }
        }.build()
    }

    val Reply: ImageVector by lazy {
        builder("ActionReply", autoMirror = true).apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9.5f, 7f)
                lineTo(5f, 11.2f)
                lineTo(9.5f, 15.4f)
                moveTo(5.5f, 11.2f)
                lineTo(14.5f, 11.2f)
                quadTo(19f, 11.2f, 19f, 16f)
                lineTo(19f, 18f)
            }
        }.build()
    }

    val Refresh: ImageVector by lazy {
        builder("ActionRefresh").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18.5f, 11.5f)
                arcToRelative(6.5f, 6.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -1.6f, -4.3f)
                moveTo(18.5f, 5.2f)
                lineTo(18.5f, 9f)
                lineTo(14.7f, 9f)
            }
        }.build()
    }

    val Download: ImageVector by lazy {
        builder("ActionDownload").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 5f)
                lineTo(12f, 14.8f)
                moveTo(8.2f, 11.2f)
                lineTo(12f, 15f)
                lineTo(15.8f, 11.2f)
                moveTo(5.5f, 16.5f)
                lineTo(5.5f, 18f)
                quadTo(5.5f, 19f, 6.8f, 19f)
                lineTo(17.2f, 19f)
                quadTo(18.5f, 19f, 18.5f, 18f)
                lineTo(18.5f, 16.5f)
            }
        }.build()
    }

    val ArrowDown: ImageVector by lazy {
        builder("ActionArrowDown").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 5.5f)
                lineTo(12f, 18.2f)
                moveTo(7.2f, 13.6f)
                lineTo(12f, 18.4f)
                lineTo(16.8f, 13.6f)
            }
        }.build()
    }

    val ChevronUp: ImageVector by lazy {
        builder("ActionChevronUp").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = WBOLD, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.6f, 14.4f)
                lineTo(12f, 9.2f)
                lineTo(17.4f, 14.4f)
            }
        }.build()
    }

    val ChevronDown: ImageVector by lazy {
        builder("ActionChevronDown").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = WBOLD, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.6f, 9.6f)
                lineTo(12f, 14.8f)
                lineTo(17.4f, 9.6f)
            }
        }.build()
    }

    val ChevronLeft: ImageVector by lazy {
        builder("ActionChevronLeft", autoMirror = true).apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = WBOLD, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14.4f, 6.6f)
                lineTo(9.2f, 12f)
                lineTo(14.4f, 17.4f)
            }
        }.build()
    }

    val ChevronRight: ImageVector by lazy {
        builder("ActionChevronRight", autoMirror = true).apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = WBOLD, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9.6f, 6.6f)
                lineTo(14.8f, 12f)
                lineTo(9.6f, 17.4f)
            }
        }.build()
    }

    val Mic: ImageVector get() = AppActionIconsExtra.Mic
    val Play: ImageVector get() = AppActionIconsExtra.Play
    val Pause: ImageVector get() = AppActionIconsExtra.Pause
    val Stop: ImageVector get() = AppActionIconsExtra.Stop
    val Pin: ImageVector get() = AppActionIconsExtra.Pin
    val Lock: ImageVector get() = AppActionIconsExtra.Lock
    val Person: ImageVector get() = AppActionIconsExtra.Person
    val Calendar: ImageVector get() = AppActionIconsExtra.Calendar
    val Clock: ImageVector get() = AppActionIconsExtra.Clock
    val Coin: ImageVector get() = AppActionIconsExtra.Coin
    val Gift: ImageVector get() = AppActionIconsExtra.Gift
    val AddPhoto: ImageVector get() = AppActionIconsExtra.AddPhoto
    val Warning: ImageVector get() = AppActionIconsExtra.Warning
}
