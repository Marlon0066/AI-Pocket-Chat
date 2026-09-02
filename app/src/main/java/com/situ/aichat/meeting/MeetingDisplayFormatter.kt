package com.situ.aichat.meeting

import com.situ.aichat.data.model.MeetingTimeGranularity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 约定时间的人话格式化（1:1 iOS `MeetingDisplayFormatter`）。确认卡 / 倒数小条 / 扫描 prompt 共用。
 * 纯函数、可注入 now / zone / locale，不依赖系统当前时间，可单测。App 仅支持中 + 英：中文 locale 走中文，其余走英文。
 */
object MeetingDisplayFormatter {

    /** dayOfWeek（MONDAY=1 … SUNDAY=7）→ 中文。 */
    private val CN_WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private fun isChinese(locale: Locale): Boolean = locale.language == "zh"

    /** `周一…周日`。internal：「我们的日子」`OurDayKey.weekdayCn` 复用同一张周几表（卷一图纸 §2.2·零行为变化）。 */
    internal fun cnWeekday(date: LocalDate): String = CN_WEEKDAYS[date.dayOfWeek.value - 1]

    /**
     * 确认卡 / 预览展示用人话。exact → "6月27日 周六 15:00"；dayOnly / vague → "6月27日 周六"。
     * 英文：exact → "Sat, Jun 27 at 15:00"；否则 "Sat, Jun 27"。
     */
    fun whenDisplay(
        scheduledAtMillis: Long,
        granularity: MeetingTimeGranularity,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
    ): String {
        val dt = Instant.ofEpochMilli(scheduledAtMillis).atZone(zone)
        if (!isChinese(locale)) return englishDate(dt, withTime = granularity == MeetingTimeGranularity.EXACT)
        val md = "${dt.monthValue}月${dt.dayOfMonth}日"
        val wd = cnWeekday(dt.toLocalDate())
        return if (granularity == MeetingTimeGranularity.EXACT) {
            "$md $wd %02d:%02d".format(dt.hour, dt.minute)
        } else {
            "$md $wd"
        }
    }

    /** 喂抽取扫描 LLM 的当前时间人话（恒中文，对齐聊天上下文时间表述）："2026-06-24 周三 15:30"。 */
    fun nowText(nowMillis: Long, zone: ZoneId): String {
        val dt = Instant.ofEpochMilli(nowMillis).atZone(zone)
        return "%04d-%02d-%02d %s %02d:%02d".format(
            dt.year, dt.monthValue, dt.dayOfMonth, cnWeekday(dt.toLocalDate()), dt.hour, dt.minute,
        )
    }

    /**
     * 倒数小条相对时间。今天(exact)→"今天 HH:mm" / 今天；明天→"明天"；2~6 天→"N天后"；7 天起 / 防御性过去→绝对"M月d日 周X"。
     * 英文：Today / Today HH:mm / Tomorrow / in N days / 绝对英文日期。
     */
    fun countdownText(
        scheduledAtMillis: Long,
        granularity: MeetingTimeGranularity,
        nowMillis: Long,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
    ): String {
        val schedDate = Instant.ofEpochMilli(scheduledAtMillis).atZone(zone).toLocalDate()
        val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(nowDate, schedDate)
        if (!isChinese(locale)) return englishCountdown(days, scheduledAtMillis, granularity, zone)
        return when {
            days == 0L -> if (granularity == MeetingTimeGranularity.EXACT) {
                val dt = Instant.ofEpochMilli(scheduledAtMillis).atZone(zone)
                "今天 %02d:%02d".format(dt.hour, dt.minute)
            } else {
                "今天"
            }
            days == 1L -> "明天"
            days in 2..6 -> "${days}天后"
            else -> whenDisplay(scheduledAtMillis, MeetingTimeGranularity.DAY_ONLY, zone, locale)
        }
    }

    // ── 英文路径 ──

    private fun englishDate(dt: ZonedDateTime, withTime: Boolean): String {
        val pattern = if (withTime) "EEE, MMM d 'at' HH:mm" else "EEE, MMM d"
        return dt.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
    }

    private fun englishCountdown(
        days: Long,
        scheduledAtMillis: Long,
        granularity: MeetingTimeGranularity,
        zone: ZoneId,
    ): String = when {
        days == 0L -> if (granularity == MeetingTimeGranularity.EXACT) {
            val dt = Instant.ofEpochMilli(scheduledAtMillis).atZone(zone)
            "Today %02d:%02d".format(dt.hour, dt.minute)
        } else {
            "Today"
        }
        days == 1L -> "Tomorrow"
        days in 2..6 -> "in $days days"
        else -> englishDate(Instant.ofEpochMilli(scheduledAtMillis).atZone(zone), withTime = false)
    }
}
