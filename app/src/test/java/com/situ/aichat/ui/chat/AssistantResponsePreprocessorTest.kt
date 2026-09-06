package com.situ.aichat.ui.chat

import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.calendar.CalendarActionType
import com.situ.aichat.offline.OfflineMeetingAction
import com.situ.aichat.offline.OfflineMeetingActionType
import com.situ.aichat.promise.PromiseToolAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结构化/文本双轨**汇合**纯逻辑测试（S3b）：断言反推 iOS `preprocessAssistantResponse` /
 * `deduplicateOfflineActions` / needsTextFollowUp —— 抓哪条路胜出、标签是否常驻剥离、空文本是否被卡片救场。
 */
class AssistantResponsePreprocessorTest {

    private fun suggest(loc: String, act: String) =
        OfflineMeetingAction(OfflineMeetingActionType.SUGGEST_MEETING, location = loc, activity = act)

    private fun end(farewell: String? = null) =
        OfflineMeetingAction(OfflineMeetingActionType.END_MEETING, farewell = farewell)

    private fun calendar() = CalendarAction(action = CalendarActionType.CREATE_EVENT, title = "开会")

    // ── preprocess: 纯文本 ──

    @Test fun plain_text_no_actions_passes_through() {
        val r = AssistantResponsePreprocessor.preprocess("今天天气不错", emptyList(), false, allowOfflineSuggestions = true)
        assertEquals("今天天气不错", r.responseAfterOffline)
        assertTrue(r.calendarActions.isEmpty())
        assertTrue(r.offlineActions.isEmpty())
        assertFalse(r.hasOfflineMeetingAction)
    }

    // ── preprocess: 结构化优先（有 tool 动作 → 跳过文本解析，正文不动）──

    @Test fun structured_calendar_skips_text_parsing_and_keeps_raw() {
        // 即使正文里有 [offline_invite] 文本标记，只要结构化日历动作非空就跳过线下文本解析（1:1 iOS）。
        val raw = "好的[offline_invite|公园|散步|走吧]"
        val r = AssistantResponsePreprocessor.preprocess(raw, listOf(calendar()), false, allowOfflineSuggestions = true)
        assertEquals(1, r.calendarActions.size)
        assertEquals(raw, r.responseAfterOffline) // 结构化路正文原样透传
        assertTrue(r.offlineActions.isEmpty())
        assertFalse(r.hasOfflineMeetingAction)
    }

    @Test fun structured_offline_toolcall_skips_text_parsing_and_sets_flag() {
        val r = AssistantResponsePreprocessor.preprocess("（卡片即回复）", emptyList(), hasOfflineMeetingToolCall = true, allowOfflineSuggestions = true)
        assertTrue(r.offlineActions.isEmpty()) // 结构化线下动作由调用方持有，preprocess 不重复产出
        assertTrue(r.hasOfflineMeetingAction) // 但 flag 透传为 true
    }

    // ── preprocess: 文本标记降级解析 ──

    @Test fun text_offline_invite_parsed_and_tag_stripped() {
        val raw = "走吧~[offline_invite|公园|散步|一起去公园吧]"
        val r = AssistantResponsePreprocessor.preprocess(raw, emptyList(), false, allowOfflineSuggestions = true)
        assertEquals(1, r.offlineActions.size)
        assertEquals(OfflineMeetingActionType.SUGGEST_MEETING, r.offlineActions[0].action)
        assertEquals("公园", r.offlineActions[0].location)
        assertTrue(r.hasOfflineMeetingAction)
        // 标签从正文剥离，防泄漏成气泡。
        assertFalse(r.responseAfterOffline.contains("offline_invite"))
        assertTrue(r.responseAfterOffline.contains("走吧"))
    }

    @Test fun text_offline_end_parsed() {
        val r = AssistantResponsePreprocessor.preprocess("时候不早了[offline_end]", emptyList(), false, allowOfflineSuggestions = true)
        assertEquals(1, r.offlineActions.size)
        assertEquals(OfflineMeetingActionType.END_MEETING, r.offlineActions[0].action)
        assertTrue(r.hasOfflineMeetingAction)
    }

    // ── preprocess: 未来约定见面文本暗号（8d-3b·无条件剥除 + 收候选）──

    @Test fun text_future_meeting_marker_parsed_and_stripped() {
        val raw = """好呀周六见~[future_meeting]{"when_text":"周六下午","activity":"看展","location":"美术馆"}"""
        val r = AssistantResponsePreprocessor.preprocess(raw, emptyList(), false, allowOfflineSuggestions = true)
        assertEquals(1, r.futureMeetingCandidates.size)
        assertEquals("看展", r.futureMeetingCandidates[0].activity)
        assertEquals("周六下午", r.futureMeetingCandidates[0].rawWhen)
        // 暗号从正文剥离，绝不泄漏成气泡；正常正文保留。
        assertFalse(r.responseAfterOffline.contains("future_meeting"))
        assertTrue(r.responseAfterOffline.contains("好呀周六见"))
    }

