package com.situ.aichat.ui.ourdays

import com.situ.aichat.data.local.entity.OurDayCalendarRow
import com.situ.aichat.ourdays.OurDayFacts
import com.situ.aichat.ourdays.OurDayFactsJson
import com.situ.aichat.ourdays.OurDayMeetingFact
import com.situ.aichat.ourdays.OurDayMilestoneFact
import com.situ.aichat.ourdays.OurDayPromiseEvent
import com.situ.aichat.ourdays.OurDayPromiseFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * T1-3（卷三图纸 §7.2·纯 JVM·假 decor / 假 card）：热度四档边界 + 通话折算、两 locale 周首日、42/35 格、邻月 / 未来标记、
 * 三点序、全部模式识别色 ≤3 + 热度合计、汇总四项 / 全零 / 才刚开始、周条与周卡、年 12 月 dimmed / 今 / 统计五项、
 * `shift` 月末钳位、入口条 7 格 / 资料卡 14 格。断言从 §3.4 独立反推。
 */
class OurDaysCalendarLogicTest {

    private val cn: WeekFields = WeekFields.of(Locale.SIMPLIFIED_CHINESE) // 周一起
    private val us: WeekFields = WeekFields.of(Locale.US) // 周日起
    private val today = LocalDate.of(2026, 9, 15)
    private val decor: (LocalDate) -> DayDecor = { DayDecor("装", false, null) }
    private val card: (LocalDate, List<OurDayCalendarRow>) -> DayCardModel = { d, rows ->
        DayCardModel(d.toString(), d, d == today, d.isAfter(today), CardStatus.NORMAL, "note${rows.size}", emptyList(), "", null, false, null)
    }

    private fun row(
        key: String, char: String = "c1", mc: Int = 0, cs: Int = 0,
        meeting: Boolean = false, relation: Boolean = false, life: Boolean = false, facts: String = "", deleted: Boolean = false,
    ) = OurDayCalendarRow(
        uuid = "$char-$key", characterUuid = char, dayKey = key, factsJson = facts, messageCount = mc, callSeconds = cs,
        hasMeeting = meeting, hasRelation = relation, hasLife = life, note = "", factLine = "", noteStatus = "ok", noteAttempts = 0,
        noteEdited = false, hiddenFromMemory = false, deleted = deleted, generatedAt = null, createdAtMillis = 1, updatedAtMillis = 1,
    )

    private fun facts(meetings: Int = 0, fulfilled: Int = 0, created: Int = 0, milestones: Int = 0, calls: Int = 0) = OurDayFactsJson.encode(
        OurDayFacts(
            callCount = calls,
            meetings = List(meetings) { OurDayMeetingFact("m$it", "咖啡馆", "喝咖啡", 0L, 30) },
            promises = List(fulfilled) { OurDayPromiseFact("p$it", "看海", OurDayPromiseEvent.FULFILLED) } +
                List(created) { OurDayPromiseFact("q$it", "散步", OurDayPromiseEvent.CREATED) },
            milestones = List(milestones) { OurDayMilestoneFact("s$it", "朋友") },
        ),
    )

    private val chars = listOf("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8")

    // ── 热度 ──

    @Test fun 热度四档边界_0_1_9_10_39_40() {
        assertEquals(listOf(0, 1, 1, 2, 2, 3), listOf(0, 1, 9, 10, 39, 40).map { OurDaysCalendarLogic.heatLevelOf(listOf(row("2026-09-01", mc = it))) })
    }

    @Test fun 通话折算_每分钟三条_不足一分钟不计() {
        assertEquals(2, OurDaysCalendarLogic.heatLevelOf(listOf(row("2026-09-01", mc = 4, cs = 120)))) // 4 + 6 = 10
        assertEquals(0, OurDaysCalendarLogic.heatLevelOf(listOf(row("2026-09-01", cs = 59))))
        assertEquals(1, OurDaysCalendarLogic.heatLevelOf(listOf(row("2026-09-01", cs = 60))))
    }

    // ── 期范围 / 翻期 ──

