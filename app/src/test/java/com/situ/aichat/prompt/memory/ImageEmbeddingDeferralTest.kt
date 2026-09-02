package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.MessageEntity
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * T2：图片消息的向量嵌入**必须等摘要落库**（R2 🟡-6 / 返工图纸 D-2）。
 *
 * 病灶：发图时序是「落库 → 受理当场嵌入 → 几秒后摘要回填」。若不推迟，受理那一刻
 * `mediaMemorySummary` 还是空，`renderMemoryContent` 恒产出「发送了一张图片」，而
 * `embedding != null` 又会永久挡住回填 → **每条图片消息的向量都是同一句话**，
 * 既检索不到「那张海边的照片」，这批同构向量还互相高相似、挤占召回名额。
 */
class ImageEmbeddingDeferralTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val conversationDao = mockk<com.situ.aichat.data.local.dao.ConversationDao>(relaxed = true)
    private val embedder = mockk<TextEmbedder>(relaxed = true)
    private val archiveIndex = mockk<MeetingArchiveVectorService>(relaxed = true)
    private val ourDayIndex = mockk<OurDayVectorService>(relaxed = true) // 卷二第 5 参：本测不触检索

    private fun service(): VectorMemoryService {
        every { embedder.isAvailable } returns true
        coEvery { embedder.embed(any()) } returns FloatArray(8) { 0.1f }
        return VectorMemoryService(messageDao, conversationDao, embedder, archiveIndex, ourDayIndex)
    }

    private fun imageMessage(summary: String, embedding: ByteArray? = null) = MessageEntity(
        messageUUID = "m1",
        conversationUuid = "c1",
        roleRaw = "user",
        content = "[图片]",
        timestamp = 1L,
        imageRelativePath = "/data/x.jpg",
        mediaMemorySummary = summary,
        embedding = embedding,
    )

    @Test
    fun `摘要为空的图片消息推迟嵌入`() = runTest {
        coEvery { messageDao.updateEmbedding(any(), any()) } just Runs
        service().embedMessageIfNeeded(imageMessage(summary = ""))
        // 一个字都不该写——否则这条消息就被钉死成「发送了一张图片」那版向量，永不翻身
        coVerify(exactly = 0) { messageDao.updateEmbedding(any(), any()) }
    }

    @Test
    fun `摘要落库后才真的嵌入`() = runTest {
        coEvery { messageDao.updateEmbedding(any(), any()) } just Runs
        service().embedMessageIfNeeded(imageMessage(summary = "海边的黄昏，两个人的背影"))
        coVerify(exactly = 1) { messageDao.updateEmbedding("m1", any()) }
    }

    @Test
    fun `嵌入用的是带描述那版文本 而不是无信息量的占位`() = runTest {
        coEvery { messageDao.updateEmbedding(any(), any()) } just Runs
        service().embedMessageIfNeeded(imageMessage(summary = "海边的黄昏"))
        // 喂给嵌入器的必须含摘要；只有「发送了一张图片」= 检索时和所有其它图片消息撞成一团
        coVerify { embedder.embed(match { it.contains("海边的黄昏") }) }
    }

    @Test
    fun `无图的普通消息不受推迟规则影响`() = runTest {
        coEvery { messageDao.updateEmbedding(any(), any()) } just Runs
        val plain = MessageEntity(
            messageUUID = "m2",
            conversationUuid = "c1",
            roleRaw = "user",
            content = "今天去看海了，风很大",
            timestamp = 1L,
        )
        service().embedMessageIfNeeded(plain)
        coVerify(exactly = 1) { messageDao.updateEmbedding("m2", any()) }
    }

    @Test
    fun `已有向量的不重复嵌入`() = runTest {
        service().embedMessageIfNeeded(imageMessage(summary = "海边", embedding = ByteArray(4)))
        coVerify(exactly = 0) { messageDao.updateEmbedding(any(), any()) }
    }
}
