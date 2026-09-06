package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

/** 标签 15 / 输入 16 / 错误行 13（契约 §6.5「输入行（T2）」）。 */
private val LABEL_SIZE = 15.sp
private val INPUT_SIZE = 16.sp
private val ERROR_SIZE = 13.sp
/** 输入行的上下内距（同滑杆 / 分段行的 12）与错误行的上缝。 */
private val ROW_PAD_V = 12.dp
private val ERROR_TOP = 4.dp

/**
 * T2 表单的输入行（契约 §6.5「输入行（T2）」· 图纸 2026-09-06 卷五 A-9）。
 *
 * 长相 = 组内一行：**标签 15 `text.secondary` 固定 [LiuliPageGeometry.inputLabelWidth] 宽** +
 * **无框**输入 16 `text.primary` 左对齐（占位 `text.tertiary`）。「无框」是关键——组本身已经是那个框，
 * 再套一层 [com.situ.aichat.ui.liuli.designsystem.LiuliField] 的内衬就成了框里画框，故本件直接落在
 * foundation [BasicTextField] 上（IME / 光标 / 选择 / 编辑语义由它保证，只重定义视觉）。
 *
 * [supportingText] 非空时在输入格下方补一行 13 `status.onError`（正则非法这类即时校验）；不传则整行
 * 只有一行高。a11y：整格的 `contentDescription` = [label]（读屏先报「这一格是什么」）。
 */
@Composable
fun LiuliInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    /** [supportingText] 是否走错误红（默认 true = 增补前行为）；「拉到 0 条模型，可手输」这类提示传 false 走 `text.tertiary`。 */
    supportingIsError: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** 输入视觉变换（密钥行传 [androidx.compose.ui.text.input.PasswordVisualTransformation] 打码）。 */
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** 行尾件（密钥行的「眼睛」圆钮）。版位由调用方自己锁，本行只留位置。 */
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    divider: Boolean = true,
) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    LiuliRowBase(
        modifier = modifier,
        minHeight = LiuliPageGeometry.rowMin,
        verticalPadding = ROW_PAD_V,
        divider = divider,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = AppTypography.secondary.copy(fontSize = LABEL_SIZE),
            color = colors.text.secondary,
            modifier = Modifier.width(LiuliPageGeometry.inputLabelWidth).padding(top = ERROR_TOP),
        )
        Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        Column(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = AppTypography.body.fontFamily,
                    fontSize = INPUT_SIZE,
                    color = colors.text.primary,
                ),
                cursorBrush = SolidColor(colors.accent.text),
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                placeholder,
                                style = AppTypography.body.copy(fontSize = INPUT_SIZE),
                                color = colors.text.tertiary,
                            )
                        }
                        inner()
                    }
                },
            )
            if (supportingText != null) {
                Text(
                    supportingText,
                    style = AppTypography.secondary.copy(fontSize = ERROR_SIZE),
                    color = if (supportingIsError) colors.status.onError else colors.text.tertiary,
                    modifier = Modifier.padding(top = ERROR_TOP),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(LiuliPageGeometry.tileGap))
            trailing()
        }
    }
}
