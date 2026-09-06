package com.situ.aichat.ui.liuli.moments

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.moments.MomentSettingsScreen

/** 朋友圈设置页的选脸包装（图纸 2026-09-06 卷五 A-1）。琉璃版排在 C3。 */
@Composable
fun SkinnedMomentSettingsScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliMomentSettingsScreen(onBack = onBack)
        return
    }
    MomentSettingsScreen(onBack = onBack)
}
