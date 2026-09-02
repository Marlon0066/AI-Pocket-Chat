package com.situ.aichat.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Fable-5 圈子枢纽功能卡自绘图标（设计语言 §3·与底栏 [AppNavIcons] 同笔法：圆角描边·全曲线无锐角·**单色矢量**，
 * 颜色由 [androidx.compose.material3.Icon] 的 `tint` 上色——这里挂在分色 IconTile 的同族功能深档 `*Ink` 上）。
 *
 * `stroke`/`fill` 用占位 [PLACEHOLDER] 黑，渲染时被 tint 整体重染。日记=笔记本（封皮 + 装订脊 + 三行字）/
 * 故事=摊开的书（两页 + 书脊）/ 宠物=爪印（4 趾垫 + 1 掌垫·实心，呼应底栏「动态」三节点的实心画法）。
 */
object AppFeatureIcons {

    private val PLACEHOLDER = SolidColor(Color.Black)
    private const val W = 1.7f

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    /** 日记：笔记本（封皮圆角矩形 + 左侧装订脊 + 三行字·描边）。 */
    val Diary: ImageVector by lazy {
        builder("FeatureDiary").apply {
            // 封皮（圆角矩形·r2）
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 4f)
                lineTo(16f, 4f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(18f, 18f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(7f, 20f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(5f, 6f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()
            }
            // 装订脊
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8.5f, 4f)
                lineTo(8.5f, 20f)
            }
            // 三行字（末行短）
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(11f, 9f)
                lineTo(15.5f, 9f)
                moveTo(11f, 12.5f)
                lineTo(15.5f, 12.5f)
                moveTo(11f, 16f)
                lineTo(13.5f, 16f)
            }
        }.build()
    }

    /** 故事：摊开的书（左右两页 + 中间书脊·描边）。 */
    val Story: ImageVector by lazy {
        builder("FeatureStory").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // 左页
                moveTo(12f, 7f)
                curveTo(9.5f, 5.3f, 6.5f, 5.3f, 4.5f, 6f)
                lineTo(4.5f, 17.5f)
                curveTo(6.5f, 16.8f, 9.5f, 16.8f, 12f, 18.5f)
                // 右页
                moveTo(12f, 7f)
                curveTo(14.5f, 5.3f, 17.5f, 5.3f, 19.5f, 6f)
                lineTo(19.5f, 17.5f)
                curveTo(17.5f, 16.8f, 14.5f, 16.8f, 12f, 18.5f)
                // 书脊
                moveTo(12f, 7f)
                lineTo(12f, 18.5f)
            }
        }.build()
    }

    /** 设定集（世界书）：闭合书 + 右上书签绶带 + 封面「小世界」徽记（圆 + 赤道线）——与日记（笔记本）/故事（摊开书）同笔法、造型区分。 */
    val Worldbook: ImageVector by lazy {
        builder("FeatureWorldbook").apply {
            // 封面（圆角矩形·r2）
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 4f)
                lineTo(17f, 4f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(19f, 18f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(7f, 20f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(5f, 6f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()
            }
            // 书签绶带（顶边垂下 + V 形缺口）
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(13.6f, 4f)
                lineTo(13.6f, 9.2f)
                lineTo(15.2f, 7.9f)
                lineTo(16.8f, 9.2f)
                lineTo(16.8f, 4f)
            }
            // 封面徽记：小世界（圆 + 赤道线）
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(13.3f, 14.2f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -5.6f, 0f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5.6f, 0f)
                close()
                moveTo(7.7f, 14.2f)
                lineTo(13.3f, 14.2f)
            }
        }.build()
    }

    /** 宠物：爪印（4 趾垫 + 1 掌垫·实心）。 */
    val Pet: ImageVector by lazy {
        builder("FeaturePet").apply {
            path(fill = PLACEHOLDER) {
                // 掌垫（圆 cx12 cy15.5 r3.3）
                moveTo(15.3f, 15.5f)
                arcToRelative(3.3f, 3.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -6.6f, 0f)
                arcToRelative(3.3f, 3.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6.6f, 0f)
                close()
                // 趾垫 1（cx7.6 cy10.2 r1.5）
                moveTo(9.1f, 10.2f)
                arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 0f)
                close()
                // 趾垫 2（cx10.4 cy7.8 r1.55）
                moveTo(11.95f, 7.8f)
                arcToRelative(1.55f, 1.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3.1f, 0f)
                arcToRelative(1.55f, 1.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3.1f, 0f)
                close()
                // 趾垫 3（cx13.6 cy7.8 r1.55）
                moveTo(15.15f, 7.8f)
                arcToRelative(1.55f, 1.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3.1f, 0f)
                arcToRelative(1.55f, 1.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3.1f, 0f)
                close()
                // 趾垫 4（cx16.4 cy10.2 r1.5）
                moveTo(17.9f, 10.2f)
                arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 0f)
                close()
            }
        }.build()
    }

    /** 我们的日子：日历页（封皮圆角矩形 + 顶栏线 + 两只环 + 右下翻页折角·描边）。 */
    val Days: ImageVector by lazy {
        builder("FeatureDays").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 6f); lineTo(18f, 6f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(20f, 14f); lineTo(14f, 20f); lineTo(6f, 20f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(4f, 8f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()
            }
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 10f); lineTo(20f, 10f)           // 顶栏线
                moveTo(8.5f, 4f); lineTo(8.5f, 7.5f)        // 左环
                moveTo(15.5f, 4f); lineTo(15.5f, 7.5f)      // 右环
                moveTo(14f, 20f); lineTo(14f, 14f); lineTo(20f, 14f) // 翻页折角
            }
        }.build()
    }
}
