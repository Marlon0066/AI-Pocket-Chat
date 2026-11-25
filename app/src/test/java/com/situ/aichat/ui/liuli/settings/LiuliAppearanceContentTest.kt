package com.situ.aichat.ui.liuli.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
 * T2：外观页内容层（设置全面重构卷二 B-2 已移除无实际效果的「动态取色」开关）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliAppearanceContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val skin = mutableStateOf(AppSkin.LIULI)
    private val tier = mutableStateOf(GlassTier.SHEER)
    private val mode = mutableStateOf(AppearanceMode.SYSTEM)
    private val calls = mutableMapOf<String, Any>()

    private fun show(hazeSupported: Boolean = true) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliAppearanceContent(
                        skin = skin.value,
                        glassTier = tier.value,
                        mode = mode.value,
                        onSetSkin = { calls["skin"] = it },
                        onSetGlassTier = { calls["tier"] = it },
                        onSetMode = { calls["mode"] = it },
                        onBack = {},
                        hazeSupported = hazeSupported,
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

    @Test fun 玻璃质感默认通透选中且点标准恰回调一次() {
        show(hazeSupported = true)
        compose.onNodeWithText("通透").performScrollTo().assertIsSelected()
        compose.onNodeWithText("标准").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(GlassTier.STANDARD, calls["tier"])
    }

    @Test fun 安卓13以下整行置灰显示毛玻璃且点不动() {
        show(hazeSupported = false)
        compose.onNodeWithText("毛玻璃").performScrollTo().assertIsSelected()
        listOf("毛玻璃", "标准", "通透").forEach { compose.onNodeWithText(it).assertIsNotEnabled() }
        compose.onNodeWithText("标准和通透需要安卓 13 及以上，当前使用毛玻璃。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("通透").performClick()
        compose.waitForIdle()
        assertEquals("置灰时点哪段都不回调", null, calls["tier"])
    }

    @Test fun 暖陶脸下玻璃质感节不组合() {
        skin.value = AppSkin.CLAY
        show(hazeSupported = true)
        compose.onNodeWithText("玻璃质感").assertDoesNotExist()
        compose.onNodeWithText("通透").assertDoesNotExist()
    }

    @Test fun 深浅模式三段与切换回调() {
        show()
        compose.onNodeWithText("跟随系统").performScrollTo().assertIsSelected()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(AppearanceMode.DARK, calls["mode"])
    }

    @Test fun 动态取色假开关已下架() {
        show()
        compose.onNodeWithText("动态取色").assertDoesNotExist()
    }

    @Test fun 底栏不透明度节琉璃不做() {
        show()
        compose.onNodeWithText("底部导航栏").assertDoesNotExist()
    }
}
