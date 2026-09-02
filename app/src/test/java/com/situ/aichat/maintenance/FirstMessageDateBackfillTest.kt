package com.situ.aichat.maintenance

import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.CharacterFirstMessageRow
import com.situ.aichat.data.local.dao.MessageDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 相识天数图纸 §6.2 · T2（E1/E3/E16/E18）：[FirstMessageDateBackfill.run] 编排——
 * 逐行调只往早改写口、返回真写回行数、空表零 UPDATE、单角色抛异常不拖垮整批。
 * 断言从图纸 §4.1 锁定算法独立反推。
 */
class FirstMessageDateBackfillTest {

    private lateinit var messageDao: MessageDao
    private lateinit var characterDao: CharacterDao
    private lateinit var backfill: FirstMessageDateBackfill

    @Before
    fun setUp() {
        // 生产类无条件打观测行（D-10），纯 JVM 须假掉 android.util.Log 否则「Method not mocked」中断。
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        messageDao = mockk()
        characterDao = mockk()
        backfill = FirstMessageDateBackfill(messageDao, characterDao)
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `逐行只往早改_返回真写回行数`() = runBlocking {
        coEvery { messageDao.earliestNonEmptyTimestampByCharacter() } returns listOf(
            CharacterFirstMessageRow("A", 120L),
            CharacterFirstMessageRow("B", 500L),
        )
        coEvery { characterDao.markFirstMessageDate("A", 120L) } returns 1
        coEvery { characterDao.markFirstMessageDate("B", 500L) } returns 0 // B 已有更早值 → 零行

        assertEquals("只数真改动的行", 1, backfill.run())
        coVerify(exactly = 1) { characterDao.markFirstMessageDate("A", 120L) }
        coVerify(exactly = 1) { characterDao.markFirstMessageDate("B", 500L) }
        // R1 🔵-4：D-10 的观测行是验收人区分「跑过但没人要改」与「压根没跑」的唯一信号，逐字钉住。
        verify(exactly = 1) { Log.i("FirstMessageDate", "首聊时间补账：扫 2 个有消息的角色，写回 1") }
    }

    @Test
    fun `零角色_零UPDATE_返回0`() = runBlocking {
        coEvery { messageDao.earliestNonEmptyTimestampByCharacter() } returns emptyList()

        assertEquals(0, backfill.run())
        coVerify(exactly = 0) { characterDao.markFirstMessageDate(any(), any()) }
    }

    @Test
    fun `单角色写失败_跳过后续照写`() = runBlocking {
        coEvery { messageDao.earliestNonEmptyTimestampByCharacter() } returns listOf(
            CharacterFirstMessageRow("A", 120L),
            CharacterFirstMessageRow("B", 500L),
        )
        coEvery { characterDao.markFirstMessageDate("A", 120L) } throws RuntimeException("db boom")
        coEvery { characterDao.markFirstMessageDate("B", 500L) } returns 1

        assertEquals("A 炸了只丢它自己，B 照写", 1, backfill.run())
        coVerify(exactly = 1) { characterDao.markFirstMessageDate("B", 500L) }
    }
}
