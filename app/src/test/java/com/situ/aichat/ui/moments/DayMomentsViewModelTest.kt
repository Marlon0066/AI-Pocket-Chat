package com.situ.aichat.ui.moments

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentLikeEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.ourdays.OurDayKey
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
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
 * T2-2（图纸 2026-09-03 §7·Robolectric + 真 in-memory Room + MockK 假掉 `CharacterRepository`）：
 * VM 状态机——`loaded` 门（E2）、两组分法（E5 / E6）、非法日键（E3）与空角色 uuid（E4）走空态不崩，
 * `toggleLike` 两分支各走对应写口（E12）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayMomentsViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var momentRepo: MomentRepository
    private val characterRepo = mockk<CharacterRepository>()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val day: LocalDate = LocalDate.of(2026, 9, 1)
    private val dayKey: String = OurDayKey.keyOf(day)
    private val bounds = OurDayKey.dayBounds(dayKey, zone)
    private val start = bounds.first
    private val end = bounds.last + 1

    private val jobs = mutableListOf<Job>()
    private val vms = mutableListOf<DayMomentsViewModel>()
    private var latest: DayMomentsUiState? = null

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        momentRepo = spyk(MomentRepository(db.momentDao()))
        every { characterRepo.observeAll() } returns flowOf(listOf(CharacterEntity(uuid = "c1", name = "林晚", creationDate = 1)))
    }

    /** 先撤 VM 的 `viewModelScope`、空转到收尾，再关库——否则孤儿协程摸到已关的库会泄漏（PITFALLS §1e）。 */
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

    private fun seedPost(uuid: String, timestamp: Long, characterUuid: String? = "c1", softDeleted: Boolean = false) = runBlocking {
        db.momentDao().insertPost(
            MomentPostEntity(
                uuid = uuid,
                timestamp = timestamp,
                authorTypeRaw = "character",
                characterUuid = characterUuid,
                isSoftDeleted = softDeleted,
            )
        )
    }

    private fun seedUserLike(postUuid: String, timestamp: Long) = runBlocking {
        db.momentDao().insertLike(MomentLikeEntity(timestamp = timestamp, authorTypeRaw = "user", characterUuid = null, postUuid = postUuid))
    }

    private fun vm(char: String = "c1", key: String = dayKey): DayMomentsViewModel {
        jobs.forEach { it.cancel() }
        jobs.clear()
        latest = null
        val handle = SavedStateHandle(
            mapOf(DayMomentsViewModel.ARG_CHARACTER_UUID to char, DayMomentsViewModel.ARG_DAY_KEY to key)
        )
        val vm = DayMomentsViewModel(handle, momentRepo, characterRepo, db.userProfileDao())
        vms += vm
        return vm
    }

    private fun collect(vm: DayMomentsViewModel) {
        jobs += CoroutineScope(Dispatchers.Main).launch { vm.uiState.collect { latest = it } }
    }

    @Test
    fun `首帧未加载 收到 DB 后转已加载`() {
        seedPost("p-in", start + 3_600_000L)
        val vm = vm()
        assertFalse("订阅前应还没加载", vm.uiState.value.loaded)
        collect(vm)
        await("首帧从 DB 返回") { latest?.loaded == true }
        assertEquals(day, latest?.date)
    }

    @Test
    fun `两组分法 当天发的进组一 更早发的有来往进组二`() {
        seedPost("p-that-day", start + 3_600_000L)
        seedPost("p-earlier", start - 86_400_000L)
        seedUserLike("p-earlier", start + 7_200_000L)
        val vm = vm()
        collect(vm)
        await("首帧") { latest?.loaded == true }
        assertEquals(listOf("p-that-day"), latest?.postedThatDay?.map { it.post.uuid })
        assertEquals(listOf("p-earlier"), latest?.earlier?.map { it.post.uuid })
    }

    @Test
    fun `只有互动没发帖 组一为空 组二有内容`() {
        seedPost("p-earlier", start - 86_400_000L)
        seedUserLike("p-earlier", start + 7_200_000L)
        val vm = vm()
        collect(vm)
        await("首帧") { latest?.loaded == true }
        assertTrue(latest?.postedThatDay?.isEmpty() == true)
        assertEquals(listOf("p-earlier"), latest?.earlier?.map { it.post.uuid })
    }

    @Test
    fun `只发帖没互动 组二为空`() {
        seedPost("p-that-day", start + 3_600_000L)
        seedPost("p-untouched-earlier", start - 86_400_000L)
        val vm = vm()
        collect(vm)
        await("首帧") { latest?.loaded == true }
        assertEquals(listOf("p-that-day"), latest?.postedThatDay?.map { it.post.uuid })
        assertTrue(latest?.earlier?.isEmpty() == true)
    }

    @Test
    fun `非法日键 已加载且两组空且日期为空 不抛异常`() {
        seedPost("p-in", start + 3_600_000L)
        val vm = vm(key = "2026-9-1")
        val state = vm.uiState.value
        assertTrue("非法日键应直接给已加载空态", state.loaded)
        assertNull(state.date)
        assertTrue(state.postedThatDay.isEmpty())
        assertTrue(state.earlier.isEmpty())
    }

    @Test
    fun `空角色 uuid 已加载且两组空 不建订阅`() {
        seedPost("p-in", start + 3_600_000L)
        val vm = vm(char = "")
        val state = vm.uiState.value
        assertTrue(state.loaded)
        assertEquals(day, state.date)
        assertTrue(state.postedThatDay.isEmpty())
        assertTrue(state.earlier.isEmpty())
    }

    @Test
    fun `toggleLike 两分支各走对应写口`() {
        seedPost("p-in", start + 3_600_000L)
        val vm = vm()
        vm.toggleLike("p-in", hasUserLike = false)
        await("加赞落库") { runBlocking { db.momentDao().likesForPost("p-in").isNotEmpty() } }
        coVerify(exactly = 1) { momentRepo.addLike("p-in", MomentAuthorType.USER, null, any()) }

        vm.toggleLike("p-in", hasUserLike = true)
        await("取消赞落库") { runBlocking { db.momentDao().likesForPost("p-in").isEmpty() } }
        coVerify(exactly = 1) { momentRepo.removeUserLike("p-in") }
    }
}
