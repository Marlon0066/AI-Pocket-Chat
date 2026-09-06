package com.situ.aichat.ui.liuli.chat

import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.ui.chat.ChatRenderItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-3 末条尾巴判定（图纸 2026-09-05 卷二A §7 · §0 ② 7）：反转序里 index 0 恒带尾；其余看它与**更新的
 * 那一条**成不成组。断言从规格独立反推（连发段只有最后发出的那一条挂尾巴 = 微信 / Telegram 观感）。
 */
class LiuliBubbleTailTest {

    private var clock = 1_000_000L

    private fun msg(
        uuid: String,
        role: String,
        atMs: Long = clock,
        kind: MessageKind = MessageKind.PLAIN_TEXT,
    ) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "c",
        roleRaw = role,
        content = uuid,
        timestamp = atMs,
        messageKindRaw = kind.raw,
    )

    /** 按时间正序传入，返回反转序（index 0 = 最新）——与生产的 `renderItems.asReversed()` 同口径。 */
    private fun reversed(vararg messages: MessageEntity): List<ChatRenderItem> =
        messages.map { ChatRenderItem.Message(it, 0.dp) }.asReversed()

    @Test fun newestItem_alwaysHasTail() {
        val items = reversed(msg("a", "user"), msg("b", "user", clock + 1_000))
        assertTrue("index 0 恒是末条（打字占位同理）", isRunLast(items, 0))
    }

    @Test fun userRun_onlyLastOneHasTail() {
        // 时间正序 [用户 a, 用户 b, AI c] → 反转序 [c, b, a]。
        val items = reversed(
            msg("a", "user"),
            msg("b", "user", clock + 1_000),
            msg("c", "assistant", clock + 2_000),
        )
        assertTrue("index0 = AI c（最新）", isRunLast(items, 0))
        assertTrue("index1 = 用户 b：更新的一条是 AI → 换发送者 → 本段到此为止", isRunLast(items, 1))
        assertFalse("index2 = 用户 a：与更新的用户 b 同段 → 不挂尾", isRunLast(items, 2))
    }

    @Test fun timeBreak_splitsRun_soEarlierOneGetsTail() {
        // 同为用户、同为文字，但间隔 > 60s → 断层拆段 → 早的那条也成段尾。
        val items = reversed(
            msg("a", "user"),
            msg("b", "user", clock + 61_000),
        )
        assertTrue(isRunLast(items, 0))
        assertTrue("跨 60s 断层不成组 → a 自成一段、挂尾", isRunLast(items, 1))
    }

    @Test fun withinSixtySeconds_sameSender_staysOneRun() {
        val items = reversed(
            msg("a", "user"),
            msg("b", "user", clock + 59_000),
        )
        assertFalse("59s 内同发送者仍是一段", isRunLast(items, 1))
    }

    @Test fun cardBreaksRun_becauseCardsAreIslands() {
        // 卡片（礼物）是独立岛：既不与文字成组，也打断相邻文字段。
        val items = reversed(
            msg("a", "user"),
            msg("gift", "user", clock + 1_000, MessageKind.GIFT_CARD),
        )
        assertTrue("index1 = 文字 a：更新的一条是卡片 → 不成组 → 挂尾", isRunLast(items, 1))
    }

    @Test fun assistantRun_behavesSameAsUserRun() {
        val items = reversed(
            msg("a", "assistant"),
            msg("b", "assistant", clock + 1_000),
            msg("c", "assistant", clock + 2_000),
        )
        assertTrue(isRunLast(items, 0))
        assertFalse(isRunLast(items, 1))
        assertFalse(isRunLast(items, 2))
    }
}
