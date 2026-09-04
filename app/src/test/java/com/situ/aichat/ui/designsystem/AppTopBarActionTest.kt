package com.situ.aichat.ui.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
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
 * T2-A：白瓷圆钮 [AppTopBarAction] 的行为（Robolectric·M3 清零收官图纸 §7）。
 *
 * 釉面 / 暖边 / 接触影属像素域（由「[Modifier.porcelainThumb] 参数复用 + 装机浅深抽查」担保）；本测试钉
 * 行为面：可读名与 `Role.Button` 语义、点击回调与触觉、关动画时仍可点、RTL 下不崩且回调仍达。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class AppTopBarActionTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) { block() }
        }
    }

    /** 关掉系统动画 = [com.situ.aichat.ui.components.rememberReduceMotion] 为真（它读的就是这个 Setting）。 */
    private fun disableSystemAnimations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @Test
    fun A1_渲染出可读名与按钮语义_点一下掉回调也掉触觉() {
        var clicked = 0
        content {
            AppTopBarAction(icon = AppTopBarIcons.Add, contentDescription = "新建对话", onClick = { clicked++ })
        }

        compose.onNodeWithContentDescription("新建对话").assertIsDisplayed()
        compose.onNodeWithContentDescription("新建对话")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNodeWithContentDescription("新建对话").performClick()
        assertEquals(1, clicked)
        verify(exactly = 1) { haptics.light() }
    }

    @Test
    fun A2_三枚自绘图标各自都渲染得出来() {
        content {
            Column {
                AppTopBarAction(icon = AppTopBarIcons.Back, contentDescription = "返回", onClick = {})
                AppTopBarAction(icon = AppTopBarIcons.Add, contentDescription = "新建", onClick = {})
                AppTopBarAction(icon = AppTopBarIcons.More, contentDescription = "更多", onClick = {})
            }
        }

        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        compose.onNodeWithContentDescription("新建").assertIsDisplayed()
        compose.onNodeWithContentDescription("更多").assertIsDisplayed()
    }

    @Test
    fun A3_关掉动画时按压不缩_但点击照样掉回调() {
        disableSystemAnimations()
        var clicked = 0
        var reduceMotionSeen = false
        content {
            // 前提自检：证明这条用例真的跑在 reduceMotion = true 的分支上，而不是设置没生效的假绿。
            reduceMotionSeen = rememberReduceMotion()
            AppTopBarAction(icon = AppTopBarIcons.More, contentDescription = "更多", onClick = { clicked++ })
        }

        compose.waitForIdle()
        assertEquals("前提不成立：ANIMATOR_DURATION_SCALE=0 没能让 rememberReduceMotion 返回 true", true, reduceMotionSeen)
        compose.onNodeWithContentDescription("更多").performClick()
        assertEquals("reduceMotion 只关缩放动画，绝不能连点击一起关掉", 1, clicked)
    }

    @Test
    fun A4_RTL下渲染不崩_回调仍达() {
        var clicked = 0
        content {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AppTopBarAction(icon = AppTopBarIcons.Back, contentDescription = "返回", onClick = { clicked++ })
            }
        }

        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, clicked)
    }
}
