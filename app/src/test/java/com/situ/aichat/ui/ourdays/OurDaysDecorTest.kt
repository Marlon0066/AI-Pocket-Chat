package com.situ.aichat.ui.ourdays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

/**
 * T1-2（卷三图纸 §7.2·Robolectric 因 ICU 农历 + FestivalCalendar）：副行五级优先级 + 撞日 + 假期首日 / 表外年份 + 周年 + 全部模式。
 * 断言从 §3.3 优先级独立反推；节日 / 假日事实取自 `FestivalCalendar` / `ChineseHolidays` 硬表（只读）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OurDaysDecorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val strings = DecorStrings(
        firstDay = "相识", firstMeeting = "初见",
        anniversary = { "相识 $it 周年" }, meetingAnniversary = { "初见 $it 周年" },
        birthdayChar = { "${it}生日" }, birthdayUser = "你的生日",
    )

    private fun decor(
        characterName: String? = "林晚",
        characterBirthday: MonthDay? = null,
        userBirthday: MonthDay? = null,
        firstDay: LocalDate? = null,
        firstMeetingDay: LocalDate? = null,
    ) = OurDaysDecor.factory(zone, characterName, characterBirthday, userBirthday, firstDay, firstMeetingDay, strings)

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test fun 一级纪念_相识压过春节与假期() {
        val r = decor(firstDay = d(2026, 2, 17))(d(2026, 2, 17))
        assertEquals("相识", r.subtitle); assertTrue(r.emphasized); assertEquals(DayBadge.REST, r.badge)
    }

    @Test fun 初见与相识同日只显相识() {
        assertEquals("相识", decor(firstDay = d(2026, 3, 4), firstMeetingDay = d(2026, 3, 4))(d(2026, 3, 4)).subtitle)
        assertEquals("初见", decor(firstDay = d(2026, 3, 1), firstMeetingDay = d(2026, 3, 4))(d(2026, 3, 4)).subtitle)
    }

    @Test fun 相识周年_N为年差_当年不算周年() {
        val f = decor(firstDay = d(2024, 9, 2))
        assertEquals("相识", f(d(2024, 9, 2)).subtitle)
        assertEquals("相识 1 周年", f(d(2025, 9, 2)).subtitle)
        assertEquals("相识 2 周年", f(d(2026, 9, 2)).subtitle)
        assertFalse(f(d(2026, 9, 3)).emphasized)
    }

    @Test fun 初见周年() {
        assertEquals("初见 1 周年", decor(firstMeetingDay = d(2025, 6, 6))(d(2026, 6, 6)).subtitle)
    }

    @Test fun 二级生日_角色生日压过劳动节_纪念仍在生日之上() {
        assertEquals("林晚生日", decor(characterBirthday = MonthDay.of(5, 1))(d(2026, 5, 1)).subtitle)
        assertEquals("相识", decor(characterBirthday = MonthDay.of(5, 1), firstDay = d(2026, 5, 1))(d(2026, 5, 1)).subtitle)
    }

    @Test fun 双生日同日显角色的_单用户生日显你的生日() {
        assertEquals("林晚生日", decor(characterBirthday = MonthDay.of(3, 4), userBirthday = MonthDay.of(3, 4))(d(2026, 3, 4)).subtitle)
        val r = decor(userBirthday = MonthDay.of(3, 4))(d(2026, 3, 4))
        assertEquals("你的生日", r.subtitle); assertTrue(r.emphasized)
    }

    @Test fun 三级节日_国庆节日名压过法定假名_并带休角标() {
        val r = decor()(d(2026, 10, 1))
        assertEquals("国庆", r.subtitle); assertTrue(r.emphasized); assertEquals(DayBadge.REST, r.badge)
    }

    @Test fun 四级假期首日显名_次日只角标副行回农历() {
        val first = decor()(d(2026, 4, 4))
        assertEquals("清明节", first.subtitle); assertTrue(first.emphasized); assertEquals(DayBadge.REST, first.badge)
        val second = decor()(d(2026, 4, 5))
        assertEquals(OurDaysLunar.label(d(2026, 4, 5), zone), second.subtitle)
        assertFalse(second.emphasized); assertEquals(DayBadge.REST, second.badge)
    }

    @Test fun 补班日带班角标_节日照显() {
        val r = decor()(d(2026, 2, 14))
        assertEquals("情人节", r.subtitle); assertEquals(DayBadge.WORK, r.badge)
    }

    @Test fun 表外年份无角标_节日仍显() {
        val r = decor()(d(2027, 1, 1))
        assertEquals("元旦", r.subtitle); assertTrue(r.emphasized); assertNull(r.badge)
    }

    @Test fun 五级农历_不强调无角标() {
        val r = decor()(d(2026, 3, 4))
        assertEquals("十六", r.subtitle); assertFalse(r.emphasized); assertNull(r.badge)
    }

    @Test fun 全部模式无生日无纪念_落到节日() {
        val r = decor(characterName = null, characterBirthday = null, userBirthday = null)(d(2026, 5, 1))
        assertEquals("劳动节", r.subtitle)
    }
}
