package com.situ.aichat.ui.story

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [StoryRecapStrip] 渲染与两态切换（卷三 C3·图纸 §4.3 画面③④）。
 *
 * 12 小时阈值的真实回访在模拟器上够不着（归真机批），故此处钉的是**卡本身**：
 * 展开态出标题+上一章摘要原文+可点「收起」；收起态只剩可点的金族 chip；两个方向的回调都带对值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryRecapStripTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private val summary = "你在旧仓库找到了那半张船票，林晚棠承认她哥哥失踪前给她寄过同样的一张。"

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun strip(expanded: Boolean, onToggle: (Boolean) -> Unit = {}) {
        compose.setContent {
            AIPocketChatTheme {
                CompositionLocalProvider(LocalAppHaptics provides haptics) {
                    StoryRecapStrip(
                        summary = summary,
                        expanded = expanded,
                        isDark = false,
                        onToggle = onToggle,
                    )
                }
            }
        }
    }

    @Test fun 展开态_出标题与上一章摘要原文() {
        strip(expanded = true)
        compose.onNodeWithText(app.getString(R.string.story_recap_title), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(summary, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.story_recap_collapse), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test fun 展开态_点收起回调false() {
        var toggled: Boolean? = null
        strip(expanded = true) { toggled = it }
        compose.onNodeWithText(app.getString(R.string.story_recap_collapse), useUnmergedTree = true).performClick()
        assertEquals(false, toggled)
    }

    @Test fun 收起态_只剩可点chip_不再显示正文() {
        strip(expanded = false)
        // chip 的点击语义挂在外层金族 Surface 上（文字是它的子节点）→ 用合并树断言「这枚 chip 可点」。
        compose.onNodeWithText(app.getString(R.string.story_recap_chip))
            .assertIsDisplayed()
            .assertHasClickAction()
        compose.onNodeWithText(summary, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun 收起态_点chip回调true() {
        var toggled: Boolean? = null
        strip(expanded = false) { toggled = it }
        compose.onNodeWithText(app.getString(R.string.story_recap_chip), useUnmergedTree = true).performClick()
        assertEquals(true, toggled)
    }
}
