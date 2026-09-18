package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.model.StructuredMemoryMetadata
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.prompt.growth.GrowthAnalysisService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [StructuredMemoryCoordinator.extractAndPersist] 的返回值契约（2026-09-18 提示词册核对 E 条）：
 * true = 真写进了新的结构化记忆；false = 没写（0 字段丢弃 / 角色已不在）。触发端据此决定要不要重烤通知文案——
 * 此前丢弃路径正常返回、触发端照样回调，与「仅成功路径调（E10）」不符。
 *
 * 手法（T2）：写锁桩成真正执行 block，其余协作者 MockK；断言返回值 + 两条写库路径各写了什么。
 */
class StructuredMemoryCoordinatorTest {

    private val structuredMemoryService = mockk<StructuredMemoryService>()
    private val growthAnalysisService = mockk<GrowthAnalysisService>()
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val characterWriteLock = mockk<CharacterWriteLock>()
    private val config = mockk<ApiConfigValues>(relaxed = true)
    private lateinit var coordinator: StructuredMemoryCoordinator

    private val metadataBefore = StructuredMemoryMetadata(roundsSinceLastExtraction = 12, totalExtractionCount = 3)

    @Before
    fun setUp() {
        coEvery { characterWriteLock.withCharacterLock<Boolean>(any(), any()) } coAnswers {
            secondArg<suspend () -> Boolean>().invoke()
        }
        coEvery { characterDao.getByUuid("c1") } returns CharacterEntity(
            uuid = "c1", name = "小雨", creationDate = 0L,
            structuredMemoryMetadataJSON = metadataBefore.encode(),
        )
        coEvery { growthAnalysisService.collectMessagesForAnalysis("c1", any()) } returns listOf(
            MessageEntity(messageUUID = "m1", conversationUuid = "conv-1", roleRaw = "user", content = "嗨", timestamp = 1L),
        )
        coordinator = StructuredMemoryCoordinator(structuredMemoryService, growthAnalysisService, characterDao, characterWriteLock)
    }

    private fun llmExtracts(memory: StructuredMemory) {
        coEvery { structuredMemoryService.extractMemory(any(), any(), any(), any(), any()) } returns memory
    }

    @Test
    fun 零字段_丢弃_返回false_只写抽取时间_不写记忆() = runBlocking {
        llmExtracts(StructuredMemory())
        assertFalse(coordinator.extractAndPersist("c1", config, "阿哲"))

        coVerify(exactly = 0) { characterDao.updateStructuredMemory(any(), any(), any(), any()) }
        val written = slot<String>()
        coVerify(exactly = 1) { characterDao.updateStructuredMemoryMetadata("c1", capture(written)) }
        val meta = StructuredMemoryMetadata.decode(written.captured)
        assertNotNull("丢弃路径仍写抽取时间（让时间冷却接管）", meta.lastExtractionDate)
        assertEquals("丢弃路径不清零轮数", 12, meta.roundsSinceLastExtraction)
        assertEquals("丢弃路径不计次", 3, meta.totalExtractionCount)
    }

    @Test
    fun 有字段_写回记忆_返回true() = runBlocking {
        llmExtracts(StructuredMemory(insideJoke = "土豆警告"))
        assertTrue(coordinator.extractAndPersist("c1", config, "阿哲"))

        val current = slot<String>()
        coVerify(exactly = 1) { characterDao.updateStructuredMemory("c1", any(), capture(current), any()) }
        assertEquals("土豆警告", StructuredMemory.decode(current.captured).insideJoke)
    }

    @Test
    fun 角色已不在_返回false_不调模型不写库() = runBlocking {
        coEvery { characterDao.getByUuid("gone") } returns null
        assertFalse(coordinator.extractAndPersist("gone", config, "阿哲"))
        coVerify(exactly = 0) { structuredMemoryService.extractMemory(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { characterDao.updateStructuredMemoryMetadata(any(), any()) }
    }
}
