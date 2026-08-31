package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.entity.ConversationEntity
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 记忆整理记账走定向列 UPDATE（图纸 2026-09-01「记忆与防污染加固批」件⑥·PITFALLS §1b）。
 * 断言从规格独立反推：成功/失败各走各自的定向 UPDATE，且**绝不**再读快照整行回写——
 * 「读快照→慢操作→整行 upsert」会把并发列（mood 三件/voiceRounds/lastMessage 快照）打回旧值。
 */
class ConversationMemorySummaryRecordTest {

    private val dao = mockk<ConversationDao>(relaxed = true)
    private val repo = ConversationRepository(dao)

    @Test fun success_usesTargetedUpdate_andNeverRewritesWholeRow() = runBlocking {
        repo.recordMemorySummaryResult("conv-1", success = true, now = 1_700_000_000_000L)
        coVerify(exactly = 1) { dao.recordMemorySummarySuccess("conv-1", 1_700_000_000_000L) }
        coVerify(exactly = 0) { dao.recordMemorySummaryFailure(any(), any()) }
        coVerify(exactly = 0) { dao.upsert(any<ConversationEntity>()) }
        coVerify(exactly = 0) { dao.update(any<ConversationEntity>()) }
        coVerify(exactly = 0) { dao.getByUuid(any()) }
    }

    @Test fun failure_usesTargetedUpdate_andNeverRewritesWholeRow() = runBlocking {
        repo.recordMemorySummaryResult("conv-2", success = false, now = 42L)
        coVerify(exactly = 1) { dao.recordMemorySummaryFailure("conv-2", 42L) }
        coVerify(exactly = 0) { dao.recordMemorySummarySuccess(any(), any()) }
        coVerify(exactly = 0) { dao.upsert(any<ConversationEntity>()) }
        coVerify(exactly = 0) { dao.update(any<ConversationEntity>()) }
        coVerify(exactly = 0) { dao.getByUuid(any()) }
    }
}
