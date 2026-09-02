package com.situ.aichat.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import kotlin.math.roundToInt

private val TRACK_HEIGHT = 48.dp
private val THUMB_INSET = 4.dp

/**
 * Fable-5 分段控件 = 凹槽 + 滑动**白瓷药丸**（按钮族重构 2026-06-19 立件；材质升级「白瓷药丸」2026-09-03 过审）。
 *
 * 替代裸 M3 `SingleChoiceSegmentedButtonRow` + `SegmentedButton` 的「硬描边 + 分隔线 + 选中滑入对勾」骨架：
 * 凹槽轨（[porcelainTrack]·sunken 底 + 顶沿内阴影）+ 选中段一枚白瓷药丸（[porcelainThumb]·釉面渐变 +
 * 陶土暖边 + 软影 + 深档月光沿·随选中索引 [AppMotion.calmSpring] 横向滑行）+ 选中标签陶土
 * （[AppColors.accent] onContainer·Medium 字重）、未选 [AppColors.text] secondary。**无对勾、无分隔线**。
 *
 * **材质升级前后**（勿回退）：旧版药丸 = `accent.container` 平填充（`#F0DDD3` 与槽 `#F1ECE4` 亮度几乎相同
 * → 观感是「槽里一块淡印子」）；新版靠**光**表达浮起，材质细节与三处越出 v2 的登记见 [AppPorcelain]。
 *
 * 等宽 N 段（≥2·药丸宽 = 轨宽/段数）→ 药丸位置确定，无测量竞态。触感 [LocalAppHaptics] selection（EFFECT_TICK）；
 * 全 [rememberReduceMotion] 门控（关动画时滑动/缩放/拉长瞬时落位·色彩走效果轴永不过冲）。
 *
 * **两处手感**（对版稿·[AppPorcelain] 存值）：① 滑行时药丸沿运动方向拉长 6.5% / 压扁 3.5%（keyframes
 * 0→峰 137ms→0 共 360ms·像一滴水滑过去）；② 按住任意段 → 药丸下沉 [AppPorcelain.PRESS_SCALE] 且软影收掉
 * （**取代**旧「整格文字缩 0.96」·文字改轻缩 [AppPorcelain.PRESS_LABEL_SCALE] 退让给药丸）。
 *
 * a11y：[selectableGroup] + 每段 `Role.Tab` + `selected` 语义（TalkBack 读「<label>·标签页·已选中」）；
 * 段高 48dp = 最小触达。[label] 为 `@Composable` 故调用方可用 `stringResource`。
 *
 * **测量**：用自绘 [Layout] 而非 `BoxWithConstraints` 拿轨宽——后者是 `SubcomposeLayout`，被放进
 * 任何用 `IntrinsicSize`（典型 = M3 `DropdownMenu` 内容列以 `width(IntrinsicSize.Max)` 对齐宽度）测量的
 * 容器时会抛 `IllegalStateException` 而闪退；自绘 Layout 支持内禀测量（宽度无界时回退到标签行的自然宽），
 * 故本件可安全放进下拉菜单 / 弹窗。药丸用 placement 相位定位（读 [animIndex] 只重摆位不重测量·60fps 平滑）。
 *
 * **边界**：段数过多撑不下的场景（如贴纸多分类）不适用本等宽件，调用方另裁（可滚动 tab 或保留 M3）。
 */
