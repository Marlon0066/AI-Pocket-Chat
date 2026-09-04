package com.situ.aichat.ui.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.test.core.app.ApplicationProvider
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-S：白瓷开关 [AppSwitch] 的行为（Robolectric·M3 清零收官图纸 §7）。
 *
 * 陶土渐变轨 / 22dp 恒白瓷拇指 / 2→20dp 滑行属像素域（由「参数逐字取自对版稿 + 装机浅深抽查」担保）；
 * 本测试钉行为面：`Role.Switch` + on/off 语义、点击回调与**触觉分支方向**（开脆关柔）、
 * `onCheckedChange = null` 与 `enabled = false` 两条不可点路径、reduceMotion 下终态仍正确。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class AppSwitchTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) { block() }
        }
    }

    private fun disableSystemAnimations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @Test
    fun S1_点一下掉回调_语义是开关且开关态跟着变_触觉分开脆关柔() {
        var checked by mutableStateOf(false)
        val seen = mutableListOf<Boolean>()
        content {
            AppSwitch(
                checked = checked,
                onCheckedChange = { seen += it; checked = it },
                modifier = Modifier.testTag("sw"),
            )
        }

        compose.onNodeWithTag("sw").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNodeWithTag("sw").assertIsOff()
        compose.onNodeWithTag("sw").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), seen)
        compose.onNodeWithTag("sw").assertIsOn()
        verify(exactly = 1) { haptics.light() }

        compose.onNodeWithTag("sw").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true, false), seen)
        compose.onNodeWithTag("sw").assertIsOff()
        // 方向感做在触觉里：开=脆(light)、关=柔(soft)，两者绝不能对调。
        verify(exactly = 1) { haptics.soft() }
    }

    @Test
    fun S2_onCheckedChange为null时_整件不挂任何可切换语义_读屏不会多一个焦点() {
        content {
            AppSwitch(checked = true, onCheckedChange = null, modifier = Modifier.testTag("sw"))
        }

        // 纯显示态与被替换的 M3 Switch 逐字同构：**一个 toggleable 节点都不该有**
        // （外层整行的 toggleable 才是唯一那个；这里若冒出第二个，就是 TalkBack 双焦点回归）。
        assertEquals(
            "onCheckedChange = null 时不许挂 toggleable —— 否则读屏树里多一个可切换节点",
            0,
            compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag("sw").performClick()
        compose.waitForIdle()
        verify(exactly = 0) { haptics.light() }
        verify(exactly = 0) { haptics.soft() }
    }

    @Test
    fun S2b_外层整行toggleable时_全树恰一个可切换节点() {
        var checked by mutableStateOf(false)
        content {
            androidx.compose.foundation.layout.Row(
                Modifier
                    .testTag("row")
                    .toggleable(value = checked, role = Role.Switch) { checked = it },
            ) {
                AppSwitch(checked = checked, onCheckedChange = null)
            }
        }

        assertEquals(1, compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
        compose.onNodeWithTag("row").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("row").assertIsOn()
    }

    @Test
    fun S3_enabled为false时点了零回调() {
        var calls = 0
        content {
            AppSwitch(
                checked = false,
                onCheckedChange = { calls++ },
                modifier = Modifier.testTag("sw"),
                enabled = false,
            )
        }

        compose.onNodeWithTag("sw").performClick()
        compose.waitForIdle()
        assertEquals("enabled = false 必须零回调", 0, calls)
        verify(exactly = 0) { haptics.light() }
    }

    @Test
    fun S4_关掉动画时切换_终态语义仍正确() {
        disableSystemAnimations()
        var checked by mutableStateOf(false)
        var reduceMotionSeen = false
        content {
            reduceMotionSeen = rememberReduceMotion()
            AppSwitch(
                checked = checked,
                onCheckedChange = { checked = it },
                modifier = Modifier.testTag("sw"),
            )
        }

        compose.waitForIdle()
        assertEquals("前提不成立：本例没跑在 reduceMotion = true 分支上", true, reduceMotionSeen)
        compose.onNodeWithTag("sw").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sw").assertIsOn()
        compose.onNodeWithTag("sw")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    @Test
    fun S5_是一个可切换节点_不是两个() {
        content {
            AppSwitch(checked = false, onCheckedChange = {}, modifier = Modifier.testTag("sw"))
        }

        // 自绘后轨与拇指都在同一个 toggleable 节点里——读屏不该看到两个可切换焦点。
        assertEquals(1, compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
    }
}
