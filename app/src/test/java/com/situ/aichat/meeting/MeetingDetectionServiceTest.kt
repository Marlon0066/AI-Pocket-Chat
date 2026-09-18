package com.situ.aichat.meeting

import com.situ.aichat.data.model.MeetingCandidate
import com.situ.aichat.data.model.MeetingCandidateIntent
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.meeting.MeetingDetectionService.ExistingAppointmentBrief
import com.situ.aichat.meeting.MeetingDetectionService.ScanTriggerDecision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描引擎纯函数 + scanForCandidates（注入 fake completionFn）单测。覆盖冷却节奏 / 宽容解析 / 校验 / prompt 组装 / 空响应重试。
 */
class MeetingDetectionServiceTest {

    // ── 冷却判定 ──

    @Test fun trigger_belowMinRounds_skips() {
        assertEquals(
            ScanTriggerDecision.SkipBelowRounds,
            MeetingDetectionService.scanTriggerDecision(roundsSinceLastScan = 3, lastScanMillis = null, lastFailureMillis = null, nowMillis = 10_000_000L),
        )
    }

    @Test fun trigger_firstScanWhenEnoughRounds() {
        assertEquals(
            ScanTriggerDecision.Trigger,
            MeetingDetectionService.scanTriggerDecision(roundsSinceLastScan = 4, lastScanMillis = null, lastFailureMillis = null, nowMillis = 10_000_000L),
        )
    }

    @Test fun trigger_failureCooldownActive_skips() {
        val now = 10_000_000L
        val d = MeetingDetectionService.scanTriggerDecision(
            roundsSinceLastScan = 10, lastScanMillis = null, lastFailureMillis = now - 60_000L, nowMillis = now,
        )
        // 失败 60s 前，短冷却 300s 内 → 还剩 240s
        assertEquals(ScanTriggerDecision.SkipFailureCooldown(240L), d)
    }

    @Test fun trigger_failureCooldownElapsed_triggers() {
        val now = 10_000_000L
        assertEquals(
            ScanTriggerDecision.Trigger,
            MeetingDetectionService.scanTriggerDecision(
                roundsSinceLastScan = 4, lastScanMillis = null, lastFailureMillis = now - 301_000L, nowMillis = now,
            ),
        )
    }

    @Test fun trigger_successCooldownNotElapsed_skips() {
        val now = 10_000_000L
        // 距上次扫描 100s（<600）、轮数 5（<12）→ 冷却中
        val d = MeetingDetectionService.scanTriggerDecision(
            roundsSinceLastScan = 5, lastScanMillis = now - 100_000L, lastFailureMillis = null, nowMillis = now,
        )
        assertEquals(ScanTriggerDecision.SkipCooldown(100L), d)
    }

    @Test fun trigger_successCooldownElapsed_triggers() {
        val now = 10_000_000L
        assertEquals(
            ScanTriggerDecision.Trigger,
            MeetingDetectionService.scanTriggerDecision(
                roundsSinceLastScan = 5, lastScanMillis = now - 601_000L, lastFailureMillis = null, nowMillis = now,
            ),
        )
    }

    @Test fun trigger_countTrackForcesEvenWithinCooldown() {
        val now = 10_000_000L
        // 距上次仅 100s（<600）但已积 12 轮 → 强制触发（高频聊天不至于久等）
        assertEquals(
            ScanTriggerDecision.Trigger,
            MeetingDetectionService.scanTriggerDecision(
                roundsSinceLastScan = 12, lastScanMillis = now - 100_000L, lastFailureMillis = null, nowMillis = now,
            ),
        )
    }

    // ── 解析（宽容） ──

    @Test fun parse_validNew() {
        val c = MeetingDetectionService.parseCandidates(
            """{"intent":"new","raw_when":"周六下午","activity":"看电影","proposed_by":"character","confidence":"high"}""",
        )
        assertEquals(1, c.size)
        assertEquals(MeetingCandidateIntent.NEW, c[0].intent)
        assertEquals("看电影", c[0].activity)
        assertEquals("周六下午", c[0].rawWhen)
    }

