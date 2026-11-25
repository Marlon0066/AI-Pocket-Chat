package com.situ.aichat.ui.liuli.offline

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.offline.OfflineMeetingMemoryScreen

/** 见面回忆选脸包装。 */
@Composable
fun SkinnedOfflineMeetingMemoryScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliOfflineMeetingMemoryScreen(onBack = onBack)
        return
    }
    OfflineMeetingMemoryScreen(onBack = onBack)
}
