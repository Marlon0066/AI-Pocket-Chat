package com.situ.aichat.ui.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.situ.aichat.ui.components.rememberReduceMotion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1 = **T1 纯逻辑**（[coerceProgress] 的越界与 NaN·E-B7），P2/P3 = T2 渲染。
 *
 * 断言从图纸 §4.10 独立反推：钳到 0f..1f、NaN 归 0、`progress = 0f` 不画填充。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class AppProgressBarTest {

    @get:Rule
    val compose = createComposeRule()

    private fun disableSystemAnimations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @Test
    fun P1_钳位纯逻辑_越界与NaN() {
        assertEquals(0f, coerceProgress(-0.3f), 0f)
        assertEquals(0f, coerceProgress(0f), 0f)
        assertEquals(0.42f, coerceProgress(0.42f), 1e-6f)
        assertEquals(1f, coerceProgress(1f), 0f)
        assertEquals(1f, coerceProgress(7.5f), 0f)
        assertEquals("NaN 必须归 0，绝不能一路画下去", 0f, coerceProgress(Float.NaN), 0f)
        assertEquals(1f, coerceProgress(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, coerceProgress(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun P2_关掉动画时渲染不崩() {
        disableSystemAnimations()
        var reduceMotionSeen = false
        compose.setContent {
            reduceMotionSeen = rememberReduceMotion()
            AppProgressBar(progress = 0.6f, modifier = Modifier.testTag("bar").width(200.dp))
        }

        compose.waitForIdle()
        assertEquals("前提不成立：本例没跑在 reduceMotion = true 分支上", true, reduceMotionSeen)
        compose.onNodeWithTag("bar").assertIsDisplayed()
    }

    @Test
    fun P3_progress为0与NaN都不崩_轨仍在() {
        compose.setContent {
            AppProgressBar(progress = 0f, modifier = Modifier.testTag("zero").width(200.dp))
        }
        compose.onNodeWithTag("zero").assertIsDisplayed()
    }

    @Test
    fun P4_NaN喂进来也不崩() {
        compose.setContent {
            AppProgressBar(progress = Float.NaN, modifier = Modifier.testTag("nan").width(200.dp))
        }
        compose.onNodeWithTag("nan").assertIsDisplayed()
    }
}
