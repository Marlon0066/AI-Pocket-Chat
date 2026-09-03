package com.situ.aichat.diagnostics.perf

import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * T2-1（图纸 2026-07-30 性能采集与量尺 §7）：[PerfCollector] 的开关语义与攒批 flush 编排。
 *
 * 断言从图纸 §3.1 / §3.3 / J5 与 §5 E1/E19 规格独立反推：
 * 关 → 采集点对 [PerfStore] **零交互**、不开计时；开 → 落盘；开转关 → 把存量 flush 完再停。
 *
 * 等待策略：[PerfCollector] 用自有 IO scope（不是可注入的调度器），故用「轮询到证据出现」而不是固定 sleep，
 * 且每个等待点都落在**真正的最后一步**（落盘批次已到达 / isEnabled 已翻）——避免中途态等待造成的假绿与协程泄漏。
 */
class PerfCollectorTest {

    private lateinit var store: PerfStore
    private lateinit var scaleSnapshot: ScaleSnapshot
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var settings: MutableStateFlow<AppSettings>
    private lateinit var collector: PerfCollector

    private val batches = CopyOnWriteArrayList<List<PerfSample>>()

    @Before
    fun setUp() {
        batches.clear()
        store = mockk()
        coEvery { store.append(any(), any()) } answers { batches += firstArg<List<PerfSample>>() }
        scaleSnapshot = mockk()
        coEvery { scaleSnapshot.capture() } returns SCALE
        settingsRepository = mockk()
        settings = MutableStateFlow(AppSettings())
        every { settingsRepository.appSettings } returns settings
        collector = PerfCollector(store, scaleSnapshot, settingsRepository)
    }

    // MARK: - 关闭态（E1 / E19）

    @Test
    fun `首装默认关_采集点对 PerfStore 零交互且不开计时`() = runBlocking {
        assertFalse("AppSettings 默认 perfCollectEnabled=false", AppSettings().perfCollectEnabled)
        awaitEnabled(false)

        assertNull("关闭时不许开回前台计时", collector.beginForegroundTrace())
        repeat(50) { collector.record(healthSample()) }
        delay(SETTLE_MS)

        coVerify(exactly = 0) { store.append(any(), any()) }
        coVerify(exactly = 0) { scaleSnapshot.capture() }
        assertTrue(batches.isEmpty())
    }

    @Test
    fun `关闭时 timedPass 直通_块照跑但不产生任何样本`() = runBlocking {
        awaitEnabled(false)
        val trace = collector.beginForegroundTrace()

        var ran = false
        trace.timedPass(PerfPassNames.WORLD_LINK) { ran = true }
        collector.flushNow()

        assertTrue("被包裹的代码必须照常执行", ran)
        coVerify(exactly = 0) { store.append(any(), any()) }
    }

    /**
     * B1 的护栏：`timedPass` 是**纯包裹**——块恰跑一次、异常原样穿透、开关开关两态行为一致。
     * `AppViewModel.onAppForeground()` 的 11 个 pass 全靠这条性质才敢说「只加包裹，行为不变」。
     */
    @Test
    fun `timedPass 是纯包裹_块恰跑一次且异常原样穿透（开关两态一致）`() = runBlocking {
        suspend fun check(enabledState: Boolean) {
            val trace = collector.beginForegroundTrace()
            var runs = 0
            var thrown: Throwable? = null
            try {
                trace.timedPass(PerfPassNames.ECONOMY_MAINTENANCE) {
                    runs++
                    throw IllegalStateException("boom-$enabledState")
                }
            } catch (e: IllegalStateException) {
                thrown = e
            }
            assertEquals("块必须恰跑一次（enabled=$enabledState）", 1, runs)
            assertEquals("异常必须原样重抛（enabled=$enabledState）", "boom-$enabledState", thrown.message)
        }

        awaitEnabled(false)
        check(false)
        enable()
        // 先把进程首趟（带冷启动标记那条）走完并清账，免得它混进下面的断言。
        collector.beginForegroundTrace()
        collector.flushNow()
        batches.clear()
        check(true)

        // 开启态下抛异常的 pass 仍要被计时（异常不该让这一趟变成盲区）。
        collector.flushNow()
        val sample = awaitForegroundSamples(1).single()
        assertEquals(listOf(PerfPassNames.ECONOMY_MAINTENANCE), sample.passes.map { it.name })
    }

