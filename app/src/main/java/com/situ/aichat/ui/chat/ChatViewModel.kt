package com.situ.aichat.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.calendar.CalendarReader
import com.situ.aichat.data.calendar.CalendarWriter
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.dao.RedPacketDao
import com.situ.aichat.economy.CharacterEconomicStateService
import com.situ.aichat.economy.CurrencyService
import com.situ.aichat.foreground.LlmGenerationForegroundController
import com.situ.aichat.network.NetworkMonitor
import com.situ.aichat.gift.GiftItem
import com.situ.aichat.gift.GiftSendService
import com.situ.aichat.redpacket.RedPacketAcceptanceDecisionService
import com.situ.aichat.redpacket.RedPacketExpirationScanService
import com.situ.aichat.redpacket.RedPacketSendOutcome
import com.situ.aichat.redpacket.RedPacketService
import com.situ.aichat.moments.MomentChatContextService
import com.situ.aichat.moments.MomentGenerationService
import com.situ.aichat.offline.OfflineChatVisibility
import com.situ.aichat.offline.OfflineMarkerStartPayload
import com.situ.aichat.offline.OfflineMeetingService
import com.situ.aichat.offline.OfflineSummaryRetryCoordinator
import com.situ.aichat.meeting.MeetingAppointmentStore
import com.situ.aichat.meeting.MeetingProposalCoordinator
import com.situ.aichat.meeting.MeetupNotificationService
import com.situ.aichat.notification.ActiveConversationStore
import com.situ.aichat.notification.CalendarNotificationScheduler
import com.situ.aichat.notification.NotificationLearningService
import com.situ.aichat.notification.NotificationNavigator
import com.situ.aichat.notification.NotificationScheduler
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.dao.ConversationWithWallpaper
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.chat.image.ImageMemorySummaryService
import com.situ.aichat.data.local.entity.resolvedConfigHasVision
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.PetWriteLock
import com.situ.aichat.data.repository.StickerRepository
import com.situ.aichat.pet.PetInventoryPromptService
import com.situ.aichat.tts.TtsAudioPlayer
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsPlaybackState
import com.situ.aichat.tts.TtsService
import com.situ.aichat.stt.SttEngine
import com.situ.aichat.stt.VoiceMessageRecorder
import com.situ.aichat.prompt.growth.GrowthAnalysisCoordinator
import com.situ.aichat.prompt.growth.RelationshipAnalysisCoordinator
import com.situ.aichat.prompt.memory.InSceneRecapCoordinator
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.memory.MemoryDigestCoordinator
import com.situ.aichat.prompt.memory.StructuredMemoryCoordinator
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.work.NotificationTemplateWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val db: com.situ.aichat.data.local.AppDatabase,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val openLoopRepository: com.situ.aichat.data.repository.OpenLoopRepository,
    private val promiseRepository: com.situ.aichat.data.repository.PromiseRepository,
    private val promiseLedgerService: com.situ.aichat.promise.PromiseLedgerService,
    private val ourDayRepository: com.situ.aichat.data.repository.OurDayRepository,
    private val offlineMeetingMemoryRepository: com.situ.aichat.data.repository.OfflineMeetingMemoryRepository,
    private val characterWriteLock: CharacterWriteLock,
    private val apiConfigRepo: ApiConfigRepository,
    private val apiFunctionRouter: ApiFunctionRouter,
    private val settingsRepo: SettingsRepository,
    private val stickerRepo: StickerRepository,
    private val petRepo: PetRepository,
    private val petWriteLock: PetWriteLock,
    private val petInventoryPromptService: PetInventoryPromptService,
    private val userProfileDao: UserProfileDao,
    private val scheduleDao: ScheduleDao,
    private val calendarReader: CalendarReader,
    private val calendarWriter: CalendarWriter,
    private val llmClient: LlmClient,
    private val vectorMemory: VectorMemoryService,
    private val imageMemorySummaryService: ImageMemorySummaryService,
    private val worldBookPromptService: com.situ.aichat.worldbook.WorldBookPromptService,
    private val worldChatContextProvider: com.situ.aichat.world.link.WorldChatContextProvider,
    private val memoryService: MemoryService,
    private val digestCoordinator: MemoryDigestCoordinator,
    private val inSceneRecapCoordinator: InSceneRecapCoordinator,
    private val structuredCoordinator: StructuredMemoryCoordinator,
    private val growthCoordinator: GrowthAnalysisCoordinator,
    private val relationshipCoordinator: RelationshipAnalysisCoordinator,
    private val affectKernel: com.situ.aichat.prompt.growth.AffectKernel,
    private val intentKernel: com.situ.aichat.prompt.growth.IntentKernel,
    private val notificationScheduler: NotificationScheduler,
    private val calendarNotificationScheduler: CalendarNotificationScheduler,
    private val notificationLearningService: NotificationLearningService,
    private val activeConversationStore: ActiveConversationStore,
    private val momentGenerationService: MomentGenerationService,
    private val momentChatContextService: MomentChatContextService,
    private val offlineMeetingService: OfflineMeetingService,
    private val offlineSummaryRetryCoordinator: OfflineSummaryRetryCoordinator,
    private val backgroundScheduler: com.situ.aichat.work.BackgroundScheduler,
    private val recoveryClaimTracker: com.situ.aichat.recovery.RecoveryClaimTracker,
    private val economicStateService: CharacterEconomicStateService,
    private val giftDao: GiftDao,
    private val giftSendService: GiftSendService,
    private val currencyService: CurrencyService,
    private val redPacketService: RedPacketService,
    private val redPacketDecisionService: RedPacketAcceptanceDecisionService,
    private val redPacketExpirationScanService: RedPacketExpirationScanService,
    private val redPacketDao: RedPacketDao,
    private val meetingProposalCoordinator: MeetingProposalCoordinator,
    private val meetingAppointmentDao: MeetingAppointmentDao,
    private val meetingAppointmentStore: MeetingAppointmentStore,
    // 图纸 2026-08-31 C2：任意入口进见面即核销到期约定（ChatOfflineController 消费·VM 仅接线）。
    private val meetingFulfillmentService: com.situ.aichat.meeting.MeetingFulfillmentService,
    private val meetupNotificationService: MeetupNotificationService,
    // 卷一 A4b：见面结束后补跑主动送礼维护线（见面期被闸掉的礼物/红包补送）。
    private val proactiveGiftMaintenanceService: com.situ.aichat.gift.ProactiveGiftMaintenanceService,
    private val notificationNavigator: NotificationNavigator,
    private val ttsService: TtsService,
    private val ttsConfigRepo: TtsConfigurationRepository,
    private val ttsAudioPlayer: TtsAudioPlayer,
    private val sttEngine: SttEngine,
    private val voiceRecorder: VoiceMessageRecorder,
    private val llmForegroundController: LlmGenerationForegroundController,
    private val networkMonitor: NetworkMonitor,
    private val contextLog: ContextLogService,
    /**
     * 健康线 2-5b（用户拍板 2026-07-03「IM 语义」）：AI 回合 + 收尾维护跑在应用级作用域——退出会话不取消，
     * 回复后台落库 → 列表未读红点；重进由 RecoveryClaimTracker 占坑防双答。仅回合系协作者用它，
     * UI 交互系（语音/日历/线下/约定/礼物 UI 流）仍随 viewModelScope 生灭。
     */
    @com.situ.aichat.di.ChatTurnScope private val chatTurnScope: kotlinx.coroutines.CoroutineScope,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Shared TTS player state (which message/preview is playing + progress) for voice-bubble UI. */
    val playbackState: StateFlow<TtsPlaybackState> = ttsAudioPlayer.state

    private val conversationUuid: String = checkNotNull(savedStateHandle["conversationUuid"])

    init {
        // P6.1d：标记「正在看这条会话」——物化通知时据此判定 markRead / 未读+1（对齐 iOS activeConversationID）。
        activeConversationStore.activeConversationUuid = conversationUuid
    }

    override fun onCleared() {
        super.onCleared()
        assistantTurnController.disposeOnCleared()
        if (activeConversationStore.activeConversationUuid == conversationUuid) {
            activeConversationStore.activeConversationUuid = null
        }
        // 离开会话停止语音回放（对齐 iOS VoiceMessageBubble.onDisappear stop）。
        ttsAudioPlayer.stop()
        // P13.4b：离开会话时停掉仍在进行的录音（录音器是 @Singleton，VM 销毁不会自动停）+ 清未发草稿（委托 voiceController）。
        voiceController.disposeOnCleared()
    }

    /** 点语音气泡播放/停止（审计 S3 搬 ChatVoiceController，薄委托）。 */
    fun toggleVoicePlayback(message: MessageEntity) = voiceController.toggleVoicePlayback(message)

    /**
     * 显示窗口大小（1:1 iOS loadedMessageCount=50）：向上滑到顶 [loadOlderMessages] +50；回到底部停留 5s
     * [shrinkMessageWindow] 缩回 50。长对话不再全量加载/diff（12.3 长列表性能）。
     */
    private val loadedMessageCount = MutableStateFlow(MESSAGE_WINDOW_INITIAL)

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<MessageEntity>> =
        loadedMessageCount
            // P6.2：隐藏忙碌延迟回复暂扣中的消息，释放后自然出现；窗口=最新 N 条（升序显示）。
            .flatMapLatest { limit -> messageRepo.observeVisibleWindowed(conversationUuid, limit) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 首屏消息是否已从 DB 首次返回——区分「加载中」与「真·空会话」，避免打开会话瞬间闪一下"空会话引导"
     * （过渡丝滑化·B1）。独立订阅同一窗口查询（Room 复用同查询、开销可忽略），首次 emit 即恒 true。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val messagesLoaded: StateFlow<Boolean> =
        loadedMessageCount
            .flatMapLatest { limit -> messageRepo.observeVisibleWindowed(conversationUuid, limit) }
            .map { true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 是否还有更早的消息可加载（窗口已满 → DB 里还有更老的）。1:1 iOS hasMoreMessages（count < total）：
     * 窗口返回的可见消息数 == 当前窗口上限时即视为「可能还有更老」，允许继续上翻加载。
     */
    val hasMoreOlderMessages: StateFlow<Boolean> =
        combine(messages, loadedMessageCount) { msgs, limit -> msgs.size >= limit }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 向上滑到列表顶部：窗口 +50（1:1 iOS loadedMessageCount += 50）。 */
    fun loadOlderMessages() {
        loadedMessageCount.update { it + MESSAGE_WINDOW_PAGE }
    }

    /** 回到底部停留 5s 后：窗口缩回初始 50，释放多余历史（1:1 iOS 近底缩减，低内存）。 */
    fun shrinkMessageWindow() {
        loadedMessageCount.update { if (it > MESSAGE_WINDOW_INITIAL) MESSAGE_WINDOW_INITIAL else it }
    }

    /** 自定义贴纸（响应式）：聊天气泡渲染自定义 sticker（按 UUID 查 imagePath/isAnimated）用，新导入自动反映。 */
    val customStickers: StateFlow<List<CustomStickerEntity>> =
        stickerRepo.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 过渡丝滑化：会话 + 壁纸路径的合并查询（一次 LEFT JOIN）。作为唯一上游，使「会话(标题/状态)」与「壁纸路径」
     * 从同一次 emission 返回——ChatScreen 由此单流派生二者，保证进会话时壁纸(含状态栏/底部手势条)与内容**同帧出现、不割裂**。
     */
    val conversationWithWallpaper: StateFlow<ConversationWithWallpaper?> =
        conversationRepo.observeWithWallpaper(conversationUuid)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 会话实体（由 [conversationWithWallpaper] 派生·喂 [character]/[offlineSessionMessages] 等内部流）。 */
    val conversation: StateFlow<ConversationEntity?> =
        conversationWithWallpaper.map { it?.conversation }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The character behind this conversation (live; powers the top-bar name/avatar + edit entry). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val character: StateFlow<CharacterEntity?> =
        conversation
            .flatMapLatest { c -> if (c == null) flowOf<CharacterEntity?>(null) else characterRepo.observe(c.characterUuid) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 线下沉浸剧场专用的【全 session 不窗口化】消息流（12.3）：常规列表已窗口化到最新 50 条，但沉浸剧场须呈现
     * 完整见面弧线，故独立订阅当前 [ConversationEntity.currentOfflineSessionId] 的全部消息（不 LIMIT），
     * 过滤入场/离场标记与系统提示（与原 ChatScreen 过滤一致）。1:1 iOS refreshOfflineSessionMessages 独立全量 fetch。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val offlineSessionMessages: StateFlow<List<MessageEntity>> =
        conversation
            .map { it?.currentOfflineSessionId }
            .distinctUntilChanged()
            .flatMapLatest { sessionId ->
                if (sessionId == null) flowOf(emptyList())
                else messageRepo.observeOfflineSessionMessages(conversationUuid, sessionId)
            }
            .map { msgs ->
                msgs.filter { m ->
                    !OfflineChatVisibility.isHiddenFromReview(MessageKind.fromRaw(m.messageKindRaw)) // S8 单源
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 响应式 AppSettings（线下沉浸 UI 读：沉浸输入开关 / 背景样式 / 粒子风格 / 背景色；新字段持久化延 10.2f，先用默认）。 */
    val appSettings: StateFlow<AppSettings> =
        settingsRepo.appSettings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** 每 60s 节拍（驱动顶栏日程副标题按事件边界刷新，= iOS refreshScheduleStatus 循环）。 */
    private val scheduleTicker = flow {
        while (true) {
            emit(Unit)
            delay(60_000L)
        }
    }

    /**
     * 顶栏副标题用「此刻日程状态」（P0-17）：scheduleSystemEnabled 时取当前进行中事件状态串（复用 [ChatListScheduleStatus]
     * + 60s ticker，零新算法，单角色无需 ChatList 的并发 fan-out）。无日程 / 未开启 / 无进行中事件 → null
     * （UI 此时回退到现有心情行）。WhileSubscribed 自动随订阅启停 = iOS onAppear/onDisappear 启停 ticker。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentScheduleStatus: StateFlow<String?> =
        combine(
            character.map { it?.uuid }.distinctUntilChanged(),
            appSettings.map { it.scheduleSystemEnabled }.distinctUntilChanged(),
            scheduleTicker,
        ) { uuid, enabled, _ -> uuid to enabled }
            .mapLatest { (uuid, enabled) ->
                if (!enabled || uuid == null) return@mapLatest null
                val now = System.currentTimeMillis()
                val today = DateFormatters.startOfDayMillis(now)
                val schedule = scheduleDao.scheduleFor(uuid, today) ?: return@mapLatest null
                val events = scheduleDao.eventsForSchedule(schedule.uuid)
                ChatListScheduleStatus.currentStatus(events, now)
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** P0-2 网络横幅 passthrough：聊天页观察渲染「网络已断开」红条 / 「网络已恢复」绿条。 */
    val networkConnected: StateFlow<Boolean> = networkMonitor.isConnected
    val networkStatusChanged: StateFlow<Boolean?> = networkMonitor.statusChanged
    fun clearNetworkStatusChange() = networkMonitor.clearStatusChange()

    init {
        // P0-2 网络恢复自动重试（**超越 iOS**：iOS 仅显示绿条不重发；本轮用户拍板要做）。仅本会话在屏期间生效
        // （VM 随会话页存活/销毁）；drop(1) 跳过订阅时的当前值，只对在屏期间发生的「恢复」反应。
        viewModelScope.launch {
            networkMonitor.statusChanged.drop(1).collect { changed ->
                if (changed == true) maybeAutoRetryAfterReconnect()
            }
        }
    }

    /** 助手「正在输入」指示（生成中 + 分条递送间隔；UI 显示打字气泡）。对齐 iOS typing 占位 + deliverSegments。 */

    // ── 打字占位槽（契约 FABLE5_CHAT_BUBBLE_REFACTOR_PROPOSAL B1·承重点）：让「正在输入」的三点气泡与即将到达的
    // 首段 AI 消息**共用同一 key**，段落库后同 key 被真实消息接管 → 同一列表项原地变身（消灭旧「独立 TypingRow +
    // 删插替换」的跳动/重叠根源）。[_pendingAssistantSlot] = 渲染层 dots 槽（builder 据此合成空内容占位·消息落库后自动
    // dedup 显形）；预留 uuid 与开/关/取槽逻辑随投递层抽到 [ChatReplyDeliverer]（VM 注入本流，公开 API 不变）。
    private val _pendingAssistantSlot = MutableStateFlow<TypingSlot?>(null)
    internal val pendingAssistantSlot: StateFlow<TypingSlot?> = _pendingAssistantSlot.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    // MARK: - 打断递送 / 视图可见性（15.2-P1 批1 · P1-4；「停止生成」UI 已随输入排 C3 退役）

    /** 递送阶段旗标（=iOS isDeliveringAssistantResponse）：流式已完成、正按节奏分条投递。此阶段新发送=打断。
     *  C3 后无 UI 消费（右键两态不再看它），仍是投递层/控制器打断判定的内部信号。 */
    private val _isDelivering = MutableStateFlow(false)

    /** 聊天视图可见性（=iOS isViewVisible·递送循环 per-segment 读）：不可见时跳过打字延迟/段间停顿，逐段即时插入。
     *  实际读取在投递层 [ChatReplyDeliverer]（VM 注入本流，只读）；VM 这里持有 + setViewVisible 写入。 */
    private val _isViewVisible = MutableStateFlow(false)

    /** ChatScreen 生命周期喂入（组合存在 + ON_START/ON_STOP 才算可见）。 */
    fun setViewVisible(visible: Boolean) {
        _isViewVisible.value = visible
    }

    /** 响应式金币余额（9.2d d-3 聊天内送礼 sheet 判可负担配色）。 */
    val coinBalance: StateFlow<Int> = currencyService.observeUserCoinBalance()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 100)

    /** 用户资料（昵称/头像）——通话记录卡 transcript 行渲染用户头像/名字（P10.1i）。 */
    val userProfile: StateFlow<UserProfileEntity?> = userProfileDao.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 一次性信息提示（非错误）：结构化工具调用降级为文本兼容模式时提示（对齐 iOS .toolCallFallback toast）。 */
    private val _infoToast = MutableStateFlow<String?>(null)
    val infoToast: StateFlow<String?> = _infoToast.asStateFlow()

    fun clearInfoToast() {
        _infoToast.value = null
    }

    /** 线下见面异常恢复提示（见面中途 App 被杀/最后线下消息 >10min）：UI 弹「继续见面 / 结束见面」（10.2c-3c）。 */
    private val _offlineRecoveryPromptVisible = MutableStateFlow(false)
    val offlineRecoveryPromptVisible: StateFlow<Boolean> = _offlineRecoveryPromptVisible.asStateFlow()

    /** 引用回复目标（M03 逻辑就绪；UI 长按「引用」由 P1.4 设置）。发送时消费并清空。 */
    private val _replyTarget = MutableStateFlow<MessageEntity?>(null)
    val replyTarget: StateFlow<MessageEntity?> = _replyTarget.asStateFlow()

    fun setReplyTarget(message: MessageEntity) {
        _replyTarget.value = message
    }

    fun clearReplyTarget() {
        _replyTarget.value = null
    }

    // MARK: - P13.4b 语音消息录制（录音/草稿/转写状态机已抽到 ChatVoiceController，VM 持有并委托；发送链留 VM）
    private val voiceController = ChatVoiceController(
        scope = viewModelScope,
        appContext = appContext,
        errorFlow = _error,
        infoToastFlow = _infoToast,
        voiceRecorder = voiceRecorder,
        sttEngine = sttEngine,
        ttsAudioPlayer = ttsAudioPlayer,
    )

    /** 录音/草稿状态（转发自 [voiceController]，保持 VM 公开面不变，UI 引用不动）。 */
    val voiceRecording: StateFlow<Boolean> = voiceController.voiceRecording
    val voiceRecordingDurationMs: StateFlow<Long> = voiceController.voiceRecordingDurationMs
    val voiceRecordingLevel: StateFlow<Float> = voiceController.voiceRecordingLevel
    val voiceRecordingCancelling: StateFlow<Boolean> = voiceController.voiceRecordingCancelling
    val voiceDraft: StateFlow<VoiceDraftState?> = voiceController.voiceDraft

    fun startVoiceRecording() = voiceController.startVoiceRecording()
    fun updateVoiceRecordingDrag(draggedUpDp: Float) = voiceController.updateVoiceRecordingDrag(draggedUpDp)
    fun finishVoiceRecording() = voiceController.finishVoiceRecording()
    fun cancelVoiceRecordingIfActive() = voiceController.cancelVoiceRecordingIfActive()
    fun retryVoiceTranscription() = voiceController.retryVoiceTranscription()
    fun toggleVoiceDraftPlayback() = voiceController.toggleVoiceDraftPlayback()
    fun cancelVoiceDraft() = voiceController.cancelVoiceDraft()

    // MARK: - 日历操作（已抽到 ChatCalendarActionHandler，VM 持有并委托；行为不变）
    private val calendarHandler = ChatCalendarActionHandler(
        scope = viewModelScope,
        errorFlow = _error,
        settingsRepo = settingsRepo,
        calendarWriter = calendarWriter,
        calendarNotificationScheduler = calendarNotificationScheduler,
        conversationRepo = conversationRepo,
        characterRepo = characterRepo,
        conversationUuid = conversationUuid,
    )
    val pendingCalendarActions: StateFlow<List<CalendarAction>> = calendarHandler.pendingCalendarActions
    val calendarToast: StateFlow<CalendarToast?> = calendarHandler.calendarToast

    // MARK: - AI 回复投递层（刀7·已抽到 ChatReplyDeliverer，VM 持有并委托；打字/递送/视图可见/错误态注入，公开 API 不变）
    private val replyDeliverer = ChatReplyDeliverer(
        appContext = appContext,
        conversationUuid = conversationUuid,
        messageRepo = messageRepo,
        conversationRepo = conversationRepo,
        characterRepo = characterRepo,
        stickerRepo = stickerRepo,
        ttsService = ttsService,
        offlineMeetingService = offlineMeetingService,
        calendarHandler = calendarHandler,
        errorFlow = _error,
        isDelivering = _isDelivering,
        pendingAssistantSlot = _pendingAssistantSlot,
        isViewVisible = _isViewVisible,
    )

    fun clearError() {
        _error.value = null
    }

    suspend fun sendGiftInChat(item: GiftItem): GiftSendService.InChatSendOutcome =
        giftRedPacketController.sendGiftInChat(item)
    suspend fun sendDiyGift(title: String, content: String, imageUri: Uri?, cost: Int): GiftSendService.InChatSendOutcome =
        giftRedPacketController.sendDiyGift(title, content, imageUri, cost)
    suspend fun loadGiftDiyImage(recordUuid: String): Bitmap? =
        giftRedPacketController.loadGiftDiyImage(recordUuid)
    suspend fun sendRedPacketInChat(amount: Int, blessing: String, festivalId: String? = null): RedPacketSendOutcome =
        giftRedPacketController.sendRedPacketInChat(amount, blessing, festivalId)
    fun observeRedPacketRecord(recordUuid: String) = giftRedPacketController.observeRedPacketRecord(recordUuid)
    suspend fun openRedPacket(recordUuid: String) = giftRedPacketController.openRedPacket(recordUuid)
    suspend fun giftRecord(recordUuid: String): GiftRecordEntity? = giftRedPacketController.giftRecord(recordUuid)

    /**
     * 删单条消息（长按菜单）。**数据驱动**：删库行（含磁盘媒体 + 行内向量）→ Flow 回灌 → 列表项即时移除
     * （UI 侧 fadeOutSpec=null 杜绝 animateItem 消失淡出残影）。落库 + 列表快照重算包在 [NonCancellable]——
     * 即便删除当下用户立刻退出会话、VM 被清，也保证删干净、前后一致（杜绝"退出再进才消失"残留）。
     */
    fun deleteMessage(message: MessageEntity) {
        // 审计 R3：删的是正在播放的语音 → 先停播。行删除后屏上再无这条的播放控件，音频却会继续放到完
        // （音频文件已删但 ExoPlayer 持句柄），只有退出会话才停——先停再删，与 toggleVoicePlayback 同判据。
        if (ttsAudioPlayer.state.value.playingId == message.messageUUID) ttsAudioPlayer.stop()
        viewModelScope.launch {
            withContext(NonCancellable) {
                messageRepo.deleteByUuid(message.messageUUID)
                // 删后重算会话列表「最后一条」快照——否则删掉最后一条，列表仍显示那条已删消息（问题②）。
                refreshConversationLastMessage(conversationUuid, messageRepo, conversationRepo)
            }
        }
    }

    /** 进入会话时标记已读。 */
    fun markRead() {
        viewModelScope.launch {
            conversationRepo.markRead(conversationUuid)
            // P6.1c：正在看该会话 → 撤回 15 分钟内将触发的该角色通知（对齐 iOS suppressImpendingNotificationsIfNeeded）。
            conversationRepo.get(conversationUuid)?.characterUuid?.let { notificationScheduler.suppressImpending(it) }
        }
    }

    // MARK: - 日历操作处理（委托 ChatCalendarActionHandler）
    fun confirmPendingCalendarAction() = calendarHandler.confirmPendingCalendarAction()

    fun cancelPendingCalendarAction() = calendarHandler.cancelPendingCalendarAction()

    fun dismissCalendarToast() = calendarHandler.dismissCalendarToast()

    // MARK: - 线下见面生命周期（已抽到 ChatOfflineController，VM 持有并委托；引擎相关经回调注入，行为不变）
    private val offlineController = ChatOfflineController(
        scope = viewModelScope,
        appContext = appContext,
        conversationUuid = conversationUuid,
        infoToastFlow = _infoToast,
        recoveryPromptVisibleFlow = _offlineRecoveryPromptVisible,
        messageRepo = messageRepo,
        conversationRepo = conversationRepo,
        settingsRepo = settingsRepo,
        offlineMeetingService = offlineMeetingService,
        offlineSummaryRetryCoordinator = offlineSummaryRetryCoordinator,
        meetingAppointmentStore = meetingAppointmentStore,
        meetingFulfillmentService = meetingFulfillmentService,
        runAssistantTurn = ::runAssistantTurnForCurrentConversation,
        serialize = ::launchSerializedTurn,
        cancelActiveTurn = ::cancelActiveTurn,
        // 审计 S3：摘要补跑搬 MemoryAnalysisTrigger；lambda 延迟解引用（该 trigger 构造晚于本 controller）。
        afterOfflineMemorySummary = { memoryAnalysisTrigger.checkAndTriggerAfterOffline() },
        scheduleOfflineAfterglow = ::scheduleOfflineAfterglow,
        // 卷二 §5④：见面结束时掷点，中签才排「朋友圈呼应帖」worker（3–7 小时后发一条含蓄相关的动态）。
        scheduleMeetingMomentEcho = ::scheduleMeetingMomentEcho,
        proactiveGiftMaintenanceService = proactiveGiftMaintenanceService,
    )

    /**
     * 见面结束成功分支排「余温消息」一次性延迟 worker（§3.10·涟漪①）：读 recordOfflineExited 刚设的
     * pendingOfflineSummarySessionId + characterUuid → [BackgroundScheduler.scheduleOneShot]（延迟 135–225 分钟随机·
     * requireNetwork·existingPolicy=KEEP 同 session 不重排）。开关判定在 [com.situ.aichat.offline.OfflineAfterglowService]
     * 守卫①（用户可能中途改设置 → 以到点时为准）。
     */
    private suspend fun scheduleOfflineAfterglow() {
        val convo = conversationRepo.get(conversationUuid) ?: return
        val sessionId = convo.pendingOfflineSummarySessionId?.takeIf { it.isNotEmpty() } ?: return
        backgroundScheduler.scheduleOneShot(
            uniqueName = com.situ.aichat.work.OfflineAfterglowWorker.uniqueName(sessionId),
            workerClass = com.situ.aichat.work.OfflineAfterglowWorker::class.java,
            initialDelay = java.time.Duration.ofMinutes(kotlin.random.Random.nextLong(135, 226)),
            requireNetwork = true,
            existingPolicy = androidx.work.ExistingWorkPolicy.KEEP,
            inputData = androidx.work.workDataOf(
                com.situ.aichat.work.OfflineAfterglowWorker.KEY_CONVERSATION_UUID to conversationUuid,
                com.situ.aichat.work.OfflineAfterglowWorker.KEY_CHARACTER_UUID to convo.characterUuid,
                com.situ.aichat.work.OfflineAfterglowWorker.KEY_SESSION_ID to sessionId,
            ),
        )
    }

    /**
     * 见面结束掷点排「朋友圈呼应帖」worker（卷二 §5④·图纸 §3.3）：75% 中签才排——未中签当场不排（省一个空转
     * 任务）但**必打日志**可观测。首延/KEEP 见 [com.situ.aichat.work.MeetingMomentEchoWorker.scheduleFirst]；
     * 其余守卫（发帖开关/深夜/睡眠/摘要熟没熟）一律到点时由 MeetingMomentEchoService 现评。
     */
    private suspend fun scheduleMeetingMomentEcho() {
        val convo = conversationRepo.get(conversationUuid) ?: return
        val sessionId = convo.pendingOfflineSummarySessionId?.takeIf { it.isNotEmpty() } ?: return
        val roll = kotlin.random.Random.nextInt(100)
        if (!com.situ.aichat.offline.MeetingMomentEchoPlanner.shouldPost(roll)) {
            Log.i(TAG, "见面呼应帖未中签 roll=$roll session=$sessionId")
            return
        }
        com.situ.aichat.work.MeetingMomentEchoWorker.scheduleFirst(
            backgroundScheduler, conversationUuid, convo.characterUuid, sessionId,
        )
    }

    fun acceptOfflineInvite(messageUuid: String) = offlineController.acceptOfflineInvite(messageUuid)
    fun declineOfflineInvite(messageUuid: String) = offlineController.declineOfflineInvite(messageUuid)
    fun startManualOfflineMeeting(location: String, activity: String) = offlineController.startManualOfflineMeeting(location, activity)
    fun handleMeetingCancelHint() = offlineController.handleMeetingCancelHint()
    fun continueOfflineMeeting(endCardMessageUuid: String) = offlineController.continueOfflineMeeting(endCardMessageUuid)
    fun exitOfflineMode() = offlineController.exitOfflineMode()
    fun endMeetingFromRecovery() = offlineController.endMeetingFromRecovery()
    fun dismissOfflineRecoveryPrompt() = offlineController.dismissOfflineRecoveryPrompt()
    fun continueMeetingFromRecovery() = offlineController.continueMeetingFromRecovery()
    val offlineRecoveryAwayMs: StateFlow<Long?> get() = offlineController.recoveryAwayMs
    // offline-1 只读回顾（审计 S3 搬 ChatOfflineController，薄委托）。
    val offlineReviewInfo: StateFlow<String?> get() = offlineController.offlineReviewInfo
    val offlineReviewMessages: StateFlow<List<MessageEntity>> get() = offlineController.offlineReviewMessages
    fun openOfflineReview(sessionId: String) = offlineController.openOfflineReview(sessionId)
    fun closeOfflineReview() = offlineController.closeOfflineReview()

    // MARK: - 未来约定见面域（用户面动作 + 倒数/赴约两态 StateFlow + 后台识别簇 detectionTrigger·全抽到 ChatMeetingController·公开 API 不变）。
    // 须置于 [offlineController] 之后、[assistantTurnEngine] 之前（赴约委托 offlineController·init 收 pendingMeetupArrival·detectionTrigger 供引擎注入·详见类 KDoc）。
    private val meetingController: ChatMeetingController = ChatMeetingController(
        scope = viewModelScope,
        conversationUuid = conversationUuid,
        conversationRepo = conversationRepo,
        messageRepo = messageRepo,
        meetingProposalCoordinator = meetingProposalCoordinator,
        meetingAppointmentDao = meetingAppointmentDao,
        meetingAppointmentStore = meetingAppointmentStore,
        meetupNotificationService = meetupNotificationService,
        notificationNavigator = notificationNavigator,
        contextLog = contextLog,
        arriveOffline = offlineController::arriveAtAppointment,
    )

    // 公开面薄委托（ChatScreen 直接调；完整语义见 ChatMeetingController 各方法 KDoc）。
    val nextCountdownAppointment: StateFlow<MeetingAppointmentEntity?> = meetingController.nextCountdownAppointment
    val arrivalAppointment: StateFlow<MeetingAppointmentEntity?> = meetingController.arrivalAppointment
    fun observeAppointment(uuid: String) = meetingController.observeAppointment(uuid)
    fun acceptAppointment(uuid: String) = meetingController.acceptAppointment(uuid)
    fun declineAppointment(uuid: String) = meetingController.declineAppointment(uuid)
    fun startFutureMeeting(scheduledAtMillis: Long, granularity: MeetingTimeGranularity, location: String, activity: String) =
        meetingController.startFutureMeeting(scheduledAtMillis, granularity, location, activity)
    fun rescheduleAppointment(uuid: String, scheduledAtMillis: Long, granularity: MeetingTimeGranularity) =
        meetingController.rescheduleAppointment(uuid, scheduledAtMillis, granularity)
    fun applyMeetingChange(messageUuid: String) = meetingController.applyMeetingChange(messageUuid)
    fun keepMeetingChange(messageUuid: String) = meetingController.keepMeetingChange(messageUuid)
    fun cancelAppointment(uuid: String) = meetingController.cancelAppointment(uuid)
    fun arriveAtAppointment(appointmentUuid: String) = meetingController.arriveAtAppointment(appointmentUuid)

    /**
     * 进入会话时调用（对齐 iOS onAppear）：委托线下处理（修脏状态/恢复弹窗/摘要重试）+ 自动恢复未答消息（通用回合恢复，留 VM）。
     * UI（ChatScreen）在 LaunchedEffect 里调用一次。
     */
    fun onChatAppear() {
        offlineController.handleChatAppear()
        autoRecoverUnansweredMessage()
    }

    // MARK: - 后台分析触发·记忆簇（已抽到 MemoryAnalysisTrigger，VM 持有并委托；行为不变）
    private val memoryAnalysisTrigger = MemoryAnalysisTrigger(
        scope = chatTurnScope, // 2-5b：回合系=应用级作用域，退出会话不取消
        conversationUuid = conversationUuid,
        characterRepo = characterRepo,
        conversationRepo = conversationRepo,
        characterWriteLock = characterWriteLock,
        memoryService = memoryService,
        digestCoordinator = digestCoordinator,
        structuredCoordinator = structuredCoordinator,
        apiConfigRepo = apiConfigRepo,
        settingsRepo = settingsRepo,
        userProfileDao = userProfileDao,
        // 活人感二期 M3（§3.3）：结构化记忆抽取成功后重烤通知文案——既有 unique work `notif_template_<id>` + REPLACE
        // 天然去重（抽取节奏 30 轮/30min 亦天然节流），离线不强制联网、无 key 时 generateAndSave 自存默认文案。
        onStructuredMemoryExtracted = { NotificationTemplateWorker.enqueueForCharacter(appContext, it) },
    )


    // MARK: - 后台分析触发·成长+关系簇（已抽到 RelationshipAnalysisTrigger，VM 持有并委托；成长命脉，行为不变）
    private val relationshipAnalysisTrigger = RelationshipAnalysisTrigger(
        scope = chatTurnScope, // 2-5b：回合系=应用级作用域，退出会话不取消
        characterRepo = characterRepo,
        characterWriteLock = characterWriteLock,
        apiConfigRepo = apiConfigRepo,
        growthCoordinator = growthCoordinator,
        relationshipCoordinator = relationshipCoordinator,
        affectKernel = affectKernel,
        intentKernel = intentKernel,
    )

    // MARK: - 后台分析触发·惦记的事簇（活人感一期 P2·VM 持有并接线引擎；与 MeetingDetectionTrigger 并排）
    private val openLoopDetectionTrigger = OpenLoopDetectionTrigger(
        scope = chatTurnScope, // 2-5b：回合系=应用级作用域，退出会话不取消
        conversationUuid = conversationUuid,
        conversationRepo = conversationRepo,
        messageRepo = messageRepo,
        openLoopRepository = openLoopRepository,
        promiseRepository = promiseRepository, // 记忆改造四期·§3.6-③：源头治理（:118 已注入·零新 DI）
        contextLog = contextLog,
        backgroundScheduler = backgroundScheduler,
    )

    // MARK: - 助手回合编排引擎（刀8·已抽到 AssistantTurnEngine，VM 持有并委托；引擎管线[assistantTurnJob/串行/打断]留 VM）
    // MARK: - 约定记账当场提示（图纸 2026-09-06 约定工具调用化 §3.5）：闸门 / 落库 / 提示全在协作者里，VM 只接线。
    // 排队条件（同槽只留一件）：日历 toast 在 / 断网 / 网络恢复条在 / 赴约钮在 → 提示等它们让位（超 8 秒放弃显示）。
    // ⚠️ 声明必须排在它读的四个流之后（PITFALLS 1b 同族坑）。
    private val promiseHintBlocked: StateFlow<Boolean> =
        combine(calendarHandler.calendarToast, networkConnected, networkStatusChanged, arrivalAppointment) { toast, connected, changed, arrival ->
            toast != null || !connected || changed == true || arrival != null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val promiseToolHandler = ChatPromiseToolHandler(
        scope = viewModelScope, // 只跑提示的等待 / 计时；落库跑在引擎的回合协程（chatTurnScope）
        ledger = promiseLedgerService,
        conversationRepo = conversationRepo,
        blocked = promiseHintBlocked,
        conversationUuid = conversationUuid,
    )
    internal val promiseHint: StateFlow<PromiseHint?> = promiseToolHandler.hint // PromiseHint 是 internal 瞬态 UI 态
    fun undoPromiseHint(uuid: String) = promiseToolHandler.undoRecorded(uuid)
    fun dismissPromiseHint() = promiseToolHandler.dismiss()

    private val assistantTurnEngine = AssistantTurnEngine(
        scope = chatTurnScope, // 2-5b：回合系=应用级作用域，退出会话不取消
        appContext = appContext,
        conversationUuid = conversationUuid,
        messageRepo = messageRepo,
        conversationRepo = conversationRepo,
        characterRepo = characterRepo,
        offlineMeetingMemoryRepository = offlineMeetingMemoryRepository,
        scheduleDao = scheduleDao,
        giftDao = giftDao,
        apiConfigRepo = apiConfigRepo,
        stickerRepo = stickerRepo,
        petRepo = petRepo,
        petWriteLock = petWriteLock,
        petInventoryPromptService = petInventoryPromptService,
        calendarReader = calendarReader,
        llmClient = llmClient,
        vectorMemory = vectorMemory,
        worldBookPromptService = worldBookPromptService,
        worldChatContextProvider = worldChatContextProvider,
        memoryService = memoryService,
        momentChatContextService = momentChatContextService,
        economicStateService = economicStateService,
        ttsConfigRepo = ttsConfigRepo,
        llmForegroundController = llmForegroundController,
        networkMonitor = networkMonitor,
        contextLog = contextLog,
        notificationScheduler = notificationScheduler,
        momentGenerationService = momentGenerationService,
        replyDeliverer = replyDeliverer,
        calendarHandler = calendarHandler,
        memoryAnalysisTrigger = memoryAnalysisTrigger,
        inSceneRecapCoordinator = inSceneRecapCoordinator,
        relationshipAnalysisTrigger = relationshipAnalysisTrigger,
        meetingDetectionTrigger = meetingController.detectionTrigger,
        openLoopDetectionTrigger = openLoopDetectionTrigger,
        openLoopRepository = openLoopRepository,
        promiseRepository = promiseRepository,
        promiseToolHandler = promiseToolHandler,
        ourDayRepository = ourDayRepository,
        meetingAppointmentStore = meetingAppointmentStore,
        errorFlow = _error,
        infoToastFlow = _infoToast,
        isDelivering = _isDelivering,
    )

    /** 聊天内礼物 / 红包钱路协作者（刀9 抽出）。送礼后的 AI 回复经 [AssistantTurnController.enqueueExternalTurn] 入合并等待窗（C1）。 */
    private val giftRedPacketController = InChatGiftRedPacketController(
        scope = viewModelScope,
        appContext = appContext,
        conversationUuid = conversationUuid,
        conversationRepo = conversationRepo,
        characterRepo = characterRepo,
        apiConfigRepo = apiConfigRepo,
        settingsRepo = settingsRepo,
        notificationLearningService = notificationLearningService,
        giftDao = giftDao,
        giftSendService = giftSendService,
        redPacketService = redPacketService,
        redPacketDecisionService = redPacketDecisionService,
        redPacketExpirationScanService = redPacketExpirationScanService,
        redPacketDao = redPacketDao,
        enqueueTurn = { assistantTurnController.enqueueExternalTurn() }, // 惰性引用：调用时 controller 必已初始化
    )

    // C1 合并等待窗调度器（输入排契约 §3.2-1）：持久层桥 SettingsRepository；默认值/钳位/自适应单源在 dispatcher。
    private val sendDispatcher = ChatMessageDispatcher(
        scope = viewModelScope,
        persistence = object : ChatMessageDispatcher.Persistence {
            override suspend fun loadWaitSeconds(): Float? = settingsRepo.getChatSendWaitSeconds()
            override suspend fun saveWaitSeconds(value: Float) {
                settingsRepo.setChatSendWaitSeconds(value)
            }
            override suspend fun loadSendTimestamps(): List<Long> = settingsRepo.getChatSendTimestamps()
            override suspend fun saveSendTimestamps(values: List<Long>) {
                settingsRepo.setChatSendTimestamps(values)
            }
        },
    )

    // MARK: - 助手回合生命周期 + 发送入口（刀10·已抽到 AssistantTurnController，VM 持有并委托）。
    // 须置于 [assistantTurnEngine]/[replyDeliverer]/[voiceController] 之后——发送/打断/串行/恢复经引擎与投递层协作。
    private val assistantTurnController = AssistantTurnController(
        scope = chatTurnScope, // 2-5b：回合系=应用级作用域，退出会话不取消
        appContext = appContext,
        conversationUuid = conversationUuid,
        db = db,
        messageRepo = messageRepo,
        conversationRepo = conversationRepo,
        characterRepo = characterRepo,
        apiConfigRepo = apiConfigRepo,
        settingsRepo = settingsRepo,
        userProfileDao = userProfileDao,
        notificationLearningService = notificationLearningService,
        offlineMeetingService = offlineMeetingService,
        recoveryClaimTracker = recoveryClaimTracker,
        assistantTurnEngine = assistantTurnEngine,
        replyDeliverer = replyDeliverer,
        voiceController = voiceController,
        vectorMemory = vectorMemory,
        imageMemorySummaryService = imageMemorySummaryService,
        dispatcher = sendDispatcher,
        typingSlot = pendingAssistantSlot,
        conversationFlow = conversation,
        isSending = _isSending,
        errorFlow = _error,
        isDelivering = _isDelivering,
        replyTarget = _replyTarget,
    )

    // 公开发送入口薄委托（ChatScreen 直接调；公开 API 不变）。
    fun send(text: String): Boolean = assistantTurnController.send(text)
    fun sendVoiceDraft() = assistantTurnController.sendVoiceDraft()
    fun sendStickerMessage(stickerId: String) = assistantTurnController.sendStickerMessage(stickerId)
    fun sendImages(uris: List<android.net.Uri>) = assistantTurnController.sendImages(uris)

    /** 图片能力面（发图入口显隐 + 存相册）——收进协作者，别让本已越 800 红线的 VM 继续长。 */
    internal val image: ChatImageFacade = ChatImageFacade(
        scope = viewModelScope,
        appContext = appContext,
        apiConfigRepo = apiConfigRepo,
        functionRouter = apiFunctionRouter,
    )
    fun regenerate() = assistantTurnController.regenerate()

    // 内部回合生命周期薄委托（网络重试 / onChatAppear / 线下回调经 VM 同名方法委托，原调用点字节不变）。
    private fun maybeAutoRetryAfterReconnect() = assistantTurnController.maybeAutoRetryAfterReconnect()
    private fun autoRecoverUnansweredMessage() = assistantTurnController.autoRecoverUnansweredMessage()
    private suspend fun runAssistantTurnForCurrentConversation() = assistantTurnController.runAssistantTurnForCurrentConversation()
    private fun launchSerializedTurn(block: suspend () -> Unit) = assistantTurnController.launchSerializedTurn(block)
    private suspend fun cancelActiveTurn() = assistantTurnController.cancelActiveTurn()

    private companion object {
        private const val TAG = "ChatViewModel"

        /** 聊天显示窗口初始/增量大小（1:1 iOS loadedMessageCount 默认 50 / 每次 +50）。 */
        const val MESSAGE_WINDOW_INITIAL = 50
        const val MESSAGE_WINDOW_PAGE = 50
    }
}
