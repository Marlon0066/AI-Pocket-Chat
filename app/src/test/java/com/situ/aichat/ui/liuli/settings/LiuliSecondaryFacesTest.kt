package com.situ.aichat.ui.liuli.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1：二级屏选脸包装的**签名不漂移**（图纸 2026-09-06 卷四 A-1 · §8 C3）。
 *
 * 为什么不整屏渲染两脸：四个包装的两条分支都落在 `hiltViewModel()` 默认形参上，Robolectric 里整屏
 * 起不来（记忆 `reference-robolectric-hiltviewmodel-blocks-fullscreen`·卷三 `LiuliHomeFacesTest` 同样绕开）。
 * 实际分派留装机批。
 *
 * 这里钉的是**能机器验、且真会出事**的那一条：包装与被包的暖陶屏参数个数必须严丝合缝
 * ——包装漏一个 `onOpen*` 就是「点了没反应」的静默事故，而 Kotlin 那边只要包装体内不用它就编得过。
 * 口径：数「`Composer` 之前的声明参数」，暖陶屏减去它的 VM 默认形参个数应等于包装的参数个数（A-1：
 * VM 默认形参不进包装签名）。
 */
class LiuliSecondaryFacesTest {

    /** 数一个 @Composable 函数在合成器参数之前的声明参数个数。 */
    private fun declaredParams(className: String, method: String): Int {
        val clazz = Class.forName(className)
        val fn = clazz.declaredMethods.firstOrNull { it.name == method }
            ?: error("$className 里找不到 $method")
        val composer = fn.parameterTypes.indexOfFirst { it.name == "androidx.compose.runtime.Composer" }
        return if (composer >= 0) composer else fn.parameterTypes.size
    }

    private fun check(wrapper: String, wrapped: Pair<String, String>, vmDefaults: Int) {
        val wrapperCount = declaredParams(FACES_SETTINGS, wrapper)
        val wrappedCount = declaredParams(wrapped.first, wrapped.second)
        assertEquals(
            "$wrapper 应与 ${wrapped.second} 同签名（减去 $vmDefaults 个 VM 默认形参）",
            wrappedCount - vmDefaults,
            wrapperCount,
        )
    }

    @Test fun 设置主页包装与暖陶同签名() {
        // 暖陶 SettingsScreen = onBack + 25 个 onOpen* + 3 个 VM 默认形参。
        assertEquals(29, declaredParams(WARM_SETTINGS, "SettingsScreen"))
        assertEquals(26, declaredParams(FACES_SETTINGS, "SkinnedSettingsScreen"))
        check("SkinnedSettingsScreen", WARM_SETTINGS to "SettingsScreen", vmDefaults = 3)
    }

    @Test fun 外观与通知包装与暖陶同签名() {
        check("SkinnedAppearanceSettingsScreen", WARM_APPEARANCE to "AppearanceSettingsScreen", vmDefaults = 1)
        check("SkinnedNotificationSettingsScreen", WARM_NOTIFICATION to "NotificationSettingsScreen", vmDefaults = 1)
    }

    @Test fun 资料页包装与暖陶同签名() {
        val wrapper = declaredParams(FACES_CHARACTER, "SkinnedCharacterProfileScreen")
        val wrapped = declaredParams(WARM_CHARACTER, "CharacterProfileScreen")
        assertEquals(8, wrapper)
        assertEquals("暖陶资料页 = 8 回调 + 1 个 VM 默认形参", 9, wrapped)
        assertEquals(wrapped - 1, wrapper)
    }

    private companion object {
        const val FACES_SETTINGS = "com.situ.aichat.ui.liuli.settings.LiuliSettingsFacesKt"
        const val FACES_CHARACTER = "com.situ.aichat.ui.liuli.character.LiuliCharacterFacesKt"
        const val WARM_SETTINGS = "com.situ.aichat.ui.settings.SettingsScreenKt"
        const val WARM_APPEARANCE = "com.situ.aichat.ui.settings.AppearanceSettingsScreenKt"
        const val WARM_NOTIFICATION = "com.situ.aichat.ui.settings.NotificationSettingsScreenKt"
        const val WARM_CHARACTER = "com.situ.aichat.ui.character.CharacterProfileScreenKt"
    }
}
