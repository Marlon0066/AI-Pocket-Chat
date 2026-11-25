package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.foreground.LlmGenerationForegroundController
import com.situ.aichat.notification.Notifier
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 灵动岛卷一 T2-1：[StoryGenerationTaskManager] 的**真实事件序**行为测试（MockK 假 Service）。
 *
 * 验的是「假进度定时器退役、进度只跟真实事件走」这件事本身：
 * PREPARING →（首个有效 preview）WRITING →（Service.onPhase）FINALIZING → ARCHIVING →（成功路）DONE。
 *
 * 取样方式：在假 Service 的 answer 体**内**逐事件读 `activeGenerations.value` 快照——
 * StateFlow 会合并（conflate）快速连续的值，靠后台 collect 抓序列必漏帧、必 flaky；
 * 而 updateStreamingPreview/updatePhase 都是同步 update，事件后立刻读即确定性快照。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryGenerationTaskManagerTest {

    private val service = mockk<StoryGenerationService>()
    private val repo = mockk<StoryRepository>()
    private val foregroundController = mockk<LlmGenerationForegroundController>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>()

    private val storyId = "s1"

    @Before
    fun setUp() {
        // Notifier 是 object 且真发系统通知：本测只关心进度事件序，隔离掉。
        mockkObject(Notifier)
        coJustRun { Notifier.postStory(any(), any(), any(), any(), any()) }
        coJustRun { repo.updateStatus(any(), any(), any()) }
        coJustRun { repo.markGenerationFailed(any(), any(), any()) }
        every { settingsRepo.appSettings } returns flowOf(AppSettings())
        // 卷一 V9：runGeneration 现在先读创作配置定超时档（思考 600s / 其余 300s）。
        // 默认桩 null → thinking=false → 300s，与本测各例既有行为一致。
        coEvery { service.creationConfig() } returns null
    }

    @After
    fun tearDown() = unmockkObject(Notifier)

    /** 真 SharedPreferences（Robolectric）：「推进起点」标记要验真写入，桩掉就验不到字节。 */
    private val readingProgressStore by lazy { StoryReadingProgressStore(RuntimeEnvironment.getApplication()) }

    private fun manager() = StoryGenerationTaskManager(
        RuntimeEnvironment.getApplication(), service, repo, foregroundController, readingProgressStore, settingsRepo,
    )

    private fun story(chapterLengthPreference: Int = 1000, cachedLatestChapterNumber: Int? = null) = StoryEntity(
        id = storyId,
        title = "测试故事",
        status = StoryStatus.SERIALIZING,
        chapterLengthPreference = chapterLengthPreference,
        cachedLatestChapterNumber = cachedLatestChapterNumber,
    )

    private fun chapter() = StoryChapterEntity(storyId = storyId, chapterNumber = 1, title = "第一章", content = "正文")

    /** 等到该故事进入指定阶段（带「事件确已发生」的证据，非初始态即满足）。 */
    private suspend fun StoryGenerationTaskManager.awaitPhase(phase: StoryGenPhase) =
        withTimeout(5_000) { activeGenerations.first { it[storyId]?.genPhase == phase } }

    private suspend fun StoryGenerationTaskManager.awaitCleared() =
        withTimeout(5_000) { activeGenerations.first { !it.containsKey(storyId) } }

    /**
     * 等生成协程摸完 [Notifier]（= 危险窗口关上）再让测试返回。
     *
     * **为什么非等不可**：`awaitPhase(DONE)` / [awaitCleared] 放行的都是**中途**状态——DONE 之后还有
     * `notifyGenerationFinished`；失败路清 map 之后还有记 lastError、`NonCancellable` 内的 markGenerationFailed
     * + `Notifier.postStory`。测试就此返回，@After 的 `unmockkObject(Notifier)` 与 Robolectric 拆环境会**抢在**
     * 孤儿协程摸 Notifier 之前跑完 → 那一摸必抛（真身撞 Robolectric 空 activityThread 的 NPE，或 MockK
     * 「can't find stub Notifier」）→ 这异常没人接，经协程**全局** handler（ServiceLoader 装的）漏进
     * kotlinx-coroutines-test 的 `ExceptionCollector`，**毒死同 JVM 里后面某条 runTest**——报
     * `UncaughtExceptionsBeforeTest`、受害者随执行序漂移（曾把 AssistantBubbleMorphTest 冤死，误诊过「帧时序 flaky」）。
     * 详见 docs/playbook/PITFALLS.md §1e。
     */
    private fun awaitNotified() = verify(timeout = 5_000) { Notifier.postStory(any(), any(), any(), any(), any()) }

    /**
     * 等失败链**整条**跑完（比 [awaitNotified] 再多等一步）：`release()` 落在 `runGeneration` 的 `finally` =
     * 失败路最后一句，等到它即证明 `handleFailure` 的 `NonCancellable` 块（记 lastError → markGenerationFailed
     * → postStory）已全部落定——失败路的断言正读这些，不等就是竞态假绿。
     */
    private fun awaitFailureSettled() = verify(timeout = 5_000) { foregroundController.release() }

    @Test
    fun 事件序_构思到撰写到整理到归档到完成_逐相推进() = runBlocking {
        val tm = manager()
        val seen = mutableListOf<Pair<StoryGenPhase, Double>>()
        coEvery { repo.getStory(storyId) } returns story()
        coEvery { service.generateFirstChapter(any(), any(), any(), any()) } coAnswers {
            val onPreview = arg<((String, Int) -> Unit)?>(2)!!
            val onPhase = arg<((StoryGenPhase) -> Unit)?>(3)!!
            fun snap() = tm.activeGenerations.value[storyId]!!.let { seen += it.genPhase to it.progress }

            snap() // 起步：还没有任何 LLM 事件
            onPreview("第一段正文…", 500)
            snap()
            onPhase(StoryGenPhase.FINALIZING)
            snap()
            onPhase(StoryGenPhase.ARCHIVING)
            snap()
            chapter()
        }

        tm.startGeneration(story())
        tm.awaitPhase(StoryGenPhase.DONE)
        awaitNotified()
        val done = tm.activeGenerations.value[storyId]!!

        assertEquals(
            listOf(StoryGenPhase.PREPARING, StoryGenPhase.WRITING, StoryGenPhase.FINALIZING, StoryGenPhase.ARCHIVING),
            seen.map { it.first },
        )
        // 撰写段：已收 500 字 ÷ 预期 1000 字 = 半程 → 0.15 + 0.60×0.5 = 0.45
        assertEquals(0.0, seen[0].second, 1e-9)
        assertEquals(0.45, seen[1].second, 1e-9)
        assertEquals(0.75, seen[2].second, 1e-9)
        assertEquals(0.92, seen[3].second, 1e-9)
        assertEquals(1.0, done.progress, 1e-9)
        assertEquals("第 1 章写好了", done.phase)
    }

    @Test
    fun E5_清洗后空预览不切撰写相_构思段诚实停住() = runBlocking {
        val tm = manager()
        val afterBlank = mutableListOf<GenerationProgressSnapshot>()
        coEvery { repo.getStory(storyId) } returns story()
        coEvery { service.generateFirstChapter(any(), any(), any(), any()) } coAnswers {
            val onPreview = arg<((String, Int) -> Unit)?>(2)!!
            // 思考模型静默期：清洗后什么正文都没清出来（E1/E5）。
            onPreview("", 320)
            onPreview("   ", 480)
            tm.activeGenerations.value[storyId]!!.let { afterBlank += GenerationProgressSnapshot(it.genPhase, it.progress, it.phase) }
            // 首个**有效** preview 才切「撰写」。
            onPreview("正文终于出来了", 500)
            tm.activeGenerations.value[storyId]!!.let { afterBlank += GenerationProgressSnapshot(it.genPhase, it.progress, it.phase) }
            chapter()
        }

        tm.startGeneration(story())
        tm.awaitPhase(StoryGenPhase.DONE)
        awaitNotified()

        assertEquals(StoryGenPhase.PREPARING, afterBlank[0].genPhase)
        assertEquals("空预览期间进度必须原地不动（绝不假爬）", 0.0, afterBlank[0].progress, 1e-9)
        assertEquals("正在构思剧情…", afterBlank[0].phase)
        assertEquals(StoryGenPhase.WRITING, afterBlank[1].genPhase)
        assertEquals(0.45, afterBlank[1].progress, 1e-9)
    }

    @Test
    fun 进度单调不倒退_字数回缩与迟到preview都不拉低() = runBlocking {
        val tm = manager()
        val track = mutableListOf<Double>()
        coEvery { repo.getStory(storyId) } returns story()
        coEvery { service.generateFirstChapter(any(), any(), any(), any()) } coAnswers {
            val onPreview = arg<((String, Int) -> Unit)?>(2)!!
            val onPhase = arg<((StoryGenPhase) -> Unit)?>(3)!!
            fun snap() = track.add(tm.activeGenerations.value[storyId]!!.progress)

            onPreview("甲", 800); snap()
            onPreview("乙", 300); snap() // 字数回缩（理论上不该发生）→ 进度不许退
            onPhase(StoryGenPhase.FINALIZING); snap()
            onPreview("迟到的 preview", 900); snap() // 已进整理段的迟到 preview → 不许把 0.75 拉回
            chapter()
        }

        tm.startGeneration(story())
        tm.awaitPhase(StoryGenPhase.DONE)
        awaitNotified()

        track.zipWithNext { a, b -> assertTrue("进度倒退：$a → $b", b >= a) }
        assertEquals(0.75, track[2], 1e-9)
        assertEquals(0.75, track[3], 1e-9)
    }

    @Test
    fun E8_章节字数偏好为脏数据时兜底两千() = runBlocking {
        val tm = manager()
        var observed = -1.0
        coEvery { repo.getStory(storyId) } returns story(chapterLengthPreference = 0)
        coEvery { service.generateFirstChapter(any(), any(), any(), any()) } coAnswers {
            val onPreview = arg<((String, Int) -> Unit)?>(2)!!
            onPreview("正文", 1000)
            observed = tm.activeGenerations.value[storyId]!!.progress
            chapter()
        }

        tm.startGeneration(story(chapterLengthPreference = 0))
        tm.awaitPhase(StoryGenPhase.DONE)
        awaitNotified()

        // 分母兜底 2000：1000/2000 = 半程 → 0.45（若误用 0 作分母会得到 Infinity → 钳成 0.75）
        assertEquals(0.45, observed, 1e-9)
    }

    @Test
    fun 成功收尾_停留一拍后清进度态且释放保活() = runBlocking {
        val tm = manager()
        coEvery { repo.getStory(storyId) } returns story()
        coEvery { service.generateFirstChapter(any(), any(), any(), any()) } returns chapter()

        tm.startGeneration(story())
        tm.awaitPhase(StoryGenPhase.DONE)
        assertEquals("满格态须先停留一拍，不许立刻消失", 1.0, tm.activeGenerations.value[storyId]!!.progress, 1e-9)
        tm.awaitCleared()

        assertNull(tm.activeGenerations.value[storyId])
        assertFalse("成功后不得留活跃任务", tm.isGenerating(storyId))
    }

    /**
     * 卷一 V9（图纸 §7 T2-8）：超时档必须**在进入 withTimeout 之前**从创作配置读出来。
     *
     * withTimeout 的时长本身不可观测（真等 300s/600s 不可行、虚拟时钟又摸不到本类自持的应用级 scope），
     * 故拆两半证：①「取值函数被调用且排在生成之前」= 本例；②「取值函数给出 600s/300s」= T1-3
     * [StoryGenerationProgressLogicTest.generation_timeout_is_600s_for_thinking_and_300s_otherwise]。
     * 两者合起来覆盖接线 + 数值（图纸 §7 T2-8 明文授权的「双保险」路径，已在 §11 登记）。
     */
    @Test
    fun 超时档在进入看门狗前读创作配置的思考标记() = runBlocking {
        val tm = manager()
        val thinkingConfig = com.situ.aichat.data.remote.llm.ApiConfigValues(
            providerType = com.situ.aichat.data.model.ApiProviderType.OPENAI_COMPATIBLE,
            apiKey = "k", baseUrl = "https://example.test", modelName = "deepseek-reasoner",
            isThinkingModel = true,
        )
        coEvery { service.creationConfig() } returns thinkingConfig
        coEvery { repo.getStory(storyId) } returns story()
        coEvery { service.generateFirstChapter(any(), any(), any(), any()) } returns chapter()

        tm.startGeneration(story())
        tm.awaitPhase(StoryGenPhase.DONE)
        tm.awaitCleared()
        awaitNotified()

        // 先读配置定档，再跑被看门狗包住的生成——顺序反了就等于「档位算在超时窗之外」
        io.mockk.coVerifyOrder {
            service.creationConfig()
            service.generateFirstChapter(any(), any(), any(), any())
        }
    }

    /**
     * **卷一 R1 🟡-1 的看门狗**：完成停留窗（1.5s）内用户点「继续生成」→ 旧任务的尾部清除
     * **不得**抹掉新一轮的进度条目。
     *
     * 用户遭遇独立反推：看到「第 N 章写好了」通知就接着点「继续生成」（真实高频路径）→ 新一轮全程
     * 无药丸、无阅读器遮罩、无书架进度；而章节照常落库，故障格外难察觉。
     *
     * 时序控制：第一轮立刻交章 → DONE → 尾部 `delay(1500)` 起跑；趁这个窗口发第二轮，第二轮的假
     * Service 挂在 [secondRoundGate] 上不返回 → 稳定停在 PREPARING；待旧尾部确已跑完（`release()`
     * 是清 map 的前一步·§9 锁定顺序）后断言新条目仍在。
     */
    @Test
    fun R1_完成停留窗内重开生成_旧任务尾部不得抹掉新进度条目() = runBlocking {
        val tm = manager()
        val secondRoundStarted = CompletableDeferred<Unit>()
        val secondRoundGate = CompletableDeferred<Unit>()
        var round = 0
        coEvery { repo.getStory(storyId) } returns story()
        coEvery { service.generateFirstChapter(any(), any(), any(), any()) } coAnswers {
            round++
            if (round == 1) {
                chapter()
            } else {
                secondRoundStarted.complete(Unit)
                secondRoundGate.await() // 挂住 → 第二轮稳定停在 PREPARING，直到断言做完
                chapter()
            }
        }

        tm.startGeneration(story())
        tm.awaitPhase(StoryGenPhase.DONE)
        // 成功路是「updatePhase(DONE) → activeTasks.remove」两步，awaitPhase 可能正落在两步之间；
        // 此刻重开会被 putIfAbsent 挡掉 → 第二轮压根没起，测试便会假绿。等去重槽真腾空（测试自身的
        // 时序纪律，非产品缺陷）。
        withTimeout(5_000) { while (tm.isGenerating(storyId)) delay(1) }

        // 停留窗内用户点「继续生成」。
        tm.startGeneration(story())
        withTimeout(5_000) { secondRoundStarted.await() }
        assertEquals(
            "新一轮应已占住进度条目",
            StoryGenPhase.PREPARING,
            tm.activeGenerations.value[storyId]!!.genPhase,
        )

        // 等旧任务 delay(1500) 醒来跑完尾部；release() 落在清 map 前一行，故它一到就说明清除已执行/正在执行。
        verify(timeout = 10_000) { foregroundController.release() }
        delay(300) // 给紧随其后的那一行清除留出确定跑完的余量

        val after = tm.activeGenerations.value[storyId]
        assertNotNull("停留窗内重开的新一轮进度条目被旧任务尾部清除抹掉了（药丸/遮罩/书架进度全丢）", after)
        assertEquals(StoryGenPhase.PREPARING, after!!.genPhase)
        assertTrue("新一轮仍应是活跃任务", tm.isGenerating(storyId))

        // 收尾：放行第二轮让它自然收官（顺带再证一次守卫不过紧——DONE 的自家尾巴照清），
        // 别把挂起的协程留到 tearDown 之后。
        secondRoundGate.complete(Unit)
        tm.awaitCleared()
        assertFalse("第二轮收官后不得留活跃任务", tm.isGenerating(storyId))
    }

    @Test
    fun E2_生成失败_即清进度态并记错误() = runBlocking {
        val tm = manager()
        coEvery { repo.getStory(storyId) } returns story()
        coEvery { service.generateFirstChapter(any(), any(), any(), any()) } throws RuntimeException("网络连接失败")

        tm.startGeneration(story())
        tm.awaitCleared()
        awaitFailureSettled() // lastError 的写入落在清 map 之后，不等即读 = 竞态

        assertNull("失败即撤药丸，不留残值", tm.activeGenerations.value[storyId])
        assertEquals("网络连接失败", tm.consumeLastError(storyId))
    }

    /**
     * **ST11 拍板①（意图保留）的看门狗**：请求了结局的书生成失败 → 失败收尾**只写 status**，
     * 绝不清 requestedEndingType/Detail/rewriteInstruction（旧 `markGenerationFailedClearingRequests`
     * 的清除已删）。断言从用户遭遇独立反推：请求结局遇一次网络失败，点「重新生成」必须仍写结局章，
     * 而不是静默变成普通续章。
     *
     * 取证法：repo 是严格 mock（非 relaxed）——「失败路调用了任何清除类写库」会直接以
     * 「no answer found for」炸掉本例；再正向 coVerify 失败路恰好只调 markGenerationFailed。
     */
    @Test
    fun ST11_生成失败_结局意图必须留着不被清() = runBlocking {
        val tm = manager()
        val requested = story().copy(
            requestedEndingType = StoryEndingType.CUSTOM,
            requestedEndingDetail = "圆满收场",
        )
        coEvery { repo.getStory(storyId) } returns requested
        coEvery { service.generateFirstChapter(any(), any(), any(), any()) } throws RuntimeException("网络连接失败")

        tm.startGeneration(requested)
        tm.awaitCleared()
        awaitFailureSettled() // markGenerationFailed 落在清 map 之后，不等即核 exactly=1 = 竞态

        // 失败收尾只写 status + updatedAt。
        coVerify(exactly = 1) { repo.markGenerationFailed(storyId, StoryStatus.GENERATION_FAILED, any()) }
        // 失败路绝不碰意图：clearEndingRequest / updateEndingRequest 一次都不许调。
        coVerify(exactly = 0) { repo.clearEndingRequest(any(), any()) }
        coVerify(exactly = 0) { repo.updateEndingRequest(any(), any(), any(), any(), any()) }
    }

    /**
     * 前台保活引用计数**必须配平**：一次生成 = 恰好 1 次 acquire ↔ 恰好 1 次 release。
     *
     * 反推自故障现象而非实现：控制器的计数是全 app 共享的一本账（聊天流式 / 备份 / 追更自动 / 第二本书
     * 都在上面记）。成功路曾经 release 两次（显式一次 + finally 一次，靠「计数钳 0」当去重）——只要同时
     * 还有别的任务在跑，多减的那一次减掉的就是别人的份额：前台服务被提前停掉、灵动岛药丸随之清空，
     * 另一路明明还在生成却没了药丸，也丢了后台保活。故这里钉死 exactly = 1。
     */
    @Test
    fun 成功路_前台保活引用计数配平_acquire与release各恰好一次() = runBlocking {
        val tm = manager()
        coEvery { repo.getStory(storyId) } returns story()
        coEvery { service.generateFirstChapter(any(), any(), any(), any()) } returns chapter()

        tm.startGeneration(story())
        tm.awaitCleared()
        awaitNotified()
        delay(500) // finally 紧跟清 map 之后，给它确定跑完的余量再核次数

        verify(exactly = 1) { foregroundController.acquire() }
        verify(exactly = 1) { foregroundController.release() }
    }

    /**
     * 「推进起点」记账：用户主动要生成下一章 = 他跟当前最新章处理完了 → 记下章号，
     * 供续读把落点前移一章（[StoryReadingProgressLogic.preferredResumeChapter]）。
     * 首章生成没有「上一章」可推进 → 不许记。
     */
    @Test
    fun 用户主动生成下一章_记下推进起点章号() = runBlocking {
        val tm = manager()
        coEvery { repo.getStory(storyId) } returns story(cachedLatestChapterNumber = 2)
        coEvery { service.generateFirstChapter(any(), any(), any(), any()) } returns chapter()
        coEvery { service.generateNextChapter(any(), any(), any(), any()) } returns chapter()

        tm.startGeneration(story(cachedLatestChapterNumber = 2))
        assertEquals(2, readingProgressStore.advancedFromChapterNumber(storyId))

        tm.awaitCleared()
        awaitNotified()
    }

    @Test
    fun 首章生成_没有上一章可推进_不记推进起点() = runBlocking {
        val tm = manager()
        coEvery { repo.getStory(storyId) } returns story()
        coEvery { service.generateFirstChapter(any(), any(), any(), any()) } returns chapter()

        tm.startGeneration(story())
        assertNull(readingProgressStore.advancedFromChapterNumber(storyId))

        tm.awaitCleared()
        awaitNotified()
    }

    @Test
    fun B4_关闭故事写好或失败提醒时不再推送系统通知() = runBlocking {
        every { settingsRepo.appSettings } returns flowOf(AppSettings(notifStoryFinishedEnabled = false))
        val tm = manager()
        coEvery { repo.getStory(storyId) } returns story()
        coEvery { service.generateFirstChapter(any(), any(), any(), any()) } returns chapter()

        tm.startGeneration(story())
        tm.awaitCleared()
        verify(timeout = 5_000) { foregroundController.release() }
        verify(exactly = 0) { Notifier.postStory(any(), any(), any(), any(), any()) }
    }

    private data class GenerationProgressSnapshot(val genPhase: StoryGenPhase, val progress: Double, val phase: String)
}
