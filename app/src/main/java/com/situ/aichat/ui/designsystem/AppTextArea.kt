package com.situ.aichat.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * 宏占位 `{{...}}` 的匹配（等长着色·配 [OffsetMapping.Identity] 不动光标偏移）。`[^{}]+` 排除嵌套花括号·
 * ICU 安全（无变长 look-behind / `\p{Emoji}` / `\s` 依赖·见 reference-android-icu-regex-pitfalls）。
 * internal 供单测独立验证。
 */
internal val MacroRegex = Regex("""\{\{[^{}]+\}\}""")

/** 返回 [text] 中所有宏占位的字符区间（含首末·按 UTF-16 char 下标，与 VisualTransformation offset 同口径）。 */
internal fun findMacroRanges(text: String): List<IntRange> =
    MacroRegex.findAll(text).map { it.range }.toList()

/** 给 `{{...}}` 着 [color] + Medium 字重；等长替换故 [OffsetMapping.Identity]（光标/选择行为不变）。 */
private class MacroHighlightTransformation(private val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotated = buildAnnotatedString {
            append(text.text)
            findMacroRanges(text.text).forEach { r ->
                addStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium), r.first, r.last + 1)
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

/**
 * Fable-5 多行书写区（设计语言 §3 + §1·2026-06-19 过审·输入框重构批2）。
 *
 * 替代裸 M3 `OutlinedTextField` + `FontFamily.Monospace` 的「代码/终端」观感：暖色软填充
 * （[AppColors.surface] sunken）+ 16dp medium 圆角 + 思源黑体正文（**去等宽**）+ 聚焦陶土玫细环
 * （方案 A·与 [AppSearchField] 同源）。[highlightMacros]=true 时给 `{{...}}` 实时上陶土色
 * （[AppColors.accent] text·等长不动光标）——提示词编辑器用，普通多行文本默认关。
 *
 * [label]=框上方静态标签（范式 A·与 [AppTextField] 同源），[supportingText]/[isError] 同款（错误时环
 * 与 label/脚注转 `status.onError`）。底座 = foundation [BasicTextField]（多行·IME/光标/选择/语义由它
 * 保证·只重定义视觉·设计语言 §5）。聚焦/错误环走效果轴（ζ1.0 永不过冲）淡入，[rememberReduceMotion] 瞬时。
 *
 * 进阶槽位（默认行为不变·按需启用）：[enabled]=false 时禁编辑、文字转 tertiary（异步加载门控）；
 * [maxLines] 限行（超出内部滚动·如 4 行评论框）；[focusRequester] 透传到内部字段（进屏自动聚焦弹键盘）；
 * [fillMaxHeight]=true 时字段填满父高（全屏长文编辑器·需调用方给 [modifier] 一个有界高度如 fillMaxSize·
 * 不要与 [label]/[supportingText] 同用）。
 *
 * 颜色覆盖（[containerColor]/[contentColor]/[placeholderColor]·**均默认 null=沿用主题取值·既有调用点零改动**）：
 * 供「深玻璃上文本域需融为一体」的特殊底（如线下见面四步沉浸输入·§4.6）在 [AppColors] 之外临时替填充/字色/占位色。
 */
@Composable
fun AppTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = 120.dp,
    maxLines: Int = Int.MAX_VALUE,
    fillMaxHeight: Boolean = false,
    focusRequester: FocusRequester? = null,
    highlightMacros: Boolean = false,
    containerColor: Color? = null,
    contentColor: Color? = null,
    placeholderColor: Color? = null,
    textStyle: TextStyle? = null,
) {
    val colors = AppTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val reduceMotion = rememberReduceMotion()
    val ringColor by animateColorAsState(
        targetValue = when {
            isError -> colors.status.onError
            isFocused -> colors.accent.primary
            else -> Color.Transparent
        },
        animationSpec = if (reduceMotion) snap<Color>() else AppMotion.effectMediumSpring<Color>(),
        label = "appTextAreaRing",
    )
    val accentOrError = if (isError) colors.status.onError else colors.text.secondary
    val macroColor = colors.accent.text
    val transformation = remember(highlightMacros, macroColor) {
        if (highlightMacros) MacroHighlightTransformation(macroColor) else VisualTransformation.None
    }

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
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillMaxHeight) Modifier.weight(1f) else Modifier)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            enabled = enabled,
            maxLines = maxLines,
            textStyle = (textStyle ?: AppTypography.body).copy(
                color = if (enabled) (contentColor ?: colors.text.primary) else colors.text.tertiary,
            ),
            cursorBrush = SolidColor(colors.accent.primary),
            visualTransformation = transformation,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (fillMaxHeight) Modifier.fillMaxHeight() else Modifier.heightIn(min = minHeight))
                        .clip(AppShapes.medium)
                        .background(containerColor ?: colors.surface.sunken)
                        .border(width = 2.dp, color = ringColor, shape = AppShapes.medium)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(text = placeholder, style = textStyle ?: AppTypography.body, color = placeholderColor ?: colors.text.secondary)
                    }
                    innerTextField()
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
 * [TextFieldValue] 重载：光标感知版（支持「把宏点按插入到光标处」等场景，如提示词模块的模块内容编辑）。
 * 视觉/软填充/宏高亮/聚焦环与 String 版完全一致，只是 value 带选区/光标，供需要 [TextFieldValue.selection]
 * 的调用方（光标定位插入、富交互）使用。
 */
@Composable
fun AppTextArea(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    minHeight: Dp = 120.dp,
    highlightMacros: Boolean = false,
    textStyle: TextStyle? = null,
) {
    val colors = AppTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val reduceMotion = rememberReduceMotion()
    val ringColor by animateColorAsState(
        targetValue = when {
            isError -> colors.status.onError
            isFocused -> colors.accent.primary
            else -> Color.Transparent
        },
        animationSpec = if (reduceMotion) snap<Color>() else AppMotion.effectMediumSpring<Color>(),
        label = "appTextAreaRingTfv",
    )
    val accentOrError = if (isError) colors.status.onError else colors.text.secondary
    val macroColor = colors.accent.text
    val transformation = remember(highlightMacros, macroColor) {
        if (highlightMacros) MacroHighlightTransformation(macroColor) else VisualTransformation.None
    }

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
            textStyle = (textStyle ?: AppTypography.body).copy(color = colors.text.primary),
            cursorBrush = SolidColor(colors.accent.primary),
            visualTransformation = transformation,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .clip(AppShapes.medium)
                        .background(colors.surface.sunken)
                        .border(width = 2.dp, color = ringColor, shape = AppShapes.medium)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (value.text.isEmpty() && placeholder != null) {
                        Text(text = placeholder, style = textStyle ?: AppTypography.body, color = colors.text.secondary)
                    }
                    innerTextField()
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
