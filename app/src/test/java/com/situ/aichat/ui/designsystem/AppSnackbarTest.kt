package com.situ.aichat.ui.designsystem

import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-N：纸条 [AppSnackbarHost] 的行为（Robolectric·M3 清零收官图纸 §7）。
 *
 * 纸卡质感（appCardSurface + grain + 16dp 圆角）属像素域；本测试钉行为面：文案渲染、动作词点击真的
 * 走到 `performAction()`（`SnackbarResult.ActionPerformed` 回传给站点）、没有动作词时零动作节点、
 * 超长文案不崩。**排队与时长由 M3 宿主承担，本测试不重复验证它们**（包壳不重写的直接后果）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class AppSnackbarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun N1_文案渲染_点动作词回传ActionPerformed() {
        var result: SnackbarResult? = null
        compose.setContent {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                result = host.showSnackbar(message = "已从圈子移除", actionLabel = "撤销", duration = SnackbarDuration.Long)
            }
            Scaffold(snackbarHost = { AppSnackbarHost(host) }) { }
        }

        compose.onNodeWithText("已从圈子移除").assertIsDisplayed()
        compose.onNodeWithText("撤销").assertIsDisplayed()
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(3_000) { result != null }
        assertEquals(
            "点动作词必须走到 data.performAction()，站点侧才收得到 ActionPerformed",
            SnackbarResult.ActionPerformed,
            result,
        )
    }

    @Test
    fun N2_超长文案_渲染不崩且动作词没被挤出去() {
        val long = "这条提示故意写得很长很长，长到必须省略号才装得下，用来验证两行截断之后动作词还在屏幕里点得到"
        compose.setContent {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { host.showSnackbar(message = long, actionLabel = "重试", duration = SnackbarDuration.Long) }
            Scaffold(snackbarHost = { AppSnackbarHost(host) }) { }
        }

        compose.onNodeWithText(long).assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
    }

    @Test
    fun N3_没有动作词时_零动作节点() {
        compose.setContent {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { host.showSnackbar(message = "已保存", duration = SnackbarDuration.Long) }
            Scaffold(snackbarHost = { AppSnackbarHost(host) }) { }
        }

        compose.onNodeWithText("已保存").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("撤销")).fetchSemanticsNodes().size)
    }
}
