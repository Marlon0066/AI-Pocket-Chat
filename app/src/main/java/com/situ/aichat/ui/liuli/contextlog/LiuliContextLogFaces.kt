package com.situ.aichat.ui.liuli.contextlog

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.contextlog.ContextLogSettingsScreen

/** 上下文日志「保留设置」页的选脸包装（图纸 2026-09-06 卷五 A-1）。琉璃版排在 C3。 */
@Composable
fun SkinnedContextLogSettingsScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliContextLogSettingsScreen(onBack = onBack)
        return
    }
    ContextLogSettingsScreen(onBack = onBack)
}
