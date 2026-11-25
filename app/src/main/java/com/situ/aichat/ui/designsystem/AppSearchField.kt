package com.situ.aichat.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * Fable-5 搜索输入框（设计语言 §3 形状 + §1 软填充·2026-06-19 过审）。
 *
 * 替代裸 M3 `OutlinedTextField` 的「硬描边」观感：暖色软填充（[AppColors.surface] sunken）+ 胶囊
 * （[AppShapes.full]）+ 聚焦时陶土玫细环（[AppColors.accent] primary·方案 A 拍板）+ 思源黑体正文，
 * 去掉一切冷灰描边——读作「嵌进页面的搜索井」而非贴上去的表单格子。
 *
 * 底座 = foundation [BasicTextField]：IME / 光标 / 选择 / 语义全由它保证，本件只重定义视觉
 * （[FABLE5_DESIGN_LANGUAGE.md] §5「foundation 自绘视觉件」路子），不碰 a11y/手势。聚焦环颜色走
 * 效果轴（ζ1.0 永不过冲）淡入淡出，[rememberReduceMotion] 时瞬时落位。清除键走 [AppIconButton]
 * 保 48dp 触达；[clearContentDescription] 给屏读。
 */
@Composable
fun AppSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    clearContentDescription: String? = null,
) {
    val colors = AppTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val reduceMotion = rememberReduceMotion()
    val ringColor by animateColorAsState(
        targetValue = if (isFocused) colors.accent.primary else Color.Transparent,
        animationSpec = if (reduceMotion) snap<Color>() else AppMotion.effectMediumSpring<Color>(),
        label = "appSearchFieldRing",
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = AppTypography.body.copy(color = colors.text.primary),
        cursorBrush = SolidColor(colors.accent.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .heightIn(min = 52.dp)
                    .clip(AppShapes.full)
                    .background(colors.surface.sunken)
                    .border(width = 2.dp, color = ringColor, shape = AppShapes.full)
                    .padding(start = 16.dp, end = if (value.isEmpty()) 16.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = AppActionIcons.Search,
                    contentDescription = null,
                    tint = colors.accent.text,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = AppTypography.body,
                            color = colors.text.secondary,
                        )
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    AppIconButton(
                        icon = AppActionIcons.Close,
                        contentDescription = clearContentDescription,
                        onClick = { onValueChange("") },
                        tint = colors.text.secondary,
                        iconSize = 18.dp,
                    )
                }
            }
        },
    )
}
