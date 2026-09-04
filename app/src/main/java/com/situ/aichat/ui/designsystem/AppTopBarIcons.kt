package com.situ.aichat.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Fable-5 顶栏自绘图标（M3 清零收官·2026-09-04 过审）——**只服务顶栏**的三枚：返回 / 新建 / 更多。
 *
 * 与既有五族（[AppNavIcons] / [AppFeatureIcons] / [AppPanelIcons] / [AppMomentIcons] / [AppProfileIcons]）
 * 同范式：圆角描边、**单色矢量**，`stroke`/`fill` 用占位 [PLACEHOLDER] 黑，渲染时由
 * [androidx.compose.material3.Icon] 的 `tint` 整体重染（`ColorFilter.tint` SrcIn）。实心圆走「右端点 →
 * 两段半圆 `arcToRelative`」标准画法（避免 large-arc 翻转）。
 *
 * **笔宽取家族值 [W] = 1.7f**（对版稿 CSS 写 1.75 系近似值，家族一致优先——先例 [AppMomentIcons]）；
 * [Back] 例外取 [WBOLD] = 2f，是小尺寸折线的光学补偿（同 [AppProfileIcons.ChevronRight]：细笔的折线在
 * 22dp 上会比同宽的直线显瘦）。
 *
 * **[Back] 带 `autoMirror = true`**：这是 Compose 官方 `materialIcon()` 自身给 `Icons.AutoMirrored.*`
 * 用的机制，故自绘返回箭头在 RTL 下与 `Icons.AutoMirrored.Filled.ArrowBack` 等价镜像，站点侧零特判。
 */
object AppTopBarIcons {

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

    /** 返回：左向雪佛龙（= [AppProfileIcons.ChevronRight] 的精确镜像 x' = 24 − x）。 */
    val Back: ImageVector by lazy {
        builder("TopBarBack", autoMirror = true).apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = WBOLD, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14.4f, 6.6f)
                lineTo(9.2f, 12f)
                lineTo(14.4f, 17.4f)
            }
        }.build()
    }

    /** 新建：十字加号（竖、横两条独立路径·圆头）。 */
    val Add: ImageVector by lazy {
        builder("TopBarAdd").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 5.5f)
                lineTo(12f, 18.5f)
            }
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5.5f, 12f)
                lineTo(18.5f, 12f)
            }
        }.build()
    }

    /** 更多：竖排三个实心圆（cx 12·cy 5.6 / 12 / 18.4·r 1.7）。 */
    val More: ImageVector by lazy {
        builder("TopBarMore").apply {
            path(fill = PLACEHOLDER) {
                // 上 (12, 5.6)
                moveTo(13.7f, 5.6f)
                arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3.4f, 0f)
                arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3.4f, 0f)
                close()
                // 中 (12, 12)
                moveTo(13.7f, 12f)
                arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3.4f, 0f)
                arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3.4f, 0f)
                close()
                // 下 (12, 18.4)
                moveTo(13.7f, 18.4f)
                arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3.4f, 0f)
                arcToRelative(1.7f, 1.7f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3.4f, 0f)
                close()
            }
        }.build()
    }
}
