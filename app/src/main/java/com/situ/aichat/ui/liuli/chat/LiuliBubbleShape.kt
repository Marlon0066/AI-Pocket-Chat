package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes

/**
 * 琉璃气泡的形与尾巴（图纸 2026-09-05 卷二A §4.4 · 契约 §5.3）：四角 18dp；**只有连发段末条**带尾巴，
 * 那一角收到 5dp（[LiuliShapes.bubbleTailCorner]），尾巴本身是一条 11×13dp 的曲线、伸出泡外 9dp。
 *
 * 尾巴用 `drawBehind` 画在气泡背景**之下**（2dp 重叠段被泡身盖住 = 无缝相接），行容器不裁 → 允许出界。
 */

/** 气泡圆角（契约 §5.3 锁·同 [LiuliShapes.bubble]）。 */
private val BubbleRadius: Dp = 18.dp

/** 尾巴路径的设计尺寸（对版稿 SVG viewBox·图纸 §4.4 锁）。 */
private val TailWidth: Dp = 11.dp
private val TailHeight: Dp = 13.dp

/** 尾巴伸出泡外的量（其余 2dp 压在泡身下·契约 §3.2 「尾巴伸出 9dp 后仍留 3dp 安全边」）。 */
private val TailOverhang: Dp = 9.dp

/** 连发段末条（带尾）与段内其余条（不带尾）的气泡形。[isUser] 决定尾在右下还是左下。 */
internal fun liuliBubbleShape(isUser: Boolean, tail: Boolean): Shape = when {
    !tail -> RoundedCornerShape(BubbleRadius)
    isUser -> RoundedCornerShape(
        topStart = BubbleRadius,
        topEnd = BubbleRadius,
        bottomEnd = LiuliShapes.bubbleTailCorner,
        bottomStart = BubbleRadius,
    )
    else -> RoundedCornerShape(
        topStart = BubbleRadius,
        topEnd = BubbleRadius,
        bottomEnd = BubbleRadius,
        bottomStart = LiuliShapes.bubbleTailCorner,
    )
}

/**
 * 末条尾巴。[color] = 泡在该处的底色，**在 draw 期求值**（`DrawScope` 接收者可读 `size`）——用户泡的尾色是
 * 渐变在泡**底缘**的取样值，随滚动逐帧变；若做成 `Color` 参数就得在组合期读滚动位置 = 每帧重组每一条气泡。
 * [show] 为 false 时零绘制（段内非末条）。**必须挂在 `clip(shape)` 之外**，否则尾巴被泡形裁掉。
 */
internal fun Modifier.liuliBubbleTail(
    isUser: Boolean,
    show: Boolean,
    color: DrawScope.() -> Color,
): Modifier =
    if (!show) this else this.drawBehind {
        val w = TailWidth.toPx()
        val h = TailHeight.toPx()
        val overlap = w - TailOverhang.toPx() // 压在泡身下的 2dp
        val left = if (isUser) size.width - overlap else -TailOverhang.toPx()
        translate(left = left, top = size.height - h) {
            drawPath(tailPath(w, h, mirrored = !isUser), color())
        }
    }

/**
 * 对版稿 SVG 路径 `M0 0v13c4 0 8-1 11-0.2C6 10 2.5 6 0 0z` 按 dp 缩放（图纸 §4.4 逐字）。
 * [mirrored] = 水平镜像（AI 泡的尾巴朝左）。
 */
private fun DrawScope.tailPath(w: Float, h: Float, mirrored: Boolean): Path {
    // 设计坐标（11×13）→ 实际像素；镜像时把 x 翻到 w−x。
    fun px(x: Float) = if (mirrored) w - x / 11f * w else x / 11f * w
    fun py(y: Float) = y / 13f * h
    return Path().apply {
        moveTo(px(0f), py(0f))
        lineTo(px(0f), py(13f))
        cubicTo(px(4f), py(13f), px(8f), py(12f), px(11f), py(12.8f))
        cubicTo(px(6f), py(10f), px(2.5f), py(6f), px(0f), py(0f))
        close()
    }
}
