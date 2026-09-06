package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import org.robolectric.annotation.GraphicsMode

/**
 * T2：下拉行（图纸 2026-09-06 卷五 A-4 ②·§8 C0）。
 *
 * 钉四件：未点之前菜单不在场 · 点整行才展开（展开态由调用方持有 → 同屏多行天然互斥）· 点菜单项恰回调
 * 一次并自动收起 · 选中项在菜单里打勾。勾是无语义的纯装饰节点（`contentDescription = null`），所以不去
 * 「找勾」，而是量**选中那条的文字槽被勾挤窄了**——量真件不回读构造参数（PITFALLS §1e）。
 *
 * 行右值取「中档」而选项是「低 / 中 / 高」：同名会让 `onNodeWithText("中")` 一次命中行与菜单两个节点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliMenuRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val picked = mutableListOf<String>()
    private var expandedSeen = false

    private fun menuRow() {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    var expanded by remember { mutableStateOf(false) }
                    expandedSeen = expanded
                    Column(Modifier.fillMaxWidth()) {
                        LiuliGroup {
                            LiuliMenuRow(
                                title = "思考强度",
                                value = "中档",
                                options = listOf("低", "中", "高").map { opt ->
                                    LiuliMenuEntry(
                                        text = opt,
                                        selected = opt == "中",
                                        onClick = { picked += opt },
                                    )
                                },
                                expanded = expanded,
                                onExpandedChange = { expanded = it },
                                divider = false,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun count(text: String): Int = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test fun 未点之前菜单不在场() {
        menuRow()
        assertEquals(0, count("低"))
        assertEquals(0, count("高"))
        compose.onNodeWithText("中档").assertIsDisplayed()
        assertFalse(expandedSeen)
    }

    @Test fun 点整行展开菜单() {
        menuRow()
        compose.onNodeWithText("思考强度").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("低").assertIsDisplayed()
        compose.onNodeWithText("高").assertIsDisplayed()
        assertTrue(expandedSeen)
    }

    @Test fun 点菜单项恰回调一次并收起() {
        menuRow()
        compose.onNodeWithText("思考强度").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("高").performClick()
        compose.waitForIdle()
        assertEquals(listOf("高"), picked)
        assertFalse("选完应收起", expandedSeen)
        assertEquals(0, count("低"))
    }

    @Test fun 选中项打勾其余不打() {
        menuRow()
        compose.onNodeWithText("思考强度").performClick()
        compose.waitForIdle()
        // 量字必须走 unmerged 树：菜单行是 clickable = 合并边界，合并树里量到的是整行 160（PITFALLS §1e）。
        val selected = compose.onNodeWithText("中", useUnmergedTree = true).fetchSemanticsNode().size.width
        val plain = compose.onNodeWithText("低", useUnmergedTree = true).fetchSemanticsNode().size.width
        assertTrue("选中项的文字槽应被勾挤窄（选中 $selected · 未选 $plain）", selected < plain)
    }
}

/**
 * 卷五复核 R1 🔴 C1：右值是「服务商 模型名」长串时标题被挤成一字一行——右值须关在自己那一半里折行。
 * 走 NATIVE 图形模式：默认模式下假字宽 0、再长的串也不折行，量不出「挤没」（bitmap 测试同款开法）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliMenuRowLongValueTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 长右值不把标题挤没() {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Column(Modifier.fillMaxWidth().testTag("host")) {
                        LiuliGroup {
                            LiuliMenuRow(
                                title = "聊天对话",
                                subtitle = "主聊天用哪一套 API",
                                // 真实长串（≈330dp）：不加权时它一行量满、把标题列挤到几十 dp；加权对半分后只能在自己那一半里折行。
                                value = LONG_VALUE,
                                options = emptyList(),
                                expanded = false,
                                onExpandedChange = {},
                                divider = false,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val host = compose.onNodeWithTag("host").getUnclippedBoundsInRoot()
        val hostW = (host.right - host.left).value
        val title = compose.onNodeWithText("聊天对话", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val value = compose.onNodeWithText(LONG_VALUE, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val titleH = (title.bottom - title.top).value
        val valueH = (value.bottom - value.top).value
        // 标题仍是一行（≤ 30dp 高）。
        assertTrue("标题被折成多行：${titleH}dp", titleH <= 30f)
        // 右值被关在自己那一半里：左缘不越过行宽四成（不加权时它会量满一行、左缘跑到 ≈45dp）。
        assertTrue("右值越界侵占标题列：left=${value.left.value} / 行宽 $hostW", value.left.value >= hostW * 0.4f)
        // 且它确实折成了两行（单行 ≈20dp）——证明「窄了就折行」而不是「窄了就消失」。
        assertTrue("右值没有折行：${valueH}dp", valueH >= 34f)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 短右值不白占半行_副标不被挤() {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Column(Modifier.fillMaxWidth().testTag("host")) {
                        LiuliGroup {
                            LiuliMenuRow(
                                title = "聊天对话",
                                subtitle = SUBTITLE,
                                value = "默认",
                                options = emptyList(),
                                expanded = false,
                                onExpandedChange = {},
                                divider = false,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val host = compose.onNodeWithTag("host").getUnclippedBoundsInRoot()
        val hostW = (host.right - host.left).value
        val sub = compose.onNodeWithText(SUBTITLE, useUnmergedTree = true).getUnclippedBoundsInRoot()
        // 副标（≈ 300dp 长）应能占到行宽六成以上、最多折两行——对半硬分时它只有 ≈170dp 宽、被挤成三四行。
        assertTrue("副标列太窄：${(sub.right - sub.left).value} / 行宽 $hostW", (sub.right - sub.left).value >= hostW * 0.6f)
        assertTrue("副标被挤成多行：${(sub.bottom - sub.top).value}dp", (sub.bottom - sub.top).value <= 40f)
    }

    private companion object {
        const val LONG_VALUE = "OpenRouter anthropic/claude-3.5-sonnet-20241022"
        const val SUBTITLE = "与 AI 文字聊天时使用；思考模型回复更好但更慢，普通模型更秒回。"
    }
}