    // MARK: - 开启态

    @Test
    fun `开启后样本会落盘`() = runBlocking {
        enable()

        collector.record(healthSample())

        awaitBatches(1)
        assertEquals(1, batches.single().size)
        assertEquals(PerfSampleKind.HEALTH, batches.single().single().header.kind)
    }

    @Test
    fun `攒批未到阈值时先不落盘_开转关时把存量 flush 掉`() = runBlocking {
        enable()
        collector.record(healthSample())   // 距上次 flush 已久 → 立即落盘（第 1 批）
        awaitBatches(1)
        collector.record(healthSample())   // 30s 窗内且不足 20 条 → 攒着
        delay(SETTLE_MS)
        assertEquals("第二条应当还攒在内存里", 1, batches.size)

        settings.value = AppSettings(perfCollectEnabled = true).copy(perfCollectEnabled = false)

        awaitBatches(2)
        assertEquals("关采集必须把存量样本写完", 1, batches[1].size)
        awaitEnabled(false)
    }

    @Test
    fun `攒够 FLUSH_BATCH 条即落盘`() = runBlocking {
        enable()
        collector.record(healthSample())   // 第 1 批（间隔触发）
        awaitBatches(1)

        repeat(PerfCollector.FLUSH_BATCH) { collector.record(healthSample()) }

        awaitBatches(2)
        assertEquals(PerfCollector.FLUSH_BATCH, batches[1].size)
    }

    // MARK: - 回前台计时封口

    @Test
    fun `回前台计时封口成一条 foreground 样本_带各 pass 与规模数`() = runBlocking {
        enable()
        val trace = collector.beginForegroundTrace()
        assertNotNull("开启后必须能开计时", trace)

        trace.timedPass(PerfPassNames.BG_DIAGNOSTICS) { Thread.sleep(2) }
        trace.timedPass(PerfPassNames.WORLD_LINK) { Thread.sleep(2) }
        trace!!.recordEntryMainThread()
        collector.flushNow()

        val sample = batches.flatten().filterIsInstance<PerfSample.Foreground>().single()
        assertEquals(PerfSampleKind.FOREGROUND, sample.header.kind)
        assertEquals(PERF_SCHEMA_VERSION, sample.header.schemaVersion)
        assertEquals(
            // 首趟 = 冷启动，故列首多一条 cold_start 标记（待采清单「冷启动 ≥3」靠它认）。
            listOf(
                PerfPassNames.COLD_START, PerfPassNames.BG_DIAGNOSTICS,
                PerfPassNames.WORLD_LINK, PerfPassNames.ENTRY_MAIN_THREAD,
            ),
            sample.passes.map { it.name },
        )
        assertEquals(SCALE, sample.scale)
        assertTrue("totalMs 应覆盖到最后一个 pass 报完", sample.totalMs >= 0)
        coVerify(exactly = 1) { scaleSnapshot.capture() }
    }

    @Test
    fun `一条 pass 都没记的计时不落空样本`() = runBlocking {
        enable()
        collector.beginForegroundTrace() // 首趟带冷启动标记，不算「一条都没记」
        collector.flushNow()
        batches.clear()
        collector.beginForegroundTrace()

        collector.flushNow()

        delay(SETTLE_MS)
        assertTrue("空 trace 不该再落一条", batches.flatten().filterIsInstance<PerfSample.Foreground>().isEmpty())
        // 只有首趟（带冷启动标记的那条）取过一次规模数；空 trace 连规模数都不该去取。
        coVerify(exactly = 1) { scaleSnapshot.capture() }
    }

