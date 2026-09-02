package com.situ.aichat.ui.ourdays

import com.situ.aichat.data.local.entity.OurDayCalendarRow
import com.situ.aichat.ourdays.OurDayFacts
import com.situ.aichat.ourdays.OurDayFactsJson
import com.situ.aichat.ourdays.OurDayKey
import com.situ.aichat.ourdays.OurDayPromiseEvent
import com.situ.aichat.ourdays.ourDayHeatLevel
import com.situ.aichat.ourdays.ourDayHeatScore
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * 日历纯核（卷三图纸 §3.4 锁定算法·零 Compose 零 DB·全部 `internal` 可测）：月格 / 周条 / 年迷你月 / 汇总 / 年统计 /
 * 入口条七格 / 资料卡 14 格 / 期范围与翻期。热度只经 [ourDayHeatScore] / [ourDayHeatLevel] 单源（W-2）；
 * 三点固定序 MEETING → RELATION → LIFE；全部模式识别色 = 角色升序序号 % 6（W-17）·每格 ≤3 + 「+」。
 */
internal object OurDaysCalendarLogic {

    const val IDENTITY_COLORS = 6
    const val MAX_IDENTITY = 3
    const val JUST_STARTED_DAYS = 7
    const val ENTRY_BAR_DAYS = 14

    /** 期范围：月 = 网格首格..末格（含邻月）；周 = 周首..周末；年 = 1/1..12/31。 */
    fun period(mode: OurDaysViewMode, anchor: LocalDate, weekFields: WeekFields): ClosedRange<LocalDate> = when (mode) {
        OurDaysViewMode.MONTH -> {
            val ym = YearMonth.from(anchor)
            weekStart(ym.atDay(1), weekFields)..weekEnd(ym.atEndOfMonth(), weekFields)
        }
        OurDaysViewMode.WEEK -> weekStart(anchor, weekFields).let { it..it.plusDays(6) }
        OurDaysViewMode.YEAR -> LocalDate.of(anchor.year, 1, 1)..LocalDate.of(anchor.year, 12, 31)
    }

    /** 翻期：月 ±1 月（`plusMonths` 自然钳月末）/ 周 ±7 天 / 年 ±1 年。 */
    fun shift(mode: OurDaysViewMode, anchor: LocalDate, delta: Int): LocalDate = when (mode) {
        OurDaysViewMode.MONTH -> anchor.plusMonths(delta.toLong())
        OurDaysViewMode.WEEK -> anchor.plusWeeks(delta.toLong())
        OurDaysViewMode.YEAR -> anchor.plusYears(delta.toLong())
    }

    /** 识别色序号 = 角色（按 creationDate 升序）序号 % 6；不在列表 ⇒ 0。 */
    fun identityIndex(characterUuids: List<String>, uuid: String): Int =
        characterUuids.indexOf(uuid).coerceAtLeast(0) % IDENTITY_COLORS

    /** 同日多行热度 = Σ score 的档位（单角色即单行）。 */
    fun heatLevelOf(rows: List<OurDayCalendarRow>): Int =
        ourDayHeatLevel(rows.sumOf { ourDayHeatScore(it.messageCount, it.callSeconds) })

    /** 第 N 天 = `DAYS.between(firstDay, today) + 1`（W-3）；无相识日 / 相识日在未来 ⇒ null。 */
    fun daysTogether(firstDay: LocalDate?, today: LocalDate): Int? =
        firstDay?.takeIf { !it.isAfter(today) }?.let { (ChronoUnit.DAYS.between(it, today) + 1).toInt() }

