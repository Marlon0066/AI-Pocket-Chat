package com.situ.aichat.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppSettingsRow
import com.situ.aichat.ui.designsystem.AppSwitch

/**
 * 设置页通用开关行（可选前置图标 + 标题 + 可选副标题 + 尾部开关；整行可点切换）。多个设置页复用。
 * 骨架走自研 [AppSettingsRow]（M3 清零收官 C9·2026-09-04 由 M3 `ListItem` 换掉——**37 个调用站点
 * 一次变脸、对外签名零改**），尾槽是自绘白瓷 [AppSwitch]。icon 默认空 = 既有调用点观感不变
 * （SETTINGS_REORG D7：设置主页两处开关行传图标，与紧凑行左缘对齐）。
 *
 * 无障碍（P15·P0-10）：整行 `toggleable(role = Role.Switch)`，Switch 传 `onCheckedChange = null`（不独立获焦），
 * 消除 TalkBack 双焦点；`stateDescription` 播报开/关态——对齐 iOS 原生 Toggle 自带的 isToggle trait + 状态朗读。
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val onText = stringResource(R.string.a11y_switch_on)
    val offText = stringResource(R.string.a11y_switch_off)
    val haptics = LocalAppHaptics.current
    AppSettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        trailing = {
            AppSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                // 开=脆、关=柔（乙 1·H6，同 AppSwitch H5 口径）。触觉挂在整行 toggleable 上——
                // 尾部 Switch 是 onCheckedChange=null 的纯显示件，点击本就由这里接管。
                onValueChange = { value ->
                    if (value) haptics.light() else haptics.soft()
                    onCheckedChange(value)
                },
            )
            .semantics { stateDescription = if (checked) onText else offText },
    )
}
