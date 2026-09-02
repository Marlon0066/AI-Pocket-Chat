package com.situ.aichat.ui.ourdays

import com.situ.aichat.gift.FestivalCalendar
import com.situ.aichat.prompt.schedule.ChineseHolidays
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

/** 装饰文案（卷三图纸 §3.3）：VM 从资源取后传入——纯核不碰 Compose / Context。 */
data class DecorStrings(
    val firstDay: String,
    val firstMeeting: String,
    val anniversary: (Int) -> String,
    val meetingAnniversary: (Int) -> String,
    val birthdayChar: (String) -> String,
    val birthdayUser: String,
)

/**
 * 格子副行查表（卷三图纸 §3.3 锁定·提案 D-4 优先级·同格只显一条）：
 * ① 纪念（相识 / 初见 / 相识 N 周年 / 初见 N 周年·相识与初见同日只显相识）② 生日（角色 > 用户）③ 节日（`FestivalCalendar` 首个）
 * ④ 法定假**首日**名（`ChineseHolidays`·前一天不是同名假才显）⑤ 农历。①–④ `emphasized`；角标 = 假 REST / 补班 WORK / 表外 null。
 * `FestivalCalendar` / `ChineseHolidays` 只读（§9.3）。ICU 查表须在 `Dispatchers.Default`（W-9）。
 */
internal object OurDaysDecor {

    fun factory(
        zone: ZoneId,
        characterName: String?,
        characterBirthday: MonthDay?,
        userBirthday: MonthDay?,
        firstDay: LocalDate?,
        firstMeetingDay: LocalDate?,
        strings: DecorStrings,
    ): (LocalDate) -> DayDecor = { date ->
        val monthDay = MonthDay.from(date)
        val label = when {
            date == firstDay -> strings.firstDay
            date == firstMeetingDay -> strings.firstMeeting
            firstDay != null && date.year > firstDay.year && monthDay == MonthDay.from(firstDay) ->
                strings.anniversary(date.year - firstDay.year)
            firstMeetingDay != null && date.year > firstMeetingDay.year && monthDay == MonthDay.from(firstMeetingDay) ->
                strings.meetingAnniversary(date.year - firstMeetingDay.year)
            characterBirthday != null && characterBirthday == monthDay -> strings.birthdayChar(characterName.orEmpty())
            userBirthday != null && userBirthday == monthDay -> strings.birthdayUser
            else -> festivalName(date, zone) ?: holidayFirstDayName(date)
        }
        val badge = when (ChineseHolidays.dayInfoFor(date)) {
            is ChineseHolidays.DayInfo.Holiday -> DayBadge.REST
            ChineseHolidays.DayInfo.MakeupWorkday -> DayBadge.WORK
            null -> null
        }
        if (label != null) DayDecor(label, emphasized = true, badge = badge) else DayDecor(OurDaysLunar.label(date, zone), emphasized = false, badge = badge)
    }

    /** 节日：取当日正午毫秒避零点边界（`Festival.matches` 用设备默认时区）。 */
    private fun festivalName(date: LocalDate, zone: ZoneId): String? {
        val noon = date.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()
        return FestivalCalendar.festivalsMatching(noon).firstOrNull()?.name
    }

    /** 法定假名只在假期首日显（前一天不是同名 Holiday）。 */
    private fun holidayFirstDayName(date: LocalDate): String? {
        val today = ChineseHolidays.dayInfoFor(date) as? ChineseHolidays.DayInfo.Holiday ?: return null
        val yesterday = ChineseHolidays.dayInfoFor(date.minusDays(1)) as? ChineseHolidays.DayInfo.Holiday
        return if (yesterday?.name == today.name) null else today.name
    }
}
