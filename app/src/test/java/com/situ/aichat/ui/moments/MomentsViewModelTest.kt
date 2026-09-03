package com.situ.aichat.ui.moments

import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.moments.MomentGenerationService
import com.situ.aichat.moments.MomentInteractionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T2-1（图纸 2026-09-03-朋友圈信息流窗口分页 §7·Robolectric + 真 in-memory Room·范式见 `DayMomentsViewModelTest`）：
 * 信息流滑动窗口三件套的行为——初始截断 30（E3）/ 扩窗 +30（E3）/ `hasMore` 的 `size >= limit` 口径（E1/E2/E4/E7）/
 * 缩窗回 30 与幂等（E5/E6）。**断言一律用图纸规格里的字面量 30 / 45 / 90，不读实现常量。**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MomentsViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var momentRepo: MomentRepository
    private val characterRepo = mockk<CharacterRepository>()
    private val generationService = mockk<MomentGenerationService>(relaxed = true)
    private val interactionService = mockk<MomentInteractionService>(relaxed = true)

    private val jobs = mutableListOf<Job>()
    private val vms = mutableListOf<MomentsViewModel>()
    private val feedSizes = mutableListOf<Int>()
    private var feedUuids: List<String> = emptyList()
    private var hasMore: Boolean? = null

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        momentRepo = spyk(MomentRepository(db.momentDao()))
        every { characterRepo.observeAll() } returns flowOf(emptyList<CharacterEntity>())
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

    /** 静置若干轮，给「本不该发生的变化」充分的发生机会——全否定断言前的必要等待。 */
    private fun settle() = repeat(60) { idle(); Thread.sleep(2) }

    /** 种 [count] 条帖：uuid `p-00`…，时间戳逐条 +1000ms ⇒ 序号越大越新。 */
    private fun seedPosts(count: Int) = runBlocking {
        repeat(count) { i ->
            db.momentDao().insertPost(
                MomentPostEntity(uuid = "p-%02d".format(i), timestamp = 1_700_000_000_000L + i * 1_000L)
            )
        }
    }

    private fun vm(): MomentsViewModel {
        val vm = MomentsViewModel(momentRepo, characterRepo, db.userProfileDao(), generationService, interactionService)
        vms += vm
        jobs += CoroutineScope(Dispatchers.Main).launch {
            vm.feed.collect { posts -> feedSizes += posts.size; feedUuids = posts.map { it.post.uuid } }
        }
        jobs += CoroutineScope(Dispatchers.Main).launch { vm.hasMoreOlderPosts.collect { hasMore = it } }
        return vm
    }

    @Test
    fun `种45条 首屏恰30条且是最新的30条`() {
        seedPosts(45)
        vm()
        await("首屏窗口") { feedSizes.lastOrNull() == 30 }
        assertEquals("首条应是时间戳最大的那条", "p-44", feedUuids.first())
        assertEquals("末条应是第 30 新的那条", "p-15", feedUuids.last())
        assertTrue("窗口装满 ⇒ 可能还有更早的", hasMore == true)
    }

    @Test
    fun `扩窗一次 45条全出`() {
        seedPosts(45)
        val vm = vm()
        await("首屏窗口") { feedSizes.lastOrNull() == 30 }
        vm.loadOlderPosts()
        await("扩窗到 45") { feedSizes.lastOrNull() == 45 }
        assertEquals("p-44", feedUuids.first())
        assertEquals("p-00", feedUuids.last())
    }

    @Test
    fun `再扩一次 仍45条且没有更早的了`() {
        seedPosts(45)
        val vm = vm()
        await("首屏窗口") { feedSizes.lastOrNull() == 30 }
        vm.loadOlderPosts()
        await("扩窗到 45") { feedSizes.lastOrNull() == 45 }
        await("库存 < 窗口 ⇒ 没有更早的了") { hasMore == false }
        vm.loadOlderPosts()
        settle()
        assertEquals("库里只有 45 条，不会凭空长出来", 45, feedSizes.last())
        assertFalse("仍是没有更早的", hasMore!!)
        // 正向证据：第二次扩窗确实换了窗口、真去重查了一次（否则「不变」可能只是没执行）。
        verify(exactly = 1) { momentRepo.observeFeed(90) }
    }

    @Test
    fun `种20条 全出且没有更早的`() {
        seedPosts(20)
        vm()
        await("全部 20 条出齐") { feedSizes.lastOrNull() == 20 }
        await("库存 < 窗口 ⇒ 没有更早的") { hasMore == false }
    }

    @Test
    fun `种30条 恰好等于窗口 有更早的 扩一次后转false`() {
        seedPosts(30)
        val vm = vm()
        await("首屏窗口") { feedSizes.lastOrNull() == 30 }
        assertTrue("size >= limit 口径 ⇒ 恰好装满也算「可能还有」（K3）", hasMore == true)
        vm.loadOlderPosts()
        await("空续一次后转 false") { hasMore == false }
        assertEquals("库里就 30 条，续不出新的", 30, feedSizes.last())
    }

    @Test
    fun `扩到90后缩窗 回到30条`() {
        seedPosts(95)
        val vm = vm()
        await("首屏窗口") { feedSizes.lastOrNull() == 30 }
        vm.loadOlderPosts()
        await("扩到 60") { feedSizes.lastOrNull() == 60 }
        vm.loadOlderPosts()
        await("扩到 90") { feedSizes.lastOrNull() == 90 }
        vm.shrinkWindow()
        await("缩回 30") { feedSizes.lastOrNull() == 30 }
        assertEquals("缩窗后仍是最新那头", "p-94", feedUuids.first())
    }

    @Test
    fun `窗口在初始值时缩窗 列表不变`() {
        seedPosts(45)
        val vm = vm()
        await("首屏窗口") { feedSizes.lastOrNull() == 30 }
        vm.shrinkWindow()
        settle()
        verify(exactly = 1) { momentRepo.observeFeed(30) }
        vm.loadOlderPosts()
        await("扩窗到 45") { feedSizes.lastOrNull() == 45 }
        // 用「随后一次已知会发射的写入」把「没发射」变成确定性断言：30 与 45 之间没有任何中间态。
        assertEquals(listOf(30, 45), feedSizes.dropWhile { it == 0 })
    }

    @Test
    fun `空库 feed空且没有更早的`() {
        vm()
        settle()
        assertTrue("空库应给空列表", feedUuids.isEmpty())
        assertEquals(false, hasMore)
    }
}
