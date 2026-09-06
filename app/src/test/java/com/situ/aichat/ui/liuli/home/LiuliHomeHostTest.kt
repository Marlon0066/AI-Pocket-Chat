package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppBottomNavItem
import com.situ.aichat.ui.designsystem.AppNavIcons
import com.situ.aichat.ui.liuli.glass.BackdropState
import com.situ.aichat.ui.liuli.glass.LiuliGatedBackdropHost
import com.situ.aichat.ui.liuli.glass.LocalBackdrop
import com.situ.aichat.ui.liuli.glass.rememberBackdropState
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-3：主页宿主与它的门（图纸 2026-09-06 卷三 §7 T2-3 · A-2 / A-5 · E5）。
 *
 * 钉三件：① 暖陶下宿主 = 一个纯 `Box`——content 与 bottomBar 都渲染、overlay 里**没有** `LocalBackdrop`
 * （暖陶一层都不录、`AppBottomNav` 也不需要）；② 琉璃下 overlay 拿得到 `LocalBackdrop`（底栏那片玻璃靠它）；
 * ③ 门关上后**结构恒定**：content 与 overlay 都照常渲染（详情页底栏还要演退场动画），只是不再录层。
 *
 * ③ 直接驱动 [LiuliGatedBackdropHost]：`tick` 是宿主的内部计数，[LiuliHomeHost] 不暴露 `state` 形参
 * （它自己 remember 一个）。`internal` 成员在测试源集里跨包可读（friend module）。
 * **「不录层」本身 Robolectric 够不着**（不跑 draw 相位 → tick 恒 0）→ 挂装机（图纸 §11 D-3）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliHomeHostTest {

    @get:Rule
    val compose = createComposeRule()

    private fun host(skin: AppSkin, onBackdrop: (BackdropState?) -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = skin) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    val chrome = rememberLiuliHomeChrome()
                    LiuliHomeHost(
                        chrome = chrome,
                        active = true,
                        modifier = Modifier.fillMaxSize(),
                        bottomBar = {
                            onBackdrop(LocalBackdrop.current)
                            Text("底栏")
                        },
                    ) {
                        Text("内容")
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 暖陶下是纯Box且overlay没有背景宿主() {
        var seen: BackdropState? = null
        var called = false
        host(AppSkin.CLAY) { seen = it; called = true }
        compose.onNodeWithText("内容").assertIsDisplayed()
        compose.onNodeWithText("底栏").assertIsDisplayed()
        assertEquals("bottomBar 槽必须真的组合过", true, called)
        assertNull("暖陶下不该有 LocalBackdrop（一层都不录）", seen)
    }

    @Test fun 琉璃下overlay拿得到背景宿主() {
        var seen: BackdropState? = null
        host(AppSkin.LIULI) { seen = it }
        compose.onNodeWithText("内容").assertIsDisplayed()
        compose.onNodeWithText("底栏").assertIsDisplayed()
        assertNotNull("底栏那片玻璃靠它切模糊", seen)
    }

    /** 真 `LazyColumn` + 真底栏挂在宿主上，用手指滑（nested-scroll 全链）而不是直接调 `onPostScroll`。 */
    private fun hostWithList(rowCount: Int) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    val chrome = rememberLiuliHomeChrome()
                    LiuliHomeHost(
                        chrome = chrome,
                        active = true,
                        modifier = Modifier.fillMaxSize(),
                        bottomBar = {
                            LiuliTabBar(
                                items = listOf("聊天", "联系人", "动态", "我").mapIndexed { i, label ->
                                    AppBottomNavItem(icon = AppNavIcons.Chat, label = label, selected = i == 0, onClick = {})
                                },
                                chrome = chrome,
                            )
                        },
                    ) {
                        LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                            items((1..rowCount).toList()) { Box(Modifier.fillMaxWidth().height(78.dp)) { Text("行 $it") } }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 铺不满一屏的列表滑一下底栏不缩丸() {
        // E1：两行列表滚不动，手指从下往上划过空白——底栏必须仍是四槽（R1 🔴-3 装机实证过反例）。
        hostWithList(rowCount = 2)
        compose.onNodeWithTag("list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        listOf("聊天", "联系人", "动态", "我").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun 铺满一屏的列表滑一下底栏缩成小丸() {
        // 对照：真能滚的列表同一手势 → 只剩当前 Tab（缩丸信号沿 nested-scroll 树上报到宿主）。
        hostWithList(rowCount = 40)
        compose.onNodeWithTag("list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithText("聊天").assertIsDisplayed()
        listOf("联系人", "动态", "我").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test fun 关门后不再录层而overlay照常渲染() {
        val active = mutableStateOf(true)
        lateinit var state: BackdropState
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                state = rememberBackdropState()
                LiuliGatedBackdropHost(
                    modifier = Modifier.fillMaxSize(),
                    active = active.value,
                    state = state,
                    content = { Text("内容") },
                    overlay = { Text("底栏") },
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { active.value = false }
        compose.waitForIdle()
        // Robolectric 不跑 draw 相位（`tick` 恒 0·captureToImage 要真 window 且会把这个「draw 里写 state」的
        // 宿主拖成永不 idle）——「关门真的不录层」那半条挂装机（图纸 §11 D-3 / §7 装机 ⑤）。
        // 这里钉住能钉的那半条：**门关上后结构恒定**，content 与 overlay 都照常渲染（详情页底栏还要演退场动画）。
        compose.onNodeWithText("内容").assertIsDisplayed()
        compose.onNodeWithText("底栏").assertIsDisplayed()
        assertEquals("关门只关录层，不关任何一个槽", 0, state.tick)
    }
}
