package com.situ.aichat.prompt.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MemoryService.summaryTriggerDecision` 触发判定 T1（图纸 2026-09-05 §3.2 / §7 T1-1）。
 *
 * 断言从规格独立反推（绝不照抄实现输出）：判定顺序 = ① 失败短冷却 300s → ② 用户可调下限 interval
 * → ③ 双轨（从未成功直接触发；否则「用户设的时间轨（0=不限）」OR「窗口外累积 ≥ 2×interval 条 user 消息」任一满足）。
 * 失败冷却 300_000ms 与计数轨 2×interval 为图纸 §9 锁定值——实现改动必在此撞墙。
 */
class MemorySummaryTriggerDecisionTest {

    private val now = 1_700_000_000_000L

    private fun decide(
        outsideRoundCount: Int,
        interval: Int = 10,
        successCooldownMinutes: Int = 30,
        lastSuccessDate: Long? = null,
        lastFailureDate: Long? = null,
    ): SummaryTriggerDecision = MemoryService.summaryTriggerDecision(
        outsideRoundCount = outsideRoundCount,
        interval = interval,
        successCooldownMinutes = successCooldownMinutes,
        lastSuccessDate = lastSuccessDate,
        lastFailureDate = lastFailureDate,
        now = now,
    )

    // ---- ① 失败短冷却（锁定 300s·优先于一切） ----

    @Test
    fun `失败60秒前_报剩余240秒短冷却`() {
        val d = decide(outsideRoundCount = 10, lastFailureDate = now - 60_000L)
        assertEquals(SummaryTriggerDecision.SkipFailureCooldown(240), d)
    }

    @Test
    fun `失败60秒前_哪怕攒够1000轮也仍在短冷却`() {
        // E15：失败冷却优先级最高，计数轨再满也不越过它。
        val d = decide(outsideRoundCount = 1000, lastFailureDate = now - 60_000L)
        assertTrue(d is SummaryTriggerDecision.SkipFailureCooldown)
    }

    @Test
    fun `失败301秒前_冷却已过_达下限且从未成功_触发`() {
        val d = decide(outsideRoundCount = 10, lastFailureDate = now - 301_000L)
        assertEquals(SummaryTriggerDecision.Trigger, d)
    }

    // ---- ② 用户可调下限 ----

    @Test
    fun `攒9轮不足下限10_跳过`() {
        assertEquals(SummaryTriggerDecision.SkipBelowInterval, decide(outsideRoundCount = 9))
    }

    @Test
    fun `从未成功且达下限_直接触发`() {
        assertEquals(SummaryTriggerDecision.Trigger, decide(outsideRoundCount = 10, lastSuccessDate = null))
    }

    // ---- ③ 双轨：时间轨（用户可调） ----

    @Test
    fun `成功29分钟前_间隔30分钟_攒10轮_双轨都未到`() {
        // 29 分钟 < 30 分钟时间轨；10 轮 < 2×10=20 计数轨 → SkipDualCooldown(已过 1740 秒)。
        val d = decide(outsideRoundCount = 10, lastSuccessDate = now - 29 * 60_000L)
        assertEquals(SummaryTriggerDecision.SkipDualCooldown(1740), d)
    }

    @Test
    fun `成功30分钟整前_间隔30分钟_时间轨到点即触发`() {
        // E16：比较符为 >=，相等即就绪。
        val d = decide(outsideRoundCount = 10, lastSuccessDate = now - 30 * 60_000L)
        assertEquals(SummaryTriggerDecision.Trigger, d)
    }

    @Test
    fun `间隔设0_不限_成功1秒前也触发`() {
        // E4：cooldownMinutes ≤ 0 = 时间轨恒就绪（间隔只管节奏，interval 才是总闸）。
        val d = decide(outsideRoundCount = 10, successCooldownMinutes = 0, lastSuccessDate = now - 1_000L)
        assertEquals(SummaryTriggerDecision.Trigger, d)
    }

    // ---- ③ 双轨：计数轨 2×interval ----

    @Test
    fun `成功1分钟前_攒20轮等于2倍下限_计数轨就绪即触发`() {
        // E16：countReady 的比较符同为 >=。
        val d = decide(outsideRoundCount = 20, lastSuccessDate = now - 60_000L)
        assertEquals(SummaryTriggerDecision.Trigger, d)
    }

    @Test
    fun `成功1分钟前_攒19轮差一轮_两轨皆未到`() {
        val d = decide(outsideRoundCount = 19, lastSuccessDate = now - 60_000L)
        assertEquals(SummaryTriggerDecision.SkipDualCooldown(60), d)
    }

    @Test
    fun `下限5000_攒9999不触发_攒10000触发`() {
        // E8：2×interval 用 Long 运算，大值不溢出。
        val justBelow = decide(outsideRoundCount = 9999, interval = 5000, lastSuccessDate = now - 60_000L)
        assertTrue(justBelow is SummaryTriggerDecision.SkipDualCooldown)
        val atTrack = decide(outsideRoundCount = 10000, interval = 5000, lastSuccessDate = now - 60_000L)
        assertEquals(SummaryTriggerDecision.Trigger, atTrack)
    }
}
