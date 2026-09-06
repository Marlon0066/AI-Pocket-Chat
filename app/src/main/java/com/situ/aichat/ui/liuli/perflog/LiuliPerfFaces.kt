package com.situ.aichat.ui.liuli.perflog

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.perflog.PerfCollectScreen

/** 性能采集页的选脸包装（图纸 2026-09-06 卷五 A-1）。琉璃版排在 C4。 */
@Composable
fun SkinnedPerfCollectScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliPerfCollectScreen(onBack = onBack)
        return
    }
    PerfCollectScreen(onBack = onBack)
}