    @Test fun parse_hasMeetingFalse_empty() {
        assertTrue(MeetingDetectionService.parseCandidates("""{"has_meeting":false,"intent":"new"}""").isEmpty())
    }

    @Test fun parse_intentNone_empty() {
        assertTrue(MeetingDetectionService.parseCandidates("""{"intent":"none"}""").isEmpty())
    }

    @Test fun parse_tolerates_jsonFenceAndSurroundingText() {
        val raw = "好的，这是结果：\n```json\n{\"intent\":\"new\",\"activity\":\"吃饭\"}\n```\n（完毕）"
        val c = MeetingDetectionService.parseCandidates(raw)
        assertEquals(1, c.size)
        assertEquals("吃饭", c[0].activity)
    }

    @Test fun parse_emptyTargetId_becomesNull() {
        val c = MeetingDetectionService.parseCandidates("""{"intent":"new","activity":"看展","target_id":""}""")
        assertNull(c[0].targetAppointmentUuid)
    }

    @Test fun parse_cancelWithTarget_keepsTarget() {
        val c = MeetingDetectionService.parseCandidates("""{"intent":"cancel","target_id":"appt-9"}""")
        assertEquals("appt-9", c[0].targetAppointmentUuid)
    }

    @Test fun parse_garbage_empty() {
        assertTrue(MeetingDetectionService.parseCandidates("这不是 JSON").isEmpty())
    }

    // ── 校验 ──

    @Test fun validate_dropsNone() {
        assertTrue(MeetingDetectionService.validate(listOf(MeetingCandidate(intent = MeetingCandidateIntent.NONE))).isEmpty())
    }

    @Test fun validate_cancelNeedsTarget() {
        assertTrue(MeetingDetectionService.validate(listOf(MeetingCandidate(intent = MeetingCandidateIntent.CANCEL))).isEmpty())
        assertEquals(
            1,
            MeetingDetectionService.validate(listOf(MeetingCandidate(intent = MeetingCandidateIntent.CANCEL, targetAppointmentUuid = "a"))).size,
        )
    }

    @Test fun validate_newNeedsTimeOrContent() {
        // 空壳 new → 丢
        assertTrue(MeetingDetectionService.validate(listOf(MeetingCandidate(intent = MeetingCandidateIntent.NEW))).isEmpty())
        // 有活动 → 留
        assertEquals(1, MeetingDetectionService.validate(listOf(MeetingCandidate(intent = MeetingCandidateIntent.NEW, activity = "看电影"))).size)
        // 有时间说法 → 留
        assertEquals(1, MeetingDetectionService.validate(listOf(MeetingCandidate(intent = MeetingCandidateIntent.NEW, rawWhen = "周六"))).size)
    }

    // ── prompt 组装 ──

    @Test fun prompt_includesExistingBlock() {
        val p = MeetingDetectionService.buildScanPrompt(
            conversationText = "用户：周六一起看电影吧",
            existing = listOf(ExistingAppointmentBrief("a1", "周六下午", "看电影")),
            characterName = "小樱",
            userName = "阿明",
            nowText = "2026-06-24 周三 15:30",
        )
        assertTrue(p.contains("- id=a1 | 时间：周六下午 | 活动：看电影"))
        assertTrue(p.contains("小樱"))
        assertTrue(p.contains("阿明"))
        assertTrue(p.contains("用户：周六一起看电影吧"))
        assertTrue(p.contains("2026-06-24 周三 15:30"))
    }

    @Test fun prompt_emptyExistingAndNameFallbacks() {
        val p = MeetingDetectionService.buildScanPrompt("对话", emptyList(), "", "", "现在")
        assertTrue(p.contains("（当前没有待定的约定）"))
        assertTrue(p.contains("AI 角色"))
        assertTrue(p.contains("用户"))
    }

