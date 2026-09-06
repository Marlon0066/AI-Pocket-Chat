package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-15：琉璃四件表单原语（[LiuliField] / [LiuliSwitch] / [LiuliChip] / [LiuliSlider]）的行为面
 * （图纸 2026-09-05 卷二C §7 · §4.11）。
 *
 * 钉的是「能用 + 读屏读得出 + 触达够 48」：视觉（内衬 62% 底 / 渐变轨 / 白拇指）由装机担保。
 * 触达一律用 `getUnclippedBoundsInRoot`——版位被压到 32 / 26 / 20 时，只有未裁边界能看出点击面是否
 * 真外溢到 48（卷二C R1 🔴-1 的复发闸）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliFormPrimitivesTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides haptics) { content() }
            }
        }
    }

    // ── LiuliField ────────────────────────────────────────────────────────────────

    @Test fun 输入框_占位与前缀与supporting都渲染_输入回调透传() {
        var value = ""
        show {
            LiuliField(
                value = value,
                onValueChange = { value = it },
                label = "金额",
                placeholder = "随手心意",
                supportingText = "0/80",
                prefix = "¥",
                big = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        compose.onNodeWithText("金额").assertIsDisplayed()
        compose.onNodeWithText("随手心意").assertIsDisplayed()
        compose.onNodeWithText("0/80").assertIsDisplayed()
        compose.onNodeWithText("¥").assertIsDisplayed()
        compose.onNodeWithContentDescription("金额").performTextInput("66")
        assertEquals("66", value)
    }

    @Test fun 输入框_错误态supporting照出且不吞掉label() {
        show {
            Column {
                LiuliField(value = "", onValueChange = {}, label = "普通", supportingText = "正常", isError = false)
                LiuliField(value = "", onValueChange = {}, label = "出错", supportingText = "钱包余额不足", isError = true)
            }
        }
        // 色值断言留给 T1 对比测（`status.onError` × 玻璃合成底）；此处钉「错误文案真出现在 supporting 位」。
        compose.onNodeWithText("正常").assertIsDisplayed()
        compose.onNodeWithText("出错").assertIsDisplayed()
        compose.onNodeWithText("钱包余额不足").assertIsDisplayed()
    }

    // ── LiuliSwitch ───────────────────────────────────────────────────────────────

    @Test fun 开关_点击翻转并恰响一次触觉() {
        var checked = false
        show {
            LiuliSwitch(checked = checked, onCheckedChange = { checked = it })
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertIsOff()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).performClick()
        assertEquals(true, checked)
        verify(exactly = 1) { haptics.light() }
    }

    @Test fun 开关_开态语义为On_触达48而版位仍是44x26() {
        show { LiuliSwitch(checked = true, onCheckedChange = {}) }
        val node = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
        node.assertIsOn()
        node.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On),
        )
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue(
            "开关触达 ${bounds.right - bounds.left} x ${bounds.bottom - bounds.top}",
            (bounds.right - bounds.left).value >= 47.5f && (bounds.bottom - bounds.top).value >= 47.5f,
        )
    }

    // ── LiuliChip ─────────────────────────────────────────────────────────────────

    @Test fun chip_选中语义与点击回调恰一次() {
        var clicks = 0
        show { LiuliChip(selected = true, onClick = { clicks++ }, label = "手作") }
        compose.onNodeWithText("手作").assertIsSelected()
        compose.onNodeWithText("手作").performClick()
        assertEquals(1, clicks)
    }

    @Test fun chip_触达不低于48dp() {
        show { LiuliChip(selected = false, onClick = {}, label = "全部") }
        val bounds = compose.onNodeWithText("全部").getUnclippedBoundsInRoot()
        assertTrue("chip 触达高 ${bounds.bottom - bounds.top}", (bounds.bottom - bounds.top).value >= 47.5f)
    }

    // ── LiuliSlider ───────────────────────────────────────────────────────────────

    @Test fun 滑杆_setProgress语义能设值且按格吸附() {
        var value = 5f
        show {
            LiuliSlider(
                value = value,
                onValueChange = { value = it },
                valueRange = 2f..20f,
                steps = 17,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(11.4f) }
        // 2..20 分 18 格 = 每格恰 1 金币：11.4 吸到 11。
        assertEquals(11f, value, 0.001f)
    }

    @Test fun 滑杆_越界值被钳进值域且语义报出当前值() {
        var value = 5f
        show {
            LiuliSlider(
                value = value,
                onValueChange = { value = it },
                valueRange = 2f..20f,
                steps = 17,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val node = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        node.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(5f, 2f..20f, 17),
            ),
        )
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(99f) }
        assertEquals(20f, value, 0.001f)
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(-5f) }
        assertEquals(2f, value, 0.001f)
    }
}
