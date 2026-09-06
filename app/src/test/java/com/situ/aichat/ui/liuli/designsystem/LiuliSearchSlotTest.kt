package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.theme.AIPocketChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-9：琉璃搜索槽（图纸 2026-09-06 卷三 §7 T2-9 · A-14 / §3.2「搜索槽」）。
 *
 * 钉：占位在空值时显示、输入回调把字送出去、清除圆**只在有字时**存在且带 cd、点它清空、
 * 清除圆触达 ≥ 48（视觉 20 外溢·PITFALLS §1d）、槽高恒 38（单行不长高）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliSearchSlotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(initial: String = "") {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                var value by remember { mutableStateOf(initial) }
                LiuliSearchSlot(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = "搜索对话",
                    clearContentDescription = "清除搜索",
                    modifier = Modifier.testTag("slot"),
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun 空值显占位且没有清除圆() {
        show()
        compose.onNodeWithText("搜索对话").assertIsDisplayed()
        compose.onNodeWithContentDescription("清除搜索").assertDoesNotExist()
    }

    @Test fun 输入把字送出去并让占位让位() {
        show()
        compose.onNodeWithTag("slot").performTextInput("小满")
        compose.waitForIdle()
        compose.onNodeWithText("小满").assertIsDisplayed()
        compose.onNodeWithText("搜索对话").assertDoesNotExist()
    }

    @Test fun 有字时清除圆出现且点它清空() {
        show(initial = "小满")
        compose.onNodeWithContentDescription("清除搜索").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("搜索对话").assertIsDisplayed()
    }

    @Test fun 清除圆触达至少四十八而槽仍是三十八高() {
        show(initial = "小满")
        compose.onNodeWithContentDescription("清除搜索").assertHeightIsAtLeast(48.dp)
        val slot = compose.onNodeWithTag("slot").getUnclippedBoundsInRoot()
        assertEquals("触达框外溢，槽本身不许被撑高", 38f, (slot.bottom - slot.top).value, 0.5f)
    }
}
