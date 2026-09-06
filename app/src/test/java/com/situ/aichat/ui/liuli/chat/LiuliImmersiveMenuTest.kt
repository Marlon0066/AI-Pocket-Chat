package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.chat.ChatImmersiveMenuState
import com.situ.aichat.ui.chat.MessageRowActions
import com.situ.aichat.ui.chat.immersiveMenuActionLabel
import com.situ.aichat.ui.chat.immersiveMenuActions
import com.situ.aichat.ui.chat.messageCanBeQuoted
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-8 琉璃沉浸菜单（图纸 2026-09-05 卷二B §7）：顶行五个表情、动作行与 `immersiveMenuActions` 同序同文案、
 * 点表情 = 回应 + 收场、点删除 = 走 `MessageRowActions` 原路。
 *
 * 动作清单与文案是**单源**（`immersiveMenuActions` / `immersiveMenuActionLabel`·图纸 §9 ④），所以这里的
 * 期望值从那两个单源反推，而不是在测试里另抄一份文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliImmersiveMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private val actions = mockk<MessageRowActions>(relaxed = true)
    private val onDelete = mockk<(MessageEntity) -> Unit>(relaxed = true)
    private val onQuote = mockk<(MessageEntity) -> Unit>(relaxed = true)
    private val state = ChatImmersiveMenuState()
    private val reactions = mutableListOf<Pair<String, String>>()

    private val message = MessageEntity(
        messageUUID = "m1",
        conversationUuid = "c",
        roleRaw = "assistant",
        content = "在的，怎么了",
        timestamp = 1_700_000_000_000L,
    )

    private fun setMenu(canRegenerate: Boolean = true, bubble: Rect = BUBBLE, bottomObstructionPx: Int = 0) {
        every { actions.onDelete } returns onDelete
        every { actions.onQuote } returns onQuote
        // 快照留空 = 退纯压暗档（模拟器 / Robolectric 拿不到 PixelCopy·菜单卡本身不依赖它）。
        state.open(message, bubble, snapshot = null, frosted = null, canRegenerate = canRegenerate)
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                Box(Modifier.fillMaxSize()) {
                    LiuliImmersiveMenuOverlay(
                        state = state,
                        actions = actions,
                        reduceMotion = true,
                        onReact = { msg, emoji -> reactions += msg.messageUUID to emoji },
                        bottomObstructionPx = { bottomObstructionPx },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun keyboardUp_menuIsClampedAboveTheObstruction() {
        // E18（复核 R1 🟡-4 顺带钉在覆盖层这一级）：底部被占 300px 时，菜单底 ≤ 被占区顶 − 8（menuBubbleGap）。
        // 泡选在「无遮挡时菜单能挂泡下、被占 300 时挂不下」的高度，让钳位真的是遮挡造成的（对照组见下）。
        val bubble = Rect(40f, 400f, 300f, 460f)
        setMenu(bubble = bubble, bottomObstructionPx = OBSTRUCTION_PX)
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val deleteRow = compose.onNodeWithText(immersiveMenuActionLabel(com.situ.aichat.ui.chat.ImmersiveMenuAction.DELETE))
            .getUnclippedBoundsInRoot()
        val cardBottom = deleteRow.bottom.value + CARD_PADDING_DP
        val limit = root.bottom.value - OBSTRUCTION_PX - LiuliChatGeometry.menuBubbleGap.value
        assertTrue("菜单底（$cardBottom）应 ≤ 被占区顶 − 8（$limit）", cardBottom <= limit + 0.5f)
        assertTrue("挂不下就翻到泡上方", deleteRow.bottom.value <= bubble.top)
    }

    @Test fun noObstruction_sameBubbleHangsBelow() {
        val bubble = Rect(40f, 400f, 300f, 460f)
        setMenu(bubble = bubble, bottomObstructionPx = 0)
        val firstEmoji = compose.onNodeWithContentDescription("表情回应 ❤️").getUnclippedBoundsInRoot()
        assertTrue("无遮挡：菜单挂在泡下方（❤️ 顶 ${firstEmoji.top} > 泡底 ${bubble.bottom}）", firstEmoji.top.value > bubble.bottom)
    }

    private fun expectedEntries(canRegenerate: Boolean) = immersiveMenuActions(
        isUser = false,
        hasImage = false,
        canRegenerate = canRegenerate,
        canQuote = messageCanBeQuoted(message),
    )

    @Test fun reactionRow_showsTheFiveContractEmojis_inOrder() {
        setMenu()
        assertEquals("契约锁定的五个表情与顺序", listOf("❤️", "😂", "😮", "🥺", "👍"), LiuliMenuReactions)
        LiuliMenuReactions.forEach {
            compose.onNodeWithContentDescription("表情回应 $it").assertIsDisplayed()
        }
    }

    @Test fun actionRows_matchTheSingleSourceListAndLabels() {
        setMenu(canRegenerate = true)
        val entries = expectedEntries(canRegenerate = true)
        assertTrue("前提：这条 AI 消息应当给出复制 / 引用 / 重新生成 / 删除四项", entries.size == 4)
        entries.forEach { compose.onNodeWithText(immersiveMenuActionLabel(it)).assertIsDisplayed() }
    }

    @Test fun notTheLastTurn_dropsRegenerate() {
        setMenu(canRegenerate = false)
        compose.onNodeWithText(immersiveMenuActionLabel(com.situ.aichat.ui.chat.ImmersiveMenuAction.REGENERATE))
            .assertDoesNotExist()
        compose.onNodeWithText(immersiveMenuActionLabel(com.situ.aichat.ui.chat.ImmersiveMenuAction.DELETE))
            .assertIsDisplayed()
    }

    @Test fun tappingAnEmoji_reactsOnThatMessage_andClosesTheMenu() {
        setMenu()
        compose.onNodeWithContentDescription("表情回应 😂").performClick()
        compose.waitForIdle()
        assertEquals("回应打在被长按的那条消息上", listOf("m1" to "😂"), reactions)
        assertTrue("点完即收场（closing 或已彻底关掉）", state.closing || !state.isOpen)
    }

    @Test fun tappingDelete_goesThroughTheSameRowActions() {
        setMenu()
        compose.onNodeWithText(immersiveMenuActionLabel(com.situ.aichat.ui.chat.ImmersiveMenuAction.DELETE)).performClick()
        compose.waitForIdle()
        verify(exactly = 1) { onDelete(message) }
        verify(exactly = 0) { onQuote(any()) }
    }

    private companion object {
        val BUBBLE = Rect(40f, 300f, 300f, 360f)
        const val OBSTRUCTION_PX = 300
        /** 菜单卡内 padding 6（图纸 §3.2·卡底 = 末行底 + 6）。 */
        const val CARD_PADDING_DP = 6f
    }
}
