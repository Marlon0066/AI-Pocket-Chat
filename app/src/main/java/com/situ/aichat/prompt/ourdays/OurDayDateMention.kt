package com.situ.aichat.prompt.ourdays

import com.situ.aichat.gift.Festival
import com.situ.aichat.gift.FestivalCalendar
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

/**
 * 日期指名纯函数（「我们的日子」卷二图纸 §3.5 十四条词表 + R1 追加规则 7′·逐字锁定）：从**用户当前消息组**文本里解析出被点名的
 * 日子（[Mentions.days]）与日期范围（[Mentions.range]）。
 *
 * - 未来日期一律丢弃；规则 3 / 6 的结果 `>= today` 丢弃、规则 8 的 `>= today` 折到去年（§3.5 逐条）。
 * - 同一日期去重，保持**首次出现的文本位置**顺序（E43 / E44「按提及顺序」）；范围只取首个命中的范围规则（4 / 11）。
 * - 纯函数：零 DB、零时钟裸取——`today` 由调用方传入；[zone] 只给节日回溯算「该日零点」毫秒
 *   （[Festival.matches] 吃毫秒），默认 = 系统时区（与 [com.situ.aichat.gift.Festival.matches]
 *   内部 `GregorianCalendar()` 的默认时区同口径）。
 * - 正则只用 ICU / OpenJDK 共有特性（定长 look-behind、无 Unicode 属性类）——记忆 `android-icu-regex-pitfalls`。
 */
internal object OurDayDateMention {

    data class Anchors(
        /** 最早有页的日子（rows.minOf dayKey）。 */
        val firstDay: LocalDate? = null,
        /** 最早 hasMeeting 行。 */
        val firstMeetingDay: LocalDate? = null,
        /** 最晚 hasMeeting 行。 */
        val lastMeetingDay: LocalDate? = null,
        val userBirthday: MonthDay? = null,
        val characterBirthday: MonthDay? = null,
    )

    data class Mentions(val days: List<LocalDate>, val range: ClosedRange<LocalDate>?)

    /** 节日名回溯上限（§9.2 锁定 400 天）。 */
    private const val FESTIVAL_LOOKBACK_DAYS = 400L