    @Test
    fun `新一趟回前台会把上一趟封口_不会两趟混成一条`() = runBlocking {
        enable()
        val first = collector.beginForegroundTrace()
        first.timedPass(PerfPassNames.PET_MAINTENANCE) {}

        val second = collector.beginForegroundTrace()
        second.timedPass(PerfPassNames.STORY_PASS) {}
        collector.flushNow()

        // 上一趟由 beginForegroundTrace 派协程异步封口，等它真的落盘再断言（别读中途态）。
        // 两趟落盘的先后不保证（一趟同步封口、一趟异步），故按内容而不是下标比对——要证的是「两趟不混成一条」。
        val samples = awaitForegroundSamples(2)
        assertEquals(
            setOf(
                listOf(PerfPassNames.COLD_START, PerfPassNames.PET_MAINTENANCE), // 首趟带冷启动标记
                listOf(PerfPassNames.STORY_PASS),
            ),
            samples.map { s -> s.passes.map { it.name } }.toSet(),
        )
    }

    @Test
    fun `封口后迟到的 pass 不再改写已落样本`() = runBlocking {
        enable()
        val trace = collector.beginForegroundTrace()
        trace.timedPass(PerfPassNames.PET_MAINTENANCE) {}
        collector.flushNow()
        val before = batches.flatten().filterIsInstance<PerfSample.Foreground>().single().passes.size

        trace.timedPass(PerfPassNames.WORLD_LINK) {}
        collector.flushNow()

        val samples = batches.flatten().filterIsInstance<PerfSample.Foreground>()
        assertEquals("迟到的 pass 不许再生出第二条样本", 1, samples.size)
        assertEquals(before, samples.single().passes.size)
    }

    @Test
    fun `冷启动标记只在进程内第一趟出现`() = runBlocking {
        enable()
        val first = collector.beginForegroundTrace()
        first.timedPass(PerfPassNames.STORY_PASS) {}
        val second = collector.beginForegroundTrace()
        second.timedPass(PerfPassNames.STORY_PASS) {}
        collector.flushNow()

        val samples = awaitForegroundSamples(2)
        assertEquals(
            "恰一条带冷启动标记（待采清单「冷启动 ≥3」= 数了 3 次进程启动，不是 3 次回前台）",
            1,
            samples.count { s -> s.passes.any { it.name == PerfPassNames.COLD_START } },
        )
    }

    // MARK: - 开关订阅

    @Test
    fun `开关变化会通知订阅者_探针据此挂摘监听`() = runBlocking {
        val seen = CopyOnWriteArrayList<Boolean>()
        collector.addEnabledListener { seen += it }

        enable()
        settings.value = AppSettings(perfCollectEnabled = false)
        awaitEnabled(false)

        assertEquals(listOf(true, false), seen.toList())
    }

    // MARK: - 工具

    private suspend fun enable() {
        settings.value = AppSettings(perfCollectEnabled = true)
        awaitEnabled(true)
    }

    private suspend fun awaitEnabled(expected: Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (collector.isEnabled != expected && System.currentTimeMillis() < deadline) delay(5)
        assertEquals("等 isEnabled=$expected 超时", expected, collector.isEnabled)
    }

    private suspend fun awaitBatches(count: Int) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (batches.size < count && System.currentTimeMillis() < deadline) delay(5)
        assertEquals("等落盘批次数 $count 超时", count, batches.size)
    }

    private suspend fun awaitForegroundSamples(count: Int): List<PerfSample.Foreground> {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        fun current() = batches.flatten().filterIsInstance<PerfSample.Foreground>()
        while (current().size < count && System.currentTimeMillis() < deadline) delay(5)
        val samples = current()
        assertEquals("等 foreground 样本数 $count 超时", count, samples.size)
        return samples
    }

    private fun healthSample() = PerfSample.Health(
        header = collector.newHeader(PerfSampleKind.HEALTH),
        thermalStatus = 0,
        thermalName = "none",
        batteryTempC = 31.0,
        scene = null,
    )

    private companion object {
        val SCALE = ScaleNumbers(1, 2, 3, 4, 5, 6, 7, 8, 9)
        const val AWAIT_MS = 3_000L

        /** 「不该发生的事」需要给它机会真的发生一次再判死，否则是假绿。 */
        const val SETTLE_MS = 150L
    }
}
