package com.situ.aichat.ui.liuli.sticker

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.sticker.StickerImportScreen
import com.situ.aichat.ui.sticker.StickerManagementScreen

/** 表情包管理页选脸包装。 */
@Composable
fun SkinnedStickerManagementScreen(
    onBack: () -> Unit,
    onImport: () -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliStickerManagementScreen(onBack = onBack, onImport = onImport)
        return
    }
    StickerManagementScreen(onBack = onBack, onImport = onImport)
}

/** 表情包导入页选脸包装。 */
@Composable
fun SkinnedStickerImportScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliStickerImportScreen(onBack = onBack)
        return
    }
    StickerImportScreen(onBack = onBack)
}