    @Test fun 月期_周一起_2026_09_是35格_2026_08_是42格() {
        val sep = OurDaysCalendarLogic.period(OurDaysViewMode.MONTH, LocalDate.of(2026, 9, 10), cn)
        assertEquals(LocalDate.of(2026, 8, 31), sep.start); assertEquals(LocalDate.of(2026, 10, 4), sep.endInclusive)
        val aug = OurDaysCalendarLogic.period(OurDaysViewMode.MONTH, LocalDate.of(2026, 8, 1), cn)
        assertEquals(LocalDate.of(2026, 7, 27), aug.start); assertEquals(LocalDate.of(2026, 9, 6), aug.endInclusive)
        assertEquals(42, OurDaysCalendarLogic.buildMonth(LocalDate.of(2026, 8, 1), emptyList(), today, cn, Locale.SIMPLIFIED_CHINESE, false, chars, decor, LocalDate.of(2026, 8, 1), card).cells.size)
        assertEquals(35, OurDaysCalendarLogic.buildMonth(LocalDate.of(2026, 9, 10), emptyList(), today, cn, Locale.SIMPLIFIED_CHINESE, false, chars, decor, today, card).cells.size)
    }

    @Test fun 月期_周日起_首格与表头随locale() {
        val m = OurDaysCalendarLogic.buildMonth(LocalDate.of(2026, 9, 10), emptyList(), today, us, Locale.US, false, chars, decor, today, card)
        assertEquals(LocalDate.of(2026, 8, 30), m.cells.first().date)
        assertEquals(DayOfWeek.SUNDAY, m.cells.first().date.dayOfWeek)
        assertEquals("S", m.weekdayLabels.first())
        val zh = OurDaysCalendarLogic.buildMonth(LocalDate.of(2026, 9, 10), emptyList(), today, cn, Locale.SIMPLIFIED_CHINESE, false, chars, decor, today, card)
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), zh.weekdayLabels)
    }

    @Test fun 周期与年期() {
        val w = OurDaysCalendarLogic.period(OurDaysViewMode.WEEK, today, cn)
        assertEquals(LocalDate.of(2026, 9, 14), w.start); assertEquals(LocalDate.of(2026, 9, 20), w.endInclusive)
        val y = OurDaysCalendarLogic.period(OurDaysViewMode.YEAR, today, cn)
        assertEquals(LocalDate.of(2026, 1, 1), y.start); assertEquals(LocalDate.of(2026, 12, 31), y.endInclusive)
    }

    @Test fun shift_月末钳位_周七天_年一年() {
        assertEquals(LocalDate.of(2026, 2, 28), OurDaysCalendarLogic.shift(OurDaysViewMode.MONTH, LocalDate.of(2026, 1, 31), 1))
        assertEquals(LocalDate.of(2025, 12, 31), OurDaysCalendarLogic.shift(OurDaysViewMode.MONTH, LocalDate.of(2026, 1, 31), -1))
        assertEquals(LocalDate.of(2026, 9, 22), OurDaysCalendarLogic.shift(OurDaysViewMode.WEEK, today, 1))
        assertEquals(LocalDate.of(2027, 9, 15), OurDaysCalendarLogic.shift(OurDaysViewMode.YEAR, today, 1))
    }

    // ── 格子 ──

    @Test fun 邻月格_不在期内_无副行_不出热度_未来格_不出热度与点() {
        val rows = listOf(row("2026-08-31", mc = 50, meeting = true), row("2026-09-16", mc = 50, meeting = true), row("2026-09-15", mc = 5, meeting = true))
        val m = OurDaysCalendarLogic.buildMonth(LocalDate.of(2026, 9, 10), rows, today, cn, Locale.SIMPLIFIED_CHINESE, false, chars, decor, today, card)
        val neighbor = m.cells.first { it.date == LocalDate.of(2026, 8, 31) }
        assertFalse(neighbor.inPeriod); assertNull(neighbor.decor); assertEquals(3, neighbor.heatLevel) // 邻月有行仍算热度，UI 按 inPeriod 透明
        val future = m.cells.first { it.date == LocalDate.of(2026, 9, 16) }
        assertTrue(future.isFuture); assertEquals(0, future.heatLevel); assertTrue(future.dots.isEmpty()); assertFalse(future.selected)
        val td = m.cells.first { it.date == today }
        assertTrue(td.isToday); assertTrue(td.selected); assertEquals(1, td.heatLevel); assertEquals(listOf(DotFamily.MEETING), td.dots)
    }

    @Test fun 单角色三点固定序_见面关系生活() {
        val c = OurDaysCalendarLogic.cell(today, listOf(row("2026-09-15", life = true, relation = true, meeting = true)), today, true, false, chars, null, false)
        assertEquals(listOf(DotFamily.MEETING, DotFamily.RELATION, DotFamily.LIFE), c.dots)
        val only = OurDaysCalendarLogic.cell(today, listOf(row("2026-09-15", life = true)), today, true, false, chars, null, false)
        assertEquals(listOf(DotFamily.LIFE), only.dots)
    }

    @Test fun 全部模式_识别色最多三个_第四位起more_热度合计_不出三点() {
        val rows = listOf(row("2026-09-15", "c1", mc = 3, meeting = true), row("2026-09-15", "c2", mc = 3), row("2026-09-15", "c3", mc = 3), row("2026-09-15", "c4", mc = 1))
        val c = OurDaysCalendarLogic.cell(today, rows, today, true, true, chars, null, false)
        assertEquals(listOf(0, 1, 2), c.identity); assertTrue(c.moreIdentity)
        assertEquals(2, c.heatLevel) // 3+3+3+1 = 10
        assertTrue(c.dots.isEmpty())
        val three = OurDaysCalendarLogic.cell(today, rows.take(3), today, true, true, chars, null, false)
        assertFalse(three.moreIdentity)
    }

    @Test fun 识别色序号_升序取模6_不在列表为0() {
        assertEquals(1, OurDaysCalendarLogic.identityIndex(chars, "c8"))
        assertEquals(5, OurDaysCalendarLogic.identityIndex(chars, "c6"))
        assertEquals(0, OurDaysCalendarLogic.identityIndex(chars, "ghost"))
    }

    // ── 汇总 ──

    @Test fun 汇总四项_只算本月_邻月行不入() {
        val rows = listOf(
            row("2026-09-01", mc = 12, facts = facts(meetings = 1, fulfilled = 2, created = 1, milestones = 1)),
            row("2026-09-02", mc = 1, facts = facts(meetings = 1)),
            row("2026-09-03", cs = 30), // 热度 0：不算聊天日，但算有记录
            row("2026-08-31", mc = 40, facts = facts(meetings = 5, fulfilled = 5, milestones = 5)),
        )
        val s = OurDaysCalendarLogic.buildMonth(LocalDate.of(2026, 9, 10), rows, today, cn, Locale.SIMPLIFIED_CHINESE, false, chars, decor, today, card).summary
        assertEquals(YearMonth.of(2026, 9), s.yearMonth)
        assertEquals(2, s.chatDays); assertEquals(2, s.meetings); assertEquals(2, s.promisesFulfilled); assertEquals(1, s.milestones)
        assertEquals(3, s.recordedDays); assertFalse(s.allMode); assertEquals(8, s.characterCount)
    }

    @Test fun 汇总全零_与坏facts按0() {
        val s = OurDaysCalendarLogic.buildMonth(LocalDate.of(2026, 9, 10), listOf(row("2026-09-05", facts = "{bad")), today, cn, Locale.SIMPLIFIED_CHINESE, false, chars, decor, today, card).summary
        assertEquals(0, s.chatDays); assertEquals(0, s.meetings); assertEquals(0, s.promisesFulfilled); assertEquals(0, s.milestones); assertEquals(1, s.recordedDays)
    }

    @Test fun 才刚开始_当月且今天在前七日() {
        fun just(t: LocalDate, anchor: LocalDate) = OurDaysCalendarLogic.buildMonth(anchor, emptyList(), t, cn, Locale.SIMPLIFIED_CHINESE, false, chars, decor, anchor, card).summary.justStarted
        assertTrue(just(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 3)))
        assertFalse(just(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 3)))
        assertFalse(just(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 8, 3)))
    }

    @Test fun 全部模式汇总_按天去重_allMode为真() {
        val rows = listOf(row("2026-09-01", "c1", mc = 5), row("2026-09-01", "c2", mc = 5), row("2026-09-02", "c2", mc = 1))
        val s = OurDaysCalendarLogic.buildMonth(LocalDate.of(2026, 9, 10), rows, today, cn, Locale.SIMPLIFIED_CHINESE, true, chars, decor, today, card).summary
        assertTrue(s.allMode); assertEquals(2, s.chatDays); assertEquals(2, s.recordedDays)
    }

    @Test fun 选中日卡_只在本月产出() {
        val m = OurDaysCalendarLogic.buildMonth(LocalDate.of(2026, 9, 10), listOf(row("2026-09-10")), today, cn, Locale.SIMPLIFIED_CHINESE, false, chars, decor, LocalDate.of(2026, 9, 10), card)
        assertEquals("note1", m.selectedCard?.note)
        val other = OurDaysCalendarLogic.buildMonth(LocalDate.of(2026, 9, 10), emptyList(), today, cn, Locale.SIMPLIFIED_CHINESE, false, chars, decor, LocalDate.of(2026, 8, 10), card)
        assertNull(other.selectedCard)
    }

    // ── 周 ──

    @Test fun 周条七格_周一起_选中为锚_周卡不出未来日() {
        val w = OurDaysCalendarLogic.buildWeek(today, listOf(row("2026-09-14", mc = 3)), today, cn, false, chars, decor, card)
        assertEquals(LocalDate.of(2026, 9, 14), w.start); assertEquals(LocalDate.of(2026, 9, 20), w.end)
        assertEquals(7, w.strip.size)
        assertEquals(listOf(today), w.strip.filter { it.selected }.map { it.date })
        assertEquals(2, w.cards.size) // 14、15；16–20 未来
        assertEquals(listOf(LocalDate.of(2026, 9, 14), today), w.cards.map { it.date })
        assertEquals(1, w.strip.first().heatLevel)
    }

    // ── 年 ──

    @Test fun 年视图_12月_相识前月份dimmed_当月isCurrent_微格按周排() {
        val rows = listOf(row("2026-03-05", mc = 45, meeting = true, facts = facts(meetings = 1, fulfilled = 1, milestones = 2, calls = 3)), row("2026-09-15", mc = 2, facts = facts(calls = 1)))
        val y = OurDaysCalendarLogic.buildYear(today, rows, today, cn, LocalDate.of(2026, 3, 5), 8)
        assertEquals(2026, y.year); assertEquals(12, y.months.size)
        assertEquals(listOf(true, true, false), y.months.take(3).map { it.dimmed })
        assertEquals(listOf(8), y.months.mapIndexedNotNull { i, m -> i.takeIf { m.isCurrent } })
        y.months.forEach { assertEquals(0, it.cells.size % 7) }
        val sep = y.months[8]
        assertEquals(1, sep.cells.count { it.isToday })
        // 2026-09-01 周二·周一起 ⇒ 1 个前导空格
        assertFalse(sep.cells[0].inMonth); assertTrue(sep.cells[1].inMonth)
        val mar5 = y.months[2].cells.first { it.inMonth && it.heatLevel == 3 }
        assertTrue(mar5.meeting)
        assertEquals(YearStats(chatDays = 2, meetings = 1, milestones = 2, promisesFulfilled = 1, calls = 4), y.stats)
        assertEquals(195, y.daysTogether) // 03-05 → 09-15：194 天差 + 1
        assertEquals(2, y.recordedDays); assertEquals(8, y.characterCount)
    }

    @Test fun 年视图_未来日不出热度_无相识日daysTogether为null() {
        val y = OurDaysCalendarLogic.buildYear(today, listOf(row("2026-12-01", mc = 50, meeting = true)), today, cn, null, 1)
        val dec1 = y.months[11].cells.first { it.inMonth }
        assertTrue(dec1.isFuture); assertEquals(0, dec1.heatLevel); assertFalse(dec1.meeting)
        assertNull(y.daysTogether); assertFalse(y.months.any { it.dimmed })
    }

    @Test fun 第N天_相识日当天为1_未来相识日为null() {
        assertEquals(1, OurDaysCalendarLogic.daysTogether(today, today))
        assertEquals(15, OurDaysCalendarLogic.daysTogether(LocalDate.of(2026, 9, 1), today))
        assertNull(OurDaysCalendarLogic.daysTogether(LocalDate.of(2026, 9, 16), today))
    }

    // ── 入口条 / 资料卡 ──

    @Test fun 入口条七格_周首日起_含未来() {
        val s = OurDaysCalendarLogic.stripWeek(today, listOf(row("2026-09-14", mc = 20)), cn)
        assertEquals(7, s.size); assertEquals(LocalDate.of(2026, 9, 14), s.first().date)
        assertEquals(2, s.first().heatLevel); assertTrue(s.last().isFuture); assertTrue(s[1].isToday)
        assertEquals(LocalDate.of(2026, 9, 13), OurDaysCalendarLogic.stripWeek(today, emptyList(), us).first().date)
    }

    @Test fun 资料卡14格_今天在末位() {
        val bar = OurDaysCalendarLogic.entryBar(today, listOf(row("2026-09-02", meeting = true, mc = 1)))
        assertEquals(14, bar.size); assertEquals(LocalDate.of(2026, 9, 2), bar.first().date); assertEquals(today, bar.last().date)
        assertTrue(bar.last().isToday); assertEquals(listOf(DotFamily.MEETING), bar.first().dots)
    }
}
