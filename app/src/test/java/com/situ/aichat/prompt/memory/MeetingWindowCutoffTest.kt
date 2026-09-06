package com.situ.aichat.prompt.memory

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.prompt.calculateEffectiveMemoryLength
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T2-1（图纸 2026-09-06 见面窗口与节拍卡七件 §7·Robolectric + 真 in-memory Room）：短期窗口切点
 * **不再随见面消息漂移**（§3.A·A 件）。断言从 §5 E5/E6/E7 规格独立反推：
 * 切点 = 第 N 近的**线上**非空 user 消息时间戳；窗外未总结数只数线上且在切点之前、摘要游标之后的 user 消息；
 * 有效窗口 = 基准 + min(未总结, 基准)。
 *
 * E6 同时钉住「旧查询仍会漂移」的对照（[com.situ.aichat.data.local.dao.MessageDao.recentUserTimestamps] 恒含见面消息）——
 * 若谁把两个查询合并回一个，本例必挂。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingWindowCutoffTest {

    private lateinit var db: AppDatabase
    private lateinit var memoryService: MemoryService

    private val charUuid = "char-1"
    private val convUuid = "conv-1"
    private val base = 30

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        memoryService = MemoryService(db.messageDao(), db.conversationDao(), mockk<ContextLogService>(relaxed = true))
        runBlocking {
            db.characterDao().upsert(CharacterEntity(uuid = charUuid, name = "小和", creationDate = 0L))
        }
    }

    @After
    fun tearDown() = db.close()

    /** 摘要游标 = 第 [summarizedThroughRound] 轮的时间戳（0 = 尚无游标）。 */
    private fun seedConversation(summarizedThroughRound: Int) = runBlocking {
        db.conversationDao().upsert(
            ConversationEntity(
                uuid = convUuid,
                title = "t",
                characterUuid = charUuid,
                creationDate = 0L,
                lastSummarizedMessageDate = if (summarizedThroughRound > 0) summarizedThroughRound.toLong() else null,
            ),
        )
    }

    /** 每轮一条非空 user 消息，timestamp = 轮号（切点与计数只看 user 消息，assistant 不影响本组断言）。 */
    private fun seedRounds(fromRound: Int, count: Int, offline: Boolean) = runBlocking {
        for (i in fromRound until fromRound + count) {
            db.messageDao().upsert(
                MessageEntity(
                    messageUUID = "u-$i",
                    conversationUuid = convUuid,
                    roleRaw = "user",
                    content = "u$i",
                    timestamp = i.toLong(),
                    isOfflineMode = offline,
                    offlineSessionId = if (offline) "s1" else null,
                ),
            )
        }
    }

    private fun cutoff(): Long? = runBlocking { memoryService.shortTermWindowCutoffMillis(convUuid, base) }

    private fun unsummarized(): Int = runBlocking {
        val convo = db.conversationDao().getByUuid(convUuid)!!
        memoryService.countUnsummarizedRoundsOutsideBaseWindow(convo, base)
    }

    /** E5：纯线上 40 轮 + 游标在第 10 轮 → 切点 = 第 30 近（ts 11）、未总结 0、有效窗口 30。 */
    @Test
    fun 纯线上会话_新旧查询同结果_有效窗口等于基准() = runBlocking {
        seedConversation(summarizedThroughRound = 10)
        seedRounds(fromRound = 1, count = 40, offline = false)

        val online = db.messageDao().recentOnlineUserTimestamps(convUuid, base)
        val all = db.messageDao().recentUserTimestamps(convUuid, base)
        assertEquals("无见面消息时两个查询必须返回相同列表", all, online)

        assertEquals(11L, cutoff())
        assertEquals(0, unsummarized())
        assertEquals(base, calculateEffectiveMemoryLength(AppSettings(shortTermMemoryLength = base), unsummarized()))
    }

    /** E6：同上再加见面内 60 条用户消息 → 线上切点不动（11）、未总结仍 0、有效窗口仍 30。 */
    @Test
    fun 见面消息不推动切点_有效窗口不被撑大() = runBlocking {
        seedConversation(summarizedThroughRound = 10)
        seedRounds(fromRound = 1, count = 40, offline = false)
        seedRounds(fromRound = 41, count = 60, offline = true)

        assertEquals(11L, cutoff())
        assertEquals(0, unsummarized())
        assertEquals(base, calculateEffectiveMemoryLength(AppSettings(shortTermMemoryLength = base), unsummarized()))

        // 对照：旧查询含见面消息 → 第 30 近落在见面里（ts 71），线上消息被挤出窗口。
        val drifted = db.messageDao().recentUserTimestamps(convUuid, base).last()
        assertEquals(71L, drifted)
        assertNotEquals(drifted, cutoff())
    }

    /** E7：线上只有 10 条用户消息（< 基准）+ 见面 50 条 → 切点 null（向量路当前会话整体排除）、未总结 0。 */
    @Test
    fun 线上不足基准条数_切点为空() = runBlocking {
        seedConversation(summarizedThroughRound = 0)
        seedRounds(fromRound = 1, count = 10, offline = false)
        seedRounds(fromRound = 11, count = 50, offline = true)

        assertEquals(10, db.messageDao().recentOnlineUserTimestamps(convUuid, base).size)
        assertNull(cutoff())
        assertEquals(0, unsummarized())
    }
}
