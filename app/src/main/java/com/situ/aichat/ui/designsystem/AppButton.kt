package com.situ.aichat.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * Fable-5 按钮三档（按钮族重构 2026-06-19·D1 柔陶软填充用户过审）。
 * - [Primary]：**釉烧陶土**（[Modifier.glazedClay]·2026-09-05 用户过审）+ [AppColors.text] onAccent 压印字
 *   ——主行动 CTA。**取代**旧「一道 135° 线性渐变」：面色仍源自 [AppColors.accent] gradientStart→gradientEnd
 *   （与用户气泡同源），但多了顶沿釉光 / 底沿积釉 / 派生底段 / 颗粒 / 两层外影。
 * - [Tonal]：极浅陶土软填充（[AppColors.accent] container）+ onContainer 字——次要行动（**替裸 M3 `OutlinedButton`
 *   的空心描边**·与 [AppChoiceChip]/[AppSegmentedControl] 选中态同源）。
 * - [Text]：透明 + [AppColors.accent] text 陶土字——三级行动。
 * - [Warning]：深琥珀实底（[AppColors.status] warningSolid）+ onWarningSolid 暖白字——破坏性确认 CTA（如删除日程·
 *   设计语言 §1.3 琥珀=「不可撤销」警示·≠血红 error）·深浅双档暖白字均 ≥4.5:1（[ColorContrastTest] 看门）。
 */
enum class AppButtonStyle { Primary, Tonal, Text, Warning }

/**
 * Fable-5 按钮（设计语言 §5「视觉定义组件用 foundation 重写」）。内容槽 [content]（与 M3 `Button`/`OutlinedButton`
 * 同形·支持文字 / 图标 / 加载圈等任意内容），文字色与 14sp/Medium 文本样式经 [LocalContentColor]/[LocalTextStyle]
 * 注入，调用方直接写 `Text(...)` 即自动取色取样式。
 *
 * 视觉随 [style]（见 [AppButtonStyle]）·[AppShapes.full] 胶囊·**无冷灰描边**。点按 [AppMotion.calmSpring] 缩放
 * 0.97（[rememberReduceMotion] 门控）+ 官方品牌 ripple（[LocalIndication]）；[enabled]=false 时降透明且不可点。
 * Primary 档另叠 1dp 下沉与釉的按下态（影撤掉 + 内陷顶沿），二者同源于同一个 `pressed`，**不另立动画规格**。
 * **胶囊裁剪分两路**：Primary 由 [Modifier.glazedClay] 链尾自带，其余三档走 `.clip().background()` 老路。
 *
 * 高度 = M3 按钮等高（视觉 ~40dp·[minimumInteractiveComponentSize] 保 48dp 触达）——迁移期与尚未换装的 M3
 * 主钮**同排不错位**。[contentPadding] 可覆写各档默认内边距（默认 Text 横 12dp / 其余 20dp·竖 8dp；齐左紧凑
 * 文字链可传 `PaddingValues(vertical = 4.dp)`）。a11y：`Role.Button` + 触达 48dp。[danger]=true 时内容走 error 色（[AppColors.status]
 * onError·破坏性动作如删除钮·Text 档用·与原 M3 `colorScheme.error` 文字钮同色同语义）。
 */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AppButtonStyle = AppButtonStyle.Primary,
    enabled: Boolean = true,
    danger: Boolean = false,
    contentPadding: PaddingValues? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduceMotion) 0.97f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "appButtonPress",
    )
    val background: Brush = when (style) {
        // Primary 的面由 Modifier.glazedClay 自绘（三段渐变 + 两道沿 + 影 + 颗粒），不再走 .background。
        AppButtonStyle.Primary -> SolidColor(Color.Transparent)
        AppButtonStyle.Tonal -> SolidColor(colors.accent.container)
        AppButtonStyle.Warning -> SolidColor(colors.status.warningSolid)
        AppButtonStyle.Text -> SolidColor(Color.Transparent)
    }
    val contentColor: Color = when {
        danger -> colors.status.onError
        style == AppButtonStyle.Primary -> colors.text.onAccent
        style == AppButtonStyle.Warning -> colors.status.onWarningSolid
        style == AppButtonStyle.Tonal -> colors.accent.onContainer
        else -> colors.accent.text
    }
    val resolvedPadding = contentPadding ?: PaddingValues(
        horizontal = if (style == AppButtonStyle.Text) 12.dp else 20.dp,
        vertical = 8.dp,
    )

    // 压印字（仅 Primary）：字下 1dp 一道硬边亮线（blurRadius = 0f），像戳进釉里。不动 AppTypography.label
    // 本体——它还服务 Tonal / Text / Warning 三档。
    val embossShadow = if (style == AppButtonStyle.Primary) {
        Shadow(
            color = (if (colors.isDark) Color.Black else Color.White).copy(
                alpha = if (colors.isDark) {
                    AppPorcelain.GLAZE_EMBOSS_ALPHA_DARK
                } else {
                    AppPorcelain.GLAZE_EMBOSS_ALPHA_LIGHT
                },
            ),
            offset = Offset(0f, with(density) { AppPorcelain.glazeEmbossOffsetY.toPx() }),
            blurRadius = 0f,
        )
    } else {
        null
    }

    CompositionLocalProvider(
        LocalContentColor provides contentColor,
        LocalTextStyle provides AppTypography.label.copy(color = contentColor, shadow = embossShadow),
    ) {
        Row(
            modifier = modifier
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .clickable(
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    // 触觉判据只看 style（图纸 §3.B H1 锁定）。注意真码另有正交的 danger 布尔（:66）——
                    // 它走 error 字色文字钮，图纸未覆盖其触觉，故此处**不**并入判据（见 §11 D-3 待裁）。
                    onClick = {
                        if (style == AppButtonStyle.Warning) haptics.medium() else haptics.light()
                        onClick()
                    },
                )
                .minimumInteractiveComponentSize()
                .then(
                    if (style == AppButtonStyle.Primary) {
                        // 釉烧面自带链尾 clip（ripple 仍被裁成胶囊）；按下再沉 1dp，与 0.97 缩放同源既有 pressed。
                        Modifier
                            .graphicsLayer {
                                translationY = if (pressed && enabled && !reduceMotion) {
                                    AppPorcelain.glazePressOffsetY.toPx()
                                } else {
                                    0f
                                }
                            }
                            .glazedClay(pressed = pressed && enabled, enabled = enabled)
                    } else {
                        Modifier.clip(AppShapes.full).background(background)
                    },
                )
                .alpha(if (enabled) 1f else 0.4f)
                .heightIn(min = 40.dp)
                .padding(resolvedPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
