package com.situ.aichat.ui.liuli.worldbook

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.worldbook.WorldBookSettingsScreen

/** 世界书设置页的选脸包装（图纸 2026-09-06 卷五 A-1）。琉璃版排在 C3。 */
@Composable
fun SkinnedWorldBookSettingsScreen(onBack: () -> Unit, onOpenMemorySettings: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliWorldBookSettingsScreen(onBack = onBack, onOpenMemorySettings = onOpenMemorySettings)
        return
    }
    WorldBookSettingsScreen(onBack = onBack, onOpenMemorySettings = onOpenMemorySettings)
}
