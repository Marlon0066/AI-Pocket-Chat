package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.model.AppSkin
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
 * T2：步进器行（图纸 2026-09-06 卷五 A-4 ①·§8 C0）。
 *
 * 期望值从图纸那一行重新打字：钮 28 · 值槽 ≥ 44 · 到界那一枚禁用 · 每次点**恰一记**触觉 ·
 * 点的是**钮自己的坐标**不是行中心（卷四 R1 教训 ②：只点行中心量不出「行里的控件到底可不可点」）。
 *
 * cd 用的是暖陶 `AppStepper` 调用点的两枚键（`reply_rule_decrease` / `reply_rule_increase`），
 * 在 zh-rCN 下是「减少」/「增加」——这里重新打字钉住，改资源值必须同步改这里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliStepperRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private val changes = mutableListOf<Int>()

    private fun host(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides haptics) {
                    Column(Modifier.fillMaxWidth()) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun row(value: Int, range: IntRange = 1..5, enabled: Boolean = true, hint: String? = null) {
        host {
            LiuliGroup {
                LiuliStepperRow(
                    title = "最少几条",
                    value = value,
                    range = range,
                    valueText = "$value 条",
                    onChange = { changes += it },
                    hint = hint,
                    enabled = enabled,
                    divider = false,
                    modifier = Modifier.testTag("stepper"),
                )
            }
        }
    }

    @Test fun 点加号恰改一次且触觉恰一记() {
        row(value = 3)
        compose.onNodeWithContentDescription("增加").performClick()
        compose.waitForIdle()
        assertEquals(listOf(4), changes)
        // 圆钮自带 light()；本件不再补第二记（图纸 §11 D-4）。
        verify(exactly = 1) { haptics.light() }
        verify(exactly = 0) { haptics.selection() }
    }

    @Test fun 点减号恰改一次() {
        row(value = 3)
        compose.onNodeWithContentDescription("减少").performClick()
        compose.waitForIdle()
        assertEquals(listOf(2), changes)
    }

    @Test fun 到下界减号禁用上界加号禁用() {
        row(value = 1, range = 1..5)
        compose.onNodeWithContentDescription("减少").assertIsNotEnabled()
        compose.onNodeWithContentDescription("增加").assertHasClickAction()
        // 禁用的那枚点下去零回调（正向证据 = 另一枚点得动·防「点根本没落到钮上」的假绿）。
        compose.onNodeWithContentDescription("减少").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), changes)
        compose.onNodeWithContentDescription("增加").performClick()
        compose.waitForIdle()
        assertEquals(listOf(2), changes)
    }

    @Test fun 到上界加号禁用() {
        row(value = 5, range = 1..5)
        compose.onNodeWithContentDescription("增加").assertIsNotEnabled()
        compose.onNodeWithContentDescription("增加").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), changes)
    }

    @Test fun 整行禁用时两枚钮都禁用() {
        row(value = 3, enabled = false)
        compose.onNodeWithContentDescription("减少").assertIsNotEnabled()
        compose.onNodeWithContentDescription("增加").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 钮版位28值槽至少44且两钮不重叠() {
        row(value = 12, range = 1..99)
        val minus = compose.onNodeWithContentDescription("减少").getUnclippedBoundsInRoot()
        val plus = compose.onNodeWithContentDescription("增加").getUnclippedBoundsInRoot()
        // 语义节点是圆钮本身（48 触达框）；版位 28 由外层盒锁，故量「两钮中心距」验值槽宽。
        val minusCenter = (minus.left + minus.right).value / 2f
        val plusCenter = (plus.left + plus.right).value / 2f
        val gap = plusCenter - minusCenter
        // 中心距 = 半钮 14 + 值槽 ≥ 44 + 半钮 14 = ≥ 72；> 48 即两枚 48 触达框不重叠。
        assertTrue("两钮中心距 $gap 应 ≥ 72", gap >= 72f - 0.01f)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 无副标行高52() {
        row(value = 3)
        val one = compose.onNodeWithTag("stepper").getUnclippedBoundsInRoot()
        assertEquals(52f, (one.bottom - one.top).value, 0.01f)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 有副标行高64且副标在屏() {
        row(value = 3, hint = "连发几条算一轮")
        val two = compose.onNodeWithTag("stepper").getUnclippedBoundsInRoot()
        assertEquals(64f, (two.bottom - two.top).value, 0.01f)
        compose.onNodeWithText("连发几条算一轮", useUnmergedTree = true).assertExists()
    }
}
