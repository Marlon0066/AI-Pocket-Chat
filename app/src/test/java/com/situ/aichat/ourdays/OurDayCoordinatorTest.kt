package com.situ.aichat.ourdays

import androidx.work.ExistingWorkPolicy
import com.situ.aichat.work.OurDayCatchUpWorker
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * T2-1（卷一图纸 §7.2）：协调器行为测试——真协调器 + 真手记服务 + 假 DAO / 假 LLM / MutableClock（[OurDayHarness]）。
 * 断言从图纸 §3.3 算法与 §5 E1 E3 E6 E8 E9 E15 E22–E25 独立反推。`runTest` 让页间 300ms 为虚拟时间。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OurDayCoordinatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 9, 2)
    private val yesterday: LocalDate = today.minusDays(1)
    private val C = "char-1"

    private fun harness(nickname: String? = null) = OurDayHarness(zone, startMillis = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), nickname = nickname)

    @Test
    fun 昨天有互动_恰一次调用_一次upsert_一次手记写回() = runTest {
        val h = harness()
        h.addCharacter(C, backfilledAt = 1L)
        h.chat(C, h.at(yesterday, 20)); h.chat(C, h.at(yesterday, 20, 5), role = "assistant")
        val r = h.coordinator.catchUp()
        assertEquals(1, h.llmCalls.size)
        assertEquals(1, h.dao.upserts); assertEquals(1, h.dao.noteUpdates); assertEquals(0, h.dao.attemptUpdates)
        assertEquals(OurDayCoordinator.CatchUpResult(written = 1, failed = 0, hasMore = false), r)
        val row = h.rowsOf(C).single()
        assertEquals("2026-09-01", row.dayKey); assertEquals("ok", row.noteStatus); assertEquals(1, row.noteAttempts)
        assertEquals(OurDayHarness.OK_NOTE, row.note); assertEquals("林晚和小明聊了考试，约好周末去看海", row.factLine)
        assertEquals(2, row.messageCount); assertFalse(row.noteEdited)
    }

    @Test
    fun 重跑零调用_幂等() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L); h.chat(C, h.at(yesterday, 20))
        h.coordinator.catchUp(); h.coordinator.catchUp(); h.coordinator.catchUp()
        assertEquals(1, h.llmCalls.size); assertEquals(1, h.dao.upserts); assertEquals(1, h.rowsOf(C).size)
    }

    @Test
    fun 今天不写_跨零点只写昨天_E1() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L)
        h.chat(C, h.at(yesterday, 23, 58)); h.chat(C, h.at(today, 0, 3)); h.chat(C, h.at(today, 8, 30))
        h.coordinator.catchUp()
        assertEquals(listOf("2026-09-01"), h.rowsOf(C).map { it.dayKey })
        assertEquals(1, h.llmCalls.size)
        assertEquals("只有昨天那一条进素材", 1, h.rowsOf(C).single().messageCount)
    }

    /**
     * E6 按图纸 §3.3 字面：手改 / 墓碑 / ok 行**不是候选**（过滤式 `row == null || (none ∧ attempts<3 ∧ !deleted ∧ !noteEdited)`），
     * catch-up 对它们零触碰——手记列零碰、零 LLM、零 upsert；事实层刷新交 `refreshFacts`（卷三日页打开）。
     * 图纸 T2-1 原文「只 updateFacts」与 §3.3 候选过滤互斥 ⇒ 施工日志 D-1 留复核裁决；processDay 内的同名守卫保留为纵深防御。
     */
    @Test
    fun 手改行_墓碑行_ok行_非候选_手记零碰零LLM_E6() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L)
        val d1 = yesterday; val d2 = today.minusDays(2); val d3 = today.minusDays(3)
        listOf(d1, d2, d3).forEach { h.chat(C, h.at(it, 20)) }
        suspend fun seed(uuid: String, d: LocalDate, f: (com.situ.aichat.data.local.entity.OurDayEntity) -> com.situ.aichat.data.local.entity.OurDayEntity) {
            h.dao.upsert(f(com.situ.aichat.data.local.entity.OurDayEntity(uuid = uuid, characterUuid = C, dayKey = OurDayKey.keyOf(d), note = "旧手记", factLine = "旧事实行", createdAtMillis = 1, updatedAtMillis = 1)))
        }
        seed("e", d1) { it.copy(noteEdited = true, noteStatus = "ok") }
        seed("del", d2) { it.copy(deleted = true, note = "", factLine = "") }
        seed("ok", d3) { it.copy(noteStatus = "ok") }
        h.dao.upserts = 0
        h.coordinator.catchUp()
        assertEquals(0, h.llmCalls.size); assertEquals(0, h.dao.upserts); assertEquals(0, h.dao.noteUpdates); assertEquals(0, h.dao.attemptUpdates)
        assertEquals("非候选行 catch-up 零触碰（D-1·§3.3 字面）", 0, h.dao.factsUpdates)
        assertEquals("旧手记", h.dao.rows["e"]!!.note); assertTrue(h.dao.rows["e"]!!.noteEdited)
        assertTrue(h.dao.rows["del"]!!.deleted); assertEquals("", h.dao.rows["del"]!!.note)
        assertEquals("旧事实行", h.dao.rows["ok"]!!.factLine); assertEquals("ok", h.dao.rows["ok"]!!.noteStatus)
        // 事实层刷新走 refreshFacts：手记列仍零碰
        h.coordinator.refreshFacts(C, OurDayKey.keyOf(d1))
        assertEquals(1, h.dao.factsUpdates); assertEquals(1, h.dao.rows["e"]!!.messageCount); assertEquals("旧手记", h.dao.rows["e"]!!.note)
        assertEquals(0, h.llmCalls.size)
    }

    @Test
    fun 失败三次转failed_第四次不再调用_E8() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L); h.chat(C, h.at(yesterday, 20))
        h.llmAnswer = { "这不是 JSON" }
        h.coordinator.catchUp()
        assertEquals(1, h.llmCalls.size); assertEquals("none", h.rowsOf(C).single().noteStatus); assertEquals(1, h.rowsOf(C).single().noteAttempts)
        h.coordinator.catchUp()
        assertEquals(2, h.llmCalls.size); assertEquals("none", h.rowsOf(C).single().noteStatus)
        val r3 = h.coordinator.catchUp()
        assertEquals(3, h.llmCalls.size); assertEquals("failed", h.rowsOf(C).single().noteStatus); assertEquals(3, h.rowsOf(C).single().noteAttempts)
        assertEquals(OurDayCoordinator.CatchUpResult(0, 1, false), r3)
        h.coordinator.catchUp()
        assertEquals("第 4 次不再调用", 3, h.llmCalls.size)
        assertEquals("行仍在、事实仍更新", 1, h.rowsOf(C).size)
    }

    @Test
    fun 截断视同失败_计一次attempt() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L); h.chat(C, h.at(yesterday, 20))
        h.finishReason = "length"
        h.coordinator.catchUp()
        assertEquals(1, h.llmCalls.size); assertEquals(1, h.rowsOf(C).single().noteAttempts); assertEquals("none", h.rowsOf(C).single().noteStatus)
    }

    @Test
    fun none且attempts为2_仍是候选_成功后ok_E23() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L); h.chat(C, h.at(yesterday, 20))
        h.dao.upsert(com.situ.aichat.data.local.entity.OurDayEntity(uuid = "r", characterUuid = C, dayKey = "2026-09-01", noteAttempts = 2, createdAtMillis = 1, updatedAtMillis = 1))
        h.dao.upserts = 0
        h.coordinator.catchUp()
        assertEquals(1, h.llmCalls.size); assertEquals(0, h.dao.upserts)
        assertEquals("ok", h.dao.rows["r"]!!.noteStatus); assertEquals(3, h.dao.rows["r"]!!.noteAttempts)
    }

    @Test
    fun 无API_零调用_flag置true_有配置后清false_E9() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L); h.chat(C, h.at(yesterday, 20))
        h.config = null
        val r = h.coordinator.catchUp()
        assertEquals(0, h.llmCalls.size); assertEquals(0, h.dao.upserts)
        assertTrue(OurDayApiMissingFlag.get(RuntimeEnvironment.getApplication()))
        assertEquals(OurDayCoordinator.CatchUpResult(0, 0, false), r)
        assertTrue("无 API 不置回填标记", h.backfillMarks.isEmpty())
        h.config = com.situ.aichat.data.remote.llm.ApiConfigValues(providerType = com.situ.aichat.data.model.ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k", baseUrl = "https://x", modelName = "m")
        h.coordinator.catchUp()
        assertFalse(OurDayApiMissingFlag.get(RuntimeEnvironment.getApplication()))
        assertEquals(1, h.llmCalls.size)
    }

    @Test
    fun 标记非null_只看近7天_8天前不处理_7天前处理() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L)
        h.chat(C, h.at(today.minusDays(8), 20)); h.chat(C, h.at(today.minusDays(7), 20)); h.chat(C, h.at(yesterday, 20))
        h.coordinator.catchUp()
        assertEquals(listOf("2026-08-26", "2026-09-01"), h.rowsOf(C).map { it.dayKey })
        assertEquals(2, h.llmCalls.size)
        assertTrue("标记已非 null ⇒ 不再置位", h.backfillMarks.isEmpty())
    }

    @Test
    fun 标记null_全史降序处理_耗尽后置位恰一次_E25() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = null)
        listOf(20L, 15L, 9L, 3L, 1L).forEach { h.chat(C, h.at(today.minusDays(it), 20)) }
        val r = h.coordinator.catchUp()
        assertEquals(5, h.llmCalls.size)
        val order = h.llmCalls.map { call -> call.second.first { it.role == "user" }.content!!.lines().first() }
        assertEquals(
            listOf("【日期】2026年9月1日 周二", "【日期】2026年8月30日 周日", "【日期】2026年8月24日 周一", "【日期】2026年8月18日 周二", "【日期】2026年8月13日 周四"),
            order,
        )
        assertEquals(listOf(C to h.clock.millis()), h.backfillMarks)
        assertFalse(r.hasMore)
        assertTrue("置位后进度条目移除", h.coordinator.backfillProgress.value.isEmpty())
        h.coordinator.catchUp()
        assertEquals("置位恰一次", 1, h.backfillMarks.size)
    }

    @Test
    fun 三十一候选_三十次调用_hasMore_自排续跑恰一次_第二轮补齐并置位_E24() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = null)
        (1L..31L).forEach { h.chat(C, h.at(today.minusDays(it), 20)) }
        val r1 = h.coordinator.catchUp()
        assertEquals(30, h.llmCalls.size); assertTrue(r1.hasMore); assertEquals(30, r1.written)
        assertTrue("未耗尽不置位", h.backfillMarks.isEmpty())
        verify(exactly = 1) {
            h.backgroundScheduler.scheduleOneShot(
                uniqueName = OurDayCatchUpWorker.UNIQUE_CONTINUE, workerClass = OurDayCatchUpWorker::class.java,
                initialDelay = Duration.ofSeconds(60), requireNetwork = true, existingPolicy = ExistingWorkPolicy.REPLACE,
            )
        }
        assertEquals(OurDayCoordinator.BackfillProgress(30, 31), h.coordinator.backfillProgress.value[C])
        val r2 = h.coordinator.catchUp()
        assertEquals(31, h.llmCalls.size); assertFalse(r2.hasMore); assertEquals(1, h.backfillMarks.size)
        assertEquals(31, h.rowsOf(C).size)
        verify(exactly = 1) { h.backgroundScheduler.scheduleOneShot(any(), any<Class<OurDayCatchUpWorker>>(), any(), any(), any(), any()) }
    }

    @Test
    fun 角色中途删除_跳过剩余候选_不置位_E3() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = null)
        (1L..3L).forEach { h.chat(C, h.at(today.minusDays(it), 20)) }
        h.llmAnswer = { h.characters.clear(); OurDayHarness.OK_JSON } // 第一次调用期间角色被删
        h.coordinator.catchUp()
        assertEquals(1, h.llmCalls.size)
        assertEquals(1, h.rowsOf(C).size)
        assertTrue(h.backfillMarks.isEmpty())
        assertTrue("角色已删 ⇒ 进度条目消失", h.coordinator.backfillProgress.value.isEmpty())
    }

    @Test
    fun 观测行格式_只打计数() = runTest {
        ShadowLog.clear()
        val h = harness(); h.addCharacter(C, backfilledAt = 1L); h.chat(C, h.at(yesterday, 20))
        h.coordinator.catchUp()
        val lines = ShadowLog.getLogsForTag("OurDays").map { it.msg }
        assertTrue(lines.toString(), lines.contains("OurDays: catchUp 角色=1 候选=1 写成=1 失败=0 剩余=0 回填置位=0"))
        assertTrue("手记正文不进日志", lines.none { it.contains(OurDayHarness.OK_NOTE.take(10)) })
    }

    @Test
    fun 无角色_观测行角色0候选0_E15() = runTest {
        ShadowLog.clear()
        val h = harness()
        assertEquals(OurDayCoordinator.CatchUpResult(0, 0, false), h.coordinator.catchUp())
        assertTrue(ShadowLog.getLogsForTag("OurDays").map { it.msg }.contains("OurDays: catchUp 角色=0 候选=0 写成=0 失败=0 剩余=0 回填置位=0"))
        assertEquals(0, h.llmCalls.size)
    }

    @Test
    fun 新装无史_候选0_立即置位_零LLM_E25() = runTest {
        ShadowLog.clear()
        val h = harness(); h.addCharacter(C, backfilledAt = null)
        h.coordinator.catchUp()
        assertEquals(0, h.llmCalls.size); assertEquals(listOf(C to h.clock.millis()), h.backfillMarks)
        assertTrue(ShadowLog.getLogsForTag("OurDays").map { it.msg }.contains("OurDays: catchUp 角色=1 候选=0 写成=0 失败=0 剩余=0 回填置位=1"))
    }

    @Test
    fun 只有system消息的一天_无行零调用_Z3() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L)
        h.chat(C, h.at(yesterday, 20), content = "系统提示", role = "system")
        h.coordinator.catchUp()
        assertEquals(0, h.llmCalls.size); assertTrue(h.rowsOf(C).isEmpty())
    }

    /** R1 🔵-2（O-1）：没进 LLM 的候选日（只有 system 消息）不吃 30 页预算——31 个候选里 1 个只有 system 消息 ⇒ 30 次调用、不续跑、当轮置位。 */
    @Test
    fun 只有system消息的候选日不吃预算_31候选含1空日_一轮跑完置位() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = null)
        (1L..30L).forEach { h.chat(C, h.at(today.minusDays(it), 20)) }
        h.chat(C, h.at(today.minusDays(31), 20), content = "系统提示", role = "system")
        val r = h.coordinator.catchUp()
        assertEquals(30, h.llmCalls.size); assertFalse(r.hasMore); assertEquals(30, h.rowsOf(C).size)
        assertEquals("空日不建行、不算失败", OurDayCoordinator.CatchUpResult(30, 0, false), r)
        assertEquals("候选耗尽当轮置位", 1, h.backfillMarks.size)
        verify(exactly = 0) { h.backgroundScheduler.scheduleOneShot(any(), any<Class<OurDayCatchUpWorker>>(), any(), any(), any(), any()) }
    }

    @Test
    fun 每页时间戳取LLM后时刻() = runTest {
        val h = harness(); h.addCharacter(C, backfilledAt = 1L); h.chat(C, h.at(yesterday, 20))
        val before = h.clock.millis()
        h.llmAnswer = { h.clock.advance(120_000); OurDayHarness.OK_JSON }
        h.coordinator.catchUp()
        val row = h.rowsOf(C).single()
        assertEquals(before + 120_000, row.generatedAt); assertEquals(before + 120_000, row.updatedAtMillis)
        assertEquals("行创建在 LLM 前", before, row.createdAtMillis)
    }

    @Test
    fun 昵称双轨进提示词_E27() = runTest {
        val h = harness(nickname = " 小明 "); h.addCharacter(C, backfilledAt = 1L); h.chat(C, h.at(yesterday, 20))
        h.coordinator.catchUp()
        val system = h.llmCalls.single().second.first { it.role == "system" }.content!!
        assertTrue(system.contains("对方直接称「小明」。")); assertTrue(system.contains("「林晚」和「小明」。"))
        val h2 = harness(nickname = null); h2.addCharacter(C, backfilledAt = 1L); h2.chat(C, h2.at(yesterday, 20))
        h2.coordinator.catchUp()
        val system2 = h2.llmCalls.single().second.first { it.role == "system" }.content!!
        assertTrue(system2.contains("对方直接称「你」。")); assertTrue(system2.contains("「林晚」和「用户」。"))
        assertNotNull(h2.rowsOf(C).single().note)
    }

    @Test
    fun 多角色按creationDate升序_同轮共享30页预算_V5() = runTest {
        val h = harness()
        h.addCharacter("b", name = "乙", creationDate = 200L, backfilledAt = null)
        h.addCharacter("a", name = "甲", creationDate = 100L, backfilledAt = null)
        (1L..20L).forEach { h.chat("a", h.at(today.minusDays(it), 20)); h.chat("b", h.at(today.minusDays(it), 20)) }
        val r = h.coordinator.catchUp()
        assertEquals(30, h.llmCalls.size); assertTrue(r.hasMore)
        assertEquals("甲先跑满 20 页", "甲", h.llmCalls[0].first); assertEquals("甲", h.llmCalls[19].first); assertEquals("乙", h.llmCalls[20].first)
        assertEquals("甲耗尽置位、乙未耗尽不置位", listOf("a"), h.backfillMarks.map { it.first })
        h.coordinator.catchUp()
        assertEquals(40, h.llmCalls.size); assertEquals(listOf("a", "b"), h.backfillMarks.map { it.first })
    }

}
