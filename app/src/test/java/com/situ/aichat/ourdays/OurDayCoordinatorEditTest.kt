package com.situ.aichat.ourdays

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * T2-1 下半（卷一图纸 §7.2·从 [OurDayCoordinatorTest] 只搬不改拆出控行数）：卷三经协调器的用户编辑口
 * `regenerate` / `refreshFacts` / `saveUserNote` / `setHidden` / `markDeleted` + catch-up × regenerate 同角色并发串行（E22）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OurDayCoordinatorEditTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 9, 2)
    private val yesterday: LocalDate = today.minusDays(1)
    private val C = "char-1"

    private fun harness(nickname: String? = null) = OurDayHarness(zone, startMillis = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), nickname = nickname)

    @Test
    fun regenerate_忽略failed与手改_清attempts_deleted复位() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L); h.chat(C, h.at(yesterday, 20))
        h.dao.upsert(com.situ.aichat.data.local.entity.OurDayEntity(uuid = "r", characterUuid = C, dayKey = "2026-09-01", noteStatus = "failed", noteAttempts = 3, noteEdited = true, deleted = true, createdAtMillis = 1, updatedAtMillis = 1))
        assertTrue(h.coordinator.regenerate(C, "2026-09-01"))
        val row = h.dao.rows["r"]!!
        assertEquals(1, h.llmCalls.size)
        assertFalse(row.deleted); assertEquals("ok", row.noteStatus); assertEquals(0, row.noteAttempts); assertEquals(OurDayHarness.OK_NOTE, row.note)
        assertEquals("事实已重算", 1, row.messageCount)
        // 失败：attempts 仍为 0（手动重写不计自动 attempts）
        h.llmAnswer = { "垃圾" }
        assertFalse(h.coordinator.regenerate(C, "2026-09-01"))
        assertEquals(0, h.dao.rows["r"]!!.noteAttempts); assertEquals("none", h.dao.rows["r"]!!.noteStatus)
        assertFalse("行不存在 ⇒ false", h.coordinator.regenerate(C, "2026-08-01"))
        assertEquals(2, h.llmCalls.size)
    }

    @Test
    fun refreshFacts_只更事实_不存在且有互动建行无手记_今天不写() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L)
        h.chat(C, h.at(yesterday, 20)); h.chat(C, h.at(today.minusDays(2), 20)); h.chat(C, h.at(today, 8))
        h.dao.upsert(com.situ.aichat.data.local.entity.OurDayEntity(uuid = "r", characterUuid = C, dayKey = "2026-09-01", note = "旧手记", noteStatus = "ok", messageCount = 99, createdAtMillis = 1, updatedAtMillis = 1))
        val refreshed = h.coordinator.refreshFacts(C, "2026-09-01")!!
        assertEquals(1, refreshed.messageCount); assertEquals("旧手记", refreshed.note); assertEquals(0, h.llmCalls.size)
        val created = h.coordinator.refreshFacts(C, "2026-08-31")!!
        assertEquals("", created.note); assertEquals("none", created.noteStatus); assertEquals(1, created.messageCount)
        assertNull("今天恒不写", h.coordinator.refreshFacts(C, "2026-09-02"))
        assertNull("无互动日不建行", h.coordinator.refreshFacts(C, "2026-08-20"))
        assertEquals(0, h.llmCalls.size)
    }

    @Test
    fun saveUserNote_setHidden_markDeleted_行不存在为noop() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L)
        h.dao.upsert(com.situ.aichat.data.local.entity.OurDayEntity(uuid = "r", characterUuid = C, dayKey = "2026-09-01", noteStatus = "failed", noteAttempts = 3, createdAtMillis = 1, updatedAtMillis = 1))
        h.clock.set(h.clock.millis() + 1000)
        h.coordinator.saveUserNote(C, "2026-09-01", "我的手记", "我的事实行")
        var row = h.dao.rows["r"]!!
        assertTrue(row.noteEdited); assertEquals("ok", row.noteStatus); assertEquals("我的手记", row.note); assertEquals(h.clock.millis(), row.generatedAt)
        h.coordinator.setHidden(C, "2026-09-01", true); assertTrue(h.dao.rows["r"]!!.hiddenFromMemory)
        h.coordinator.markDeleted(C, "2026-09-01")
        row = h.dao.rows["r"]!!
        assertTrue(row.deleted); assertEquals("", row.note); assertEquals("none", row.noteStatus)
        h.coordinator.saveUserNote(C, "2026-01-01", "x", "y"); h.coordinator.setHidden(C, "2026-01-01", true); h.coordinator.markDeleted(C, "2026-01-01")
        assertEquals(1, h.dao.rows.size)
    }

    @Test
    fun catchUp与regenerate同角色并发_串行_E22() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L); h.chat(C, h.at(yesterday, 20))
        val gate = CompletableDeferred<Unit>()
        var firstCall = true
        h.llmAnswer = { if (firstCall) { firstCall = false; gate.await() }; OurDayHarness.OK_JSON }
        var regenerateDone: Boolean? = null
        val j1 = launch { h.coordinator.catchUp() }
        runCurrent() // catchUp 建行后停在 LLM 门里（持锁）
        assertEquals(1, h.llmCalls.size); assertEquals(1, h.rowsOf(C).size)
        val j2 = launch { regenerateDone = h.coordinator.regenerate(C, "2026-09-01") }
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals("regenerate 排队在同一把锁上：LLM 仍只被调过 1 次", 1, h.llmCalls.size)
        assertNull("regenerate 尚未完成", regenerateDone)
        gate.complete(Unit)
        advanceUntilIdle(); j1.join(); j2.join()
        assertEquals(true, regenerateDone)
        assertEquals("两条路各一次调用·串行无交叉", 2, h.llmCalls.size)
        assertEquals(1, h.rowsOf(C).size)
        assertEquals("regenerate 读到最新行并覆盖：attempts 清 0", 0, h.rowsOf(C).single().noteAttempts)
        assertEquals("ok", h.rowsOf(C).single().noteStatus)
    }
}
