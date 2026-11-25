package com.situ.aichat.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppSettingsRow
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

/**
 * 设置页通用开关行（可选前置图标 + 标题 + 可选副标题 + 尾部开关；整行可点切换）。
 * 卷一升级（D-8·加法零回归）：支持 [disabledReason]（总开关关掉时变灰并显示锁图标 + 原因）与 [indented]（从属缩进）。
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    disabledReason: String? = null,
    indented: Boolean = false,
) {
    val onText = stringResource(R.string.a11y_switch_on)
    val offText = stringResource(R.string.a11y_switch_off)
    val haptics = LocalAppHaptics.current
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (indented) Modifier.padding(start = 16.dp) else Modifier),
    ) {
        AppSettingsRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            enabled = enabled,
            trailing = {
                AppSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
            },
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { value ->
                        if (value) haptics.light() else haptics.soft()
                        onCheckedChange(value)
                    },
                )
                .semantics { stateDescription = if (checked) onText else offText },
        )
        if (!enabled && !disabledReason.isNullOrBlank()) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = colors.status.onWarning,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    disabledReason,
                    style = AppTypography.settingsRowSubtitle,
                    color = colors.status.onWarning,
                )
            }
        }
    }
}
