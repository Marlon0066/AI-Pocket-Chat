package com.situ.aichat.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * Fable-5 选择标签（按钮族重构 2026-06-19 立件；材质升级「白瓷药丸」2026-09-03 过审·同批同源
 * [AppSegmentedControl]）。
 *
 * 替代裸 M3 `FilterChip` 的「方角描边 + 选中对勾前导图标」骨架：胶囊（[AppShapes.full]·**无对勾**）——
 * 未选 = [AppColors.surface] sunken 平填充 + [AppColors.text] secondary；选中 = **白瓷浮起**
 * （[porcelainThumb]·釉面渐变 + 陶土暖边 + 软影 + 深档月光沿）+ [AppColors.accent] onContainer 陶土字。
 * 一排 chip 里选中那枚**从凹面浮起来**，与分段控件「槽里一枚瓷片」是同一句话。
 *
 * **过渡做法**（性能取舍·见 [AppPorcelain] KDoc）：白瓷是独立一层，靠 `graphicsLayer` 的 alpha 在 sunken 底上
 * **淡入淡出**，而非把 alpha 插进 [porcelainThumb] 的绘制参数——后者会让 `BlurMaskFilter` 逐帧重建。
 *
 * 14sp/Medium 紧凑标签（chip 偏小·与 16sp 分段控件区分）；选中/未选同字号字重 → 无重排，纯靠底色+字色切换
 * （[AppMotion.effectMediumSpring] 效果轴永不过冲·[rememberReduceMotion] 时瞬时）。点按缩放 0.96 + 触感
 * [LocalAppHaptics] selection。[leading] 可空槽给带分类图标的 chip（如礼物分类·图标自动取 [contentColor] 染色）。
 *
 * a11y：[selectable] + `selected` 语义 + [role]（默认 [Role.RadioButton] 单选组——分类/格式/题材等互斥取一；
 * 可开可关的独立开关传 [Role.Checkbox]·TalkBack 读「单选/复选按钮，已选」与被替的 M3 `FilterChip` 等价）；
 * [minimumInteractiveComponentSize] 保 48dp 触达
 * （视觉胶囊约 36dp·触达区上下各补不可见 margin·与 M3 FilterChip 等价）。多用在 `horizontalScroll`/`LazyRow`/
 * `FlowRow` 选择行（关系标签 / 礼物分类 / 故事题材…单选组沿用调用方逻辑）。
 */
@Composable
fun AppChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 白瓷层的浮起程度（0 = 只见 sunken 凹面·1 = 白瓷完全盖上）。效果轴恒 ζ1.0 永不过冲。
    val raise by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "chipRaise",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.accent.onContainer else colors.text.secondary,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "chipContent",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.96f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "chipPress",
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .alpha(if (enabled) 1f else 0.45f)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = role,
                interactionSource = interaction,
                indication = null,
                onClick = { haptics.selection(); onClick() },
            )
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        // 视觉层：尺寸由内容行决定，两层背景 matchParentSize 跟随（**外层不 clip**——白瓷的软影要能溢出胶囊）。
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().background(colors.surface.sunken, AppShapes.full))
            // 未选且未在淡入中时整层短路——白瓷层含两枚 BlurMaskFilter，横滑 chip 行（礼物分类 / 红包吉利数）
            // 一屏十几枚时不做无谓录制。`raise` 本就因 contentColor 同帧重组被读，判断不引入新重组源。
            if (raise > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = raise }
                        .porcelainThumb(pressed = pressed && selected, raised = enabled),
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (leading != null) {
                    CompositionLocalProvider(LocalContentColor provides contentColor) {
                        leading()
                    }
                }
                Text(
                    text = label,
                    style = AppTypography.label,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