    /** C3 回归钉（图纸 §3）：recentlyHonored 缺省/传空 → 提示词与旧签名输出**字节级一致**。 */
    @Test fun prompt_emptyRecentlyHonored_byteIdenticalToLegacy() {
        val legacy = MeetingDetectionService.buildScanPrompt(
            conversationText = "用户：周六一起看电影吧",
            existing = listOf(ExistingAppointmentBrief("a1", "周六下午", "看电影")),
            characterName = "小樱", userName = "阿明", nowText = "2026-06-24 周三 15:30",
        )
        val explicitEmpty = MeetingDetectionService.buildScanPrompt(
            conversationText = "用户：周六一起看电影吧",
            existing = listOf(ExistingAppointmentBrief("a1", "周六下午", "看电影")),
            characterName = "小樱", userName = "阿明", nowText = "2026-06-24 周三 15:30",
            recentlyHonored = emptyList(),
        )
        assertEquals(legacy, explicitEmpty)
        assertFalse(legacy.contains("近期已赴约"))
    }

    /** C3：已赴约块渲染——带时间/活动/「（已赴约）」标注 + 旧事重提禁令；**不给 id**（终态不可作 target）。 */
    @Test fun prompt_includesRecentlyHonoredBlock_withoutIds() {
        val p = MeetingDetectionService.buildScanPrompt(
            conversationText = "用户：昨天买裙子好开心",
            existing = emptyList(),
            characterName = "小樱", userName = "阿明", nowText = "2026-08-31 周一 10:00",
            recentlyHonored = listOf(ExistingAppointmentBrief("h1", "8月30日 周日", "买裙子")),
        )
        assertTrue(p.contains("【近期已赴约的见面】"))
        assertTrue(p.contains("- 时间：8月30日 周日 | 活动：买裙子（已赴约）"))
        assertTrue(p.contains("不要为它们输出 new"))
        assertFalse("终态不暴露 id 防被当 target", p.contains("id=h1"))
    }

    /**
     * 多行插值不得带出模板缩进（2026-09-18 五处小修 #1）：旧写法「原始字符串里插值 + 末尾 trimIndent()」是先插值后 trimIndent，
     * 对话（恒多行）/ 待定约定 ≥2 条 / 已赴约块 任一跨行 → 最小公共缩进=0 → 发给模型的每行都带 12 格行首空格。
     */
    @Test fun prompt_multiLineInterpolation_noIndentLeak() {
        val p = MeetingDetectionService.buildScanPrompt(
            conversationText = "[2026-06-24 周三 15:20] 阿明：周六一起看电影吧\n[2026-06-24 周三 15:21] 小樱：好呀",
            existing = listOf(
                ExistingAppointmentBrief("a1", "周六下午", "看电影"),
                ExistingAppointmentBrief("a2", "周日晚上", "吃火锅"),
            ),
            characterName = "小樱", userName = "阿明", nowText = "2026-06-24 周三 15:30",
            recentlyHonored = listOf(ExistingAppointmentBrief("h1", "8月30日 周日", "买裙子")),
        )
        val lines = p.lines()
        // JSON 块内只有两格相对缩进；三格及以上 = 模板缩进泄漏。
        assertTrue("有行带模板缩进：" + lines.filter { it.startsWith("   ") }, lines.none { it.startsWith("   ") })
        assertTrue(p.startsWith("你是一个严格的信息抽取器。判断 阿明 和 小樱 在最近这段对话里"))
        assertTrue(lines.contains("当前时间：2026-06-24 周三 15:30"))
        assertTrue(lines.contains("【判定标准】"))
        assertTrue(
            p.contains(
                "【已有待定约定】\n- id=a1 | 时间：周六下午 | 活动：看电影\n- id=a2 | 时间：周日晚上 | 活动：吃火锅\n\n【近期已赴约的见面】",
            ),
        )
        assertTrue(lines.contains("- 时间：8月30日 周日 | 活动：买裙子（已赴约）"))
        assertTrue(
            p.contains("【最近对话】\n[2026-06-24 周三 15:20] 阿明：周六一起看电影吧\n[2026-06-24 周三 15:21] 小樱：好呀\n\n【输出】"),
        )
        assertTrue(lines.contains("  \"intent\": \"new | reschedule | cancel | confirm | none\","))
        assertTrue(lines.contains("  \"invitation\": \"（小樱口吻的一句邀约，可空）\","))
        assertEquals("没有任何未来约定时，输出 {\"intent\":\"none\"}。", lines.last())
    }

