package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.entity.CharacterEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动编辑写回（图纸 2026-09-01 件③·T2-4·E12/E16）。
 *
 * 断言从规格独立反推：锁内**重读**库内现值与 baseline 比对——不一致返 Conflict 绝不静默覆盖；
 * force 才覆盖；旧值必须进 previousMemorySummary；角色没了返 CharacterGone 不写库。
 * ⚠️ MockK 教训（记忆护栏卷）：锁内那次 getByUuid 必须显式打桩，relaxed 的默认返回会让断言走空。
 */
class MemoryManualEditTest {

    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val memoryService = mockk<MemoryService>(relaxed = true)
    private val coordinator = MemorySummaryCoordinator(memoryService, characterDao)

    private val uuid = "char-1"

    private fun character(memory: String) =
        CharacterEntity(uuid = uuid, name = "角色", creationDate = 0L, memorySummary = memory)

    @Test
    fun save_writesNewMemory_andKeepsOldAsPrevious() = runBlocking {
        coEvery { characterDao.getByUuid(uuid) } returns character("旧记忆")

        val result = coordinator.applyManualEdit(uuid, baseline = "旧记忆", newMemory = "新记忆")

        assertEquals(ManualEditResult.Saved, result)
        coVerify(exactly = 1) { characterDao.updateMemorySummary(uuid, "旧记忆", "新记忆") }
    }

    @Test
    fun baselineMismatch_returnsConflict_andWritesNothing() = runBlocking {
        // E12：编辑期间后台自动整理写回了新版本 → 绝不静默互相覆盖。
        coEvery { characterDao.getByUuid(uuid) } returns character("后台刚写的新版")

        val result = coordinator.applyManualEdit(uuid, baseline = "我进屏时看到的旧版", newMemory = "我编辑的内容")

        assertTrue(result is ManualEditResult.Conflict)
        assertEquals("后台刚写的新版", (result as ManualEditResult.Conflict).current)
        coVerify(exactly = 0) { characterDao.updateMemorySummary(any(), any(), any()) }
    }

    @Test
    fun force_overwritesEvenWhenMismatched() = runBlocking {
        // 冲突弹窗「仍然保存」：以库内现值为 previous（不是用户那份过期 baseline），历史可回溯。
        coEvery { characterDao.getByUuid(uuid) } returns character("后台刚写的新版")

        val result = coordinator.applyManualEdit(uuid, baseline = "过期 baseline", newMemory = "我编辑的内容", force = true)

        assertEquals(ManualEditResult.Saved, result)
        coVerify(exactly = 1) { characterDao.updateMemorySummary(uuid, "后台刚写的新版", "我编辑的内容") }
    }

    @Test
    fun characterDeleted_returnsGone_andWritesNothing() = runBlocking {
        // E16：编辑页开着时角色被删 → 不崩、不写库。
        coEvery { characterDao.getByUuid(uuid) } returns null

        val result = coordinator.applyManualEdit(uuid, baseline = "旧记忆", newMemory = "新记忆")

        assertEquals(ManualEditResult.CharacterGone, result)
        coVerify(exactly = 0) { characterDao.updateMemorySummary(any(), any(), any()) }
    }

    @Test
    fun manualEdit_touchesOnlyMemoryColumns() = runBlocking {
        // 只写正文：游标/冷却/触发判定/护栏解套链一概不碰（手动编辑不是一次「整理」）。
        coEvery { characterDao.getByUuid(uuid) } returns character("旧记忆")

        coordinator.applyManualEdit(uuid, baseline = "旧记忆", newMemory = "新记忆")

        coVerify(exactly = 0) { characterDao.upsert(any<CharacterEntity>()) }
        coVerify(exactly = 1) { characterDao.updateMemorySummary(any(), any(), any()) }
    }
}
