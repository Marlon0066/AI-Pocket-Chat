package com.situ.aichat.seam

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.prompt.growth.GrowthAnalysisService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卷一 C8「分析取材」T1（图纸 §7 T1-C8·E13/E14）：喂给成长/结构化记忆分析的消息里，**线下见面行剥掉
 * 沉浸标签**（否则模型把 `[叙述]` 这类标签当语言习惯学走），线上行**字节不变**，条数与顺序不变
 * （见面轮次照常计入成长 = 拍板零碰）。
 */
class AnalysisCollectStripTest {

    private fun msg(uuid: String, content: String, ts: Long, offline: Boolean) = MessageEntity(
        messageUUID = uuid, conversationUuid = "conv-1", roleRaw = "assistant",
        content = content, timestamp = ts, isOfflineMode = offline,
        offlineSessionId = if (offline) "sess-1" else null,
    )

    private suspend fun collect(messages: List<MessageEntity>): List<MessageEntity> {
        val conversationDao: ConversationDao = mockk()
        val messageDao: MessageDao = mockk()
        coEvery { conversationDao.getByCharacter("c1") } returns listOf(
            ConversationEntity(uuid = "conv-1", title = "t", characterUuid = "c1", creationDate = 0L),
        )
        coEvery { messageDao.recentForAnalysis("conv-1", any()) } returns messages
        return GrowthAnalysisService(
            contextLog = mockk(relaxed = true), conversationDao = conversationDao,
            messageDao = messageDao, scheduleDao = mockk(relaxed = true),
        ).collectMessagesForAnalysis("c1")
    }

    @Test
    fun 混合取材_只剥线下行_线上行字节不变() = runTest {
        val online = msg("m1", "明天几点出发？记得带伞[sticker:x]", 1L, offline = false)
        val offline = msg("m2", "[叙述]她把伞递过来。[/叙述][对话]拿着，别淋着。[/对话]", 2L, offline = true)
        val result = collect(listOf(online, offline))

        assertEquals("条数不变", 2, result.size)
        assertEquals("顺序不变（时间升序）", listOf("m1", "m2"), result.map { it.messageUUID })
        assertEquals("线上行字节级不变", online.content, result[0].content)
        val stripped = result[1].content
        assertTrue("线下标签须剥净: $stripped", !stripped.contains("[叙述]") && !stripped.contains("[对话]"))
        assertTrue("正文须保留: $stripped", stripped.contains("她把伞递过来") && stripped.contains("别淋着"))
        assertTrue("其余字段不动", result[1].isOfflineMode && result[1].messageUUID == "m2")
    }

    /** E13：无线下标签的线下行 → 原样返回（剥标签幂等，不误伤正文）。 */
    @Test
    fun 线下行无标签_原样返回() = runTest {
        val plain = msg("m1", "今天很开心", 1L, offline = true)
        assertEquals("今天很开心", collect(listOf(plain)).single().content)
    }

    @Test
    fun 全线上取材_零改动() = runTest {
        val a = msg("m1", "在吗", 1L, offline = false)
        val b = msg("m2", "在的[场景：这不是标签因为没标线下]", 2L, offline = false)
        val result = collect(listOf(a, b))
        assertEquals(listOf(a.content, b.content), result.map { it.content })
    }
}
