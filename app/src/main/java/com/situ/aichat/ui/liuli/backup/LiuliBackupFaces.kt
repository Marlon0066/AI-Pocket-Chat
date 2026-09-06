package com.situ.aichat.ui.liuli.backup

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.backup.BackupScreen

/**
 * 备份页的选脸包装（图纸 2026-09-06 卷五 A-1）。导入预览屏**无路由**（由备份屏内联替换），故不做包装。
 * 琉璃版排在 C4。
 */
@Composable
fun SkinnedBackupScreen(onBack: () -> Unit, autoPickFolder: Boolean = false) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliBackupScreen(onBack = onBack, autoPickFolder = autoPickFolder)
        return
    }
    BackupScreen(onBack = onBack, autoPickFolder = autoPickFolder)
}
