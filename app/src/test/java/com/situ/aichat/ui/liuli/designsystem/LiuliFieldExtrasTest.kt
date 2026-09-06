package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.MacroHighlightTransformation
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue

/**
 * T2：内衬框的三枚增补槽（图纸 2026-09-06 卷五 A-4 ⑤ / ⑨·§8 C0「三处 add-only」）。
 *
 * **加法零回归**的钉：不传三槽时框高仍是常规 44（增补前行为）；传 `minHeight = 96` 才长到多行组的高。
 * `visualTransformation` 与 `textStyle` 走「传了不崩、文字照样在」的在场钉——宏着色是 `SpanStyle`
 * 层的事，Robolectric 的语义树里读不出颜色，真观感留装机批。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliFieldExtrasTest {

    @get:Rule
    val compose = createComposeRule()

    private fun field(
        value: String = "写点什么",
        minHeight: androidx.compose.ui.unit.Dp? = null,
        mono: Boolean = false,
        macros: Boolean = false,
    ) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Column(Modifier.fillMaxWidth().testTag("host")) {
                        LiuliField(
                            value = value,
                            onValueChange = {},
                            modifier = Modifier.testTag("field"),
                            singleLine = !(minHeight != null),
                            minHeight = minHeight,
                            visualTransformation = if (macros) {
                                MacroHighlightTransformation(AppSkinColorProbe)
                            } else {
                                androidx.compose.ui.text.input.VisualTransformation.None
                            },
                            textStyle = if (mono) TextStyle(fontFamily = FontFamily.Monospace) else null,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 不传minHeight时框高仍是常规44() {
        field()
        val h = compose.onNodeWithTag("field").getUnclippedBoundsInRoot()
        assertEquals(44f, (h.bottom - h.top).value, 0.01f)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 传minHeight96时长到96() {
        field(minHeight = 96.dp)
        val h = compose.onNodeWithTag("field").getUnclippedBoundsInRoot()
        assertEquals(96f, (h.bottom - h.top).value, 0.01f)
    }

    @Test fun 等宽槽与宏着色都不吞文字() {
        field(value = "开头 {{角色}} 结尾", mono = true, macros = true)
        compose.onNodeWithText("开头 {{角色}} 结尾", useUnmergedTree = true).assertExists()
    }
}

/** 宏着色只要一个颜色实参；测试不关心是哪一枚，取个恒定值即可。 */
private val AppSkinColorProbe = androidx.compose.ui.graphics.Color(0xFF2570E8)

/** 卷五复核 R1 🔴 A-1：兑换中 / 成功后输入框要真的不可编辑（`enabled = false`）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliFieldEnabledTest {

    @get:Rule
    val compose = createComposeRule()

    @Test fun 禁用时不可编辑且值不变() {
        val typed = mutableListOf<String>()
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliField(value = "AIC-1", onValueChange = { typed += it }, enabled = false, modifier = Modifier.testTag("field"))
                }
            }
        }
        compose.waitForIdle()
        // disabled 语义挂在内层 BasicTextField 上（外层 Box 只有 testTag，合并树里不一定带上来）——按未合并树找。
        compose.onNode(isNotEnabled(), useUnmergedTree = true).assertExists("输入框没有 disabled 语义")
        // 禁用的输入框没有 SetText 动作：往里打字必须失败，且回调一次不响。
        val inputRefused = runCatching { compose.onNodeWithTag("field").performTextInput("X") }.isFailure
        compose.waitForIdle()
        assertTrue("禁用后仍接受输入", inputRefused)
        assertEquals(emptyList<String>(), typed)
    }
}
