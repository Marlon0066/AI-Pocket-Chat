package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.designsystem.AppProfileIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTileTone
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliGroupIconTile

/**
 * 琉璃 2.0 设置主页顶部大卡（AI 服务 / 外观）。
 * 使用 [liuliCardMaterial] 轻磨砂材质 + [LiuliGroupIconTile] 渐变彩砖。
 */
@Composable
internal fun LiuliSettingsHeroCard(
    title: String,
    value: String,
    icon: ImageVector,
    tileTone: LiuliTileTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    valueWarning: Boolean = false,
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val haptics = LocalAppHaptics.current
    Column(
        modifier = modifier
            .liuliCardMaterial(LiuliShapes.group, dark = LocalIsDarkTheme.current)
            .clickable(role = Role.Button) {
                haptics.light()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LiuliGroupIconTile(icon = icon, tone = tileTone)
            Icon(
                imageVector = AppProfileIcons.ChevronRight,
                contentDescription = null,
                tint = colors.text.tertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = typography.label,
                color = colors.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = typography.secondary,
                color = if (valueWarning) colors.status.onWarning else colors.text.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
