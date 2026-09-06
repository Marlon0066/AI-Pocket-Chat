package com.situ.aichat.ui.chat

import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.model.MeetingCandidate
import com.situ.aichat.meeting.FutureMeetingTool
import com.situ.aichat.offline.OfflineMeetingAction
import com.situ.aichat.offline.OfflineMeetingActionType
import com.situ.aichat.promise.PromiseChatTool
import com.situ.aichat.promise.PromiseToolAction

/**
 * 结构化 + 文本标记双轨的**汇合**纯逻辑（1:1 iOS `ChatViewModel+StreamReceiver.preprocessAssistantResponse`
 * + `+PostProcess.deduplicateOfflineActions` + `+StreamReceiver` 的 needsTextFollowUp 判定）。
 *
 * 流式收完后：结构化路（tool_calls 解析出的动作）优先；否则从正文文本标记兜底解析（日历 [CALENDAR_ACTION] /
 * 线下 [offline_invite|…]）。无论哪条路，正文里的标记都会被剥离（防泄漏成聊天气泡）。纯函数 → 单测覆盖。
 */
object AssistantResponsePreprocessor {

    /**
     * @param responseAfterOffline 清理掉日历 + 线下标记后的正文（结构化路则原样透传）。
     * @param calendarActions 最终日历动作（结构化优先，否则文本解析）。
     * @param offlineActions 最终线下动作（仅文本路产出；结构化线下动作由调用方单独持有并合并）。
     * @param hasOfflineMeetingAction 是否存在任一线下动作（结构化 tool call 或文本解析）。
     */
    data class Result(
        val responseAfterOffline: String,
        val calendarActions: List<CalendarAction>,
        val offlineActions: List<OfflineMeetingAction>,
        val hasOfflineMeetingAction: Boolean,
        /** 文本暗号路解析出的未来约定候选（intent=new·source=fallback）；标记已从正文剥离。 */
        val futureMeetingCandidates: List<MeetingCandidate> = emptyList(),
        /** 文本暗号路解析出的约定记账动作（图纸 2026-09-06）；`[promise]` 标记已从正文剥离，闸门在 handler。 */
        val promiseMarkerActions: List<PromiseToolAction> = emptyList(),
    )

    /**
     * 汇合双路（1:1 iOS preprocessAssistantResponse）。
     * @param toolCalendarActions 结构化路解析出的日历动作（非空 → 跳过日历文本解析，正文不动）。
     * @param hasOfflineMeetingToolCall 结构化路是否有线下 tool call（true 或有日历 tool → 跳过线下文本解析）。
     * @param allowOfflineSuggestions 「角色可主动发起见面」开关；false 时丢弃文本解析出的 suggestMeeting（仍剥标签）。
     */
    fun preprocess(
        fullResponse: String,
        toolCalendarActions: List<CalendarAction>,
        hasOfflineMeetingToolCall: Boolean,
        allowOfflineSuggestions: Boolean,
    ): Result {
        // ── 日历：结构化优先，否则文本标记解析 ──
        val responseAfterCalendar: String
        val calendarActions: List<CalendarAction>
        if (toolCalendarActions.isEmpty()) {
            val parsed = CalendarAction.parseFromResponse(fullResponse)
            responseAfterCalendar = parsed.first
            calendarActions = parsed.second
        } else {
            responseAfterCalendar = fullResponse
            calendarActions = toolCalendarActions
        }

        // ── 线下：仅当无任何结构化动作时才文本解析（与日历同一条降级链路）──
        var hasOfflineMeetingAction = hasOfflineMeetingToolCall
        val responseAfterOffline: String
        val offlineActions: List<OfflineMeetingAction>
        if (toolCalendarActions.isEmpty() && !hasOfflineMeetingToolCall) {
            val parsed = OfflineMeetingAction.parseFromResponse(responseAfterCalendar)
            // 开关关闭 → 丢弃 suggestMeeting，保留 endMeeting（cleanText 仍用，标签已从正文移除）。
            val filtered = if (allowOfflineSuggestions) {
                parsed.second
            } else {
                parsed.second.filter { it.action != OfflineMeetingActionType.SUGGEST_MEETING }
            }
            responseAfterOffline = if (parsed.second.isEmpty()) responseAfterCalendar else parsed.first
            offlineActions = filtered
            if (filtered.isNotEmpty()) hasOfflineMeetingAction = true
        } else {
            responseAfterOffline = responseAfterCalendar
            offlineActions = emptyList()
        }

        // ── 未来约定见面文本暗号：无条件剥 [future_meeting]{...}（防泄露成气泡，与日历/线下标记同口径）+ 收候选 ──
        val (responseAfterMeeting, futureMeetingCandidates) = FutureMeetingTool.parseProposalMarkers(responseAfterOffline)

        // ── 约定记账文本暗号：同口径无条件剥 [promise]{...}（图纸 2026-09-06 §3.3-A）+ 收动作 ──
        val (responseAfterPromise, promiseMarkerActions) = PromiseChatTool.parseMarkers(responseAfterMeeting)

        return Result(
            responseAfterPromise,
            calendarActions,
            offlineActions,
            hasOfflineMeetingAction,
            futureMeetingCandidates,
            promiseMarkerActions,
        )
    }

    /**
     * 按内容去重线下动作（1:1 iOS deduplicateOfflineActions）：相同 action 类型 + 核心参数只留第一个。
     * 合并「结构化 tool call 解析」+「文本标记解析」两来源后调用，避免同一邀约/结束插两张卡。
     */
    fun deduplicateOfflineActions(actions: List<OfflineMeetingAction>): List<OfflineMeetingAction> {
        if (actions.size <= 1) return actions
        val seen = HashSet<String>()
        return actions.filter { action ->
            val key = when (action.action) {
                OfflineMeetingActionType.SUGGEST_MEETING -> "suggest|${action.location ?: ""}|${action.activity ?: ""}"
                OfflineMeetingActionType.END_MEETING -> "end|${action.farewell ?: ""}"
            }
            seen.add(key)
        }
    }

    /**
     * 模型只回 tool_calls 没文本时，是否需要发工具结果取一段文字回复（1:1 iOS needsTextFollowUp，StreamReceiver:307-310）：
     * (有日历动作 || 有线下动作 || **有约定动作**) 且 **非「只有线下动作」**。线下邀约/结束卡本身就是完整回复 →
     * 不 follow-up（否则 LLM 会抄 llmRepresentation 描述文字，卡片旁多出重复气泡）。
     * [promiseActions] 为空时与旧公式**逐字节等价**（图纸 2026-09-06 §3.3-B·既有用例全保）：只调了约定工具、
     * 正文空的回合要去取一段正文，否则会变成没有气泡的空回合。
     */
    fun needsTextFollowUp(
        calendarActions: List<CalendarAction>,
        offlineActions: List<OfflineMeetingAction>,
        promiseActions: List<PromiseToolAction> = emptyList(),
    ): Boolean {
        val hasCalendar = calendarActions.isNotEmpty()
        val hasPromise = promiseActions.isNotEmpty()
        // 线下卡在场且无日历 → 卡即回复（约定动作静默记账，不因它多要一段文字）。
        val hasOnlyOfflineLike = offlineActions.isNotEmpty() && calendarActions.isEmpty()
        return (hasCalendar || offlineActions.isNotEmpty() || hasPromise) && !hasOnlyOfflineLike
    }
}
