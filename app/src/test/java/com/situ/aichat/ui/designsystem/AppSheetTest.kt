package com.situ.aichat.ui.designsystem

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-S：底部弹层托盘 [AppSheet] 的行为（Robolectric·M3 清零卷一图纸 §7）。
 *
 * 本件是**包壳**：手势/弹簧交给 M3，测试只钉「内容真渲染、回调真接通、sheetState 真透传、题头槽按 title 开关」。
 * 覆盖图纸 §5 边界 E6（sheetState 配置透传）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
// qualifiers 钉 zh-rCN：关闭钮的 a11y 文案取自 R.string.action_close，顺带把「zh/en 成对」（图纸 E11）验掉。
@Config(sdk = [34], qualifiers = "zh-rCN")
class AppSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) { block() }
        }
    }

    @Test
    fun S1_内容原样渲染() {
        content {
            AppSheet(onDismissRequest = {}) {
                Text("挑一张壁纸")
            }
        }

        compose.onNodeWithText("挑一张壁纸").assertIsDisplayed()
    }

    @Test
    fun S2_站点的sheetState配置照样透传_skipPartiallyExpanded不崩() {
        content {
            AppSheet(
                onDismissRequest = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Text("直接全展开的弹层")
            }
        }

        compose.onNodeWithText("直接全展开的弹层").assertIsDisplayed()
    }

    @Test
    fun S3_题头行_标题与关闭钮都在_点关闭走回调() {
        var closed = 0
        content {
            AppSheet(onDismissRequest = {}, title = "选择角色", onClose = { closed++ }) {
                Text("列表内容")
            }
        }

        compose.onNodeWithText("选择角色").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun S4_不传title时_题头行整行不渲染() {
        content {
            AppSheet(onDismissRequest = {}, onClose = {}) {
                Text("只有内容")
            }
        }

        compose.onNodeWithText("只有内容").assertIsDisplayed()
        assertEquals(
            "收编 32 站一律不传 title——题头槽必须一个节点都不冒出来",
            0,
            compose.onAllNodes(hasContentDescription("关闭")).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun S5_关闭圆点不传onClose时不渲染_只剩标题() {
        content {
            AppSheet(onDismissRequest = {}, title = "只有标题") {
                Text("内容")
            }
        }

        compose.onNodeWithText("只有标题").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("关闭")).fetchSemanticsNodes().size)
    }
}
