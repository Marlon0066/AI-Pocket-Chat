package com.situ.aichat.ui.ourdays

import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.OurDayEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.OurDayRepository
import com.situ.aichat.ourdays.OurDayKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * T2-3（卷三图纸 §7.2·Robolectric 真 Room）：入口条角色 = 最近活跃 / 回退最新创建 / 零角色；预览三态链（昨天 → 近 7 天最近 → null）；
 * nthDay；七格自周首日起。`today` 取真实时钟 ⇒ 行相对 now 构造。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OurDaysStripViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: OurDayRepository
    private val charactersFlow = MutableStateFlow<List<CharacterEntity>>(emptyList())
    private val characterRepo = mockk<CharacterRepository>()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)
    private val jobs = mutableListOf<Job>()
    private val vms = mutableListOf<OurDaysStripViewModel>()
    private var latest: OurDaysStripState? = null

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        repo = OurDayRepository(db.ourDayDao())
        every { characterRepo.observeAll() } returns charactersFlow
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

    private fun character(uuid: String, creation: Long) = CharacterEntity(uuid = uuid, name = "角$uuid", creationDate = creation)

    private fun row(char: String, date: LocalDate, mc: Int = 1, note: String = "", deleted: Boolean = false) =
        OurDayEntity(uuid = "$char-${OurDayKey.keyOf(date)}", characterUuid = char, dayKey = OurDayKey.keyOf(date), messageCount = mc, note = note, noteStatus = if (note.isBlank()) "none" else "ok", deleted = deleted, createdAtMillis = 1, updatedAtMillis = 1)

    private fun insert(vararg rows: OurDayEntity) = runBlocking { rows.forEach { db.ourDayDao().upsert(it) } }

    private fun vm(): OurDaysStripViewModel {
        jobs.forEach { it.cancel() }; jobs.clear(); latest = null
        val vm = OurDaysStripViewModel(repo, characterRepo)
        vms += vm
        jobs += CoroutineScope(Dispatchers.Main).launch { vm.state.collect { latest = it } }
        return vm
    }

    private fun loaded(cond: (OurDaysStripState) -> Boolean = { true }): OurDaysStripState {
        await("state") { latest?.let { it.loaded && cond(it) } == true }
        return latest!!
    }

    @Test
    fun 最近活跃角色_优先于最新创建() {
        charactersFlow.value = listOf(character("c1", 10), character("c2", 20))
        insert(row("c1", today.minusDays(1)))
        vm()
        assertEquals("c1", loaded { it.character != null }.character!!.uuid)
    }

    @Test
    fun 无互动_回退最新创建() {
        charactersFlow.value = listOf(character("c1", 10), character("c2", 20))
        vm()
        assertEquals("c2", loaded { it.character != null }.character!!.uuid)
    }

    @Test
    fun 零角色_空副标_七格仍在_预览null() {
        vm()
        val s = loaded()
        assertNull(s.character); assertNull(s.nthDay); assertNull(s.preview)
        assertEquals(7, s.week.size)
        assertEquals(OurDaysCalendarLogic.weekStart(today, WeekFields.of(Locale.getDefault())), s.week.first().date)
        assertTrue(s.week.any { it.isToday })
    }

    @Test
    fun 预览链_昨天有手记优先() {
        charactersFlow.value = listOf(character("c1", 10))
        insert(row("c1", today.minusDays(1), note = "昨天很开心。后面"), row("c1", today.minusDays(3), note = "三天前。"))
        vm()
        val p = loaded { it.preview != null }.preview!!
        assertTrue(p.isYesterday); assertEquals(today.minusDays(1), p.date); assertEquals("昨天很开心。", p.firstSentence); assertEquals("角c1", p.characterName)
    }

    @Test
    fun 预览链_昨天无手记取近七天最近一篇_墓碑与空手记不算() {
        charactersFlow.value = listOf(character("c1", 10))
        insert(
            row("c1", today.minusDays(1)),                                  // 昨天无手记
            row("c1", today.minusDays(2), note = "墓碑", deleted = true),     // 墓碑不算
            row("c1", today.minusDays(4), note = "四天前的事。"),
            row("c1", today.minusDays(6), note = "六天前。"),
            row("c1", today.minusDays(9), note = "九天前不在窗内。"),
        )
        vm()
        val p = loaded { it.preview != null }.preview!!
        assertFalse(p.isYesterday); assertEquals(today.minusDays(4), p.date); assertEquals("四天前的事。", p.firstSentence)
    }

    @Test
    fun 预览链_近七天无手记为null_nthDay从相识日算() {
        charactersFlow.value = listOf(character("c1", 10))
        insert(row("c1", today.minusDays(20), note = "太早了。"), row("c1", today.minusDays(1)))
        vm()
        val s = loaded { it.nthDay != null }
        assertNull(s.preview)
        assertEquals(21, s.nthDay)
        assertEquals(1, s.week.first { it.date == today.minusDays(1) }.heatLevel)
    }
}
