package com.situ.aichat.ui.designsystem

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.SettingsSwitchRow
import io.mockk.mockk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 乙 1·T2-B1：四单源件 + 开关的触觉接线（Robolectric 跑真 Compose 树 + MockK 假 [AppHaptics]）。
 *
 * 验的是「点下去到底响哪一档」——手感本体属真机域，但**档位映射错没错**在这儿就能钉死。
 * 断言从 §4.B 方案表的大白话反推：普通钮轻「嗒」、警告钮沉一点、开关开脆关柔、步进器到顶「咚」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComponentHapticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) { block() }
        }
    }

    // ---- H1 AppButton ----

    @Test
    fun 普通按钮_点一下轻轻嗒一记() {
        content { AppButton(onClick = {}) { Text("好的") } }
        compose.onNodeWithText("好的").performClick()

        verify(exactly = 1) { haptics.light() }
        verify(exactly = 0) { haptics.medium() }
    }

    @Test
    fun 警告按钮_点一下沉一点() {
        content { AppButton(onClick = {}, style = AppButtonStyle.Warning) { Text("删除") } }
        compose.onNodeWithText("删除").performClick()

        verify(exactly = 1) { haptics.medium() }
        verify(exactly = 0) { haptics.light() }
    }

    @Test
    fun 文字钮也震_用户原话都加上() {
        content { AppButton(onClick = {}, style = AppButtonStyle.Text) { Text("取消") } }
        compose.onNodeWithText("取消").performClick()

        verify(exactly = 1) { haptics.light() }
    }

    @Test
    fun 禁用的按钮_点不动也不许震() {
        content { AppButton(onClick = {}, enabled = false) { Text("不可点") } }
        compose.onNodeWithText("不可点").performClick()

        verify(exactly = 0) { haptics.light() }
        verify(exactly = 0) { haptics.medium() }
    }

    @Test
    fun 按钮触觉先于回调_不因回调抛异常而丢震() {
        var clicked = false
        content { AppButton(onClick = { clicked = true }) { Text("确认") } }
        compose.onNodeWithText("确认").performClick()

        verify(exactly = 1) { haptics.light() }
        assert(clicked) { "原 onClick 必须照常触发（触觉只是加一层，不许吞回调）" }
    }

    // ---- H5 AppSwitch ----

    @Test
    fun 开关打开_是脆的() {
        content { AppSwitch(checked = false, onCheckedChange = {}) }
        compose.onNode(isToggleableNode()).performClick()

        verify(exactly = 1) { haptics.light() }
        verify(exactly = 0) { haptics.soft() }
    }

    @Test
    fun 开关关闭_是柔的() {
        content { AppSwitch(checked = true, onCheckedChange = {}) }
        compose.onNode(isToggleableNode()).performClick()

        verify(exactly = 1) { haptics.soft() }
        verify(exactly = 0) { haptics.light() }
    }

    @Test
    fun E10_纯显示态开关_透传null零触觉零包装() {
        content { AppSwitch(checked = true, onCheckedChange = null) }
        // 纯显示态自己不可点：点了也不该有任何触觉（且不崩）。
        compose.onAllNodes(isToggleableNode()).let { if (it.fetchSemanticsNodes().isNotEmpty()) it[0].performClick() }

        verify(exactly = 0) { haptics.light() }
        verify(exactly = 0) { haptics.soft() }
    }

    // ---- H6 SettingsSwitchRow ----

    @Test
    fun 设置开关行_开脆关柔同口径() {
        content { SettingsSwitchRow(title = "自动备份", checked = false, onCheckedChange = {}) }
        compose.onNodeWithText("自动备份").performClick()
        verify(exactly = 1) { haptics.light() }
    }

    @Test
    fun 设置开关行_关时走柔() {
        content { SettingsSwitchRow(title = "自动备份", checked = true, onCheckedChange = {}) }
        compose.onNodeWithText("自动备份").performClick()
        verify(exactly = 1) { haptics.soft() }
    }

    // ---- H4 AppStepper ----

    @Test
    fun 步进器_普通一格是选择档() {
        content {
            AppStepper(
                value = 3, valueText = "3", range = 1..5, onValueChange = {},
                increaseDescription = "加一", decreaseDescription = "减一",
            )
        }
        compose.onNodeWithContentDescription("加一").performClick()

        verify(exactly = 1) { haptics.selection() }
        verify(exactly = 0) { haptics.medium() }
    }

    @Test
    fun 步进器_加到顶那一下咚一声撞墙() {
        content {
            AppStepper(
                value = 4, valueText = "4", range = 1..5, onValueChange = {},
                increaseDescription = "加一", decreaseDescription = "减一",
            )
        }
        compose.onNodeWithContentDescription("加一").performClick() // 4 → 5 = range.last

        verify(exactly = 1) { haptics.medium() }
        verify(exactly = 0) { haptics.selection() }
    }

    @Test
    fun 步进器_减到底那一下也撞墙() {
        content {
            AppStepper(
                value = 2, valueText = "2", range = 1..5, onValueChange = {},
                increaseDescription = "加一", decreaseDescription = "减一",
            )
        }
        compose.onNodeWithContentDescription("减一").performClick() // 2 → 1 = range.first

        verify(exactly = 1) { haptics.medium() }
        verify(exactly = 0) { haptics.selection() }
    }

    private fun isToggleableNode() =
        androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.ToggleableState,
        )
}
