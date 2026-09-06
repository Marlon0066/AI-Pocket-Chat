package com.situ.aichat.ui.liuli.settings

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.settings.AboutScreen
import com.situ.aichat.ui.settings.AgreementViewScreen
import com.situ.aichat.ui.settings.ApiConfigEditScreen
import com.situ.aichat.ui.settings.ApiConfigScreen
import com.situ.aichat.ui.settings.ApiFunctionAssignmentScreen
import com.situ.aichat.ui.settings.BackgroundReliabilityScreen
import com.situ.aichat.ui.settings.KernelObservatoryScreen
import com.situ.aichat.ui.settings.QrScanScreen
import com.situ.aichat.ui.settings.StoryGlobalSettingsScreen
import com.situ.aichat.ui.settings.SystemTogglesScreen
import com.situ.aichat.ui.settings.TtsConfigurationScreen
import com.situ.aichat.ui.settings.VoiceCallSettingsScreen
import com.situ.aichat.ui.settings.WorldSettingsScreen

/**
 * 设置族选脸包装 · C 组（图纸 2026-09-06 卷五 A-1）。
 *
 * 本文件 = C2 / C3 / C4 的十三屏。**包装在 C1 一次建齐**（`AIChatApp.kt` 只在 C1 动一次·§10 ⑥），
 * 各 chunk 再回来补 `if (LocalAppSkin.current == AppSkin.LIULI) { … return }` 那两行。
 * **十三屏已于 C2 / C3 / C4 逐批接完**（`TODO(Cx)` 全清）——漏一个就是「点了没换脸」的静默事故，
 * `LiuliVolume5FacesTest` 钉的是签名不漂移，「有没有接上」靠装机走查（§7-3）。
 */

// ── C2：API 三屏 + 扫码 + TTS + 语音通话 ──────────────────────────────────────────

@Composable
fun SkinnedApiConfigScreen(
    onBack: () -> Unit,
    onEditConfig: (String) -> Unit,
    onOpenScan: () -> Unit = {},
    scannedConfig: String? = null,
    onScanConsumed: () -> Unit = {},
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliApiConfigScreen(
            onBack = onBack,
            onEditConfig = onEditConfig,
            onOpenScan = onOpenScan,
            scannedConfig = scannedConfig,
            onScanConsumed = onScanConsumed,
        )
        return
    }
    ApiConfigScreen(
        onBack = onBack,
        onEditConfig = onEditConfig,
        onOpenScan = onOpenScan,
        scannedConfig = scannedConfig,
        onScanConsumed = onScanConsumed,
    )
}

@Composable
fun SkinnedApiConfigEditScreen(uuid: String, onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliApiConfigEditScreen(uuid = uuid, onBack = onBack)
        return
    }
    ApiConfigEditScreen(uuid = uuid, onBack = onBack)
}

@Composable
fun SkinnedApiFunctionAssignmentScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliApiFunctionAssignmentScreen(onBack = onBack)
        return
    }
    ApiFunctionAssignmentScreen(onBack = onBack)
}

@Composable
fun SkinnedQrScanScreen(onResult: (String) -> Unit, onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliQrScanScreen(onResult = onResult, onBack = onBack)
        return
    }
    QrScanScreen(onResult = onResult, onBack = onBack)
}

@Composable
fun SkinnedTtsConfigurationScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliTtsConfigScreen(onBack = onBack)
        return
    }
    TtsConfigurationScreen(onBack = onBack)
}

@Composable
fun SkinnedVoiceCallSettingsScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliVoiceCallSettingsScreen(onBack = onBack)
        return
    }
    VoiceCallSettingsScreen(onBack = onBack)
}

// ── C3：故事全局 + 世界 ────────────────────────────────────────────────────────

@Composable
fun SkinnedStoryGlobalSettingsScreen(onBack: () -> Unit, onOpenField: (String) -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliStoryGlobalSettingsScreen(onBack = onBack, onOpenField = onOpenField)
        return
    }
    StoryGlobalSettingsScreen(onBack = onBack, onOpenField = onOpenField)
}

@Composable
fun SkinnedWorldSettingsScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliWorldSettingsScreen(onBack = onBack)
        return
    }
    WorldSettingsScreen(onBack = onBack)
}

// ── C4：关于 / 协议 / 系统开关 / 后台保障 / 观测台 ────────────────────────────────

@Composable
fun SkinnedAboutScreen(onBack: () -> Unit, onOpenAgreement: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliAboutScreen(onBack = onBack, onOpenAgreement = onOpenAgreement)
        return
    }
    AboutScreen(onBack = onBack, onOpenAgreement = onOpenAgreement)
}

@Composable
fun SkinnedAgreementViewScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliAgreementViewScreen(onBack = onBack)
        return
    }
    AgreementViewScreen(onBack = onBack)
}

@Composable
fun SkinnedSystemTogglesScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliSystemTogglesScreen(onBack = onBack)
        return
    }
    SystemTogglesScreen(onBack = onBack)
}

@Composable
fun SkinnedBackgroundReliabilityScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliBackgroundReliabilityScreen(onBack = onBack)
        return
    }
    BackgroundReliabilityScreen(onBack = onBack)
}

@Composable
fun SkinnedKernelObservatoryScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliKernelObservatoryScreen(onBack = onBack)
        return
    }
    KernelObservatoryScreen(onBack = onBack)
}
