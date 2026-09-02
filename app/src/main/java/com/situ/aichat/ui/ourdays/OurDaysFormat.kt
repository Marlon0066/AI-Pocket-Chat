package com.situ.aichat.ui.ourdays

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * 日期 / 周几 / 时刻格式化（卷三图纸 §2.1）：pattern 由调用方从资源取（`our_days_fmt_*`），locale 跟系统（W-15）。
 * 周几表头 = `DayOfWeek.getDisplayName(NARROW, locale)`（zh-CN → 一 二 … 日）。
 */
internal object OurDaysFormat {

    fun date(date: LocalDate, pattern: String, locale: Locale = Locale.getDefault()): String =
        date.format(DateTimeFormatter.ofPattern(pattern, locale))

    fun time(millis: Long, zone: ZoneId, pattern: String, locale: Locale = Locale.getDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern(pattern, locale))

    fun weekdayNarrow(day: DayOfWeek, locale: Locale = Locale.getDefault()): String =
        day.getDisplayName(TextStyle.NARROW, locale)

    /** 从周首日起的 7 个窄表头。 */
    fun weekdayLabels(weekFields: WeekFields, locale: Locale = Locale.getDefault()): List<String> =
        (0L until 7L).map { weekdayNarrow(weekFields.firstDayOfWeek.plus(it), locale) }

    /** 迷你月题（zh-CN →「8月」· en →「Aug」）。 */
    fun monthShort(yearMonth: YearMonth, locale: Locale = Locale.getDefault()): String =
        yearMonth.month.getDisplayName(TextStyle.SHORT, locale)
}
