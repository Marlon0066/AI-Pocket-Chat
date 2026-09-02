package com.situ.aichat.prompt.memory

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
 * 向量检索全量扫描 T2（批1 1-2·Robolectric 真 Room + MockK 嵌入器·CHAT_CORE_HEALTH_PLAN.md）：
 * 规格——语义检索候选必须覆盖会话【全部】已嵌入消息。修复前实现只取每会话最新 200 条，
 * 第 201 条之外的老消息永久不可召回（本测试第一例在旧实现下必红）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VectorMemoryRetrievalTest {

    private lateinit var db: AppDatabase
    private lateinit var service: VectorMemoryService
    private val embedder = mockk<TextEmbedder>()

    /** 第二路候选（记忆改造四期·E15-④）：既有消息路用例默认返回空 Retrieval → 消息路行为逐字节不变。 */
    private val archiveIndex = mockk<MeetingArchiveVectorService>()

    /** 第三路候选（「我们的日子」卷二·T2-2）：既有用例默认返回空 Retrieval → 消息 / 档案路行为逐字节不变。 */
    private val ourDayIndex = mockk<OurDayVectorService>()

    private val charUuid = "char-1"
    private val currentConv = "conv-current"
    private val historyConv = "conv-history"

    /** 与查询同向（相似度 1.0）/ 正交（相似度 0）的四维向量。 */
    private val queryVec = floatArrayOf(1f, 0f, 0f, 0f)
    private val orthogonalVec = floatArrayOf(0f, 1f, 0f, 0f)

    private val queryText = "还记得我们聊过的那件重要的事情吗"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = VectorMemoryService(db.messageDao(), db.conversationDao(), embedder, archiveIndex, ourDayIndex)
        every { embedder.embed(queryText) } returns queryVec
        // 默认第二路空（既有断言零改）；档案合并/排除专测各自覆盖 retrieval 桩。
        coEvery { archiveIndex.retrieval(any(), any(), any()) } returns
            MeetingArchiveVectorService.Retrieval(emptyList(), emptySet())
        coEvery { ourDayIndex.retrieval(any(), any(), any(), any()) } returns OurDayVectorService.Retrieval(emptyList())
        runBlocking {
            db.characterDao().upsert(CharacterEntity(uuid = charUuid, name = "角色", creationDate = 0L))
            db.conversationDao().upsert(ConversationEntity(uuid = currentConv, title = "当前", characterUuid = charUuid, creationDate = 0L))
            db.conversationDao().upsert(ConversationEntity(uuid = historyConv, title = "历史", characterUuid = charUuid, creationDate = 0L))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun embeddedMsg(ts: Long, content: String, vec: FloatArray) = MessageEntity(
        messageUUID = "m-$ts",
        conversationUuid = historyConv,
        roleRaw = if (ts % 2 == 1L) "user" else "assistant",
        content = content,
        timestamp = ts,
        embedding = service.serializeEmbedding(vec),
    )

    @Test
    fun `第201条之外的老消息可被召回`() = runBlocking {
        // 最旧一条（ts=1）与查询同向；其后 249 条全部正交 → 旧实现（最新 200 条窗口）永远看不到 ts=1。
        db.messageDao().upsert(embeddedMsg(1L, "两百条之外的目标老消息内容", queryVec))
        for (ts in 2L..250L) {
            db.messageDao().upsert(embeddedMsg(ts, "这是第 $ts 条无关的普通消息", orthogonalVec))
        }

        val result = service.searchRelevantMemories(
            query = queryText,
            characterUuid = charUuid,
            currentConversationUuid = currentConv,
            userName = "司徒",
            characterName = "夏晴子",
            shortTermLength = 20,
            thresholdPercent = 65,
        )

        assertEquals("只有目标老消息过阈值", 1, result.size)
        assertTrue("召回的必须是第 201+ 条之外的那条老消息", result.single().contains("两百条之外的目标老消息内容"))
        // 真名标注（2026-07-12 拍板）：ts=1 为奇数 → user 消息 → 标注用传入的用户名，绝非「用户」。
        assertTrue("说话人标注应为真名：${result.single()}", result.single().contains("司徒："))
    }

    @Test
    fun `阈值以下的候选不注入`() = runBlocking {
        for (ts in 1L..30L) {
            db.messageDao().upsert(embeddedMsg(ts, "这是第 $ts 条无关的普通消息", orthogonalVec))
        }
        val result = service.searchRelevantMemories(
            query = queryText,
            characterUuid = charUuid,
            currentConversationUuid = currentConv,
            userName = "司徒",
            characterName = "夏晴子",
            shortTermLength = 20,
            thresholdPercent = 65,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `TOP_K上限仍然生效`() = runBlocking {
        for (ts in 1L..300L) {
            db.messageDao().upsert(embeddedMsg(ts, "这是第 $ts 条全部同向的消息内容", queryVec))
        }
        val result = service.searchRelevantMemories(
            query = queryText,
            characterUuid = charUuid,
            currentConversationUuid = currentConv,
            userName = "司徒",
            characterName = "夏晴子",
            shortTermLength = 20,
            thresholdPercent = 65,
        )
        assertEquals(VectorMemoryService.TOP_K, result.size)
    }

    // ── 记忆改造四期·部件⑥（图纸 §3.2 / §7 T2-1/T2-2/T2-4）：单池合并 + session 排除 + 双清。既有消息路断言零改 ──

    /** 与查询夹角 cos=0.8 的向量（0.8² + 0.6² = 1·过 0.65 阈值·低于同向消息 1.0）。 */
    private val partialVec = floatArrayOf(0.8f, 0.6f, 0f, 0f)

    @Test fun `T2-1 档案候选与消息候选单池合并按相似度_formatArchiveSnippet格式`() = runBlocking {
        // 3 条 cos=0.8 消息 + 1 条 sim=0.95 档案候选（MockK 注入）→ 合并后档案凭更高相似度排最前。
        for (ts in 1L..3L) db.messageDao().upsert(embeddedMsg(ts, "普通消息内容$ts", partialVec))
        coEvery { archiveIndex.retrieval(any(), any(), any()) } returns MeetingArchiveVectorService.Retrieval(
            candidates = listOf(MeetingArchiveVectorService.ArchiveCandidate("那次见面的档案回忆", 1_700_000_000_000L, 0.95)),
            excludedSessionIds = emptySet(),
        )

        val result = service.searchRelevantMemories(queryText, charUuid, currentConv, "司徒", "夏晴子", 20, 65)

        assertEquals("合并池 = 3 消息 + 1 档案", 4, result.size)
        assertTrue("档案片段走 formatArchiveSnippet 格式", result.first().contains(" · 见面档案] 那次见面的档案回忆"))
        assertTrue("档案（0.95）排在消息（0.8）之前", result.first().contains("· 见面档案]"))
        assertTrue("消息候选仍在池内", result.any { it.contains("普通消息内容") })
    }

    @Test fun `T2-2 排除集命中的offlineSessionId消息跳过_null不受影响_e5`() = runBlocking {
        db.messageDao().upsert(embeddedMsg(1L, "被排除的见面原文消息", queryVec).copy(offlineSessionId = "excluded-sess"))
        db.messageDao().upsert(embeddedMsg(2L, "普通聊天消息不受影响", queryVec).copy(offlineSessionId = null))
        coEvery { archiveIndex.retrieval(any(), any(), any()) } returns
            MeetingArchiveVectorService.Retrieval(emptyList(), setOf("excluded-sess"))

        val result = service.searchRelevantMemories(queryText, charUuid, currentConv, "司徒", "夏晴子", 20, 65)

        assertTrue("排除集命中的 offlineSessionId 消息被跳过", result.none { it.contains("被排除的见面原文消息") })
        assertTrue("null offlineSessionId 不受排除影响", result.any { it.contains("普通聊天消息不受影响") })
    }

    // ── 「我们的日子」卷二·第三路（图纸 §3.6 / §7 T2-2）：单池并入 + 真库 hidden 谓词 + 三清 ──

    @Test fun `卷二T2-2 日子候选与消息档案单池按相似度_formatOurDaySnippet格式_窗口cutoff透传`() = runBlocking {
        for (ts in 1L..3L) db.messageDao().upsert(embeddedMsg(ts, "普通消息内容$ts", partialVec))
        coEvery { archiveIndex.retrieval(any(), any(), any()) } returns MeetingArchiveVectorService.Retrieval(
            candidates = listOf(MeetingArchiveVectorService.ArchiveCandidate("那次见面的档案回忆", 1_700_000_000_000L, 0.9)),
            excludedSessionIds = emptySet(),
        )
        coEvery { ourDayIndex.retrieval(any(), any(), any(), any()) } returns OurDayVectorService.Retrieval(
            listOf(OurDayVectorService.DayCandidate("2026-08-22", "林晚和阿澄去了江边", 0.97)),
        )

        val result = service.searchRelevantMemories(queryText, charUuid, currentConv, "司徒", "夏晴子", 20, 65)

        assertEquals("合并池 = 3 消息 + 1 档案 + 1 日子 = 5 ≤ TOP_K", 5, result.size)
        assertEquals("日子（0.97）凭最高相似度排最前·片段格式锁定", "[2026-08-22 周六 · 日子] 林晚和阿澄去了江边", result.first())
        assertTrue("档案（0.9）次之", result[1].contains("· 见面档案]"))
        assertTrue("消息候选仍在池内", result.drop(2).all { it.contains("普通消息内容") })
        // W-8：窗口 cutoff 与消息路同源透传（当前会话零用户消息 → 两路都 null；短于窗口的形态见下方 R1 用例）。
        coVerify(exactly = 1) { ourDayIndex.retrieval(any(), charUuid, 0.65, null) }
    }

    @Test fun `卷二R1 第三路cutoff_会话短于窗口退回最旧用户消息时刻_够窗口时与消息路同值`() = runBlocking {
        // 当前会话 3 条用户消息（ts 1/3/5）+ 2 条助手（2/4）；窗口 20 → 消息路 cutoff = null（整个会话都在原文里）。
        for (ts in 1L..5L) db.messageDao().upsert(embeddedMsg(ts, "当前会话消息$ts", orthogonalVec).copy(conversationUuid = currentConv))
        service.searchRelevantMemories(queryText, charUuid, currentConv, "司徒", "夏晴子", 20, 65)
        coVerify(exactly = 1) { ourDayIndex.retrieval(any(), charUuid, 0.65, 1L) } // 最旧用户消息 ts=1 → 那天起全部排除
        // 窗口 2：第 2 近的用户消息 ts=3 就是消息路 cutoff，日子路同值。
        service.searchRelevantMemories(queryText, charUuid, currentConv, "司徒", "夏晴子", 2, 65)
        coVerify(exactly = 1) { ourDayIndex.retrieval(any(), charUuid, 0.65, 3L) }
    }

    @Test fun `卷二R1 injectableForCharacter投影_大列置空_谓词与升序_真Room`() = runBlocking {
        val vec = service.serializeEmbedding(queryVec)
        fun row(key: String, factLine: String = "事实$key", hidden: Boolean = false, deleted: Boolean = false, char: String = charUuid) =
            com.situ.aichat.data.local.entity.OurDayEntity(
                uuid = "p-$key", characterUuid = char, dayKey = key, factsJson = "{\"k\":1}", note = "手记正文", factLine = factLine,
                messageCount = 5, callSeconds = 60, hasMeeting = true, hiddenFromMemory = hidden, deleted = deleted, embedding = vec,
                createdAtMillis = 1L, updatedAtMillis = 2L,
            )
        db.ourDayDao().upsert(row("2026-08-02"))
        db.ourDayDao().upsert(row("2026-08-01"))
        db.ourDayDao().upsert(row("2026-08-03", hidden = true))
        db.ourDayDao().upsert(row("2026-08-04", deleted = true))
        db.ourDayDao().upsert(row("2026-08-05", factLine = ""))
        db.ourDayDao().upsert(row("2026-08-06", char = "other"))

        val got = db.ourDayDao().injectableForCharacter(charUuid)
        assertEquals("谓词 + 升序", listOf("2026-08-01", "2026-08-02"), got.map { it.dayKey })
        val r = got.first()
        assertEquals("事实2026-08-01", r.factLine)
        assertEquals(5, r.messageCount)
        assertEquals(60, r.callSeconds)
        assertTrue(r.hasMeeting)
        assertNull("embedding 不物化", r.embedding)
        assertEquals("note 不物化", "", r.note)
        assertEquals("factsJson 不物化", "", r.factsJson)
        assertEquals("投影不改库里的完整行", "手记正文", db.ourDayDao().getAll().first { it.dayKey == "2026-08-01" }.note)
    }

    @Test fun `卷二T2-2 真Room库 embeddedForCharacter 排除hidden与deleted与无向量行_E13`() = runBlocking {
        fun row(key: String, hidden: Boolean = false, deleted: Boolean = false, emb: ByteArray? = service.serializeEmbedding(queryVec)) =
            com.situ.aichat.data.local.entity.OurDayEntity(
                uuid = "d-$key", characterUuid = charUuid, dayKey = key, factLine = "事实行$key",
                hiddenFromMemory = hidden, deleted = deleted, embedding = emb, createdAtMillis = 0L, updatedAtMillis = 0L,
            )
        db.ourDayDao().upsert(row("2026-08-01"))
        db.ourDayDao().upsert(row("2026-08-02", hidden = true))
        db.ourDayDao().upsert(row("2026-08-03", deleted = true))
        db.ourDayDao().upsert(row("2026-08-04", emb = null))
        db.ourDayDao().upsert(row("2026-08-05").copy(characterUuid = "other"))

        assertEquals(listOf("2026-08-01"), db.ourDayDao().embeddedForCharacter(charUuid).map { it.dayKey })
        // 回填谓词同样挡住 hidden / deleted；无向量的正常行才是缺失集。
        assertEquals(listOf("2026-08-04"), db.ourDayDao().missingEmbedding(16).map { it.dayKey })
        // 「别让 TA 记」置位 → DAO 顺手把向量置 NULL（卷一）→ 检索候选与缺失集都不再含它。
        db.ourDayDao().updateHidden("d-2026-08-01", hidden = true, now = 1L)
        assertTrue(db.ourDayDao().embeddedForCharacter(charUuid).isEmpty())
        assertTrue(db.ourDayDao().missingEmbedding(16).none { it.dayKey == "2026-08-01" })
    }

    @Test fun `卷二T2-2 模型签名变更_CLEAR_AND_REEMBED三清含日子_E52`() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        EmbeddingModelSignatureStore.set(ctx, "legacy-other-dim384")
        every { embedder.isAvailable } returns true
        coEvery { archiveIndex.clearAll() } returns 3
        coEvery { ourDayIndex.clearAll() } returns 2

        service.detectModelChangeAndClearIfNeeded(ctx)

        coVerify(exactly = 1) { ourDayIndex.clearAll() }
        coVerify(exactly = 1) { archiveIndex.clearAll() }
    }

    @Test fun `T2-4 模型签名变更_CLEAR_AND_REEMBED分支双清含档案_e2`() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        EmbeddingModelSignatureStore.set(ctx, "legacy-other-dim384") // 存量旧签名（非首装·与当前不同）
        every { embedder.isAvailable } returns true
        coEvery { archiveIndex.clearAll() } returns 3
        coEvery { ourDayIndex.clearAll() } returns 0 // 卷二第三路：三清同分支（既有断言零改）

        service.detectModelChangeAndClearIfNeeded(ctx)

        coVerify(exactly = 1) { archiveIndex.clearAll() } // 消息 + 档案双清（图纸 §3.2）
    }
}
