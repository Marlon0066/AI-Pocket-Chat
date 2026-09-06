package com.situ.aichat.ui.liuli.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1：卷五三十处选脸包装的**签名不漂移**（图纸 2026-09-06 卷五 A-1 · §2.1「Faces 追 30 包装签名钉」）。
 *
 * 为什么不整屏渲染两脸：包装的两条分支都落在 `hiltViewModel()` 默认形参上，Robolectric 里整屏起不来
 * （记忆 `reference-robolectric-hiltviewmodel-blocks-fullscreen`·同卷四 `LiuliSecondaryFacesTest`）。
 *
 * 这里钉的是**能机器验、且真会出事**的那一条：包装与被包的暖陶屏参数个数必须严丝合缝——包装漏一个
 * `onOpen*` 就是「点了没反应」的静默事故，而 Kotlin 那边只要包装体内不用它就编得过。
 * 口径：数「`Composer` 之前的声明参数」，暖陶屏减去它的 VM 默认形参个数应等于包装的参数个数。
 */
class LiuliVolume5FacesTest {

    private fun declaredParams(className: String, method: String): Int {
        val clazz = Class.forName(className)
        val fn = clazz.declaredMethods.firstOrNull { it.name == method }
            ?: error("$className 里找不到 $method")
        val composer = fn.parameterTypes.indexOfFirst { it.name == "androidx.compose.runtime.Composer" }
        return if (composer >= 0) composer else fn.parameterTypes.size
    }

    /** 一行 = 包装类 / 包装名 / 暖陶类 / 暖陶名 / 暖陶的 VM 默认形参个数。 */
    private data class Pin(
        val facesClass: String,
        val wrapper: String,
        val warmClass: String,
        val warm: String,
        val vmDefaults: Int,
    )

    private fun check(pin: Pin) {
        val wrapperCount = declaredParams(pin.facesClass, pin.wrapper)
        val warmCount = declaredParams(pin.warmClass, pin.warm)
        assertEquals(
            "${pin.wrapper} 应与 ${pin.warm} 同签名（减去 ${pin.vmDefaults} 个 VM 默认形参）",
            warmCount - pin.vmDefaults,
            wrapperCount,
        )
    }

    @Test fun 三十处包装与暖陶逐一同签名() {
        PINS.forEach(::check)
        // 三十处路由 = 二十九个包装（`promptModules` 两条路由共用一枚）。
        assertEquals(29, PINS.size)
    }

    @Test fun 参数个数逐条钉死() {
        // 期望值从各暖陶屏的签名重新打字（不回读 PINS 表·防「表错了测试跟着错」）。
        assertEquals(1, declaredParams(FACES_B, "SkinnedMemoryHubScreen"))
        assertEquals(2, declaredParams(FACES_B, "SkinnedGrowthSettingsScreen"))
        assertEquals(5, declaredParams(FACES_C, "SkinnedApiConfigScreen"))
        assertEquals(2, declaredParams(FACES_C, "SkinnedApiConfigEditScreen"))
        assertEquals(2, declaredParams(FACES_C, "SkinnedQrScanScreen"))
        assertEquals(2, declaredParams(FACES_C, "SkinnedAboutScreen"))
        assertEquals(3, declaredParams(FACES_DIARY, "SkinnedDiaryPromptSettingsScreen"))
        assertEquals(2, declaredParams(FACES_BACKUP, "SkinnedBackupScreen"))
    }

