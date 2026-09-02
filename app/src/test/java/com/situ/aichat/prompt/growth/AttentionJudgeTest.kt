package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 活人感内核·卷三《场内核与渲染收编》T1-6（图纸 §7.2 · E14 / E32 / E33）：D2 三源判定的纯函数部分。
 *
 * 断言从图纸 §4.3 **独立反推**：
 * - `recentCharacterLines`：倒数第 3 条 user 之后的角色行、3h 过期、线下行排除；按轮切不按条数（E33）
 * - 关键词：`不困` → AWAKE（先于 SLEEPY）、`好困` → SLEEPY、`有空` → AVAILABLE；最新一句优先
 * - 裁决表**每格一例**（含 E14 退化保护、E32 无自述无日程时深夜不新增 ⚠️）
 */
class AttentionJudgeTest {

    private val now = 1_700_000_000_000L
    private val h = 3_600_000L
    private fun msg(id: String, role: String, ts: Long, content: String = id, offline: Boolean = false) =
        MessageEntity(messageUUID = id, conversationUuid = "c", roleRaw = role, content = content, timestamp = ts, isOfflineMode = offline)

    // MARK: - recentCharacterLines

    @Test
    fun linesAfterThirdLastUserTurn_within3h_excludingOffline() {
        val messages = listOf(
            msg("u1", "user", now - 5 * h), msg("a1", "assistant", now - 5 * h + 60_000),
            msg("u2", "user", now - 2 * h), msg("a2", "assistant", now - 2 * h + 60_000),
            msg("u3", "user", now - h), msg("a3", "assistant", now - h + 60_000),
            msg("a3o", "assistant", now - 50 * 60_000, offline = true),
            msg("u4", "user", now - 10 * 60_000), msg("a4", "assistant", now - 9 * 60_000),
        )
        assertEquals(listOf("a2", "a3", "a4"), AttentionJudge.recentCharacterLines(messages, now))
    }

    @Test
    fun fewerThanThreeUserTurns_startsFromZero_butStillExpiresAt3h() {
        val messages = listOf(
            msg("a0", "assistant", now - 3 * h - 60_000),
            msg("a1", "assistant", now - 3 * h),
            msg("u1", "user", now - 2 * h), msg("a2", "assistant", now - h),
            msg("a2b", "assistant", now - h + 1_000),
            msg("u2", "user", now - 30 * 60_000), msg("a3", "assistant", now - 20 * 60_000),
        )
        assertEquals(listOf("a1", "a2", "a2b", "a3"), AttentionJudge.recentCharacterLines(messages, now))
    }

    @Test
    fun replyCountSettingDoesNotMatter_multiBubbleTurnsAllIncluded() {
        // E33：同一轮里角色连发 6 条，全部纳入；只看轮数
        val messages = listOf(msg("u1", "user", now - h)) + (1..6).map { msg("a$it", "assistant", now - h + it * 1_000L) } + listOf(msg("u2", "user", now - 10_000))
        assertEquals((1..6).map { "a$it" }, AttentionJudge.recentCharacterLines(messages, now))
    }

    @Test
    fun emptyMessages_givesEmptyLines() {
        assertEquals(emptyList<String>(), AttentionJudge.recentCharacterLines(emptyList(), now))
    }

    // MARK: - selfReport

    @Test
    fun keywords_mapToReports_andAwakeBeatsSleepyWithinOneLine() {
        assertEquals(SelfReport.AWAKE, AttentionJudge.selfReport(listOf("我不困，还能聊会儿")))
        assertEquals(SelfReport.SLEEPY, AttentionJudge.selfReport(listOf("好困啊")))
        assertEquals(SelfReport.AVAILABLE, AttentionJudge.selfReport(listOf("这会儿有空")))
        assertEquals(SelfReport.AWAKE, AttentionJudge.selfReport(listOf("翻来覆去睡不着觉")))
        assertEquals(SelfReport.SLEEPY, AttentionJudge.selfReport(listOf("眼皮打架了")))
        assertEquals(SelfReport.AVAILABLE, AttentionJudge.selfReport(listOf("刚开完会")))
        assertEquals(SelfReport.NONE, AttentionJudge.selfReport(listOf("今天天气不错")))
    }

    // MARK: - 修缮卷 T1-13（E39 / F21）：疑问句守卫

    @Test
    fun questionForms_areNotSelfReports_E39() {
        assertEquals("你睡了吗 ⇒ 在问对方", SelfReport.NONE, AttentionJudge.selfReport(listOf("你睡了吗？")))
        assertEquals(SelfReport.NONE, AttentionJudge.selfReport(listOf("你睡了没")))
        assertEquals(SelfReport.NONE, AttentionJudge.selfReport(listOf("睡了？")))
        assertEquals(SelfReport.NONE, AttentionJudge.selfReport(listOf("你有空吗", "还没睡呢?")))
    }

