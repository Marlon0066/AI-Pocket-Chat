package com.situ.aichat.recovery

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.R
import com.situ.aichat.data.calendar.CalendarReader
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.content.ContentFilterService
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.MoodHistoryEntry
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StickerRepository
import com.situ.aichat.economy.CharacterEconomicStateService
import com.situ.aichat.gift.GiftHistoryPromptService
import com.situ.aichat.moments.MomentChatContextService
import com.situ.aichat.offline.outgoingOfflineSessionId
import com.situ.aichat.prompt.AssistantOutputGate
import com.situ.aichat.prompt.MessageKindInference
import com.situ.aichat.prompt.MessageSplitter
import com.situ.aichat.prompt.PromptBuilder
import com.situ.aichat.prompt.PromptScene
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.prompt.ReplyParser
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.sticker.DisabledBuiltInStickerStore
import com.situ.aichat.sticker.StickerService
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.worldbook.WorldBookPromptService
import com.situ.aichat.worldbook.toWorldInfoSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 未答恢复的**无头**回复生成器（10.2g）。iOS 用一个 `isViewVisible=false` 的 ChatViewModel 驱动恢复；安卓
 * ChatViewModel 是 @HiltViewModel 绑定屏幕、无法后台实例化，故这里复刻 [com.situ.aichat.busyreply.BusyReplyService].
 * generateBusyReply 的「无头」装配-生成-落库链路（与 ChatViewModel.runAssistantTurn 同源的上下文 fan-out），为最后一条
 * 是用户消息的对话生成并**立即落库**一条常规回复（不打字动画、不暂扣）。
 *
 * **与正常聊天回复的有意偏离（后台恢复，已知且可接受）**：
 *  - **无打字动画 / 分段时延**：直接插入消息（对齐 iOS isViewVisible=false 跳过打字）。
 *  - **纯文字、不走语音**：后台无人听（同 BusyReplyService 不重导语音）。
 *  - **不发工具（日历/线下）**：线下已被恢复扫描过滤；日历需确认卡 UI，后台无从呈现 → 仅出纯文字回复。
 *  - **不触发逐回合维护**（记忆摘要/成长/关系/节拍/通知重排/补帖）：由下次前台回合或既有周期 Worker 兜账，
 *    后台触发会与活跃 VM 的 characterMetaMutex 争用并多烧 LLM。
 *  - 但**保留 mood 解析+落库**（顶栏情绪与正常回复一致）+ 表情包归一 + 向量记忆/日程/日历/朋友圈/经济/礼物上下文。
 */
