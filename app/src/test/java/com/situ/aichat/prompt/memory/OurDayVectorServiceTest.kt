package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.OurDayDao
import com.situ.aichat.data.local.entity.OurDayEntity
import com.situ.aichat.prompt.growth.MutableClock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * T1-4（「我们的日子」卷二图纸 §7.2·照 [MeetingArchiveVectorServiceTest]）：断言从 §3.6 规格独立反推（MockK 假掉 dao / embedder·
 * [MutableClock] 钉 2026-09-02 12:00 上海）：
 * - [OurDayVectorService.embedSource] = trim 后事实行；
 * - [OurDayVectorService.retrieval] 阈值 / 维度不符 / 窗口排除（起日及之后不出·之前出·null 不排除）/ 今天不出 / 坏向量跳过 / 排除按 clock.zone；
 * - [OurDayVectorService.backfillMissing] 空批退 / 不可用即停 / 批推进 / 嵌入失败即停。
 */
class OurDayVectorServiceTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun millis(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()
    private val clock = MutableClock(millis(LocalDateTime.of(2026, 9, 2, 12, 0)), zone)

    private val dao = mockk<OurDayDao>(relaxed = true)
    private val embedder = mockk<TextEmbedder>()
    private val service = OurDayVectorService(dao, embedder, clock)

    private val queryVec = floatArrayOf(1f, 0f, 0f, 0f)
    private fun sameDir() = VectorMemoryService.serializeEmbedding(floatArrayOf(1f, 0f, 0f, 0f))
    private fun orthogonal() = VectorMemoryService.serializeEmbedding(floatArrayOf(0f, 1f, 0f, 0f))
    private fun wrongDim() = VectorMemoryService.serializeEmbedding(floatArrayOf(1f, 0f))

    private fun row(dayKey: String, factLine: String = "林晚和阿澄聊了$dayKey", embedding: ByteArray? = sameDir()) = OurDayEntity(
        uuid = "u-$dayKey", characterUuid = "c", dayKey = dayKey, factLine = factLine, embedding = embedding,
        createdAtMillis = 0L, updatedAtMillis = 0L,
    )

    // ── embedSource ──

    @Test fun embedSource_isTrimmedFactLine() {
        assertEquals("林晚和阿澄去了江边", service.embedSource(row("2026-08-22", factLine = "  林晚和阿澄去了江边 \n")))
    }

    // ── retrieval ──

    @Test fun retrieval_returnsDayKeyFactLineSimilarity() = runBlocking {
        coEvery { dao.embeddedForCharacter("c") } returns listOf(row("2026-08-22", factLine = "去了江边"))
        val r = service.retrieval(queryVec, "c", threshold = 0.65, windowCutoffMillis = null)
        assertEquals(1, r.candidates.size)
        assertEquals("2026-08-22", r.candidates[0].dayKey)
        assertEquals("去了江边", r.candidates[0].factLine)
        assertEquals(1.0, r.candidates[0].similarity, 1e-9)
    }

    @Test fun retrieval_belowThreshold_dropped() = runBlocking {
        coEvery { dao.embeddedForCharacter("c") } returns listOf(row("2026-08-22", embedding = orthogonal()))
        assertTrue(service.retrieval(queryVec, "c", 0.65, null).candidates.isEmpty())
    }

    @Test fun retrieval_dimensionMismatch_skipped() = runBlocking {
        coEvery { dao.embeddedForCharacter("c") } returns listOf(row("2026-08-22", embedding = wrongDim()))
        assertTrue("阈值 0 也不救：维度检查在阈值之前", service.retrieval(queryVec, "c", 0.0, null).candidates.isEmpty())
    }

    @Test fun retrieval_nullOrCorruptEmbedding_skipped() = runBlocking {
        coEvery { dao.embeddedForCharacter("c") } returns listOf(row("2026-08-21", embedding = null), row("2026-08-22", embedding = byteArrayOf(1, 2, 3)))
        assertTrue(service.retrieval(queryVec, "c", 0.0, null).candidates.isEmpty())
    }

    @Test fun retrieval_windowCutoff_excludesStartDayAndAfter_keepsBefore_W8() = runBlocking {
        coEvery { dao.embeddedForCharacter("c") } returns listOf(row("2026-08-30"), row("2026-08-31"), row("2026-09-01"))
        val cutoff = millis(LocalDateTime.of(2026, 8, 31, 20, 30)) // 窗口起日 = 08-31
        val r = service.retrieval(queryVec, "c", 0.65, cutoff)
        assertEquals(listOf("2026-08-30"), r.candidates.map { it.dayKey })
    }

    @Test fun retrieval_windowCutoffNull_noExclusion_E56() = runBlocking {
        coEvery { dao.embeddedForCharacter("c") } returns listOf(row("2026-08-30"), row("2026-09-01"))
        val r = service.retrieval(queryVec, "c", 0.65, null)
        assertEquals(setOf("2026-08-30", "2026-09-01"), r.candidates.map { it.dayKey }.toSet())
    }

    @Test fun retrieval_todayNeverReturned() = runBlocking {
        coEvery { dao.embeddedForCharacter("c") } returns listOf(row("2026-09-02"), row("2026-09-01"))
        val r = service.retrieval(queryVec, "c", 0.65, null)
        assertEquals(listOf("2026-09-01"), r.candidates.map { it.dayKey })
    }

    @Test fun retrieval_cutoffConvertedInClockZone() = runBlocking {
        // 同一毫秒：上海 09-01 02:00 = UTC 08-31 18:00。clock.zone = 上海 ⇒ 起日 09-01 ⇒ 08-31 仍可出。
        coEvery { dao.embeddedForCharacter("c") } returns listOf(row("2026-08-31"), row("2026-09-01"))
        val cutoff = millis(LocalDateTime.of(2026, 9, 1, 2, 0))
        assertEquals(listOf("2026-08-31"), service.retrieval(queryVec, "c", 0.65, cutoff).candidates.map { it.dayKey })
        // 换成 UTC 时钟 ⇒ 起日 08-31 ⇒ 两行都排除。
        val utcService = OurDayVectorService(dao, embedder, MutableClock(clock.millis(), ZoneId.of("UTC")))
        assertTrue(utcService.retrieval(queryVec, "c", 0.65, cutoff).candidates.isEmpty())
    }

    // ── backfillMissing ──

    @Test fun backfill_emptyBatch_returnsWithoutWrite() = runBlocking {
        every { embedder.isAvailable } returns true
        coEvery { dao.missingEmbedding(any()) } returns emptyList()
        service.backfillMissing()
        coVerify(exactly = 0) { dao.updateEmbedding(any(), any()) }
    }

    @Test fun backfill_embedderUnavailable_stopsImmediately() = runBlocking {
        every { embedder.isAvailable } returns false
        service.backfillMissing()
        coVerify(exactly = 0) { dao.missingEmbedding(any()) }
        coVerify(exactly = 0) { dao.updateEmbedding(any(), any()) }
    }

    @Test fun backfill_processesBatchThenAdvancesToEmpty_batch16() = runBlocking {
        every { embedder.isAvailable } returns true
        every { embedder.embed(any()) } returns floatArrayOf(1f, 0f, 0f, 0f)
        val a = row("2026-08-01", embedding = null)
        val b = row("2026-08-02", embedding = null)
        coEvery { dao.missingEmbedding(any()) } returnsMany listOf(listOf(a, b), emptyList())
        service.backfillMissing()
        coVerify(exactly = 1) { dao.updateEmbedding("u-2026-08-01", any()) }
        coVerify(exactly = 1) { dao.updateEmbedding("u-2026-08-02", any()) }
        coVerify(exactly = 2) { dao.missingEmbedding(16) }
    }

    @Test fun backfill_embedFailure_stopsWithoutWrite() = runBlocking {
        every { embedder.isAvailable } returns true
        every { embedder.embed(any()) } returns null
        coEvery { dao.missingEmbedding(any()) } returns listOf(row("2026-08-01", embedding = null))
        service.backfillMissing()
        coVerify(exactly = 0) { dao.updateEmbedding(any(), any()) }
        coVerify(exactly = 1) { dao.missingEmbedding(any()) } // 失败即停，不再取下一批
    }

    @Test fun clearAll_delegatesToDao() = runBlocking {
        coEvery { dao.clearAllEmbeddings() } returns 7
        assertEquals(7, service.clearAll())
    }
}
