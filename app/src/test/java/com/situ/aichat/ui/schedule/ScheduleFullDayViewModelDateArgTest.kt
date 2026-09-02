package com.situ.aichat.ui.schedule

import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.repository.CharacterRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * T1-5（卷三图纸 §7.2·W-10 / E27）：日程全天屏 VM 新增可选 `date` 参——合法 ⇒ 初值 = 该日零点；非法 / 缺省 ⇒ today（既有行为字节不变）。
 * 仓里此前无该 VM 的测试类，本类即「既有测试 + 1」的承载。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleFullDayViewModelDateArgTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val todayStart: Long = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun vm(date: String?): ScheduleFullDayViewModel {
        val characterRepo = mockk<CharacterRepository>()
        val scheduleDao = mockk<ScheduleDao>()
        val messageDao = mockk<MessageDao>(relaxed = true)
        every { characterRepo.observe(any()) } returns flowOf(null)
        every { scheduleDao.observeScheduleFor(any(), any()) } returns flowOf(null)
        every { scheduleDao.observeEventsForSchedule(any()) } returns flowOf(emptyList())
        val args = mutableMapOf<String, Any?>(ScheduleFullDayViewModel.ARG_CHARACTER_UUID to "c1")
        if (date != null) args[ScheduleFullDayViewModel.ARG_DATE] = date
        return ScheduleFullDayViewModel(SavedStateHandle(args), characterRepo, scheduleDao, messageDao)
    }

    @Test
    fun 合法date参_初值为该日零点() {
        val expected = LocalDate.of(2026, 3, 4).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, vm("2026-03-04").selectedDate.value)
    }

    @Test
    fun 非法date参_退回today() {
        assertEquals(todayStart, vm("2026-3-4").selectedDate.value)
        assertEquals(todayStart, vm("abc").selectedDate.value)
    }

    @Test
    fun 缺省date参_today_既有行为不变() {
        assertEquals(todayStart, vm(null).selectedDate.value)
    }
}
