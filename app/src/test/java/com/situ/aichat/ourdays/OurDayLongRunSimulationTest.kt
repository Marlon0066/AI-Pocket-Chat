package com.situ.aichat.ourdays

import androidx.work.ExistingWorkPolicy
import com.situ.aichat.work.OurDayCatchUpWorker
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * T2-2（卷一图纸 §7.2）：**90 天三档用户假 LLM 长程模拟**——真协调器 + 真手记服务 + 假 DAO（[OurDayHarness]），
 * [com.situ.aichat.prompt.growth.MutableClock] 每天 09:00 跑一次 `catchUp`（有剩余即续跑=模拟 `our_days_continue`）。
 * 断言从图纸 §3.3 / §5 E5 E23 E24 E25 反推：调用总数 == 有互动天数 · 每日键至多一行 · 今天恒无行 · 回填分批且最终置位 · 停跑数日后 7 天窗补齐。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OurDayLongRunSimulationTest {

    private enum class Profile(val activeOn: (Int) -> Boolean) {
        HEAVY({ true }),
        MEDIUM({ it % 2 == 0 }),
        LIGHT({ it % 7 == 3 }),
    }

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day0: LocalDate = LocalDate.of(2026, 9, 3)
    private val C = "char-1"
    private val days = 90

    private class Outcome(val calls: Int, val activeDays: Int, val continueRounds: Int)

    /**
     * 跑 [days] 天：day d 09:00 catch-up（[skipRun] 为真的日子不跑），10:00 / 20:00 按档位聊天。
     * [historyDays] 天历史（day −historyDays … −1 每天聊）在 day 0 之前预埋，角色标记 null（首次回填）。
     */
    private suspend fun simulate(h: OurDayHarness, profile: Profile, historyDays: Int = 0, skipRun: (Int) -> Boolean = { false }): Outcome {
        h.addCharacter(C, backfilledAt = null)
        var active = 0
        for (d in -historyDays until 0) { h.chat(C, h.at(day0.plusDays(d.toLong()), 10)); active++ }
        var continueRounds = 0
        for (d in 0 until days) {
            val date = day0.plusDays(d.toLong())
            h.clock.set(h.at(date, 9))
            if (!skipRun(d)) {
                val results = h.catchUpUntilDone()
                continueRounds += results.size - 1
                results.forEach { r -> if (r.hasMore) assertEquals("每轮 ≤ 30 页", 30, r.written + r.failed) }
            }
            val today = OurDayKey.keyOf(date)
            assertTrue("今天恒无行 ($today)", h.rowsOf(C).none { it.dayKey == today })
            assertTrue("所有行都在今天之前", h.rowsOf(C).all { it.dayKey < today })
            if (profile.activeOn(d)) {
                h.chat(C, h.at(date, 10)); h.chat(C, h.at(date, 20), role = "assistant"); active++
            }
        }
        // 最后一天（day 89）的聊天发生在当天 09:00 跑之后 ⇒ 不计入本轮预期
        val expected = active - (if (profile.activeOn(days - 1)) 1 else 0)
        return Outcome(h.llmCalls.size, expected, continueRounds)
    }

    private fun assertInvariants(h: OurDayHarness, o: Outcome) {
        assertEquals("LLM 调用总数 == 有互动天数（今天除外）", o.activeDays, o.calls)
        val keys = h.rowsOf(C).map { it.dayKey }
        assertEquals("每日键至多一行", keys.size, keys.toSet().size)
        assertEquals("行数 == 有互动天数", o.activeDays, keys.size)
        assertTrue("全部 ok", h.rowsOf(C).all { it.noteStatus == "ok" && it.noteAttempts == 1 && it.note.isNotEmpty() })
        assertEquals("回填标记恰置位一次", 1, h.backfillMarks.size)
    }

    @Test
    fun 重度_每天聊_90天() = runTest {
        val h = OurDayHarness(zone, startMillis = 0L)
        val o = simulate(h, Profile.HEAVY)
        assertInvariants(h, o)
        assertEquals(89, o.calls) // day 0..88 各一（day 89 的聊天发生在当天 09:00 跑之后）
        assertEquals(0, o.continueRounds)
    }

    @Test
    fun 中度_隔天聊_90天() = runTest {
        val h = OurDayHarness(zone, startMillis = 0L)
        val o = simulate(h, Profile.MEDIUM)
        assertInvariants(h, o)
        assertEquals(45, o.calls) // day 0,2,…,88
    }

    @Test
    fun 轻度_每周聊_90天() = runTest {
        val h = OurDayHarness(zone, startMillis = 0L)
        val o = simulate(h, Profile.LIGHT)
        assertInvariants(h, o)
        assertEquals(13, o.calls) // day 3,10,…,87
    }

    @Test
    fun 首装带45天历史_首轮回填分两批_续跑一次_最终置位_E24_E25() = runTest {
        val h = OurDayHarness(zone, startMillis = 0L)
        val o = simulate(h, Profile.HEAVY, historyDays = 45)
        assertInvariants(h, o)
        assertEquals(45 + 89, o.calls)
        assertEquals("只有 day 0 那次需要续跑一轮（45 = 30 + 15）", 1, o.continueRounds)
        verify(exactly = 1) {
            h.backgroundScheduler.scheduleOneShot(
                uniqueName = OurDayCatchUpWorker.UNIQUE_CONTINUE, workerClass = OurDayCatchUpWorker::class.java,
                initialDelay = Duration.ofSeconds(60), requireNetwork = true, existingPolicy = ExistingWorkPolicy.REPLACE,
            )
        }
        val markDay = OurDayKey.dayKey(h.backfillMarks.single().second, zone)
        assertEquals("置位发生在 day 0", OurDayKey.keyOf(day0), markDay)
        assertEquals("最早一页是 45 天前", OurDayKey.keyOf(day0.minusDays(45)), h.rowsOf(C).first().dayKey)
    }

    @Test
    fun 停跑三天_7天窗补齐无遗漏_E5() = runTest {
        val h = OurDayHarness(zone, startMillis = 0L)
        val skipped = setOf(40, 41, 42)
        val o = simulate(h, Profile.HEAVY, skipRun = { it in skipped })
        assertInvariants(h, o)
        val keys = h.rowsOf(C).map { it.dayKey }.toSet()
        listOf(39, 40, 41, 42).forEach { d -> assertTrue("day $d 在 day 43 的 7 天窗内补齐", OurDayKey.keyOf(day0.plusDays(d.toLong())) in keys) }
        assertFalse(h.coordinator.backfillProgress.value.containsKey(C))
    }
}
