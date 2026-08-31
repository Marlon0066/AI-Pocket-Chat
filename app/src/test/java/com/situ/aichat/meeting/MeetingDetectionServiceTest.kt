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
