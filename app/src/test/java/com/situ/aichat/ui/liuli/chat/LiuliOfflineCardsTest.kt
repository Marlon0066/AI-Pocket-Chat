package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.model.OfflineInviteData
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-8 琉璃线下卡族（图纸 2026-09-05 卷二C §7 · E9）：邀约卡 `responded` 三态、结束确认卡两钮 /
 * 「已继续见面」、离场分隔线的「· 回顾」只在有 sessionId（= 调用方给了 `onClick`）时出现且可点。
 *
 * 文案期望值**在测试里重新打字**（F12 原字面），改一个字这里就红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliOfflineCardsTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private var accepts = 0
    private var declines = 0
    private var ends = 0
    private var continues = 0
    private var reviews = 0

    private fun invite(responded: String? = null) = OfflineInviteData(
        type = "offline_invite",
        location = "江边",
        activity = "去江边走走",
        invitation = "风刚好，别带伞",
        tensionHint = "有话想说",
        responded = responded,
    )

    private fun setInvite(responded: String? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliOfflineInviteCard(
                    data = invite(responded),
                    characterName = "云野",
                    onAccept = { accepts++ },
                    onDecline = { declines++ },
                )
            }
        }
    }

    private fun setEndCard(responded: String? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliOfflineEndCard(
                    data = invite(responded),
                    onEndMeeting = { ends++ },
                    onContinue = { continues++ },
                )
            }
        }
    }

    private fun setDivider(clickable: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliOfflineEndDivider(
                    durationText = "32 分钟",
                    onClick = if (clickable) ({ reviews++ }) else null,
                    entryAnimation = false,
                )
            }
        }
    }

    // ── 邀约卡三态 ────────────────────────────────────────────────────────────

    @Test fun invite_pending_showsTitleBodyAndTwoActions() {
        setInvite()
        compose.onNodeWithText("☕ 云野 想和你一起").assertIsDisplayed()
        compose.onNodeWithText("去江边走走").assertIsDisplayed()
        compose.onNodeWithText("📍 江边").assertIsDisplayed()
        compose.onNodeWithText("「风刚好，别带伞」").assertIsDisplayed()
        compose.onNodeWithText("✨ 有话想说").assertIsDisplayed()
        compose.onNodeWithText("好呀").assertIsDisplayed()
        compose.onNodeWithText("下次吧").assertIsDisplayed()
    }

    @Test fun invite_eachActionFiresExactlyOnce() {
        setInvite()
        compose.onNodeWithText("好呀").performClick()
        compose.onNodeWithText("下次吧").performClick()
        assertEquals(1, accepts)
        assertEquals(1, declines)
    }

    @Test fun invite_accepted_swapsButtonsForLabel() {
        setInvite("accepted")
        compose.onNodeWithText("已接受邀约").assertIsDisplayed()
        compose.onNodeWithText("好呀").assertDoesNotExist()
    }

    @Test fun invite_declined_swapsButtonsForLabel() {
        setInvite("declined")
        compose.onNodeWithText("已婉拒").assertIsDisplayed()
        compose.onNodeWithText("下次吧").assertDoesNotExist()
    }

    // ── 结束确认卡 ────────────────────────────────────────────────────────────

    @Test fun endCard_showsTwoActions_andNeverExitsByItself() {
        setEndCard()
        compose.onNodeWithText("要结束这次见面吗？").assertIsDisplayed()
        compose.onNodeWithText("结束见面").performClick()
        compose.onNodeWithText("再待一会儿").performClick()
        assertEquals(1, ends)
        assertEquals(1, continues)
    }

    @Test fun endCard_continued_showsLabelOnly() {
        setEndCard("continued")
        compose.onNodeWithText("已继续见面").assertIsDisplayed()
        compose.onNodeWithText("结束见面").assertDoesNotExist()
    }

    // ── 离场分隔线 ────────────────────────────────────────────────────────────

    @Test fun divider_withSession_offersReviewAndRoutesClick() {
        setDivider(clickable = true)
        compose.onNodeWithText("线下见面结束 · 32 分钟").assertIsDisplayed()
        compose.onNodeWithText("· 回顾").assertIsDisplayed()
        compose.onNodeWithText("· 回顾").performClick()
        assertEquals(1, reviews)
    }

    @Test fun divider_withoutSession_hasNoReviewAffordance() {
        setDivider(clickable = false)
        compose.onNodeWithText("线下见面结束 · 32 分钟").assertIsDisplayed()
        compose.onNodeWithText("· 回顾").assertDoesNotExist()
    }
}
