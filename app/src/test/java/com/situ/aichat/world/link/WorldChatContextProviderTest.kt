package com.situ.aichat.world.link

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldMemoryEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.util.StringListJson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [WorldChatContextProvider] T2-5（Robolectric 真 Room + MockK 假嵌入器 + 真 [VectorMemoryService] 真余弦·
 * 图纸 §7·E10/E11）：门控四连 + 检索阈值。断言从图纸 §3.3 门控四连 + 0.65 阈值独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldChatContextProviderTest {

    private lateinit var db: AppDatabase
    private lateinit var embedder: TextEmbedder
    private lateinit var vectorService: VectorMemoryService
    private lateinit var provider: WorldChatContextProvider

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        embedder = mockk(relaxed = true)
        // 第 4 参 archiveIndex（记忆改造四期·E15·图纸侦察漏点）：本测走 provider 自有世界记忆检索，不触 searchRelevantMemories → relaxed 桩不被调用。
        vectorService = VectorMemoryService(mockk(relaxed = true), mockk(relaxed = true), embedder, mockk(relaxed = true), mockk(relaxed = true)) // 真余弦/序列化（第 5 参 ourDayIndex·卷二·同上不触）
        provider = WorldChatContextProvider(db.worldDao(), db.worldSocialDao(), db.worldMemoryDao(), db.characterDao(), embedder, vectorService)
    }

    @After
    fun tearDown() = db.close()

    private fun char(uuid: String, joined: Boolean = true) =
        CharacterEntity(uuid = uuid, name = uuid, creationDate = 0L, joinedWorld = joined)

    private fun settings(rel: Boolean = true) = AppSettings(worldRelationshipsEnabled = rel)

    private fun seedState() = runBlocking { db.worldDao().upsertState(WorldStateEntity(seed = 1L, userTimezoneId = "UTC", createdAt = 0L)) }

    private fun seedEdgeAndPeer(self: String, other: String) = runBlocking {
        db.characterDao().upsert(char(other))
        db.worldSocialDao().upsertEdge(
            WorldRelationshipEntity(fromId = self, toId = other, typesJson = StringListJson.encode(listOf("相识")), closeness = 40, colorRaw = "投缘"),
        )
    }

    private fun mem(uuid: String, owner: String, content: String, happenedAt: Long, embedding: ByteArray? = null) =
        WorldMemoryEntity(
            uuid = uuid, characterUuid = owner, otherIdsJson = "[]", kindRaw = "rel_first_meet",
            content = content, happenedAt = happenedAt, sourceUuid = "s", createdAt = happenedAt, embedding = embedding,
        )

    private val nowMs get() = System.currentTimeMillis()

    // MARK: - E10 门控四连

    @Test
    fun `E10 未加入世界_null`() = runBlocking {
        seedState(); seedEdgeAndPeer("me", "peer")
        assertNull(provider.forTurn(char("me", joined = false), "query", settings(rel = true)))
    }

    @Test
    fun `E10 关系开关关_null`() = runBlocking {
        seedState(); seedEdgeAndPeer("me", "peer")
        assertNull(provider.forTurn(char("me"), "query", settings(rel = false)))
    }

    @Test
    fun `E10 世界未初始化_null`() = runBlocking {
        seedEdgeAndPeer("me", "peer") // 有边但无 world_state 行
        assertNull(provider.forTurn(char("me"), "query", settings(rel = true)))
    }

    @Test
    fun `E10 无边且无记忆_null`() = runBlocking {
        seedState()
        assertNull(provider.forTurn(char("me"), "query", settings(rel = true)))
    }

    @Test
    fun `门控全过_有边则注入块头与提炼行`() = runBlocking {
        seedState(); seedEdgeAndPeer("me", "peer")
        val out = provider.forTurn(char("me"), "", settings(rel = true))!!
        assertTrue("含块头", out.contains("以下是你在这座小城生活的人际近况与经历"))
        assertTrue("含提炼行", out.contains("【与peer｜相识】"))
    }

    // MARK: - E11 检索（query 空 → 仅近 3 天层；非空 → 余弦≥0.65 入选，无关不入）

    @Test
    fun `E11 query空_仅近3天层_无向量层`() = runBlocking {
        seedState()
        val recent = nowMs - 1L * 86_400_000L // 1 天前（近层）
        val old = nowMs - 30L * 86_400_000L // 30 天前但有嵌入（向量层候选）
        db.worldMemoryDao().upsert(mem("m-near", "me", "近三天的记忆", recent))
        db.worldMemoryDao().upsert(mem("m-old", "me", "很久以前的记忆", old, embedding = vectorService.serializeEmbedding(floatArrayOf(1f, 0f))))

        val out = provider.forTurn(char("me"), "", settings(rel = true))!! // query 空 → 向量层跳过
        assertTrue("近 3 天记忆入选", out.contains("近三天的记忆"))
        assertTrue("旧记忆不经向量层入选", !out.contains("很久以前的记忆"))
    }

    @Test
    fun `E11 向量检索_相关入选无关不入_阈值0点65`() = runBlocking {
        seedState()
        val old = nowMs - 30L * 86_400_000L // 均在近 3 天窗之外 → 只能经向量层
        db.worldMemoryDao().upsert(mem("m-rel", "me", "相关记忆", old, embedding = vectorService.serializeEmbedding(floatArrayOf(1f, 0f))))
        db.worldMemoryDao().upsert(mem("m-irr", "me", "无关记忆", old - 1000L, embedding = vectorService.serializeEmbedding(floatArrayOf(0f, 1f))))
        every { embedder.isAvailable } returns true
        every { embedder.embed("找猫") } returns floatArrayOf(1f, 0f) // 与相关记忆余弦=1.0，与无关余弦=0.0

        val out = provider.forTurn(char("me"), "找猫", settings(rel = true))!!
        assertTrue("余弦 1.0≥0.65 → 相关入选", out.contains("相关记忆"))
        assertTrue("余弦 0.0<0.65 → 无关不入", !out.contains("无关记忆"))
    }
}
