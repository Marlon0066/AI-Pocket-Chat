package com.situ.aichat.ui.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T1+T2：分段控件 [AppSegmentedControl] 的**状态机与语义**（「白瓷药丸」复核 R1 🟡-1 补齐）。
 *
 * 材质本身（釉面/影/内阴影）是纯绘制，靠 [ColorContrastTest] 锁色 + 装机截图对版；本件钉的是**装机看不出、
 * 一改就静默坏**的三件：① 按压索引状态机的两条语义（多指乱序保持、无按压归 -1）；② `Role.Tab` + `selected`
 * 无障碍语义；③ 选择回调真接通 + 触感真触发。
 *
 * 屏尺寸钉 `w411dp-h891dp`（记忆 `reference-robolectric-screen-size-fake-green`：默认屏太小会让元素落到
 * 可视区外，点击静默不命中 → 假绿）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class AppSegmentedControlBehaviorTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    // ── T1：按压索引状态机（纯函数·药丸是否下沉的唯一判据）──

    @Test
    fun `按下某段则记录该段`() {
        assertEquals(2, nextPressedIndex(current = -1, index = 2, isPressed = true))
    }

    @Test
    fun `松开当前记录的那段则归无按压`() {
        assertEquals(-1, nextPressedIndex(current = 2, index = 2, isPressed = false))
    }

    /**
     * 多指乱序：按 tab0 → 按 tab1 → **先松 tab0**。松开的不是当前记录的那段，必须保持不变。
     * 写成 `else -1` 会让药丸在另一指仍按住时提前弹起；反过来若 tab1 后松也不能把 -1 覆盖回去。
     */
    @Test
    fun `松开的不是当前记录那段则保持不变`() {
        assertEquals(1, nextPressedIndex(current = 1, index = 0, isPressed = false))
    }

    @Test
    fun `后按的段覆盖先按的段`() {
        assertEquals(1, nextPressedIndex(current = 0, index = 1, isPressed = true))
    }

    /** 首帧各段各发一次 `false`：无按压态下任何松开事件都不该把状态推离 -1。 */
    @Test
    fun `无按压时收到任意松开事件仍为无按压`() {
        assertEquals(-1, nextPressedIndex(current = -1, index = 0, isPressed = false))
        assertEquals(-1, nextPressedIndex(current = -1, index = 2, isPressed = false))
    }

    // ── T2：语义与回调（Robolectric 真组合）──

    @Composable
    private fun Host(onSelect: (String) -> Unit) {
        var selected by remember { mutableStateOf("月") }
        CompositionLocalProvider(LocalAppHaptics provides haptics) {
            AppSegmentedControl(
                options = listOf("年", "月", "周"),
                selected = selected,
                onSelect = { selected = it; onSelect(it) },
                label = { it },
            )
        }
    }

    @Test
    fun `每段带 Tab 角色与选中语义`() {
        compose.setContent { Host {} }
        val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNodeWithText("年").assert(isTab).assertIsNotSelected()
        compose.onNodeWithText("月").assert(isTab).assertIsSelected()
        compose.onNodeWithText("周").assert(isTab).assertIsNotSelected()
    }

    @Test
    fun `点击未选中段切换选中并回调`() {
        var picked: String? = null
        compose.setContent { Host { picked = it } }

        compose.onNodeWithText("周").performClick()

        assertEquals("周", picked)
        compose.onNodeWithText("周").assertIsSelected()
        compose.onNodeWithText("月").assertIsNotSelected()
    }

    @Test
    fun `点击触发选择触感`() {
        compose.setContent { Host {} }
        compose.onNodeWithText("年").performClick()
        verify(exactly = 1) { haptics.selection() }
    }

    /** 禁用态：语义仍在，但点击不改选中、不回调。 */
    @Test
    fun `禁用态点击不生效`() {
        var picked: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                AppSegmentedControl(
                    options = listOf("创建副本", "覆盖已有", "跳过"),
                    selected = "创建副本",
                    onSelect = { picked = it },
                    enabled = false,
                    label = { it },
                )
            }
        }

        compose.onNodeWithText("跳过").performClick()

        assertEquals(null, picked)
        compose.onNodeWithText("创建副本").assertIsSelected()
    }
}
