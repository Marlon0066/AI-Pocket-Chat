package com.situ.aichat.ui.liuli.chat.sheets

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import org.robolectric.shadows.ShadowDialog

/**
 * T2-22：琉璃版「发起线下见面」表单（图纸 2026-09-05 卷二C §7 · E25 · 照抄源 F26 前半）。
 *
 * 最要紧的一条是 `committed` 旗标的分叉：**未提交**就关 → 通知 AI（`onCancel`）；**提交过**就关
 * → 一次都不许回调 `onCancel`（否则 AI 会同时收到「约好了」和「他反悔了」两条相反的提示）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliOfflineManualMeetingSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var started = mutableListOf<Pair<String, String>>()
    private var cancels = 0
    private var dismisses = 0

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) { content() }
            }
        }
    }

    private fun sheet() = show {
        LiuliOfflineManualMeetingSheet(
            onStart = { l, a -> started += l to a },
            onDismiss = { dismisses++ },
            onCancel = { cancels++ },
        )
    }

    /**
     * 触发弹层自己的「关闭请求」：M3 的 ModalBottomSheet 起在一枚独立 `ModalBottomSheetDialogWrapper`
     * 窗口里、带自己的返回分发器（Activity 那只**够不着**它）。下滑关闭与返回键走同一个 onDismissRequest，
     * 而拖拽在 Robolectric 里没有真手势，故取返回键这条等价且确定的入口。
     */
    private fun pressSheetBack() {
        val window = ShadowDialog.getLatestDialog() as ComponentDialog
        compose.runOnUiThread { window.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun locationField() = compose.onAllNodes(hasSetTextAction()).onFirst()
    private fun activityField() = compose.onAllNodes(hasSetTextAction())[1]

    @Test fun 两框任一为空就见不了面() {
        sheet()
        compose.onNodeWithText("见面！").assertIsNotEnabled()
        locationField().performTextInput("公园")
        compose.onNodeWithText("见面！").assertIsNotEnabled()
    }

    @Test fun 提交走trim后的两值且不通知取消() {
        sheet()
        locationField().performTextInput("  公园 ")
        activityField().performTextInput(" 散步 ")
        compose.onNodeWithText("见面！").performClick()
        assertEquals(listOf("公园" to "散步"), started)
        assertEquals("提交过就绝不回调 onCancel", 0, cancels)
        assertEquals(1, dismisses)
        // committed 旗标真在承重：提交之后再走一次关闭请求，也一次都不许通知「他反悔了」。
        pressSheetBack()
        assertEquals("提交后关闭仍不许回调 onCancel", 0, cancels)
    }

    @Test fun 点取消同时通知AI并关闭() {
        sheet()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, cancels)
        assertEquals(1, dismisses)
        assertEquals(emptyList<Pair<String, String>>(), started)
    }

    @Test fun 未提交就返回键关闭_通知AI恰一次() {
        sheet()
        locationField().performTextInput("公园")
        // 「下滑关闭」与返回键走的是 ModalBottomSheet 的同一个 onDismissRequest（拖拽在 Robolectric
        // 里没有真手势，返回键是等价且确定的入口）。
        pressSheetBack()
        assertEquals("未提交就关 = 恰一次 onCancel（1:1 iOS .sheet onCancel）", 1, cancels)
        assertEquals(emptyList<Pair<String, String>>(), started)
    }
}
