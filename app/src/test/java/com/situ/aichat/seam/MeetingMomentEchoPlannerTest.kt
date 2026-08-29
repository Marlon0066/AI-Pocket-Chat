package com.situ.aichat.seam

import com.situ.aichat.offline.MeetingMomentEchoPlanner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-K3（卷二 §5④·图纸 §3.3/§7）：[MeetingMomentEchoPlanner] 三纯函数 + 确定性 uuid 的边界矩阵。
 *
 * 断言从图纸 §3.3 与 M5 数值包**独立反推**：概率 75（roll<75）/ 首延 ∈[180,420] /
 * 深夜窗 23:30–07:00 / 顺延落点 09:00–11:30（min-of-day ∈[540,690]）/ uuid 种子 `moment:echo:{sessionId}`。
 * 时区一律显式入参（E13：固定时区 + 跨日；另跑一个有夏令时的时区）。
 */
class MeetingMomentEchoPlannerTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 固定 nextLong 返回值的假随机（纯函数的可复现夹具）。 */
    private class FixedRandom(private val value: Long) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(until: Long): Long = value.coerceIn(0L, until - 1)
    }

    private fun millisAt(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** 顺延后的落点在一天中的第几分钟（跨日按落点当天算）。 */
    private fun landingMinuteOfDay(nowMillis: Long, zone: ZoneId, delayMinutes: Long): Int {
        val landing = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).plusMinutes(delayMinutes)
        return landing.hour * 60 + landing.minute
    }

    // ══════ 掷点 ══════

    @Test fun 掷点边界_74中签75不中() {
        assertTrue("roll=74 应中签（0..74 = 75%）", MeetingMomentEchoPlanner.shouldPost(74))
        assertTrue(MeetingMomentEchoPlanner.shouldPost(0))
        assertEquals(false, MeetingMomentEchoPlanner.shouldPost(75))
        assertEquals(false, MeetingMomentEchoPlanner.shouldPost(99))
    }

    /** 0..99 全值域恰 75 个中签点 = 概率就是 75%。 */
    @Test fun 掷点全值域_恰75个中签() {
        assertEquals(75, (0..99).count { MeetingMomentEchoPlanner.shouldPost(it) })
    }

    // ══════ 首延 ══════

    @Test fun 首延_下界180上界420() {
        assertEquals(180L, MeetingMomentEchoPlanner.initialDelayMinutes(FixedRandom(0L)))
        assertEquals(420L, MeetingMomentEchoPlanner.initialDelayMinutes(FixedRandom(240L)))
        assertEquals("上界开区间：传超界值也被钳在 240", 420L, MeetingMomentEchoPlanner.initialDelayMinutes(FixedRandom(999L)))
    }

    @Test fun 首延_真随机抽查恒落3到7小时() {
        val random = Random(20260827)
        repeat(500) {
            val d = MeetingMomentEchoPlanner.initialDelayMinutes(random)
            assertTrue("首延 $d 应 ∈[180,420]", d in 180L..420L)
        }
    }

    // ══════ 深夜窗 ══════

    @Test fun 深夜窗矩阵_2329不算2330起算() {
        assertNull("23:29 不是深夜 → 照常发", MeetingMomentEchoPlanner.lateNightRescheduleMinutes(
            millisAt(shanghai, 2026, 8, 27, 23, 29), shanghai, FixedRandom(0L),
        ))
        assertNotNull("23:30 起进深夜窗", MeetingMomentEchoPlanner.lateNightRescheduleMinutes(
            millisAt(shanghai, 2026, 8, 27, 23, 30), shanghai, FixedRandom(0L),
        ))
        assertNotNull("00:00 在深夜窗内", MeetingMomentEchoPlanner.lateNightRescheduleMinutes(
            millisAt(shanghai, 2026, 8, 28, 0, 0), shanghai, FixedRandom(0L),
        ))
        assertNotNull("06:59 仍在深夜窗内", MeetingMomentEchoPlanner.lateNightRescheduleMinutes(
            millisAt(shanghai, 2026, 8, 28, 6, 59), shanghai, FixedRandom(0L),
        ))
        assertNull("07:00 整已出窗", MeetingMomentEchoPlanner.lateNightRescheduleMinutes(
            millisAt(shanghai, 2026, 8, 28, 7, 0), shanghai, FixedRandom(0L),
        ))
        assertNull("白天照常发", MeetingMomentEchoPlanner.lateNightRescheduleMinutes(
            millisAt(shanghai, 2026, 8, 27, 15, 0), shanghai, FixedRandom(0L),
        ))
    }

    /** 23:30 顺延跨日到次日 09:00（jitter=0）：9 小时 30 分 = 570 分钟。 */
    @Test fun 顺延_深夜跨日到次日九点() {
        val now = millisAt(shanghai, 2026, 8, 27, 23, 30)
        val delay = MeetingMomentEchoPlanner.lateNightRescheduleMinutes(now, shanghai, FixedRandom(0L))!!
        assertEquals(570L, delay)
        assertEquals(9 * 60, landingMinuteOfDay(now, shanghai, delay))
    }

    /** 凌晨 03:00 顺延到**当日** 09:00（不跨日）：360 分钟。 */
    @Test fun 顺延_凌晨到当日九点不跨日() {
        val now = millisAt(shanghai, 2026, 8, 28, 3, 0)
        val delay = MeetingMomentEchoPlanner.lateNightRescheduleMinutes(now, shanghai, FixedRandom(0L))!!
        assertEquals(360L, delay)
        assertEquals(9 * 60, landingMinuteOfDay(now, shanghai, delay))
    }

    /** 落点随机跨度 0–150 分钟 → 恒落 09:00–11:30（min-of-day ∈[540,690]）。 */
    @Test fun 顺延落点_恒在九点到十一点半之间() {
        val random = Random(4242)
        val nows = listOf(
            millisAt(shanghai, 2026, 8, 27, 23, 30),
            millisAt(shanghai, 2026, 8, 27, 23, 59),
            millisAt(shanghai, 2026, 8, 28, 0, 0),
            millisAt(shanghai, 2026, 8, 28, 4, 17),
            millisAt(shanghai, 2026, 8, 28, 6, 59),
        )
        nows.forEach { now ->
            repeat(60) {
                val delay = MeetingMomentEchoPlanner.lateNightRescheduleMinutes(now, shanghai, random)!!
                val landing = landingMinuteOfDay(now, shanghai, delay)
                assertTrue("落点 $landing 应 ∈[540,690]（09:00–11:30）", landing in 540..690)
            }
        }
    }

    /** jitter 上界：150 分钟 → 落点恰 11:30。 */
    @Test fun 顺延落点_上界恰十一点半() {
        val now = millisAt(shanghai, 2026, 8, 28, 3, 0)
        val delay = MeetingMomentEchoPlanner.lateNightRescheduleMinutes(now, shanghai, FixedRandom(150L))!!
        assertEquals(360L + 150L, delay)
        assertEquals(11 * 60 + 30, landingMinuteOfDay(now, shanghai, delay))
    }

    /** E13：秒尾不把落点挤到 09:00 之前（按整分算距离）。 */
    @Test fun 顺延_带秒尾仍不早于九点() {
        val now = LocalDateTime.of(2026, 8, 28, 6, 59, 40).atZone(shanghai).toInstant().toEpochMilli()
        val delay = MeetingMomentEchoPlanner.lateNightRescheduleMinutes(now, shanghai, FixedRandom(0L))!!
        assertTrue("落点不得早于 09:00", landingMinuteOfDay(now, shanghai, delay) >= 540)
    }

    /**
     * E13：夏令时切换日（美东 2026-03-08 02:00 拨快一小时）——深夜 01:00 到 09:00 的**真实经过时间**
     * 只有 7 小时（420 分钟），不是墙钟差的 480；落点仍是 09:00。
     */
    @Test fun 顺延_夏令时切换日按真实经过时间() {
        val newYork = ZoneId.of("America/New_York")
        val now = LocalDateTime.of(2026, 3, 8, 1, 0).atZone(newYork).toInstant().toEpochMilli()
        val delay = MeetingMomentEchoPlanner.lateNightRescheduleMinutes(now, newYork, FixedRandom(0L))!!
        assertEquals(420L, delay)
        assertEquals(9 * 60, landingMinuteOfDay(now, newYork, delay))
    }

    /** 时区是入参不是环境：同一时刻在不同时区判定不同。 */
    @Test fun 深夜判定跟着入参时区走() {
        val now = millisAt(shanghai, 2026, 8, 28, 0, 30) // 上海 00:30 = 伦敦前一天 17:30
        assertNotNull(MeetingMomentEchoPlanner.lateNightRescheduleMinutes(now, shanghai, FixedRandom(0L)))
        assertNull(MeetingMomentEchoPlanner.lateNightRescheduleMinutes(now, ZoneId.of("Europe/London"), FixedRandom(0L)))
    }

    // ══════ 确定性 uuid ══════

    @Test fun 呼应uuid_种子串逐字且稳定() {
        val expected = UUID.nameUUIDFromBytes("moment:echo:sess-1".toByteArray()).toString()
        assertEquals(expected, MeetingMomentEchoPlanner.echoPostUuid("sess-1"))
        assertEquals("同 session 恒同 uuid（幂等身份）", expected, MeetingMomentEchoPlanner.echoPostUuid("sess-1"))
        assertTrue("不同 session 不同 uuid", MeetingMomentEchoPlanner.echoPostUuid("sess-2") != expected)
    }

    /** 日期只是佐证时区夹具没写错（防夹具自身漂移）。 */
    @Test fun 夹具自检_跨日落在次日() {
        val now = millisAt(shanghai, 2026, 8, 27, 23, 30)
        val delay = MeetingMomentEchoPlanner.lateNightRescheduleMinutes(now, shanghai, FixedRandom(0L))!!
        val landing = java.time.Instant.ofEpochMilli(now).atZone(shanghai).plusMinutes(delay)
        assertEquals(LocalDate.of(2026, 8, 28), landing.toLocalDate())
    }
}
