package com.situ.aichat.ui.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import com.situ.aichat.ui.components.rememberReduceMotion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-R：陶环 [AppLoadingRing] 的行为（Robolectric·M3 清零收官图纸 §7）。
 *
 * sweep 渐变色标 / 3.5dp 环宽 / 1s 匀速属像素与时间域（由「参数逐字取自对版稿 + 装机实看」担保）；
 * 本测试钉行为面：三档尺寸都渲染得出来不崩、关动画时节点仍在（静态 3/4 环那条分支不炸）、
 * 有可读名时挂得上语义、没传就是纯装饰。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class AppLoadingRingTest {

    @get:Rule
    val compose = createComposeRule()

    private fun disableSystemAnimations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @Test
    fun R4_三档尺寸都渲染不崩_可读名挂得上() {
        compose.setContent {
            Column {
                AppLoadingRing(size = AppLoadingRingSize.Large, contentDescription = "大环")
                AppLoadingRing(size = AppLoadingRingSize.Medium, contentDescription = "中环")
                AppLoadingRing(size = AppLoadingRingSize.Small, contentDescription = "小环")
            }
        }

        compose.onNodeWithContentDescription("大环").assertIsDisplayed()
        compose.onNodeWithContentDescription("中环").assertIsDisplayed()
        compose.onNodeWithContentDescription("小环").assertIsDisplayed()
    }

    @Test
    fun R4b_不传可读名时是纯装饰_读屏树里没有它() {
        compose.setContent { AppLoadingRing(contentDescription = null) }

        assertEquals(
            "contentDescription = null 就该是装饰件，不该在读屏树里冒出节点",
            0,
            compose.onAllNodes(
                androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsProperties.ContentDescription,
                ),
            ).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun R5_关掉动画时_静态环分支照样渲染得出来() {
        disableSystemAnimations()
        var reduceMotionSeen = false
        compose.setContent {
            reduceMotionSeen = rememberReduceMotion()
            AppLoadingRing(size = AppLoadingRingSize.Medium, contentDescription = "静态环")
        }

        compose.waitForIdle()
        assertEquals("前提不成立：本例没跑在 reduceMotion = true 分支上", true, reduceMotionSeen)
        // 走的是 270° 静态环那一支（不转）——它必须仍然在屏上，否则关动画的人看不出「在忙」。
        compose.onNodeWithContentDescription("静态环").assertIsDisplayed()
    }
}
