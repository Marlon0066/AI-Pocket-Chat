package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-14：琉璃弹层壳 [LiuliSheetShell] 的行为面（图纸 2026-09-05 卷二C §7）。
 *
 * 本件是**包壳**：拖拽 / 弹簧 / IME 让位交给 M3 [androidx.compose.material3.ModalBottomSheet]，
 * 测试只钉「题头按 title 开关、副标真渲染、关闭圆走回调、关闭圆触达 ≥48dp 而版位不长」。
 * 玻璃像素域（着色档退化）由装机担保。真机尺寸 qualifiers 防节点被推出可视区导致点击静默不命中
 * （PITFALLS §1e）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliSheetShellTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) { content() }
            }
        }
    }

    @Test fun 题头三件都在_点关闭圆恰一次() {
        var closed = 0
        show {
            LiuliSheetShell(
                onDismissRequest = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                title = "送礼物给 小夏",
                subtitle = "钱包余额 120 金币",
                onClose = { closed++ },
            ) { Text("礼物网格") }
        }
        compose.onNodeWithText("送礼物给 小夏").assertIsDisplayed()
        compose.onNodeWithText("钱包余额 120 金币").assertIsDisplayed()
        compose.onNodeWithText("礼物网格").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        assertEquals(1, closed)
    }

    @Test fun 关闭圆触达不低于48dp_而视觉只有26() {
        show {
            LiuliSheetShell(
                onDismissRequest = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                title = "标题",
            ) { Text("内容") }
        }
        // liuliFootprint：点击面撑满 48，版位只报 26（外溢居中·不把题头行顶高）。
        val bounds = compose.onNodeWithContentDescription("关闭").getUnclippedBoundsInRoot()
        assertTrue("关闭圆触达高 = ${bounds.bottom - bounds.top}", (bounds.bottom - bounds.top).value >= 47.5f)
        assertTrue("关闭圆触达宽 = ${bounds.right - bounds.left}", (bounds.right - bounds.left).value >= 47.5f)
    }

    @Test fun 不传onClose时关闭圆仍在_回调走onDismissRequest() {
        var dismissed = 0
        show {
            LiuliSheetShell(
                onDismissRequest = { dismissed++ },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                title = "标题",
            ) { Text("内容") }
        }
        compose.onNodeWithContentDescription("关闭").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun 无标题时题头整行不渲染_内容照旧() {
        show {
            LiuliSheetShell(
                onDismissRequest = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) { Text("只有内容") }
        }
        compose.onNodeWithText("只有内容").assertIsDisplayed()
        assertEquals(
            "无 title 时题头槽必须一个节点都不冒出来",
            0,
            compose.onAllNodes(hasContentDescription("关闭")).fetchSemanticsNodes().size,
        )
    }
}
