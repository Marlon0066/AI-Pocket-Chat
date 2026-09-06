package com.situ.aichat.ui.chat

import com.situ.aichat.data.calendar.CalendarAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `toolFollowUpResultText`（H3·#3·决定 C）：回喂给模型的工具结果**绝不谎报「已完成」**（回喂在执行之前）。
 * 需确认 → 据实说待确认/尚未执行；自动 → 意图态「将为用户…」；线下 → 卡片待回应。
 */
class ToolFollowUpResultTextTest {

    private val createAction = CalendarAction.fromToolCallArguments(
        """{"action":"create_event","title":"开会","startDate":"2026-06-05T10:00:00"}""",
    )

    @Test fun confirm_mode_never_claims_done() {
        val s = toolFollowUpResultText(createAction, "calendar_action", calendarNeedsConfirmation = true)
        assertTrue("应说明待确认", s.contains("待") && s.contains("确认"))
        assertTrue("应说明尚未执行", s.contains("尚未"))
        assertFalse("不应是自动执行的意图态", s.contains("将为用户"))
        assertTrue("仍带上操作内容供模型措辞", s.contains("开会"))
    }

    @Test fun auto_mode_states_intent_not_completion() {
        val s = toolFollowUpResultText(createAction, "calendar_action", calendarNeedsConfirmation = false)
        assertTrue("自动态用意图措辞", s.startsWith("将为用户"))
        assertTrue(s.contains("开会"))
        assertFalse("自动态不提确认卡", s.contains("确认卡"))
    }

    @Test fun offline_tools_use_pending_card_text() {
        assertEquals("邀约卡片已发送，等待用户回应。", toolFollowUpResultText(null, "suggest_offline_meeting", false))
        assertEquals("结束见面卡片已发送，等待用户确认。", toolFollowUpResultText(null, "end_offline_meeting", false))
    }

    @Test fun failed_calendar_call_states_not_executed_never_done() {
        // 日历工具但参数没解析出动作（解析失败）→ 据实说没执行，绝不谎报「操作已执行」(P1)。
        val s = toolFollowUpResultText(null, "calendar_action", calendarNeedsConfirmation = false)
        assertFalse("绝不谎报已执行", s.contains("操作已执行"))
        assertFalse(s.contains("已执行"))
        assertTrue(s.contains("没能执行"))
    }

    @Test fun failed_calendar_via_real_parse_mirrors_engine_chain() {
        // 镜像引擎链路：坏参数 → CalendarAction.fromToolCallArguments 抛 → getOrNull()=null → 文案不谎报完成。
        val action = runCatching { CalendarAction.fromToolCallArguments("{ 这不是合法 json") }.getOrNull()
        assertNull(action)
        assertFalse(toolFollowUpResultText(action, "calendar_action", false).contains("操作已执行"))
    }

    @Test fun future_meeting_call_uses_pending_not_done() {
        // 约见面 = 待确认提案（冒确认卡、尚未落定）→ 待定态，绝不「操作已执行」（复核新揪出·同 P1 一家）。
        val s = toolFollowUpResultText(null, "propose_future_meeting", calendarNeedsConfirmation = false)
        assertFalse(s.contains("操作已执行"))
        assertTrue(s.contains("提案") || s.contains("待"))
    }

    @Test fun genuinely_unknown_tool_states_not_executed() {
        val s = toolFollowUpResultText(null, "something_else", false)
        assertFalse(s.contains("操作已执行"))
        assertTrue(s.contains("没能执行"))
    }

    // ── T1-13（图纸 2026-09-06 约定工具调用化·§3.3-C·H3 不谎报） ──

    @Test fun promise_tools_use_pending_wording_never_claim_recorded() {
        // 锁定文本在测试里「重新打字」为字面量 + 与实现常量双保险 pin（PITFALLS §1e）。
        val expected = "已收到这条约定记账请求，App 会在你回复之后据实处理（当前尚未写入账本）。"
        assertEquals(expected, toolFollowUpResultText(null, "record_promise", calendarNeedsConfirmation = false))
        assertEquals(expected, toolFollowUpResultText(null, "resolve_promise", calendarNeedsConfirmation = true))
        assertEquals(expected, PROMISE_FOLLOW_UP_TEXT)
        // 绝不预报「已记下 / 已写入」。
        assertFalse(expected.contains("已记下"))
        assertFalse(expected.contains("已写入"))
        assertFalse(expected.contains("操作已执行"))
    }
}
