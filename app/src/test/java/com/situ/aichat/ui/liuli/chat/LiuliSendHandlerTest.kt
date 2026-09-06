package com.situ.aichat.ui.liuli.chat

import androidx.compose.ui.geometry.Rect
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.chat.ChatSendFlightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2-5 发送通路（图纸 2026-09-05 卷二B §7 · A-7）：`liuliSendHandler` 与**真** `ChatSendFlightState`
 * 合起来的三种走向。核心断言是「清空输入框（commit）在什么时候发生」——A-7 把它从「受理即清」
 * 押后到「新气泡就位那一帧」，闸关时才退回同帧清。
 */
class LiuliSendHandlerTest {

    private val now = 1_000L
    private val state = ChatSendFlightState(nowMs = { now })
    private var commits = 0
    private var accepted = 0

    private fun handle(text: String, sendOk: Boolean, gatesOpen: Boolean): Boolean =
        liuliSendHandler(
            text = text,
            send = { sendOk },
            gatesOpen = gatesOpen,
            sendFlight = state,
            commit = { commits++ },
            onAccepted = { accepted++ },
        )

    private fun landed(text: String) = MessageEntity(
        messageUUID = "m1",
        conversationUuid = "c",
        roleRaw = "user",
        content = text,
        timestamp = now + 1,
    )

    @Test fun rejectedSend_neverClearsInput_andNeverCountsATurn() {
        assertFalse(handle("在吗", sendOk = false, gatesOpen = true))
        assertEquals("发送被拒 → 绝不清空输入框（E7）", 0, commits)
        assertEquals("也不绕心情四色", 0, accepted)
        assertNull("更不该进握手", state.pending)
    }

    @Test fun gatesClosed_clearsImmediately_noHandshake() {
        assertTrue(handle("在吗", sendOk = true, gatesOpen = false))
        assertEquals("闸关 = 与旧写法同帧清空", 1, commits)
        assertEquals(1, accepted)
        assertNull("闸关不进握手", state.pending)
    }

    @Test fun gatesOpen_postponesClearUntilBubbleLands() {
        assertTrue(handle("在吗", sendOk = true, gatesOpen = true))
        assertNotNull("闸开 → 进握手", state.pending)
        assertEquals("这一刻还不能清——飞行泡要抄输入框里那份文字", 0, commits)
        assertEquals(1, accepted)

        state.inputBounds = Rect(0f, 900f, 400f, 944f)
        state.onBubblePositioned(landed("在吗"), Rect(100f, 700f, 400f, 740f))
        assertEquals("新气泡就位那一帧才清，且恰一次", 1, commits)
        assertNull(state.pending)
    }

    @Test fun gatesOpen_timeoutStillClearsExactlyOnce() {
        handle("在吗", sendOk = true, gatesOpen = true)
        state.resolveByTimeout()
        assertEquals("200ms 没等到气泡 → 兜底清空（E12）", 1, commits)
        state.resolveByTimeout()
        assertEquals("决议幂等：不会清第二次", 1, commits)
    }

    @Test fun unrelatedMessage_doesNotResolveTheHandshake() {
        handle("在吗", sendOk = true, gatesOpen = true)
        // 别人的消息（AI 回的、或另一句用户消息）不该把这次握手兑掉。
        state.onBubblePositioned(
            MessageEntity(messageUUID = "x", conversationUuid = "c", roleRaw = "assistant", content = "在吗", timestamp = now + 1),
            Rect(0f, 700f, 300f, 740f),
        )
        assertEquals(0, commits)
        state.onBubblePositioned(landed("在不在"), Rect(100f, 700f, 400f, 740f))
        assertEquals("文不对也不兑", 0, commits)
        assertNotNull(state.pending)
    }
}
