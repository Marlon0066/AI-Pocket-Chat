package com.situ.aichat.quickreply

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.recovery.RecoveryClaimTracker
import com.situ.aichat.recovery.RecoveryReplyGenerator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [ListQuickReplyService.retryReply] T2-8（Robolectric 真 Room + 真 [RecoveryClaimTracker] + MockK 假生成器·图纸 §7·E2/E4）：
 * retryReply **不插用户消息**、占坑复用；既有 `sendAndAwait` 行为零变化；已答会话（末条非 user）→ 不再生成（双答护栏）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListQuickReplyRetryTest {

    private lateinit var db: AppDatabase
    private lateinit var messageRepo: MessageRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var replyGenerator: RecoveryReplyGenerator
    private lateinit var service: ListQuickReplyService

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        messageRepo = MessageRepository(db.messageDao())
        conversationRepo = ConversationRepository(db.conversationDao())
        replyGenerator = mockk()
        service = ListQuickReplyService(messageRepo, conversationRepo, replyGenerator, RecoveryClaimTracker(), db.characterDao())
        db.characterDao().upsert(CharacterEntity(uuid = "c1", name = "苏晚", creationDate = 0L))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun conv(): String = conversationRepo.getOrCreateForCharacter("c1", "苏晚")

    @Test
    fun `retryReply不插消息_占坑生成一次`() = runBlocking {
        val c = conv()
        conversationRepo.recordLastMessage(c, "你好", "user", 1L) // 末条 = user（快聊发送失败后的态）
        coEvery { replyGenerator.generateAndPersist(c) } returns true
        assertTrue(service.retryReply(c))
        coVerify(exactly = 1) { replyGenerator.generateAndPersist(c) }
        assertEquals("retryReply 不插任何消息", 0, messageRepo.recentChronological(c, 50).size)
    }

    @Test
    fun `insertUserMessage插消息加末条_不触发生成`() = runBlocking {
        // R1 🔴-1：insert-only 口——落用户消息 + 末条更新，**不**触发 LLM 生成（供忙碌快聊即时回显）。
        val c = conv()
        coEvery { replyGenerator.generateAndPersist(any()) } returns true
        assertTrue(service.insertUserMessage(c, "你好"))
        val msgs = messageRepo.recentChronological(c, 50)
        assertEquals("落一条用户消息", 1, msgs.size)
        assertEquals("user", msgs[0].roleRaw)
        assertEquals("你好", msgs[0].content)
        assertEquals("末条角色更新", "user", conversationRepo.get(c)?.lastMessageRole)
        assertEquals("末条预览更新", "你好", conversationRepo.get(c)?.lastMessagePreview)
        coVerify(exactly = 0) { replyGenerator.generateAndPersist(any()) } // 绝不触发生成
        assertFalse("空白 → false 不落库", service.insertUserMessage(c, "   "))
        assertEquals("空白未新增消息", 1, messageRepo.recentChronological(c, 50).size)
    }

    @Test
    fun `既有sendAndAwait插用户消息_行为零变化`() = runBlocking {
        val c = conv()
        coEvery { replyGenerator.generateAndPersist(c) } returns true
        assertTrue(service.sendAndAwait(c, "你好"))
        val msgs = messageRepo.recentChronological(c, 50)
        assertEquals("sendAndAwait 落一条用户消息", 1, msgs.size)
        assertEquals("user", msgs[0].roleRaw)
        assertEquals("你好", msgs[0].content)
    }

    @Test
    fun `E4_已答会话末条非user_不再生成_防双答`() = runBlocking {
        val c = conv()
        conversationRepo.recordLastMessage(c, "来啦", "assistant", 1L) // 已答（如快聊即时答后）
        coEvery { replyGenerator.generateAndPersist(any()) } returns true
        assertFalse("末条非 user → 不生成", service.retryReply(c))
        coVerify(exactly = 0) { replyGenerator.generateAndPersist(any()) }
    }

    // ── 火花续期（前后置区审计 R1-N8·2026-07-13 用户拍板·图纸 2026-07-13-快捷回复火花续期.md） ──

    @Test
    fun `快捷回复续火花_昨天聊过则连续天数加一`() = runBlocking {
        val c = conv()
        val yesterday = System.currentTimeMillis() - 24 * 3_600_000 // 恰好落在昨天（无论几点）
        db.characterDao().upsert(
            CharacterEntity(uuid = "c1", name = "苏晚", creationDate = 0L, streakCount = 3, lastChatDate = yesterday),
        )
        assertTrue(service.insertUserMessage(c, "早呀"))
        val after = db.characterDao().getByUuid("c1")!!
        assertEquals("昨天聊过 → 连续天数 +1", 4, after.streakCount)
        assertTrue("lastChatDate 刷到今天", after.lastChatDate!! > yesterday)
    }

    @Test
    fun `同日重复快捷回复_不重复计数不重复写库`() = runBlocking {
        val c = conv()
        assertTrue(service.insertUserMessage(c, "第一句")) // 从未聊过 → 置 1
        val stamp = db.characterDao().getByUuid("c1")!!.lastChatDate
        assertTrue(service.insertUserMessage(c, "第二句"))
        val after = db.characterDao().getByUuid("c1")!!
        assertEquals("同日不重复计数", 1, after.streakCount)
        assertEquals("同日零写（recordChat 引用相等跳过持久化）", stamp, after.lastChatDate)
    }

    /** 相识天数图纸 §5 E6：快捷回复路（真 Room）落「第一次聊天时间」= 该次 now，与火花的 lastChatDate 同刻。 */
    @Test
    fun `快捷回复_字段空时落第一次聊天时间`() = runBlocking {
        val c = conv()
        assertTrue(service.insertUserMessage(c, "早呀"))
        val after = db.characterDao().getByUuid("c1")!!
        assertNotNull("首聊时间已落", after.firstMessageDate)
        assertEquals("与火花同一个 now", after.lastChatDate, after.firstMessageDate)
    }

    @Test
    fun `续期失败不阻断回合_消息照落生成照跑`() = runBlocking {
        // best-effort 锁：火花是加分项，通知回复的核心价值是回复本身——续期路炸了回合必须照跑。
        val throwingDao = mockk<com.situ.aichat.data.local.dao.CharacterDao>()
        coEvery { throwingDao.getByUuid(any()) } throws RuntimeException("db boom")
        val svc = ListQuickReplyService(messageRepo, conversationRepo, replyGenerator, RecoveryClaimTracker(), throwingDao)
        val c = conv()
        coEvery { replyGenerator.generateAndPersist(c) } returns true
        assertTrue("续期炸了回合照跑", svc.sendAndAwait(c, "你好"))
        assertEquals("用户消息照落", 1, messageRepo.recentChronological(c, 50).size)
    }
}