    @Test
    fun statementForms_stillSelfReports() {
        assertEquals(SelfReport.SLEEPY, AttentionJudge.selfReport(listOf("我睡了")))
        assertEquals(SelfReport.SLEEPY, AttentionJudge.selfReport(listOf("我先去睡了，晚安")))
        assertEquals(SelfReport.AVAILABLE, AttentionJudge.selfReport(listOf("我现在有空")))
    }

    @Test
    fun laterStatementOccurrence_countsEvenAfterAQuestion() {
        // 同一句里先问后答：「你睡了吗？我可睡不着」——第二处「睡不着」是陈述 ⇒ AWAKE
        assertEquals(SelfReport.AWAKE, AttentionJudge.selfReport(listOf("你睡了吗？我可睡不着")))
        assertEquals("「睡了吗……我也睡了」后一次算", SelfReport.SLEEPY, AttentionJudge.selfReport(listOf("你睡了吗，我也睡了")))
        assertEquals(SelfReport.NONE, AttentionJudge.selfReport(emptyList()))
    }

    @Test
    fun latestLineWins() {
        assertEquals(SelfReport.AWAKE, AttentionJudge.selfReport(listOf("好困", "算了，睡不着")))
        assertEquals(SelfReport.SLEEPY, AttentionJudge.selfReport(listOf("睡不着", "现在好困")))
        assertEquals(SelfReport.AVAILABLE, AttentionJudge.selfReport(listOf("好困", "天气", "下班了")))
    }

    // MARK: - 裁决表（每格一例）

    @Test
    fun sleep_awake_enoughArousal_overrides() {
        assertEquals(AttentionVerdict.AWAKE_OVERRIDE, AttentionJudge.judge(SelfReport.AWAKE, ScheduleSignal.SLEEP, arousal = 20, hour = 1))
    }

    @Test
    fun sleep_awake_lowArousal_degradesToOldSleep() {
        assertEquals(AttentionVerdict.SLEEP_OLD, AttentionJudge.judge(SelfReport.AWAKE, ScheduleSignal.SLEEP, arousal = 19, hour = 1))
    }

    @Test
    fun sleep_otherReports_oldSleep() {
        for (r in listOf(SelfReport.SLEEPY, SelfReport.NONE, SelfReport.AVAILABLE)) {
            assertEquals(r.name, AttentionVerdict.SLEEP_OLD, AttentionJudge.judge(r, ScheduleSignal.SLEEP, arousal = 80, hour = 1))
        }
    }

    @Test
    fun phoneUnavailable_available_enoughArousal_overrides() {
        assertEquals(AttentionVerdict.AVAILABLE_OVERRIDE, AttentionJudge.judge(SelfReport.AVAILABLE, ScheduleSignal.PHONE_UNAVAILABLE, arousal = 20, hour = 14))
    }

    @Test
    fun phoneUnavailable_otherReportsOrLowArousal_oldDistracted() {
        assertEquals(AttentionVerdict.DISTRACTED_OLD, AttentionJudge.judge(SelfReport.AVAILABLE, ScheduleSignal.PHONE_UNAVAILABLE, arousal = 19, hour = 14))
        for (r in listOf(SelfReport.AWAKE, SelfReport.SLEEPY, SelfReport.NONE)) {
            assertEquals(r.name, AttentionVerdict.DISTRACTED_OLD, AttentionJudge.judge(r, ScheduleSignal.PHONE_UNAVAILABLE, arousal = 80, hour = 14))
        }
    }

    @Test
    fun none_sleepy_atNight_oldSleep() {
        for (hour in listOf(22, 23, 0, 3, 6)) {
            assertEquals("hour=$hour", AttentionVerdict.SLEEP_OLD, AttentionJudge.judge(SelfReport.SLEEPY, ScheduleSignal.NONE, arousal = 5, hour = hour))
        }
    }

    @Test
    fun none_sleepy_byDay_orOtherReports_none() {
        for (hour in listOf(7, 12, 21)) {
            assertEquals("hour=$hour", AttentionVerdict.NONE, AttentionJudge.judge(SelfReport.SLEEPY, ScheduleSignal.NONE, arousal = 5, hour = hour))
        }
        for (r in listOf(SelfReport.AWAKE, SelfReport.AVAILABLE, SelfReport.NONE)) {
            assertEquals(r.name, AttentionVerdict.NONE, AttentionJudge.judge(r, ScheduleSignal.NONE, arousal = 5, hour = 2))
        }
    }

    @Test
    fun lowArousalAlone_neverAddsALine_E32() {
        assertEquals(AttentionVerdict.NONE, AttentionJudge.judge(SelfReport.NONE, ScheduleSignal.NONE, arousal = 0, hour = 3))
    }
}
