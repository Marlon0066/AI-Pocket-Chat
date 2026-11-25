package com.situ.aichat.story

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.foreground.ForegroundActivity
import com.situ.aichat.foreground.LlmGenerationForegroundController
import com.situ.aichat.notification.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 故事生成前台任务管理器 + 状态机（1:1 iOS `StoryGenerationTaskManager.swift`，330 行）。
 *
 * iOS 是 `@MainActor @Observable` 单例；安卓地道改造：
 * - `@Singleton`，用 [MutableStateFlow] 暴露 [activeGenerations]/[lastErrors] 给 UI 收集（替 @Observable）。
 * - **应用级协程作用域**（[scope] = SupervisorJob + Dispatchers.Default，同 [com.situ.aichat.busyreply.BusyReplyService]）：
 *   生成任务须 survive 屏幕切换 / ViewModel 重建——这正是 iOS `beginBackgroundTask`(~30s 宽限) 的安卓对应物。
 *   **真正的后台存活**（进程死亡 / 退后台续跑 / 追更自动生成）= 11.1g（WorkManager + 前台服务），本块不构建。
 * - per-storyId [Job] 管理（[activeTasks]，模板见 [com.situ.aichat.moments.MomentDelayedTaskRegistry]）：
 *   用 `putIfAbsent` 原子去重 = iOS `guard activeTasks[id] == nil`。
 *
 * 状态机：serializing/generationFailed →(startGeneration)→ generating →(成功：completed/waitingChoice/serializing
 * 由 [StoryGenerationService] 内 materialize 决策) 或 →(失败/超时/取消)→ generationFailed。状态落库一律走定向写
 * （[StoryRepository.updateStatus]/[StoryRepository.markGenerationFailed]），不用整行 @Update（D1 安全）。
 *
 * 章节生成本身串已就位的 [StoryGenerationService.generateFirstChapter]/[generateNextChapter]，
 * 流式预览经 onPreview → [updateStreamingPreview]。诊断走 Logcat（M01 诊断日志未移植，全体 LLM 服务一致）。
 *
 * 完成/失败本地通知 = f-2（用户已拍板：成功+失败都发、新「故事更新」独立渠道、文案 1:1 iOS），见 [notifyGenerationFinished] 接缝。
 */
