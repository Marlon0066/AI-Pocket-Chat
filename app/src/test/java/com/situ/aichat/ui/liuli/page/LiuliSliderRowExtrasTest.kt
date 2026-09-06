package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import com.situ.aichat.data.model.AppSkin
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
import androidx.compose.ui.test.getUnclippedBoundsInRoot

/**
 * T2：滑杆行的两枚增补槽（图纸 2026-09-06 卷五 A-3·§8 C0「三处 add-only」）。
 *
 * 加法零回归的钉：**两槽都不传时，ⓘ 与手填弹窗一个节点都不出**（旧调用点逐字节同渲染）；
 * 传了才出。手填的钳位逐字照暖陶 `SettingsSliderRow`：只留数字 → `toIntOrNull()` 且 `>= 0` 才回调，
 * 且**可以超过滑杆上限**（上限钳位在各 setter 里，不在这一层）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliSliderRowExtrasTest {

    @get:Rule
    val compose = createComposeRule()

    private val manual = mutableListOf<Int>()

    private fun row(info: String? = null, withManual: Boolean = false) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Column(Modifier.fillMaxWidth()) {
                        LiuliGroup {
                            LiuliSliderRow(
                                title = "保留多少条",
                                valueLabel = "30",
                                value = 30f,
                                onValueChange = {},
                                valueRange = 10f..100f,
                                steps = 8,
                                divider = false,
                                info = info,
                                onManualInput = if (withManual) ({ manual += it }) else null,
                                modifier = Modifier.testTag("slider"),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 两槽都不传时零增量节点() {
        row()
        assertEquals(0, compose.onAllNodesWithContentDescription("说明").fetchSemanticsNodes().size)
        // 右值在场但不可点（不传 onManualInput = 与增补前同）。
        compose.onNodeWithText("30").assertExists()
        compose.onNodeWithText("30").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), manual)
        assertEquals(0, compose.onAllNodesWithText("手动输入").fetchSemanticsNodes().size)
    }

    @Test fun 传info时出说明钮点开弹窗标题是本行标题() {
        row(info = "这一档决定注入多少条历史。")
        compose.onNodeWithContentDescription("说明").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("这一档决定注入多少条历史。").assertExists()
        // 图纸 A-3：弹窗标题 = 本行标题（不是「说明」）。
        assertEquals(2, compose.onAllNodesWithText("保留多少条").fetchSemanticsNodes().size)
    }

    @Test fun 手填只留数字且可超上限() {
        row(withManual = true)
        compose.onNodeWithText("30").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("手动输入").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("12a8")
        compose.waitForIdle()
        compose.onNodeWithText("确定").performClick()
        compose.waitForIdle()
        // 「12a8」滤成「128」——超过滑杆上限 100 也照写（钳位在 setter 侧·暖陶同语义）。
        assertEquals(listOf(128), manual)
    }

    @Test fun 手填空串不回调() {
        row(withManual = true)
        compose.onNodeWithText("30").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextReplacement("abc")
        compose.waitForIdle()
        compose.onNodeWithText("确定").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), manual)
        // 正向证据：弹窗确已关闭 = 「确定」那一下真点到了（防全否定假绿·PITFALLS §1e）。
        assertEquals(0, compose.onAllNodesWithText("手动输入").fetchSemanticsNodes().size)
        compose.onNodeWithTag("slider").assertExists()
    }
}

/**
 * 卷五复核 R1 🔴：有手填槽的滑杆行右值曾比标题高一截（`liuliTouchHeight` 直接挂在 Text 上、没有居中盒）。
 * 量真件：右值与标题的**竖向中心**必须重合（容差 1dp）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliSliderRowValueAlignTest {

    @get:Rule
    val compose = createComposeRule()

    private fun row(withManual: Boolean) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Column(Modifier.fillMaxWidth()) {
                        LiuliGroup {
                            LiuliSliderRow(
                                title = "对话轮数",
                                valueLabel = "30 轮",
                                value = 30f,
                                onValueChange = {},
                                valueRange = 1f..100f,
                                steps = 98,
                                divider = false,
                                onManualInput = if (withManual) ({}) else null,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun centerY(text: String): Float {
        val b = compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()
        return ((b.top + b.bottom) / 2).value
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 有手填槽时右值与标题竖向居中对齐() {
        row(withManual = true)
        assertEquals(centerY("对话轮数"), centerY("30 轮"), 1f)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 无手填槽时右值与标题竖向居中对齐() {
        row(withManual = false)
        assertEquals(centerY("对话轮数"), centerY("30 轮"), 1f)
    }
}
