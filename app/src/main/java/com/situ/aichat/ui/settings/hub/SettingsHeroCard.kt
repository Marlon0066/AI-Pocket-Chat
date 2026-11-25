package com.situ.aichat.ui.settings.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.situ.aichat.ui.designsystem.appCardSurface

/**
 * 暖陶设置主页顶部大卡（AI 服务 / 外观，总规划 §2）。
 * 双列等宽排布，卡内带陶土图标砖、标题、当前状态（支持警示色）与轻量右箭头。
 */
@Composable
internal fun SettingsHeroCard(
    title: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    valueWarning: Boolean = false,
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val haptics = LocalAppHaptics.current
    Column(
        modifier = modifier
            .appCardSurface(raised = true, cornerRadius = 18.dp)
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
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(colors.accent.primary.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.accent.text,
                    modifier = Modifier.size(19.dp),
                )
            }
            Icon(
                imageVector = AppProfileIcons.ChevronRight,
                contentDescription = null,
                tint = colors.text.tertiary,
                modifier = Modifier.size(16.dp),
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
                style = typography.settingsHubValue,
                color = if (valueWarning) colors.status.onWarning else colors.text.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
