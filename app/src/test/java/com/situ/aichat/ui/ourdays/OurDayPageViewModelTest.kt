package com.situ.aichat.ui.ourdays

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.situ.aichat.R
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.OurDayEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.OurDayRepository
import com.situ.aichat.ourdays.OurDayCoordinator
import com.situ.aichat.ourdays.OurDayFacts
import com.situ.aichat.ourdays.OurDayFactsJson
import com.situ.aichat.ourdays.OurDayKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
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

/**
 * T2-2（卷三图纸 §7.2·Robolectric 真 Room + MockK 协调器）：过去日 init `refreshFacts` 恰一次（今天 / 全部不调·E9）；六状态；
 * `save` 三分支（W-7 / E18）+ 空白不保存（E19）；`rewrite` 成功 / 失败 toast（E15）；`busy` 守卫（E21）；`delete`；hidden 页脚（E17）；
 * 墓碑（E16）；草稿恢复（E23）；第 N 天 + 事实层。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OurDayPageViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: OurDayRepository
    private val characterRepo = mockk<CharacterRepository>()
    private val diaryRepo = mockk<DiaryRepository>()
    private val coordinator = mockk<OurDayCoordinator>()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)
    private val yesterday: LocalDate = today.minusDays(1)
    private val jobs = mutableListOf<Job>()
    private val vms = mutableListOf<OurDayPageViewModel>()
    private var latest: OurDayPageUiState? = null
    private val toasts = mutableListOf<Int>()
    private val regenerated = mutableListOf<String>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        repo = OurDayRepository(db.ourDayDao())
        every { characterRepo.observeAll() } returns flowOf(listOf(CharacterEntity(uuid = "c1", name = "林晚", creationDate = 1), CharacterEntity(uuid = "c2", name = "阿棠", creationDate = 2)))
        coEvery { coordinator.refreshFacts(any(), any()) } returns null
        coEvery { coordinator.regenerate(any(), any()) } answers { regenerated += secondArg<String>(); true }
        coEvery { coordinator.saveUserNote(any(), any(), any(), any()) } returns Unit
        coEvery { coordinator.setHidden(any(), any(), any()) } returns Unit
        coEvery { coordinator.markDeleted(any(), any()) } returns Unit
        coEvery { diaryRepo.entriesInRange(any(), any()) } returns emptyList()
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

    private fun row(date: LocalDate = yesterday, char: String = "c1", note: String = "昨天我们聊了很久。", status: String = "ok", hidden: Boolean = false, deleted: Boolean = false, facts: String = "") =
        OurDayEntity(uuid = "$char-${OurDayKey.keyOf(date)}", characterUuid = char, dayKey = OurDayKey.keyOf(date), factsJson = facts, messageCount = 3, note = note, factLine = "事实行", noteStatus = status, hiddenFromMemory = hidden, deleted = deleted, generatedAt = 10L, createdAtMillis = 1, updatedAtMillis = 1)

    private fun insert(vararg rows: OurDayEntity) = runBlocking { rows.forEach { db.ourDayDao().upsert(it) } }

    private fun vm(char: String = "c1", date: LocalDate = yesterday, extra: Map<String, Any?> = emptyMap()): OurDayPageViewModel {
        // 同一测试里多次建 VM：先撤上一只的收集器，否则旧 VM 继续往 latest 写（竞态假红）。
        jobs.forEach { it.cancel() }
        jobs.clear()
        latest = null
        val handle = SavedStateHandle(mapOf(OurDayPageViewModel.ARG_CHARACTER_UUID to char, OurDayPageViewModel.ARG_DAY_KEY to OurDayKey.keyOf(date)) + extra)
        val vm = OurDayPageViewModel(handle, repo, characterRepo, db.userProfileDao(), diaryRepo, coordinator, RuntimeEnvironment.getApplication())
        vms += vm
        jobs += CoroutineScope(Dispatchers.Main).launch { vm.uiState.collect { latest = it } }
        jobs += CoroutineScope(Dispatchers.Main).launch { vm.toast.collect { toasts += it } }
        return vm
    }

    private fun loaded(): OurDayPageUiState {
        await("首帧") { latest?.loaded == true }
        return latest!!
    }

    private fun awaitRow(vm: OurDayPageViewModel) = await("行到位") { latest?.loaded == true && latest?.row != null }

    @Test
    fun 过去日init调refreshFacts恰一次_今天与全部不调() {
        vm(); idle()
        coVerify(exactly = 1) { coordinator.refreshFacts("c1", OurDayKey.keyOf(yesterday)) }
        vm(date = today); idle()
        vm(char = OurDaysRoutes.ALL); idle()
        coVerify(exactly = 1) { coordinator.refreshFacts(any(), any()) }
    }

    @Test
    fun 六状态_按行判定() {
        fun statusOf(r: OurDayEntity?, date: LocalDate = yesterday): CardStatus {
            latest = null
            if (r != null) insert(r)
            val s = vm(date = date).let { loaded() }
            if (r != null) await("行") { latest?.row != null }
            return latest!!.card!!.status
        }
        assertEquals(CardStatus.EMPTY, statusOf(null))
        assertEquals(CardStatus.NORMAL, statusOf(row()))
        assertEquals(CardStatus.HIDDEN_NORMAL, statusOf(row(date = yesterday.minusDays(1), hidden = true), yesterday.minusDays(1)))
        assertEquals(CardStatus.FAILED, statusOf(row(date = yesterday.minusDays(2), note = "", status = "failed"), yesterday.minusDays(2)))
        assertEquals(CardStatus.DELETED, statusOf(row(date = yesterday.minusDays(3), note = "", status = "none", deleted = true), yesterday.minusDays(3)))
        latest = null
        vm(date = today)
        assertEquals(CardStatus.TODAY, loaded().card!!.status)
    }

    @Test
    fun save_note变_只调saveUserNote带原事实行_并关sheet() {
        insert(row())
        val vm = vm(); awaitRow(vm)
        vm.openSheet(); idle()
        assertTrue(vm.sheetOpen.value); assertEquals("昨天我们聊了很久。", vm.draft.value)
        vm.updateDraft("改过的手记")
        assertTrue(vm.isDirty())
        vm.save()
        await("sheet 关") { !vm.sheetOpen.value }
        coVerify(exactly = 1) { coordinator.saveUserNote("c1", OurDayKey.keyOf(yesterday), "改过的手记", "事实行") }
        coVerify(exactly = 0) { coordinator.setHidden(any(), any(), any()) }
    }

    @Test
    fun save_只改开关_只调setHidden() {
        insert(row())
        val vm = vm(); awaitRow(vm)
        vm.openSheet(); idle()
        vm.updateDraftHidden(true)
        vm.save()
        await("sheet 关") { !vm.sheetOpen.value }
        coVerify(exactly = 1) { coordinator.setHidden("c1", OurDayKey.keyOf(yesterday), true) }
        coVerify(exactly = 0) { coordinator.saveUserNote(any(), any(), any(), any()) }
    }

    @Test
    fun save_都没变_两口都不调_直接关() {
        insert(row())
        val vm = vm(); awaitRow(vm)
        vm.openSheet(); idle()
        assertFalse(vm.isDirty())
        vm.save()
        await("sheet 关") { !vm.sheetOpen.value }
        coVerify(exactly = 0) { coordinator.saveUserNote(any(), any(), any(), any()) }
        coVerify(exactly = 0) { coordinator.setHidden(any(), any(), any()) }
    }

    @Test
    fun save_note空白_不保存_sheet留着() {
        insert(row())
        val vm = vm(); awaitRow(vm)
        vm.openSheet(); idle()
        vm.updateDraft("   ")
        vm.save(); idle()
        assertTrue(vm.sheetOpen.value)
        coVerify(exactly = 0) { coordinator.saveUserNote(any(), any(), any(), any()) }
    }

    @Test
    fun rewrite_成功无toast_失败toast() {
        insert(row())
        val vm = vm(); awaitRow(vm)
        vm.rewrite()
        // R1 🟡-3：先等「真调到了」（busy 初值就是 false·等它会在动作发生前放行），再等 busy 复位。
        await("regenerate 已调") { regenerated.isNotEmpty() }
        await("busy 复位") { latest?.busy == false }
        assertTrue("成功路径不出 toast", toasts.isEmpty())
        coVerify(exactly = 1) { coordinator.regenerate("c1", OurDayKey.keyOf(yesterday)) }
        coEvery { coordinator.regenerate(any(), any()) } returns false
        vm.retry()
        await("失败 toast") { toasts == listOf(R.string.our_days_toast_rewrite_failed) }
        // 与本测试前半段同因（R1 🟡-3）：rewrite() 先 tryEmit(toast) 后在 finally 复位 busy，
        // 且 busy 还要经 combine 跨回 Main 才进 latest——等到 toast 就断言 busy 是竞态，必须再等复位。
        await("失败路径 busy 复位") { latest?.busy == false }
        assertFalse(latest!!.busy)
    }

    @Test
    fun busy守卫_进行中再点重写被忽略() {
        insert(row())
        val gate = CompletableDeferred<Boolean>()
        coEvery { coordinator.regenerate(any(), any()) } coAnswers { gate.await() }
        val vm = vm(); awaitRow(vm)
        vm.rewrite(); idle()
        await("busy 置位") { latest?.busy == true }
        vm.rewrite(); idle()
        coVerify(exactly = 1) { coordinator.regenerate(any(), any()) }
        gate.complete(true)
        await("busy 复位") { latest?.busy == false }
    }

    @Test
    fun delete_调markDeleted_并关sheet() {
        insert(row())
        val vm = vm(); awaitRow(vm)
        vm.openSheet(); idle()
        vm.delete()
        await("sheet 关") { !vm.sheetOpen.value }
        coVerify(exactly = 1) { coordinator.markDeleted("c1", OurDayKey.keyOf(yesterday)) }
    }

    @Test
    fun 页脚_hidden为HIDDEN_墓碑与今天为NONE_正常REMEMBERS() {
        insert(row(hidden = true))
        vm(); awaitRow(vm())
        assertEquals(FooterKind.HIDDEN, latest!!.footer)
        latest = null
        insert(row(date = yesterday.minusDays(1), note = "", status = "none", deleted = true))
        vm(date = yesterday.minusDays(1)); await("墓碑行") { latest?.row?.deleted == true }
        assertEquals(FooterKind.NONE, latest!!.footer); assertEquals(CardStatus.DELETED, latest!!.card!!.status)
        latest = null
        insert(row(date = yesterday.minusDays(2)))
        vm(date = yesterday.minusDays(2)); await("正常行") { latest?.row != null }
        assertEquals(FooterKind.REMEMBERS, latest!!.footer)
        latest = null
        vm(date = today); loaded()
        assertEquals(FooterKind.NONE, latest!!.footer)
    }

    @Test
    fun E23_草稿从SavedStateHandle恢复() {
        insert(row())
        val vm = vm(extra = mapOf("ourdays_sheet_open" to true, "ourdays_note_draft" to "恢复的草稿", "ourdays_hidden_draft" to true))
        awaitRow(vm)
        assertTrue(vm.sheetOpen.value); assertEquals("恢复的草稿", vm.draft.value); assertTrue(vm.draftHidden.value); assertTrue(vm.isDirty())
    }

    @Test
    fun 第N天与事实层与全部模式分段() {
        insert(row(date = yesterday.minusDays(9), note = "第一天"), row(facts = OurDayFactsJson.encode(OurDayFacts(messageCount = 5, scheduleLine = "清晨 起床"))))
        vm(); await("事实层") { latest?.facts?.isNotEmpty() == true }
        assertEquals(10, latest!!.nthDay)
        assertEquals(listOf(FactKind.CHAT, FactKind.SCHEDULE), latest!!.facts.map { it.kind })
        assertEquals("林晚", latest!!.characterName)
        latest = null
        insert(row(char = "c2", note = "阿棠的手记"))
        vm(char = OurDaysRoutes.ALL); await("分段") { latest?.card?.segments?.size == 2 }
        assertTrue(latest!!.isAll); assertNull(latest!!.row)
        assertEquals(listOf("c1", "c2"), latest!!.card!!.segments.map { it.characterUuid })
        assertEquals(FooterKind.NONE, latest!!.footer)
        coVerify(exactly = 0) { coordinator.refreshFacts(OurDaysRoutes.ALL, any()) }
    }
}
