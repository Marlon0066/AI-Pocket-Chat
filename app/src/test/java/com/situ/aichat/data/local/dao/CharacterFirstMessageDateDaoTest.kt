package com.situ.aichat.data.local.dao

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 相识天数图纸 §6.2 · T2（E3/E5/E16/E17/E2）：「第一次聊天时间」两条新 SQL 的真 Room 行为——
 * [CharacterDao.markFirstMessageDate] 只往早改（空写 / 更晚不写 / 更早改写 / 未知 uuid 零行），
 * [MessageDao.earliestNonEmptyTimestampByCharacter] 跨会话取最早、跳空内容、无消息角色整行缺席。
 * 断言从图纸 §4.1 的 SQL 守卫与谓词独立反推（不照抄实现输出）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacterFirstMessageDateDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var characterDao: CharacterDao
    private lateinit var messageDao: MessageDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        characterDao = db.characterDao()
        messageDao = db.messageDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun character(uuid: String) =
        characterDao.upsert(CharacterEntity(uuid = uuid, name = "角色-$uuid", creationDate = 0L))

    private suspend fun conversation(uuid: String, charUuid: String) =
        db.conversationDao().upsert(
            ConversationEntity(uuid = uuid, title = "会话-$uuid", characterUuid = charUuid, creationDate = 0L),
        )

    private suspend fun message(uuid: String, convUuid: String, ts: Long, content: String = "你好") =
        messageDao.upsert(
            MessageEntity(messageUUID = uuid, conversationUuid = convUuid, roleRaw = "user", content = content, timestamp = ts),
        )

    @Test
    fun `markFirstMessageDate 只往早改`() = runBlocking {
        character("c1")
        assertNull("初始为空", characterDao.getByUuid("c1")!!.firstMessageDate)

        assertEquals("空 → 写入", 1, characterDao.markFirstMessageDate("c1", 100L))
        assertEquals(100L, characterDao.getByUuid("c1")!!.firstMessageDate)

        assertEquals("更晚的值 → 零行", 0, characterDao.markFirstMessageDate("c1", 200L))
        assertEquals("值不动", 100L, characterDao.getByUuid("c1")!!.firstMessageDate)

        assertEquals("更早的值 → 改写", 1, characterDao.markFirstMessageDate("c1", 50L))
        assertEquals(50L, characterDao.getByUuid("c1")!!.firstMessageDate)

        assertEquals("相等的值 → 零行（严格大于才写）", 0, characterDao.markFirstMessageDate("c1", 50L))

        assertEquals("未知 uuid → 零行", 0, characterDao.markFirstMessageDate("不存在", 1L))
    }

    @Test
    fun `最早非空投影 跨会话取最早_跳空内容_无消息角色缺席`() = runBlocking {
        character("c1"); character("c2"); character("c3")
        conversation("conv-a", "c1")
        conversation("conv-b", "c1")
        conversation("conv-c", "c3")
        message("m1", "conv-a", 300L)
        message("m2", "conv-b", 120L)
        message("m3", "conv-a", 10L, content = "") // 空内容不算「第一次聊天」
        message("m4", "conv-c", 7L, content = "")  // c3 只有空内容消息

        val rows = messageDao.earliestNonEmptyTimestampByCharacter()
        assertEquals("只有 c1 有非空消息", 1, rows.size)
        assertEquals("c1", rows[0].characterUuid)
        assertEquals("跨两个会话取最早的非空那条", 120L, rows[0].ts)
    }

    @Test
    fun `角色已删_会话与消息级联清_投影无该行`() = runBlocking {
        character("c1")
        conversation("conv-a", "c1")
        message("m1", "conv-a", 300L)
        assertEquals(1, messageDao.earliestNonEmptyTimestampByCharacter().size)

        characterDao.deleteByUuid("c1")
        assertEquals("级联删后投影不再有该角色", 0, messageDao.earliestNonEmptyTimestampByCharacter().size)
        assertEquals("对已删角色的写口零行", 0, characterDao.markFirstMessageDate("c1", 1L))
    }

    @Test
    fun `多角色互不串_各自一行`() = runBlocking {
        character("c1"); character("c2")
        conversation("conv-a", "c1")
        conversation("conv-b", "c2")
        message("m1", "conv-a", 500L)
        message("m2", "conv-b", 900L)

        val byChar = messageDao.earliestNonEmptyTimestampByCharacter().associate { it.characterUuid to it.ts }
        assertEquals(mapOf("c1" to 500L, "c2" to 900L), byChar)
    }
}
