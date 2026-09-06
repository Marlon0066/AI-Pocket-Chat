package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.gift.GiftCatalog
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
 * T2-4 琉璃礼物卡（图纸 2026-09-05 卷二C §7）：无障碍句逐字（F9 四段拼法）、以及「只有用户 DIY 且
 * 调用方给了 [onDiyClick] 才可点」这道门。
 *
 * cd 期望值**在测试里重新打字**为字面量（不引实现串），改一个字这里就红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliGiftCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private var diyClicks = 0

    private fun gift(
        itemId: String = "gift_osmanthus_cake",
        name: String = "桂花糕",
        cost: Int = 120,
        handmade: Boolean = false,
    ) = GiftCardData(
        type = "gift_card",
        giftItemId = itemId,
        giftRecordId = "rec-1",
        cost = cost,
        giftName = name,
        isHandmade = handmade,
    )

    private fun setCard(data: GiftCardData, isFromUser: Boolean, onDiyClick: (() -> Unit)?) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliGiftCard(data = data, isFromUser = isFromUser, diyImage = null, onDiyClick = onDiyClick)
            }
        }
    }

    @Test fun receivedGift_readsMergedSentence() {
        setCard(gift(), isFromUser = false, onDiyClick = null)
        compose.onNodeWithContentDescription("收到礼物 桂花糕，心意 120 金币").assertIsDisplayed()
    }

    @Test fun sentGift_flipsDirectionWord() {
        setCard(gift(), isFromUser = true, onDiyClick = null)
        compose.onNodeWithContentDescription("送出礼物 桂花糕，心意 120 金币").assertIsDisplayed()
    }

    @Test fun handmadeGift_addsSuffix() {
        setCard(gift(handmade = true), isFromUser = false, onDiyClick = null)
        compose.onNodeWithContentDescription("收到礼物 桂花糕，手作，心意 120 金币").assertIsDisplayed()
    }

    @Test fun presetGift_isNotClickable_evenWhenCallbackGiven() {
        setCard(gift(), isFromUser = false, onDiyClick = { diyClicks++ })
        compose.onNodeWithContentDescription("收到礼物 桂花糕，心意 120 金币").assertHasNoClickAction()
        assertEquals(0, diyClicks)
    }

    @Test fun diyGift_withCallback_isClickableAndAnnouncesIt() {
        val diy = gift(itemId = "${GiftCatalog.userDIYIdPrefix}abc", name = "小画", cost = 50, handmade = true)
        setCard(diy, isFromUser = false, onDiyClick = { diyClicks++ })
        val node = compose.onNodeWithContentDescription("收到礼物 小画，手作，心意 50 金币，点击查看")
        node.assertHasClickAction()
        node.performClick()
        assertEquals(1, diyClicks)
    }

    @Test fun diyGift_withoutCallback_dropsClickHint() {
        val diy = gift(itemId = "${GiftCatalog.userDIYIdPrefix}abc", name = "小画", cost = 50)
        setCard(diy, isFromUser = false, onDiyClick = null)
        compose.onNodeWithContentDescription("收到礼物 小画，心意 50 金币").assertHasNoClickAction()
    }
}
