package com.situ.aichat.ui.liuli.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T1-1 / T2-1 琉璃录音声波条（图纸 2026-09-05 卷二B §7）。
 *
 * 断言全部从**规格**反推：环形缓冲的容量与先进先出语义、柱高线性公式的两端与越界钳位、
 * `M:SS` 的进位，以及两种状态下条上该出现的文案（图纸 §3.2 声波条一节）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliRecordingBarTest {

    @get:Rule
    val compose = createComposeRule()

    // ── T1-1 纯逻辑 ────────────────────────────────────────────────────────────────

    @Test fun waveHistory_keepsOnlyNewestCapacitySamples_inNewestFirstOrder() {
        val history = LiuliWaveHistory(capacity = 40)
        // 推 45 格 0.00, 0.01 … 0.44：满 40 后最早 5 格应被挤掉。
        repeat(45) { history.push(it / 100f) }
        assertEquals("容量恒 40", 40, history.size)
        val newest = history.latest(40)
        assertEquals("索引 0 = 最新一格（画在最右）", 0.44f, newest.first(), 1e-6f)
        assertEquals("最老的一格 = 第 6 次推入（前 5 格已被挤掉）", 0.05f, newest.last(), 1e-6f)
        assertEquals("latest 严格新→旧", 0.43f, newest[1], 1e-6f)
    }

    @Test fun waveHistory_beforeFull_returnsOnlyWhatItHas() {
        val history = LiuliWaveHistory(capacity = 40)
        history.push(0.2f)
        history.push(0.8f)
        assertEquals(2, history.size)
        assertEquals("不足格数时只给已有样本（左侧留空）", listOf(0.8f, 0.2f), history.latest(40))
    }

    @Test fun waveBarHeight_isLinearBetweenSixAndTwentyTwo_andClamps() {
        assertEquals("静音 = 底高 6dp", 6.dp, liuliWaveBarHeightDp(0f))
        assertEquals("满电平 = 22dp", 22.dp, liuliWaveBarHeightDp(1f))
        assertEquals("中点 = 14dp", 14.dp, liuliWaveBarHeightDp(0.5f))
        assertEquals("越上界钳到 22dp", 22.dp, liuliWaveBarHeightDp(2f))
        assertEquals("越下界钳到 6dp", 6.dp, liuliWaveBarHeightDp(-1f))
    }

    @Test fun durationFormat_isMinuteColonTwoDigitSeconds() {
        assertEquals("0:00", liuliFormatVoiceDuration(0L))
        assertEquals("不足一秒仍是 0:00", "0:00", liuliFormatVoiceDuration(999L))
        assertEquals("0:07", liuliFormatVoiceDuration(7_000L))
        assertEquals("秒补零", "1:01", liuliFormatVoiceDuration(61_000L))
        assertEquals("负时长兜底为 0:00", "0:00", liuliFormatVoiceDuration(-5_000L))
    }

    // ── T2-1 行为 ─────────────────────────────────────────────────────────────────

    @Test fun recordingState_showsSlideUpAndReleaseHint_withRunningTimer() {
        compose.setContent {
            LiuliRecordingBar(level = 0.4f, durationMs = 7_000L, cancelling = false, reduceMotion = true)
        }
        compose.onNodeWithText("上滑取消 · 松开发送").assertIsDisplayed()
        compose.onNodeWithText("0:07").assertIsDisplayed()
        compose.onNodeWithText("松手取消").assertDoesNotExist()
    }

    @Test fun waveform_keepsSampling_whileLevelStaysConstant() {
        // E1（复核 R1 🟡-5）：录音器的 StateFlow 会把相同电平去重，数字静音 = 恒 0；波形必须按**时间**推格，
        // 静音时源源不断进 0 才会滚成 6dp 底高，而不是冻在最后几根高柱上。level 全程不变，只靠时钟也得攒出样本。
        val history = LiuliWaveHistory(capacity = 40)
        compose.setContent {
            LiuliRecordingBar(level = 0f, durationMs = 1_000L, cancelling = false, reduceMotion = true, history = history)
        }
        compose.waitUntil(SAMPLE_WAIT_MS) { history.size >= MIN_SAMPLES }
        assertTrue("电平恒 0 时仍按 80ms 一格推样本（攒到 ${history.size}）", history.size >= MIN_SAMPLES)
        assertEquals("推进去的就是当前电平（静音 = 0 ⇒ 全部底高）", List(MIN_SAMPLES) { 0f }, history.latest(MIN_SAMPLES))
    }

    @Test fun cancellingState_swapsHintToReleaseToCancel() {
        compose.setContent {
            LiuliRecordingBar(level = 0.4f, durationMs = 7_000L, cancelling = true, reduceMotion = true)
        }
        compose.onNodeWithText("松手取消").assertIsDisplayed()
        compose.onNodeWithText("上滑取消 · 松开发送").assertDoesNotExist()
        // 计时照走（取消态只换文案与配色，不停表）。
        compose.onNodeWithText("0:07").assertIsDisplayed()
    }

    private companion object {
        /** 5 格 = 4×80ms 节拍 + 首帧那一格；等待上限给足余量（Robolectric 主线程调度不匀）。 */
        const val MIN_SAMPLES = 5
        const val SAMPLE_WAIT_MS = 3_000L
    }
}
