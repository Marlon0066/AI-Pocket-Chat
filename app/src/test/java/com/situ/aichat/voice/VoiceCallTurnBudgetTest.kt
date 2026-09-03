package com.situ.aichat.voice

import com.situ.aichat.data.model.ThinkingBudgetLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T1（C3 通话响应预算·2026-08-25 看门狗改行级活性）：规格独立反推——
 *  - 看门狗只管「第一个流事件之前」：活性按 SSE 行级计（[VoiceCallTurnBudget.collectWithFirstEventBudget]
 *    工厂回调喂狗），静默**累计**满预算才抛 [VoiceCallTurnBudget.FirstStreamEventTimeout]；首个流事件
 *    （含 reasoning）到达即永久撤狗，之后再久的静默交还底层超时；
 *  - 外部取消原样传播（挂断/思考中打断不得被误报成超时）；空流正常结束不得自爆；
 *  - 思考档钳制：OFF 保持、其余全部只降到 LOW、绝不反向升档。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallTurnBudgetTest {

    @Test
    fun `no stream event within budget - throws FirstStreamEventTimeout`() = runTest {
        val never = flow<Int> { awaitCancellation() }
        try {
            VoiceCallTurnBudget.collectWithFirstEventBudget(
                budgetMs = 20_000L,
                streamFactory = { never },
            ) {}
            fail("应在预算耗尽时抛 FirstStreamEventTimeout")
        } catch (e: VoiceCallTurnBudget.FirstStreamEventTimeout) {
            // 预期：20s 虚拟时间内一个事件都没有。
            assertEquals("真僵死判死时刻=恰 20s（图纸 §2.3-2 不劣化钉·R1 复核代办）", 20_000L, testScheduler.currentTime)
        }
    }

    @Test
    fun `first event arrives - watchdog disarmed, later silence is not our business`() = runTest {
        val collected = mutableListOf<Int>()
        val slowTail = flow {
            emit(1) // 首事件在预算内到达 → 撤狗
            delay(90_000) // 远超预算的静默（现实=思考后慢慢生成）——不许再触发看门狗
            emit(2)
        }
        VoiceCallTurnBudget.collectWithFirstEventBudget(
            budgetMs = 20_000L,
            streamFactory = { slowTail },
        ) { collected += it }
        assertEquals(listOf(1, 2), collected)
    }

    @Test
    fun `external cancellation propagates as cancellation, never masked as timeout`() = runTest {
        val never = flow<Int> { awaitCancellation() }
        val job = launch {
            VoiceCallTurnBudget.collectWithFirstEventBudget(
                budgetMs = 20_000L,
                streamFactory = { never },
            ) {}
        }
        advanceTimeBy(1_000)
        job.cancel() // = 挂断 / 思考中被用户打断
        job.join()
        assertTrue("外部取消必须是干净取消，不是超时失败", job.isCancelled)
    }

    @Test
    fun `empty flow completing normally does not blow up on the watchdog`() = runTest {
        VoiceCallTurnBudget.collectWithFirstEventBudget(
            budgetMs = 20_000L,
            streamFactory = { emptyFlow<Int>() },
        ) {
            fail("空流不应回调")
        }
    }

    @Test
    fun `thinking level clamps down to LOW and never re-enables OFF`() {
        assertEquals(ThinkingBudgetLevel.OFF, VoiceCallTurnBudget.clampThinkingForCall(ThinkingBudgetLevel.OFF))
        assertEquals(ThinkingBudgetLevel.LOW, VoiceCallTurnBudget.clampThinkingForCall(ThinkingBudgetLevel.AUTO))
        assertEquals(ThinkingBudgetLevel.LOW, VoiceCallTurnBudget.clampThinkingForCall(ThinkingBudgetLevel.LOW))
        assertEquals(ThinkingBudgetLevel.LOW, VoiceCallTurnBudget.clampThinkingForCall(ThinkingBudgetLevel.MEDIUM))
        assertEquals(ThinkingBudgetLevel.LOW, VoiceCallTurnBudget.clampThinkingForCall(ThinkingBudgetLevel.HIGH))
    }

    /** 隐藏思考：keep-alive 活性每 2s 一次，55.5s 后才出首 token → 绝不误杀、token 正常 collect。 */
    @Test
    fun `hidden thinking fed by keepalive ticks every 2s - first token at 55_5s is not killed`() = runTest {
        var tick: () -> Unit = {}
        val ticker = launch { repeat(28) { delay(2_000); tick() } }
        val collected = mutableListOf<Int>()
        VoiceCallTurnBudget.collectWithFirstEventBudget(
            budgetMs = 20_000L,
            streamFactory = { onLiveness ->
                tick = onLiveness
                flow {
                    delay(55_500)
                    emit(1)
                }
            },
        ) { collected += it }
        ticker.cancel()
        assertEquals(listOf(1), collected)
    }

    /** 思考中途中继死掉：活性到 8.5s 戛然而止 → 距最后一次活性恰 20s 判死（虚拟时间 29_000ms）。 */
    @Test
    fun `relay dies mid-thinking - timeout fires exactly 20s after last liveness`() = runTest {
        var tick: () -> Unit = {}
        val ticker = launch {
            delay(500); tick()
            delay(2_000); tick()
            delay(2_000); tick()
            delay(2_000); tick()
            delay(2_000); tick()
        }
        try {
            VoiceCallTurnBudget.collectWithFirstEventBudget(
                budgetMs = 20_000L,
                streamFactory = { onLiveness ->
                    tick = onLiveness
                    flow<Int> { awaitCancellation() }
                },
            ) {}
            fail("最后一次活性后静默满预算应抛 FirstStreamEventTimeout")
        } catch (e: VoiceCallTurnBudget.FirstStreamEventTimeout) {
            // 预期：9s 轮询见到最后活性 + 20s 静默 → 29s 判死。
        }
        ticker.cancel()
        assertEquals("判死时刻=最后一次活性(9s 见)+静默累计 20s", 29_000L, testScheduler.currentTime)
    }

    /** 活性持续喂的同时 1s 处外部取消（挂断/打断）→ 干净取消，绝不伪装成超时。 */
    @Test
    fun `external cancellation while liveness keeps flowing - still clean cancellation`() = runTest {
        var tick: () -> Unit = {}
        val job = launch {
            VoiceCallTurnBudget.collectWithFirstEventBudget(
                budgetMs = 20_000L,
                streamFactory = { onLiveness ->
                    tick = onLiveness
                    flow<Int> { awaitCancellation() }
                },
            ) {}
        }
        val ticker = launch { while (true) { delay(1_000); tick() } }
        advanceTimeBy(1_000)
        job.cancel() // = 挂断 / 思考中被用户打断
        job.join()
        ticker.cancel()
        assertTrue("有活性时外部取消也必须是干净取消，不是超时失败", job.isCancelled)
    }
}
