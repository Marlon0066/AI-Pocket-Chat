package com.situ.aichat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T3（图纸 2026-09-04 §7·E10/E11）：`updateLastMessageSnapshotIfNewer` 的 **SQL 条件真值**只有真 SQLite 能证
 * （单测层的 mockk 只能钉「走哪个口」）。三态逐条钉死：
 *
 * 1. 库中 ts 更**晚** → 不覆写（= 打断瞬间用户新消息的快照不被 AI 已插段的旧时刻打回去）；
 * 2. 库中 ts 更**早** → 覆写（正常递送路径不被误拦）；
 * 3. 库中 ts 为 **NULL**（整会话删空后清过快照）→ 覆写（`IS NULL` 分支不许省）。
 *
 * 外加同毫秒放行（`<=` 不是 `<`）与「旧方法恒无条件」的对照，钉住图纸 §9① 锁定的那条 SQL。
 */
@RunWith(AndroidJUnit4::class)
class ConversationPreviewMonotonicDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ConversationDao

    private val convUuid = "conv-1"

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.conversationDao()
        // FK 父链：character → conversation。
        db.characterDao().upsert(CharacterEntity(uuid = "char-1", name = "测试", creationDate = 0L))
        dao.upsert(ConversationEntity(uuid = convUuid, title = "t", characterUuid = "char-1", creationDate = 0L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun snapshot() = dao.getByUuid(convUuid)!!

    @Test
    fun 库中ts更晚_不覆写() = runBlocking {
        dao.updateLastMessageSnapshot(convUuid, "用户刚发的", "user", 200L)
        dao.updateLastMessageSnapshotIfNewer(convUuid, "AI 已插段", "assistant", 100L)
        val row = snapshot()
        assertEquals("用户刚发的", row.lastMessagePreview)
        assertEquals("user", row.lastMessageRole)
        assertEquals(200L, row.lastMessageDate)
    }

    @Test
    fun 库中ts更早_正常覆写() = runBlocking {
        dao.updateLastMessageSnapshot(convUuid, "旧的", "user", 200L)
        dao.updateLastMessageSnapshotIfNewer(convUuid, "AI 说的", "assistant", 300L)
        val row = snapshot()
        assertEquals("AI 说的", row.lastMessagePreview)
        assertEquals("assistant", row.lastMessageRole)
        assertEquals(300L, row.lastMessageDate)
    }

    @Test
    fun 库中ts为NULL_照样覆写() = runBlocking {
        dao.updateLastMessageSnapshot(convUuid, "", "", null) // = clearLastMessage（整会话删空）
        assertEquals(null, snapshot().lastMessageDate)
        dao.updateLastMessageSnapshotIfNewer(convUuid, "AI 说的", "assistant", 50L)
        val row = snapshot()
        assertEquals("AI 说的", row.lastMessagePreview)
        assertEquals(50L, row.lastMessageDate)
    }

    @Test
    fun 同毫秒放行_不被静默丢弃() = runBlocking {
        dao.updateLastMessageSnapshot(convUuid, "并列写入", "user", 200L)
        dao.updateLastMessageSnapshotIfNewer(convUuid, "AI 说的", "assistant", 200L)
        assertEquals("AI 说的", snapshot().lastMessagePreview) // `<=` 放行；写成 `<` 这里会红
    }

    @Test
    fun 旧方法恒无条件_写更早ts照样生效() = runBlocking {
        dao.updateLastMessageSnapshot(convUuid, "新的", "assistant", 300L)
        dao.updateLastMessageSnapshot(convUuid, "删消息后重算", "user", 100L) // 重算路径会写更早的 ts
        val row = snapshot()
        assertEquals("删消息后重算", row.lastMessagePreview)
        assertEquals(100L, row.lastMessageDate)
    }
}