    @Test fun text_no_future_marker_leaves_candidates_empty() {
        val r = AssistantResponsePreprocessor.preprocess("周末有空一起玩呀", emptyList(), false, allowOfflineSuggestions = true)
        assertTrue(r.futureMeetingCandidates.isEmpty())
        assertEquals("周末有空一起玩呀", r.responseAfterOffline)
    }

    @Test fun suggestions_disabled_drops_suggest_but_still_strips_tag() {
        // 开关关：suggestMeeting 丢弃（无动作、flag 不置 true），但标签仍从正文剥离（1:1 iOS）。
        val raw = "走吧[offline_invite|公园|散步|一起去]"
        val r = AssistantResponsePreprocessor.preprocess(raw, emptyList(), false, allowOfflineSuggestions = false)
        assertTrue(r.offlineActions.isEmpty())
        assertFalse(r.hasOfflineMeetingAction)
        assertFalse(r.responseAfterOffline.contains("offline_invite"))
    }

    @Test fun suggestions_disabled_keeps_end_meeting() {
        // 开关关只挡 suggestMeeting，endMeeting（结束见面）仍保留。
        val r = AssistantResponsePreprocessor.preprocess("[offline_end]", emptyList(), false, allowOfflineSuggestions = false)
        assertEquals(1, r.offlineActions.size)
        assertEquals(OfflineMeetingActionType.END_MEETING, r.offlineActions[0].action)
    }

    // ── deduplicateOfflineActions ──

    @Test fun dedup_collapses_identical_suggests() {
        val out = AssistantResponsePreprocessor.deduplicateOfflineActions(listOf(suggest("公园", "散步"), suggest("公园", "散步")))
        assertEquals(1, out.size)
    }

    @Test fun dedup_keeps_distinct_and_mixed() {
        val out = AssistantResponsePreprocessor.deduplicateOfflineActions(listOf(suggest("公园", "散步"), end("再见"), end("再见")))
        assertEquals(2, out.size) // 一个 suggest + 一个 end（两 end farewell 相同合一）
    }

    // ── needsTextFollowUp ──

    @Test fun follow_up_needed_for_calendar_only() {
        assertTrue(AssistantResponsePreprocessor.needsTextFollowUp(listOf(calendar()), emptyList()))
    }

    @Test fun follow_up_skipped_for_offline_only() {
        // 线下卡本身即完整回复 → 不 follow-up（否则卡片旁多重复气泡）。
        assertFalse(AssistantResponsePreprocessor.needsTextFollowUp(emptyList(), listOf(suggest("公园", "散步"))))
    }

    @Test fun follow_up_needed_for_calendar_plus_offline() {
        assertTrue(AssistantResponsePreprocessor.needsTextFollowUp(listOf(calendar()), listOf(end())))
    }

    @Test fun follow_up_skipped_for_nothing() {
        assertFalse(AssistantResponsePreprocessor.needsTextFollowUp(emptyList(), emptyList()))
    }

    // ── T1-10（图纸 2026-09-06 约定工具调用化·E7）：[promise] 暗号剥离 + follow-up 公式扩展 ──

    private fun record() = PromiseToolAction.Record("周六一起去看展", null, "那就周六去看展吧")

    @Test fun text_promise_marker_parsed_and_stripped() {
        val raw = "好呀，说定啦～\n" +
            """[promise]{"action":"record","content":"周六一起去看展","evidence":"那就周六去看展吧"}"""
        val r = AssistantResponsePreprocessor.preprocess(raw, emptyList(), false, true)
        assertEquals("好呀，说定啦～", r.responseAfterOffline)
        assertFalse("正文绝不残留标记", r.responseAfterOffline.contains("[promise]"))
        assertEquals(1, r.promiseMarkerActions.size)
        assertEquals("周六一起去看展", (r.promiseMarkerActions[0] as PromiseToolAction.Record).content)
    }

    @Test fun text_no_promise_marker_leavesActionsEmpty() {
        val r = AssistantResponsePreprocessor.preprocess("今天天气真好呀", emptyList(), false, true)
        assertEquals("今天天气真好呀", r.responseAfterOffline)
        assertTrue(r.promiseMarkerActions.isEmpty())
    }

    @Test fun follow_up_needed_for_promise_only_but_skipped_when_offline_card_present() {
        // 只调了约定工具、正文空 → 要去取一段正文（否则回合没有气泡）。
        assertTrue(AssistantResponsePreprocessor.needsTextFollowUp(emptyList(), emptyList(), listOf(record())))
        // 线下卡在场且无日历 → 卡即回复，约定动作静默记账，不额外要文字。
        assertFalse(AssistantResponsePreprocessor.needsTextFollowUp(emptyList(), listOf(suggest("公园", "散步")), listOf(record())))
        // 日历 + 约定 → 仍要。
        assertTrue(AssistantResponsePreprocessor.needsTextFollowUp(listOf(calendar()), emptyList(), listOf(record())))
        // 尾参缺省 = 旧公式逐字节等价（既有四例已覆盖，这里再钉一次空约定动作的等价性）。
        assertFalse(AssistantResponsePreprocessor.needsTextFollowUp(emptyList(), emptyList(), emptyList()))
    }
}
