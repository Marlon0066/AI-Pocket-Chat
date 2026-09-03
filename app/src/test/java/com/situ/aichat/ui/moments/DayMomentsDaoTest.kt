package com.situ.aichat.ui.moments

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.MomentDao
import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentLikeEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T2-1（图纸 2026-09-03 §7·Robolectric + 真 in-memory Room）：`observeDayMomentsWithRelations` 的
 * 四路互动口径 / 软删过滤 / 去重 / 排序 / 窗口边界。每例只种**一条**互动以隔离路数——任一路的 WHERE
 * 被改动，必有一例挂掉（§6 口径耦合的锁）。
 *
 * 断言从 §3.1 规格独立反推：窗口半开 `[start, end)`、外层 `isSoftDeleted = 0`、`UNION` 去重、
 * `ORDER BY timestamp DESC`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayMomentsDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MomentDao

    /** 当天窗口 = `[START, END)`；DAY_MID 在窗内，BEFORE / AFTER 在窗外。 */
    private val start = 1_756_656_000_000L // 任取一天零点（毫秒）
    private val end = start + 86_400_000L
    private val inDay = start + 10 * 3_600_000L
    private val before = start - 3_600_000L
    private val after = end + 3_600_000L

    private val me = "c-me"
    private val other = "c-other"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.momentDao()
    }

    @After
    fun tearDown() = db.close()

    private fun post(
        uuid: String,
        timestamp: Long,
        author: String = "character",
        characterUuid: String? = me,
        softDeleted: Boolean = false,
    ) = MomentPostEntity(
        uuid = uuid,
        timestamp = timestamp,
        authorTypeRaw = author,
        characterUuid = characterUuid,
        isSoftDeleted = softDeleted,
    )

    private fun seedPost(p: MomentPostEntity) = runBlocking { dao.insertPost(p) }

    private fun seedComment(postUuid: String, timestamp: Long, author: String, characterUuid: String?) = runBlocking {
        dao.insertComment(
            MomentCommentEntity(
                uuid = "cm-$postUuid-$timestamp-$author",
                timestamp = timestamp,
                authorTypeRaw = author,
                characterUuid = characterUuid,
                postUuid = postUuid,
            )
        )
    }

    private fun seedLike(postUuid: String, timestamp: Long, author: String, characterUuid: String?) = runBlocking {
        dao.insertLike(
            MomentLikeEntity(timestamp = timestamp, authorTypeRaw = author, characterUuid = characterUuid, postUuid = postUuid)
        )
    }

    /** 当天窗口内、本角色相关的帖 uuid（查询结果顺序原样返回，供排序断言用）。 */
    private fun query(): List<String> = runBlocking {
        dao.observeDayMomentsWithRelations(me, start, end).first().map { it.post.uuid }
    }

    @Test
    fun `1 角色在窗口内发帖 入列`() {
        seedPost(post("p1", inDay))
        assertEquals(listOf("p1"), query())
    }

    @Test
    fun `2 角色发帖在窗口外 不入列`() {
        seedPost(post("p-before", before))
        seedPost(post("p-after", after))
        assertEquals(emptyList<String>(), query())
    }

    @Test
    fun `2b 角色发帖恰在次日零点 不入列（上界开）`() {
        // 复核 R1 🟡-1 补：例 2 的 after 是 end + 1h，例 10 的 p-late 是 end - 1——两者都活不下来
        // 「`< :endMillis` 被改成 `<=`」这一处变异。恰 end 这一刻是唯一能钉死上界开区间的输入。
        seedPost(post("p-next-day-midnight", end))
        assertEquals(emptyList<String>(), query())
    }

    @Test
    fun `3 角色在窗口内评论更早的别人的帖 该帖入列`() {
        seedPost(post("p-other", before, characterUuid = other))
        seedComment("p-other", inDay, author = "character", characterUuid = me)
        assertEquals(listOf("p-other"), query())
    }

    @Test
    fun `4 角色在窗口内点赞更早的别人的帖 该帖入列`() {
        seedPost(post("p-other", before, characterUuid = other))
        seedLike("p-other", inDay, author = "character", characterUuid = me)
        assertEquals(listOf("p-other"), query())
    }

    @Test
    fun `5 用户在窗口内评论该角色的帖 入列`() {
        seedPost(post("p-mine", before))
        seedComment("p-mine", inDay, author = "user", characterUuid = null)
        assertEquals(listOf("p-mine"), query())
    }

    @Test
    fun `6 用户在窗口内点赞该角色的帖 入列`() {
        seedPost(post("p-mine", before))
        seedLike("p-mine", inDay, author = "user", characterUuid = null)
        assertEquals(listOf("p-mine"), query())
    }

    @Test
    fun `7 别的角色的帖且本角色无互动 不入列`() {
        seedPost(post("p-other", inDay, characterUuid = other))
        seedComment("p-other", inDay, author = "character", characterUuid = other)
        seedLike("p-other", inDay, author = "user", characterUuid = null)
        assertEquals(emptyList<String>(), query())
    }

    @Test
    fun `8 软删帖即使窗口内有互动 不入列`() {
        seedPost(post("p-del", before, softDeleted = true))
        seedComment("p-del", inDay, author = "user", characterUuid = null)
        assertEquals(emptyList<String>(), query())
    }

    @Test
    fun `9 同一帖既被评论又被点赞 只出现一次`() {
        seedPost(post("p-other", before, characterUuid = other))
        seedComment("p-other", inDay, author = "character", characterUuid = me)
        seedLike("p-other", inDay, author = "character", characterUuid = me)
        assertEquals(listOf("p-other"), query())
    }

    @Test
    fun `10 多条结果按 timestamp 倒序`() {
        seedPost(post("p-old", before, characterUuid = other))
        seedComment("p-old", inDay, author = "character", characterUuid = me)
        seedPost(post("p-early", start))
        seedPost(post("p-late", end - 1))
        assertEquals(listOf("p-late", "p-early", "p-old"), query())
    }
}
