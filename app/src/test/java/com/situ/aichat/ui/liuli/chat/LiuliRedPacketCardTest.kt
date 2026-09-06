package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.data.model.RedPacketStatus
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
 * T2-5 琉璃红包卡（图纸 2026-09-05 卷二C §7）：四态的主 / 副文案上屏、整卡点击恰一次、合并朗读句逐字。
 *
 * **为什么全走 cd 断言**：卡整体压成一个 Button 停（`clearAndSetSemantics`·F9 钱路面口径照抄），
 * 卡内的文字节点在语义树上被有意清空——拿 `onNodeWithText` 断言卡内文字会恒空，那是假红不是真绿。
 * 状态胶囊单独直调组件断言（它不在被清空的子树里）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliRedPacketCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private var clicks = 0

    private fun packet(blessing: String = "", festivalId: String? = null) =
        RedPacketData(type = "red_packet", recordUUID = "r1", amount = 88, blessingText = blessing, festivalId = festivalId)

    private fun setCard(
        data: RedPacketData = packet(),
        isFromUser: Boolean = false,
        status: RedPacketStatus = RedPacketStatus.PENDING,
        festivalName: String? = null,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliRedPacketCard(
                    data = data,
                    isFromUser = isFromUser,
                    status = status,
                    festivalName = festivalName,
                    onClick = { clicks++ },
                )
            }
        }
    }

    @Test fun pending_readsFestivalTitleAndOpenHint() {
        setCard(festivalName = "春节")
        compose.onNodeWithContentDescription("红包，春节红包，点击拆开 🧧").assertIsDisplayed()
    }

    @Test fun pending_blessingWins_overFestival() {
        setCard(data = packet(blessing = "请你吃糖"), festivalName = "春节")
        compose.onNodeWithContentDescription("红包，请你吃糖，点击拆开 🧧").assertIsDisplayed()
    }

    @Test fun accepted_readsCollectedState() {
        setCard(status = RedPacketStatus.ACCEPTED)
        compose.onNodeWithContentDescription("红包，恭喜发财，已领取").assertIsDisplayed()
    }

    @Test fun expired_readsReturnedState() {
        setCard(status = RedPacketStatus.EXPIRED)
        compose.onNodeWithContentDescription("红包，恭喜发财，24 小时未拆,已退回").assertIsDisplayed()
    }

    @Test fun sentByUser_flipsDirectionWording() {
        setCard(isFromUser = true, status = RedPacketStatus.ACCEPTED)
        compose.onNodeWithContentDescription("红包，恭喜发财，对方已领取").assertIsDisplayed()
    }

    @Test fun click_firesExactlyOnce() {
        setCard(festivalName = "春节")
        compose.onNodeWithContentDescription("红包，春节红包，点击拆开 🧧").performClick()
        assertEquals(1, clicks)
    }

    /** 状态胶囊本体（非 pending 时卡底那一枚）：文案原样上屏。 */
    @Test fun statusPill_rendersGivenText() {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliRedPacketStatusPill(text = "已领取")
            }
        }
        compose.onNodeWithText("已领取").assertIsDisplayed()
    }
}
