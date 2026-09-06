package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.theme.AIPocketChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「即现」弹层态（用户 2026-09-06：点「+」等滑入像卡住）：钉的是**行为**——真 `ModalBottomSheet` 挂上即现态，
 * 时钟冻结、一帧不推，弹层内容已经贴在屏底（首帧在位·无滑入）；对照例用 M3 默认态，同一帧内容还在屏外。
 * （独立复核 🟡-2：原版只回读构造参数，是自证式断言。）
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LiuliSheetStateTest {

    @get:Rule
    val compose = createComposeRule()

    private val bodyHeight = 300.dp
    private val screenHeight = 891.dp

    private fun showSheet(state: @Composable () -> SheetState) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                ModalBottomSheet(onDismissRequest = {}, sheetState = state()) {
                    Box(Modifier.fillMaxWidth().height(bodyHeight).testTag("body"))
                }
            }
        }
    }

    @Test fun 即现态首帧弹层内容已贴屏底() {
        showSheet { rememberLiuliInstantSheetState() }
        // 不推时钟：frame 0 的位置就是用户第一眼看到的位置。
        val b = compose.onNodeWithTag("body").getUnclippedBoundsInRoot()
        assertEquals("首帧底边 = 屏底（在位）", screenHeight.value, b.bottom.value, 0.5f)
        assertEquals("首帧顶边 = 屏底 − 内容高", (screenHeight - bodyHeight).value, b.top.value, 0.5f)
    }

    @Test fun 默认态首帧弹层内容还在屏外() {
        showSheet { rememberModalBottomSheetState(skipPartiallyExpanded = true) }
        val b = compose.onNodeWithTag("body").getUnclippedBoundsInRoot()
        assertEquals("默认态首帧在屏外（滑入起点）", true, b.top.value > screenHeight.value)
    }
}
