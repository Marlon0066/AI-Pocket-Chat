package com.situ.aichat.ui.liuli.settings

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.settings.CalendarAwarenessScreen
import com.situ.aichat.ui.settings.ContentFilterSettingsScreen
import com.situ.aichat.ui.settings.GrowthSettingsScreen
import com.situ.aichat.ui.settings.ImmersiveSettingsScreen
import com.situ.aichat.ui.settings.MemoryHubScreen
import com.situ.aichat.ui.settings.ReplyRuleSettingsScreen

/**
 * 设置族选脸包装 · B 组（图纸 2026-09-06 卷五 A-1·口径同卷四 [LiuliSettingsFaces]）。
 *
 * 每个包装与暖陶屏**同签名**（VM 默认形参不进包装签名）；体内按 [LocalAppSkin] 二选一，两条分支实参
 * 逐字相同——`AIChatApp.kt` 只把函数名换成 `Skinned…`。三十个包装**一次建齐**（§8 C1 / §10 ⑥：
 * `AIChatApp.kt` 只动这一次），琉璃版还没建的屏先无条件走暖陶并挂 `TODO(Cx)`（卷四 D-6 先例）。
 *
 * 本文件 = C1 的六屏（记忆 / 成长 / 沉浸 / 回复 / 过滤 / 日历），六个都已有琉璃版。
 */

@Composable
fun SkinnedMemoryHubScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliMemoryHubScreen(onBack = onBack)
        return
    }
    MemoryHubScreen(onBack = onBack)
}

@Composable
fun SkinnedGrowthSettingsScreen(onBack: () -> Unit, onOpenObservatory: () -> Unit = {}) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliGrowthSettingsScreen(onBack = onBack, onOpenObservatory = onOpenObservatory)
        return
    }
    GrowthSettingsScreen(onBack = onBack, onOpenObservatory = onOpenObservatory)
}

@Composable
fun SkinnedImmersiveSettingsScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliImmersiveSettingsScreen(onBack = onBack)
        return
    }
    ImmersiveSettingsScreen(onBack = onBack)
}

@Composable
fun SkinnedReplyRuleSettingsScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliReplyRuleScreen(onBack = onBack)
        return
    }
    ReplyRuleSettingsScreen(onBack = onBack)
}

@Composable
fun SkinnedContentFilterSettingsScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliContentFilterScreen(onBack = onBack)
        return
    }
    ContentFilterSettingsScreen(onBack = onBack)
}

@Composable
fun SkinnedCalendarAwarenessScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliCalendarAwarenessScreen(onBack = onBack)
        return
    }
    CalendarAwarenessScreen(onBack = onBack)
}
