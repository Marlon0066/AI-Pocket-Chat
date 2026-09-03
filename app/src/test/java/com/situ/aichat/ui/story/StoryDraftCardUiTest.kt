package com.situ.aichat.ui.story

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 章末草稿卡 T2（图纸 2026-08-05 OL6·U-2）：显示门（beats 空/纯空白 → 整卡缺席·E12/E20）、
 * 两个 tag 分支（AI 预排 / 你已指定·E13）、整卡点击接的是**既有** onWriteClick（零新回调·J8）。
 * 断言经资源解析（locale 无关，照 [StoryArchivedDeleteUiTest] 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryDraftCardUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()

    private fun title() = app.getString(R.string.story_continue_draft_title)

    @Test
    fun 草稿卡_显示标题与正文_整卡点击走既有导演台回调() {
        var clicked = 0
        compose.setContent {
            StoryDraftCard(
                draftBeats = "温泉第二天清晨：林俐借晨雾单独约司徒散步。",
                draftUserEdited = false,
                isDark = true,
                onClick = { clicked++ },
            )
        }
        compose.onNodeWithText(title()).assertIsDisplayed()
        compose.onNodeWithText("温泉第二天清晨：林俐借晨雾单独约司徒散步。").assertIsDisplayed().performClick()
        assertEquals("整卡 clickable = 开导演台（本卷不许新增回调）", 1, clicked)
    }

    @Test
    fun 草稿卡_AI预排tag() { // E13
        compose.setContent {
            StoryDraftCard(draftBeats = "AI 的打算", draftUserEdited = false, isDark = true, onClick = {})
        }
        compose.onNodeWithText(app.getString(R.string.story_continue_draft_tag_ai)).assertIsDisplayed()
        assertTrue(
            "没改过时不许显示「你已指定」",
            compose.onAllNodesWithText(app.getString(R.string.story_continue_draft_tag_user))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun 草稿卡_你已指定tag_浅色纸面同构() { // E13
        compose.setContent {
            StoryDraftCard(draftBeats = "我指定的", draftUserEdited = true, isDark = false, onClick = {})
        }
        compose.onNodeWithText(app.getString(R.string.story_continue_draft_tag_user)).assertIsDisplayed()
        assertTrue(
            "改过之后不许再显示「AI 预排」",
            compose.onAllNodesWithText(app.getString(R.string.story_continue_draft_tag_ai))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    // ── 显示门：草稿卡住在推进区里，空 beats 时整卡缺席，推进区本体照常在（E12/E20）──

    @Test
    fun 推进区_beats非空才出草稿卡_空与纯空白都整卡缺席() {
        var beats by mutableStateOf<String?>("下一章打算写温泉戏")
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                StoryContinueZone(
                    isDark = true,
                    breatheTrigger = 0,
                    finaleProgress = null,
                    // 本例测的是草稿卡显示门，走态 A（现状）——与已存走向卷前的行为逐字节相同。
                    mode = ContinueZoneMode.NATURAL_FLOW,
                    directionText = null,
                    draftBeats = beats,
                    draftUserEdited = false,
                    onWriteClick = {},
                    onFlowClick = {},
                    onFinaleClick = {},
                    onCancelFinaleClick = {},
                )
            }
        }
        compose.onNodeWithText(title()).assertIsDisplayed()

        for (empty in listOf(null, "", "   ")) {
            compose.runOnIdle { beats = empty }
            compose.waitForIdle()
            assertTrue(
                "beats=<$empty> 时草稿卡必须整卡缺席",
                compose.onAllNodesWithText(title()).fetchSemanticsNodes().isEmpty(),
            )
            // 推进区本体照常在（缺的只是草稿卡，不是整个区）
            compose.onNodeWithText(app.getString(R.string.story_continue_director_hint)).assertIsDisplayed()
        }
    }
}
