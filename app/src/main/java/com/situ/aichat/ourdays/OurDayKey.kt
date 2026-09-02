package com.situ.aichat.ourdays

import com.situ.aichat.meeting.MeetingDisplayFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * 「我们的日子」日键纯函数（卷一《沉淀》图纸 §2.1 · 总图纸 Z-1）：日键 = `yyyy-MM-dd`（Locale.ROOT），
 * 按**写入时刻的 `clock.zone`** 取本地日；字典序 = 时间序（DAO `BETWEEN` 直接可用）。
 *
 * 全部纯函数：零系统时钟、零系统默认时区（§9.5）——时区一律由调用方从注入的 [java.time.Clock] 传入。
 * [parse] 只认**规范键**（解析后回格式化须与原串逐字相等），非法 / 非规范一律返 `null`。
 */
object OurDayKey {

    /** 日键格式（Locale.ROOT·锁定）。 */
    val DAY_KEY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    /** 提示词用中文日期 `yyyy年M月d日`（总图纸 §4.1 `{日期}`）。 */
    private val DATE_CN_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.ROOT)

    /** 毫秒时刻在 [zone] 的本地日键。 */
    fun dayKey(millis: Long, zone: ZoneId): String = keyOf(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())

    fun keyOf(date: LocalDate): String = date.format(DAY_KEY_FORMATTER)

    /** 规范键 → 日期；非法（`"abc"` / `"2026-9-2"` / `"2026-02-30"`）→ `null`。 */
    fun parse(key: String): LocalDate? {
        val date = try {
            LocalDate.parse(key, DAY_KEY_FORMATTER)
        } catch (e: DateTimeParseException) {
            return null
        }
        return date.takeIf { keyOf(it) == key }
    }

    /**
     * 该日在 [zone] 的毫秒半开区间 `[dayStart, nextDayStart)`——`LongRange.first` = 当天零点，
     * `last + 1` = 次日零点（调用方给 DAO `< :end` 半开查询时传 `last + 1`）。非法键抛 [IllegalArgumentException]。
     */
    fun dayBounds(key: String, zone: ZoneId): LongRange {
        val date = requireNotNull(parse(key)) { "非法日键" }
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start until end
    }

    /** `周一…周日`（委托 [MeetingDisplayFormatter.cnWeekday]·单源周几表）；非法键返 `""`。 */
    fun weekdayCn(key: String): String = parse(key)?.let { MeetingDisplayFormatter.cnWeekday(it) } ?: ""

    /** `2026年9月2日`；非法键返 `""`。 */
    fun dateCn(key: String): String = parse(key)?.format(DATE_CN_FORMATTER) ?: ""
}
