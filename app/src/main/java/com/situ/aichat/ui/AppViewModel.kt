package com.situ.aichat.ui

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import com.situ.aichat.data.local.SettingsPreferences
import com.situ.aichat.data.model.AppearanceState
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.BackgroundWorkDiagnostics
import com.situ.aichat.diagnostics.perf.PerfCollector
import com.situ.aichat.diagnostics.perf.PerfPassNames
import com.situ.aichat.diagnostics.perf.timedPass
import com.situ.aichat.meeting.MeetingMissedReactionService
import com.situ.aichat.meeting.MeetupNotificationService
import com.situ.aichat.moments.MomentInteractionService
import com.situ.aichat.moments.MomentRecoveryService
import com.situ.aichat.notification.CalendarNotificationScheduler
import com.situ.aichat.notification.NotificationNavigator
import com.situ.aichat.notification.StreakNotificationBridgeService
import com.situ.aichat.economy.CharacterEconomyMaintenanceService
import com.situ.aichat.gift.ProactiveGiftMaintenanceService
import com.situ.aichat.pet.PetMaintenanceService
import com.situ.aichat.pet.PetReminderSync
import com.situ.aichat.prompt.growth.GrowthAnalysisCoordinator
import com.situ.aichat.prompt.growth.RelationshipArchetypeCalibrator
import com.situ.aichat.redpacket.RedPacketExpirationScanService
import com.situ.aichat.story.StoryAutoSerializeService
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.DiaryGenerationWorker
import com.situ.aichat.work.EmbeddingBackfillWorker
import com.situ.aichat.work.MomentGenerationWorker
import com.situ.aichat.work.NotificationRescheduleWorker
import com.situ.aichat.work.NotificationTemplateWorker
import com.situ.aichat.work.OfflineSummaryScanWorker
import com.situ.aichat.work.PromiseBackfillWorker
import com.situ.aichat.work.UnansweredMessageRecoveryWorker
import com.situ.aichat.work.RedPacketExpirationWorker
import com.situ.aichat.work.ReliabilityPromptController
import com.situ.aichat.work.ScheduleGenerationWorker
import com.situ.aichat.shortcut.ConversationShortcutPublisher
import com.situ.aichat.util.AppNightModeSync
import com.situ.aichat.util.WallpaperMaintenanceService
import com.situ.aichat.widget.CharacterStatusWidgetSync
import com.situ.aichat.widget.MomentWidgetSync
import com.situ.aichat.widget.PetWidgetSync
import com.situ.aichat.work.WidgetRefreshWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val settings: SettingsPreferences,
    private val backgroundScheduler: BackgroundScheduler,
    private val notificationBridge: StreakNotificationBridgeService,
    private val notificationNavigator: NotificationNavigator,
    private val calendarNotificationScheduler: CalendarNotificationScheduler,
    private val momentInteractionService: MomentInteractionService,
    private val momentRecoveryService: MomentRecoveryService,
    private val petMaintenanceService: PetMaintenanceService,
    private val growthAnalysisCoordinator: GrowthAnalysisCoordinator,
    private val archetypeCalibrator: RelationshipArchetypeCalibrator,
    private val economyMaintenanceService: CharacterEconomyMaintenanceService,
    private val proactiveGiftMaintenanceService: ProactiveGiftMaintenanceService,
    private val redPacketExpirationScanService: RedPacketExpirationScanService,
    private val storyAutoSerializeService: StoryAutoSerializeService,
    private val petWidgetSync: PetWidgetSync,
    private val characterStatusWidgetSync: CharacterStatusWidgetSync,
    private val momentWidgetSync: MomentWidgetSync,
    private val petReminderSync: PetReminderSync,
    private val conversationShortcutPublisher: ConversationShortcutPublisher,
    private val reliabilityPromptController: ReliabilityPromptController,
    private val appNightModeSync: AppNightModeSync,
    private val wallpaperMaintenanceService: WallpaperMaintenanceService,
    private val meetupNotificationService: MeetupNotificationService,
    private val meetingMissedReactionService: MeetingMissedReactionService,
    private val backgroundWorkDiagnostics: BackgroundWorkDiagnostics,
    private val perfCollector: PerfCollector,
    private val settingsRepository: SettingsRepository,
    private val messageRepository: MessageRepository,
    private val worldLinkRunner: com.situ.aichat.world.link.WorldLinkRunner,
) : ViewModel() {

    /**
     * P12.6 D2：**进程级**前后台观察者（ProcessLifecycleOwner，非界面级 LocalLifecycleOwner）。挂在「survive 转屏」的
     * AppViewModel 上（init 注册 / onCleared 移除）：转屏/切深浅色重建界面时 VM 不重建 → 观察者不重挂 → 不会重放 ON_RESUME、
     * 不再误触发整套「回前台维护」（通知物化/补朋友圈日记/日程/扫红包/宠物经济维护…）。拿真·进程前后台语义 + 免疫转屏重入。
     */
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) = onAppForeground()
        override fun onStop(owner: LifecycleOwner) = onAppBackground()
    }

    /** 前台朋友圈恢复循环（=iOS 4 分钟 Timer）；回前台启动、进后台取消。 */
    private var momentRecoveryLoopJob: Job? = null

    /** 回前台「待互动 drain + 一次恢复」的一次性任务；守卫式启动，避免多次 ON_RESUME 叠跑。 */
    private var momentForegroundPassJob: Job? = null

    /** 回前台「世界联动通行证」的一次性任务（W5·懒结算→记忆→情绪→小报→排嵌入）；守卫式启动防 ON_RESUME 叠跑。 */
    private var worldLinkPassJob: Job? = null

    /** 回前台宠物批量维护（衰减/进化/日记）的一次性任务；守卫避免多次 ON_RESUME 叠跑。 */
    private var petMaintenancePassJob: Job? = null
    /** 回前台角色真经济维护（月薪/发薪/房租/奖金/日程消费）的一次性任务；守卫避免多次 ON_RESUME 叠跑。 */
    private var economyMaintenancePassJob: Job? = null
    /** 回前台 24h 启动自愈维护（闲置角色关系淡化 14.7b…）的一次性任务；内部各自 24h 节流，守卫避免多次 ON_RESUME 叠跑。 */
    private var coldStartMaintenancePassJob: Job? = null
    /** null = still loading from DataStore; true = show agreement; false = proceed to app. */
    val showAgreement: StateFlow<Boolean?> = settings.needsAgreement
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * null = still loading; true = show 4-page welcome onboarding; false = proceed to app.
     * 仅在协议已同意后才消费（gate 顺序 1:1 iOS：协议 → 引导 → 主界面）。
     */
    val showOnboarding: StateFlow<Boolean?> = settings.hasCompletedOnboarding
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 根部主题外观（主题配色 + 深浅模式 + 动态取色，11.4a）。初值=默认（跟随系统+默认暖陶）→ 无首帧闪烁。 */
    val appearance: StateFlow<AppearanceState> =
        combine(settings.appearanceMode, settings.useDynamicColor, settings.themePalette) { mode, dynamic, palette ->
            AppearanceState(mode, dynamic, palette)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppearanceState.DEFAULT)

    /**
     * Splash 释放门控（P1-9+28）：协议门控 + 外观偏好（深浅 + 配色）均已从 DataStore 读出才放首帧
     * （防 LoadingGate 转圈闪一下 + 主题错档首帧）。SharingStarted.Eagerly：VM 创建即预热——
     * splash 正挡着绘制，组合订阅（WhileSubscribed）此时不可靠；combine 只看「都发过一次值」，不读具体值。
     */
    val splashReady: StateFlow<Boolean> =
        combine(
            settings.needsAgreement,
            settings.appearanceMode,
            settings.useDynamicColor,
            settings.themePalette,
        ) { _, _, _, _ -> true }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 通知点击 → 待跳转的会话 uuid（[com.situ.aichat.ui.AIChatApp] 观察后导航）。 */
    val pendingNavConversation: StateFlow<String?> = notificationNavigator.pendingConversation

    /** 朋友圈互动通知点击 → 待跳转的帖子 uuid（决策①，P7.2.8）。 */
    val pendingNavMoment: StateFlow<String?> = notificationNavigator.pendingMoment

    /** 朋友圈「N 位好友」合并通知点击 → 待跳转朋友圈 feed（13.7e）。 */
    val pendingNavMomentsFeed: StateFlow<Boolean> = notificationNavigator.pendingMomentsFeed

    /** 宠物小组件点击 → 待跳转的宠物详情 characterUuid（P11.3）。 */
    val pendingNavPet: StateFlow<String?> = notificationNavigator.pendingPet

    /**
     * 跳「联系人」Tab 的**一次性导航信号**（导航后即 consume）。来源：① 分享给角色通用分享（13.10a，选择条数据另由
     * [com.situ.aichat.share.ShareTargetCoordinator] 持有）；② 快捷设置磁贴「找角色」（13.10c）。
     */
    val pendingNavContacts: StateFlow<Boolean> = notificationNavigator.pendingContacts

    /** 里程碑庆祝通知点击 → 待跳转的角色资料页 characterUuid（P1-33）。 */
    val pendingNavCharacterProfile: StateFlow<String?> = notificationNavigator.pendingCharacterProfile

    /** 自动备份通知点击 → 跳备份设置的一次性信号（P15·P0-19；值=focusFolder，true 时进页自动重选目录）。 */
    val pendingNavBackup: StateFlow<Boolean?> = notificationNavigator.pendingBackup

    /** 故事章节解锁/完成/失败通知点击 → 待跳转的故事详情 storyId（U4）。 */
    val pendingNavStory: StateFlow<String?> = notificationNavigator.pendingStory

    /** 世界通知点击 → 待压栈世界屏的一次性信号（W9a）。 */
    val pendingNavWorld: StateFlow<Boolean> = notificationNavigator.pendingWorld

    /** 主动后台可靠性引导对话框是否可见（13.7a；首次开启后台功能时弹一次，app 根观察渲染）。 */
    val showReliabilityPrompt: StateFlow<Boolean> = reliabilityPromptController.visible

    /** 关闭后台可靠性引导对话框（「去设置」或「暂不」均调；一次性 flag 已在弹出时记下）。 */
    fun dismissReliabilityPrompt() = reliabilityPromptController.dismiss()

    init {
        scheduleBackgroundWork()
        // P11.3：启动宠物小组件响应式同步（观察宠物流 → 状态变化时刷新小组件）。
        petWidgetSync.start()
        // 13.9a：启动角色「此刻」状态小组件响应式同步（观察会话+角色流 → 主对话身份变化时刷新小组件）。
        characterStatusWidgetSync.start()
        // 13.9b：启动最新动态（朋友圈）小组件响应式同步（观察 feed 流 → 最新角色帖变化时刷新小组件）。
        momentWidgetSync.start()
        // 13.7c：启动宠物饿/病提醒响应式重排（观察宠物流 → 喂食/护理/衰减/聊天后重算精确闹钟）。
        petReminderSync.start()
        // 13.5 C2：启动对话快捷方式发布（观察会话流 → 把最近会话推成图标长按动态快捷方式）。
        conversationShortcutPublisher.start()
        // C3#0：启动深浅偏好→系统级 per-app night mode 镜像（API 31+·治「强制深/浅与系统反向」冷启反色闪屏）。
        appNightModeSync.start()
        // 时间感知优化·修 A：一次性把老角色/全局模块里的「时间感知/此刻」顺序抬到 suffix 末尾（紧贴生成处）。
        // best-effort：解码失败等不影响启动，flag 已置不反复试；新建角色走默认本就在末尾。
        viewModelScope.launch { runCatching { settingsRepository.migratePromptModuleTimeOrderOnce() } }
        // 见面记忆前置迁移（2026-07-11 拍板）：默认位置 SUFFIX→PREFIX,老用户未自定义位置的一次性归位。
        viewModelScope.launch { runCatching { settingsRepository.migratePromptModuleMeetingMemoryOnce() } }
        // 短信腔四件线下退场迁移（两语境模型 2026-07-12）：老用户四模块 enabledScenes null→setOf(ONLINE_CHAT) 的一次性归位。
        viewModelScope.launch { runCatching { settingsRepository.migratePromptModuleSceneDefaultsOnce() } }
        // P12.6 D2：挂进程级前后台观察者（放在 init 末尾，使启动时的 ON_RESUME 重放在 scheduleBackgroundWork 之后触发
        // onAppForeground，时序与旧 AppRoot DisposableEffect 一致）。ProcessLifecycleOwner 是进程单例 → 须在 onCleared 移除防泄漏。
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
    }

    override fun onCleared() {
        // Activity 真正结束（非转屏）→ VM 清除 → 摘除进程级观察者（转屏时 onCleared 不调、观察者保留 = 不重触发）。
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        super.onCleared()
    }

    /**
     * App 回前台（ON_RESUME）：把已投递通知物化成会话消息（对齐 iOS .task + scenePhase .active 两处调用点）。
     */
    fun onAppForeground() {
        // 性能采集·尺 2（图纸 2026-07-30 chunk 3）：给每个 pass 套一层 perfTrace.timedPass 秒表。
        // 采集关闭时 perfTrace = null，timedPass 内联后是**零成本直通**（不取时间、不分配）。
        // 只加包裹：pass 的内容 / 顺序 / 线程 / 既有 job 守卫 / KEEP 策略一律不动；`} }` 收尾是为了让
        // 被包裹的代码块保持原缩进——diff 里只看得到包裹本身，方便逐行核对「真的没改内容」。
        val perfTrace = perfCollector.beginForegroundTrace()
        // ③ Android16 后台诊断：另起协程打一张「后台健康快照」到 logcat（排队为何不跑 + 最近被停原因），
        // 与下方功能性维护解耦——诊断慢/异常都不拖延、不波及正常回前台流程。
        viewModelScope.launch { perfTrace.timedPass(PerfPassNames.BG_DIAGNOSTICS) { backgroundWorkDiagnostics.logForegroundSnapshot() } }
        viewModelScope.launch { perfTrace.timedPass(PerfPassNames.NOTIFY_MATERIALIZE) {
            notificationBridge.materializeDeliveredNotifications()
            // 忙碌延迟回复功能已删除（2026-07-11 用户拍板）：回前台一次性释放**全部**存量暂扣消息
            // （老版本可能留有 isHeldForDelivery=true 的隐藏消息，不清会永久不可见；无存量时 = 零行 no-op）。
            messageRepository.releaseAllHeldMessages()
            // P6.3：物化完再刷日历事件通知（对齐 iOS scenePhase .active → runCalendarNotificationRefresh）。
            // 顺序很关键——先物化把已触发的日历通知回填 deliveredAt + 物化，避免刷新清旧时误取消刚触发未排干的台账。
            calendarNotificationScheduler.refreshForForeground()
            // Phase 10 未来约定见面：回前台重烤到点闹钟（精确闹钟不跨 force-stop / 重启；boot 另由 NotificationRescheduleWorker 兜）。
            meetupNotificationService.rescheduleAll()
            // P9.3b 红包：回前台扫过期（24h 退回）+ 22h 预警催拆（1:1 iOS scenePhase active → scan）。纯本地不需网络。
            redPacketExpirationScanService.scan()
        } }
        // P12.6 D4 + 14.7a：回前台补一发日程生成。iOS scenePhase .active 会 backfillMissedDays + ensureTodaySchedules；
        // 安卓经此 UNIQUE_ENSURE_TODAY worker 的 doWork 内部同样先 backfillMissedDays（补最近≤7 天缺日）再 ensureTodaySchedules
        // （14.7a 已补齐历史缺失日补算，对齐 iOS）。此前日程只在冷启动 scheduleBackgroundWork + 每日 24h 周期 worker 跑，回前台**独缺**
        // （朋友圈/日记/宠物/经济/红包/见面摘要/未答恢复都补、唯日程没补）——App 挂后台跨夜/跨多日再打开，当天及缺失日日程可能短时
        // 还没生成好（HyperOS 杀后台时更明显）。两步均幂等（已生成的日跳过），KEEP 防与启动一次性/周期重入。
        perfTrace.timedPass(PerfPassNames.SCHEDULE_ENSURE_TODAY) { backgroundScheduler.scheduleOneShot(
            uniqueName = ScheduleGenerationWorker.UNIQUE_ENSURE_TODAY,
            workerClass = ScheduleGenerationWorker::class.java,
            requireNetwork = true,
            existingPolicy = ExistingWorkPolicy.KEEP,
        ) }
        // 13.7d：回前台也重排一次主动消息通知（对齐 iOS 每次 scenePhase .active 都重烤刷新文案/重排精确闹钟）。
        // 之前通知重排只在冷启动 + 每日周期跑，回前台独缺——挂后台跨日再打开时，新一天的文案/时刻能及时续上。
        // scheduleAll 内有 shouldRebuild 节流（日期/火花/事件数没变即 NO-OP，不调 LLM、不烧 token）；KEEP 防与冷启动/周期重入。无网也跑（回退模板）。
        backgroundScheduler.scheduleOneShot(
            uniqueName = NotificationRescheduleWorker.UNIQUE_ONESHOT,
            workerClass = NotificationRescheduleWorker::class.java,
            requireNetwork = false,
            existingPolicy = ExistingWorkPolicy.KEEP,
        )
        // P7.1.2 日记：回前台触发自动生成（1:1 iOS scenePhase .active → runDiaryGeneration，先补昨天再查今天）。
        // KEEP 防与周期兜底重入；协调器自带并发锁 + 时间门槛 + 去重，重复排入安全。
        backgroundScheduler.scheduleOneShot(
            uniqueName = DiaryGenerationWorker.UNIQUE_ENSURE,
            workerClass = DiaryGenerationWorker::class.java,
            requireNetwork = true,
            existingPolicy = ExistingWorkPolicy.KEEP,
        )
        // P7.2.3 朋友圈：回前台触发自动发帖（1:1 iOS scenePhase .active → checkAndGeneratePosts）。
        // KEEP 防与周期兜底重入；Service 自带重入锁 + 频率/冷却/睡眠守卫，重复排入安全。
        backgroundScheduler.scheduleOneShot(
            uniqueName = MomentGenerationWorker.UNIQUE_ENSURE,
            workerClass = MomentGenerationWorker::class.java,
            requireNetwork = true,
            existingPolicy = ExistingWorkPolicy.KEEP,
        )
        // P10.2d 见面摘要重试链 ④回前台扫描层（1:1 iOS scenePhase active → scanAndRetry + healOneFallbackIfDue）。
        // KEEP 防与周期/冷启动重入；退避判断在 worker 内（Room 状态），重复排入安全。
        backgroundScheduler.scheduleOneShot(
            uniqueName = OfflineSummaryScanWorker.UNIQUE_ENSURE,
            workerClass = OfflineSummaryScanWorker::class.java,
            requireNetwork = true,
            existingPolicy = ExistingWorkPolicy.KEEP,
        )
        // P10.2g 未答恢复 ④回前台扫描层（1:1 iOS scenePhase active → recoverIfNeeded）。KEEP 防与冷启动重入；
        // 服务自带 AtomicBoolean 互斥 + 串行 + 幂等（回复落库自然出候选），重复排入安全；要联网（LLM）→ requireNetwork=true。
        backgroundScheduler.scheduleOneShot(
            uniqueName = UnansweredMessageRecoveryWorker.UNIQUE_ENSURE,
            workerClass = UnansweredMessageRecoveryWorker::class.java,
            requireNetwork = true,
            existingPolicy = ExistingWorkPolicy.KEEP,
        )
        // 图纸 2026-09-01 件④：回前台补一发向量回填（原独缺此路——瞬态嵌入失败留 NULL 后，自愈延迟从「下次冷启动」缩到「下次打开」）。
        // worker 先 EXISTS 秒探测，无缺失零成本；KEEP 防与冷启动/导入重入；纯本地 ONNX 不需网。
        backgroundScheduler.scheduleOneShot(
            uniqueName = EmbeddingBackfillWorker.UNIQUE_ENSURE,
            workerClass = EmbeddingBackfillWorker::class.java,
            requireNetwork = false,
            existingPolicy = ExistingWorkPolicy.KEEP,
        )
        // P7.2.5 朋友圈韧性：回前台补处理待互动队列 + 恢复被国产 ROM 杀后台冲掉的延迟互动（场景 A/B/C）。
        // 1:1 iOS 回前台 runPendingMomentInteractions → runMomentRecovery。守卫避免多次 ON_RESUME 叠跑；
        // 不随后台取消（在 viewModelScope 跑完，更稳）；恢复服务自带重入锁。
        if (momentForegroundPassJob?.isActive != true) {
            momentForegroundPassJob = viewModelScope.launch { perfTrace.timedPass(PerfPassNames.MOMENT_PASS) {
                momentInteractionService.processPendingInteractions()
                momentRecoveryService.recoverIfNeeded()
            } }
        }
        // Phase 11 未来约定见面爽约：回前台扫「已确认、过宽限、未赴约」的约定 → 置 missed + 角色按人设自适应反应（旁白喂
        // LLM·无头生成）。服务自带重入互斥 + 终态守卫(只反应一次)；独立 launch 不让 LLM 生成阻塞其余回前台维护。
        viewModelScope.launch { perfTrace.timedPass(PerfPassNames.MEETING_MISSED) { meetingMissedReactionService.scanAndReact() } }
        // P8.2 宠物：回前台批量惰性衰减 + 进化检查 + 宠物日记（1:1 iOS AppBootstrapService.applyPetDecayAndGrowthCheck）。
        // 衰减按时间戳惰性补算（不足 1 小时 no-op、幂等）；守卫避免多次 ON_RESUME 叠跑（同 momentForegroundPass 模式）。
        if (petMaintenancePassJob?.isActive != true) {
            petMaintenancePassJob = viewModelScope.launch { perfTrace.timedPass(PerfPassNames.PET_MAINTENANCE) {
                petMaintenanceService.runStartupMaintenance()
            } }
        }
        // P9.1b 角色真经济：回前台遍历角色跑月薪推断/入职/发薪/奖金/房租/昨日日程消费（1:1 iOS AppBootstrapService）。
        // 串行、幂等 key + last*Date 防重复；守卫避免多次 ON_RESUME 叠跑。currencySystemEnabled 关则整块跳过。
        if (economyMaintenancePassJob?.isActive != true) {
            economyMaintenancePassJob = viewModelScope.launch { perfTrace.timedPass(PerfPassNames.ECONOMY_MAINTENANCE) {
                economyMaintenanceService.runMaintenance()
                // P9.2c：经济维护之后跑主动送礼（角色须先发薪，钱包有月薪→经济档位/预算非零→真能触发）。
                // 串行、幂等 key + 同日闸门 + last*Date 防重复；Service 自带重入锁。currency/proactiveGift 开关关则整块跳过。
                proactiveGiftMaintenanceService.runMaintenance()
            } }
        }
        // 14.7b 启动自愈维护：闲置角色关系淡化扫（1:1 iOS AppBootstrapService.checkAndApplyRelationshipDecay）。
        // iOS 每 24h 启动遍历所有角色应用关系淡化（与聊天驱动的分析前淡化是两条独立调用点）；安卓此前只有聊天驱动一条
        // → 从不/极少聊天的角色永不淡化。内部 24h 节流 + growthSystemEnabled 门控 + 逐角色写锁；不调 LLM、纯本地。
        // 守卫避免多次 ON_RESUME 叠跑（同 pet/economy 模式）；放 viewModelScope（轻量、跑完不随后台取消更稳）。
        if (coldStartMaintenancePassJob?.isActive != true) {
            coldStartMaintenancePassJob = viewModelScope.launch { perfTrace.timedPass(PerfPassNames.COLD_START_HEAL) {
                // 成长原型校准（图纸 §3.3 入口④·D-14）：先于衰减做内容指纹全量扫（进程内一次·指纹变才扫），
                // 让本次衰减即吃到新原型地板。失败不影响后续维护。
                runCatching { archetypeCalibrator.runStartupSweepIfNeeded() }
                growthAnalysisCoordinator.runIdleRelationshipDecay()
                // 清裁剪壁纸孤儿文件（复核 confirmed MED·防长期累积·失败不影响其它维护）。
                runCatching { wallpaperMaintenanceService.purgeOrphanWallpapers() }
            } }
        }
        // P11 11.1g-2 故事：回前台故事 pass（卡死恢复 + 解锁闹钟重排 + 追更自动生成检查）。
        // 服务内自跑 app-scope + 自带守卫/防抖/互斥（生成须 survive VM/屏幕切换 → 不放 viewModelScope）；
        // 用户拍板「开 app 生成」→ 仅回前台触发，不做周期后台生成（前台服务保活 = 11.1g-3）。
        perfTrace.timedPass(PerfPassNames.STORY_PASS) { storyAutoSerializeService.onAppForeground() }
        // W5 世界联动通行证（契约 §9·结算首次真实消费者）：懒结算→抄双视角记忆→落情绪→拼开机小报→排嵌入回填。
        // 守卫避免多次 ON_RESUME 叠跑（同 momentForegroundPass 模式）；Runner 自带 Mutex + 每步 try/catch，世界永不死机。
        if (worldLinkPassJob?.isActive != true) {
            worldLinkPassJob = viewModelScope.launch { perfTrace.timedPass(PerfPassNames.WORLD_LINK) {
                worldLinkRunner.runForegroundPass(System.currentTimeMillis())
            } }
        }
        perfTrace.timedPass(PerfPassNames.MOMENT_LOOP) { startMomentForegroundRecoveryLoop() }
        // 主线程同步段到此结束 —— 记成 entry_main_thread 这一条 pass（M13 的「单趟 ms」看它）。
        // 注：viewModelScope 默认 Dispatchers.Main.immediate，上面各 launch 的开头是**不派发直接跑**的，
        // 那部分真实主线程开销也计在本条里 —— 这正是要量的东西。
        perfTrace?.recordEntryMainThread()
    }

    /**
     * App 进入后台（ON_STOP）：停前台朋友圈恢复循环（=iOS stopForegroundTimer，避免后台空转）。
     * 进行中的一次性恢复（[momentForegroundPassJob]）让它跑完——安卓前台协程被杀前可继续，更利韧性。
     */
    fun onAppBackground() {
        momentRecoveryLoopJob?.cancel()
        momentRecoveryLoopJob = null
    }

    /** 前台每 4 分钟扫一遍丢失的 AI 互动（=iOS 4 分钟 Timer）。守卫式启动，避免重复创建循环。 */
    private fun startMomentForegroundRecoveryLoop() {
        if (momentRecoveryLoopJob?.isActive == true) return
        momentRecoveryLoopJob = viewModelScope.launch {
            while (isActive) {
                delay(MOMENT_RECOVERY_INTERVAL_MS)
                momentRecoveryService.recoverIfNeeded()
            }
        }
    }

    /** 跳转完成后清除待跳转目标。 */
    fun consumeNavConversation() = notificationNavigator.consume()

    /** 朋友圈帖子跳转完成后清除目标。 */
    fun consumeNavMoment() = notificationNavigator.consumeMoment()

    /** 朋友圈 feed 跳转完成后清除信号（13.7e）。 */
    fun consumeNavMomentsFeed() = notificationNavigator.consumeMomentsFeed()

    fun consumeNavCharacterProfile() = notificationNavigator.consumeCharacterProfile()

    /** 宠物详情跳转完成后清除目标。 */
    fun consumeNavPet() = notificationNavigator.consumePet()

    /** 跳到联系人后清除一次性导航信号（13.10a 分享 / 13.10c 磁贴共用；分享选择条文本不动，由 ContactsViewModel 投递/取消时清）。 */
    fun consumeNavContacts() = notificationNavigator.consumeContacts()

    /** 跳到备份设置后清除一次性导航信号（P15·P0-19）。 */
    fun consumeNavBackup() = notificationNavigator.consumeBackup()

    /** 故事详情跳转完成后清除目标（U4）。 */
    fun consumeNavStory() = notificationNavigator.consumeStory()

    fun consumeNavWorld() = notificationNavigator.consumeWorld()

    /**
     * 排背景任务（P5.1）：每日日程生成（周期，缺则补）+ 启动即补一发，保证今日日程及时就绪。
     * 任务自身幂等且受系统开关/有无 API 配置守卫，重复排入安全（unique work 去重）。
     */
    private fun scheduleBackgroundWork() {
        backgroundScheduler.schedulePeriodic(
            uniqueName = ScheduleGenerationWorker.UNIQUE_DAILY,
            workerClass = ScheduleGenerationWorker::class.java,
            repeatInterval = Duration.ofHours(24),
            requireNetwork = true,
        )
        backgroundScheduler.scheduleOneShot(
            uniqueName = ScheduleGenerationWorker.UNIQUE_ENSURE_TODAY,
            workerClass = ScheduleGenerationWorker::class.java,
            requireNetwork = true,
        )
        // P6.1c 通知：每日重排（兜底长时间不开 App）+ 启动一次性重排（对齐 iOS 启动调度）。无网也跑（回退模板文案）。
        backgroundScheduler.schedulePeriodic(
            uniqueName = NotificationRescheduleWorker.UNIQUE_DAILY,
            workerClass = NotificationRescheduleWorker::class.java,
            repeatInterval = Duration.ofHours(24),
            requireNetwork = false,
        )
        backgroundScheduler.scheduleOneShot(
            uniqueName = NotificationRescheduleWorker.UNIQUE_ONESHOT,
            workerClass = NotificationRescheduleWorker::class.java,
            requireNetwork = false,
        )
        // 启动补生成：对仍在用默认文案的角色重生成通知文案（worker 内 24h 节流，对齐 iOS regenerateIfUsingDefaults）。
        backgroundScheduler.scheduleOneShot(
            uniqueName = NotificationTemplateWorker.UNIQUE_REGENERATE_DEFAULTS,
            workerClass = NotificationTemplateWorker::class.java,
            requireNetwork = true,
        )
        // P7.1.2 日记自动生成：每 15 分钟周期兜底（对抗 HyperOS 杀后台，决策③）。时间门槛/去重由协调器内部判定，
        // 未到设定时刻即 no-op；回前台触发见 [onAppForeground]。
        backgroundScheduler.schedulePeriodic(
            uniqueName = DiaryGenerationWorker.UNIQUE_DAILY,
            workerClass = DiaryGenerationWorker::class.java,
            repeatInterval = Duration.ofMinutes(15),
            requireNetwork = true,
        )
        // P7.2.3 朋友圈自动发帖：每 15 分钟周期兜底（对抗 HyperOS 杀后台，决策③）。频率/冷却/睡眠守卫由 Service 判定。
        // 13.7e：仅此「周期后台」路传 notify=true → 用户不在 app 时发的帖推「X 发了新动态」；回前台 ENSURE 路不传（不打扰）。
        // existingPolicy=UPDATE（非默认 KEEP）：本周期任务 P7.2.3 起就有，老用户原地升级时 KEEP 会沿用旧 spec（无此
        // inputData）→ 新功能静默失效；UPDATE 刷新 inputData（不重置 15min 周期），让已装机用户也收到通知。
        backgroundScheduler.schedulePeriodic(
            uniqueName = MomentGenerationWorker.UNIQUE_DAILY,
            workerClass = MomentGenerationWorker::class.java,
            repeatInterval = Duration.ofMinutes(15),
            requireNetwork = true,
            existingPolicy = ExistingPeriodicWorkPolicy.UPDATE,
            inputData = workDataOf(MomentGenerationWorker.KEY_NOTIFY_NEW_POST to true),
        )
        // P9.3b 红包过期扫描：每 6 小时周期兜底（过期不紧迫，钱锁托管账户安全；预警精确触发靠精确闹钟）。无网也跑（纯本地）。
        backgroundScheduler.schedulePeriodic(
            uniqueName = RedPacketExpirationWorker.UNIQUE_PERIODIC,
            workerClass = RedPacketExpirationWorker::class.java,
            repeatInterval = Duration.ofHours(6),
            requireNetwork = false,
        )
        // P10.2d 见面摘要重试链：周期兜底（③④扫 pending 退避重试 + ⑤24h 自愈，对抗 HyperOS 杀后台）+ 启动一次性扫描。
        // 退避状态在 Room（自带粒度），周期只负责「何时跑一次」；要联网（LLM 摘要）→ requireNetwork=true。
        backgroundScheduler.schedulePeriodic(
            uniqueName = OfflineSummaryScanWorker.UNIQUE_PERIODIC,
            workerClass = OfflineSummaryScanWorker::class.java,
            repeatInterval = Duration.ofHours(6),
            requireNetwork = true,
        )
        backgroundScheduler.scheduleOneShot(
            uniqueName = OfflineSummaryScanWorker.UNIQUE_ENSURE,
            workerClass = OfflineSummaryScanWorker::class.java,
            requireNetwork = true,
        )
        // P10.2g 未答恢复：冷启动一次性扫描（1:1 iOS 冷启动 recoverIfNeeded）。要联网（LLM）→ requireNetwork=true。
        backgroundScheduler.scheduleOneShot(
            uniqueName = UnansweredMessageRecoveryWorker.UNIQUE_ENSURE,
            workerClass = UnansweredMessageRecoveryWorker::class.java,
            requireNetwork = true,
        )
        // 12.3 嵌入回填自愈：冷启动一次性扫描缺 embedding 的历史消息（导入旧备份/早期消息/嵌入器曾不可用）并后台
        // 分批补嵌入（1:1 iOS 启动回填）。纯本地 ONNX → 不需联网；worker 先 EXISTS 秒探测，无缺失则秒退不加载模型。
        // KEEP：不打断已在跑的回填（幂等自推进，下次冷启动会再排）。
        backgroundScheduler.scheduleOneShot(
            uniqueName = EmbeddingBackfillWorker.UNIQUE_ENSURE,
            workerClass = EmbeddingBackfillWorker::class.java,
            requireNetwork = false,
            existingPolicy = ExistingWorkPolicy.KEEP,
        )
        // 记忆改造一期（图纸 §3.11）：承诺账本历史回填冷启动一次性扫描——把存量见面档案 promisesJson 的约定注册进账本。
        // 纯本地（无 LLM）→ 不需联网；worker 内先查 device-local 标记，回填过则秒退。KEEP：已排过不重排（幂等·注册端去重）。
        backgroundScheduler.scheduleOneShot(
            uniqueName = PromiseBackfillWorker.UNIQUE_ONCE,
            workerClass = PromiseBackfillWorker::class.java,
            requireNetwork = false,
            existingPolicy = ExistingWorkPolicy.KEEP,
        )
        // 13.9 桌面小组件定期刷新：每 30 分钟 nudge 一次重渲染（对齐 iOS PetWidget 30min timeline；让被 HyperOS 杀后台后
        // 角色「此刻」状态也能按当前时间翻新——小组件渲染时现算，本 worker 只负责「何时再渲染一次」）。即时刷新（数据变）见各
        // *WidgetSync。纯本地（不调 LLM/网络）→ requireNetwork=false；KEEP 防与已排周期重入。
        backgroundScheduler.schedulePeriodic(
            uniqueName = WidgetRefreshWorker.UNIQUE_PERIODIC,
            workerClass = WidgetRefreshWorker::class.java,
            repeatInterval = Duration.ofMinutes(30),
            requireNetwork = false,
        )
    }

    fun acceptAgreement() {
        viewModelScope.launch { settings.acceptCurrentAgreement() }
    }

    /** 首启欢迎引导看完（11.4b）→ 持久化 hasCompletedOnboarding=true，下次直接进主界面。 */
    fun completeOnboarding() {
        viewModelScope.launch { settings.completeOnboarding() }
    }

    private companion object {
        /** 前台朋友圈恢复循环周期：4 分钟（iOS `MomentRecoveryService` foregroundTimer 4*60）。 */
        const val MOMENT_RECOVERY_INTERVAL_MS = 4L * 60 * 1000
    }
}