    fun cell(
        date: LocalDate,
        rows: List<OurDayCalendarRow>,
        today: LocalDate,
        inPeriod: Boolean,
        allMode: Boolean,
        characterUuids: List<String>,
        decor: DayDecor?,
        selected: Boolean,
    ): CellModel {
        val isFuture = date.isAfter(today)
        val heat = if (isFuture) 0 else heatLevelOf(rows)
        val dots = if (isFuture || allMode) emptyList() else rows.firstOrNull()?.let { r ->
            buildList {
                if (r.hasMeeting) add(DotFamily.MEETING)
                if (r.hasRelation) add(DotFamily.RELATION)
                if (r.hasLife) add(DotFamily.LIFE)
            }
        }.orEmpty()
        val identity = if (isFuture || !allMode) emptyList() else rows.map { identityIndex(characterUuids, it.characterUuid) }.distinct()
        return CellModel(
            date = date, key = OurDayKey.keyOf(date), inPeriod = inPeriod, isToday = date == today, isFuture = isFuture,
            heatLevel = heat, dots = dots, identity = identity.take(MAX_IDENTITY), moreIdentity = identity.size > MAX_IDENTITY,
            decor = decor, selected = selected,
        )
    }

    fun buildMonth(
        anchor: LocalDate,
        rows: List<OurDayCalendarRow>,
        today: LocalDate,
        weekFields: WeekFields,
        locale: Locale,
        allMode: Boolean,
        characterUuids: List<String>,
        decor: (LocalDate) -> DayDecor,
        selected: LocalDate,
        card: (LocalDate, List<OurDayCalendarRow>) -> DayCardModel,
    ): MonthModel {
        val ym = YearMonth.from(anchor)
        val byDay = rows.groupBy { it.dayKey }
        val cells = dates(period(OurDaysViewMode.MONTH, anchor, weekFields)).map { d ->
            val inMonth = YearMonth.from(d) == ym
            cell(d, byDay[OurDayKey.keyOf(d)].orEmpty(), today, inMonth, allMode, characterUuids, if (inMonth) decor(d) else null, selected = inMonth && d == selected)
        }
        val monthRows = rows.filter { it.dayKey.startsWith(ym.toString()) }
        val selectedCard = if (YearMonth.from(selected) == ym) card(selected, byDay[OurDayKey.keyOf(selected)].orEmpty()) else null
        return MonthModel(ym, OurDaysFormat.weekdayLabels(weekFields, locale), cells, summary(ym, monthRows, today, allMode, characterUuids.size), selectedCard)
    }

    private fun summary(ym: YearMonth, monthRows: List<OurDayCalendarRow>, today: LocalDate, allMode: Boolean, characterCount: Int): MonthSummary {
        val byDay = monthRows.groupBy { it.dayKey }
        val facts = monthRows.mapNotNull { OurDayFactsJson.decodeOrNull(it.factsJson) }
        return MonthSummary(
            yearMonth = ym,
            chatDays = byDay.values.count { heatLevelOf(it) >= 1 },
            meetings = facts.sumOf { it.meetings.size },
            promisesFulfilled = facts.sumOf { it.fulfilledCount() },
            milestones = facts.sumOf { it.milestones.size },
            justStarted = ym == YearMonth.from(today) && today.dayOfMonth <= JUST_STARTED_DAYS,
            allMode = allMode,
            characterCount = characterCount,
            recordedDays = byDay.size,
        )
    }

    fun buildWeek(
        anchor: LocalDate,
        rows: List<OurDayCalendarRow>,
        today: LocalDate,
        weekFields: WeekFields,
        allMode: Boolean,
        characterUuids: List<String>,
        decor: (LocalDate) -> DayDecor,
        card: (LocalDate, List<OurDayCalendarRow>) -> DayCardModel,
    ): WeekModel {
        val start = weekStart(anchor, weekFields)
        val end = start.plusDays(6)
        val byDay = rows.groupBy { it.dayKey }
        val days = dates(start..end)
        val strip = days.map { d -> cell(d, byDay[OurDayKey.keyOf(d)].orEmpty(), today, true, allMode, characterUuids, decor(d), selected = d == anchor) }
        val cards = days.filter { !it.isAfter(today) }.map { d -> card(d, byDay[OurDayKey.keyOf(d)].orEmpty()) }
        return WeekModel(start, end, strip, cards)
    }

