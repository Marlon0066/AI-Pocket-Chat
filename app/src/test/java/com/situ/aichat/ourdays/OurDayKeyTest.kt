package com.situ.aichat.ourdays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * T1-1（卷一图纸 §7.2）：日键纯函数。断言从总图纸 Z-1 / §5 E1 E2 独立反推——
 * 跨零点两键相邻、同毫秒异时区异键、半开区间长度整日、规范键往返、周几 / 中文日期、非法键返 null。
 */
class OurDayKeyTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val utc: ZoneId = ZoneOffset.UTC

    private fun millis(dt: LocalDateTime, zone: ZoneId): Long = dt.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun 跨零点两条消息分属相邻两键_E1() {
        val late = millis(LocalDateTime.of(2026, 9, 1, 23, 58), shanghai)
        val early = millis(LocalDateTime.of(2026, 9, 2, 0, 3), shanghai)
        assertEquals("2026-09-01", OurDayKey.dayKey(late, shanghai))
        assertEquals("2026-09-02", OurDayKey.dayKey(early, shanghai))
    }

    @Test
    fun 同一毫秒上海与UTC日键不同_E2() {
        // 上海 09-02 02:00 = UTC 09-01 18:00
        val ms = millis(LocalDateTime.of(2026, 9, 2, 2, 0), shanghai)
        assertEquals("2026-09-02", OurDayKey.dayKey(ms, shanghai))
        assertEquals("2026-09-01", OurDayKey.dayKey(ms, utc))
    }

    @Test
    fun 零点整点归当天_前一毫秒归前一天() {
        val midnight = millis(LocalDateTime.of(2026, 9, 2, 0, 0), shanghai)
        assertEquals("2026-09-02", OurDayKey.dayKey(midnight, shanghai))
        assertEquals("2026-09-01", OurDayKey.dayKey(midnight - 1, shanghai))
    }

    @Test
    fun dayBounds为整日半开区间_长度86400000() {
        val bounds = OurDayKey.dayBounds("2026-09-02", shanghai)
        val start = millis(LocalDateTime.of(2026, 9, 2, 0, 0), shanghai)
        assertEquals(start, bounds.first)
        assertEquals(start + 86_400_000L - 1, bounds.last)
        assertEquals(86_400_000L, bounds.last + 1 - bounds.first)
    }

    @Test
    fun dayBounds两端归属_首毫秒属本日_末毫秒加一属次日() {
        val bounds = OurDayKey.dayBounds("2026-09-02", shanghai)
        assertEquals("2026-09-02", OurDayKey.dayKey(bounds.first, shanghai))
        assertEquals("2026-09-02", OurDayKey.dayKey(bounds.last, shanghai))
        assertEquals("2026-09-03", OurDayKey.dayKey(bounds.last + 1, shanghai))
    }

    @Test
    fun dayBounds随时区变化() {
        val sh = OurDayKey.dayBounds("2026-09-02", shanghai)
        val u = OurDayKey.dayBounds("2026-09-02", utc)
        assertEquals("上海零点比 UTC 零点早 8 小时", 8 * 3_600_000L, u.first - sh.first)
    }

    @Test
    fun parse往返() {
        val date = LocalDate.of(2026, 9, 2)
        assertEquals("2026-09-02", OurDayKey.keyOf(date))
        assertEquals(date, OurDayKey.parse("2026-09-02"))
        assertEquals(date, OurDayKey.parse(OurDayKey.keyOf(date)))
        assertEquals(LocalDate.of(2024, 2, 29), OurDayKey.parse("2024-02-29"))
    }

    @Test
    fun 非法键parse返null() {
        assertNull(OurDayKey.parse(""))
        assertNull(OurDayKey.parse("abc"))
        assertNull(OurDayKey.parse("2026-9-2"))
        assertNull(OurDayKey.parse("2026-02-30"))
        assertNull(OurDayKey.parse("2026/09/02"))
        assertNull(OurDayKey.parse("2026-09-02 "))
    }

    @Test
    fun 周几与中文日期() {
        assertEquals("周三", OurDayKey.weekdayCn("2026-09-02"))
        assertEquals("周日", OurDayKey.weekdayCn("2026-09-06"))
        assertEquals("周一", OurDayKey.weekdayCn("2026-09-07"))
        assertEquals("2026年9月2日", OurDayKey.dateCn("2026-09-02"))
        assertEquals("2026年12月31日", OurDayKey.dateCn("2026-12-31"))
        assertEquals("", OurDayKey.weekdayCn("bad"))
        assertEquals("", OurDayKey.dateCn("bad"))
    }

    @Test
    fun 日键字典序等于时间序() {
        val keys = listOf("2026-09-02", "2025-12-31", "2026-08-31", "2026-01-01")
        val sorted = keys.sorted()
        val byDate = keys.sortedBy { OurDayKey.parse(it) }
        assertEquals(byDate, sorted)
        assertTrue("2026-09-02" > "2026-08-31")
    }
}
