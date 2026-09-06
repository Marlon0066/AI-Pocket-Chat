package com.situ.aichat.ui.liuli.diary

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.diary.DiaryPromptPreviewScreen
import com.situ.aichat.ui.diary.DiaryPromptSettingsScreen
import com.situ.aichat.ui.diary.DiarySettingsScreen

/**
 * 日记族三屏的选脸包装（图纸 2026-09-06 卷五 A-1）。三屏的琉璃版排在 C3，本文件先与另外二十七个包装
 * 一起建齐，好让 `AIChatApp.kt` 只动一次（§10 ⑥）。
 */

@Composable
fun SkinnedDiarySettingsScreen(onBack: () -> Unit, onOpenWritingRules: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliDiarySettingsScreen(onBack = onBack, onOpenWritingRules = onOpenWritingRules)
        return
    }
    DiarySettingsScreen(onBack = onBack, onOpenWritingRules = onOpenWritingRules)
}

@Composable
fun SkinnedDiaryPromptSettingsScreen(
    onBack: () -> Unit,
    onOpenPreviewMine: () -> Unit,
    onOpenPreviewExchange: () -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliDiaryPromptSettingsScreen(
            onBack = onBack,
            onOpenPreviewMine = onOpenPreviewMine,
            onOpenPreviewExchange = onOpenPreviewExchange,
        )
        return
    }
    DiaryPromptSettingsScreen(
        onBack = onBack,
        onOpenPreviewMine = onOpenPreviewMine,
        onOpenPreviewExchange = onOpenPreviewExchange,
    )
}

@Composable
fun SkinnedDiaryPromptPreviewScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliDiaryPromptPreviewScreen(onBack = onBack)
        return
    }
    DiaryPromptPreviewScreen(onBack = onBack)
}
