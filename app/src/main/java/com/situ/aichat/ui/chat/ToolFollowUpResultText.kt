package com.situ.aichat.ui.chat

import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.meeting.FutureMeetingTool
import com.situ.aichat.offline.OfflineMeetingAction
import com.situ.aichat.promise.PromiseChatTool

/** 约定记账工具的回喂文案（图纸 2026-09-06 §3.3-C 逐字锁定·H3 不谎报）。 */
internal const val PROMISE_FOLLOW_UP_TEXT =
    "已收到这条约定记账请求，App 会在你回复之后据实处理（当前尚未写入账本）。"

/**
 * 回喂给模型的「工具结果」文案（H3·#3·产品决定 C「视情况」）。
 *
 * **红线**：回喂发生在动作**真正执行之前**（执行在 `ChatReplyDeliverer` 阶段、可能只是排了确认卡、也可能失败），
 * 所以**绝不能谎报「已完成」**——旧文案 `"已${verb}${type}：${title}"` 就是这个 bug。
 * - 日历 + **需确认**（确认卡）：据实说「已发确认卡、待用户确认、尚未执行」→ 模型自然提示确认，不会说「已创建」。
 * - 日历 + **自动执行**：回合后即写入，说「将为用户…」（意图态），不预报完成。
 * - **约见面**（propose_future_meeting）：只是把提案交用户确认（冒确认卡·尚未落定）→ 待定态。
 * - 线下见面：本就是「卡片已发送、等待回应/确认」的待定态（沿用原文案）。
 * - **约定记账**（record_promise / resolve_promise）：回喂时账还没记（闸门与落库在回合尾的
 *   `ChatPromiseToolHandler`，可能被证据闸 / 去重挡下）→ 意图态，绝不预报「已记下」。
 * - **日历工具但参数没解析出动作（解析失败）/ 未知工具**：据实说「没能执行」。
 *
 * ⚠️ 兜底 `else` **绝不能**用「操作已执行」——那会让解析失败的日历调用、或刚发提案的约见面调用被谎报成已办成
 * （P1 + 复核揪出的约见面同类，均经此 else）。口吻（陪伴 vs 助手腔）交角色系统提示词把关（已在 follow-up 的
 * originalMessages 里）——此处只给**纯状态陈述**，不塞「请用你的口吻回应」之类指令。
 */
internal fun toolFollowUpResultText(
    calendarAction: CalendarAction?,
    toolName: String,
    calendarNeedsConfirmation: Boolean,
): String = when {
    calendarAction != null -> if (calendarNeedsConfirmation) {
        "已把「${calendarAction.actionVerb}${calendarAction.typeDisplayName}：${calendarAction.title}」作为确认卡发给用户，待其确认后才会生效（当前尚未执行）。"
    } else {
        "将为用户${calendarAction.actionVerb}${calendarAction.typeDisplayName}：${calendarAction.title}。"
    }

    FutureMeetingTool.isFutureMeetingTool(toolName) ->
        "已把这次见面约定作为提案交给用户确认（待其确认，当前尚未落定）。"

    PromiseChatTool.isPromiseTool(toolName) -> PROMISE_FOLLOW_UP_TEXT

    OfflineMeetingAction.isOfflineMeetingTool(toolName) ->
        if (toolName == "suggest_offline_meeting") "邀约卡片已发送，等待用户回应。" else "结束见面卡片已发送，等待用户确认。"

    else ->
        // 日历工具但参数没解析出动作（解析失败），或未知工具。绝不谎报「操作已执行」(P1)：据实说没办成。
        "这条操作没能执行（参数无法识别）。"
}
