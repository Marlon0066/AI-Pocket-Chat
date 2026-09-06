package com.situ.aichat.ui.liuli.designsystem

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.glass.LiuliGlassSpec
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import androidx.compose.ui.draw.alpha

/** 内衬框几何（§3.2）：常规 44 高 / [big] 56 高 · 圆角 14 · 底 = `surface.raised` 62%。 */
private val FIELD_HEIGHT = 44.dp
private val FIELD_HEIGHT_BIG = 56.dp
private val FIELD_SHAPE = RoundedCornerShape(14.dp)
private const val FIELD_FILL_ALPHA = 0.62f
/** 禁用态透明度（与 [LiuliSwitch] / [LiuliButton] 同值）。 */
private const val FIELD_DISABLED_ALPHA = 0.38f

/**
 * 琉璃表单内衬框（图纸 2026-09-05 卷二C §4.11 · 落值 §3.2 · A-15）。
 *
 * 底座 = foundation [BasicTextField]（IME / 光标 / 选择 / 编辑语义由它保证·只重定义视觉），皮 = 玻璃上的
 * 「内衬」：`surface.raised` 62% 半透明底 + 0.5dp 玻璃发丝（[LiuliGlassSpec] 同源·与玻璃片边缘同一句话）
 * + 聚焦 1dp `accent.text` 环。**禁 M3 `TextField`**（§9 ⑤）。
 *
 * [big] = 金额大字档（红包 composer）：56 高 + `titleMedium` + tnum 等宽数字。
 * [isError] 时 label / supporting 转 `status.onError`（环仍走聚焦色——错误提示已在下方那行说清）。
 * 聚焦环走效果轴 [AppMotion].effectMediumSpring 淡入，[rememberReduceMotion] 时瞬时落位。
 * a11y：[label] 非空时同时作节点的 `contentDescription`（读屏先报「这一格是什么」再报内容）。
 */
@Composable
fun LiuliField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** 键盘动作（卷五增补·**加法零回归**：默认 [KeyboardActions.Default] = 与增补前同）。回车即兑换这类用。 */
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    prefix: String? = null,
    /** 尾缀（单位这类恒定后缀·卷四 A-1 钱包面板要「金币」·加法零回归：不传 = 与增补前逐字节同行为）。 */
    suffix: String? = null,
    big: Boolean = false,
    /** 多行时的行数上限（`singleLine = false` 才生效·加法零回归：默认无上限 = 增补前行为）。 */
    maxLines: Int = Int.MAX_VALUE,
    /**
     * 内衬框最小高（卷五 A-4 ⑤「多行文本域」·**加法零回归**：null = 常规 44 / [big] 56，与增补前逐字节同渲染）。
     * 多行组用 96（契约 §6.5「输入行（T2）」）。
     */
    minHeight: Dp? = null,
    /**
     * 输入视觉变换（卷五 A-4 ⑤·**加法零回归**：默认 [VisualTransformation.None] = 增补前行为）。
     * 提示词编辑器传暖陶的 `MacroHighlightTransformation`（等长着色·不动光标偏移）。
     */
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /**
     * 覆盖正文字形（卷五 A-4 ⑨「等宽字体槽」·**加法零回归**：null = 按 [big] 取原样式）。
     * 只覆盖字形，颜色仍由本件统一注入（传进来的 color 会被覆写为玻璃上主文字色）。
     */
    textStyle: TextStyle? = null,
    /** 禁用（卷五复核 R1 补·**加法零回归**：默认 true = 增补前行为）：不可编辑 + 整框淡到 38%（兑换中的兑换码框）。 */
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val dark = LocalIsDarkTheme.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val reduceMotion = rememberReduceMotion()
    val ringColor by animateColorAsState(
        targetValue = if (isFocused) colors.accent.text else Color.Transparent,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "liuliFieldRing",
    )
    val hairline = if (dark) LiuliGlassSpec.hairlineDark else LiuliGlassSpec.hairlineLight
    val supportColor = if (isError) colors.status.onError else onGlass.secondary
    val resolvedTextStyle = when {
        textStyle != null -> textStyle.copy(color = onGlass.primary)
        big -> AppTypography.titleMedium.copy(color = onGlass.primary, fontFeatureSettings = "tnum")
        else -> AppTypography.listPreview.copy(color = onGlass.primary)
    }

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = AppTypography.snackbarBody,
                color = supportColor,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else FIELD_DISABLED_ALPHA)
                .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
            enabled = enabled,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else maxLines,
            textStyle = resolvedTextStyle,
            visualTransformation = visualTransformation,
            cursorBrush = SolidColor(colors.accent.text),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .heightIn(min = minHeight ?: if (big) FIELD_HEIGHT_BIG else FIELD_HEIGHT)
                        .clip(FIELD_SHAPE)
                        .background(colors.surface.raised.copy(alpha = FIELD_FILL_ALPHA))
                        .border(LiuliGlassSpec.hairlineWidth, hairline, FIELD_SHAPE)
                        .border(1.dp, ringColor, FIELD_SHAPE)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    if (prefix != null) {
                        Text(prefix, style = resolvedTextStyle, color = onGlass.secondary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            // 撑满格宽：占位要跟着正文的 textAlign 走（居中输入的兑换码框·占位若按内容宽就只能靠左）。
                            Text(placeholder, style = resolvedTextStyle, color = onGlass.secondary, modifier = Modifier.fillMaxWidth())
                        }
                        innerTextField()
                    }
                    if (suffix != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(suffix, style = resolvedTextStyle, color = onGlass.secondary)
                    }
                }
            },
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = AppTypography.caption,
                color = supportColor,
                modifier = Modifier.padding(start = 4.dp, top = 5.dp),
            )
        }
    }
}

