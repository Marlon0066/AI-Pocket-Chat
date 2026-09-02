package com.situ.aichat.ui.ourdays

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.OurDayEntity
import com.situ.aichat.data.repository.OurDayRepository
import com.situ.aichat.ourdays.OurDayKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * T2-4（卷三图纸 §7.2·Robolectric 真 Room）：资料卡三数字（第 N 天 / 本月聊天日 / 见面天数）+ 14 格 + 空态 `hasAny = false`（E4）。
 * 父路由参 `characterUuid` 经 SavedStateHandle 注入（F15 范式）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OurDaysEntryViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: OurDayRepository
    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)
    private val jobs = mutableListOf<Job>()
    private val vms = mutableListOf<OurDaysEntryViewModel>()
    private var latest: OurDaysEntryState? = null

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        repo = OurDayRepository(db.ourDayDao())
    }

    /** 先撤 VM 的 `viewModelScope`、空转到收尾，再关库——否则孤儿协程摸到已关的库会泄漏进全局 handler，冤枉下一条测试（PITFALLS §1e）。 */
    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        vms.forEach { it.viewModelScope.cancel() }
        repeat(20) { idle(); Thread.sleep(2) }
        db.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(message: String, condition: () -> Boolean) {
        repeat(400) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    private fun row(date: LocalDate, mc: Int = 1, meeting: Boolean = false, deleted: Boolean = false, char: String = "c1") =
        OurDayEntity(uuid = "$char-${OurDayKey.keyOf(date)}", characterUuid = char, dayKey = OurDayKey.keyOf(date), messageCount = mc, hasMeeting = meeting, deleted = deleted, createdAtMillis = 1, updatedAtMillis = 1)

    private fun insert(vararg rows: OurDayEntity) = runBlocking { rows.forEach { db.ourDayDao().upsert(it) } }

    private fun vm(uuid: String = "c1"): OurDaysEntryViewModel {
        val vm = OurDaysEntryViewModel(SavedStateHandle(mapOf(OurDaysEntryViewModel.ARG_CHARACTER_UUID to uuid)), repo)
        vms += vm
        jobs += CoroutineScope(Dispatchers.Main).launch { vm.state.collect { latest = it } }
        return vm
    }

    private fun loaded(cond: (OurDaysEntryState) -> Boolean = { true }): OurDaysEntryState {
        await("state") { latest?.let { it.loaded && cond(it) } == true }
        return latest!!
    }

    @Test
    fun 空态_hasAny为false_三数字为0_14格全无热度() {
        vm()
        val s = loaded()
        assertFalse(s.hasAny); assertEquals(0, s.daysTogether); assertEquals(0, s.chatDaysThisMonth); assertEquals(0, s.meetingDays)
        assertEquals(14, s.bar.size); assertTrue(s.bar.all { it.heatLevel == 0 })
        assertEquals(today.minusDays(13), s.rangeStart); assertEquals(today.minusDays(13), s.bar.first().date); assertTrue(s.bar.last().isToday)
    }

    @Test
    fun 三数字_第N天从相识日_本月聊天日按热度_见面天数计非墓碑() {
        // 全部相对今天且日期互不重合（今天若是月初，部分行落到上月：本月聊天日按 monthStart 过滤反推）。
        val monthStart = YearMonth.from(today).atDay(1)
        insert(
            row(today.minusDays(40), mc = 1),                                // 相识日（本月外）
            row(today.minusDays(1), mc = 12, meeting = true),                 // 聊天日 + 见面
            row(today.minusDays(2), mc = 0),                                  // 有行但热度 0：不算聊天日
            row(today.minusDays(3), mc = 3, meeting = true, deleted = true),  // 墓碑：见面天数不计·热度照算
        )
        vm()
        val s = loaded { it.daysTogether > 0 }
        assertTrue(s.hasAny)
        assertEquals(41, s.daysTogether)
        val expectedChatDays = listOf(today.minusDays(1), today.minusDays(3)).count { !it.isBefore(monthStart) }
        assertEquals(expectedChatDays, s.chatDaysThisMonth)
        assertEquals(1, s.meetingDays)
        // 墓碑行仍出热度与点（§3.4）
        assertEquals(listOf(DotFamily.MEETING), s.bar.first { it.date == today.minusDays(3) }.dots)
    }

    @Test
    fun 十四格_只含该角色_热度与见面点() {
        insert(row(today.minusDays(3), mc = 45, meeting = true), row(today.minusDays(3), mc = 99, char = "other"), row(today.minusDays(14), mc = 5))
        vm()
        val s = loaded { it.hasAny }
        val cell = s.bar.first { it.date == today.minusDays(3) }
        assertEquals(3, cell.heatLevel); assertEquals(listOf(DotFamily.MEETING), cell.dots)
        assertTrue(s.bar.none { it.date == today.minusDays(14) })
    }

    @Test
    fun 相识日在14格与本月之外_hasAny仍true() {
        insert(row(today.minusDays(60), mc = 1))
        vm()
        val s = loaded { it.hasAny }
        assertEquals(61, s.daysTogether); assertEquals(0, s.chatDaysThisMonth); assertTrue(s.bar.all { it.heatLevel == 0 })
    }
}
