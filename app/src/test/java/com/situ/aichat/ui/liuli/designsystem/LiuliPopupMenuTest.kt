package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp

/**
 * T2-16：琉璃弹出菜单 [LiuliPopupMenu] 的行为面（图纸 2026-09-05 卷二C §7 · §4.11）。
 *
 * 钉三件：两项都渲染得出、点一项 = 回调恰一次 + 菜单自己关掉（`onDismiss` 也恰一次）、行触达 ≥48
 * 而行版位仍是 40（§3.2 锁定值）。danger 项的红字色由 T1 对比测与装机担保，此处只钉它存在且可点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliPopupMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) { content() }
            }
        }
    }

    @Test fun 两项都渲染_含danger项() {
        show {
            LiuliPopupMenu(
                expanded = true,
                onDismiss = {},
                items = listOf(
                    LiuliMenuEntry("改期") {},
                    LiuliMenuEntry("取消约定", danger = true) {},
                ),
            )
        }
        compose.onNodeWithText("改期").assertIsDisplayed()
        compose.onNodeWithText("取消约定").assertIsDisplayed()
        // 行版位恒 40（§3.2）= 触达：紧贴的兄弟行不外溢（R2 🔴-1·外溢会让后一行抢前一行底边的点击）。
        val bounds = compose.onNodeWithText("改期").getUnclippedBoundsInRoot()
        assertEquals("菜单行高", 40f, (bounds.bottom - bounds.top).value, 0.5f)
    }

    /**
     * R2 🔴-1 回归锁：点「改期」**底边 2dp 之内**必须仍是「改期」——两行若各自外溢 48 触达，后一行
     * （「取消约定」·不可撤销）会盖进前一行底边，Compose 命中测试后声明者优先 → 点改期却取消了约定。
     */
    @Test fun 点前一行底边_不会串到后一行的danger项() {
        var reschedule = 0
        var cancel = 0
        show {
            LiuliPopupMenu(
                expanded = true,
                onDismiss = {},
                items = listOf(
                    LiuliMenuEntry("改期") { reschedule++ },
                    LiuliMenuEntry("取消约定", danger = true) { cancel++ },
                ),
            )
        }
        compose.onNodeWithText("改期").performTouchInput { click(Offset(centerX, height - 2.dp.toPx())) }
        assertEquals("点的是改期底边", 1, reschedule)
        assertEquals("绝不能串到取消约定", 0, cancel)
    }

    @Test fun 点一项_回调恰一次且菜单关闭恰一次() {
        var reschedule = 0
        var cancel = 0
        var dismissed = 0
        show {
            LiuliPopupMenu(
                expanded = true,
                onDismiss = { dismissed++ },
                items = listOf(
                    LiuliMenuEntry("改期") { reschedule++ },
                    LiuliMenuEntry("取消约定", danger = true) { cancel++ },
                ),
            )
        }
        compose.onNodeWithText("取消约定").performClick()
        assertEquals(0, reschedule)
        assertEquals(1, cancel)
        assertEquals("点完必须自己收起来", 1, dismissed)
    }

    @Test fun expanded为false时一个节点都不冒() {
        show {
            LiuliPopupMenu(expanded = false, onDismiss = {}, items = listOf(LiuliMenuEntry("改期") {}))
        }
        assertEquals(
            "收起态必须整棵子树都不在（Popup 不许留空 window）",
            0,
            compose.onAllNodes(hasText("改期")).fetchSemanticsNodes().size,
        )
    }
}
