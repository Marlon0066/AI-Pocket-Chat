package com.situ.aichat.offline

import com.situ.aichat.data.calendar.CalendarActionType
import com.situ.aichat.data.model.MeetingCandidateIntent
import com.situ.aichat.data.remote.llm.CompletedToolCall
import com.situ.aichat.promise.PromiseToolAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ToolCallActionExtractor` tests (S3a): accumulator → domain actions split, reverse-derived from iOS
 * `parseToolCallActions` / `parseOfflineMeetingActions` (skip-other-tool, strict→lenient fallback,
 * canInitiate filter, parsingFailed on total decode failure).
 */
class ToolCallActionExtractorTest {

    private fun call(name: String, args: String) = CompletedToolCall(id = "c", name = name, arguments = args)

    // H2 共享夹具：合法/非法日历参数 + 空线下结果。
    private val goodCalendarArgs = """{"action":"create_event","title":"团队会议","startDate":"2026-06-05T10:00:00"}"""
    private val badCalendarArgs = """{"action":"bogus","title":"x"}"""
    private val emptyOffline = ToolCallActionExtractor.OfflineResult(emptyList(), false)

    // ── parseCalendarActions ──

    @Test fun calendar_decodes_valid_and_skips_offline_tools() {
        val completed = listOf(
            call("calendar_action", """{"action":"create_event","title":"团队会议","startDate":"2026-06-05T10:00:00"}"""),
            call("suggest_offline_meeting", """{"location":"公园"}"""), // 线下工具，日历解析跳过
        )
        val r = ToolCallActionExtractor.parseCalendarActions(completed)
        assertFalse(r.parsingFailed)
        assertEquals(1, r.actions.size)
        assertEquals(CalendarActionType.CREATE_EVENT, r.actions[0].action)
        assertEquals("团队会议", r.actions[0].title)
    }

    @Test fun calendar_unknown_enum_marks_parsing_failed() {
        val r = ToolCallActionExtractor.parseCalendarActions(listOf(call("calendar_action", """{"action":"bogus","title":"x"}""")))
        assertTrue(r.parsingFailed)
        assertTrue(r.actions.isEmpty())
    }

    @Test fun calendar_empty_is_clean() {
        val r = ToolCallActionExtractor.parseCalendarActions(emptyList())
        assertTrue(r.actions.isEmpty())
        assertFalse(r.parsingFailed)
    }

    // ── parseOfflineActions ──

    @Test fun offline_decodes_suggest_and_end_and_skips_calendar() {
        val completed = listOf(
            call("suggest_offline_meeting", """{"location":"公园","activity":"散步","invitation":"走吧","hidden_tension":"x","tension_hint":"y"}"""),
            call("end_offline_meeting", """{"final_mood":"warm"}"""),
            call("calendar_action", """{"action":"create_event","title":"x"}"""), // 日历工具，线下解析跳过
        )
        val r = ToolCallActionExtractor.parseOfflineActions(completed, allowSuggestions = true)
        assertFalse(r.parsingFailed)
        assertEquals(2, r.actions.size)
        assertEquals(OfflineMeetingActionType.SUGGEST_MEETING, r.actions[0].action)
        assertEquals("公园", r.actions[0].location)
        assertEquals(OfflineMeetingActionType.END_MEETING, r.actions[1].action)
        assertEquals("warm", r.actions[1].finalMood)
    }

    @Test fun offline_strict_fail_lenient_succeeds_no_failure() {
        // 非法（非 lenient）JSON：未加引号的值 → 严格解码失败，宽容解析（isLenient）兜底成功。
        val r = ToolCallActionExtractor.parseOfflineActions(
            listOf(call("end_offline_meeting", """{"final_mood": warm}""")),
            allowSuggestions = true,
        )
        assertFalse(r.parsingFailed)
        assertEquals(1, r.actions.size)
        assertEquals("warm", r.actions[0].finalMood)
    }

    @Test fun offline_total_decode_failure_marks_parsing_failed() {
        // 严格 + 宽容都解析不出（结构损坏）→ parsingFailed
        val r = ToolCallActionExtractor.parseOfflineActions(
            listOf(call("suggest_offline_meeting", "}{ broken")),
            allowSuggestions = true,
        )
        assertTrue(r.parsingFailed)
        assertTrue(r.actions.isEmpty())
    }

    @Test fun offline_disallow_suggestions_drops_suggest_keeps_end_no_failure() {
        val completed = listOf(
            call("suggest_offline_meeting", """{"location":"公园","activity":"散步","invitation":"走吧","hidden_tension":"x","tension_hint":"y"}"""),
            call("end_offline_meeting", """{"final_mood":"sweet"}"""),
        )
        val r = ToolCallActionExtractor.parseOfflineActions(completed, allowSuggestions = false)
        assertFalse(r.parsingFailed) // 主动丢弃不计失败
        assertEquals(1, r.actions.size)
        assertEquals(OfflineMeetingActionType.END_MEETING, r.actions[0].action)
    }

    // ── parseFutureMeetingActions（8d-3b 工具快路） ──

    @Test fun futureMeeting_decodes_candidate_and_calendar_skips_it() {
        val completed = listOf(
            call("propose_future_meeting", """{"when_text":"周六下午","activity":"看展","location":"美术馆","proposed_by":"user"}"""),
            call("calendar_action", """{"action":"create_event","title":"x"}"""),
        )
        val meeting = ToolCallActionExtractor.parseFutureMeetingActions(completed)
        assertEquals(1, meeting.size)
        assertEquals(MeetingCandidateIntent.NEW, meeting[0].intent)
        assertEquals("看展", meeting[0].activity)
        assertEquals("周六下午", meeting[0].rawWhen)
        // 关键：未来约定工具不被当成日历工具（否则解析失败逼降级）。
        val cal = ToolCallActionExtractor.parseCalendarActions(completed)
        assertFalse(cal.parsingFailed)
        assertEquals(1, cal.actions.size) // 仅 calendar_action，未来约定被跳过
    }

    @Test fun futureMeeting_emptyShell_and_nonTool_ignored() {
        val completed = listOf(
            call("propose_future_meeting", """{"when_text":"","activity":"","iso_datetime":""}"""), // 空壳 → 丢
            call("suggest_offline_meeting", """{"location":"公园"}"""), // 非本工具 → 忽略
        )
        assertTrue(ToolCallActionExtractor.parseFutureMeetingActions(completed).isEmpty())
    }

    // ── shouldFallBackToText（H2：一坏调用不再毁整轮·只全军覆没才退文本降级） ──

    @Test fun fallback_only_when_all_failed_and_nothing_parsed() {
        val cal = ToolCallActionExtractor.parseCalendarActions(listOf(call("calendar_action", badCalendarArgs)))
        assertTrue(cal.parsingFailed)
        assertTrue(cal.actions.isEmpty())
        assertTrue(ToolCallActionExtractor.shouldFallBackToText(cal, emptyOffline, emptyList()))
    }

    @Test fun no_fallback_when_partial_success_same_tool() {
        // 同轮一好一坏的日历调用：好的进 actions、坏的标 parsingFailed → 不该整轮退（留住好的）。
        val cal = ToolCallActionExtractor.parseCalendarActions(
            listOf(call("calendar_action", goodCalendarArgs), call("calendar_action", badCalendarArgs)),
        )
        assertEquals(1, cal.actions.size)
        assertTrue(cal.parsingFailed)
        assertFalse(ToolCallActionExtractor.shouldFallBackToText(cal, emptyOffline, emptyList()))
    }

    @Test fun no_fallback_when_failed_calendar_but_offline_parsed() {
        // 日历坏，但同轮线下调用解析成功 → 不因日历失败丢掉线下动作。
        val cal = ToolCallActionExtractor.parseCalendarActions(listOf(call("calendar_action", badCalendarArgs)))
        val off = ToolCallActionExtractor.parseOfflineActions(
            listOf(call("end_offline_meeting", """{"final_mood":"warm"}""")), allowSuggestions = true,
        )
        assertTrue(cal.parsingFailed)
        assertEquals(1, off.actions.size)
        assertFalse(ToolCallActionExtractor.shouldFallBackToText(cal, off, emptyList()))
    }

    @Test fun no_fallback_when_failed_calendar_but_future_meeting_parsed() {
        // 日历坏，但约见面候选解析成功 → 留住候选。
        val cal = ToolCallActionExtractor.parseCalendarActions(listOf(call("calendar_action", badCalendarArgs)))
        val meetings = ToolCallActionExtractor.parseFutureMeetingActions(
            listOf(call("propose_future_meeting", """{"when_text":"周六下午","activity":"看展","location":"美术馆","proposed_by":"user"}""")),
        )
        assertTrue(cal.parsingFailed)
        assertEquals(1, meetings.size)
        assertFalse(ToolCallActionExtractor.shouldFallBackToText(cal, emptyOffline, meetings))
    }

    @Test fun no_fallback_on_clean_success_or_no_calls() {
        val clean = ToolCallActionExtractor.parseCalendarActions(listOf(call("calendar_action", goodCalendarArgs)))
        assertFalse(clean.parsingFailed)
        assertFalse(ToolCallActionExtractor.shouldFallBackToText(clean, emptyOffline, emptyList()))
        // 整轮没有任何工具调用 → 也不退。
        val none = ToolCallActionExtractor.parseCalendarActions(emptyList())
        assertFalse(ToolCallActionExtractor.shouldFallBackToText(none, emptyOffline, emptyList()))
    }

    // ── T1-6（图纸 2026-09-06 约定工具调用化·E1/E8）：约定工具的解析与降级判定 ──

    @Test fun calendar_skipsPromiseTools_withoutMarkingParsingFailed() {
        val completed = listOf(
            call("calendar_action", goodCalendarArgs),
            call("record_promise", """{"content":"周六一起去看展","evidence":"那就周六去看展吧"}"""),
            call("resolve_promise", """{"no":1,"status":"fulfilled","evidence":"简历我已经改好发你了"}"""),
        )
        val r = ToolCallActionExtractor.parseCalendarActions(completed)
        assertFalse("约定工具不该被当日历解析失败（否则整轮误降级）", r.parsingFailed)
        assertEquals(1, r.actions.size)
    }

    @Test fun promise_parsesRecordAndResolve_andSkipsInvalidSilently() {
        val actions = ToolCallActionExtractor.parsePromiseActions(
            listOf(
                call("record_promise", """{"content":"周六一起去看展","due":"2026-09-13","evidence":"那就周六去看展吧"}"""),
                call("resolve_promise", """{"no":2,"status":"cancelled","evidence":"这次就先不去了"}"""),
                call("record_promise", """{"content":"","evidence":"空壳"}"""), // 空壳 → 静默跳过
                call("resolve_promise", """{"no":1,"status":"fulfilled""""), // 非法 JSON → 静默跳过
                call("calendar_action", goodCalendarArgs), // 非本族 → 忽略
            ),
        )
        assertEquals(2, actions.size)
        assertEquals("周六一起去看展", (actions[0] as PromiseToolAction.Record).content)
        val resolve = actions[1] as PromiseToolAction.Resolve
        assertEquals(2, resolve.no)
        assertEquals("cancelled", resolve.status)
    }

    @Test fun no_fallback_when_failed_calendar_but_promise_parsed() {
        val cal = ToolCallActionExtractor.parseCalendarActions(listOf(call("calendar_action", badCalendarArgs)))
        val promises = ToolCallActionExtractor.parsePromiseActions(
            listOf(call("record_promise", """{"content":"周六一起去看展","evidence":"那就周六去看展吧"}""")),
        )
        assertTrue(cal.parsingFailed)
        assertEquals(1, promises.size)
        assertFalse("约定动作解出来了 → 不整轮降级（H2 语义延伸）", ToolCallActionExtractor.shouldFallBackToText(cal, emptyOffline, emptyList(), promises))
        // 尾参缺省时与旧公式等价：同样入参不带约定动作 → 仍然要降级。
        assertTrue(ToolCallActionExtractor.shouldFallBackToText(cal, emptyOffline, emptyList()))
    }
}
