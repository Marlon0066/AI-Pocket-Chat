package com.situ.aichat.toolcalling

import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.ui.chat.toolFollowUpResultText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 0-3 黄金快照：`toolFollowUpResultText` 各分支（回喂给模型的 `role=tool` 结果文案）字节快照。
 * 给 ③（大输出安全阀）看门——B 期会把这段文案过一遍 `truncateToolResultText`；当前文案都是短状态串、
 * **永不触发截断**，故包裹后必须字节不变。任一字节漂移即红。
 *
 * 与 [com.situ.aichat.ui.chat.ToolFollowUpResultTextTest] 互补：那边查语义（绝不谎报已完成），这边钉字节。
 */
class ToolFollowUpResultTextGoldenTest {

    private val createAction = CalendarAction.fromToolCallArguments(
        """{"action":"create_event","title":"开会","startDate":"2026-06-05T10:00:00"}""",
    )

    @Test fun confirm_mode() =
        assertEquals(GoldenResources.read("followup_confirm.txt"), toolFollowUpResultText(createAction, "calendar_action", true))

    @Test fun auto_mode() =
        assertEquals(GoldenResources.read("followup_auto.txt"), toolFollowUpResultText(createAction, "calendar_action", false))

    @Test fun future_meeting() =
        assertEquals(GoldenResources.read("followup_future.txt"), toolFollowUpResultText(null, "propose_future_meeting", false))

    @Test fun offline_suggest() =
        assertEquals(GoldenResources.read("followup_offline_suggest.txt"), toolFollowUpResultText(null, "suggest_offline_meeting", false))

    @Test fun offline_end() =
        assertEquals(GoldenResources.read("followup_offline_end.txt"), toolFollowUpResultText(null, "end_offline_meeting", false))

    /** T1-13（图纸 2026-09-06 约定工具调用化）：约定两工具共用同一句意图态文案，字节冻结。 */
    @Test fun promise_record() =
        assertEquals(GoldenResources.read("followup_promise.txt"), toolFollowUpResultText(null, "record_promise", false))

    @Test fun promise_resolve() =
        assertEquals(GoldenResources.read("followup_promise.txt"), toolFollowUpResultText(null, "resolve_promise", false))

    @Test fun parse_fail_or_unknown() =
        assertEquals(GoldenResources.read("followup_parsefail.txt"), toolFollowUpResultText(null, "calendar_action", false))
}
