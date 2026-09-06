package com.situ.aichat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `buildChatToolDefinitions`（H5·#7 解绑「日历集成」连坐）：日历工具只在集成开时下发；
 * 线下/约见面工具与日历无关、只要走工具路就下发（不再被日历开关连坐回暗号）。
 */
class ChatToolDefinitionsTest {

    private fun names(
        includeCalendar: Boolean,
        canInitiate: Boolean,
        allowEnd: Boolean = true,
        offlineMeeting: Boolean = false,
    ) = buildChatToolDefinitions(includeCalendar, canInitiate, allowEnd, offlineMeeting).map { it.function.name }

    @Test fun calendar_tool_only_when_integration_on() {
        assertTrue(names(includeCalendar = true, canInitiate = true).contains("calendar_action"))
        assertFalse(names(includeCalendar = false, canInitiate = true).contains("calendar_action"))
    }

    @Test fun offline_and_future_decoupled_from_calendar() {
        // 关了日历（includeCalendar=false），线下/约见面工具仍下发——这正是 H5 解绑的目的。
        val n = names(includeCalendar = false, canInitiate = true)
        assertTrue(n.contains("suggest_offline_meeting"))
        assertTrue(n.contains("end_offline_meeting"))
        assertTrue(n.contains("propose_future_meeting"))
    }

    /** T1-4（图纸 2026-09-06 见面窗口与节拍卡七件 §7·E16）：散场硬闸期只撤 end，其余工具一个不少。 */
    @Test fun end_tool_removed_when_hold_active() {
        val n = names(includeCalendar = true, canInitiate = true, allowEnd = false)
        assertFalse("硬闸期不下发结束工具", n.contains("end_offline_meeting"))
        assertTrue(n.contains("suggest_offline_meeting"))
        assertTrue(n.contains("propose_future_meeting"))
        assertTrue(n.contains("calendar_action"))
    }

    // ── T1-7（图纸 2026-09-06 约定工具调用化·E5）：约定两工具的下发门与顺序 ──

    @Test fun promise_tools_downstreamOfFutureMeeting_byDefault() {
        val n = names(includeCalendar = true, canInitiate = true)
        assertTrue(n.contains("record_promise"))
        assertTrue(n.contains("resolve_promise"))
        // registry 顺序：约定两工具排在 propose_future_meeting 之后（追加在末尾 → 既有工具字节不动）。
        assertTrue("record_promise 应排在 propose_future_meeting 之后", n.indexOf("record_promise") > n.indexOf("propose_future_meeting"))
        assertEquals("resolve_promise 紧随 record_promise", n.indexOf("record_promise") + 1, n.indexOf("resolve_promise"))
        // 与日历开关解绑：关了日历也照常下发。
        assertTrue(names(includeCalendar = false, canInitiate = true).contains("record_promise"))
    }

    @Test fun promise_tools_droppedInOfflineMeeting_othersUnchanged() {
        val inMeeting = names(includeCalendar = true, canInitiate = true, offlineMeeting = true)
        assertFalse("见面中不下发记新约定", inMeeting.contains("record_promise"))
        assertFalse("见面中不下发了结约定", inMeeting.contains("resolve_promise"))
        // 其余工具一个不少、顺序不变。
        assertEquals(
            listOf("calendar_action", "suggest_offline_meeting", "end_offline_meeting", "propose_future_meeting"),
            inMeeting,
        )
    }

    @Test fun offline_suggest_filtered_when_cannot_initiate() {
        val n = names(includeCalendar = true, canInitiate = false)
        assertFalse("不能主动邀约 → 过滤 suggest", n.contains("suggest_offline_meeting"))
        assertTrue("保留 end（可结束已开始的见面）", n.contains("end_offline_meeting"))
        assertTrue(n.contains("propose_future_meeting"))
        assertTrue(n.contains("calendar_action"))
    }
}
