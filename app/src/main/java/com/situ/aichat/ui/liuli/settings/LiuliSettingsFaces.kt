package com.situ.aichat.ui.liuli.settings

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.settings.AppearanceSettingsScreen
import com.situ.aichat.ui.settings.NotificationSettingsScreen
import com.situ.aichat.ui.settings.SettingsScreen

/**
 * 设置三屏的选脸点（图纸 2026-09-06 卷四 A-1·口径同卷三 `LiuliHomeFaces`）。每个包装与暖陶屏**同签名**
 * （VM 默认形参不进包装签名）；体内按 [LocalAppSkin] 二选一，两条分支实参逐字相同——导航图只把函数名
 * 换成 `Skinned…`。三个包装一次建齐（图纸 §10 ⑥：`AIChatApp.kt` 只在 C3 动一次）。
 */

@Composable
fun SkinnedSettingsScreen(
    onBack: () -> Unit,
    onOpenApiConfig: () -> Unit,
    onOpenApiFunctions: () -> Unit,
    onOpenMemorySettings: () -> Unit,
    onOpenSystemToggles: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenImmersiveSettings: () -> Unit,
    onOpenStickerManagement: () -> Unit,
    onOpenGrowthSettings: () -> Unit,
    onOpenReplyRules: () -> Unit,
    onOpenContentFilter: () -> Unit,
    onOpenCalendarAwareness: () -> Unit,
    onOpenWorldBooks: () -> Unit,
    onOpenPromptModules: () -> Unit,
    onOpenTtsConfig: () -> Unit,
    onOpenVoiceCallSettings: () -> Unit,
    onOpenDiarySettings: () -> Unit,
    onOpenMomentSettings: () -> Unit,
    onOpenStoryGlobalSettings: () -> Unit,
    onOpenWorldSettings: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenBackgroundReliability: () -> Unit,
    onOpenContextLog: () -> Unit,
    onOpenPerfCollect: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliSettingsScreen(
            onBack = onBack,
            callbacks = LiuliSettingsCallbacks(
                onOpenApiConfig = onOpenApiConfig,
                onOpenApiFunctions = onOpenApiFunctions,
                onOpenMemorySettings = onOpenMemorySettings,
                onOpenSystemToggles = onOpenSystemToggles,
                onOpenAppearance = onOpenAppearance,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onOpenImmersiveSettings = onOpenImmersiveSettings,
                onOpenStickerManagement = onOpenStickerManagement,
                onOpenGrowthSettings = onOpenGrowthSettings,
                onOpenReplyRules = onOpenReplyRules,
                onOpenContentFilter = onOpenContentFilter,
                onOpenCalendarAwareness = onOpenCalendarAwareness,
                onOpenWorldBooks = onOpenWorldBooks,
                onOpenPromptModules = onOpenPromptModules,
                onOpenTtsConfig = onOpenTtsConfig,
                onOpenVoiceCallSettings = onOpenVoiceCallSettings,
                onOpenDiarySettings = onOpenDiarySettings,
                onOpenMomentSettings = onOpenMomentSettings,
                onOpenStoryGlobalSettings = onOpenStoryGlobalSettings,
                onOpenWorldSettings = onOpenWorldSettings,
                onOpenBackup = onOpenBackup,
                onOpenBackgroundReliability = onOpenBackgroundReliability,
                onOpenContextLog = onOpenContextLog,
                onOpenPerfCollect = onOpenPerfCollect,
                onOpenAbout = onOpenAbout,
            ),
        )
        return
    }
    SettingsScreen(
        onBack = onBack,
        onOpenApiConfig = onOpenApiConfig,
        onOpenApiFunctions = onOpenApiFunctions,
        onOpenMemorySettings = onOpenMemorySettings,
        onOpenSystemToggles = onOpenSystemToggles,
        onOpenAppearance = onOpenAppearance,
        onOpenNotificationSettings = onOpenNotificationSettings,
        onOpenImmersiveSettings = onOpenImmersiveSettings,
        onOpenStickerManagement = onOpenStickerManagement,
        onOpenGrowthSettings = onOpenGrowthSettings,
        onOpenReplyRules = onOpenReplyRules,
        onOpenContentFilter = onOpenContentFilter,
        onOpenCalendarAwareness = onOpenCalendarAwareness,
        onOpenWorldBooks = onOpenWorldBooks,
        onOpenPromptModules = onOpenPromptModules,
        onOpenTtsConfig = onOpenTtsConfig,
        onOpenVoiceCallSettings = onOpenVoiceCallSettings,
        onOpenDiarySettings = onOpenDiarySettings,
        onOpenMomentSettings = onOpenMomentSettings,
        onOpenStoryGlobalSettings = onOpenStoryGlobalSettings,
        onOpenWorldSettings = onOpenWorldSettings,
        onOpenBackup = onOpenBackup,
        onOpenBackgroundReliability = onOpenBackgroundReliability,
        onOpenContextLog = onOpenContextLog,
        onOpenPerfCollect = onOpenPerfCollect,
        onOpenAbout = onOpenAbout,
    )
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
