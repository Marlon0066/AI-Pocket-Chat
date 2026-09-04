package com.situ.aichat.ui.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.LocalAppHaptics

/**
 * Fable-5 设置行 = **陶土瓦片 + 题 + 副 + 尾槽**（六件套草图 2026-07-17 过审·取代 M3 `ListItem`）。
 *
 * 骨架：`[30dp 瓦片] —12dp— [题/副 竖排 weight 1f] —12dp— [尾值] [chevron 或 trailing 槽]`，
 * 行内边距 18×10dp、最小高 56dp。瓦片 = 30dp 圆角 9dp、底 [AppColors.accent] primary 15%、图标 18dp。
 * 字阶三枚走 [AppTypography] 具名 token（`settingsRowTitle` / `settingsRowSubtitle` / `settingsRowValue`），
 * **组件内不写裸 sp**。
 *
 * **[trailing] 在场时整行不吃点击**：尾槽里通常是开关，行与槽都可点会双触发。需要「整行切换」的场景
 * （[com.situ.aichat.ui.components.SettingsSwitchRow]）由调用方在 [modifier] 上自己挂 `toggleable`——
 * 本组件只提供视觉骨架，不抢它的语义。
 *
 * **判据（§4.8）**：收编站若含本组件没有的槽（头像、多行尾内容、自定义 leading 尺寸），**登记不硬套、
 * 也不给本组件加参数**——加参数是巨参组件的第一步。
 */
@Composable
fun AppSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    badge: String? = null,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    // trailing 在场时行本身不吃点击（防与槽内开关双触发·§4.8）。
    val rowClick = onClick.takeIf { trailing == null }
    Row(
        modifier = modifier
            .then(
                if (rowClick != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        indication = LocalIndication.current,
                        interactionSource = null,
                    ) { haptics.light(); rowClick() }
                } else {
                    Modifier
                },
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.accent.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = AppTypography.settingsRowTitle,
                color = colors.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = AppTypography.settingsRowSubtitle,
                    color = colors.text.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = AppTypography.settingsRowValue,
                color = colors.text.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            // 药丸小标（R1 🟡-1 增补）：badge 不可点，故不参与 trailing 的「在场则行不吃点击」互斥。
            Text(
                badge,
                style = AppTypography.settingsRowValue,
                color = colors.accent.text,
                maxLines = 1,
                modifier = Modifier
                    .background(colors.accent.primary.copy(alpha = 0.12f), AppShapes.full)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        trailing?.invoke()
        if (showChevron) {
            Icon(
                AppProfileIcons.ChevronRight,
                contentDescription = null,
                tint = colors.text.tertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
