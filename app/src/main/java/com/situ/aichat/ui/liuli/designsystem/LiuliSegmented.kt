package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.glass.LiuliGlassStyle
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.liuliTouchHeight
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import kotlin.math.roundToInt

/** 分段条两态（契约 §6.5「分段行」/「分段 sticky」）。 */
enum class LiuliSegmentedStyle {
    /** 纸面态：36 高 · 轨 `surface.sunken` · 选中段 `surface.raised` + 0.5 发丝 + 1 影。 */
    Paper,

    /** 玻璃态（T3 收起后住进顶栏）：40 高 · 轨透明（骑在同一片玻璃上）· 选中段 Button 档玻璃。 */
    Glass,
}

/** 轨内边（选中段与轨之间的呼吸·对版稿 `.seg{padding:3px}` / `.segbar{padding:4px}`）。 */
private val PAPER_INSET = 3.dp
private val GLASS_INSET = 4.dp
/** 段字 14/500（对版稿 `.seg span`）。 */
private val LABEL_SIZE = 14.sp
/** 纸面态选中片的影（对版稿 `0 1px 2px`）。 */
private val PAPER_THUMB_SHADOW = 1.dp

/**
 * 琉璃分段控件（图纸 2026-09-06 卷四 §2.1 · 契约 §6.5）。**禁 M3 `SegmentedButton`**（§9 ⑤）。
 *
 * 选中片是一枚随选中段**滑动**的独立层（[AppMotion].calmSpring；[rememberReduceMotion] 时 `snap()` 直切），
 * 不是「每段各画各的底」——滑动感是这枚控件的全部长相。
 *
 * a11y：整条 `selectableGroup`，每段 `selectable(role = [role])`；切段 `haptics.selection()`。
 */
@Composable
fun <T> LiuliSegmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    style: LiuliSegmentedStyle = LiuliSegmentedStyle.Paper,
    role: Role = Role.RadioButton,
    enabled: Boolean = true,
) {
    if (options.isEmpty()) return
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val paper = style == LiuliSegmentedStyle.Paper
    val inset = if (paper) PAPER_INSET else GLASS_INSET
    val height = if (paper) LiuliPageGeometry.stripPaper else LiuliPageGeometry.stripGlass

    val index = options.indexOf(selected).coerceAtLeast(0)
    val indexAnim by animateFloatAsState(
        targetValue = index.toFloat(),
        animationSpec = if (reduceMotion) snap() else AppMotion.calmSpring(),
        label = "liuliSegmentedThumb",
    )
    var trackPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { trackPx = it.width },
    ) {
        val insetPx = with(density) { inset.roundToPx() }
        val cellPx = ((trackPx - insetPx * 2) / options.size).coerceAtLeast(0)
        val cellDp = with(density) { cellPx.toDp() }
        // 轨底 + 滑动片：**这一层才裁**（滑片用 calmSpring，越界那一帧要被 pill 收住）。
        Box(
            Modifier
                .matchParentSize()
                .clip(LiuliShapes.pill)
                .then(if (paper) Modifier.background(colors.surface.sunken) else Modifier)
                .padding(inset),
        ) {
            Box(
                Modifier
                    .offset { IntOffset((cellPx * indexAnim).roundToInt(), 0) }
                    .width(cellDp)
                    .fillMaxHeight()
                    .then(
                        if (paper) {
                            Modifier
                                .shadow(PAPER_THUMB_SHADOW, LiuliShapes.pill)
                                .clip(LiuliShapes.pill)
                                .background(colors.surface.raised)
                                .border(0.5.dp, colors.surface.stroke, LiuliShapes.pill)
                        } else {
                            Modifier.liuliGlass(LiuliShapes.pill, dark = dark, style = LiuliGlassStyle.Button)
                        },
                    ),
            )
        }
        // 段（点击面）：**不裁**——[liuliTouchHeight] 把触达撑到 48 靠的是上下外溢，裁一刀就只剩轨高
        // （36 − 内边×2 = 30 < 48·破 a11y 红线）。外溢落在分段行的 12dp 内距里，不与别的点击面相争。
        Row(Modifier.matchParentSize().padding(inset).selectableGroup()) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .liuliTouchHeight()
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = role,
                            interactionSource = null,
                            indication = null,
                            onClick = {
                                if (!isSelected) {
                                    haptics.selection()
                                    onSelect(option)
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        style = AppTypography.label.copy(fontSize = LABEL_SIZE, fontWeight = FontWeight.W500),
                        color = when {
                            paper && isSelected -> colors.text.primary
                            paper -> colors.text.secondary
                            isSelected -> LiuliTheme.onGlass.primary
                            else -> LiuliTheme.onGlass.secondary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
