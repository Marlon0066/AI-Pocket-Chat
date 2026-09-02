package com.situ.aichat.data.backup

import com.situ.aichat.maintenance.FirstMessageDateBackfill
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 相识天数图纸 §6.2 · T2（E7）：备份恢复后的「第一次聊天时间」补账钩子——
 * 只在 [ImportResult.Success] 后跑一次，[ImportResult.Error] 不跑，补账抛异常不改导入结果。
 * 两个导入出口（旧 .json `import` / zip `importArchive`）各一组。
 */
class BackupServiceBackfillHookTest {

    private lateinit var importer: BackupImporter
    private lateinit var backfill: FirstMessageDateBackfill
    private lateinit var service: BackupService

    private val success = ImportResult.Success(imported = 1, overwritten = 0, duplicated = 0, skipped = 0, messages = 10)

    @Before
    fun setUp() {
        importer = mockk()
        backfill = mockk()
        service = BackupService(exporter = mockk(), importer = importer, firstMessageDateBackfill = backfill)
    }

    @Test
    fun `json导入成功_补账跑一次且结果原样`() = runBlocking {
        coEvery { importer.import(any()) } returns success
        coEvery { backfill.run() } returns 3

        assertEquals(success, service.import("{}"))
        coVerify(exactly = 1) { backfill.run() }
    }

    @Test
    fun `json导入失败_不补账`() = runBlocking {
        val error = ImportResult.Error("坏包")
        coEvery { importer.import(any()) } returns error
        coEvery { backfill.run() } returns 0

        assertEquals(error, service.import("{}"))
        coVerify(exactly = 0) { backfill.run() }
    }

    @Test
    fun `补账抛异常_导入结果不受影响`() = runBlocking {
        coEvery { importer.import(any()) } returns success
        coEvery { backfill.run() } throws RuntimeException("boom")

        assertEquals("补账炸了仍返回原 Success", success, service.import("{}"))
    }

    @Test
    fun `zip恢复成功_补账跑一次且结果原样`() = runBlocking {
        coEvery { importer.importArchive(any(), any(), any()) } returns success
        coEvery { backfill.run() } returns 2

        assertEquals(success, service.importArchive(BackupByteSource.fromBytes(byteArrayOf(1))))
        coVerify(exactly = 1) { backfill.run() }
    }

    /** R1 🔵-4：`importArchive` 的异常分支与 `import` 同形，但原先只有 json 那条有例。 */
    @Test
    fun `zip恢复_补账抛异常_导入结果不受影响`() = runBlocking {
        coEvery { importer.importArchive(any(), any(), any()) } returns success
        coEvery { backfill.run() } throws RuntimeException("boom")

        assertEquals(
            "补账炸了仍返回原 Success",
            success,
            service.importArchive(BackupByteSource.fromBytes(byteArrayOf(1))),
        )
    }

    @Test
    fun `zip恢复失败_不补账`() = runBlocking {
        val error = ImportResult.Error("非 zip")
        coEvery { importer.importArchive(any(), any(), any()) } returns error
        coEvery { backfill.run() } returns 0

        assertEquals(error, service.importArchive(BackupByteSource.fromBytes(byteArrayOf(1))))
        coVerify(exactly = 0) { backfill.run() }
    }
}
