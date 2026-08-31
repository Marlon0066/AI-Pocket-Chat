package com.situ.aichat.prompt.memory

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 向量回填的失败归因与哨兵洗白（图纸 2026-09-01「记忆与防污染加固批」件④·T2-5/T3-1）。
 *
 * 断言从规格独立反推：**瞬态**失败（嵌入器未加载/推理异常）绝不写哨兵——写了就等于把「这次没嵌上」
 * 永久钉成「永远不该嵌」，那条消息的语义检索从此哑巴，且没有任何自愈路径会回访它（缺失谓词看的是
 * `embedding IS NULL`，哨兵是 NOT NULL）。只有**永久**不可嵌（内容过短 / 无实义 token）才准写哨兵。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VectorMemoryBackfillTest {

    private lateinit var db: AppDatabase
    private lateinit var embedder: TextEmbedder
    private lateinit var service: VectorMemoryService

    private val convUuid = "conv-1"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        embedder = mockk(relaxed = true)
        every { embedder.isAvailable } returns true
        service = VectorMemoryService(db.messageDao(), db.conversationDao(), embedder, mockk(relaxed = true))
        runBlocking {
            db.characterDao().upsert(CharacterEntity(uuid = "char-1", name = "角色", creationDate = 0L))
            db.conversationDao().upsert(
                ConversationEntity(uuid = convUuid, title = "会话", characterUuid = "char-1", creationDate = 0L),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun seed(vararg contents: String) = runBlocking {
        contents.forEachIndexed { i, c ->
            db.messageDao().upsert(
                MessageEntity(
                    messageUUID = "m$i",
                    conversationUuid = convUuid,
                    roleRaw = if (i % 2 == 0) "user" else "assistant",
                    content = c,
                    timestamp = 1_000L + i,
                ),
            )
        }
    }

    private fun embeddingOf(uuid: String): ByteArray? = runBlocking {
        db.messageDao().getByUuid(uuid)!!.embedding
    }

    private fun okVector() = TextEmbedder.EmbedOutcome.Ok(FloatArray(512) { 0.01f })

    /** E17：推理异常/未加载属瞬态——留 NULL 待下轮，绝不写哨兵。 */
    @Test
    fun transientFailure_leavesNull_andNextRoundHeals() = runBlocking {
        seed("这是一条够长的正常消息内容")
        every { embedder.embedDetailed(any()) } returns TextEmbedder.EmbedOutcome.Failed
        service.backfillMissingEmbeddings()
        assertNull("瞬态失败必须留 NULL（哨兵会让它永远检索不到）", embeddingOf("m0"))
        assertTrue("该行必须仍在待回填集内", db.messageDao().hasMissingEmbedding())

        // 下一轮嵌入器恢复 → 自愈拿到真向量
        every { embedder.embedDetailed(any()) } returns okVector()
        service.backfillMissingEmbeddings()
        assertEquals(512 * 4, embeddingOf("m0")?.size)
    }

    /** E18：整批全瞬态失败时批内零写入 → break，绝不在同一批上死循环。 */
    @Test
    fun allTransientInBatch_terminatesInsteadOfSpinning() = runBlocking {
        seed(*Array(5) { "第 $it 条够长的正常消息内容" })
        every { embedder.embedDetailed(any()) } returns TextEmbedder.EmbedOutcome.Failed
        val processed = service.backfillMissingEmbeddings() // 死循环则本用例根本不会返回
        assertEquals("零写入", 0, processed)
        assertTrue(db.messageDao().hasMissingEmbedding())
    }

    /** 永久不可嵌两条路径仍照旧写哨兵（否则它们每轮被重取，回填永不收敛）。 */
    @Test
    fun permanentlyUnembeddable_writesSentinel() = runBlocking {
        seed("短", "这是一条够长的正常消息内容") // "短" < MIN_CONTENT_LENGTH=8
        every { embedder.embedDetailed(any()) } returns TextEmbedder.EmbedOutcome.NoContent
        service.backfillMissingEmbeddings()
        assertEquals("过短行写哨兵", 0, embeddingOf("m0")?.size)
        assertEquals("无实义 token 行写哨兵", 0, embeddingOf("m1")?.size)
        assertTrue("哨兵是 NOT NULL，不再进待回填集", !db.messageDao().hasMissingEmbedding())
    }

    /** E19：一次性洗白——被冤枉的哨兵回到 NULL 复评拿到真向量；真不合格行复评后重新落哨兵。 */
    @Test
    fun sentinelWash_isOneShot_andReevaluates() = runBlocking {
        seed("这是一条够长的被冤枉的消息内容", "短")
        val ctx = RuntimeEnvironment.getApplication()
        // 模拟历史：两行都被旧实现写成哨兵
        db.messageDao().updateEmbedding("m0", ByteArray(0))
        db.messageDao().updateEmbedding("m1", ByteArray(0))

        val washed = service.washWronglySentineledOnce(ctx)
        assertEquals("两条哨兵都回到待回填集", 2, washed)
        assertNull(embeddingOf("m0"))
        assertNull(embeddingOf("m1"))

        assertEquals("旗标落盘后第二次调用必须 0 行（绝不每轮重洗）", 0, service.washWronglySentineledOnce(ctx))

        every { embedder.embedDetailed(any()) } returns okVector()
        service.backfillMissingEmbeddings()
        assertEquals("被冤枉的行复评后拿到真向量", 512 * 4, embeddingOf("m0")?.size)
        assertEquals("真不合格的行复评后重新落哨兵", 0, embeddingOf("m1")?.size)
    }

    /** T3-1（DAO 级）：洗白只碰空 blob，真向量分毫不动。 */
    @Test
    fun resetSentinelEmbeddings_touchesOnlyEmptyBlobs() = runBlocking {
        seed("这是一条够长的正常消息内容", "另一条够长的正常消息内容", "第三条够长的正常消息内容")
        val real = ByteArray(2048) { (it % 127).toByte() }
        db.messageDao().updateEmbedding("m0", ByteArray(0))
        db.messageDao().updateEmbedding("m1", real)
        // m2 保持 NULL

        val reset = db.messageDao().resetSentinelEmbeddings()
        assertEquals("只该重置那一条哨兵", 1, reset)
        assertNull(embeddingOf("m0"))
        assertNotNull(embeddingOf("m1"))
        assertTrue("真向量必须逐字节不变", real.contentEquals(embeddingOf("m1")!!))
        assertNull(embeddingOf("m2"))
    }
}
