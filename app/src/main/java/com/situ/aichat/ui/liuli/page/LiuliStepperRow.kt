package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton

/** 值字号（契约 §6.5「值行」的右值同档·`captionNumeric` 等宽数字换位数不跳）。 */
private val VALUE_SIZE = 15.sp

/**
 * 步进器行（图纸 2026-09-06 卷五 A-4 ①·暖陶 `AppStepper` 的琉璃对应件）。
 *
 * 行基线 = [LiuliRowBase]；右端 `[−] 值 [+]`：两枚 [LiuliPageGeometry.stepperButton] 圆玻璃钮（Button 档·
 * 图标 [LiuliPageGeometry.stepperIcon] `accent.text`，色由 [LiuliCircleButton] 自己注入）+ 中间定宽值槽
 * （≥ [LiuliPageGeometry.stepperValueMin] 居中）。到界的那一枚 `enabled = false`（透明度由圆钮自带）。
 *
 * **整行不可点**（点击面只在两枚钮上）：行本身没有「切换 / 打开」语义，一整行可点会让读屏多念一个假动作。
 * a11y = 行挂 `semantics(mergeDescendants = true)` 把「标题（副标）值」并成一条播报，两枚钮因自带
 * `clickable` 是各自的合并边界，仍是两个独立的 `Role.Button` 节点。
 *
 * 触觉：走 [LiuliCircleButton] 自带的 `light()`，**每次点恰一记**（图纸 A-4 ① 写的是 `selection`，但圆钮
 * 内建 `light()` 且它不在本卷可改白名单内，再补一记 `selection` 会变成一次点击响两下·见图纸 §11 D-4）。
 *
 * cd 复用暖陶 `AppStepper` 调用点的两枚键（`reply_rule_decrease` / `reply_rule_increase`·本卷零新增资源键）。
 */
@Composable
fun LiuliStepperRow(
    title: String,
    value: Int,
    range: IntRange,
    valueText: String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
    divider: Boolean = true,
) {
    LiuliRowBase(
        modifier = modifier.semantics(mergeDescendants = true) {},
        minHeight = if (hint != null) LiuliPageGeometry.rowTwoLine else LiuliPageGeometry.rowMin,
        verticalPadding = if (hint != null) LiuliPageGeometry.rowTwoLinePad else 0.dp,
        divider = divider,
    ) {
        LiuliRowTitleColumn(title, hint, Modifier.weight(1f))
        Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        StepButton(
            icon = Icons.Filled.Remove,
            contentDescription = stringResource(R.string.reply_rule_decrease),
            enabled = enabled && value > range.first,
            onClick = { onChange((value - 1).coerceIn(range)) },
        )
        Text(
            valueText,
            style = AppTypography.captionNumeric.copy(fontSize = VALUE_SIZE),
            // 这是**正在被改的那个数**，不是行尾的旁观值，故走主文字色（`LiuliRowValue` 的次级色留给值行）。
            color = AppTheme.colors.text.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.widthIn(min = LiuliPageGeometry.stepperValueMin),
        )
        StepButton(
            icon = Icons.Filled.Add,
            contentDescription = stringResource(R.string.reply_rule_increase),
            enabled = enabled && value < range.last,
            onClick = { onChange((value + 1).coerceIn(range)) },
        )
    }
}

/**
 * 一枚 ± 圆钮：版位恰 [LiuliPageGeometry.stepperButton]（外层盒），48 触达框由 [LiuliCircleButton] 自带的
 * `minimumInteractiveComponentSize` 居中外溢——裸放会把 `Row` 撑成 48 宽把值槽挤走（卷四 R1 🟡-3 同款）。
 * 两枚钮中心相距 ≥ 44 + 28 = 72 > 48，触达框互不重叠。
 */
@Composable
private fun StepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(Modifier.size(LiuliPageGeometry.stepperButton), contentAlignment = Alignment.Center) {
        LiuliCircleButton(
            onClick = onClick,
            contentDescription = contentDescription,
            size = LiuliPageGeometry.stepperButton,
            enabled = enabled,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(LiuliPageGeometry.stepperIcon))
        }
    }
}
