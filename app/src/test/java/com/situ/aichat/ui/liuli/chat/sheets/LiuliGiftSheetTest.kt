package com.situ.aichat.ui.liuli.chat.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.situ.aichat.data.model.GiftCategory
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.gift.GiftItem
import com.situ.aichat.gift.GiftSendService
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

/**
 * T2-20：琉璃版聊天内送礼底片（图纸 2026-09-05 卷二C §7 · E23 · 照抄源 F23）。**钱路只显示不改**。
 *
 * 钉：DIY 入口按分类显隐、确认框两行文案、确认 → `onSendGift` 恰一次、余额不足 / 送礼失败两条文案、
 * 成功 500ms 后才关（`mainClock` 推时钟——不许提前关，那 500ms 是给余额滚动看的）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliGiftSheetTest {

    @get:Rule
    val compose = createComposeRule()

    /** 目录第一件礼物（不写死名字·目录改了测试跟着走）。 */
    private val firstItem: GiftItem = GiftCatalog.allItems.first()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) { content() }
            }
        }
    }

    private fun sheet(
        balance: Int = 9999,
        onSendGift: suspend (GiftItem) -> GiftSendService.InChatSendOutcome = { GiftSendService.InChatSendOutcome.SpendFailed },
        onDismiss: () -> Unit = {},
    ) = show {
        LiuliGiftSheet(
            characterName = "小夏",
            avatarPath = null,
            balance = balance,
            onSendGift = onSendGift,
            onSendDiy = { _, _, _, _ -> GiftSendService.InChatSendOutcome.SpendFailed },
            onDismiss = onDismiss,
        )
    }

    @Test fun 头行与送给行都在() {
        sheet()
        compose.onNodeWithText("送礼物给 小夏").assertIsDisplayed()
        compose.onNodeWithText("送给 小夏").assertIsDisplayed()
    }

    @Test fun DIY入口在全部与手作两档才出现() {
        sheet()
        compose.onNodeWithText("创建我的 DIY").assertIsDisplayed()
        // 切到一个非「手作」分类 → DIY 入口整格消失（照抄 F23 `showDiyEntry`）。
        val other = GiftCategory.entries.first { it != GiftCategory.HANDMADE }
        compose.onNodeWithText(other.displayName).performScrollTo().performClick()
        assertEquals(
            "DIY 入口只属「全部」与「手作」",
            0,
            compose.onAllNodes(hasText("创建我的 DIY")).fetchSemanticsNodes().size,
        )
        // 切回「全部」→ 入口回来（HANDMADE 那枚 chip 在 LazyRow 右侧、未必已组合，故用恒在最左的「全部」验回程）。
        compose.onNodeWithText("全部").performClick()
        compose.onNodeWithText("创建我的 DIY").assertIsDisplayed()
    }

    @Test fun 点礼物弹确认框两行文案() {
        sheet()
        compose.onNodeWithText(firstItem.name).performScrollTo().performClick()
        compose.onNodeWithText("送出这份 ${firstItem.name}？").assertIsDisplayed()
        compose.onNodeWithText("将从余额扣 ${firstItem.price} 金币").assertIsDisplayed()
    }

    @Test fun 确认送出走onSendGift恰一次() {
        val sent = mutableListOf<GiftItem>()
        sheet(onSendGift = { sent += it; GiftSendService.InChatSendOutcome.SpendFailed })
        compose.onNodeWithText(firstItem.name).performScrollTo().performClick()
        compose.onNodeWithText("确认送出").performClick()
        compose.waitForIdle()
        assertEquals(listOf(firstItem), sent)
    }

    @Test fun 余额不足与送礼失败各自的文案() {
        sheet(onSendGift = { GiftSendService.InChatSendOutcome.InsufficientCoins(need = 120, have = 20) })
        compose.onNodeWithText(firstItem.name).performScrollTo().performClick()
        compose.onNodeWithText("确认送出").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("余额不足，还差 100 金币").assertIsDisplayed()
    }

    @Test fun 送礼失败文案() {
        sheet(onSendGift = { GiftSendService.InChatSendOutcome.SpendFailed })
        compose.onNodeWithText(firstItem.name).performScrollTo().performClick()
        compose.onNodeWithText("确认送出").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("送礼失败，请稍后重试").assertIsDisplayed()
    }
}
