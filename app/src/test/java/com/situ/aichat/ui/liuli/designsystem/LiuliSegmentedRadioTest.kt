package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：孪生三件（图纸 2026-09-06 卷四 §8 C2 · §2.1 · 契约 §6.5「分段行 / 单选行」· A-6 选项卡）。
 *
 * 钉：选中语义（`assertIsSelected`）· 每段触达 ≥ 48（版位 30 · [liuliTouchHeight] 上下外溢）·
 * 切段 / 选中触觉 `selection()` **恰一次**且再点已选段不发 · 选项卡选中态与触达 ≥ 48。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliSegmentedRadioTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private val selectedMode = mutableStateOf("跟随系统")
    private var picks = 0

    private fun host(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides haptics) {
                    // 上下各留 12（= 分段行内距），让 48 触达框有地方外溢。
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), content = { content() })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun segmented() {
        host {
            LiuliSegmented(
                modifier = Modifier.testTag("seg"),
                options = listOf("跟随系统", "浅色", "深色"),
                selected = selectedMode.value,
                label = { it },
                onSelect = { selectedMode.value = it; picks++ },
            )
        }
    }

    @Test fun 分段选中语义与切段回调恰一次() {
        segmented()
        compose.onNodeWithText("跟随系统").assertIsSelected()
        compose.onNodeWithText("浅色").assertIsNotSelected()
        compose.onNodeWithText("浅色").performClick()
        compose.waitForIdle()
        assertEquals(1, picks)
        assertEquals("浅色", selectedMode.value)
        compose.onNodeWithText("浅色").assertIsSelected()
        verify(exactly = 1) { haptics.selection() }
    }

    @Test fun 再点已选中的段不发回调也不发触觉() {
        segmented()
        compose.onNodeWithText("跟随系统").performClick()
        compose.waitForIdle()
        assertEquals(0, picks)
        verify(exactly = 0) { haptics.selection() }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 分段版位36而每段触达48且外溢真能点() {
        segmented()
        // 版位：整条轨 36（契约 §6.5「分段行」）——触达框外溢不占版。
        val track = compose.onNodeWithTag("seg").getUnclippedBoundsInRoot()
        assertEquals(36f, (track.bottom - track.top).value, 0.01f)
        // 触达：段的可点节点恒 48 高（`liuliTouchHeight` 把内层量成 48 并上下居中外溢）。
        val cell = compose.onNodeWithText("浅色").getUnclippedBoundsInRoot()
        assertEquals(48f, (cell.bottom - cell.top).value, 0.01f)
        // 外溢那一段**真能点**（轨若还带着 clip，这一下就落空了）：点在触达框顶沿、即视觉上沿之上 9dp。
        compose.onNodeWithText("浅色").performTouchInput { click(Offset(centerX, 1f)) }
        compose.waitForIdle()
        assertEquals("浅色", selectedMode.value)
        assertEquals(1, picks)
    }

    @Test fun 单选圆选中语义与触觉() {
        val picked = mutableStateOf(false)
        host {
            Column(Modifier.selectableGroup()) {
                LiuliRadio(selected = picked.value, onClick = { picked.value = true })
            }
        }
        compose.onNode(androidx.compose.ui.test.isSelectable()).assertIsNotSelected()
        compose.onNode(androidx.compose.ui.test.isSelectable()).assertHeightIsAtLeast(48.dp)
        compose.onNode(androidx.compose.ui.test.isSelectable()).performClick()
        compose.waitForIdle()
        assert(picked.value)
        verify(exactly = 1) { haptics.selection() }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 选项卡选中语义与触达至少48() {
        val skin = mutableStateOf("琉璃")
        host {
            Column(Modifier.selectableGroup()) {
                LiuliOptionCard(
                    selected = skin.value == "暖陶",
                    onSelect = { skin.value = "暖陶" },
                    title = "暖陶",
                    subtitle = "釉烧陶土 · 白瓷钮",
                    swatchStart = Color(0xFFF6F3EF),
                    swatchEnd = Color(0xFFB4705E),
                )
                LiuliOptionCard(
                    selected = skin.value == "琉璃",
                    onSelect = { skin.value = "琉璃" },
                    title = "琉璃",
                    subtitle = "液态玻璃 · 钴蓝",
                    swatchStart = Color(0xFFF6F7FB),
                    swatchEnd = Color(0xFF2570E8),
                )
            }
        }
        compose.onNodeWithText("琉璃").assertIsSelected()
        compose.onNodeWithText("暖陶").assertIsNotSelected()
        compose.onNodeWithText("暖陶").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("暖陶").performClick()
        compose.waitForIdle()
        assertEquals("暖陶", skin.value)
        verify(exactly = 1) { haptics.selection() }
    }
}