@Singleton
class RecoveryReplyGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val offlineMeetingMemoryRepository: com.situ.aichat.data.repository.OfflineMeetingMemoryRepository,
    private val apiConfigRepo: ApiConfigRepository,
    private val settingsRepo: SettingsRepository,
    private val userProfileDao: UserProfileDao,
    private val messageRepo: MessageRepository,
    private val vectorMemory: VectorMemoryService,
    private val memoryService: MemoryService,
    private val scheduleDao: ScheduleDao,
    private val calendarReader: CalendarReader,
    private val momentChatContextService: MomentChatContextService,
    private val stickerRepo: StickerRepository,
    private val economicStateService: CharacterEconomicStateService,
    private val giftDao: GiftDao,
    private val contextLog: ContextLogService,
    private val db: AppDatabase,
    private val worldBookPromptService: WorldBookPromptService,
) {

    /**
     * 为 [conversationUuid] 生成并立即落库一条助手回复。成功（产出非空回复并落库）返回 true；配置/角色缺失、
     * LLM 空响应 → 不落任何消息、返回 false（防写空助手消息把 lastMessageRole 翻成 assistant、永久隐藏待恢复对话）。
     */
    suspend fun generateAndPersist(conversationUuid: String): Boolean {
        val convo = conversationRepo.get(conversationUuid) ?: run {
            Log.w(TAG, "补回复跳过·会话/角色缺失 会话缺失 conv=$conversationUuid")
            return false
        }
        val character = characterRepo.get(convo.characterUuid) ?: run {
            Log.w(TAG, "补回复跳过·会话/角色缺失 角色缺失 conv=$conversationUuid char=${convo.characterUuid}")
            return false
        }
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) ?: run {
            Log.w(TAG, "补回复跳过·未配置 API conv=$conversationUuid")
            return false
        }
        val settings = settingsRepo.getAppSettings()
        val userProfile = userProfileDao.get()

        val nowInstant = Instant.now()
        val zone = ZoneId.systemDefault()
        val todayStart = nowInstant.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val history = messageRepo.recentChronological(conversationUuid, HISTORY_FETCH_LIMIT)
        // 向量检索查询 = 待回复的最后一条【真实】用户消息（与主引擎批3 3-6 同口径：跳耳语/结构化卡·渲染同源）。
        val queryMessage = history.lastOrNull {
            val kind = com.situ.aichat.data.model.MessageKind.fromRaw(it.messageKindRaw)
            it.roleRaw == "user" && kind != com.situ.aichat.data.model.MessageKind.SYSTEM_HINT && !kind.isStructuredCard
        }
        val query = queryMessage?.let {
            MemoryService.renderMemoryContent(it.content, it.mediaMemorySummary, it.imageRelativePath != null)
        }.orEmpty()
        val retrievedSnippets = if (settings.vectorSearchThreshold > 0 && query.isNotBlank()) {
            vectorMemory.searchRelevantMemories(
                query = query,
                characterUuid = character.uuid,
                currentConversationUuid = conversationUuid,
                userName = (userProfile?.nickname ?: "").ifEmpty { context.getString(R.string.pb_user_fallback) },
                characterName = character.name,
                shortTermLength = settings.shortTermMemoryLength,
                thresholdPercent = settings.vectorSearchThreshold,
            )
        } else {
            emptyList()
        }
        val unsummarizedRounds = memoryService.countUnsummarizedRoundsOutsideBaseWindow(
            currentConversation = convo,
            baseShortTermLength = settings.shortTermMemoryLength,
        )
        val structuredMemory = StructuredMemory.decode(character.structuredMemoryJSON)
        val offlineMeetingMemoryText = offlineMeetingMemoryRepository.renderedForInjection(character.uuid)
        val milestones = characterRepo.getMilestones(character.uuid)
        val todaySchedule = scheduleDao.scheduleFor(character.uuid, todayStart)
        val todayScheduleEvents = todaySchedule?.let { scheduleDao.eventsForSchedule(it.uuid) } ?: emptyList()
        val calendarUpcoming = if (settings.calendarIntegrationEnabled) {
            calendarReader.upcomingEvents(nowInstant.toEpochMilli())?.text
        } else {
            null
        }
        val momentChatContext = momentChatContextService.buildMomentContext(
            character = character,
            userNickname = (userProfile?.nickname ?: "").ifEmpty { context.getString(R.string.pb_user_fallback) },
            scheduleSystemEnabled = settings.scheduleSystemEnabled,
            nowMillis = nowInstant.toEpochMilli(),
        )
        val customStickers = stickerRepo.getAllForPrompt()
        val disabledStickers = DisabledBuiltInStickerStore.disabledIds(context)
        val economicState = economicStateService.resolveChatState(character.uuid, nowInstant.toEpochMilli())
        val giftHistory = GiftHistoryPromptService.buildContent(character.uuid, giftDao, nowInstant.toEpochMilli(), (userProfile?.nickname ?: "").ifEmpty { context.getString(R.string.pb_user_fallback) })

        // 批W W-3：断线恢复管道接世界书（与主引擎同激活口径·每次生成激活一次）。重设定角色的补答不再「失忆」。
        val worldInfo = worldBookPromptService.activateForTurn(
            characterUuid = character.uuid,
            conversationUuid = conversationUuid,
            sortedMessages = history,
            characterName = character.name,
            userName = (userProfile?.nickname ?: "").ifEmpty { context.getString(R.string.pb_user_fallback) },
            vectorThresholdPercent = settings.vectorSearchThreshold,
            settings = settings.toWorldInfoSettings(),
        )
        val messages = PromptBuilder.buildMessages(
            character = character,
            // 延迟生成路：间隔是系统欠的,时间锚间隔行用中性措辞(T5 复核🟡④·2026-07-11 修)。
            delayedGeneration = true,
            conversation = convo,
            sortedMessages = history,
            userProfile = userProfile,
            appSettings = settings,
            strings = PromptStrings(context),
            structuredMemory = structuredMemory,
            milestones = milestones,
            todaySchedule = todaySchedule,
            todayScheduleEvents = todayScheduleEvents,
            calendarUpcomingEvents = calendarUpcoming,
            momentChatContext = momentChatContext,
            economicState = economicState,
            giftHistory = giftHistory,
            customStickers = customStickers,
            disabledStickers = disabledStickers,
            retrievedMemorySnippets = retrievedSnippets,
            offlineMeetingMemoryText = offlineMeetingMemoryText,
            unsummarizedRoundsOutsideBaseWindow = unsummarizedRounds,
            scene = PromptScene.ONLINE_CHAT,
            worldInfo = worldInfo,
            now = nowInstant,
        )

        val rawReply = contextLog.completion(
            source = LogSource.RECOVERY_REPLY,
            characterName = character.name,
            config = config,
            messages = messages,
        )
        // 与正常回复同序：parseMood → sanitize → 表情包归一（语音标签一律不保留，后台恢复纯文字）。
        // 卷一 A2c：**见面中**须保留线下叙事标签（[叙述]/[对话]/[场景：…]）——本管线也服务「见面期间的
        // 列表快捷回复 / 通知直接回复」，剥掉标签会让这条回复在沉浸剧场里缺席渲染结构（与主路径
        // ChatReplyDeliverer 的 preserveOfflineTags = isOffline 同源）。非见面照旧全剥。
        val inMeeting = convo.isInOfflineMode
        val mood = ReplyParser.parseMood(rawReply, preserveOfflineTags = inMeeting, preserveMiniMaxVoiceTags = false)
        val sanitized = ReplyParser.sanitizeAssistantResponse(
            mood.cleanText, characterName = character.name, preserveOfflineTags = inMeeting, preserveMiniMaxVoiceTags = false,
        )
        // 14.3c 用户自定义内容过滤：sanitize 后、表情包归一前净化（与主聊天/忙碌回复同接）。iOS 的未答恢复经
        // 共享聊天流水线 processAndDeliverFullResponse 会过滤，本无头重实现须显式补上，否则开启过滤规则后
        // 恢复/通知快捷回复/通知直接回复（共用本 generator）的 AI 正文会绕过过滤直接落库。
        val filtered = ContentFilterService.applyFilters(
            sanitized, ContentFilterService.loadRules(settings.contentFilterRulesJSON),
        )
        val stickerNormalized = StickerService.normalizeAssistantStickerTags(
            filtered, customStickers, settings.characterCanSendStickersEnabled,
        )
        if (stickerNormalized.isEmpty()) {
            Log.d(TAG, "未答恢复 LLM 返回空，跳过 conv=$conversationUuid")
            return false
        }

        // mood 持久化（解析到情绪时）：conversation.mood* + character.lastMood*（与正常回复一致）。
        val emotionTag = mood.emoji.ifEmpty { null }
        if (mood.emoji.isNotEmpty() || mood.text.isNotEmpty()) {
            conversationRepo.recordMood(conversationUuid, mood.emoji, mood.text, mood.colorName)
            // P12.6 D1b：列级写回心情三列，不再整行 upsert 覆盖分析/计数器并发写的列（分析不写这三列；心情写
            // 点之间为「最后写者生效」语义，无须进每角色锁）。
            characterRepo.updateMood(character.uuid, mood.emoji, mood.text, mood.colorName)
            // 情绪历史归档（成长系统）：与正常回复同源同点；复活情绪低落送礼加成 / 主动暖心送礼 / 善解人意标签。timestamp 必填此刻。
            characterRepo.appendMoodHistory(
                character.uuid,
                MoodHistoryEntry(
                    timestamp = System.currentTimeMillis(),
                    emoji = mood.emoji,
                    colorName = mood.colorName,
                    text = mood.text,
                ),
                settings.moodHistoryMaxCount,
            )
        }

        // 分段（<50 字不拆，对齐忙碌/正常回复阈值）→ 立即落库（不暂扣），时间戳严格递增。
        // 批4 4-2：与前台/忙碌路同口径吃用户「回复分条」设置。
        val segmentRange = settings.sanitizedReplySegmentRange
        val split = if (inMeeting) {
            // 卷一 A2c：见面期 = 单段投递（整条叙事不拆句），与主路径 ChatReplyDeliverer.deliverTextReply 同源——
            // 剧场按内容块渲染，拆句会把一段叙事切成互不相干的碎片。
            listOf(stickerNormalized)
        } else if (stickerNormalized.length < 50) {
            listOf(stickerNormalized)
        } else {
            MessageSplitter.split(stickerNormalized, maxSegments = segmentRange.last, minSegments = segmentRange.first)
                .ifEmpty { listOf(stickerNormalized) }
        }
        val baseTs = System.currentTimeMillis()
        // 见面期间触达（列表快捷回复 / 通知直接回复经本无头管线，目标会话正在见面中）须随会话打线下标记，与助手投递
        // deliverTextReply 同源——否则 AI 回复漏进普通聊天 + 缺席沉浸剧场。后台未答恢复扫描已过滤线下会话→此处恒 null·不受影响。
        val offlineSessionId = outgoingOfflineSessionId(convo.isInOfflineMode, convo.currentOfflineSessionId)
        // 落库前置闸（图纸 2026-09-01 件①）：判脏的段丢弃不落库；kind 与下方事务内 upsert 同口径。
        // 整轮全脏 → 放弃本次补回复（会话仍是待答态，下次扫描会重来一轮），绝不落半份脏内容。
        val segments = AssistantOutputGate.filterSegments(split, isOfflineMode = offlineSessionId != null, source = "recovery")
        if (segments.isEmpty()) {
            Log.d(TAG, "未答恢复输出整轮判脏，放弃 conv=$conversationUuid")
            return false
        }
        // 批3 3-2：分段落库 + 快照翻转包进**同一事务**——旧实现逐段 upsert 后才翻 lastMessageRole，中途进程死亡
        // = 半截回复已在库但会话仍是待答态 → 下次扫描再生成一轮 → 半截旧回复+完整新回复叠加双答。无头路径本就
        // 无打字节奏需求，原子写无副作用。（遵 CurrencyService 契约：事务内仅 suspend DAO 调用、不切调度器。）
        val lastTs = baseTs + segments.size - 1
        db.withTransaction {
            segments.forEachIndexed { index, segment ->
                messageRepo.upsert(
                    MessageEntity(
                        messageUUID = UUID.randomUUID().toString(),
                        conversationUuid = conversationUuid,
                        roleRaw = "assistant",
                        content = segment,
                        messageKindRaw = MessageKindInference.forAssistantText(segment, isOfflineMode = offlineSessionId != null).raw,
                        timestamp = baseTs + index,
                        emotionTag = emotionTag,
                        isOfflineMode = offlineSessionId != null,
                        offlineSessionId = offlineSessionId,
                    ),
                )
            }
            if (offlineSessionId != null) {
                // 见面期回复：与 finalizeDelivery 同源——见面正文绝不写进会话列表预览（方案 A·OfflineChatVisibility），也不 +1 未读
                //（消息归沉浸剧场·非日常未读）；仅刷新「最后活动时间」保鲜排序，预览保持入场标记直到见面收尾覆写。
                conversationRepo.touchLastMessageDate(conversationUuid, lastTs)
            } else {
                // 会话末条信息 + 未读 +1（后台恢复＝非活跃对话，markReadNow=false；活跃对话由聊天页 autoRecover 处理）。
                conversationRepo.applyMaterialization(
                    conversationUuid = conversationUuid,
                    preview = StickerTagParser.replaceStickerTagsForDisplay(segments.last()).take(60),
                    timestamp = lastTs,
                    markReadNow = false,
                )
            }
        }
        // 批4 4-3：当场嵌入（旧=等下次冷启回填才可语义检索）。
        runCatching {
            messageRepo.recentChronological(conversationUuid, segments.size).forEach { vectorMemory.embedMessageIfNeeded(it) }
        }
        Log.i(TAG, "未答恢复已生成 ${segments.size} 段 conv=$conversationUuid")
        return true
    }

    private companion object {
        private const val TAG = "UnansweredRecovery"
        private const val HISTORY_FETCH_LIMIT = 500
    }
}
