package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.model.AppSkin
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
import com.situ.aichat.ui.liuli.page.LiuliGroup

/**
 * T1 + T2：可输入下拉（图纸 2026-09-06 卷五 A-4 ②′·§8 C0）。
 *
 * 筛选是纯函数，独立于 Compose 逐条反推（断言从图纸 A-4 ②′ 与暖陶 `ModelDropdownField`
 * `ApiConfigScreen.kt:552–567` 的规格重新打字，绝不照抄实现输出）：
 * 空查询 / 恰好选中 → 全量 · 否则 id **或**显示名 `contains` 忽略大小写 · 选中置顶 · 截到 listCap。
 */
class LiuliCatalogFilterTest {

    private val items = listOf(
        "openai/gpt-4o" to "GPT-4o",
        "openai/gpt-4o-mini" to "GPT-4o mini",
        "anthropic/claude-opus" to "Claude Opus",
        "deepseek-chat" to null,
    )

    @Test fun 空查询给全量() {
        assertEquals(items, filterCatalogItems("", items))
    }

    @Test fun 查询恰等于某个id时给全量且它置顶() {
        val out = filterCatalogItems("anthropic/claude-opus", items)
        assertEquals(items.size, out.size)
        assertEquals("anthropic/claude-opus", out.first().first)
    }

    @Test fun 中段匹配也算命中而不是只认前缀() {
        // 「gpt」不是 `openai/gpt-4o` 的前缀——前缀匹配会一条都搜不到（图纸 §11 D-6）。
        val out = filterCatalogItems("gpt", items)
        assertEquals(listOf("openai/gpt-4o", "openai/gpt-4o-mini"), out.map { it.first })
    }

    @Test fun 按显示名也能命中且忽略大小写() {
        assertEquals(listOf("anthropic/claude-opus"), filterCatalogItems("CLAUDE OP", items).map { it.first })
    }

    @Test fun 显示名为空时拿id当显示名参与匹配() {
        assertEquals(listOf("deepseek-chat"), filterCatalogItems("deepseek", items).map { it.first })
    }

    @Test fun 一条都没匹配上就给空表() {
        assertEquals(emptyList<Pair<String, String?>>(), filterCatalogItems("没有这个模型", items))
    }

    @Test fun 截到上限100() {
        val many = (1..250).map { "model-$it" to "Model $it" }
        assertEquals(100, filterCatalogItems("", many).size)
        assertEquals(LIULI_CATALOG_LIST_CAP, filterCatalogItems("", many).size)
    }

    @Test fun 选中项置顶排在截断之前故永不被截掉() {
        val many = (1..250).map { "model-$it" to "Model $it" }
        val out = filterCatalogItems("model-249", many)
        assertEquals(100, out.size)
        assertEquals("model-249", out.first().first)
    }
}

/**
 * T2：可输入下拉的壳（拉取钮 / 转圈 / 错误态 / 回填）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliCatalogFieldTest {

    @get:Rule
    val compose = createComposeRule()

    private var fetches = 0
    private val typed = mutableListOf<String>()

    private fun field(value: String = "", loading: Boolean = false, error: String? = null) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Column(Modifier.fillMaxWidth()) {
                        LiuliCatalogField(
                            value = value,
                            onValueChange = { typed += it },
                            label = "模型名",
                            items = listOf("openai/gpt-4o" to "GPT-4o"),
                            loading = loading,
                            error = error,
                            onFetch = { fetches++ },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 点拉取钮恰回调一次() {
        field()
        compose.onNodeWithContentDescription("拉取模型列表").performClick()
        compose.waitForIdle()
        assertEquals(1, fetches)
    }

    @Test fun 拉取中钮禁用且零回调() {
        field(loading = true)
        compose.onNodeWithContentDescription("拉取模型列表").assertIsNotEnabled()
        compose.onNodeWithContentDescription("拉取模型列表").performClick()
        compose.waitForIdle()
        assertEquals(0, fetches)
    }

    @Test fun 错误串落在辅助行上() {
        field(error = "拉取失败：401")
        compose.onNodeWithText("拉取失败：401", useUnmergedTree = true).assertExists()
        assertTrue(fetches == 0)
    }
}

/** 卷五复核 R1 A1 / D2：得焦且从没拉过 → 自动拉一次；已有候选时得焦不再拉、直接展开。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliCatalogFieldFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private var fetches = 0

    private fun field(items: List<Pair<String, String?>>) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Column(Modifier.fillMaxWidth()) {
                        LiuliGroup {
                            LiuliCatalogField(
                                value = "",
                                onValueChange = {},
                                label = "模型名",
                                items = items,
                                loading = false,
                                error = null,
                                onFetch = { fetches++ },
                                divider = false,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 没拉过时得焦自动拉一次() {
        field(emptyList())
        assertEquals(0, fetches)
        compose.onNodeWithContentDescription("模型名").performClick()
        compose.waitForIdle()
        assertEquals(1, fetches)
    }

    @Test fun 已有候选时得焦不拉而展开() {
        field(listOf("gpt-4o" to "GPT-4o", "o3" to null))
        compose.onNodeWithContentDescription("模型名").performClick()
        compose.waitForIdle()
        assertEquals(0, fetches)
        compose.onNodeWithText("GPT-4o").assertExists()
    }
}
