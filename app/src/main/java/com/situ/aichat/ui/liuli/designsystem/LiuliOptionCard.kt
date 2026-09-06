package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

/** 卡最小高（触达 ≥ 48 由此保）· 内距 · swatch 直径。 */
private val CARD_MIN_HEIGHT = 64.dp
private val CARD_PAD = 12.dp
private val SWATCH = 28.dp
/** 选中描边 1.5 `accent.primary`；未选 0.5 发丝。 */
private val BORDER_ON = 1.5.dp
private val BORDER_OFF = 0.5.dp
private val TITLE_SIZE = 15.sp
private val SUB_SIZE = 13.sp

/**
 * 外观页的选项卡（图纸 2026-09-06 卷四 A-6）：双色 swatch 圆 + 标签 / 副标；
 * 选中 = 1.5dp `accent.primary` 描边 + `accent.container` 底，未选 = `surface.raised` + 0.5 发丝。
 *
 * 触达 ≥ 48 由 [CARD_MIN_HEIGHT] 保（卡本身就比 48 高，无需外溢框）；`selectable(role = RadioButton)`，
 * 互斥由调用方的 `selectableGroup()` 管；切换 `haptics.selection()`。
 */
@Composable
fun LiuliOptionCard(
    selected: Boolean,
    onSelect: () -> Unit,
    title: String,
    swatchStart: Color,
    swatchEnd: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val shape = LiuliShapes.small
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CARD_MIN_HEIGHT)
            .clip(shape)
            .background(if (selected) colors.accent.container else colors.surface.raised)
            .border(
                width = if (selected) BORDER_ON else BORDER_OFF,
                color = if (selected) colors.accent.primary else colors.surface.stroke,
                shape = shape,
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = {
                    if (!selected) {
                        haptics.selection()
                        onSelect()
                    }
                },
            )
            .padding(CARD_PAD),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 双色 swatch：左半 = 页底、右半 = 主强调（一眼看出这张脸长什么样）。
        Box(
            Modifier
                .size(SWATCH)
                .clip(CircleShape)
                .drawBehind {
                    drawRect(swatchStart, size = size.copy(width = size.width / 2f))
                    drawRect(swatchEnd, topLeft = Offset(size.width / 2f, 0f), size = size.copy(width = size.width / 2f))
                }
                .border(0.5.dp, colors.surface.stroke, CircleShape),
        )
        Text(
            title,
            style = AppTypography.label.copy(fontSize = TITLE_SIZE, fontWeight = FontWeight.W600),
            color = if (selected) colors.accent.onContainer else colors.text.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = AppTypography.secondary.copy(fontSize = SUB_SIZE),
                color = if (selected) colors.accent.onContainer else colors.text.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
