package com.situ.aichat.ui.ourdays

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * T1-1（卷三图纸 §7.2·Robolectric 因 `android.icu`）：农历标签表——初一显月名（含闰）、其余显日名。
 * 期望值从公开农历事实独立反推（2026 春节 02-17 / 元宵 03-03 / 中秋 09-25 / 2025 闰六月初一 07-25 / 腊八 2026-01-26）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OurDaysLunarTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun label(y: Int, m: Int, d: Int) = OurDaysLunar.label(LocalDate.of(y, m, d), zone)

    @Test fun 普通日显日名_2026_09_02_七月廿一() = assertEquals("廿一", label(2026, 9, 2))

    @Test fun 初一显月名_2026_08_13_七月() = assertEquals("七月", label(2026, 8, 13))

    @Test fun 中秋_2026_09_25_十五() = assertEquals("十五", label(2026, 9, 25))

    @Test fun 春节_2026_02_17_正月() = assertEquals("正月", label(2026, 2, 17))

    @Test fun 闰月初一_2025_07_25_闰六月() = assertEquals("闰六月", label(2025, 7, 25))

    @Test fun 腊月初一_2026_01_19() = assertEquals("腊月", label(2026, 1, 19))

    @Test fun 冬月初一_2025_12_20() = assertEquals("冬月", label(2025, 12, 20))

    @Test fun 日名表_初二初十二十廿九() {
        assertEquals("初二", label(2026, 2, 18))
        assertEquals("初十", label(2026, 2, 26))
        assertEquals("二十", label(2026, 3, 8))
        assertEquals("廿九", label(2026, 3, 17))
    }

    @Test fun 元宵_2026_03_03_十五_且次月初一显月名() {
        assertEquals("十五", label(2026, 3, 3))
        // 八月初一 = 2026-09-11（中秋 09-25 回推 14 天）
        assertEquals("八月", label(2026, 9, 11))
    }
}
