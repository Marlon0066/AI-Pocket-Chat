package com.situ.aichat.data.local

import androidx.room.Room
import com.situ.aichat.data.local.dao.WorldBookDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.WorldBookBindingEntity
import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.data.local.entity.WorldBookTimedStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 世界书数据层 T2（WB1·Robolectric 真 Room 内存库·契约 `FABLE5_WORLDBOOK_PROPOSAL.md` §4.1）：
 * 级联删除（删书清条目 / 删角色清绑定 / 删条目·删会话清时效状态）、绑定去重、
 * 「全局书 ∪ 绑定书」聚合过滤、时效状态同键覆写、条目排序。断言从契约反推，非照搬实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBookDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: WorldBookDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.worldBookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun book(uuid: String, global: Boolean = false, enabled: Boolean = true) =
        WorldBookEntity(uuid = uuid, name = "书-$uuid", isGlobal = global, enabled = enabled)

    private fun entry(uuid: String, bookUuid: String, displayIndex: Int = 0) =
        WorldBookEntryEntity(uuid = uuid, bookUuid = bookUuid, displayIndex = displayIndex)

    private suspend fun seedCharacter(uuid: String) {
        db.characterDao().upsert(CharacterEntity(uuid = uuid, name = "角色-$uuid", creationDate = 0L))
    }

    private suspend fun seedConversation(uuid: String, characterUuid: String) {
        db.conversationDao().upsert(
            ConversationEntity(uuid = uuid, title = "会话", characterUuid = characterUuid, creationDate = 0L),
        )
    }

    private fun timedStateCountInTable(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM world_book_timed_states").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    @Test
    fun 删书_条目级联清空() = runBlocking {
        dao.upsertBook(book("b1"))
        dao.upsertEntries(listOf(entry("e1", "b1"), entry("e2", "b1")))
        assertEquals(2, dao.entryCountForBook("b1"))

        dao.deleteBook("b1")

        assertNull("书应已删除", dao.getBook("b1"))
        assertEquals("删书必须级联清条目，不留孤儿", 0, dao.entryCountForBook("b1"))
    }

    @Test
    fun 激活书聚合_等于启用的全局书并绑定书() = runBlocking {
        seedCharacter("c1")
        dao.upsertBook(book("global-on", global = true))
        dao.upsertBook(book("global-off", global = true, enabled = false))
        dao.upsertBook(book("bound-on"))
        dao.upsertBook(book("bound-off", enabled = false))
        dao.upsertBook(book("stranger"))
        dao.bind(WorldBookBindingEntity("c1", "bound-on"))
        dao.bind(WorldBookBindingEntity("c1", "bound-off"))

        val active = dao.activeBooksForCharacter("c1").map { it.uuid }.toSet()

        // 契约：生效 = 启用的（全局 ∪ 绑定）；停用书与未绑定书都不得混入。
        assertEquals(setOf("global-on", "bound-on"), active)
    }

    @Test
    fun 重复绑定_静默去重不报错() = runBlocking {
        seedCharacter("c1")
        dao.upsertBook(book("b1"))
        dao.bind(WorldBookBindingEntity("c1", "b1"))
        dao.bind(WorldBookBindingEntity("c1", "b1"))

        assertEquals(listOf("b1"), dao.boundBookUuids("c1"))
        assertEquals(1, dao.bindingCountForBook("b1"))
    }

    @Test
    fun 删角色_绑定级联清_书本身保留() = runBlocking {
        seedCharacter("c1")
        dao.upsertBook(book("b1"))
        dao.bind(WorldBookBindingEntity("c1", "b1"))
        assertEquals(1, dao.bindingCountForBook("b1"))

        db.openHelper.writableDatabase.execSQL("DELETE FROM characters WHERE uuid = 'c1'")

        assertEquals("删角色必须级联清绑定", 0, dao.bindingCountForBook("b1"))
        assertNotNull("书不属于角色，删角色不得动书", dao.getBook("b1"))
    }

    @Test
    fun 删条目_时效状态级联清() = runBlocking {
        seedCharacter("c1")
        seedConversation("v1", "c1")
        dao.upsertBook(book("b1"))
        dao.upsertEntry(entry("e1", "b1"))
        dao.upsertTimedState(WorldBookTimedStateEntity("v1", "e1", "sticky", 10, 3))
        assertEquals(1, timedStateCountInTable())

        dao.deleteEntry("e1")

        assertEquals("删条目必须级联清时效状态", 0, timedStateCountInTable())
    }

    @Test
    fun 删会话_时效状态级联清() = runBlocking {
        seedCharacter("c1")
        seedConversation("v1", "c1")
        dao.upsertBook(book("b1"))
        dao.upsertEntry(entry("e1", "b1"))
        dao.upsertTimedState(WorldBookTimedStateEntity("v1", "e1", "cooldown", 5, 2))
        assertEquals(1, timedStateCountInTable())

        db.openHelper.writableDatabase.execSQL("DELETE FROM conversations WHERE uuid = 'v1'")

        assertEquals("删会话必须级联清时效状态", 0, timedStateCountInTable())
    }

    @Test
    fun 时效状态_同键覆写不叠加() = runBlocking {
        seedCharacter("c1")
        seedConversation("v1", "c1")
        dao.upsertBook(book("b1"))
        dao.upsertEntry(entry("e1", "b1"))

        dao.upsertTimedState(WorldBookTimedStateEntity("v1", "e1", "sticky", 10, 3))
        dao.upsertTimedState(WorldBookTimedStateEntity("v1", "e1", "sticky", 20, 5))

        val states = dao.timedStatesForConversation("v1")
        assertEquals("同（会话,条目,类型）键必须覆写成一行", 1, states.size)
        assertEquals(20, states.single().triggeredAtMessageCount)
        assertEquals(5, states.single().durationMessages)
    }

    @Test
    fun 时效状态_sticky与cooldown互不覆盖() = runBlocking {
        seedCharacter("c1")
        seedConversation("v1", "c1")
        dao.upsertBook(book("b1"))
        dao.upsertEntry(entry("e1", "b1"))

        dao.upsertTimedState(WorldBookTimedStateEntity("v1", "e1", "sticky", 10, 3))
        dao.upsertTimedState(WorldBookTimedStateEntity("v1", "e1", "cooldown", 10, 8))

        assertEquals("sticky 与 cooldown 各自独立成行", 2, dao.timedStatesForConversation("v1").size)
    }

    @Test
    fun 定向更新嵌入_只写两列其余不动() = runBlocking {
        dao.upsertBook(book("b1"))
        dao.upsertEntry(entry("e1", "b1"))

        dao.updateEntryEmbedding("e1", byteArrayOf(1, 2, 3), "sig-v1")

        val e = dao.getEntry("e1")!!
        org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3), e.embedding)
        assertEquals("sig-v1", e.embeddingSignature)
        assertEquals("其余列必须原样", entry("e1", "b1").content, e.content)
    }

    @Test
    fun 条目按displayIndex升序返回() = runBlocking {
        dao.upsertBook(book("b1"))
        dao.upsertEntries(
            listOf(entry("e-c", "b1", displayIndex = 2), entry("e-a", "b1", displayIndex = 0), entry("e-b", "b1", displayIndex = 1)),
        )

        assertEquals(listOf("e-a", "e-b", "e-c"), dao.entriesForBook("b1").map { it.uuid })
    }

    @Test
    fun entriesForBooks_跨书聚合() = runBlocking {
        dao.upsertBook(book("b1"))
        dao.upsertBook(book("b2"))
        dao.upsertEntries(listOf(entry("e1", "b1"), entry("e2", "b2"), entry("e3", "b2")))

        assertEquals(3, dao.entriesForBooks(listOf("b1", "b2")).size)
        assertEquals(1, dao.entriesForBooks(listOf("b1")).size)
    }

    @Test
    fun 入世角色_连全局书与残留绑定书一并隔离_离世后恢复() = runBlocking {
        db.characterDao().upsert(
            CharacterEntity(uuid = "c-world", name = "入世角色", creationDate = 0L, joinedWorld = true),
        )
        dao.upsertBook(book("global-on", global = true))
        dao.upsertBook(book("bound-on"))
        dao.bind(WorldBookBindingEntity("c-world", "bound-on"))

        // 3A：joinedWorld = 1 时彻底隔离世界书（含全局书）
        assertEquals(emptyList<String>(), dao.activeBooksForCharacter("c-world").map { it.uuid })

        // 离开世界后恢复生效
        db.characterDao().updateWorldMembership("c-world", joined = false, joinedAt = null)
        assertEquals(setOf("global-on", "bound-on"), dao.activeBooksForCharacter("c-world").map { it.uuid }.toSet())

        // unbindAllForCharacter 清空专属绑定但保留全局书
        dao.unbindAllForCharacter("c-world")
        assertEquals(listOf("global-on"), dao.activeBooksForCharacter("c-world").map { it.uuid })
    }
}
