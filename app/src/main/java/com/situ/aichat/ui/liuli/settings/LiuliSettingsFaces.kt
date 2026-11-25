package com.situ.aichat.ui.liuli.settings

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.settings.AppearanceSettingsScreen
import com.situ.aichat.ui.settings.NotificationSettingsScreen
import com.situ.aichat.ui.settings.SettingsScreen
import com.situ.aichat.ui.settings.hub.SettingsHubEntry

/** 设置三屏的选脸点：主页签名已收束为 (onBack, onOpenEntry)，与暖陶 [SettingsScreen] 逐参对齐。 */
@Composable
fun SkinnedSettingsScreen(
    onBack: () -> Unit,
    onOpenEntry: (SettingsHubEntry) -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliSettingsScreen(onBack = onBack, onOpenEntry = onOpenEntry)
        return
    }
    SettingsScreen(onBack = onBack, onOpenEntry = onOpenEntry)
}

@Composable
fun SkinnedAppearanceSettingsScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliAppearanceScreen(onBack = onBack)
        return
    }
    AppearanceSettingsScreen(onBack = onBack)
}

@Composable
fun SkinnedNotificationSettingsScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliNotificationScreen(onBack = onBack)
        return
    }
    NotificationSettingsScreen(onBack = onBack)
}
