package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.offline.OfflineMarkerStartPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线下见面 prompt 历史过滤纯逻辑测试（10.2c-3c）：断言反推 iOS PromptBuilder
 * `filteredMessages` 的 `.offlineMarkerStart` 分支 + [currentOfflineSessionStartPayload]
 * （场景感小批 2026-09-06：地点 / 活动 / 心事种子改从**全量**历史取当前 session 的入场标记，
 * 取代只看截断窗口的旧 `extractTensionSeedFromSessionMessages`）。
 */
class PromptBuilderOfflineFilterTest {

    private fun markerStart(
        sessionId: String,
        tensionSeed: String?,
        ts: Long,
        location: String = "公园",
        activity: String = "散步",
    ): MessageEntity =
        MessageEntity(
            messageUUID = "m$ts",
            conversationUuid = "c1",
            roleRaw = "assistant",
            content = OfflineMarkerStartPayload(location, activity, "下午3:30", tensionSeed).makeContent(),
            timestamp = ts,
            isOfflineMode = true,
            offlineSessionId = sessionId,
            messageKindRaw = MessageKind.OFFLINE_MARKER_START.raw,
        )

    private fun narrative(sessionId: String, text: String, ts: Long): MessageEntity =
        MessageEntity(
            messageUUID = "n$ts",
            conversationUuid = "c1",
            roleRaw = "assistant",
            content = text,
            timestamp = ts,
            isOfflineMode = true,
            offlineSessionId = sessionId,
        )

    // ── shouldKeepOfflineMarkerStart ──

    @Test fun marker_kept_only_when_offline_and_session_matches() {
        assertTrue(PromptBuilder.shouldKeepOfflineMarkerStart(true, "s1", "s1"))
    }

    @Test fun marker_dropped_when_not_in_offline_mode() {
        assertFalse(PromptBuilder.shouldKeepOfflineMarkerStart(false, "s1", "s1"))
    }

    @Test fun marker_dropped_when_session_differs() {
        assertFalse(PromptBuilder.shouldKeepOfflineMarkerStart(true, "s1", "s2"))
    }

    @Test fun marker_dropped_on_null_or_blank_session() {
        assertFalse(PromptBuilder.shouldKeepOfflineMarkerStart(true, null, "s1"))
        assertFalse(PromptBuilder.shouldKeepOfflineMarkerStart(true, "", "s1"))
        assertFalse(PromptBuilder.shouldKeepOfflineMarkerStart(true, "s1", null))
        assertFalse(PromptBuilder.shouldKeepOfflineMarkerStart(true, "s1", ""))
    }

    // ── currentOfflineSessionStartPayload（场景感小批 §7 T1-2）──

    @Test fun 入场标记三字段完整解析出来() {
        val msgs = listOf(
            markerStart("s1", "她今天其实有点心事没说", 1),
            narrative("s1", "[环境]咖啡馆很安静[/环境]", 2),
        )
        val payload = currentOfflineSessionStartPayload(msgs, "s1")
        assertEquals("公园", payload?.location)
        assertEquals("散步", payload?.activity)
        assertEquals("她今天其实有点心事没说", payload?.tensionSeed)
    }

    @Test fun 只认当前session的标记_旧session不串场() {
        // s0 = 上一场见面（地点/种子都不同），s1 = 当前场；helper 必须只认 s1。
        val msgs = listOf(
            markerStart("s0", "旧种子", 1, location = "电影院", activity = "看电影"),
            markerStart("s1", "新种子", 2),
        )
        val payload = currentOfflineSessionStartPayload(msgs, "s1")
        assertEquals("公园", payload?.location)
        assertEquals("新种子", payload?.tensionSeed)
        // 反向：当前 session 若是 s0，取到的也只能是 s0 那张。
        assertEquals("电影院", currentOfflineSessionStartPayload(msgs, "s0")?.location)
    }

    @Test fun 历史里没有入场标记返回null() {
        assertNull(currentOfflineSessionStartPayload(listOf(narrative("s1", "随便聊聊", 1)), "s1"))
        assertNull(currentOfflineSessionStartPayload(emptyList(), "s1"))
    }

    @Test fun sessionId空白一律返回null() {
        val msgs = listOf(markerStart("s1", "种子", 1))
        assertNull(currentOfflineSessionStartPayload(msgs, null))
        assertNull(currentOfflineSessionStartPayload(msgs, ""))
        assertNull(currentOfflineSessionStartPayload(msgs, "   "))
    }

    @Test fun 当前标记parse失败返回null_不续扫更早的合法标记() {
        // 同一 session 两张标记，最新那张 content 被改坏 → 返 null（找到首条匹配即停）。
        val msgs = listOf(
            markerStart("s1", "更早的合法种子", 1, location = "电影院"),
            markerStart("s1", "坏掉的那张", 2).copy(content = "随便一段字"),
        )
        assertNull(currentOfflineSessionStartPayload(msgs, "s1"))
    }

    @Test fun 标记不是最后一条时仍能找到() {
        val msgs = listOf(
            markerStart("s1", "她今天有心事", 1),
            narrative("s1", "[叙述]你推门进去[/叙述]", 2),
            narrative("s1", "[对话]来啦[/对话]", 3),
            narrative("s1", "[动作]她笑了笑[/动作]", 4),
        )
        val payload = currentOfflineSessionStartPayload(msgs, "s1")
        assertEquals("公园", payload?.location)
        assertEquals("她今天有心事", payload?.tensionSeed)
    }
}
