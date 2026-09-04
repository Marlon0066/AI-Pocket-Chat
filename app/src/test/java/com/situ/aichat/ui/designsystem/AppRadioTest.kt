package com.situ.aichat.ui.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * T2-R：陶珠单选 [AppRadio] 的行为（Robolectric·M3 清零收官图纸 §7）。
 *
 * 20dp / 1.5dp 描边 / 7dp 中点 / 陶土渐变属像素域；本测试钉行为面：`Role.RadioButton` 与选中语义、
 * 点击回调与触觉、`onClick = null` 的纯显示态、reduceMotion 下不崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class AppRadioTest {

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
    fun R1_点一下掉回调_语义是单选按钮且已选中() {
        var clicks = 0
        content {
            AppRadio(selected = true, onClick = { clicks++ }, modifier = Modifier.testTag("radio"))
        }

        compose.onNodeWithTag("radio")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.onNodeWithTag("radio").assertIsSelected()
        compose.onNodeWithTag("radio").performClick()
        assertEquals(1, clicks)
        verify(exactly = 1) { haptics.light() }
    }

    @Test
    fun R2_onClick为null时点了零回调_也不挂可选语义() {
        content {
            AppRadio(selected = false, onClick = null, modifier = Modifier.testTag("radio"))
        }

        compose.onNodeWithTag("radio").assertIsDisplayed()
        // 纯显示态（外层整行 selectable 接管点击）：本件不该在读屏树里再冒一个可选节点。
        assertEquals(0, compose.onAllNodes(isSelectable()).fetchSemanticsNodes().size)
        compose.onNodeWithTag("radio").performClick()
        verify(exactly = 0) { haptics.light() }
    }

    @Test
    fun R3_关掉动画时渲染不崩_点击照样掉回调() {
        disableSystemAnimations()
        var clicks = 0
        var reduceMotionSeen = false
        content {
            reduceMotionSeen = rememberReduceMotion()
            AppRadio(selected = false, onClick = { clicks++ }, modifier = Modifier.testTag("radio"))
        }

        compose.waitForIdle()
        assertEquals("前提不成立：本例没跑在 reduceMotion = true 分支上", true, reduceMotionSeen)
        compose.onNodeWithTag("radio").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun R3b_enabled为false时点了零回调() {
        var clicks = 0
        content {
            AppRadio(selected = false, onClick = { clicks++ }, modifier = Modifier.testTag("radio"), enabled = false)
        }

        compose.onNodeWithTag("radio").performClick()
        assertEquals("enabled = false 必须零回调", 0, clicks)
    }
}
