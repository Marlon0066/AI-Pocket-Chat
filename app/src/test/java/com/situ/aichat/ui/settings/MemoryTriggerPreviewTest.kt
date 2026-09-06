package com.situ.aichat.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「攒够多少轮再总结」活例子四态 T1（图纸 2026-09-05 §3.4 / §7 T1-2）。
 *
 * 断言从规格独立反推：interval ≤ 0 → Off；interval > window → OverWindow（**含等号的一侧算安全**）；
 * 否则 Normal，第一次总结落在 window + interval 轮。cooldown 只透传，不参与分档。
 */
class MemoryTriggerPreviewTest {

    @Test
    fun `攒够0轮_已关闭自动总结`() {
        assertEquals(MemoryTriggerPreview.Off, MemoryTriggerPreview.from(window = 30, interval = 0, cooldownMinutes = 30))
    }

    @Test
    fun `攒够轮数超出窗口_报越界并带回窗口值作建议上限`() {
        assertEquals(
            MemoryTriggerPreview.OverWindow(window = 20),
            MemoryTriggerPreview.from(window = 20, interval = 30, cooldownMinutes = 30),
        )
    }

    @Test
    fun `常态_窗口30攒够10_第一次总结落在第40轮`() {
        assertEquals(
            MemoryTriggerPreview.Normal(firstRound = 40, interval = 10, cooldownMinutes = 30),
            MemoryTriggerPreview.from(window = 30, interval = 10, cooldownMinutes = 30),
        )
    }

    @Test
    fun `攒够轮数正好等于窗口_仍算安全区_不报越界`() {
        assertEquals(
            MemoryTriggerPreview.Normal(firstRound = 60, interval = 30, cooldownMinutes = 30),
            MemoryTriggerPreview.from(window = 30, interval = 30, cooldownMinutes = 30),
        )
    }

    @Test
    fun `间隔设为不限_仍是常态_cooldown原样透传0`() {
        assertEquals(
            MemoryTriggerPreview.Normal(firstRound = 40, interval = 10, cooldownMinutes = 0),
            MemoryTriggerPreview.from(window = 30, interval = 10, cooldownMinutes = 0),
        )
    }
}
