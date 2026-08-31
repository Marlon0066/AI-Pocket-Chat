package com.situ.aichat.prompt.memory

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 滚动摘要收集/游标 T2（批1 1-1·Robolectric 真 Room·CHAT_CORE_HEALTH_PLAN.md）：
 * 断言从规格反推——「窗口外、游标后的每条消息终将且只会进一次摘要」，非照搬实现。
 *
 * 修复前缺陷（本测试组第一例在旧实现下必红）：`summarizableMessages` 恒取全会话**最旧** 500 条再在
 * Kotlin 侧过滤游标 → 会话超 500 条且游标越过第 500 条后，收集恒空，摘要永久停摆。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemorySummaryCollectionTest {

    private lateinit var db: AppDatabase
    private lateinit var memoryService: MemoryService

    private val charUuid = "char-1"
    private val convUuid = "conv-1"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        memoryService = MemoryService(db.messageDao(), db.conversationDao(), mockk<ContextLogService>(relaxed = true))
        runBlocking {
            db.characterDao().upsert(CharacterEntity(uuid = charUuid, name = "角色", creationDate = 0L))
            db.conversationDao().upsert(ConversationEntity(uuid = convUuid, title = "会话", characterUuid = charUuid, creationDate = 0L))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** ts 兼作序号：奇数 user / 偶数 assistant。 */
    private fun msg(ts: Long, conv: String = convUuid, offline: Boolean = false, held: Boolean = false) = MessageEntity(
        messageUUID = "m-$conv-$ts",
        conversationUuid = conv,
        roleRaw = if (ts % 2 == 1L) "user" else "assistant",
        content = "这是第 $ts 条测试消息内容",
        timestamp = ts,
        isOfflineMode = offline,
        isHeldForDelivery = held,
    )

    private fun seed(range: LongRange, conv: String = convUuid, offline: (Long) -> Boolean = { false }) = runBlocking {
        for (ts in range) db.messageDao().upsert(msg(ts, conv, offline(ts)))
    }

    private fun setCursor(cursor: Long?, conv: String = convUuid) = runBlocking {
        if (cursor != null) db.conversationDao().updateSummaryCursor(conv, cursor)
    }

    private fun collect(shortTermLength: Int = 10): List<MessageEntity> = runBlocking {
        memoryService.collectMessagesOutsideWindow(charUuid, convUuid, shortTermLength)
    }

    // ---- 停摆修复 ----

    @Test
    fun `会话超500条且游标越过最旧500条后_游标之后的消息仍可收集`() {
        seed(1L..600L)
        setCursor(550L)
        // shortTermLength=10 → 窗口起点 = 第 10 近的 user 消息 ts=581（user=奇数 599..581）
        val collected = collect(shortTermLength = 10)
        assertTrue("停摆重现：收集不得为空", collected.isNotEmpty())
        assertEquals("应恰为游标后、窗口前的 551..580 共 30 条", 30, collected.size)
        assertTrue(collected.all { it.timestamp in 551L..580L })
    }

    /**
     * 期望顺移（图纸 2026-09-01 件⑦）：满批的尾部同毫秒撮让给下一轮，故每满批轮次收 499 条而非 500——
     * 边界外是否还有同毫秒孪生条在本批里看不见，让一撮出去是「绝不丢条」的代价。并集完整性才是规格。
     */
    @Test
    fun `积压超过500条时分轮消化且游标不过冲`() = runBlocking {
        seed(1L..1200L)
        // 窗口起点 = 第 10 近 user ts=1181；游标空 → 第一轮取最旧 500 条，裁尾后 1..499
        val round1 = collect(shortTermLength = 10)
        assertEquals(499, round1.size)
        assertEquals(1L..499L, round1.minOf { it.timestamp }..round1.maxOf { it.timestamp })

        memoryService.markSummarized(round1)
        assertEquals(499L, db.conversationDao().getByUuid(convUuid)?.lastSummarizedMessageDate)

        val round2 = collect(shortTermLength = 10)
        assertEquals("第二轮应从 500 接续（游标不过冲不跳段）", 500L..998L, round2.minOf { it.timestamp }..round2.maxOf { it.timestamp })

        memoryService.markSummarized(round2)
        val round3 = collect(shortTermLength = 10)
        assertEquals("第三轮消化至窗口起点为止", 999L..1180L, round3.minOf { it.timestamp }..round3.maxOf { it.timestamp })

        // 三轮并集 = 窗口外全部消息，无重复无遗漏（裁尾只推迟、绝不丢条）
        val union = (round1 + round2 + round3).map { it.timestamp }.toSet()
        assertEquals(1180, union.size)
        assertEquals(round1.size + round2.size + round3.size, union.size)
    }

    // ---- 线下隔离 ----

    @Test
    fun `线下叙事消息不进常规摘要收集`() {
        seed(1L..60L, offline = { it in 21L..40L })
        // shortTermLength=5 → 窗口起点 = 第 5 近 user ts=51
        val collected = collect(shortTermLength = 5)
        assertTrue("线下消息必须被谓词隔离", collected.none { it.isOfflineMode })
        assertEquals("1..20 ∪ 41..50 共 30 条", 30, collected.size)
    }

    @Test
    fun `未总结轮数统计排除线下消息`() = runBlocking {
        seed(1L..60L, offline = { it in 21L..40L })
        val conv = db.conversationDao().getByUuid(convUuid)!!
        // 窗口起点 51 前的 user=25 条，其中线下 10 条 → 15
        assertEquals(15, memoryService.countUnsummarizedRoundsOutsideBaseWindow(conv, 5))
    }

    // ---- 游标推进语义 ----

    // ---- 件⑦ 批次边界：同毫秒并列裁尾（图纸 2026-09-01·T1-6/T2-6）----

    /** 纯函数真值表（T1-6）：断言从规格反推——满批才裁、只裁最大时间戳那一撮、全同毫秒不裁空。 */
    @Test
    fun `裁尾纯函数_满批裁同毫秒尾_未满与全同毫秒原样`() {
        val limit = 5
        // 满批且尾部三条同毫秒 → 裁掉那三条
        val full = listOf(msg(1L), msg(2L), msg(7L), msg(7L, conv = "x"), msg(7L, conv = "y"))
        assertEquals(listOf(1L, 2L), MemoryService.dropTailTimestampTies(full, limit).map { it.timestamp })
        // 未满批 = 已取尽，无边界问题
        val partial = listOf(msg(1L), msg(7L), msg(7L, conv = "x"))
        assertEquals(3, MemoryService.dropTailTimestampTies(partial, limit).size)
        // 满批即使尾部只有一条 → 也让给下一轮（满批意味着边界外可能还有同毫秒孪生条，看不见就不能断言没有）
        val noTie = listOf(msg(1L), msg(2L), msg(3L), msg(4L), msg(5L))
        assertEquals(listOf(1L, 2L, 3L, 4L), MemoryService.dropTailTimestampTies(noTie, limit).map { it.timestamp })
        // 全批同毫秒（病态）→ 原样返回，绝不返回空表（否则该会话永久停摆）
        val allSame = (1..5).map { msg(9L, conv = "c$it") }
        assertEquals(5, MemoryService.dropTailTimestampTies(allSame, limit).size)
    }

    /**
     * T2-6：同毫秒两条恰跨 500 批边界时不得丢消息（E20）。
     * 修复前：第 500 条与第 501 条同毫秒 → 游标推进到该毫秒 → 第 501 条被 `timestamp > cursor` 永久跳过。
     */
    @Test
    fun `同毫秒跨批边界_两轮收集并集完整不丢条`() = runBlocking {
        // 1..499 各占一毫秒；第 500/501 条共用毫秒 500；其后 502..700 用 501..699
        for (ts in 1L..499L) db.messageDao().upsert(msg(ts))
        db.messageDao().upsert(msg(500L).copy(messageUUID = "tie-a"))
        db.messageDao().upsert(msg(500L).copy(messageUUID = "tie-b", roleRaw = "assistant"))
        for (ts in 501L..699L) db.messageDao().upsert(msg(ts))

        val round1 = collect(shortTermLength = 10)
        assertEquals("满批时须裁掉尾部同毫秒的两条，留给下一轮", 499, round1.size)
        assertTrue("裁尾后批内不得再含边界毫秒", round1.none { it.timestamp == 500L })

        memoryService.markSummarized(round1)
        assertEquals("游标须停在裁尾条之前", 499L, db.conversationDao().getByUuid(convUuid)?.lastSummarizedMessageDate)

        val round2 = collect(shortTermLength = 10)
        val union = (round1 + round2).map { it.messageUUID }.toSet()
        assertTrue("tie-a 必须进过摘要", "tie-a" in union)
        assertTrue("tie-b 必须进过摘要", "tie-b" in union)
        assertEquals("两轮并集不得有重复投喂", round1.size + round2.size, union.size)
    }

    /** T3-1（DAO 级）：暂扣未投递的行不进摘要素材（E22）。 */
    @Test
    fun `暂扣未投递的消息不进摘要素材`() = runBlocking {
        for (ts in 1L..40L) db.messageDao().upsert(msg(ts, held = ts in 11L..20L))
        val fetched = db.messageDao().summarizableMessages(convUuid, null, 500)
        assertTrue("暂扣行必须被谓词排除", fetched.none { it.isHeldForDelivery })
        assertEquals(30, fetched.size)
    }

    @Test
    fun `游标按实际喂入批次推进_未贡献会话不动`() = runBlocking {
        val convB = "conv-2"
        db.conversationDao().upsert(ConversationEntity(uuid = convB, title = "会话2", characterUuid = charUuid, creationDate = 0L))
        seed(1L..10L)
        seed(101L..110L, conv = convB)

        memoryService.markSummarized((1L..6L).map { msg(it) })

        assertEquals(6L, db.conversationDao().getByUuid(convUuid)?.lastSummarizedMessageDate)
        assertNull("未喂入任何消息的会话游标必须保持原状", db.conversationDao().getByUuid(convB)?.lastSummarizedMessageDate)
    }
}
