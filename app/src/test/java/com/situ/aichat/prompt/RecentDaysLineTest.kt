package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.prompt.schedule.buildRecentDaysSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 【你最近几天的日子】段落格式化单测（时间感知三期·图纸 §4.1 / §3.4）。
 * 断言从图纸规格独立反推：标题逐字、每行 `M月D日：` + 「 → 」串联、日期倒序、每天上限 6 条 + 截断标记、
 * 跨午夜按 startTime 归属不拆、今天之后的事件一律不进本段、空表整段不出。
 */
class RecentDaysLineTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 9, 3)
    private val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun at(d: LocalDate, h: Int, m: Int): Long =
        LocalDateTime.of(d, java.time.LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        day: LocalDate,
        h: Int,
        period: String,
        activity: String,
        endH: Int = h + 1,
        type: String = "planned",
    ) = ScheduleEventEntity(
        uuid = "$day-$h-$activity",
        scheduleUuid = "s-$day",
        startTime = at(day, h, 0),
        endTime = at(day, endH.coerceAtMost(23), 30),
        periodLabel = period,
        location = "咖啡店",
        activity = activity,
        moodText = "惬意",
        eventTypeRaw = type,
    )

    @Test
    fun emptyEvents_returnsEmptyString() {
        // E11：新角色 / 日程系统刚开 / 首装冷启种子未播 → 整段不出（连标题都不出）。
        assertEquals("", buildRecentDaysSection(emptyList(), todayStart, zone))
    }

    @Test
    fun onlyTodayEvents_returnsEmptyString() {
        // 拍板 6：今天那份归既有【你今天完整的日程】模块，本段只管今天之前 → 只有今天的事件时整段不出。
        val events = listOf(event(today, 9, "上午", "开店"), event(today, 14, "下午", "拉花赶单"))
        assertEquals("", buildRecentDaysSection(events, todayStart, zone))
    }

    @Test
    fun threeDays_headerAndDescendingDateLines() {
        // §4.1 结构：标题 + 每天一行「M月D日：时段词 活动 → 时段词 活动」，日期倒序（昨天在最前），
        // 每天内按 startTime 升序；钟点 / 地点 / 心情一律不出（schedulePastLine 口径）。
        val d1 = today.minusDays(1)
        val d2 = today.minusDays(2)
        val d3 = today.minusDays(3)
        val events = listOf(
            event(d3, 9, "全天", "出差在外"),
            event(d1, 19, "晚上", "追剧"),
            event(d1, 9, "上午", "在家赶稿"),
            event(d2, 14, "下午", "收拾屋子"),
            event(d1, 14, "下午", "见客户"),
            event(d2, 9, "上午", "睡到中午"),
        )
        assertEquals(
            """
            【你最近几天的日子】
            9月2日：上午 在家赶稿 → 下午 见客户 → 晚上 追剧
            9月1日：上午 睡到中午 → 下午 收拾屋子
            8月31日：全天 出差在外
            """.trimIndent(),
            buildRecentDaysSection(events, todayStart, zone),
        )
    }

    @Test
    fun moreThanSixEventsInOneDay_truncatesWithMarker() {
        // E12：某天 8 条 → 只取 startTime 升序前 6 条，行末追加 " → …"。
        val d1 = today.minusDays(1)
        val events = (8..15).map { event(d1, it, "上午", "事件$it") }
        val out = buildRecentDaysSection(events, todayStart, zone)
        assertEquals(
            "【你最近几天的日子】\n9月2日：上午 事件8 → 上午 事件9 → 上午 事件10 → 上午 事件11 → " +
                "上午 事件12 → 上午 事件13 → …",
            out,
        )
        assertFalse("第 7 条起不得出现", out.contains("事件14"))
    }

    @Test
    fun exactlySixEvents_noTruncationMarker() {
        // 边界内侧：恰 6 条不加截断标记。
        val d1 = today.minusDays(1)
        val out = buildRecentDaysSection((8..13).map { event(d1, it, "上午", "事件$it") }, todayStart, zone)
        assertFalse("恰 6 条不该有截断标记", out.endsWith(" → …"))
        assertTrue(out.endsWith("事件13"))
    }

    @Test
    fun crossMidnightEvent_belongsToStartTimeDay_notSplit() {
        // E13：23:00 开始、次日 01:00 结束的事件归 startTime 那天，不拆成两行。
        val d2 = today.minusDays(2)
        val events = listOf(
            ScheduleEventEntity(
                uuid = "night", scheduleUuid = "s", startTime = at(d2, 23, 0),
                endTime = at(d2.plusDays(1), 1, 0), periodLabel = "深夜", activity = "赶通宵稿",
            ),
        )
        val out = buildRecentDaysSection(events, todayStart, zone)
        assertEquals("【你最近几天的日子】\n9月1日：深夜 赶通宵稿", out)
        assertEquals("只出一行，不跨两天拆", 2, out.split("\n").size)
    }

    @Test
    fun userInteractionEvents_filteredOut() {
        // 聊天写回 / 线下记录不是角色自己的日程（与今日日程模块、成长分析、「我们的日子」同一口径）。
        val d1 = today.minusDays(1)
        val events = listOf(
            event(d1, 9, "上午", "开店"),
            event(d1, 15, "下午", "和用户聊天", type = "userInteraction"),
        )
        assertEquals("【你最近几天的日子】\n9月2日：上午 开店", buildRecentDaysSection(events, todayStart, zone))
    }
}
