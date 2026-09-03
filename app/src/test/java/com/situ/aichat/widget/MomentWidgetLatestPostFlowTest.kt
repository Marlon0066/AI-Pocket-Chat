package com.situ.aichat.widget

import android.os.Looper
import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentLikeEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.repository.MomentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T2-3（图纸 2026-09-03-朋友圈信息流窗口分页 §7·卷 B·Robolectric + 真 in-memory Room）：
 * 小组件同步桥的新窄源 `observeLatestCharacterPost` 的**口径**（E15/E17/E18）与**发射时机**（E16）。
 *
 * 最要紧的是最后一例：点赞 / 评论落库**不得**惊动这条流——这正是卷 B 的存在理由（旧实现订
 * `observeFeedWithRelations(200)` 会观察 `moment_post` / `moment_comment` / `moment_like` 三张表，
 * 任一互动写入即整包重查重组）。断言从 §3.4 规格独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MomentWidgetLatestPostFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MomentRepository
    private val jobs = mutableListOf<Job>()
    private val emissions = mutableListOf<String?>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = MomentRepository(db.momentDao())
    }

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
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
        error("等待超时：$message（实际发射：$emissions）")
    }

    /** 静置若干轮，给「本不该发生的发射」充分的发生机会。 */
    private fun settle() = repeat(60) { idle(); Thread.sleep(2) }

    private fun seedPost(uuid: String, timestamp: Long, character: Boolean = true, softDeleted: Boolean = false) = runBlocking {
        db.momentDao().insertPost(
            MomentPostEntity(
                uuid = uuid,
                content = "c-$uuid",
                timestamp = timestamp,
                authorTypeRaw = if (character) "character" else "user",
                characterUuid = if (character) "char-1" else null,
                isSoftDeleted = softDeleted,
            )
        )
    }

    private fun collect() {
        jobs += CoroutineScope(Dispatchers.Main).launch {
            repo.observeLatestCharacterPost().collect { emissions += it?.uuid }
        }
    }

    @Test
    fun `只有用户帖 首帧为 null`() {
        seedPost("u-1", 1_000L, character = false)
        seedPost("u-2", 2_000L, character = false)
        collect()
        await("首帧") { emissions.isNotEmpty() }
        assertEquals(listOf<String?>(null), emissions)
    }

    @Test
    fun `用户帖再新也不顶替 取最新的角色帖`() {
        seedPost("a", 1_000L)
        seedPost("b", 2_000L)
        seedPost("u-newest", 9_000L, character = false)
        collect()
        await("首帧") { emissions.isNotEmpty() }
        assertEquals("b", emissions.last())
    }

    @Test
    fun `插入更新的角色帖 第二次发射是它`() {
        seedPost("b", 2_000L)
        collect()
        await("首帧") { emissions.lastOrNull() == "b" }
        seedPost("c", 3_000L)
        await("新帖到达") { emissions.size >= 2 }
        assertEquals("c", emissions.last())
    }

    @Test
    fun `软删当前最新角色帖 发射次新`() {
        seedPost("a", 1_000L)
        seedPost("b", 2_000L)
        collect()
        await("首帧") { emissions.lastOrNull() == "b" }
        runBlocking { db.momentDao().softDeletePost("b") }
        await("软删后回落") { emissions.size >= 2 }
        assertEquals("a", emissions.last())
    }

    @Test
    fun `点赞与评论落库不惊动这条流 只有新角色帖才发射`() {
        seedPost("b", 2_000L)
        collect()
        await("首帧") { emissions.lastOrNull() == "b" }
        runBlocking {
            db.momentDao().insertLike(MomentLikeEntity(timestamp = 2_500L, authorTypeRaw = "user", postUuid = "b"))
        }
        settle()
        runBlocking {
            db.momentDao().insertComment(
                MomentCommentEntity(uuid = "cm-1", content = "赞", timestamp = 2_600L, authorTypeRaw = "user", postUuid = "b")
            )
        }
        settle()
        // 用「随后一次已知会发射的写入」把「没发射」变成确定性断言，不靠 sleep 猜。
        seedPost("d", 3_000L)
        await("新角色帖 D 到达") { emissions.lastOrNull() == "d" }
        assertEquals("点赞与评论不得产生任何中间发射", listOf("b", "d"), emissions)
    }
}
