package com.situ.aichat.ui.liuli.chat.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import com.situ.aichat.gift.GiftSendService
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
 * T2-21：琉璃版 DIY 手作创作底片（图纸 2026-09-05 卷二C §7 · E24 · 照抄源 F24）。**钱路只显示不改**。
 *
 * 钉：标题 / 内容的**截断计数**（超长保留前 N 字而不是整段拒绝——那是 gift-6 修过的手感 bug）、
 * `canSend` 门、滑杆 2..20 钳位（A-20 明令保持滑杆）、确认框 `ifEmpty{"手作礼物"}` 兜底、成功走 `onSuccess`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliDiyGiftCreationSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) { content() }
            }
        }
    }

    private fun sheet(
        onSend: suspend (String, String, android.net.Uri?, Int) -> GiftSendService.InChatSendOutcome =
            { _, _, _, _ -> GiftSendService.InChatSendOutcome.SpendFailed },
        onSuccess: () -> Unit = {},
    ) = show {
        LiuliDiyGiftCreationSheet(onSend = onSend, onSuccess = onSuccess, onDismiss = {})
    }

    private fun titleField() = compose.onAllNodes(hasSetTextAction()).onFirst()
    private fun contentField() = compose.onAllNodes(hasSetTextAction())[1]
    private fun slider() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    @Test fun 标题超长截断到12字且计数跟着走() {
        sheet()
        titleField().performTextInput("一二三四五六七八九十甲乙丙丁")
        compose.onNodeWithText("12/12").assertIsDisplayed()
    }

    @Test fun 内容超长截断到300字() {
        sheet()
        contentField().performTextInput("字".repeat(400))
        compose.onNodeWithText("300/300").assertIsDisplayed()
    }

    @Test fun 两框有一个空就送不出() {
        sheet()
        compose.onNodeWithText("送出").performScrollTo().assertIsNotEnabled()
        titleField().performTextInput("糖")
        compose.onNodeWithText("送出").performScrollTo().assertIsNotEnabled()
    }

    @Test fun 滑杆钳在2到20之间() {
        sheet()
        // 断言走滑杆自己的 ProgressBarRangeInfo（读屏读到的那个值）而不是「金币」那行文字是否可见——
        // 弹层里那行在 Robolectric 窗口下会落在可视区外，可见性断言与本例要钉的钳位无关。
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(99f) }
        slider().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(20f, 2f..20f, 17),
            ),
        )
        compose.onNodeWithText(" 20 金币").assertExists()
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(-5f) }
        slider().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(2f, 2f..20f, 17),
            ),
        )
        compose.onNodeWithText(" 2 金币").assertExists()
    }

    @Test fun 确认框标题空时兜底手作礼物_成功走onSuccess() {
        var success = 0
        val sent = mutableListOf<Triple<String, String, Int>>()
        sheet(
            onSend = { t, c, _, cost ->
                sent += Triple(t, c, cost)
                GiftSendService.InChatSendOutcome.Success(mockk(relaxed = true), mockk(relaxed = true))
            },
            onSuccess = { success++ },
        )
        titleField().performTextInput("桂花糕")
        contentField().performTextInput("给你做的")
        compose.onNodeWithText("送出").performScrollTo().performClick()
        compose.onNodeWithText("送出这份 桂花糕？").assertIsDisplayed()
        compose.onNodeWithText("将从余额扣 5 金币").assertIsDisplayed()
        compose.onNodeWithText("确认送出").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("桂花糕", "给你做的", 5)), sent)
        assertEquals(1, success)
    }
}
