package com.situ.aichat.offline

import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.model.MeetingCandidate
import com.situ.aichat.data.remote.llm.CompletedToolCall
import com.situ.aichat.meeting.FutureMeetingTool
import com.situ.aichat.promise.PromiseChatTool
import com.situ.aichat.promise.PromiseToolAction

/**
 * 把累积完成的结构化工具调用解码为领域动作（纯函数，1:1 iOS `parseToolCallActions` / `parseOfflineMeetingActions`）。
 *
 * 双轨的「结构化」侧：[com.situ.aichat.data.remote.llm.ToolCallAccumulator] 收完整 tool_calls 后，由本提取器
 * 拆成日历 / 线下两类动作。任一调用解码彻底失败 → [parsingFailed]=true，上层（S3b/ChatViewModel）据此降级为
 * 文本标记模式重试。落库 / 分发由调用方做。
 */
object ToolCallActionExtractor {

    data class CalendarResult(val actions: List<CalendarAction>, val parsingFailed: Boolean)
    data class OfflineResult(val actions: List<OfflineMeetingAction>, val parsingFailed: Boolean)

    /**
     * 解析日历类工具调用（1:1 iOS parseToolCallActions）：跳过线下见面工具；其余按 [CalendarAction.fromToolCallArguments]
     * 严格解码，任一失败 → parsingFailed=true。
     */
    fun parseCalendarActions(completed: List<CompletedToolCall>): CalendarResult {
        if (completed.isEmpty()) return CalendarResult(emptyList(), false)
        val actions = ArrayList<CalendarAction>()
        var parsingFailed = false
        for (call in completed) {
            if (OfflineMeetingAction.isOfflineMeetingTool(call.name)) continue // 线下见面单独处理
            if (FutureMeetingTool.isFutureMeetingTool(call.name)) continue // 未来约定单独处理（不当日历，否则误判解析失败逼降级）
            if (PromiseChatTool.isPromiseTool(call.name)) continue // 约定记账单独处理（同上，不当日历解析）
            runCatching { CalendarAction.fromToolCallArguments(call.arguments) }
                .onSuccess { actions.add(it) }
                .onFailure { parsingFailed = true }
        }
        return CalendarResult(actions, parsingFailed)
    }

    /**
     * 解析未来约定见面工具调用（[FutureMeetingTool.TOOL_NAME]）→ 候选（intent=new·source=tool）。
     * 工具参数空壳 / 非法 → 静默跳过（不计解析失败·识别侧宁漏勿错）；非本工具的调用忽略。
     */
    fun parseFutureMeetingActions(completed: List<CompletedToolCall>): List<MeetingCandidate> {
        if (completed.isEmpty()) return emptyList()
        val candidates = ArrayList<MeetingCandidate>()
        for (call in completed) {
            if (!FutureMeetingTool.isFutureMeetingTool(call.name)) continue
            FutureMeetingTool.candidateFromToolCall(call.arguments)?.let { candidates.add(it) }
        }
        return candidates
    }

    /**
     * 解析约定记账工具调用（`record_promise` / `resolve_promise`）→ 待落库动作（图纸 2026-09-06 §3.1）。
     * 工具参数空壳 / 非法 → 静默跳过（**不计解析失败**·识别侧宁漏勿错，与约见面同款）；非本族的调用忽略。
     * 闸门（证据 / 编号 / 上限）不在此，在 `ChatPromiseToolHandler.screen`。
     */
    fun parsePromiseActions(completed: List<CompletedToolCall>): List<PromiseToolAction> {
        if (completed.isEmpty()) return emptyList()
        val actions = ArrayList<PromiseToolAction>()
        for (call in completed) {
            if (!PromiseChatTool.isPromiseTool(call.name)) continue
            PromiseChatTool.fromToolCall(call.name, call.arguments)?.let { actions.add(it) }
        }
        return actions
    }

    /**
     * 解析线下见面类工具调用（1:1 iOS parseOfflineMeetingActions，两层防御）：① 严格 fromToolCallArguments；
     * ② 失败则 lenientParseToolCallArguments 兜底；③ 两层都失败 → parsingFailed=true。
     *
     * @param allowSuggestions 「角色可主动发起见面」开关；false 时**源头丢弃** suggestMeeting（保留 endMeeting），
     *   让下游 hasOfflineMeetingAction / needsTextFollowUp 等判定一致，避免「正文被抑制但卡片又被过滤」致消息消失。
     *   主动丢弃不计入解析失败。
     */
    fun parseOfflineActions(completed: List<CompletedToolCall>, allowSuggestions: Boolean): OfflineResult {
        if (completed.isEmpty()) return OfflineResult(emptyList(), false)
        fun keep(action: OfflineMeetingAction): Boolean =
            allowSuggestions || action.action != OfflineMeetingActionType.SUGGEST_MEETING

        val actions = ArrayList<OfflineMeetingAction>()
        var parsingFailed = false
        for (call in completed) {
            if (!OfflineMeetingAction.isOfflineMeetingTool(call.name)) continue
            val strict = runCatching { OfflineMeetingAction.fromToolCallArguments(call.name, call.arguments) }.getOrNull()
            val action = strict ?: OfflineMeetingAction.lenientParseToolCallArguments(call.name, call.arguments)
            when {
                action == null -> parsingFailed = true // 严格 + 宽容都失败 → 触发文本降级
                keep(action) -> actions.add(action)
                // else：开关关闭丢弃 LLM 幻觉的 suggestMeeting，不算失败
            }
        }
        return OfflineResult(actions, parsingFailed)
    }

    /**
     * 工具调用解析后，是否该整轮退回文本标记降级（H2·治 #5「一坏调用毁整轮」）。
     *
     * 旧逻辑：任一 [CalendarResult.parsingFailed]/[OfflineResult.parsingFailed] 即退 → 一个坏调用把同轮
     * **已解析成功**的其它调用（日历/线下/约见面候选/约定动作）连同正文一起丢掉、再多花一次 LLM 重发。
     * 新逻辑：仅当「有调用失败、且四类一个可用动作/候选都没解析出来」（全军覆没）才退；**部分成功 → 不退**，
     * 留住解析出来的、丢掉坏的那个（坏调用已在各 `parse*` 内被跳过）。
     */
    fun shouldFallBackToText(
        calendar: CalendarResult,
        offline: OfflineResult,
        meetingCandidates: List<MeetingCandidate>,
        promiseActions: List<PromiseToolAction> = emptyList(),
    ): Boolean {
        val anyFailed = calendar.parsingFailed || offline.parsingFailed
        val anyParsed = calendar.actions.isNotEmpty() || offline.actions.isNotEmpty() ||
            meetingCandidates.isNotEmpty() || promiseActions.isNotEmpty()
        return anyFailed && !anyParsed
    }
}