    private companion object {
        const val FACES_B = "com.situ.aichat.ui.liuli.settings.LiuliSettingsFacesBKt"
        const val FACES_C = "com.situ.aichat.ui.liuli.settings.LiuliSettingsFacesCKt"
        const val FACES_DIARY = "com.situ.aichat.ui.liuli.diary.LiuliDiaryFacesKt"
        const val FACES_MOMENTS = "com.situ.aichat.ui.liuli.moments.LiuliMomentsFacesKt"
        const val FACES_WORLDBOOK = "com.situ.aichat.ui.liuli.worldbook.LiuliWorldBookFacesKt"
        const val FACES_CONTEXTLOG = "com.situ.aichat.ui.liuli.contextlog.LiuliContextLogFacesKt"
        const val FACES_PROMPTMODULE = "com.situ.aichat.ui.liuli.promptmodule.LiuliPromptModuleFacesKt"
        const val FACES_PERF = "com.situ.aichat.ui.liuli.perflog.LiuliPerfFacesKt"
        const val FACES_WALLET = "com.situ.aichat.ui.liuli.wallet.LiuliWalletFacesKt"
        const val FACES_BACKUP = "com.situ.aichat.ui.liuli.backup.LiuliBackupFacesKt"

        const val WARM_SETTINGS = "com.situ.aichat.ui.settings."
        val PINS = listOf(
            // C1 六屏（已有琉璃版）
            Pin(FACES_B, "SkinnedMemoryHubScreen", WARM_SETTINGS + "MemoryHubScreenKt", "MemoryHubScreen", 0),
            Pin(FACES_B, "SkinnedGrowthSettingsScreen", WARM_SETTINGS + "GrowthSettingsScreenKt", "GrowthSettingsScreen", 1),
            Pin(FACES_B, "SkinnedImmersiveSettingsScreen", WARM_SETTINGS + "ImmersiveSettingsScreenKt", "ImmersiveSettingsScreen", 1),
            Pin(FACES_B, "SkinnedReplyRuleSettingsScreen", WARM_SETTINGS + "ReplyRuleSettingsScreenKt", "ReplyRuleSettingsScreen", 1),
            Pin(FACES_B, "SkinnedContentFilterSettingsScreen", WARM_SETTINGS + "ContentFilterSettingsScreenKt", "ContentFilterSettingsScreen", 1),
            Pin(FACES_B, "SkinnedCalendarAwarenessScreen", WARM_SETTINGS + "CalendarAwarenessScreenKt", "CalendarAwarenessScreen", 1),
            // C2–C4（包装先建齐·琉璃版随各 chunk 补）
            Pin(FACES_C, "SkinnedApiConfigScreen", WARM_SETTINGS + "ApiConfigScreenKt", "ApiConfigScreen", 1),
            Pin(FACES_C, "SkinnedApiConfigEditScreen", WARM_SETTINGS + "ApiConfigEditScreenKt", "ApiConfigEditScreen", 1),
            Pin(FACES_C, "SkinnedApiFunctionAssignmentScreen", WARM_SETTINGS + "ApiFunctionAssignmentScreenKt", "ApiFunctionAssignmentScreen", 1),
            Pin(FACES_C, "SkinnedQrScanScreen", WARM_SETTINGS + "QrScanScreenKt", "QrScanScreen", 0),
            Pin(FACES_C, "SkinnedTtsConfigurationScreen", WARM_SETTINGS + "TtsConfigurationScreenKt", "TtsConfigurationScreen", 1),
            Pin(FACES_C, "SkinnedVoiceCallSettingsScreen", WARM_SETTINGS + "VoiceCallSettingsScreenKt", "VoiceCallSettingsScreen", 1),
            Pin(FACES_C, "SkinnedStoryGlobalSettingsScreen", WARM_SETTINGS + "StoryGlobalSettingsScreenKt", "StoryGlobalSettingsScreen", 1),
            Pin(FACES_C, "SkinnedWorldSettingsScreen", WARM_SETTINGS + "WorldSettingsScreenKt", "WorldSettingsScreen", 1),
            Pin(FACES_C, "SkinnedAboutScreen", WARM_SETTINGS + "AboutScreenKt", "AboutScreen", 0),
            Pin(FACES_C, "SkinnedAgreementViewScreen", WARM_SETTINGS + "AboutScreenKt", "AgreementViewScreen", 0),
            Pin(FACES_C, "SkinnedSystemTogglesScreen", WARM_SETTINGS + "SystemTogglesScreenKt", "SystemTogglesScreen", 1),
            Pin(FACES_C, "SkinnedBackgroundReliabilityScreen", WARM_SETTINGS + "BackgroundReliabilityScreenKt", "BackgroundReliabilityScreen", 0),
            Pin(FACES_C, "SkinnedKernelObservatoryScreen", WARM_SETTINGS + "KernelObservatoryScreenKt", "KernelObservatoryScreen", 1),
            Pin(FACES_DIARY, "SkinnedDiarySettingsScreen", "com.situ.aichat.ui.diary.DiarySettingsScreenKt", "DiarySettingsScreen", 1),
            Pin(FACES_DIARY, "SkinnedDiaryPromptSettingsScreen", "com.situ.aichat.ui.diary.DiaryPromptSettingsScreenKt", "DiaryPromptSettingsScreen", 1),
            Pin(FACES_DIARY, "SkinnedDiaryPromptPreviewScreen", "com.situ.aichat.ui.diary.DiaryPromptPreviewScreenKt", "DiaryPromptPreviewScreen", 1),
            Pin(FACES_MOMENTS, "SkinnedMomentSettingsScreen", "com.situ.aichat.ui.moments.MomentSettingsScreenKt", "MomentSettingsScreen", 1),
            Pin(FACES_WORLDBOOK, "SkinnedWorldBookSettingsScreen", "com.situ.aichat.ui.worldbook.WorldBookSettingsScreenKt", "WorldBookSettingsScreen", 1),
            Pin(FACES_CONTEXTLOG, "SkinnedContextLogSettingsScreen", "com.situ.aichat.ui.contextlog.ContextLogSettingsScreenKt", "ContextLogSettingsScreen", 1),
            Pin(FACES_PROMPTMODULE, "SkinnedPromptModuleSettingsScreen", "com.situ.aichat.ui.promptmodule.PromptModuleSettingsScreenKt", "PromptModuleSettingsScreen", 1),
            Pin(FACES_PERF, "SkinnedPerfCollectScreen", "com.situ.aichat.ui.perflog.PerfCollectScreenKt", "PerfCollectScreen", 1),
            Pin(FACES_WALLET, "SkinnedRedeemCodeScreen", "com.situ.aichat.ui.wallet.RedeemCodeScreenKt", "RedeemCodeScreen", 1),
            Pin(FACES_BACKUP, "SkinnedBackupScreen", "com.situ.aichat.ui.backup.BackupScreenKt", "BackupScreen", 1),
        )
    }
}
