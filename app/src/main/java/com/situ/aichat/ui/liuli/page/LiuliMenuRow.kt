package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.designsystem.LiuliPopupMenu
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.layout

/** 行尾下拉雪佛龙的尺寸与它左侧的缝（与 `LiuliNavRow` 的 chevron 同档 12 / 8）。 */
private val CHEVRON = 12.dp
private val CHEVRON_GAP = 8.dp

/** 菜单相对行右缘的内缩（图纸 2026-09-06 卷五 A-4 ②「锚在行右下 `DpOffset(-16, 0)`」）。 */
private val MENU_OFFSET = DpOffset((-16).dp, 0.dp)

/**
 * 下拉行（图纸 2026-09-06 卷五 A-4 ②·暖陶 `AppDropdownField` / 原生 `DropdownMenuItem` 行的琉璃对应件）。
 *
 * 长相 = 值行（标题 + 右值）再加一枚 12 chevron-down `text.tertiary`——**不是** [LiuliNavRow]：点开的是就地
 * 菜单不是下一层页，故雪佛龙朝下。整行可点 → 展开 [LiuliPopupMenu]；[options] 里 `selected = true` 的那条
 * 打勾。
 *
 * 菜单挂在**整行**这一层（不是行内某个子件）：`LiuliPopupMenu` 锚的是它所在的那个布局节点，塞进 `Row` 里
 * 会锚到一个零宽占位上、位置无从谈起，故本件把行包在一枚 [Box] 里、菜单与行同级，`TopEnd` +
 * [MENU_OFFSET] 得到「贴着行右缘往左展开」。
 *
 * 展开态由调用方持有（[expanded] / [onExpandedChange]）——同屏多行下拉必须互斥，状态在行里就关不掉别人。
 */
@Composable
fun LiuliMenuRow(
    title: String,
    value: String,
    options: List<LiuliMenuEntry>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    valueWarning: Boolean = false,
    divider: Boolean = true,
) {
    val colors = AppTheme.colors
    Box(modifier) {
        LiuliRowBase(
            onClick = { onExpandedChange(true) },
            enabled = enabled,
            minHeight = if (subtitle != null) LiuliPageGeometry.rowTwoLine else LiuliPageGeometry.rowMin,
            verticalPadding = if (subtitle != null) LiuliPageGeometry.rowTwoLinePad else 0.dp,
            divider = divider,
        ) {
            LiuliRowTitleColumn(title, subtitle, Modifier.weight(1f))
            Spacer(Modifier.width(LiuliPageGeometry.tileGap))
            // 右值**最多占半行**、最多两行靠右：不设上限时右值先量、长串（「服务商 模型名」）会把标题挤成
            // 一字一行；对半硬分又会让短值（「默认」）白占半行、副标被挤成五行（卷五复核 R1 🔴-4 两次装机）。
            // 故右值按「上限半行、实际多宽算多宽」量，标题列吃剩下的全部。
            Box(Modifier.maxWidthFraction(VALUE_MAX_FRACTION), contentAlignment = Alignment.CenterEnd) {
                LiuliRowValue(value, valueWarning, maxLines = 2, textAlign = TextAlign.End)
            }
            Spacer(Modifier.width(CHEVRON_GAP))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.text.tertiary,
                modifier = Modifier.size(CHEVRON),
            )
        }
        LiuliPopupMenu(
            expanded = expanded,
            onDismiss = { onExpandedChange(false) },
            items = options,
            offset = MENU_OFFSET,
        )
    }
}

/** 右值最多占行宽的比例（超过就折行·最多两行）。 */
private const val VALUE_MAX_FRACTION = 0.5f

/**
 * 「上限为父宽的 [fraction]、实际多宽算多宽」：与 `fillMaxWidth(fraction)` 的区别是后者把最小宽也钉死在
 * 该比例（短值也白占半行）；与 `weight` 的区别是本件量完后剩余宽仍归兄弟（标题列）。
 */
private fun Modifier.maxWidthFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val cap = if (constraints.hasBoundedWidth) (constraints.maxWidth * fraction).toInt() else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = cap))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
