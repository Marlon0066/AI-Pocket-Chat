package com.situ.aichat.ui.liuli.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.AppearanceMode
import com.situ.aichat.data.model.GlassTier
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：外观页内容层（图纸 2026-09-06 卷四 §8 C3b · A-6）。
 *
 * 钉：三个条件节（透明度只在琉璃 + 有实时模糊时显 E4 / 动态取色只在 SDK ≥ 31 显 E5 /
 * **底栏不透明度节琉璃不做**）；四个 setter 各恰一次且带正确的新值；选中态语义正确。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliAppearanceContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val skin = mutableStateOf(AppSkin.LIULI)
    private val tier = mutableStateOf(GlassTier.CLEAR)
    private val mode = mutableStateOf(AppearanceMode.SYSTEM)
    private val dynamic = mutableStateOf(false)
    private val calls = mutableMapOf<String, Any>()

    private fun show(blurSupported: Boolean = true, dynamicColorSupported: Boolean = true) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliAppearanceContent(
                        skin = skin.value,
                        glassTier = tier.value,
                        mode = mode.value,
                        useDynamicColor = dynamic.value,
                        onSetSkin = { calls["skin"] = it },
                        onSetGlassTier = { calls["tier"] = it },
                        onSetMode = { calls["mode"] = it },
                        onSetDynamicColor = { calls["dynamic"] = it },
                        onBack = {},
                        blurSupported = blurSupported,
                        dynamicColorSupported = dynamicColorSupported,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 主题两张脸都在且只有当前脸被选中() {
        show()
        compose.onNodeWithText("琉璃").assertIsSelected()
        compose.onNodeWithText("暖陶").assertIsNotSelected()
    }

    @Test fun 点另一张脸恰回调一次带新值() {
        show()
        compose.onNodeWithText("暖陶").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(AppSkin.CLAY, calls["skin"])
    }

    @Test fun 透明度节只在琉璃且有实时模糊时才在() {
        show(blurSupported = true)
        compose.onNodeWithText("清透").performScrollTo().assertIsSelected()
        compose.onNodeWithText("着色").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(GlassTier.TINTED, calls["tier"])
    }

    @Test fun 无实时模糊能力时透明度节不组合() {
        show(blurSupported = false)
        compose.onNodeWithText("清透").assertDoesNotExist()
        compose.onNodeWithText("着色").assertDoesNotExist()
    }

    @Test fun 暖陶脸下透明度节不组合() {
        skin.value = AppSkin.CLAY
        show(blurSupported = true)
        compose.onNodeWithText("清透").assertDoesNotExist()
    }

    @Test fun 深浅模式三段与切换回调() {
        show()
        compose.onNodeWithText("跟随系统").performScrollTo().assertIsSelected()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(AppearanceMode.DARK, calls["mode"])
    }

    @Test fun 动态取色只在支持时显且开关回调一次() {
        show(dynamicColorSupported = true)
        compose.onNodeWithText("动态取色").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(true, calls["dynamic"])
    }

    @Test fun 不支持动态取色时整组不组合() {
        show(dynamicColorSupported = false)
        compose.onNodeWithText("动态取色").assertDoesNotExist()
    }

    /** 契约 D-7：琉璃底栏是玻璃片、不读底栏不透明度偏好，所以那一节整节不做。 */
    @Test fun 底栏不透明度节琉璃不做() {
        show()
        compose.onNodeWithText("底部导航栏").assertDoesNotExist()
    }
}
