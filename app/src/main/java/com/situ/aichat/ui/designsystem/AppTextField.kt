package com.situ.aichat.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * Fable-5 单行表单输入框（设计语言 §3 + §1·2026-06-19 过审·输入框重构批3）。
 *
 * 替代裸 M3 `OutlinedTextField` 的硬描边观感：暖色软填充（[AppColors.surface] sunken）+ 16dp medium
 * 圆角 + 思源黑体正文 + 聚焦陶土玫细环（方案 A·与 [AppSearchField]/[AppTextArea] 同源）。
 * **label 范式 = 框上方静态标签**（方案 A 拍板 2026-06-19·软填充无描边缺口可供 M3 浮动标签依附）。
 *
 * 错误态 [isError] 时环色与 label/[supportingText] 转 `status.onError`（优先于聚焦环）。支持
 * [prefix]/[suffix] 单位、[trailingIcon] 槽位（眼睛/loading/清除）、[visualTransformation]（密码遮蔽）、
 * 可选 [textStyle]（如兑换码等宽居中）。
 * 底座 = foundation [BasicTextField]（IME/光标/选择/语义由它保证·只重定义视觉·设计语言 §5）；
 * 聚焦/错误环走效果轴（ζ1.0 永不过冲）淡入，[rememberReduceMotion] 时瞬时落位。
 *
 * **不替**只读下拉框（M3 `ExposedDropdownMenuBox` anchor·§5 包壳不重写）与多行书写区（用 [AppTextArea]）。
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    prefix: String? = null,
    suffix: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    textStyle: TextStyle? = null,
) {
    val colors = AppTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val reduceMotion = rememberReduceMotion()
    val targetRing = when {
        isError -> colors.status.onError
        isFocused -> colors.accent.primary
        else -> Color.Transparent
    }
    val ringColor by animateColorAsState(
        targetValue = targetRing,
        animationSpec = if (reduceMotion) snap<Color>() else AppMotion.effectMediumSpring<Color>(),
        label = "appTextFieldRing",
    )
    val accentOrError = if (isError) colors.status.onError else colors.text.secondary
    val baseStyle = textStyle ?: AppTypography.body

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = AppTypography.secondary,
                color = accentOrError,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = singleLine,
            textStyle = baseStyle.copy(
                color = if (enabled) colors.text.primary else colors.text.tertiary,
            ),
            cursorBrush = SolidColor(colors.accent.primary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .clip(AppShapes.medium)
                        .background(colors.surface.sunken)
                        .border(width = 2.dp, color = ringColor, shape = AppShapes.medium)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (prefix != null) {
                        Text(prefix, style = AppTypography.body, color = colors.text.secondary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                placeholder,
                                style = baseStyle,
                                color = colors.text.secondary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        innerTextField()
                    }
                    if (suffix != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(suffix, style = AppTypography.body, color = colors.text.secondary)
                    }
                    if (trailingIcon != null) {
                        Spacer(Modifier.width(8.dp))
                        trailingIcon()
                    }
                }
            },
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = AppTypography.caption,
                color = accentOrError,
                modifier = Modifier.padding(start = 4.dp, top = 5.dp),
            )
        }
    }
}

/**
 * [TextFieldValue] 重载：光标感知版（支持全选/光标定位等场景，如宠物改名 dialog 进屏选中现名）。
 * 视觉/软填充/聚焦环/[prefix]/[suffix]/[trailingIcon] 与 String 版完全一致，只是 value 带选区/光标。
 */
@Composable
fun AppTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    prefix: String? = null,
    suffix: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val reduceMotion = rememberReduceMotion()
    val targetRing = when {
        isError -> colors.status.onError
        isFocused -> colors.accent.primary
        else -> Color.Transparent
    }
    val ringColor by animateColorAsState(
        targetValue = targetRing,
        animationSpec = if (reduceMotion) snap<Color>() else AppMotion.effectMediumSpring<Color>(),
        label = "appTextFieldRingTfv",
    )
    val accentOrError = if (isError) colors.status.onError else colors.text.secondary

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = AppTypography.secondary,
                color = accentOrError,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = singleLine,
            textStyle = AppTypography.body.copy(
                color = if (enabled) colors.text.primary else colors.text.tertiary,
            ),
            cursorBrush = SolidColor(colors.accent.primary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .clip(AppShapes.medium)
                        .background(colors.surface.sunken)
                        .border(width = 2.dp, color = ringColor, shape = AppShapes.medium)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (prefix != null) {
                        Text(prefix, style = AppTypography.body, color = colors.text.secondary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.text.isEmpty() && placeholder != null) {
                            Text(placeholder, style = AppTypography.body, color = colors.text.secondary)
                        }
                        innerTextField()
                    }
                    if (suffix != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(suffix, style = AppTypography.body, color = colors.text.secondary)
                    }
                    if (trailingIcon != null) {
                        Spacer(Modifier.width(8.dp))
                        trailingIcon()
                    }
                }
            },
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = AppTypography.caption,
                color = accentOrError,
                modifier = Modifier.padding(start = 4.dp, top = 5.dp),
            )
        }
    }
}
