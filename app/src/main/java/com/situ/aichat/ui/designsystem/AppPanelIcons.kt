package com.situ.aichat.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Fable-5 聊天输入栏「+」功能面板自绘图标（契约 FABLE5_CHAT_PLUS_PANEL_PROPOSAL.md §4·C2 精修）。
 *
 * 圆角描边·全曲线无锐角·**单色矢量**（颜色由 [androidx.compose.material3.Icon] 的 `tint` 上色——
 * 启用 `accent.text`、禁用 `text.tertiary`）。`stroke`/`fill` 用占位 [PLACEHOLDER] 黑、渲染时被 tint 整体重染。
 * 路径用 [PathParser] 直接吃 SVG d 串（与设计 mockup 同源），24×24 视口。同 [AppNavIcons]/[AppFeatureIcons] 一族。
 */
object AppPanelIcons {

    private val PLACEHOLDER = SolidColor(Color.Black)
    private const val W = 1.7f

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    private fun ImageVector.Builder.stroke(d: String) = addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        stroke = PLACEHOLDER,
        strokeLineWidth = W,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )

    private fun ImageVector.Builder.solid(d: String) = addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        fill = PLACEHOLDER,
    )

    /** 送礼：礼盒 + 盖线 + 中缝竖带 + 蝴蝶结双环。 */
    val Gift: ImageVector by lazy {
        builder("PanelGift")
            .stroke("M6 12 H18 A1.5 1.5 0 0 1 19.5 13.5 V19 A1.5 1.5 0 0 1 18 20.5 H6 A1.5 1.5 0 0 1 4.5 19 V13.5 A1.5 1.5 0 0 1 6 12 Z")
            .stroke("M4.5 14.6 H19.5")
            .stroke("M12 12 V20.5")
            .stroke("M12 12 C12 12 10.5 8 8 8 C6.8 8 6.3 8.8 6.5 9.6 C6.8 11 9.4 12 12 12 Z")
            .stroke("M12 12 C12 12 13.5 8 16 8 C17.2 8 17.7 8.8 17.5 9.6 C17.2 11 14.6 12 12 12 Z")
            .build()
    }

    /** 红包：竖信封 + 顶部折口弧 + 中心圆封印。 */
    val RedPacket: ImageVector by lazy {
        builder("PanelRedPacket")
            .stroke("M7 3.5 H17 A2 2 0 0 1 19 5.5 V18.5 A2 2 0 0 1 17 20.5 H7 A2 2 0 0 1 5 18.5 V5.5 A2 2 0 0 1 7 3.5 Z")
            .stroke("M5 8.4 C7.6 10.4 9.6 11.4 12 11.4 C14.4 11.4 16.4 10.4 19 8.4")
            .stroke("M14.2 13.8 A2.2 2.2 0 0 1 9.8 13.8 A2.2 2.2 0 0 1 14.2 13.8 Z")
            .build()
    }

    /** 见面：前后两个人（前排实头 + 肩，后排小头 + 肩提示）。 */
    val Meet: ImageVector by lazy {
        builder("PanelMeet")
            .stroke("M11.6 8.5 A2.6 2.6 0 0 1 6.4 8.5 A2.6 2.6 0 0 1 11.6 8.5 Z")
            .stroke("M3.8 18.6 A5.2 5.2 0 0 1 14.2 18.6")
            .stroke("M18.7 9.2 A2.2 2.2 0 0 1 14.3 9.2 A2.2 2.2 0 0 1 18.7 9.2 Z")
            .stroke("M14.7 14.5 C17 14.8 18.8 16.8 18.8 19.1")
            .build()
    }

    /** 约未来见面：日历本（圆角框 + 双挂脚 + 表头线）+ 体内「＋」号（对齐 iOS calendar.badge.plus）。 */
    val FutureMeet: ImageVector by lazy {
        builder("PanelFutureMeet")
            .stroke("M6.5 6 H17.5 A2 2 0 0 1 19.5 8 V17.5 A2 2 0 0 1 17.5 19.5 H6.5 A2 2 0 0 1 4.5 17.5 V8 A2 2 0 0 1 6.5 6 Z")
            .stroke("M8.5 4 V7.5")
            .stroke("M15.5 4 V7.5")
            .stroke("M4.5 10 H19.5")
            .stroke("M12 12.3 V17.3")
            .stroke("M9.5 14.8 H14.5")
            .build()
    }

    /** 通话：话筒（单笔连续轮廓）。 */
    val Call: ImageVector by lazy {
        builder("PanelCall")
            .stroke("M6.4 4.6 C7.1 4.6 7.7 5 8 5.6 L9 7.8 C9.25 8.4 9.1 9.1 8.6 9.55 L7.6 10.45 C8.5 12.35 10.05 13.9 11.95 14.8 L12.85 13.8 C13.3 13.3 14 13.15 14.6 13.4 L16.8 14.4 C17.4 14.7 17.8 15.3 17.8 16 V18.3 C17.8 19.3 17 20.1 16 20 C9.4 19.3 4.7 14.6 4 8 C3.9 7 4.7 5.6 5.5 5.6 Z")
            .build()
    }

    /** 照片：圆角相框 + 日点 + 远近双山（全曲线·与家族同 1.7 描边）。 */
    val Photo: ImageVector by lazy {
        builder("PanelPhoto")
            .stroke("M6.6 4.8 H17.4 A2.8 2.8 0 0 1 20.2 7.6 V16.4 A2.8 2.8 0 0 1 17.4 19.2 H6.6 A2.8 2.8 0 0 1 3.8 16.4 V7.6 A2.8 2.8 0 0 1 6.6 4.8 Z")
            .solid("M10.5 9.4 A1.4 1.4 0 0 1 7.7 9.4 A1.4 1.4 0 0 1 10.5 9.4 Z")
            .stroke("M3.8 15.5 L8.3 11.4 L11.9 14.6")
            .stroke("M11.2 14 L14.6 11 L20.2 15.9")
            .build()
    }

    /** 表情：圆角方贴 + 笑脸（双眼填充点 + 嘴弧）。 */
    val Sticker: ImageVector by lazy {
        builder("PanelSticker")
            .stroke("M8.2 3.6 H15.8 A4.6 4.6 0 0 1 20.4 8.2 V15.8 A4.6 4.6 0 0 1 15.8 20.4 H8.2 A4.6 4.6 0 0 1 3.6 15.8 V8.2 A4.6 4.6 0 0 1 8.2 3.6 Z")
            .solid("M10.2 10 A1 1 0 0 1 8.2 10 A1 1 0 0 1 10.2 10 Z")
            .solid("M15.8 10 A1 1 0 0 1 13.8 10 A1 1 0 0 1 15.8 10 Z")
            .stroke("M8.6 14.2 C9.55 15.5 10.7 16.1 12 16.1 C13.3 16.1 14.45 15.5 15.4 14.2")
            .build()
    }
}
