package com.situ.aichat.ui.liuli.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppBottomNavItem
import com.situ.aichat.ui.designsystem.AppNavIcons
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-10：底栏选脸（图纸 2026-09-06 卷三 §7 T2-10 · A-1 · E12）。
 *
 * 组件级、不整屏——整屏会被 `hiltViewModel()` 掐死（记忆 `reference-robolectric-hiltviewmodel-blocks-fullscreen`）。
 *
 * 判别力从**行为差**取，不从「都有四个 Tab 文案」取：缩丸是琉璃底栏独有的（暖陶 `AppBottomNav` 根本不读
 * `chrome`）——滚一下之后，暖陶仍是四个 Tab，琉璃只剩当前那一个。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliHomeFacesTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var chrome: LiuliHomeChrome

    private val labels = listOf("聊天", "联系人", "动态", "我")

    private fun show(skin: AppSkin) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = skin) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    chrome = rememberLiuliHomeChrome()
                    SkinnedBottomNav(
                        items = labels.zip(
                            listOf(AppNavIcons.Chat, AppNavIcons.Contacts, AppNavIcons.Moments, AppNavIcons.Profile),
                        ).mapIndexed { i, (label, icon) ->
                            AppBottomNavItem(icon = icon, label = label, selected = i == 0, onClick = {})
                        },
                        opacity = 1f,
                        chrome = chrome,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun scrollDown() {
        compose.runOnIdle {
            chrome.connection.onPostScroll(
                Offset(0f, -30f * compose.density.density),
                Offset.Zero,
                NestedScrollSource.UserInput,
            )
        }
        compose.waitForIdle()
    }

    @Test fun 暖陶下是暖陶底栏且不认缩丸信号() {
        show(AppSkin.CLAY)
        labels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        scrollDown()
        labels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun 琉璃下是玻璃底栏且会缩成小丸() {
        show(AppSkin.LIULI)
        labels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        scrollDown()
        compose.onNodeWithText("聊天").assertIsDisplayed()
        labels.drop(1).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }
}
