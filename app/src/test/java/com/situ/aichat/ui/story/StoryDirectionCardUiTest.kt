package com.situ.aichat.ui.story

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 章末「你的走向」卡 T2（图纸 2026-08-06「已存走向」§7 T2-3·边界 E12）：
 * 标题 / tag / 正文三件可见、整卡点击接的是**既有** onWriteClick（开导演台）、超长走向不崩。
 * 断言经资源解析（locale 无关，照 [StoryDraftCardUiTest] 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryDirectionCardUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()

    private fun title() = app.getString(R.string.story_continue_direction_title)
    private fun tag() = app.getString(R.string.story_continue_direction_tag)

    @Test
    fun 走向卡_标题与tag与正文都可见_整卡点击开导演台() {
        var clicked = 0
        compose.setContent {
            StoryDirectionCard(
                directionText = "让她在温泉旅馆偶遇两人，别急着摊牌。",
                isDark = true,
                onClick = { clicked++ },
            )
        }
        compose.onNodeWithText(title()).assertIsDisplayed()
        compose.onNodeWithText(tag()).assertIsDisplayed()
        compose.onNodeWithText("让她在温泉旅馆偶遇两人，别急着摊牌。").assertIsDisplayed().performClick()
        assertEquals("整卡 clickable = 开导演台（编辑模式预填这条走向）", 1, clicked)
    }

    /** 浅色纸面同构（两档色源都走 StoryReaderLayout，长相不随 App 主题变）。 */
    @Test
    fun 走向卡_浅色纸面同构() {
        compose.setContent {
            StoryDirectionCard(directionText = "浅纸上的走向", isDark = false, onClick = {})
        }
        compose.onNodeWithText(title()).assertIsDisplayed()
        compose.onNodeWithText(tag()).assertIsDisplayed()
        compose.onNodeWithText("浅纸上的走向").assertIsDisplayed()
    }

    /**
     * E12：走向超过 4 行 → 卡上省略号截断（全文仍在导演台里可编辑）。
     * 这里钉的是**渲染面**：超长文本不崩、卡照常可点（截断本身是 maxLines + Ellipsis 的框架行为）。
     */
    @Test
    fun E12_超长走向_不崩且整卡仍可点() {
        var clicked = 0
        val long = (1..40).joinToString("，") { "第${it}件事要写清楚" }
        compose.setContent {
            StoryDirectionCard(directionText = long, isDark = true, onClick = { clicked++ })
        }
        compose.onNodeWithText(title()).assertIsDisplayed().performClick()
        assertEquals(1, clicked)
    }
}