@Singleton
class StoryGenerationTaskManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: StoryGenerationService,
    private val storyRepository: StoryRepository,
    private val foregroundController: LlmGenerationForegroundController,
    private val readingProgressStore: StoryReadingProgressStore,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * UI 经故事 ID 读取的当前生成进度。进度值与阶段全部由**真实事件**驱动（假进度定时器已退役）：
     * 起步 PREPARING → 首个有效流式 preview 切 WRITING（段内按字数连续推进）→ Service 的 onPhase 发
     * FINALIZING / ARCHIVING → 成功路写 DONE。换算口径唯一收口在 [StoryProgressModel]。
     */
    data class GenerationProgress(
        /** 总 fraction 0–1（= [StoryProgressModel.overall] 输出·update 内取 max 保证单调不减）。 */
        val progress: Double,
        val genPhase: StoryGenPhase,
        /** 显示文案 = [StoryProgressModel.phaseLabel]（页内直读；随 [genPhase] 同步改写）。 */
        val phase: String,
        val storyTitle: String,
        val chapterNumber: Int,
        /** 流式预览：已清理的小说正文片段（末约 200 字），由 Service 经 onPreview 节流推送。 */
        val streamingPreview: String = "",
    )

    private val _activeGenerations = MutableStateFlow<Map<String, GenerationProgress>>(emptyMap())
    /** 活跃生成进度（storyId → 进度），1:1 iOS `activeGenerations`。UI 据此渲染进度条/流式预览。 */
    val activeGenerations: StateFlow<Map<String, GenerationProgress>> = _activeGenerations.asStateFlow()

    private val _lastErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 最近一次错误（storyId → 文案），供阅读页停留时即时提示，1:1 iOS `lastErrors`。 */
    val lastErrors: StateFlow<Map<String, String>> = _lastErrors.asStateFlow()

    /** 应用级作用域：生成任务 survive 屏幕切换（= iOS beginBackgroundTask 宽限；真后台 = 11.1g）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 活跃任务按故事隔离，避免重复启动，1:1 iOS `activeTasks`（去重靠 putIfAbsent）。 */
    private val activeTasks = ConcurrentHashMap<String, Job>()

    init {
        // ⑤ Live Update：观察自身活跃生成（取进度最高的一路当代表·E3），转发给前台服务控制器 → 常驻通知用
        // ProgressStyle 渲染成 Android 16 灵动岛四段药丸；映射为空（生成全部结束/清理）即让出故事槽。
        // 进度的所有更新都经 [_activeGenerations]，故这一处观察即全覆盖。
        //
        // 节流闸只在这儿（推 controller = 跨 binder notify 系统通知，扛不住流式 6-7 次/秒的刷新）；
        // 页内 UI 直读 [activeGenerations]，不受此闸——150ms 一帧的丝滑零成本。
        var lastPush: ForegroundActivity.StoryProgress? = null
        var lastPushAt = 0L
        scope.launch {
            activeGenerations.collect { gens ->
                val lead = leadForegroundProgress(gens)
                if (lead == null) {
                    lastPush = null
                    foregroundController.clearStoryProgress()
                    return@collect
                }
                val now = SystemClock.elapsedRealtime() // 单调时钟：防用户调表把闸卡死/打开
                val last = lastPush
                if (last == null ||
                    StoryProgressModel.shouldPushToPill(
                        last.overall, last.genPhase, lastPushAt, lead.overall, lead.genPhase, now,
                        // 换书 / 换章必推：接棒者进度更低时「涨 ≥0.01」恒假，不开这道门药丸会一直挂着旧主角。
                        identityChanged = last.storyId != lead.storyId || last.chapterNumber != lead.chapterNumber,
                    )
                ) {
                    lastPush = lead
                    lastPushAt = now
                    foregroundController.updateStoryProgress(lead)
                }
            }
        }
    }

    /**
     * 启动一章生成（1:1 iOS `startGeneration` :37-170）。去重：同一故事已有活跃任务则直接返回。
     * 非 suspend，可从任意 ViewModel 调用；status=generating 的 DB 写在生成协程内首步完成（与 iOS 同步保存等价）。
     */
    fun startGeneration(story: StoryEntity) {
        val storyId = story.id
        val chapterNumber = StoryGenerationProgressLogic.nextChapterNumber(story.cachedLatestChapterNumber)
        val storyTitle = story.title

        // LAZY 建 job → putIfAbsent 原子去重（= iOS `guard activeTasks[id] == nil`）→ 仅首个启动；落败的 lazy job 从未运行体。
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runGeneration(storyId, storyTitle, chapterNumber, fromStatus = story.status)
        }
        if (activeTasks.putIfAbsent(storyId, job) != null) {
            job.cancel()
            return
        }
        // 「推进起点」记账：用户主动要求写下一章 ⇒ 他已经跟当前最新章处理完了。续读时据此把落点前移一章
        // （[StoryReadingProgressLogic.preferredResumeChapter]），免得「第 3 章都写好了，进来还是第 2 章」。
        // 本方法是**用户主动生成的唯一入口**——追更自动路（StoryAutoSerializeService）直调 service、不经这儿，
        // 故半夜自动更新的章不会把用户没读完的进度推着走。首章生成没有「上一章」可推进，跳过。
        story.cachedLatestChapterNumber?.takeIf { it > 0 }?.let { readingProgressStore.markAdvancedFrom(storyId, it) }
        _lastErrors.update { it - storyId }
        _activeGenerations.update {
            it + (
                storyId to GenerationProgress(
                    progress = StoryProgressModel.overall(StoryGenPhase.PREPARING, 0.0),
                    genPhase = StoryGenPhase.PREPARING,
                    phase = StoryProgressModel.phaseLabel(StoryGenPhase.PREPARING, chapterNumber),
                    storyTitle = storyTitle,
                    chapterNumber = chapterNumber,
                )
            )
        }
        Log.d(TAG, "startGeneration story=${storyId.take(8)} ch=$chapterNumber")
        job.start()
    }

    /** 生成协程主体（1:1 iOS startGeneration 内 Task 体 :61-168）。[fromStatus] = 启动时调用方手头的故事状态（观测闸用）。 */
    private suspend fun runGeneration(storyId: String, storyTitle: String, chapterNumber: Int, fromStatus: String?) {
        // 前台服务保活（11.1g-3）：手动生成期间也挂前台，防切走 app 被国产 ROM 杀（= iOS beginBackgroundTask 加强版）。
        foregroundController.acquire()
        // 一次 acquire 只许配一次 release：成功路要在清 map 之前先 release（E15 治闪帧），失败/超时/取消路走 finally，
        // 两条路都调 [releaseOnce] 由这枚 CAS 去重。**绝不能靠「计数钳 0」当去重**——控制器的计数是全局共享的
        // （聊天流式 / 备份 / 追更自动 / 第二本书都在同一本账上），多减那一次减掉的是别人的份额：前台服务被提前停掉、
        // 药丸随之清空，另一路明明还在生成却没了灵动岛，还丢了后台保活。
        val released = AtomicBoolean(false)
        fun releaseOnce() {
            if (released.compareAndSet(false, true)) foregroundController.release()
        }
        try {
            // status=generating 立即落库：供 11.1g recoverStuckStories 在进程死亡后识别卡死任务。放 try 内 → DB 写失败
            // 收敛到 catch 标 generationFailed（有意微偏 iOS「日志后照常起任务」：连状态都写不进的 DB，章节更写不进，提早失败更稳）。
            StoryStateTransitions.check(fromStatus, StoryStatus.GENERATING, "StoryGenerationTaskManager.runGeneration")
            storyRepository.updateStatus(storyId, StoryStatus.GENERATING, System.currentTimeMillis())

            val freshStory = storyRepository.getStory(storyId) ?: throw StoryGenerationError.NoApiConfig
            // 撰写段分母：本章预期字数（脏数据兜底 2000·E8）。freshStory 在手，捎带进回调闭包。
            val expectedChars = StoryProgressModel.expectedChars(freshStory.chapterLengthPreference)

            // 全局超时（= iOS timeoutWatchdog）：超时抛 TimeoutCancellationException、用户取消抛 CancellationException，二者可区分。
            // 卷一 V9 按创作配置分档：思考模型 600s / 其余 300s（思考+长章+编务兜底全链余量）。
            val thinking = service.creationConfig()?.isThinkingModel == true
            val chapter = withTimeout(StoryGenerationProgressLogic.generationTimeoutMs(thinking)) {
                val now = System.currentTimeMillis()
                val onPreview: (String, Int) -> Unit = { preview, totalChars ->
                    updateStreamingPreview(preview, storyId, totalChars, expectedChars)
                }
                val onPhase: (StoryGenPhase) -> Unit = { phase -> updatePhase(storyId, phase) }
                if (freshStory.cachedChapterCount == 0) {
                    service.generateFirstChapter(freshStory, now, onPreview, onPhase)
                } else {
                    service.generateNextChapter(freshStory, now, onPreview, onPhase)
                }
            }

            updatePhase(storyId, StoryGenPhase.DONE)
            activeTasks.remove(storyId)
            Log.d(TAG, "generation success story=${storyId.take(8)} ch=${chapter.chapterNumber}「${chapter.title}」")
            notifyGenerationFinished(storyTitle, chapter.chapterNumber, chapter.title, success = true, storyId = storyId)
            // 满格「第 N 章写好了」停留一拍，然后**先 release 后清 map**：release 使引用计数归零 → 前台服务 stop →
            // 收集器随之死掉 → 之后清 map 已无人消费 → 药丸从满格直接整条消失，不闪静默帧（E15）。
            // 计数 >0（并发聊天/备份/多故事）时 release 不停服，清 map 走正常回落语义。
            delay(StoryGenerationProgressLogic.PROGRESS_CLEAR_DELAY_MS)
            releaseOnce()
            // 只清「还停在 DONE」的自家尾巴（卷一 R1 🟡-1）：停留窗这 1.5s 里用户完全可能已点「继续生成」
            // （看到「第 N 章写好了」就接着看下一章 = 真实高频路径），新一轮的 PREPARING 条目已占住同一个 key。
            // 无条件 `it - storyId` 会把新条目连带抹掉 → 新一轮全程无药丸、无阅读器遮罩、无书架进度（章节仍照常落库，
            // 纯进度显示丢失，故格外难察觉）。失败路（handleFailure）的清除保持无守卫——那里清的就是自己，语义正确。
            _activeGenerations.update { if (it[storyId]?.genPhase == StoryGenPhase.DONE) it - storyId else it }
        } catch (timeout: TimeoutCancellationException) {
            val message = StoryGenerationProgressLogic.failureMessage(
                cause = timeout,
                isTimeout = true,
                timeoutMessage = StoryGenerationError.Timeout.message ?: StoryGenerationProgressLogic.MESSAGE_GENERIC_FAILURE,
            )
            handleFailure(storyId, storyTitle, chapterNumber, message)
        } catch (cancel: CancellationException) {
            // 用户取消（cancelGeneration）/ 后台保活到期：协程已被取消，DB 回写 + 通知须在 NonCancellable 内（见 handleFailure），再 rethrow。
            val message = StoryGenerationProgressLogic.failureMessage(cancel, isTimeout = false, timeoutMessage = "")
            handleFailure(storyId, storyTitle, chapterNumber, message)
            throw cancel
        } catch (e: Exception) {
            val message = StoryGenerationProgressLogic.failureMessage(e, isTimeout = false, timeoutMessage = "")
            handleFailure(storyId, storyTitle, chapterNumber, message)
        } finally {
            // 释放前台保活（超时/取消/失败路经此；成功路已在上面 releaseOnce 过，这里由 CAS 去重成真正的空转）。
            releaseOnce()
        }
    }

    /**
     * 失败收尾（原 1:1 iOS catch :130-167）：清任务/进度态 → 记 lastError → status=generationFailed → 通知。
     *
     * **ST11 拍板①（意图保留）**：此处**不再清一次性请求字段**（requestedEndingType/Detail/rewriteInstruction）。
     * 旧行为「失败即清」= 用户请求结局遇一次网络失败，点重试就静默变成普通续章（结局意图被系统吞掉）。
     * 现在意图留着：重试仍写结局章；要改主意，用户做任一其它推进动作即覆盖（[StoryRepository.clearEndingRequest]·§3.3）。
     * 这是本卷「失败不吞用户意图」的**唯一行为来源**，别把清除加回来。
     */
    private suspend fun handleFailure(storyId: String, storyTitle: String, chapterNumber: Int, message: String) {
        activeTasks.remove(storyId)
        _activeGenerations.update { it - storyId }
        _lastErrors.update { it + (storyId to message) }
        // 被取消时仍需完成 DB 回写 + 通知 → NonCancellable（= iOS catch 在取消后照常 save / sendNotification）。
        withContext(NonCancellable) {
            // DB 写失败仅记日志（= iOS catch 内 save 的 do/catch）；通知照发（iOS sendNotification 在 if-let save 之外）。
            try {
                val current = storyRepository.getStory(storyId)
                if (current != null) {
                    StoryStateTransitions.check(current.status, StoryStatus.GENERATION_FAILED, "StoryGenerationTaskManager.handleFailure")
                    storyRepository.markGenerationFailed(
                        storyId, StoryStatus.GENERATION_FAILED, System.currentTimeMillis(),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "故事失败状态保存失败 story=${storyId.take(8)}", e)
            }
            notifyGenerationFinished(storyTitle, chapterNumber, chapterTitle = null, success = false, storyId = storyId)
        }
        Log.w(TAG, "generation failed story=${storyId.take(8)} ch=$chapterNumber: $message")
    }

    /**
     * 取消生成（1:1 iOS `cancelGeneration` :172-177）。被取消任务的 catch 会落 generationFailed + 失败通知（= iOS）。
     * iOS 另清 timedOutStoryIDs；安卓用 TimeoutCancellationException 区分超时/取消，无需该集合。
     */
    fun cancelGeneration(storyId: String) {
        activeTasks.remove(storyId)?.cancel()
        _activeGenerations.update { it - storyId }
    }

    /** 重试生成（1:1 iOS `retryGeneration` :179-190）：清错误 → status=serializing → 重新 startGeneration。 */
    fun retryGeneration(story: StoryEntity) {
        _lastErrors.update { it - story.id }
        scope.launch {
            // 状态写失败仅记日志、不挡重试（= iOS save 失败后照常 startGeneration）；CancellationException 须放行。
            try {
                StoryStateTransitions.check(story.status, StoryStatus.SERIALIZING, "StoryGenerationTaskManager.retryGeneration")
                storyRepository.updateStatus(story.id, StoryStatus.SERIALIZING, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "故事状态保存失败 story=${story.id.take(8)} (serializing)", e)
            }
            startGeneration(story)
        }
    }

    /** 是否有活跃生成任务（1:1 iOS `isGenerating` :192-194）。 */
    fun isGenerating(storyId: String): Boolean = activeTasks.containsKey(storyId)

    /** 取走并清除最近错误（1:1 iOS `consumeLastError` :196-198）。 */
    fun consumeLastError(storyId: String): String? = _lastErrors.getAndUpdate { it - storyId }[storyId]

    /**
     * 恢复卡死故事（1:1 iOS `recoverStuckStories` :200-229）：扫 status==generating 但无活跃任务者 → generationFailed + 失败通知。
     * iOS 此路 **不** 清一次性请求字段（用普通 status 写），故走 [StoryRepository.updateStatus]。
     * app 启动时调用——**接线到启动 = 11.1g**（与 checkAndGenerateStories 一并），本块仅提供方法。
     */
    suspend fun recoverStuckStories() {
        val stuck = storyRepository.getStoriesByStatus(StoryStatus.GENERATING)
        if (stuck.isEmpty()) return
        val now = System.currentTimeMillis()
        for (story in stuck) {
            if (isGenerating(story.id)) continue
            StoryStateTransitions.check(story.status, StoryStatus.GENERATION_FAILED, "StoryGenerationTaskManager.recoverStuckStories")
            storyRepository.updateStatus(story.id, StoryStatus.GENERATION_FAILED, now)
            Log.w(TAG, "recoverStuckStories story=${story.id.take(8)} → generationFailed")
            notifyGenerationFinished(
                storyTitle = story.title,
                chapterNumber = StoryGenerationProgressLogic.nextChapterNumber(story.cachedLatestChapterNumber),
                chapterTitle = null,
                success = false,
                storyId = story.id,
            )
        }
    }

    /**
     * 阶段推进（真实事件驱动：Service 的 onPhase 发 FINALIZING/ARCHIVING，成功路发 DONE）。
     * 仅当该故事仍有进度态时才改（= iOS guard）；进度取 max 保证单调不减——preview（150ms 采样）
     * 与 onPhase 竞态下只进不退。
     */
    private fun updatePhase(storyId: String, genPhase: StoryGenPhase) {
        _activeGenerations.update { current ->
            val state = current[storyId] ?: return@update current
            current + (
                storyId to state.copy(
                    progress = maxOf(state.progress, StoryProgressModel.overall(genPhase, 0.0)),
                    genPhase = genPhase,
                    phase = StoryProgressModel.phaseLabel(genPhase, state.chapterNumber),
                )
                )
        }
    }

    /**
     * 更新流式预览 + 撰写段真实进度：Service 在源头节流后调用（≈6-7 次/秒），仅当有进度态时改。
     *
     * 首个**有效**（非空白）预览即把 PREPARING 切成 WRITING——清洗后为空说明正文还没成形，
     * 构思段就诚实停住（E5）；思考模型的长静默期同理不假爬（E1）。
     *
     * @param totalChars 累计已收原始字符数（Service 侧 buffer 长度）。
     * @param expectedChars 本章预期字数（分母·已过 [StoryProgressModel.expectedChars] 兜底）。
     */
    fun updateStreamingPreview(text: String, storyId: String, totalChars: Int, expectedChars: Int) {
        _activeGenerations.update { current ->
            val state = current[storyId] ?: return@update current
            if (state.genPhase == StoryGenPhase.PREPARING && text.isBlank()) {
                return@update current + (storyId to state.copy(streamingPreview = text))
            }
            val genPhase = if (state.genPhase == StoryGenPhase.PREPARING) StoryGenPhase.WRITING else state.genPhase
            val writingFraction = totalChars.toDouble() / expectedChars
            current + (
                storyId to state.copy(
                    progress = maxOf(state.progress, StoryProgressModel.overall(StoryGenPhase.WRITING, writingFraction)),
                    genPhase = genPhase,
                    phase = StoryProgressModel.phaseLabel(genPhase, state.chapterNumber),
                    streamingPreview = text,
                )
                )
        }
    }

    /**
     * 完成/失败本地通知（1:1 iOS `sendNotification` :243-278，f-2）。用户已拍板：成功+失败都发、「故事更新」独立渠道、文案 1:1 iOS。
     * 区别于 11.1g 的章节「解锁」通知——本通知是「生成完成/失败」事件。点击深链开 app（跳具体故事的路由留 11.1h-j）。
     */
    private suspend fun notifyGenerationFinished(
        storyTitle: String,
        chapterNumber: Int,
        chapterTitle: String?,
        success: Boolean,
        storyId: String,
    ) {
        if (!settingsRepository.appSettings.first().notifStoryFinishedEnabled) {
            Log.d(TAG, "故事完成/失败提醒已关闭，跳过通知 story=${storyId.take(8)} ch=$chapterNumber")
            return
        }
        Notifier.postStory(
            context = context,
            notificationId = storyNotificationId(storyId),
            storyId = storyId,
            title = StoryGenerationNotificationText.title(storyTitle),
            body = StoryGenerationNotificationText.body(chapterNumber, chapterTitle, success),
        )
        Log.d(TAG, "notifyGenerationFinished story=${storyId.take(8)} ch=$chapterNumber success=$success")
    }

    /** 故事通知 id：1:1 iOS identifier "storyGen_{storyID}"（同故事重发覆盖前条）；前缀哈希与他类通知 id 空间隔离。 */
    private fun storyNotificationId(storyId: String): Int = "storyGen_$storyId".hashCode()

    private companion object {
        const val TAG = "StoryGenTaskManager"
    }
}

/**
 * 从活跃生成映射挑「代表进度」转成前台服务活动（⑤ Live Update）。多故事并发时取进度最高的一路（单药丸只能显示一个·E3），
 * storyId 随之切换 → 点击深链跟着走；空映射 → null（让出故事槽）。纯函数（无副作用、不碰框架），便于单测。
 *
 * 吃 entries 而非 values：新结构要 storyId 当深链目标，丢 key 就取不到。
 */
internal fun leadForegroundProgress(
    gens: Map<String, StoryGenerationTaskManager.GenerationProgress>,
): ForegroundActivity.StoryProgress? =
    gens.entries.maxByOrNull { it.value.progress }?.let { (storyId, gen) ->
        ForegroundActivity.StoryProgress(
            storyId = storyId,
            overall = gen.progress.coerceIn(0.0, 1.0),
            genPhase = gen.genPhase,
            phaseLabel = StoryProgressModel.phaseLabel(gen.genPhase, gen.chapterNumber),
            shortLabel = StoryProgressModel.shortLabel(gen.genPhase),
            title = gen.storyTitle,
            chapterNumber = gen.chapterNumber,
        )
    }