    // ── §3.5 十四条正则（逐字）──
    private val R1_RELATIVE_DAY = Regex("昨天|前天|大前天")
    private val R2_DAYS_AGO = Regex("(\\d{1,2}|[一二两三四五六七八九十]{1,3})天前")
    private val R3_WEEKDAY = Regex("(上上周|上周|上星期|这周|本周|这星期|本星期)([一二三四五六日天])")
    private val R4_LAST_MONTH = Regex("上个月|上月")
    private val R5_LAST_MONTH_DAY = Regex("(上个月|上月)(\\d{1,2}|[一二三四五六七八九十]{1,3})[号日]")
    private val R6_THIS_MONTH_DAY = Regex("(这个月|本月)(\\d{1,2}|[一二三四五六七八九十]{1,3})[号日]")
    private val R7_FULL_DATE_CN = Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})[号日]")
    private val R7_FULL_DATE_ISO = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")
    private val R7_LAST_YEAR_DATE = Regex("去年(\\d{1,2}|[一二三四五六七八九十]{1,3})月(\\d{1,2}|[一二三四五六七八九十]{1,3})[号日]")
    private val R8_MONTH_DAY = Regex("(?<![\\d年])(\\d{1,2})月(\\d{1,2})[号日]")
    private val R9_YEAR_AGO = Regex("去年今天|去年的今天|一年前的今天|一年前")
    private val R10_HALF_YEAR = Regex("半年前")
    private val R10_MONTHS_AGO = Regex("(\\d{1,2}|[一二两三四五六七八九十]{1,3})个月前")
    private val R11_LAST_YEAR_MONTH = Regex("去年(\\d{1,2}|[一二三四五六七八九十]{1,3})月")
    private val R12_LAST_MEETING = Regex("上次见面|上一次见面|上回见面")
    private val R12_FIRST_MEETING = Regex("第一次见面|初次见面")
    private val R12_FIRST_DAY = Regex("第一次聊天|第一次说话|刚认识|认识那天|认识的那天|第一天")
    private val R13_520 = Regex("(?<!\\d)520(?!\\d)")
    private val R14_BIRTHDAY = Regex("生日")

    private val CN_DIGITS = mapOf(
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    private val CN_WEEKDAYS = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7,
    )

    // zone = 第 4 个默认参（R1 核准施工 D-1·图纸 §3.1 已回填）：只给规则 13 算「该日零点」毫秒；默认系统时区与 Festival.matches 同口径。
    fun resolve(text: String, today: LocalDate, anchors: Anchors, zone: ZoneId = ZoneId.systemDefault()): Mentions {
        if (text.isEmpty()) return Mentions(emptyList(), null)
        // (文本位置, 日期)——最后按位置稳定排序再去重 = 「按提及顺序·同日只留首次」。
        val hits = ArrayList<Pair<Int, LocalDate>>()
        fun hit(index: Int, date: LocalDate?) {
            if (date != null && !date.isAfter(today)) hits += index to date
        }

        // 1 昨天 / 前天 / 大前天
        for (m in R1_RELATIVE_DAY.findAll(text)) {
            val back = when (m.value) { "昨天" -> 1L; "前天" -> 2L; else -> 3L }
            hit(m.range.first, today.minusDays(back))
        }
        // 2 n 天前（1..99）
        for (m in R2_DAYS_AGO.findAll(text)) {
            val n = parseNumber(m.groupValues[1]) ?: continue
            if (n < 1 || n > 99) continue
            hit(m.range.first, today.minusDays(n.toLong()))
        }
        // 3 上上周 / 上周 / 本周 + 周几（周一为周首·结果 >= today 丢弃）
        val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        for (m in R3_WEEKDAY.findAll(text)) {
            val weeksBack = when (m.groupValues[1]) { "上上周" -> 2L; "上周", "上星期" -> 1L; else -> 0L }
            val dow = CN_WEEKDAYS[m.groupValues[2][0]] ?: continue
            val date = thisMonday.minusWeeks(weeksBack).plusDays((dow - 1).toLong())
            if (date.isBefore(today)) hit(m.range.first, date)
        }
        // 5 上月 X 号（超月末钳月末）—— 先算，好让 4 的「不接数字」排除这些位置
        val lastMonth = today.minusMonths(1)
        val rule5Starts = HashSet<Int>()
        for (m in R5_LAST_MONTH_DAY.findAll(text)) {
            rule5Starts += m.range.first
            val d = parseNumber(m.groupValues[2]) ?: continue
            if (d < 1) continue
            hit(m.range.first, lastMonth.withDayOfMonth(minOf(d, lastMonth.lengthOfMonth())))
        }
        // 6 本月 X 号（>= today 丢弃）
        for (m in R6_THIS_MONTH_DAY.findAll(text)) {
            val d = parseNumber(m.groupValues[2]) ?: continue
            if (d < 1 || d > today.lengthOfMonth()) continue
            val date = today.withDayOfMonth(d)
            if (date.isBefore(today)) hit(m.range.first, date)
        }
        // 7 精确日
        for (m in R7_FULL_DATE_CN.findAll(text)) hit(m.range.first, safeDate(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
        for (m in R7_FULL_DATE_ISO.findAll(text)) hit(m.range.first, safeDate(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
        // 7′ 去年 X 月 Y 日（精确日·R1 🔵-4）——记起点，好让 11 的「去年 X 月」范围让位（同 4 / 5 的关系）
        val rule7bStarts = HashSet<Int>()
        for (m in R7_LAST_YEAR_DATE.findAll(text)) {
            rule7bStarts += m.range.first
            val month = parseNumber(m.groupValues[1]) ?: continue
            val day = parseNumber(m.groupValues[2]) ?: continue
            hit(m.range.first, safeDate((today.year - 1).toString(), month.toString(), day.toString()))
        }
        // 8 X月Y日（今年；>= today 折去年）
        for (m in R8_MONTH_DAY.findAll(text)) {
            val thisYear = safeDate(today.year.toString(), m.groupValues[1], m.groupValues[2]) ?: continue
            val date = if (thisYear.isBefore(today)) thisYear else safeDate((today.year - 1).toString(), m.groupValues[1], m.groupValues[2])
            hit(m.range.first, date)
        }
        // 9 一年前
        for (m in R9_YEAR_AGO.findAll(text)) hit(m.range.first, today.minusYears(1))
        // 10 半年前 / n 个月前（minusMonths 自然钳位）
        for (m in R10_HALF_YEAR.findAll(text)) hit(m.range.first, today.minusMonths(6))
        for (m in R10_MONTHS_AGO.findAll(text)) {
            val n = parseNumber(m.groupValues[1]) ?: continue
            if (n < 1) continue
            hit(m.range.first, today.minusMonths(n.toLong()))
        }
        // 12 锚点（为空即跳过）
        for (m in R12_LAST_MEETING.findAll(text)) hit(m.range.first, anchors.lastMeetingDay)
        for (m in R12_FIRST_MEETING.findAll(text)) hit(m.range.first, anchors.firstMeetingDay)
        for (m in R12_FIRST_DAY.findAll(text)) hit(m.range.first, anchors.firstDay)
        // 13 节日名（逐个 contains·最长名优先且已认领的文本段不再被短名撞进·R1 🔵-6；520 仅整词）→ 从 today − 1 起回溯 ≤ 400 天取首个命中
        val claimed = ArrayList<IntRange>()
        for (festival in FestivalCalendar.allFestivals.sortedByDescending { it.name.length }) {
            val index = festivalIndex(text, festival.name, claimed) ?: continue
            claimed += index until index + festival.name.length
            hit(index, lastFestivalDay(festival, today, zone))
        }
        // 14 生日（两锚各取 <= today − 1 的最近一次）
        R14_BIRTHDAY.find(text)?.let { m ->
            hit(m.range.first, anchors.userBirthday?.let { lastOccurrence(it, today) })
            hit(m.range.first, anchors.characterBirthday?.let { lastOccurrence(it, today) })
        }

        val days = hits.sortedBy { it.first }.map { it.second }.distinct()

        // 范围：4「上个月」（不接数字 = 不是规则 5 的起点）优先，其次 11「去年 X 月」（不是规则 7′ 的起点）；只取首个命中。
        val range: ClosedRange<LocalDate>? = R4_LAST_MONTH.findAll(text)
            .firstOrNull { it.range.first !in rule5Starts }
            ?.let { lastMonth.withDayOfMonth(1)..lastMonth.withDayOfMonth(lastMonth.lengthOfMonth()) }
            ?: R11_LAST_YEAR_MONTH.findAll(text).filter { it.range.first !in rule7bStarts }.firstNotNullOfOrNull { m ->
                val month = parseNumber(m.groupValues[1]) ?: return@firstNotNullOfOrNull null
                if (month < 1 || month > 12) return@firstNotNullOfOrNull null
                val first = LocalDate.of(today.year - 1, month, 1)
                first..first.withDayOfMonth(first.lengthOfMonth())
            }

        return Mentions(days, range)
    }

    /** §3.5b 中文数字：一…十 / 十一…十九 / 二十·二十一…三十一；阿拉伯数字直接解析；解析不出返 null。 */
    internal fun parseNumber(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        if (s.isEmpty()) return null
        if (s == "十") return 10
        val tenIdx = s.indexOf('十')
        return when {
            tenIdx < 0 -> if (s.length == 1) CN_DIGITS[s[0]] else null
            tenIdx == 0 -> if (s.length == 2) CN_DIGITS[s[1]]?.let { 10 + it } else null
            tenIdx == 1 -> {
                val tens = when (s[0]) { '二' -> 2; '三' -> 3; else -> return null }
                val ones = when (s.length) { 2 -> 0; 3 -> CN_DIGITS[s[2]] ?: return null; else -> return null }
                tens * 10 + ones
            }
            else -> null
        }
    }

    private fun safeDate(y: String, m: String, d: String): LocalDate? =
        runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()

    /** 该月日在 `<= today − 1` 的最近一次（`MonthDay.atYear` 对 2/29 非闰年自然钳 2/28）。 */
    private fun lastOccurrence(monthDay: MonthDay, today: LocalDate): LocalDate {
        val thisYear = monthDay.atYear(today.year)
        return if (thisYear.isBefore(today)) thisYear else monthDay.atYear(today.year - 1)
    }

    /** [name] 在 [text] 里首个不落在 [claimed] 区间内的出现位置（「白色情人节」先认领，短名「情人节」就不会撞进它）；520 仅整词。 */
    private fun festivalIndex(text: String, name: String, claimed: List<IntRange>): Int? {
        if (name == "520") return R13_520.findAll(text).map { it.range.first }.firstOrNull { i -> claimed.none { i in it } }
        var from = 0
        while (true) {
            val i = text.indexOf(name, from)
            if (i < 0) return null
            if (claimed.none { i in it }) return i
            from = i + 1
        }
    }

    /**
     * 从 today − 1 起逐日回溯 ≤ 400 天，取首个命中 [festival] 的日子；无命中返 null（E48）。只判这一个节日
     * （R1 🟡-3：原 `festivalsMatching` 每天全表 16 节日 × 5 个 ICU 农历构造，落在装配线程）。
     */
    private fun lastFestivalDay(festival: Festival, today: LocalDate, zone: ZoneId): LocalDate? {
        for (back in 1..FESTIVAL_LOOKBACK_DAYS) {
            val date = today.minusDays(back)
            if (festival.matches(date.atStartOfDay(zone).toInstant().toEpochMilli())) return date
        }
        return null
    }
}
