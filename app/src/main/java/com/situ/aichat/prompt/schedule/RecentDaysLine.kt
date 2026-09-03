package com.situ.aichat.prompt.schedule

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import java.time.Instant
import java.time.ZoneId

/**
 * 「【你最近几天的日子】」段落格式化（时间感知三期·图纸 §4.1）——每天一行的流水账，让角色说得出
 * 「这两天在忙什么」，而不是把前天的事当成正在发生。
 *
 * 措辞主语恒为**角色自己**（「你这几天做了…」），**禁**「你不在的这几天…」——后者把用户当原点，
 * 导出依附感；角色的日程是角色的日程（用户拍板 7·2026-09-03）。
 *
 * 只格式化不查库：事件由调用方（两个发起点 → `BuildContext.recentDaysScheduleEvents`）查好传入。
 * 每行的事件串联复用 [schedulePastLine]（与「我们的日子」事实层共用·**只调用不修改**，它有字节级哨兵）。
 */

/** 每天最多列几条事件（超出截断，行末追加 [TRUNCATION_SUFFIX]）。 */
private const val MAX_EVENTS_PER_DAY = 6

/** 被截断那天的行末标记。 */
private const val TRUNCATION_SUFFIX = " → …"

/** 段标题（已 grep 实证不撞 `DirtyMessageDetector` 任何保留标记）。 */
internal const val RECENT_DAYS_HEADER = "【你最近几天的日子】"

/** 聊天写回 / 线下记录，不是角色自己的日程——与今日日程模块、成长分析、「我们的日子」同一口径过滤。 */
private const val EVENT_TYPE_USER_INTERACTION = "userInteraction"

/**
 * 把「今天之前」的日程事件渲染成段落；无任何往日事件 → 返 `""`（**整段不出，连标题都不出**）。
 *
 * @param events 调用方查好的最近几天事件（含今天的也无妨，[todayStartMillis] 之后的一律滤掉——
 *   今天那份归既有 `【你今天完整的日程】` 模块，两段零重叠）
 * @param todayStartMillis 今天 0 点（epoch ms）
 * @param zone 分组用时区（与同一次装配的日程模块同源，保证同一次装配内单一时区源）
 */
internal fun buildRecentDaysSection(
    events: List<ScheduleEventEntity>,
    todayStartMillis: Long,
    zone: ZoneId,
): String {
    // 跨午夜事件（23:00–01:00）按 startTime 归属前一天，不拆——拆开会让同一件事在两行各出现半截。
    val byDay = events
        .filter { it.eventTypeRaw != EVENT_TYPE_USER_INTERACTION && it.startTime < todayStartMillis }
        .groupBy { Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate() }
    if (byDay.isEmpty()) return ""

    val lines = byDay.keys.sortedDescending().map { date ->  // 日期倒序：昨天在最前
        val dayEvents = byDay.getValue(date).sortedBy { it.startTime }
        val shown = dayEvents.take(MAX_EVENTS_PER_DAY)
        val suffix = if (dayEvents.size > MAX_EVENTS_PER_DAY) TRUNCATION_SUFFIX else ""
        "${date.monthValue}月${date.dayOfMonth}日：${schedulePastLine(shown)}$suffix"
    }
    return (listOf(RECENT_DAYS_HEADER) + lines).joinToString("\n")
}
