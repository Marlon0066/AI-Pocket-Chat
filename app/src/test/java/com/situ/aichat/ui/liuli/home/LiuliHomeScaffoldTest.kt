package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliSectionHeader
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-2：主页骨架（图纸 2026-09-06 卷三 §7 T2-2 · §4.2 · A-17）。
 *
 * 钉：`collapsed = false` 时屏上只有一处标题（列表里的大标题带）；`collapsed = true` 时**多**出玻璃顶栏里的
 * 小标题、而大标题仍在列表里（它是 item 0、随内容滚，并不被替换掉）；「+」的 cd 与回调恰一次、`plus = null`
 * 不画节点；**两态「+」位置一像素不动**（A-17：静止时压在大标题带右端、收起后在玻璃顶栏里，同一枚钮不跳）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliHomeScaffoldTest {

    @get:Rule
    val compose = createComposeRule()

    private var plusTaps = 0
    private val collapsedState = mutableStateOf(false)

    private fun show(collapsed: Boolean, withPlus: Boolean = true) {
        collapsedState.value = collapsed
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliHomeScaffold(
                        title = "聊天",
                        collapsed = collapsedState.value,
                        plus = if (withPlus) {
                            {
                                LiuliCircleButton(onClick = { plusTaps++ }, contentDescription = "发起对话") {
                                    Icon(Icons.Filled.Add, contentDescription = null)
                                }
                            }
                        } else {
                            null
                        },
                    ) {
                        Column(Modifier) {
                            LiuliLargeTitle("聊天")
                            LiuliSectionHeader("置顶")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 未收起时屏上只有大标题一处() {
        show(collapsed = false)
        compose.onAllNodesWithText("聊天").assertCountEquals(1)
        compose.onNodeWithContentDescription("发起对话").assertIsDisplayed()
    }

    @Test fun 收起后小标题出现且大标题仍在列表里() {
        show(collapsed = true)
        compose.onAllNodesWithText("聊天").assertCountEquals(2)
        compose.onAllNodesWithText("置顶").assertCountEquals(1)
    }

    @Test fun 加号回调恰一次() {
        show(collapsed = false)
        compose.onNodeWithContentDescription("发起对话").performClick()
        compose.waitForIdle()
        assertEquals(1, plusTaps)
    }

    @Test fun 无加号槽时不画钮() {
        show(collapsed = false, withPlus = false)
        compose.onNodeWithContentDescription("发起对话").assertDoesNotExist()
    }

    @Test fun 加号两态位置不动() {
        show(collapsed = false)
        val expanded = compose.onNodeWithContentDescription("发起对话").getUnclippedBoundsInRoot()
        compose.runOnIdle { collapsedState.value = true }
        compose.waitForIdle()
        val collapsedBounds = compose.onNodeWithContentDescription("发起对话").getUnclippedBoundsInRoot()
        assertEquals(expanded.left.value, collapsedBounds.left.value, 0.01f)
        assertEquals(expanded.top.value, collapsedBounds.top.value, 0.01f)
        // 版位 = 视觉 40（骨架用 Box(size 40) 定位，`LiuliCircleButton` 自带的 48 触达框居中外溢·PITFALLS §1d），
        // 所以两态里量到的都是那枚 40 的圆，位置就是「状态栏底 + 2 / 右 20」。
        assertEquals(40.dp.value, (collapsedBounds.bottom - collapsedBounds.top).value, 0.01f)
    }
}
