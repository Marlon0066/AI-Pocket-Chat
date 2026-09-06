package com.situ.aichat.ui.liuli.chat.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.ui.chat.ChatSheetsState
import com.situ.aichat.ui.chat.ChatViewModel
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-17 第四例（图纸 §7 · E31 · 照抄源 F21 末段）：线下异常恢复弹窗的两版正文与三条出口。
 *
 * 为什么落在这里而不是 `LiuliDialogTest`（C6a）：这一例断言的是**分支选择**（`longAbsence` 决定
 * 用哪版文案）与三个 VM 出口的接线，主体住 [LiuliChatSheets]（C6b 才建）——§11-C6 偏差 D-C6-1。
 * 期望文案从规格反推重新打字（不 import 实现常量）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliChatSheetsTest {

    @get:Rule
    val compose = createComposeRule()

    private val shortBody = "上次的线下见面好像被中断了。要继续这次见面，还是结束它？"
    private val longBody = "离开挺久了，这次见面建议先告一段落——回忆会替你们收好。当然，也可以让 TA 陪你再待一会儿。"

    /** 3 小时是 `OfflineReturnPolicy.LONG_ABSENCE_MS` 的门槛：恰好 3h 不算长，3h+1ms 才算。 */
    private val threeHours = 3 * 60 * 60_000L

    private fun show(awayMs: Long?, vm: ChatViewModel) {
        every { vm.offlineRecoveryAwayMs } returns MutableStateFlow(awayMs)
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliChatSheets(
                        sheets = ChatSheetsState(),
                        viewModel = vm,
                        characterName = "小夏",
                        avatarPath = null,
                        coinBalance = 100,
                        customStickers = emptyList(),
                        offlineRecoveryVisible = true,
                        onOpenStickerManagement = {},
                    )
                }
            }
        }
    }

    @Test fun 短暂离开走短文案() {
        show(awayMs = threeHours, vm = mockk(relaxed = true))
        compose.onNodeWithText("继续上次的见面？").assertIsDisplayed()
        compose.onNodeWithText(shortBody).assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText(longBody)).fetchSemanticsNodes().size)
    }

    @Test fun 超长离开走引导结束的长文案() {
        show(awayMs = threeHours + 1, vm = mockk(relaxed = true))
        compose.onNodeWithText(longBody).assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText(shortBody)).fetchSemanticsNodes().size)
    }

    @Test fun 继续见面走continueMeetingFromRecovery恰一次() {
        val vm = mockk<ChatViewModel>(relaxed = true)
        show(awayMs = null, vm = vm)
        compose.onNodeWithText("继续见面").performClick()
        verify(exactly = 1) { vm.continueMeetingFromRecovery() }
        verify(exactly = 0) { vm.endMeetingFromRecovery() }
    }

    @Test fun 结束见面走endMeetingFromRecovery恰一次() {
        val vm = mockk<ChatViewModel>(relaxed = true)
        show(awayMs = null, vm = vm)
        compose.onNodeWithText("结束见面").performClick()
        verify(exactly = 1) { vm.endMeetingFromRecovery() }
        verify(exactly = 0) { vm.continueMeetingFromRecovery() }
    }
}