@Composable
fun <T> AppSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (T) -> String,
) {
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalAppHaptics.current
    val segments = options.size.coerceAtLeast(1)
    val selectedIndex = options.indexOfFirst { it == selected }.coerceAtLeast(0)
    val animIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (reduceMotion) snap() else AppMotion.calmSpring(),
        label = "segmentSlide",
    )
    // 按住任意段 → 药丸下沉（单指故至多一段按住；-1 = 无按压）。
    var pressedIndex by remember { mutableIntStateOf(-1) }
    // 滑行拉长：选中索引真的变了才跑（首次组合 lastIndex 已等于 selectedIndex → 不触发入场抽搐）。
    val squash = remember { Animatable(0f) }
    var lastIndex by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex != lastIndex) {
            lastIndex = selectedIndex
            if (!reduceMotion) {
                squash.snapTo(0f)
                squash.animateTo(
                    targetValue = 0f,
                    // 两段各挂 EaseOutQuint（对版稿 cubic-bezier(.22,.9,.3,1) 的项目同族 token·
                    // (0.23,1,0.32,1) 形状实质相同，复用既有曲线不新增近邻）。**不写 easing 会落到
                    // Compose 默认的 LinearEasing → 对称三角波**，水滴感变机械拉扯（PITFALLS §1d 红线）。
                    animationSpec = keyframes {
                        durationMillis = AppPorcelain.SQUASH_DURATION_MS
                        0f at 0 using AppMotion.EaseOutQuint
                        1f at AppPorcelain.SQUASH_PEAK_MS using AppMotion.EaseOutQuint
                        0f at AppPorcelain.SQUASH_DURATION_MS
                    },
                )
            }
        }
    }
    val thumbPressScale by animateFloatAsState(
        targetValue = if (pressedIndex >= 0 && !reduceMotion) AppPorcelain.PRESS_SCALE else 1f,
        animationSpec = if (reduceMotion) snap() else AppMotion.calmSpring(),
        label = "segmentThumbPress",
    )
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .alpha(if (enabled) 1f else 0.45f)
            .porcelainTrack(),
        content = {
            // 滑动药丸（measurables[0]·先摆=绘于标签后面）。尺寸由父测量按段宽下发，不带 size 修饰符。
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        val s = squash.value
                        scaleX = (1f + AppPorcelain.SQUASH_X * s) * thumbPressScale
                        scaleY = (1f - AppPorcelain.SQUASH_Y * s) * thumbPressScale
                    }
                    .porcelainThumb(pressed = pressedIndex >= 0, raised = enabled),
            )
            // 标签行（measurables[1]）。
            // 裁剪下放到标签行：只裁过长文字，**不裁药丸的外扩软影**（[porcelainTrack] 已不裁子内容）。
            Row(
                modifier = Modifier
                    .clip(AppShapes.full)
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEachIndexed { index, option ->
                    SegmentTab(
                        text = label(option),
                        selected = option == selected,
                        enabled = enabled,
                        reduceMotion = reduceMotion,
                        onClick = { haptics.selection(); onSelect(option) },
                        onPressedChange = { isPressed ->
                            pressedIndex = nextPressedIndex(pressedIndex, index, isPressed)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val insetPx = THUMB_INSET.roundToPx()
        // 高度恒为轨高（.height(TRACK_HEIGHT)）；内禀高度查询时无界 → 回退轨高。
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else TRACK_HEIGHT.roundToPx()
        val thumbMeasurable = measurables[0]
        val rowMeasurable = measurables[1]
        // 内禀宽度查询（如 DropdownMenu 的 IntrinsicSize.Max）→ 宽度无界 → 回退到标签行自然宽，避免无穷约束。
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else rowMeasurable.maxIntrinsicWidth(height)

        val segWidth = if (segments > 0) width / segments else width
        val thumb = thumbMeasurable.measure(
            Constraints.fixed(
                width = (segWidth - insetPx * 2).coerceAtLeast(0),
                height = (height - insetPx * 2).coerceAtLeast(0),
            ),
        )
        val row = rowMeasurable.measure(Constraints.fixed(width, height))
        layout(width, height) {
            // 药丸先摆（绘于底层）→ 标签压上。药丸 x 用浮点段宽 * 动画索引求平滑滑行（place=绝对·对齐旧 offset{}）。
            val segWidthF = if (segments > 0) width.toFloat() / segments else width.toFloat()
            val thumbX = (segWidthF * animIndex).roundToInt() + insetPx
            thumb.place(x = thumbX, y = insetPx)
            row.place(x = 0, y = 0)
        }
    }
}

@Composable
private fun SegmentTab(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.accent.onContainer else colors.text.secondary,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "segmentColor",
    )
    val pressScale by animateFloatAsState(
        // 主反馈已交给药丸下沉，文字只轻缩一档退让（旧值 0.96）。
        targetValue = if (pressed && !reduceMotion) AppPorcelain.PRESS_LABEL_SCALE else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "segmentPress",
    )
    LaunchedEffect(pressed) { onPressedChange(pressed) }
    // 按住期间本段被移出组合（options 变短）时 LaunchedEffect 随之取消、`false` 永不到达 →
    // 药丸会永久停在下沉态。当前 19 站 options 全定长不可达，此处为公开组件封口。
    DisposableEffect(Unit) { onDispose { onPressedChange(false) } }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (selected) AppTypography.bodyEmphasis else AppTypography.body,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 按压索引状态转移（药丸是否下沉的唯一判据）。抽成纯函数便于 T1 直测——它承载两条易被"顺手简化"
 * 掉的语义：① 松开的若不是当前记录的那一段（多指乱序到达）则**保持不变**，不能写成 `else -1`；
 * ② 无按压恒为 -1。
 *
 * @param current 当前记录的按压段索引，-1 = 无按压
 * @param index 发来事件的段索引
 * @param isPressed 该段是按下(true)还是松开(false)
 */
internal fun nextPressedIndex(current: Int, index: Int, isPressed: Boolean): Int = when {
    isPressed -> index
    current == index -> -1
    else -> current
}