    fun buildYear(
        anchor: LocalDate,
        rows: List<OurDayCalendarRow>,
        today: LocalDate,
        weekFields: WeekFields,
        firstDay: LocalDate?,
        characterCount: Int,
    ): YearModel {
        val year = anchor.year
        val yearRows = rows.filter { it.dayKey.startsWith("$year-") }
        val byDay = yearRows.groupBy { it.dayKey }
        val months = (1..12).map { m ->
            val ym = YearMonth.of(year, m)
            val leading = ((ym.atDay(1).dayOfWeek.value - weekFields.firstDayOfWeek.value) + 7) % 7
            val cells = buildList {
                repeat(leading) { add(MiniCell(inMonth = false, heatLevel = 0, meeting = false, isToday = false, isFuture = false)) }
                for (d in 1..ym.lengthOfMonth()) {
                    val date = ym.atDay(d)
                    val dayRows = byDay[OurDayKey.keyOf(date)].orEmpty()
                    val future = date.isAfter(today)
                    add(MiniCell(inMonth = true, heatLevel = if (future) 0 else heatLevelOf(dayRows), meeting = !future && dayRows.any { it.hasMeeting }, isToday = date == today, isFuture = future))
                }
                while (size % 7 != 0) add(MiniCell(inMonth = false, heatLevel = 0, meeting = false, isToday = false, isFuture = false))
            }
            MiniMonth(ym, cells, dimmed = firstDay != null && ym < YearMonth.from(firstDay), isCurrent = ym == YearMonth.from(today))
        }
        val facts = yearRows.mapNotNull { OurDayFactsJson.decodeOrNull(it.factsJson) }
        val stats = YearStats(
            chatDays = byDay.values.count { heatLevelOf(it) >= 1 },
            meetings = facts.sumOf { it.meetings.size },
            milestones = facts.sumOf { it.milestones.size },
            promisesFulfilled = facts.sumOf { it.fulfilledCount() },
            calls = facts.sumOf { it.callCount },
        )
        return YearModel(year, months, stats, firstDay, daysTogether(firstDay, today), characterCount, byDay.size)
    }

    /** 入口条本周七格（周首日起·无副行）。 */
    fun stripWeek(today: LocalDate, rows: List<OurDayCalendarRow>, weekFields: WeekFields): List<CellModel> {
        val start = weekStart(today, weekFields)
        val byDay = rows.groupBy { it.dayKey }
        return dates(start..start.plusDays(6)).map { d -> cell(d, byDay[OurDayKey.keyOf(d)].orEmpty(), today, true, false, emptyList(), null, false) }
    }

    /** 资料卡 14 格（today−13..today）。 */
    fun entryBar(today: LocalDate, rows: List<OurDayCalendarRow>): List<CellModel> {
        val byDay = rows.groupBy { it.dayKey }
        return dates(today.minusDays((ENTRY_BAR_DAYS - 1).toLong())..today).map { d -> cell(d, byDay[OurDayKey.keyOf(d)].orEmpty(), today, true, false, emptyList(), null, false) }
    }

    fun weekStart(date: LocalDate, weekFields: WeekFields): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(weekFields.firstDayOfWeek))

    private fun weekEnd(date: LocalDate, weekFields: WeekFields): LocalDate =
        date.with(TemporalAdjusters.nextOrSame(weekFields.firstDayOfWeek.minus(1)))

    private fun dates(range: ClosedRange<LocalDate>): List<LocalDate> =
        generateSequence(range.start) { it.plusDays(1) }.takeWhile { it <= range.endInclusive }.toList()

    private fun OurDayFacts.fulfilledCount(): Int = promises.count { it.event == OurDayPromiseEvent.FULFILLED }
}
