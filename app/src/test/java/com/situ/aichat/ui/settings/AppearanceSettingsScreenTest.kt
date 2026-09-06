package com.situ.aichat.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.situ.aichat.data.local.SettingsPreferences
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.data.model.AppearanceMode
import com.situ.aichat.data.model.GlassTier
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：外观页在「脸 × API 档」下的节可见性（图纸 2026-09-04-琉璃第二张脸-卷一 §7 T2-3 · E4/E5/E6/E9）。
 *
 * 唯二有意变化就靠这三例看门：琉璃下多一节「透明度」（API≥31）、少一节「底部导航栏」；暖陶下反之；
 * API 29–30 没有实时模糊能力 → 连「透明度」都不给选（图纸 §0 ② 8）。
 *
 * qualifiers 钉 zh-rCN（顺带验五键 zh/en 成对可解析）+ 真机尺寸（屏太小节点会被推出可视区·PITFALLS §1e）。
 * VM 用真 [AppearanceSettingsViewModel] + MockK 的 [SettingsPreferences]，显式传 `viewModel =` 形参绕开
 * `hiltViewModel()` 默认值（本库无 Hilt 单测基建）；[LocalAppHaptics] 无默认值也须自己 provide。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class AppearanceSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun vmWith(skin: AppSkin, tier: GlassTier = GlassTier.CLEAR): AppearanceSettingsViewModel {
        val prefs = mockk<SettingsPreferences>(relaxed = true)
        every { prefs.appSkin } returns flowOf(skin)
        every { prefs.glassTier } returns flowOf(tier)
        every { prefs.appearanceMode } returns flowOf(AppearanceMode.LIGHT)
        every { prefs.useDynamicColor } returns flowOf(false)
        every { prefs.bottomNavOpacity } returns flowOf(0.88f)
        coEvery { prefs.setAppSkin(any()) } returns Unit
        coEvery { prefs.setGlassTier(any()) } returns Unit
        return AppearanceSettingsViewModel(prefs)
    }

    private fun show(skin: AppSkin, tier: GlassTier = GlassTier.CLEAR) {
        val vm = vmWith(skin, tier)
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = skin) {
                // LocalAppHaptics 无默认值（正式注入点在 AppRoot），单测必须自己 provide。
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    AppearanceSettingsScreen(onBack = {}, viewModel = vm)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `琉璃 + API34：有透明度节、无底部导航栏节、默认清透选中`() {
        show(AppSkin.LIULI)
        compose.onNodeWithText("透明度").assertExists()
        compose.onNodeWithText("清透").assertIsSelected()
        compose.onNodeWithText("底部导航栏").assertDoesNotExist()
        // 正向锚：两张脸的卡都在，琉璃那张被选中（证明这一屏真渲染出来了，不是空树假绿）。
        compose.onNodeWithText("琉璃").assertIsSelected()
    }

    @Test
    fun `暖陶 + API34：有底部导航栏节、无透明度节`() {
        show(AppSkin.CLAY)
        compose.onNodeWithText("底部导航栏").assertExists()
        compose.onNodeWithText("暖陶").assertIsSelected()
        compose.onNodeWithText("透明度").assertDoesNotExist()
        compose.onNodeWithText("清透").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [30], qualifiers = "zh-rCN-w411dp-h891dp")
    fun `琉璃 + API30：无实时模糊能力则连透明度节都不给`() {
        show(AppSkin.LIULI)
        // 正向锚：屏确实渲染了琉璃态（否则下面的全否定断言没有判别力）。
        compose.onNodeWithText("琉璃").assertIsSelected()
        compose.onNodeWithText("透明度").assertDoesNotExist()
        compose.onNodeWithText("清透").assertDoesNotExist()
        // 底栏节的隐藏只看脸、不看 API：琉璃下仍然没有。
        compose.onNodeWithText("底部导航栏").assertDoesNotExist()
    }
}
