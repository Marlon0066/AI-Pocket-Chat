package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.glass.LiuliGlassStyle
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃按钮三档（契约 FABLE5_THEME_LIULI_PROPOSAL.md §4.1 · 图纸 §4.3）。与暖陶 `AppButtonStyle` 同形，
 * 卷二搬 sheet 内容时可平移替换。
 * - [Glass]：玻璃片本身当面（模糊 + 染色 + 迎光 + 发丝 + 影）——琉璃的默认钮。
 * - [Prominent]：钴蓝对角渐变实底 + 顶沿硬亮线 + 一道彩影——主行动 CTA。
 * - [Text]：透明 + 钴蓝字——三级行动。
 */
enum class LiuliButtonStyle { Glass, Prominent, Text }

/** Prominent 档顶沿的 1px 迎光硬线（白 35%·契约 §4.1）。 */
private const val PROMINENT_SPECULAR_ALPHA = 0.35f

/** Prominent 档彩影：4dp、影色 = 渐变终点 35%（契约 §4.1）。 */
private val PROMINENT_SHADOW_ELEVATION = 4.dp
private const val PROMINENT_SHADOW_ALPHA = 0.35f

/**
 * 禁用态透明度（结构恒定·绝不 `if (!enabled) return`·REDLINES §7）。
 *
 * ⚠️ 承重排序：`Modifier.alpha` 只淡化它**之后**（内层）画的东西，所以它必须排在「画底」的那一段
 * （Prominent 的渐变实底 / Glass 的 [liuliGlass] / 圆钮的圆玻璃）**之前**。排到底之后 = 底满色、只有字发灰，
 * 用户看不出钮按不动（卷五 C5 装机取证 `liuli_v5/07_api_config.png` 就是这么翻的车）。
 * 回归钉在 `LiuliButtonTest.禁用态三档底色都必须被淡化`。
 */
private const val DISABLED_ALPHA = 0.38f

/**
 * 琉璃按钮。内容槽 [content] 与暖陶 `AppButton` 同形（同参数名、同 [RowScope] 内容槽），文字色与
 * [AppTypography].label 经 [LocalContentColor]/[LocalTextStyle] 注入，调用方直接写 `Text(...)` 即自动取色取样式。
 *
 * 视觉高 40dp（[defaultMinSize]）、触达 48dp（[minimumInteractiveComponentSize]）；按压走
 * [Modifier.liuliPressable]（缩 0.96 + Glass/Prominent 叠亮）+ 官方品牌 ripple（[LocalIndication]）+
 * `haptics.light()`。[enabled]=false 时降透明且不可点，**结构恒定**（禁用态提前 return 是动画杀手）。
 * [danger]=true 时 Text 档内容走 error 色（与暖陶同语义）。
 */
@Composable
fun LiuliButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: LiuliButtonStyle = LiuliButtonStyle.Glass,
    enabled: Boolean = true,
    danger: Boolean = false,
    contentPadding: PaddingValues? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val haptics = LocalAppHaptics.current
    val interaction = remember { MutableInteractionSource() }
    val shape = LiuliShapes.pill

    // R1 🔵-1：danger 只染 Glass / Text 两档的字（红字 on 玻璃 / on 表面）；Prominent 是钴蓝实底，红字压蓝底
    // 既不达对比也不是任何已过审长相，故忽略 danger 保持白字。
    val contentColor: Color = when {
        style == LiuliButtonStyle.Prominent -> Palette.White
        danger -> colors.status.onError
        style == LiuliButtonStyle.Text -> colors.accent.text
        else -> LiuliTheme.onGlass.primary
    }
    val resolvedPadding = contentPadding ?: PaddingValues(
        horizontal = if (style == LiuliButtonStyle.Text) 12.dp else 18.dp,
    )
    val gradientStart = colors.accent.gradientStart
    val gradientEnd = colors.accent.gradientEnd

    CompositionLocalProvider(
        LocalContentColor provides contentColor,
        LocalTextStyle provides AppTypography.label.copy(color = contentColor),
    ) {
        Row(
            modifier = modifier
                .liuliPressable(
                    interactionSource = interaction,
                    enabled = enabled,
                    brighten = style != LiuliButtonStyle.Text,
                )
                .clickable(
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = {
                        haptics.light()
                        onClick()
                    },
                )
                .minimumInteractiveComponentSize()
                // alpha 必须在「画底」之前：它只作用于内层绘制（见 [DISABLED_ALPHA] 承重排序注释）。
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .then(
                    when (style) {
                        LiuliButtonStyle.Glass -> Modifier.liuliGlass(shape, dark = dark, style = LiuliGlassStyle.Button)
                        LiuliButtonStyle.Prominent -> Modifier
                            .shadow(
                                elevation = PROMINENT_SHADOW_ELEVATION,
                                shape = shape,
                                clip = false,
                                ambientColor = gradientEnd.copy(alpha = PROMINENT_SHADOW_ALPHA),
                                spotColor = gradientEnd.copy(alpha = PROMINENT_SHADOW_ALPHA),
                            )
                            .clip(shape)
                            .drawWithCache {
                                // 135° 对角：起点左上、终点右下（按实测 size 建一次，随尺寸变化才重建）。
                                val brush = Brush.linearGradient(
                                    colors = listOf(gradientStart, gradientEnd),
                                    start = Offset.Zero,
                                    end = Offset(size.width, size.height),
                                )
                                onDrawBehind {
                                    drawRect(brush)
                                    // 顶沿迎光：1px 硬线（形状之外已被 clip 裁掉）。
                                    drawRect(
                                        color = Color.White.copy(alpha = PROMINENT_SPECULAR_ALPHA),
                                        size = size.copy(height = 1f),
                                    )
                                }
                            }
                        LiuliButtonStyle.Text -> Modifier.clip(shape)
                    },
                )
                .defaultMinSize(minHeight = 40.dp)
                .padding(resolvedPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * 琉璃圆钮（顶栏 / 输入排的图标钮）：一片圆玻璃 + 居中内容。[size] 为视觉直径（默认 40dp），
 * 触达由 [minimumInteractiveComponentSize] 保 48dp。[contentDescription] 同时进 `onClickLabel` 与 semantics。
 *
 * 长相 = 用户 2026-09-06 拍板「圆钮甲·iOS 26 淡玻璃钮」：玻璃走 [LiuliGlassStyle.Button]（2dp 小影 + 顶半区透镜 +
 * 内白 rim），图标 / 内容用 `accent.text` 钴蓝（原 `onGlass.primary` 墨色太重）。
 */
@Composable
fun LiuliCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val haptics = LocalAppHaptics.current
    val interaction = remember { MutableInteractionSource() }

    CompositionLocalProvider(LocalContentColor provides AppTheme.colors.accent.text) {
        Box(
            modifier = modifier
                .liuliPressable(interactionSource = interaction, enabled = enabled, brighten = true)
                .minimumInteractiveComponentSize()
                .size(size)
                // 同上：alpha 排在圆玻璃之前，否则禁用态只有图标发灰、玻璃片仍满色。
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .liuliGlass(CircleShape, dark = dark, style = LiuliGlassStyle.Button)
                .clickable(
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClickLabel = contentDescription,
                    onClick = {
                        haptics.light()
                        onClick()
                    },
                )
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
            content = { content() },
        )
    }
}
