package com.situ.aichat.ui.ourdays

import android.icu.util.Calendar
import android.icu.util.ChineseCalendar
import java.time.LocalDate
import java.time.ZoneId

/**
 * 农历标签（卷三图纸 §3.3 锁定·提案 D-4「初一显月名，其余显日」）。来源 = `android.icu.util.ChineseCalendar`
 * （`FestivalCalendar` 已用·零第三方）。月名 / 日名表文化专属·不本地化。ICU 构造较重（每月 42 格 ≈ 42 次）——
 * 调用方在 `Dispatchers.Default` 里算（W-9）。
 */
internal object OurDaysLunar {

    private val MONTH_NAMES = listOf("正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月")

    private val DAY_NAMES = listOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
    )

    /** 该日农历标签：初一 ⇒ `(闰)X月`；其余 ⇒ 日名；越界返 `""`。取当日正午避零点边界。 */
    fun label(date: LocalDate, zone: ZoneId): String {
        val cal = ChineseCalendar().apply {
            timeInMillis = date.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()
        }
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val leap = cal.get(ChineseCalendar.IS_LEAP_MONTH) == 1
        return if (day == 1) {
            val name = MONTH_NAMES.getOrNull(month - 1) ?: return ""
            (if (leap) "闰" else "") + name
        } else {
            DAY_NAMES.getOrNull(day - 1) ?: ""
        }
    }
}
