package com.situ.aichat.ui.moments

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentLikeEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
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
 * T2-1（图纸 2026-09-03-作者动态页窗口分页 §7·Robolectric + 真 in-memory Room·范式见 `MomentsViewModelTest`）：
 * 「TA 的动态 / 我的动态」页滑动窗口三件套（E1–E6）+ **头部计数走 COUNT 查询、不随窗口变**（E7–E9·K1 回归锁）
 * + 两模式不串线（E10/E11）。**断言一律用图纸规格里的字面量 30 / 45 / 90，不读实现常量。**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MomentAuthorViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var momentRepo: MomentRepository
    private val characterRepo = mockk<CharacterRepository>()

    private val jobs = mutableListOf<Job>()
    private val vms = mutableListOf<MomentAuthorViewModel>()
    private val postSizes = mutableListOf<Int>()
    private val counts = mutableListOf<Int>()
    private var postUuids: List<String> = emptyList()
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

    /** 种 [count] 条角色 `ch` 的帖：uuid `c-00`…，时间戳逐条 +1000ms ⇒ 序号越大越新。 */
    private fun seedCharacterPosts(count: Int) = runBlocking {
        repeat(count) { i ->
            db.momentDao().insertPost(
                MomentPostEntity(
                    uuid = "c-%02d".format(i),
                    timestamp = BASE_TS + i * 1_000L,
                    authorTypeRaw = "character",
                    characterUuid = "ch",
                )
            )
        }
    }

    /** 种 [count] 条用户帖：uuid `u-00`…，同样越大越新。 */
    private fun seedUserPosts(count: Int) = runBlocking {
        repeat(count) { i ->
            db.momentDao().insertPost(MomentPostEntity(uuid = "u-%02d".format(i), timestamp = BASE_TS + i * 1_000L))
        }
    }

    /** [characterUuid] 传 `""` = 用户模式（VM 的 `takeIf { it.isNotEmpty() }` 会把它折成 null）。 */
    private fun vm(characterUuid: String = "ch"): MomentAuthorViewModel {
        val handle = SavedStateHandle(mapOf(MomentAuthorViewModel.ARG_CHARACTER_UUID to characterUuid))
        val vm = MomentAuthorViewModel(handle, momentRepo, characterRepo, db.userProfileDao())
        vms += vm
        jobs += CoroutineScope(Dispatchers.Main).launch {
            vm.posts.collect { list -> postSizes += list.size; postUuids = list.map { it.post.uuid } }
        }
        jobs += CoroutineScope(Dispatchers.Main).launch { vm.hasMoreOlderPosts.collect { hasMore = it } }
        jobs += CoroutineScope(Dispatchers.Main).launch { vm.totalCount.collect { counts += it } }
        return vm
    }

    /**
     * 订阅 `loaded` 并回传「最新值」读取器。**必须真收集**：`loaded` 是 `stateIn(WhileSubscribed)`，
     * 没有收集者时上游根本不启动、`.value` 恒为初值 false（屏侧由 `collectAsStateWithLifecycle` 订阅）。
     */
    private fun collectLoaded(vm: MomentAuthorViewModel): () -> Boolean {
        val seen = mutableListOf<Boolean>()
        jobs += CoroutineScope(Dispatchers.Main).launch { vm.loaded.collect { seen += it } }
        return { seen.lastOrNull() == true }
    }

    /** 复核 R1 🟡-1 用：把「屏上会看到什么」按帧记下来——(loaded, 头部数字, 列表是否空)。 */
    private fun frames(vm: MomentAuthorViewModel): MutableList<Triple<Boolean, Int, Boolean>> {
        val out = mutableListOf<Triple<Boolean, Int, Boolean>>()
        jobs += CoroutineScope(Dispatchers.Main).launch {
            combine(vm.loaded, vm.totalCount, vm.posts) { l, c, p -> Triple(l, c, p.isEmpty()) }.collect { out += it }
        }
        return out
    }

    @Test
    fun `首帧loaded为false 收到DB后转true`() {
        seedCharacterPosts(45)
        val vm = vm()
        assertFalse("订阅前不该已加载", vm.loaded.value)
        val isLoaded = collectLoaded(vm)
        await("首屏到位") { postSizes.lastOrNull() == 30 }
        await("loaded 转 true") { isLoaded() }
    }

    @Test
    fun `空态永不与非零计数同屏（R1 🟡-1 回归锁）`() {
        seedCharacterPosts(45)
        val vm = vm()
        val f = frames(vm)
        await("列表就位") { postSizes.lastOrNull() == 30 }
        await("计数就位") { counts.lastOrNull() == 45 }
        settle()
        // 屏侧空态的渲染条件恰是 (loaded && 列表空)；此时头部数字必须是 0，否则就是
        // 「45 条动态」压着「还没有动态」那一帧（修复前实测确有此帧）。
        val bad = f.filter { it.first && it.third && it.second > 0 }
        assertTrue("不该存在「已加载 + 列表空 + 计数非零」的帧，实测：$bad", bad.isEmpty())
    }

    @Test
    fun `空作者也会loaded转true 空态不被永久压住`() {
        seedUserPosts(3) // 只有用户帖；开角色模式 ⇒ 该作者真的一条都没有
        val vm = vm()
        val isLoaded = collectLoaded(vm)
        await("loaded 转 true") { isLoaded() }
        assertTrue("角色模式下列表应为空", postUuids.isEmpty())
        assertEquals("计数应为 0", 0, counts.last())
        // loaded 已 true 且列表空 ⇒ 屏侧会正常渲染空态，没被闸门永久压住。
    }

    @Test
    fun `角色模式种45条 首屏恰30条且是最新的30条`() {
        seedCharacterPosts(45)
        vm()
        await("首屏窗口") { postSizes.lastOrNull() == 30 }
        assertEquals("首条应是时间戳最大的那条", "c-44", postUuids.first())
        assertEquals("末条应是第 30 新的那条", "c-15", postUuids.last())
        assertTrue("窗口装满 ⇒ 可能还有更早的", hasMore == true)
    }

    @Test
    fun `角色模式扩窗一次45条全出 再扩仍45且没有更早的`() {
        seedCharacterPosts(45)
        val vm = vm()
        await("首屏窗口") { postSizes.lastOrNull() == 30 }
        vm.loadOlderPosts()
        await("扩窗到 45") { postSizes.lastOrNull() == 45 }
        assertEquals("c-44", postUuids.first())
        assertEquals("c-00", postUuids.last())
        await("库存 < 窗口 ⇒ 没有更早的了") { hasMore == false }
        vm.loadOlderPosts()
        settle()
        assertEquals("库里只有 45 条，不会凭空长出来", 45, postSizes.last())
        assertEquals("仍是没有更早的", false, hasMore)
        // 正向证据：第二次扩窗确实换了窗口、真去重查了一次（否则「不变」可能只是没执行）。
        verify(exactly = 1) { momentRepo.observeCharacterFeed("ch", 90) }
    }

    @Test
    fun `角色模式种20条 全出且没有更早的`() {
        seedCharacterPosts(20)
        vm()
        await("全部 20 条出齐") { postSizes.lastOrNull() == 20 }
        await("库存 < 窗口 ⇒ 没有更早的") { hasMore == false }
    }

    @Test
    fun `角色模式种30条 恰好等于窗口 空续一次后转false`() {
        seedCharacterPosts(30)
        val vm = vm()
        await("首屏窗口") { postSizes.lastOrNull() == 30 }
        assertTrue("size >= limit 口径 ⇒ 恰好装满也算「可能还有」", hasMore == true)
        vm.loadOlderPosts()
        await("空续一次后转 false") { hasMore == false }
        assertEquals("库里就 30 条，续不出新的", 30, postSizes.last())
    }

    @Test
    fun `扩到90后缩窗 回到30条且仍是最新那头`() {
        seedCharacterPosts(95)
        val vm = vm()
        await("首屏窗口") { postSizes.lastOrNull() == 30 }
        vm.loadOlderPosts()
        await("扩到 60") { postSizes.lastOrNull() == 60 }
        vm.loadOlderPosts()
        await("扩到 90") { postSizes.lastOrNull() == 90 }
        vm.shrinkWindow()
        await("缩回 30") { postSizes.lastOrNull() == 30 }
        assertEquals("缩窗后仍是最新那头", "c-94", postUuids.first())
    }

    @Test
    fun `窗口在初始值时缩窗 幂等不重新订阅`() {
        seedCharacterPosts(45)
        val vm = vm()
        await("首屏窗口") { postSizes.lastOrNull() == 30 }
        vm.shrinkWindow()
        settle()
        verify(exactly = 1) { momentRepo.observeCharacterFeed("ch", 30) }
        vm.loadOlderPosts()
        await("扩窗到 45") { postSizes.lastOrNull() == 45 }
        // 用「随后一次已知会发射的写入」把「没发射」变成确定性断言：30 与 45 之间没有任何中间态。
        assertEquals(listOf(30, 45), postSizes.dropWhile { it == 0 })
    }

    @Test
    fun `头部计数走COUNT查询 三种窗口状态下恒等于库存45`() {
        seedCharacterPosts(45)
        val vm = vm()
        await("首屏窗口 30") { postSizes.lastOrNull() == 30 }
        await("计数就位") { counts.lastOrNull() == 45 }
        assertEquals("窗口 30 时头部仍是真实总数（不是 30）", 45, counts.last())
        vm.loadOlderPosts()
        await("窗口 60 ⇒ 列表 45") { postSizes.lastOrNull() == 45 }
        assertEquals("窗口 60 时头部仍是 45", 45, counts.last())
        vm.loadOlderPosts()
        settle()
        assertEquals("窗口 90 时头部仍是 45", 45, counts.last())
    }

    @Test
    fun `软删一条 头部计数实时变44`() {
        seedCharacterPosts(45)
        vm()
        await("计数就位") { counts.lastOrNull() == 45 }
        runBlocking { db.momentDao().softDeletePost("c-00") }
        await("软删 ⇒ 44") { counts.lastOrNull() == 44 }
    }

    @Test
    fun `点赞与评论不惊动头部计数 发射序列恰为45与46`() {
        seedCharacterPosts(45)
        vm()
        await("计数就位") { counts.lastOrNull() == 45 }
        runBlocking {
            db.momentDao().insertLike(MomentLikeEntity(postUuid = "c-00", authorTypeRaw = "character", characterUuid = "ch"))
            db.momentDao().insertComment(
                MomentCommentEntity(uuid = "cm-1", postUuid = "c-00", authorTypeRaw = "character", characterUuid = "ch")
            )
        }
        settle()
        // 用「随后一次已知会发射的写入」把「没发射」变成确定性断言（同上一卷 E16 打法）。
        runBlocking {
            db.momentDao().insertPost(
                MomentPostEntity(uuid = "c-99", timestamp = BASE_TS + 99_000L, authorTypeRaw = "character", characterUuid = "ch")
            )
        }
        await("新帖 ⇒ 计数 46") { counts.lastOrNull() == 46 }
        assertEquals("点赞/评论不该在 45 与 46 之间挤进任何发射", listOf(45, 46), counts.dropWhile { it == 0 })
    }

    @Test
    fun `用户模式 只取用户帖的窗口与计数 不串角色帖`() {
        seedUserPosts(35)
        seedCharacterPosts(5)
        val vm = vm(characterUuid = "")
        await("首屏窗口") { postSizes.lastOrNull() == 30 }
        await("计数 = 用户帖总数") { counts.lastOrNull() == 35 }
        assertEquals("首条应是最新的用户帖", "u-34", postUuids.first())
        assertTrue("窗口装满 ⇒ 可能还有更早的", hasMore == true)
        vm.loadOlderPosts()
        await("扩窗到 35") { postSizes.lastOrNull() == 35 }
        assertEquals("计数不受窗口影响", 35, counts.last())
        assertTrue("列表里不该混进角色帖", postUuids.none { it.startsWith("c-") })
    }

    @Test
    fun `该作者一条帖都没有 空列表且计数0且没有更早的`() {
        seedUserPosts(3) // 别人的帖不该被角色模式看见
        vm()
        settle()
        assertTrue("角色 ch 一条帖都没有 ⇒ 空列表", postUuids.isEmpty())
        assertEquals("头部「0 条动态」", 0, counts.last())
        assertEquals(false, hasMore)
    }

    private companion object {
        const val BASE_TS = 1_700_000_000_000L
    }
}
