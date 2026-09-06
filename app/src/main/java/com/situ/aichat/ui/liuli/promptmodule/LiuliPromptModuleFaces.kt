package com.situ.aichat.ui.liuli.promptmodule

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.promptmodule.PromptModuleSettingsScreen

/**
 * 提示词模块页的选脸包装（图纸 2026-09-06 卷五 A-1）。**两条路由共用这一枚**
 * （`promptModules` 与 `promptModules/{characterUuid}`·A-1 明说两条都换）。琉璃版排在 C3。
 */
@Composable
fun SkinnedPromptModuleSettingsScreen(onBack: () -> Unit, onOpenImmersiveSettings: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliPromptModuleScreen(onBack = onBack, onOpenImmersiveSettings = onOpenImmersiveSettings)
        return
    }
    PromptModuleSettingsScreen(onBack = onBack, onOpenImmersiveSettings = onOpenImmersiveSettings)
}
