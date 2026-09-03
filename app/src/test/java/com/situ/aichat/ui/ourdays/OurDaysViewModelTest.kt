package com.situ.aichat.ui.ourdays

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.work.ExistingWorkPolicy
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.OurDayEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.OurDayRepository
import com.situ.aichat.ourdays.OurDayCoordinator
import com.situ.aichat.ourdays.OurDayCoordinator.BackfillProgress
import com.situ.aichat.ourdays.OurDayKey
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.OurDayCatchUpWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import java.time.YearMonth
import java.time.ZoneId

/**
 * T2-1（卷三图纸 §7.2·Robolectric 真 Room 内存库 + MockK 协调器 / 调度器）：默认预选 / 回退 / 零角色（E1 E2）、KEY 写回、
 * 切视图 anchor 不变、shiftPeriod 三模式、goToday、openMonth、全部模式走 observeCalendarRangeAll、回填横幅过滤 / 求和（E24）、
 * backfillMonth 目标筛选 + 全部模式无动作（E22）、进程死亡恢复（E23）、init ensure 恰一次。
 * `today` 由 VM 构造时取真实时钟 ⇒ 行相对真实 now 构造（PITFALLS §1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OurDaysViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: OurDayRepository
    private val charactersFlow = MutableStateFlow<List<CharacterEntity>>(emptyList())
    private val progress = MutableStateFlow<Map<String, BackfillProgress>>(emptyMap())
    private val characterRepo = mockk<CharacterRepository>()
    private val diaryRepo = mockk<DiaryRepository>()
    private val coordinator = mockk<OurDayCoordinator>()
    private val scheduler = mockk<BackgroundScheduler>(relaxed = true)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)
    private val jobs = mutableListOf<Job>()
    private val vms = mutableListOf<OurDaysViewModel>()
    private val regenerated = mutableListOf<String>()
    private var latest: OurDaysUiState = OurDaysUiState.Loading

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        repo = OurDayRepository(db.ourDayDao())
        every { characterRepo.observeAll() } returns charactersFlow
        every { coordinator.backfillProgress } returns progress
        coEvery { coordinator.regenerate(any(), any()) } answers { regenerated += secondArg<String>(); true }
        coEvery { diaryRepo.entriesInRange(any(), any()) } returns emptyList()
    }

    /**
     * 先撤 VM 自己的 `viewModelScope`（它在收 Room / mock 流），空转到收尾，**再**关库——否则孤儿协程摸到已关的库
     * 抛进全局 handler，被下一条 `runTest` replay 成「测试开跑前就失败」，冤枉别的测试类（PITFALLS §1e 泄漏源形状）。
     */
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

    private fun row(char: String, date: LocalDate, mc: Int = 1, status: String = "ok", deleted: Boolean = false, edited: Boolean = false, updated: Long = 1L, meeting: Boolean = false) =
        OurDayEntity(uuid = "$char-${OurDayKey.keyOf(date)}", characterUuid = char, dayKey = OurDayKey.keyOf(date), messageCount = mc, hasMeeting = meeting, note = if (status == "ok") "手记" else "", noteStatus = status, noteEdited = edited, deleted = deleted, createdAtMillis = 1, updatedAtMillis = updated)

    private fun insert(vararg rows: OurDayEntity) = runBlocking { rows.forEach { db.ourDayDao().upsert(it) } }

    private fun vm(handle: SavedStateHandle = SavedStateHandle()): OurDaysViewModel {
        val vm = OurDaysViewModel(handle, repo, characterRepo, db.userProfileDao(), diaryRepo, coordinator, scheduler, RuntimeEnvironment.getApplication())
        vms += vm
        jobs += CoroutineScope(Dispatchers.Main).launch { vm.uiState.collect { latest = it } }
        return vm
    }

    /** 等到 Content **且**满足 [cond]——只等「到 Content」会读到上一次组装的旧值（全量跑时必竞态）。 */
    private fun content(cond: (OurDaysUiState.Content) -> Boolean = { true }): OurDaysUiState.Content {
        await("uiState 到 Content") { (latest as? OurDaysUiState.Content)?.let(cond) == true }
        return latest as OurDaysUiState.Content
    }

    @Test
    fun 默认预选_最近活跃角色_并写回KEY() {
        charactersFlow.value = listOf(character("c1", 10), character("c2", 20), character("c3", 30))
        insert(row("c1", today.minusDays(1)), row("c2", today.minusDays(3)))
        val handle = SavedStateHandle()
        val vm = vm(handle)
        await("选中 c1") { vm.selection.value == OurDaysSelection.Character("c1") }
        await("写回") { handle.get<String>("ourdays_selection") == "c1" }
        assertEquals(OurDaysSelection.Character("c1"), content { it.selection == OurDaysSelection.Character("c1") }.selection)
    }

    @Test
    fun 无互动行_回退最新创建_零角色None() {
        charactersFlow.value = listOf(character("old", 10), character("new", 99))
        val vm = vm()
        await("回退最新创建") { vm.selection.value == OurDaysSelection.Character("new") }

        charactersFlow.value = emptyList()
        await("零角色") { vm.selection.value == OurDaysSelection.None }
        val none = content { it.selection is OurDaysSelection.None }
        assertEquals(OurDaysSelection.None, none.selection)
        assertFalse(none.hasAnyRow)
    }

    @Test
    fun E1_入口角色已删_退回解析并写回() {
        charactersFlow.value = listOf(character("c1", 10))
        val handle = SavedStateHandle(mapOf(OurDaysViewModel.ARG_CHARACTER to "ghost"))
        val vm = vm(handle)
        await("退回 c1") { vm.selection.value == OurDaysSelection.Character("c1") }
        await("写回") { handle.get<String>("ourdays_selection") == "c1" }
    }

    @Test
    fun 入口日期参_非法退回今天_合法为锚() {
        charactersFlow.value = listOf(character("c1", 10))
        assertEquals(today, vm(SavedStateHandle(mapOf(OurDaysViewModel.ARG_DATE to "2026-9-2"))).anchor.value)
        assertEquals(LocalDate.of(2026, 3, 4), vm(SavedStateHandle(mapOf(OurDaysViewModel.ARG_DATE to "2026-03-04"))).anchor.value)
    }

    @Test
    fun 切视图_anchor不变_period随视图() {
        charactersFlow.value = listOf(character("c1", 10))
        val vm = vm()
        vm.setAnchor(LocalDate.of(2026, 9, 10))
        vm.setViewMode(OurDaysViewMode.WEEK)
        idle()
        assertEquals(LocalDate.of(2026, 9, 10), vm.anchor.value)
        assertEquals(OurDaysViewMode.WEEK, vm.viewMode.value)
        assertTrue(LocalDate.of(2026, 9, 10) in vm.period.value)
        assertEquals(7, java.time.temporal.ChronoUnit.DAYS.between(vm.period.value.start, vm.period.value.endInclusive) + 1)
        vm.setViewMode(OurDaysViewMode.YEAR)
        idle()
        assertEquals(LocalDate.of(2026, 9, 10), vm.anchor.value)
        assertEquals(LocalDate.of(2026, 1, 1), vm.period.value.start)
    }

    @Test
    fun shiftPeriod三模式_月末钳位() {
        charactersFlow.value = listOf(character("c1", 10))
        val vm = vm()
        vm.setAnchor(LocalDate.of(2026, 1, 31)); idle()
        vm.shiftPeriod(1); idle()
        assertEquals(LocalDate.of(2026, 2, 28), vm.anchor.value)
        vm.setViewMode(OurDaysViewMode.WEEK); idle()
        vm.shiftPeriod(-1); idle()
        assertEquals(LocalDate.of(2026, 2, 21), vm.anchor.value)
        vm.setViewMode(OurDaysViewMode.YEAR); idle()
        vm.shiftPeriod(1); idle()
        assertEquals(LocalDate.of(2027, 2, 21), vm.anchor.value)
    }

    @Test
    fun goToday与openMonth() {
        charactersFlow.value = listOf(character("c1", 10))
        val vm = vm()
        vm.setAnchor(today.minusYears(1)); idle()
        vm.goToday(); idle()
        assertEquals(today, vm.anchor.value)
        vm.setViewMode(OurDaysViewMode.YEAR); idle()
        vm.openMonth(YearMonth.from(today)); idle()
        assertEquals(today, vm.anchor.value); assertEquals(OurDaysViewMode.MONTH, vm.viewMode.value)
        vm.setViewMode(OurDaysViewMode.YEAR); idle()
        vm.openMonth(YearMonth.from(today).minusMonths(2)); idle()
        assertEquals(YearMonth.from(today).minusMonths(2).atDay(1), vm.anchor.value); assertEquals(OurDaysViewMode.MONTH, vm.viewMode.value)
    }

    @Test
    fun 全部模式_rows走全部范围_同日两角色识别圈() {
        charactersFlow.value = listOf(character("c1", 10), character("c2", 20))
        val d = today.minusDays(1)
        insert(row("c1", d, mc = 3), row("c2", d, mc = 3))
        val vm = vm()
        vm.select(OurDaysSelection.All)
        await("全部模式月格识别圈") {
            (latest as? OurDaysUiState.Content)?.let { c -> c.selection == OurDaysSelection.All && c.month?.cells?.firstOrNull { it.date == d }?.identity?.size == 2 } == true
        }
        val c = content { it.selection == OurDaysSelection.All }
        assertTrue(c.hasAnyRow)
        assertTrue(c.month!!.summary.allMode)
        assertEquals(1, c.month.summary.recordedDays)
    }

    /** R1 🟡-2：初见日走全史 MIN——原实现从「当前期的行」里取最早见面 ⇒ 每翻到一个含见面的月份，该月首场见面都被误标「初见」。 */
    @Test
    fun R1_初见标签_只落全史最早见面日_不随翻期漂移() {
        charactersFlow.value = listOf(character("c1", 10))
        // 相识日（更早的纯聊天行）必须另设：相识与初见同日时按 §3.3 优先级只显「相识」。
        val firstChat = today.minusMonths(4).withDayOfMonth(1)
        val firstMeet = today.minusMonths(3).withDayOfMonth(10)
        val laterMeet = today.minusMonths(1).withDayOfMonth(20)
        insert(row("c1", firstChat), row("c1", firstMeet, meeting = true), row("c1", laterMeet, meeting = true))
        val label = RuntimeEnvironment.getApplication().getString(com.situ.aichat.R.string.our_days_label_first_meeting)
        val vm = vm()
        // 等到「该月的行真到位」再断言——只等锚会读到「锚已换、Room 结果未到」的中间帧（那时 rows 空，两种实现都不出初见）。
        fun cellOf(c: OurDaysUiState.Content, d: LocalDate) = c.month?.cells?.firstOrNull { it.date == d }
        vm.setAnchor(laterMeet)
        val later = content { it.anchor == laterMeet && cellOf(it, laterMeet)?.heatLevel == 1 }
        assertEquals("期外还有更早的见面 ⇒ 本月这场不是初见", null, cellOf(later, laterMeet)!!.decor?.subtitle?.takeIf { it == label })
        vm.setAnchor(firstMeet)
        val first = content { it.anchor == firstMeet && cellOf(it, firstMeet)?.heatLevel == 1 }
        assertEquals("全史最早见面日才是初见", label, cellOf(first, firstMeet)!!.decor?.subtitle)
    }

    @Test
    fun 回填横幅_单角色取条目_全部求和_空null() {
        val map = mapOf("c1" to BackfillProgress(1, 4), "c2" to BackfillProgress(2, 6))
        assertEquals(BackfillProgress(1, 4), OurDaysViewModel.bannerProgress(map, OurDaysSelection.Character("c1")))
        assertNull(OurDaysViewModel.bannerProgress(map, OurDaysSelection.Character("c9")))
        assertEquals(BackfillProgress(3, 10), OurDaysViewModel.bannerProgress(map, OurDaysSelection.All))
        assertNull(OurDaysViewModel.bannerProgress(emptyMap(), OurDaysSelection.All))
        assertNull(OurDaysViewModel.bannerProgress(map, OurDaysSelection.None))
    }

    @Test
    fun 补写这个月_目标筛选_顺序regenerate_完成后进度归null() {
        charactersFlow.value = listOf(character("c1", 10))
        val ym = YearMonth.from(today)
        // 用本月 1 日..今天造行（今天当月内必存在）；今天行须排除。
        val d1 = ym.atDay(1)
        insert(
            row("c1", d1, status = "ok"),                                   // ok 排除
            row("c1", today, status = "none"),                              // 今天排除
            row("c1", today.minusDays(0), status = "none").copy(uuid = "dup-today", dayKey = OurDayKey.keyOf(today)), // 同日重复 upsert 覆盖·仍今天
            row("c1", ym.atDay(2), status = "failed"),                      // 目标
            row("c1", ym.atDay(3), status = "none", deleted = true),        // 墓碑排除
            row("c1", ym.atDay(4), status = "none", edited = true),         // 手改排除
            row("c1", ym.atDay(5), status = "none"),                        // 目标（若 5 日 < 今天）
        )
        val vm = vm()
        vm.setAnchor(ym.atDay(1)); idle()
        await("选中") { vm.selection.value == OurDaysSelection.Character("c1") }
        val expected = listOf(ym.atDay(2), ym.atDay(5)).filter { it.isBefore(today) }.map { OurDayKey.keyOf(it) }
        vm.backfillMonth()
        // R1 🟡-3：等「效果」不等 monthBackfill——它的初值就是 null（终值也是 null），等它会在动作发生前就放行（实测真红）。
        await("逐日 regenerate 跑完") { regenerated.size == expected.size }
        await("进度归 null") { vm.monthBackfill.value == null }
        assertEquals("目标筛选 + 按 dayKey 升序（用例名的「顺序」）", expected, regenerated.toList())
        coVerify(exactly = 0) { coordinator.regenerate("c1", OurDayKey.keyOf(d1)) }
        coVerify(exactly = 0) { coordinator.regenerate("c1", OurDayKey.keyOf(today)) }
    }

    @Test
    fun E22_全部模式_补写无动作() {
        charactersFlow.value = listOf(character("c1", 10), character("c2", 20))
        insert(row("c1", today.minusDays(1), status = "none"))
        val vm = vm()
        vm.select(OurDaysSelection.All)
        await("全部") { vm.selection.value == OurDaysSelection.All }
        vm.backfillMonth(); idle()
        assertNull(vm.monthBackfill.value)
        coVerify(exactly = 0) { coordinator.regenerate(any(), any()) }
    }

    @Test
    fun E23_进程死亡_同SavedStateHandle重建恢复选中视图与锚() {
        charactersFlow.value = listOf(character("c1", 10), character("c2", 20))
        val handle = SavedStateHandle()
        val first = vm(handle)
        first.select(OurDaysSelection.Character("c1"))
        first.setViewMode(OurDaysViewMode.WEEK)
        first.setAnchor(LocalDate.of(2026, 3, 4))
        idle()
        val second = vm(handle)
        await("恢复选中") { second.selection.value == OurDaysSelection.Character("c1") }
        assertEquals(OurDaysViewMode.WEEK, second.viewMode.value)
        assertEquals(LocalDate.of(2026, 3, 4), second.anchor.value)
    }

    @Test
    fun init_调ensure一次性任务_KEEP_恰一次() {
        charactersFlow.value = listOf(character("c1", 10))
        vm()
        verify(exactly = 1) {
            scheduler.scheduleOneShot(OurDayCatchUpWorker.UNIQUE_ENSURE, OurDayCatchUpWorker::class.java, null, true, ExistingWorkPolicy.KEEP, null)
        }
    }

    @Test
    fun 单角色零行_hasAnyRow为false_有行为true() {
        charactersFlow.value = listOf(character("c1", 10))
        val vm = vm()
        await("零行") { (latest as? OurDaysUiState.Content)?.hasAnyRow == false }
        insert(row("c1", today.minusDays(40)))
        await("有行（相识日在期外也算）") { (latest as? OurDaysUiState.Content)?.hasAnyRow == true }
        assertEquals("角c1", content { it.characterName == "角c1" }.characterName)
        assertTrue(vm.selection.value is OurDaysSelection.Character)
    }
}