    /**
     * #1 回归钉：插值全为单行时（旧实现唯一正确的情形），提示词与修前**逐字节相同**。
     * 金标 = 修前实现对同一输入的实跑输出（2026-09-18 取证），不是照抄新实现。
     */
    @Test fun prompt_singleLineInputs_byteIdenticalToPreFixGolden() {
        val p = MeetingDetectionService.buildScanPrompt(
            conversationText = "[2026-06-24 周三 15:20] 阿明：周六一起看电影吧",
            existing = listOf(ExistingAppointmentBrief("a1", "周六下午", "看电影")),
            characterName = "小樱", userName = "阿明", nowText = "2026-06-24 周三 15:30",
        )
        val golden = """
            你是一个严格的信息抽取器。判断 阿明 和 小樱 在最近这段对话里，是否**明确约定了未来某天**线下见面。

            当前时间：2026-06-24 周三 15:30

            【判定标准】
            - 只算**确定的约定**：双方都明确同意，或一方提议、另一方答应。
            - **排除客套寒暄**（如「改天约」「有空再说」「下次吧」）——这些不是约定。
            - **排除当下立刻见面**（那是另一套流程）。这里只处理**未来某天**的约定。
            - 也要识别对【已有待定约定】的：改期(reschedule)、取消(cancel)、确认(confirm)。

            【已有待定约定】
            - id=a1 | 时间：周六下午 | 活动：看电影

            【最近对话】
            [2026-06-24 周三 15:20] 阿明：周六一起看电影吧

            【输出】只输出一个 JSON 对象，不要任何额外文字或解释：
            {
              "intent": "new | reschedule | cancel | confirm | none",
              "target_id": "（reschedule/cancel/confirm 时填上面某条 id，否则空字符串）",
              "iso_datetime": "（依据当前时间推算的具体时间，ISO8601 带时区，如 2026-06-27T15:00:00+08:00；只到天则给当天 19:00）",
              "raw_when": "（对话里原话的时间说法，如 周六下午）",
              "location": "（地点，没有就空字符串）",
              "activity": "（一起做什么，没有就空字符串）",
              "invitation": "（小樱口吻的一句邀约，可空）",
              "tension_hint": "（≤12字、给用户看的隐晦暗示，可空）",
              "hidden_tension": "（一句小樱藏着的小心事，用户不可见，可空）",
              "proposed_by": "character | user",
              "confidence": "high | medium | low"
            }
            没有任何未来约定时，输出 {"intent":"none"}。
        """.trimIndent()
        assertEquals(golden, p)
    }

    // ── scanForCandidates（注入 fake completionFn） ──

    @Test fun scan_parsesValidResponse() = runBlocking {
        val json = """{"intent":"new","raw_when":"周六下午","activity":"看电影"}"""
        val result = MeetingDetectionService.scanForCandidates("sys") { _: List<ChatMessageDto>, _: Double -> json }
        assertEquals(1, result.size)
        assertEquals(MeetingCandidateIntent.NEW, result[0].intent)
    }

    @Test fun scan_retriesOnceOnEmptyThenSucceeds() = runBlocking {
        var calls = 0
        val json = """{"intent":"new","activity":"吃饭"}"""
        val result = MeetingDetectionService.scanForCandidates("sys") { _, _ ->
            calls++
            if (calls == 1) "" else json
        }
        assertEquals(2, calls)
        assertEquals(1, result.size)
    }

    @Test fun scan_twoEmptyResponses_returnsEmpty() = runBlocking {
        var calls = 0
        val result = MeetingDetectionService.scanForCandidates("sys") { _, _ -> calls++; "" }
        assertEquals(2, calls)
        assertTrue(result.isEmpty())
    }
}
