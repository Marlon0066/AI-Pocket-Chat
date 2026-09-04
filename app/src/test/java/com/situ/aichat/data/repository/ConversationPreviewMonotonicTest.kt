package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.ConversationDao
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * T2-12（图纸 2026-09-04 §5 E12）：会话预览快照的**两个口**各走各的 DAO 语句，互不串味。
 *
 * - [ConversationRepository.recordLastMessage]（16 个既有调用点·含「删消息后重算预览」）恒**无条件**覆写——
 *   重算路径会写**更早**的 timestamp，给公共方法加单调条件会让删消息后列表预览不刷新（图纸 F12）。
 * - [ConversationRepository.recordLastMessageIfNewer]（仅 AI 递送收尾一处）走条件 UPDATE。
 *
 * 断言从规格独立反推：只钉「哪个口走哪条语句 + 绝不走另一条」，SQL 条件本身的真值由真 SQLite 的
 * `ConversationPreviewMonotonicDaoTest`（androidTest）覆盖。
 */
class ConversationPreviewMonotonicTest {

    private val dao = mockk<ConversationDao>(relaxed = true)
    private val repo = ConversationRepository(dao)

    @Test fun 旧方法恒无条件覆写_删消息后重算写更早ts不被拦() = runBlocking {
        repo.recordLastMessage("conv-1", "上一条", "user", 100L)
        coVerify(exactly = 1) { dao.updateLastMessageSnapshot("conv-1", "上一条", "user", 100L) }
        coVerify(exactly = 0) { dao.updateLastMessageSnapshotIfNewer(any(), any(), any(), any()) }
    }

    @Test fun 单调方法走条件语句_不碰无条件语句() = runBlocking {
        repo.recordLastMessageIfNewer("conv-1", "她说的话", "assistant", 300L)
        coVerify(exactly = 1) { dao.updateLastMessageSnapshotIfNewer("conv-1", "她说的话", "assistant", 300L) }
        coVerify(exactly = 0) { dao.updateLastMessageSnapshot(any(), any(), any(), any()) }
    }
}
