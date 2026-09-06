package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-17：琉璃确认弹窗 [LiuliDialog] / [LiuliDialogShell] 的行为面（图纸 2026-09-05 卷二C §7 · §4.11）。
 *
 * 钉四件：标题 + 说明真渲染；确认 / 取消各恰一次；两钮皆 null 时钮行整排不渲染（进行中 / 纯展示弹窗）；
 * `content` 自定义槽真渲染（贴纸长按预览走 [LiuliDialogShell]）。
 * 「恢复弹窗 longAbsence 两版文案」那一例落在 C6b 的 `LiuliChatSheetsTest`——它断言的是**分支选择**，
 * 主体（恢复弹窗）在 C6b 才建（§11-C6 偏差 D-C6-1）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) { content() }
            }
        }
    }

    @Test fun 标题与说明都渲染() {
        show {
            LiuliDialog(
                onDismissRequest = {},
                title = "送出这份 桂花糕？",
                body = "将从余额扣 120 金币",
                confirmText = "确认送出",
                dismissText = "取消",
            )
        }
        compose.onNodeWithText("送出这份 桂花糕？").assertIsDisplayed()
        compose.onNodeWithText("将从余额扣 120 金币").assertIsDisplayed()
    }

    @Test fun 确认与取消各恰一次() {
        var confirm = 0
        var dismiss = 0
        show {
            LiuliDialog(
                onDismissRequest = {},
                title = "标题",
                confirmText = "确认送出",
                onConfirm = { confirm++ },
                dismissText = "取消",
                onDismiss = { dismiss++ },
            )
        }
        compose.onNodeWithText("确认送出").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, confirm)
        assertEquals(1, dismiss)
    }

    @Test fun 两钮皆null时钮行整排不渲染() {
        show { LiuliDialog(onDismissRequest = {}, title = "只有标题", body = "只有说明") }
        compose.onNodeWithText("只有标题").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("取消")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("确认")).fetchSemanticsNodes().size)
    }

    @Test fun 裸壳的自定义内容槽真渲染() {
        show { LiuliDialogShell(onDismissRequest = {}) { Text("贴纸预览") } }
        compose.onNodeWithText("贴纸预览").assertIsDisplayed()
    }
}
