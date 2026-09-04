package com.situ.aichat.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * Fable-5 只读下拉框（设计语言 §3·2026-06-19 过审·输入框重构 §7）。
 *
 * 替代裸 M3 `OutlinedTextField`（readOnly）下拉锚的硬描边观感：暖色软填充（[AppColors.surface] sunken）
 * + 16dp medium 圆角 + 框上方静态 label（范式 A·与 [AppTextField] 同源）+ 右侧陶土 ▼（展开旋转 180°）
 * + 展开时陶土玫细环（方案 A）。**系统下拉交互不重写**——仍用 [ExposedDropdownMenuBox] + `.menuAnchor()`
 * 保证弹出/键盘/无障碍语义，只把锚点视觉替掉（设计语言 §5「foundation 自绘视觉件」）。
 *
 * 弹出菜单经 [MaterialTheme] 局部覆写暖化：surface→`surface.raised`（暖白纸）+ extraSmall 形→16dp 圆角
 * （M3 1.3.0 `ExposedDropdownMenu` 无 `containerColor`/`shape` 形参，故走 token 覆写）。菜单项用
 * [AppDropdownMenuItem]（选中项陶土 tint 底 + 勾）。
 *
 * 菜单项由 [menuContent] 提供（各调用方保留自己的选项映射逻辑·一般填若干 [AppDropdownMenuItem]）。
 * [value] = 当前选中项的展示文本；[expanded]/[onExpandedChange] 由调用方持有（可在展开时拉取数据）。
 *
 * **可输入 + 筛选**的下拉（如 TTS 音色目录·边打字边拉取）不走本件——那是半输入半下拉，用 [AppTextField]
 * 打底 + 尾部 ▼/loading 单独处理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDropdownField(
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    menuContent: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()
    val ringColor by animateColorAsState(
        targetValue = if (expanded) colors.accent.primary else Color.Transparent,
        animationSpec = if (reduceMotion) snap<Color>() else AppMotion.effectMediumSpring<Color>(),
        label = "appDropdownRing",
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduceMotion) snap<Float>() else AppMotion.effectMediumSpring<Float>(),
        label = "appDropdownChevron",
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        Column {
            if (label != null) {
                Text(
                    text = label,
                    style = AppTypography.secondary,
                    color = colors.text.secondary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
            }
            Row(
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(AppShapes.medium)
                    .background(colors.surface.sunken)
                    .border(width = 2.dp, color = ringColor, shape = AppShapes.medium)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = AppTypography.body, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(value, style = AppTypography.body, color = colors.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.accent.text,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
                )
            }
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = AppTypography.caption,
                    color = colors.text.secondary,
                    modifier = Modifier.padding(start = 4.dp, top = 5.dp),
                )
            }
        }

        // 暖色软填充菜单：M3 1.3.0 ExposedDropdownMenu 无 containerColor/shape 形参 → 局部 MaterialTheme 覆写
        // surface（菜单 Surface 底色）+ extraSmall 形（菜单容器圆角 token）。onSurface 落 text.primary 保默认项色。
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                surface = colors.surface.raised,
                surfaceContainer = colors.surface.raised,
                onSurface = colors.text.primary,
            ),
            shapes = MaterialTheme.shapes.copy(extraSmall = AppShapes.medium),
        ) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                content = menuContent,
            )
        }
    }
}

/**
 * [AppDropdownField] 的菜单项：思源黑体正文·选中项陶土 tint 底 + 陶土勾（[AppColors.accent] text）。
 * 在 [AppDropdownField] 的 `menuContent` lambda 内调用。
 */
@Composable
fun AppDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = AppTheme.colors
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = AppTypography.body,
                color = if (selected) colors.accent.text else colors.text.primary,
            )
        },
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent.text) }
        } else {
            null
        },
        onClick = onClick,
        modifier = if (selected) modifier.background(colors.accent.primary.copy(alpha = 0.14f)) else modifier,
    )
}

/**
 * [AppDropdownField] 的**可输入**变体（半输入半下拉）：用户可打字（自定义值 / 筛选），也可从 [menuContent]
 * 选；尾部 ▼ 或 [loading] 转圈。给「模型名（可手填可拉取列表）」「TTS 音色目录（边打字边筛选）」等用。
 *
 * 软填充编辑锚（[AppTextField] 同款视觉·去硬描边）+ `.menuAnchor(PrimaryEditable)`（系统可编辑下拉交互）
 * + 暖色软填充菜单（与 [AppDropdownField] 同源·[MaterialTheme] 局部覆写）。聚焦/展开时陶土玫细环。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDropdownTextField(
    value: String,
    onValueChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    loading: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    menuContent: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val reduceMotion = rememberReduceMotion()
    val ringColor by animateColorAsState(
        targetValue = if (isFocused || expanded) colors.accent.primary else Color.Transparent,
        animationSpec = if (reduceMotion) snap<Color>() else AppMotion.effectMediumSpring<Color>(),
        label = "appDropdownTfRing",
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduceMotion) snap<Float>() else AppMotion.effectMediumSpring<Float>(),
        label = "appDropdownTfChevron",
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        Column {
            if (label != null) {
                Text(
                    text = label,
                    style = AppTypography.secondary,
                    color = colors.text.secondary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .fillMaxWidth(),
                singleLine = true,
                textStyle = AppTypography.body.copy(color = colors.text.primary),
                cursorBrush = SolidColor(colors.accent.primary),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
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
                        Box(Modifier.weight(1f)) {
                            if (value.isEmpty() && placeholder != null) {
                                Text(placeholder, style = AppTypography.body, color = colors.text.secondary)
                            }
                            innerTextField()
                        }
                        Spacer(Modifier.width(8.dp))
                        if (loading) {
                            AppLoadingRing(size = AppLoadingRingSize.Small)
                        } else {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = colors.accent.text,
                                modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
                            )
                        }
                    }
                },
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = AppTypography.caption,
                    color = colors.text.secondary,
                    modifier = Modifier.padding(start = 4.dp, top = 5.dp),
                )
            }
        }

        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                surface = colors.surface.raised,
                surfaceContainer = colors.surface.raised,
                onSurface = colors.text.primary,
            ),
            shapes = MaterialTheme.shapes.copy(extraSmall = AppShapes.medium),
        ) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                content = menuContent,
            )
        }
    }
}