/**
 * [LiuliField] 的 [TextFieldValue] 变体（卷五 A-4 ⑤ 增补·**加法零回归**：与 `String` 那枚同一副长相，
 * 只是把光标 / 选区也交给调用方）。给「点一枚宏片就插到光标处」这类需要动选区的编辑器用
 * （提示词模块编辑页）。两枚共用同一段视觉实现，改长相只需改上面那枚的 `decorationBox`。
 */
@Composable
fun LiuliField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** 键盘动作（卷五增补·**加法零回归**：默认 [KeyboardActions.Default] = 与增补前同）。回车即兑换这类用。 */
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    minHeight: Dp? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle? = null,
) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val dark = LocalIsDarkTheme.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val reduceMotion = rememberReduceMotion()
    val ringColor by animateColorAsState(
        targetValue = if (isFocused) colors.accent.text else Color.Transparent,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "liuliFieldRingTfv",
    )
    val hairline = if (dark) LiuliGlassSpec.hairlineDark else LiuliGlassSpec.hairlineLight
    val supportColor = if (isError) colors.status.onError else onGlass.secondary
    val resolvedTextStyle = (textStyle ?: AppTypography.listPreview).copy(color = onGlass.primary)

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = AppTypography.snackbarBody,
                color = supportColor,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
            singleLine = singleLine,
            textStyle = resolvedTextStyle,
            cursorBrush = SolidColor(colors.accent.text),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            visualTransformation = visualTransformation,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .heightIn(min = minHeight ?: FIELD_HEIGHT)
                        .clip(FIELD_SHAPE)
                        .background(colors.surface.raised.copy(alpha = FIELD_FILL_ALPHA))
                        .border(LiuliGlassSpec.hairlineWidth, hairline, FIELD_SHAPE)
                        .border(1.dp, ringColor, FIELD_SHAPE)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.text.isEmpty() && placeholder != null) {
                            Text(placeholder, style = resolvedTextStyle, color = onGlass.secondary)
                        }
                        innerTextField()
                    }
                }
            },
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = AppTypography.caption,
                color = supportColor,
                modifier = Modifier.padding(start = 4.dp, top = 5.dp),
            )
        }
    }
}
