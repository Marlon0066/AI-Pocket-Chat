package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.liuli.page.liuliFootprint

/** 视觉直径 22（触达 48 由 [liuliFootprint] 居中外溢·只在本件自带点击时挂）。 */
private val DIAMETER = 22.dp
/** 未选环 1.5 `text.tertiary`；选中环 2 `accent.primary` + 10 实心点。 */
private val RING_OFF = 1.5.dp
private val RING_ON = 2.dp
private val DOT = 10.dp
/** 禁用态透明度（与 [LiuliSwitch] / [LiuliButton] 同值·结构恒定不提前 return）。 */
private const val DISABLED_ALPHA = 0.38f

/**
 * 琉璃单选圆（图纸 2026-09-06 卷四 §2.1 · 契约 §6.5「单选行」）。**禁 M3 `RadioButton`**（§9 ⑤）。
 *
 * 自绘：未选 = 1.5dp `text.tertiary` 环；选中 = 2dp `accent.primary` 环 + 10dp 同色实心点。
 * 版位恒 22×22、触达 48 由 `requiredSize` 居中外溢（PITFALLS §1d：`minimumInteractiveComponentSize`
 * 会把版位一起撑大，行内对齐就散了）。切换 `haptics.selection()`。
 *
 * **整行可点时调用方把 `selectable` 挂在行上、本件传 `onClick = null`**——两层都可点会双触发，
 * 且读屏会念出两个可选中节点。
 */
@Composable
fun LiuliRadio(
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val ringOn = colors.accent.primary
    val ringOff = colors.text.tertiary

    Box(
        modifier = modifier
            .then(if (onClick != null) Modifier.liuliFootprint(DIAMETER) else Modifier.size(DIAMETER))
            .then(
                if (onClick != null) {
                    Modifier.selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = {
                            if (!selected) {
                                haptics.selection()
                                onClick()
                            }
                        },
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .size(DIAMETER)
                .drawBehind {
                    val stroke = (if (selected) RING_ON else RING_OFF).toPx()
                    drawCircle(
                        color = if (selected) ringOn else ringOff,
                        radius = (size.minDimension - stroke) / 2f,
                        style = Stroke(width = stroke),
                    )
                    if (selected) drawCircle(color = ringOn, radius = DOT.toPx() / 2f)
                },
        )
    }
}
