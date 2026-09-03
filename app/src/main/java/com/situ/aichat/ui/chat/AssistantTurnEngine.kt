package com.situ.aichat.ui.chat

import android.content.Context
import android.util.Log
import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.calendar.CalendarReader
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.economy.CharacterEconomicStateService
import com.situ.aichat.foreground.ForegroundActivity
import com.situ.aichat.foreground.LlmGenerationForegroundController
import com.situ.aichat.network.NetworkMonitor
import com.situ.aichat.gift.GiftHistoryPromptService
import com.situ.aichat.moments.MomentChatContextService
import com.situ.aichat.moments.MomentGenerationService
import com.situ.aichat.offline.OfflineMeetingAction
import com.situ.aichat.meeting.FutureMeetingTool
import com.situ.aichat.meeting.MeetingAppointmentStore
import com.situ.aichat.offline.ToolCallActionExtractor
import com.situ.aichat.openloop.OpenLoopScanService
import com.situ.aichat.notification.NotificationScheduler
import com.situ.aichat.data.local.dao.ScheduleDao
import java.time.Instant
import java.time.ZoneId
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.MeetingCandidate
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.CompletedToolCall
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.RequestToolCallDto
import com.situ.aichat.data.remote.llm.RequestToolCallFunctionDto
import com.situ.aichat.data.remote.llm.StreamToken
import com.situ.aichat.data.remote.llm.ToolCallAccumulator
import com.situ.aichat.data.remote.llm.UsageDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.diagnostics.LogToolInfo
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.PetWriteLock
import com.situ.aichat.data.repository.StickerRepository
import com.situ.aichat.prompt.ContextSegment
import com.situ.aichat.prompt.PromptBuilder
import com.situ.aichat.prompt.PromptBuilder.AssistantDeliveryMode
import com.situ.aichat.pet.OtherPetInfo
import com.situ.aichat.pet.PetCareService
import com.situ.aichat.pet.PetInventoryPromptService
import com.situ.aichat.pet.growthStage
import com.situ.aichat.pet.species
import com.situ.aichat.sticker.DisabledBuiltInStickerStore
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsService
import com.situ.aichat.tts.TtsVoiceProfile
import com.situ.aichat.tts.provider.MiniMaxVoiceTagsCapability
import com.situ.aichat.tts.provider.MiniMaxVoiceTagsSettings
import com.situ.aichat.prompt.PromptScene
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.prompt.memory.InSceneRecapCoordinator
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.worldbook.toWorldInfoSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 助手回合编排引擎（刀8·从 ChatViewModel 抽出·只搬不改）：构建 prompt → 流式（工具调用降级 + 空响应重试 +
 * 媒体降级）→ 交投递层 [ChatReplyDeliverer] 分条递送 → 成功后逐回合维护（嵌入/宠物/记忆/成长/关系/通知/补帖/
 * 节拍状态）。被 VM 各发送入口与 runAssistantTurnForCurrentConversation 复用；引擎管线（assistantTurnJob/
 * launchSerializedTurn/打断）仍由 VM 持有，本引擎只跑「一回合」。打字/递送/错误/info 流由 VM 持有并注入；
 * 节拍状态触发经 [incrementSceneProgress] 回调回 VM（其带 android Log 与 in-memory 状态，留 VM 最干净）。
 */
internal const val HISTORY_FETCH_LIMIT = 500


internal class AssistantTurnEngine(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val conversationUuid: String,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val offlineMeetingMemoryRepository: com.situ.aichat.data.repository.OfflineMeetingMemoryRepository,
    private val scheduleDao: ScheduleDao,
    private val giftDao: GiftDao,
    private val apiConfigRepo: ApiConfigRepository,
    private val stickerRepo: StickerRepository,
    private val petRepo: PetRepository,
    private val petWriteLock: PetWriteLock,
    private val petInventoryPromptService: PetInventoryPromptService,
    private val calendarReader: CalendarReader,
    private val llmClient: LlmClient,
    private val vectorMemory: VectorMemoryService,
    private val worldBookPromptService: com.situ.aichat.worldbook.WorldBookPromptService,
    private val worldChatContextProvider: com.situ.aichat.world.link.WorldChatContextProvider,
    private val memoryService: MemoryService,
    private val momentChatContextService: MomentChatContextService,
    private val economicStateService: CharacterEconomicStateService,
    private val ttsConfigRepo: TtsConfigurationRepository,
    private val llmForegroundController: LlmGenerationForegroundController,
    private val networkMonitor: NetworkMonitor,
    private val contextLog: ContextLogService,
    private val notificationScheduler: NotificationScheduler,
    private val momentGenerationService: MomentGenerationService,
    private val replyDeliverer: ChatReplyDeliverer,
    private val calendarHandler: ChatCalendarActionHandler,
    private val memoryAnalysisTrigger: MemoryAnalysisTrigger,
    private val inSceneRecapCoordinator: InSceneRecapCoordinator,
    private val relationshipAnalysisTrigger: RelationshipAnalysisTrigger,
    private val meetingDetectionTrigger: MeetingDetectionTrigger,
    private val openLoopDetectionTrigger: OpenLoopDetectionTrigger,
    private val openLoopRepository: com.situ.aichat.data.repository.OpenLoopRepository,
    private val promiseRepository: com.situ.aichat.data.repository.PromiseRepository,
    private val ourDayRepository: com.situ.aichat.data.repository.OurDayRepository,
    private val meetingAppointmentStore: MeetingAppointmentStore,
    private val errorFlow: MutableStateFlow<String?>,
    private val infoToastFlow: MutableStateFlow<String?>,
    private val isDelivering: MutableStateFlow<Boolean>,
    private val incrementSceneProgress: () -> Unit,
) {
    /** 批4 4-8：异常 → 用户可读中文短句（LlmError 自带本地化；Http 文案剥 " - " 后的原始错误体）。 */
    private fun userFacingError(e: Exception): String = when (e) {
        is com.situ.aichat.data.remote.llm.LlmError.Http ->
            e.message?.substringBefore(" - ") ?: appContext.getString(R.string.chat_error_generic)
        is com.situ.aichat.data.remote.llm.LlmError ->
            e.message ?: appContext.getString(R.string.chat_error_generic)
        is java.net.SocketTimeoutException -> appContext.getString(R.string.chat_error_timeout)
        is java.io.IOException -> appContext.getString(R.string.chat_error_connection)
        else -> e.message ?: appContext.getString(R.string.chat_error_generic)
    }

    /** 其他角色的宠物社交信息（前 5，跳过角色已删的），供 PET_STATUS「其他角色也养了宠物」段。 */
    private suspend fun resolveOtherPets(currentCharacterUuid: String): List<OtherPetInfo> =
        petRepo.getAll()
            .filter { it.characterUuid != currentCharacterUuid }
            .take(5)
            .mapNotNull { p ->
                characterRepo.get(p.characterUuid)?.let { c -> OtherPetInfo(c.name, p.name, p.species, p.growthStage) }
            }

    /**
     * 助手回合：构建 prompt → 流式（空响应重试）→ 分条打字延迟递送。被 send/regenerate 复用。
     * [userMessageForEmbed] 为本轮用户消息（regenerate 时为 null，仅嵌入助手消息）。
     */
    internal suspend fun runAssistantTurn(
        config: ApiConfigValues,
        character: CharacterEntity,
        settings: AppSettings,
        userProfile: UserProfileEntity?,
        userMessageForEmbed: MessageEntity?,
    ) {
        val userName = userProfile?.nickname ?: ""
        val convo = conversationRepo.get(conversationUuid) ?: return
        // P0-2：离线快速失败（对齐 iOS prepareStreamContext 的 NetworkMonitor guard）。所有发送入口都汇于此，
        // 一处守卫覆盖 send/sticker/voice/gift/redpacket/regenerate/线下恢复。用户消息已落库，错误 toast 提示重试。
        if (!networkMonitor.isConnected.value) {
            errorFlow.value = appContext.getString(R.string.chat_no_network)
            return
        }
        val promptStrings = PromptStrings(appContext)
        val history = messageRepo.recentChronological(conversationUuid, HISTORY_FETCH_LIMIT)

        // 多模态附件预取（音频 P13.4b / 图片一期）：读盘 + base64 全在 Default 线程，细节见 TurnMediaAttachments。
        val audioAttachments = TurnMediaAttachments.audio(history, config, convo.isInOfflineMode, convo.currentOfflineSessionId)
        val imageAttachments = TurnMediaAttachments.images(
            history = history,
            config = config,
            // 候选集与提示词窗口同口径（见面消息在线上模式整片不进窗口，不该占用图片名额）。
            inOfflineMode = convo.isInOfflineMode,
            currentOfflineSessionId = convo.currentOfflineSessionId,
        )

        // M05 向量检索 query = 最新一条【真实】用户消息（批3 3-6）：跳过系统耳语（SYSTEM_HINT 的 roleRaw='user'，
        // 爽约旁白会被当检索词）与结构化卡（礼物回合最新 user 消息是 GIFT_CARD 原始 JSON→该轮召回失效）；
        // 文本经 renderMemoryContent 渲染，与嵌入侧同源对称（表情标签/图片语义化）。
        val queryMessage = history.lastOrNull {
            val kind = MessageKind.fromRaw(it.messageKindRaw)
            it.roleRaw == "user" && kind != MessageKind.SYSTEM_HINT && !kind.isStructuredCard
        }
        val query = queryMessage?.let {
            MemoryService.renderMemoryContent(it.content, it.mediaMemorySummary, it.imageRelativePath != null)
        }.orEmpty()
        val retrievedSnippets = if (settings.vectorSearchThreshold > 0 && query.isNotBlank()) {
            vectorMemory.searchRelevantMemories(
                query = query,
                characterUuid = character.uuid,
                currentConversationUuid = conversationUuid,
                userName = userName.ifEmpty { promptStrings.s(R.string.pb_user_fallback) },
                characterName = character.name,
                shortTermLength = settings.shortTermMemoryLength,
                thresholdPercent = settings.vectorSearchThreshold,
            )
        } else {
            emptyList()
        }
        // WB4 世界书：每回合激活**一次**（降级/重试重装配复用同一结果——避免概率重掷与时效状态重复写）；
        // 无书/无条目/无命中 → null，装配零变化。触发设置每回合从 AppSettings 现读（WB7c·热更新 §12.11-3）。
        val worldInfo = worldBookPromptService.activateForTurn(
            characterUuid = character.uuid,
            conversationUuid = conversationUuid,
            sortedMessages = history,
            characterName = character.name,
            userName = userName.ifEmpty { promptStrings.s(R.string.pb_user_fallback) },
            vectorThresholdPercent = settings.vectorSearchThreshold,
            settings = settings.toWorldInfoSettings(),
        )
        // W5 世界联动（契约 §9【核心】）：注入「关系提炼 + 世界记忆」块——门控四连（未入世/开关关/世界未初始化/
        // 无边无记忆 → null）·纯读 + 一次 ONNX 嵌入·query=最新真实用户消息（与向量检索同源）。
        val worldContext = worldChatContextProvider.forTurn(character, query, settings)
        // 承诺回连注入（活人感一期 P2·§3.2）：预取该角色 open 惦记的事，注入选择/格式化在 PromptBuilder 内用 timeSnapshot 完成。
        val openLoops = openLoopRepository.openLoopsForCharacter(character.uuid)
        // 长线回访（活人感二期 M2·图纸 §3.2）：已 resolved 的「惦记的事」在 7–30 天后由角色回头问一次进展（一次为限）。
        // 两门控全过才取候选——①非线下 ②无到期 open 项（有到期让位·E4）；取到则随 openLoops 透传给
        // selectLoopsForInjection（resolved 项走回访分支），回合成功后 markRevisited 置终态（E6 失败不标）。now 预取时取一次。
        val revisitNow = System.currentTimeMillis()
        val revisitLoop: OpenLoopEntity? = if (
            !convo.isInOfflineMode &&
            openLoops.none { it.dueAt != null && it.dueAt <= revisitNow }
        ) {
            OpenLoopScanService.selectRevisitLoop(openLoopRepository.revisitCandidates(character.uuid, revisitNow))
        } else {
            null
        }
        val openLoopsForPrompt = if (revisitLoop != null) openLoops + revisitLoop else openLoops
        // 承诺账本注入（记忆改造一期·部件①·§3.3）：预取该角色注入候选约定（open 全量 + 近 7 天已结）；
        // 选择/排序/软上限/渲染在 PromptBuilder 内经 PromiseInjectionRenderer 用 ctx.now 完成（照 openLoops 透传路径）。
        val promises = promiseRepository.injectableForCharacter(character.uuid, System.currentTimeMillis())
        // 我们的日子·卷二（图纸 §3.3）：预取注入候选行（deleted=0·hidden=0·factLine 非空），筛选渲染在 PromptBuilder 内。
        val ourDays = ourDayRepository.injectableForCharacter(character.uuid)
        val unsummarizedRounds = memoryService.countUnsummarizedRoundsOutsideBaseWindow(
            currentConversation = convo,
            baseShortTermLength = settings.shortTermMemoryLength,
        )
        val structuredMemory = StructuredMemory.decode(character.structuredMemoryJSON)
        val offlineMeetingMemoryText = offlineMeetingMemoryRepository.renderedForInjection(character.uuid)
        // 记忆改造二期·部件④ 见面时间线注记（§3.1）：预取该角色全部见面档案行（注记端 selectEligible 自筛跨度 + 上限 5）；
        // 仅普通在线聊天时产注记（PromptBuilder 内 now 门控·见面 / 通话 / 忙碌场景零注记）。
        val meetingRows = offlineMeetingMemoryRepository.byCharacter(character.uuid)
        // M14 成长模块：关系里程碑（升序）注入 characterGrowth。
        val milestones = characterRepo.getMilestones(character.uuid)
        // M12 日程模块（P5.2）：今日日程 + 事件注入 scheduleAwareness/currentMoment。
        // 用同一个 now 既定"今天"又传给 PromptBuilder，避免跨午夜时两处读不同日期。
        val nowInstant = Instant.now()
        val todayStartMillis = nowInstant.atZone(ZoneId.systemDefault())
            .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val todaySchedule = scheduleDao.scheduleFor(character.uuid, todayStartMillis)
        val todayScheduleEvents = todaySchedule?.let { scheduleDao.eventsForSchedule(it.uuid) } ?: emptyList()
        // 时间感知三期：今天之前 3 天的日程事件 → 【你最近几天的日子】。起点用**日期算术**
        //（不是 todayStart − 3×86400000，夏令时 / 时区偏移下不安全）；复用上面同一个 now，不另起 Instant.now()。
        val recentDaysStartMillis = Instant.ofEpochMilli(todayStartMillis).atZone(ZoneId.systemDefault())
            .toLocalDate().minusDays(3).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val recentDaysScheduleEvents = scheduleDao.eventsForCharacterSince(character.uuid, recentDaysStartMillis)
        // 等待期（Phase 9）：该角色下一个「已确认未来约定」→ 注入【待见约定】让 AI 自然提起（PromptBuilder 仅非线下时注入）。
        val nextMeetingAppointment = meetingAppointmentStore.nextUpcomingForCharacter(character.uuid, nowInstant.toEpochMilli())
        // M12 日历感知（P5.3a）：已授权 + 开启时注入设备日历近期事件（calendarAwareness）。
        // P5.3b：同时刷新 #E{n} → 事件 id 映射，供写入闭环解析 ref（对齐 iOS fetchCalendarContext）。
        val calendarUpcomingEvents = if (settings.calendarIntegrationEnabled) {
            val upcoming = calendarReader.upcomingEvents(nowInstant.toEpochMilli())
            calendarHandler.calendarEventRefMap = upcoming?.eventRefMap ?: emptyMap()
            upcoming?.text
        } else {
            calendarHandler.calendarEventRefMap = emptyMap()
            null
        }
        // M06 朋友圈上下文（P7.2.6）：近 7 天角色与用户的朋友圈互动摘要，注入 MOMENTS_CONTEXT 模块，
        // 让 AI 聊天时能自然带出朋友圈。无互动 → null（模块跳过）。userNickname 兜底「用户」=对齐 iOS `?? "User"`。
        val momentChatContext = momentChatContextService.buildMomentContext(
            character = character,
            userNickname = userName.ifEmpty { promptStrings.s(R.string.pb_user_fallback) },
            scheduleSystemEnabled = settings.scheduleSystemEnabled,
            nowMillis = nowInstant.toEpochMilli(),
        )
        // M17 表情包：自定义贴纸（createdAt 升序）+ 被隐藏的内置 → STICKER_LIBRARY 模块 + 历史别名转换。
        val customStickers = stickerRepo.getAllForPrompt()
        val disabledStickers = DisabledBuiltInStickerStore.disabledIds(appContext)
        // M11 宠物：角色宠物 + 其他角色宠物社交 → PET_STATUS 模块（宠物系统关或无宠物自动跳过）。
        val pet = if (settings.petSystemEnabled) petRepo.getForCharacter(character.uuid) else null
        val otherPets = if (pet != null) resolveOtherPets(character.uuid) else emptyList()
        // 最近 24h 给宠物买的东西（去重 isPetMessage 提过的）→ 宠物状态「最近买的东西」行（无宠物则不查）。
        val petRecentPurchaseNames =
            if (pet != null) petInventoryPromptService.recentPetShopItemNames(nowInstant.toEpochMilli()) else emptyList()
        // M10 经济状态（P9.1a）：角色月薪/余额/欠租 → 压力档位注入 CHARACTER_ECONOMIC_STATE 模块；月薪 0/无钱包 → null（模块跳过）。
        val economicState = economicStateService.resolveChatState(character.uuid, nowInstant.toEpochMilli())
        // M09 礼物历史（P9.2b）：双向 GiftRecord → <gift_history> 块注入 GIFT_HISTORY 模块；双向无礼物 → "" 跳过。
        val giftHistory = GiftHistoryPromptService.buildContent(character.uuid, giftDao, nowInstant.toEpochMilli(), userName.ifEmpty { promptStrings.s(R.string.pb_user_fallback) })
        // P10.1c 语音回复预判（对齐 iOS plannedAssistantDeliveryPlan + buildMiniMaxVoiceTagsCapability）：
        // 先决定本轮是文字还是语音，结果同时驱动 ① 提示词 deliveryMode/语气标签前门 ② 投递分支。
        // 审计 S5：计划计算搬 VoiceDelivery.kt（VoicePlan 的家），引擎注入自有依赖，行为不变。
        val voicePlan = resolveVoicePlan(character, convo, history, settings, conversationUuid, ttsConfigRepo, conversationRepo, appContext)
        // S3b 结构化工具调用双轨：是否走工具路 = 模型能力综合判定（H5·#7 解绑「日历集成」连坐——线下/约见面工具
        // 与日历无关，不再被日历开关一起踢回暗号；日历工具改由 buildChatToolDefinitions 按集成开关单独决定）。
        // useToolCalling 同时决定 ① 走不走工具路 ② PromptBuilder 选「工具调用」还是「文本标记」提示词。有意与 iOS（耦合）分叉。
        val useToolCalling = config.toolCallingEnabled
        val canInitiateOffline = settings.characterCanInitiateOfflineMeeting
        // ② 执行失败回流：本轮装配前**一次性**消费该会话「未消费的日历真失败」（已 TTL 过滤）；工具/降级两版装配共用同一值。
        val calendarFailureNudge = calendarHandler.consumePendingFailure(nowInstant.toEpochMilli())
        // 同一上下文按 toolCallingEnabled 装配两版消息：tool 路用 useToolCalling 版；降级时用 false 版（纯文本标记）。
        fun assembleMessages(
            toolCallingEnabled: Boolean,
            /** 媒体降级重试时置 false：音频与图片一并不挂，改按纯文本/语义占位装配。 */
            withMedia: Boolean = true,
            // 批 D 上下文日志：仅主装配传非 null 收一次分段（fallback 重装配不再收，1:1 iOS 一次性 buildResult.segments）。
            segmentSink: MutableList<ContextSegment>? = null,
        ): List<ChatMessageDto> = PromptBuilder.buildMessages(
            character = character,
            conversation = convo,
            // 批2 2-7：按当前线下态传对场景——修复前恒默认 ONLINE_CHAT，模块的「线下见面」场景勾选是摆设
            // （勾「仅线下」永不注入、勾「排除线下」线下照样注入）。默认模块 enabledScenes=null=全场景，行为不变；
            // 时间分割线本就有 !isCurrentlyInOfflineMode 双保险，不受影响。
            scene = if (convo.isInOfflineMode) PromptScene.OFFLINE_MEETING else PromptScene.ONLINE_CHAT,
            sortedMessages = history,
            userProfile = userProfile,
            appSettings = settings,
            strings = promptStrings,
            structuredMemory = structuredMemory,
            milestones = milestones,
            todaySchedule = todaySchedule,
            todayScheduleEvents = todayScheduleEvents,
            recentDaysScheduleEvents = recentDaysScheduleEvents,
            calendarUpcomingEvents = calendarUpcomingEvents,
            momentChatContext = momentChatContext,
            economicState = economicState,
            giftHistory = giftHistory,
            customStickers = customStickers,
            disabledStickers = disabledStickers,
            pet = pet,
            otherPets = otherPets,
            petRecentPurchaseNames = petRecentPurchaseNames,
            retrievedMemorySnippets = retrievedSnippets,
            offlineMeetingMemoryText = offlineMeetingMemoryText,
            worldContext = worldContext,
            openLoops = openLoopsForPrompt,
            promises = promises,
            ourDays = ourDays,
            assistantDeliveryMode = if (voicePlan.plan.isVoice) AssistantDeliveryMode.VOICE else AssistantDeliveryMode.TEXT,
            miniMaxVoiceTagsCapability = voicePlan.capability,
            toolCallingEnabled = toolCallingEnabled,
            unsummarizedRoundsOutsideBaseWindow = unsummarizedRounds,
            // P13.4b：withMedia=false（媒体降级重试）时强制按纯文本（转写）装配。
            audioInputEnabled = config.audioInputEnabled && withMedia,
            audioAttachments = if (withMedia) audioAttachments else emptyMap(),
            // 图片同构：降级重试时不挂 image 段，图片消息退语义占位（正文照旧可读）。
            visionEnabled = config.visionEnabled && withMedia,
            imageAttachments = if (withMedia) imageAttachments else emptyMap(),
            nextMeetingAppointment = nextMeetingAppointment,
            calendarFailure = calendarFailureNudge,
            worldInfo = worldInfo,
            now = nowInstant,
            meetingTimeline = meetingRows,
            // 记忆改造二期·部件⑤ 前情提要门控（§3.2-E 见面）：本场（key==currentOfflineSessionId）且提要非空才注入。
            inSceneRecap = convo.inSceneRecapText.takeIf {
                convo.isInOfflineMode &&
                    convo.inSceneRecapSessionKey.isNotEmpty() &&
                    convo.inSceneRecapSessionKey == convo.currentOfflineSessionId &&
                    it.isNotBlank()
            },
            segmentSink = segmentSink,
        )
        // 批 D 上下文日志：主装配顺带收一次结构化分段（聊天管线，1:1 iOS buildMessagesWithSegments）；
        // 后续 fallback/降级重装配不再收。每次流式尝试落一条日志（source=CHAT），用本表 + 末帧 usage。
        val chatSegments = mutableListOf<ContextSegment>()
        var chatMessages = assembleMessages(useToolCalling, segmentSink = chatSegments)
        // P13.4b 媒体降级重试状态：本轮是否真的挂了音频段 + 是否已去媒体降级过（最多降级一次）+ 是否发生过降级（供成功后提示）。
        val hasAttachedAudio = config.audioInputEnabled && audioAttachments.isNotEmpty()
        val hasAttachedImage = config.visionEnabled && imageAttachments.isNotEmpty()
        val hasAttachedMedia = hasAttachedAudio || hasAttachedImage
        var mediaStripped = false
        var mediaFellBackToText = false
        // 本轮合并等待窗覆盖的用户消息：降级提示据此判断「剥掉的图是不是这一轮发的」（谓词体在 TurnMediaAttachments）。
        val turnUserMessageUuids = TurnMediaAttachments.turnUserMessageUuids(history)

        val dotsAppearMillis = System.currentTimeMillis()
        replyDeliverer.openTypingSlot() // B1：打字点亮起即预分配首段 uuid + 发布渲染占位槽（dots 在流式生成期就显示）
        val sb = StringBuilder()
        var delivered = false
        // 13.7b A7「切走不中断」：流式回合期间挂轻量 dataSync 前台服务保活，防 HyperOS 在用户切去别的 app 后秒杀进程导致
        // 回复断半截（= iOS beginBackgroundTask 的安卓加强版：iOS 仅 ~30s 宽限，安卓前台服务无时限）。纯进程保活锚点，
        // 不改本管线；release 在下方 finally，覆盖正常完成/异常/取消三路（acquire best-effort 不抛，须前台调用——发消息总在开 app 时）。
        llmForegroundController.acquire()
        llmForegroundController.setTyping(ForegroundActivity.Typing(character.name, character.avatarPath, conversationUuid))
        try {
            // M03/D5 空响应保护（P12.6 D5，对齐 iOS handleEmptyResponse）：以「清洗+分段后是否为空」判空，**而非原始流是否为空**——
            // 模型回的内容非空、但清洗后全被吃掉（整条都是 mood/无效表情/纯语气标签）时也算空响应（旧实现只判原始流空 → 这种会
            // 静默丢掉一轮：打字气泡闪一下消失、无消息无提示，且 embed/记忆/成长等逐回合维护照跑）。每次尝试经 streamOneTurn（tool 路
            // 收正文 + tool_calls，含降级链 + needsTextFollowUp）+ deliverAssistantReply（mood 落库 + 清洗 + 分段投递，**返回实际落库
            // 的消息**：清洗后为空则空表）。清洗后空且无结构化动作 → 自动重试一次（用户无感，iOS consecutiveEmptyResponses<=1）；仍空 →
            // 报可重试错误 + 不跑逐回合维护。结构化动作（日历/线下卡片）即「非空回合」（卡片即完整回复）→ 不重试、不报空（避免重试再生成重复卡片）。
            var result = TurnStreamResult.EMPTY
            var turn = DeliveredTurn.EMPTY
            var attempt = 0
            while (attempt < MAX_STREAM_ATTEMPTS) {
                attempt++
                // 批 D 上下文日志：每次流式尝试单独计时 + 捕获末帧 usage（onUsage 穿到本轮所有内部 streamChat/completion）。
                val turnStart = System.currentTimeMillis()
                var turnUsage: UsageDto? = null
                result = try {
                    // 去媒体降级后强制不发工具（1:1 iOS buildFallbackMessages：toolCallingEnabled=false，纯文本回合）。
                    streamOneTurn(chatMessages, config, useToolCalling && !mediaStripped, canInitiateOffline, settings.calendarIntegrationEnabled, settings.calendarActionConfirmation, sb, settings.sanitizedLlmTemperature, onUsage = { turnUsage = it }) {
                        assembleMessages(false, withMedia = !mediaStripped)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 失败落库（本尝试真实发生过一次 LLM 调用，每 attempt 各记一条）；fire-and-forget 不阻塞 UI 流。
                    contextLog.recordError(LogSource.CHAT, character.name, config.modelName, chatMessages, e, chatSegments)
                    // P13.4b 媒体降级重试（1:1 iOS retryStreamWithoutMedia）：本轮挂了音频段且流式失败 → 去音频 + 去工具
                    // 改纯文本（端侧转写当用户轮）重试一次；不消耗空响应重试额度。仍失败/无音频 → 上抛原异常。
                    if (hasAttachedMedia && !mediaStripped) {
                        mediaStripped = true
                        mediaFellBackToText = true
                        chatMessages = assembleMessages(false, withMedia = false)
                        attempt--
                        continue
                    }
                    throw e
                }
                // 成功落库（本尝试的消息 + 分段 + 末帧 usage·耗时；用 turnStart 而非 dotsAppearMillis=只算 LLM 调用时长）。
                contextLog.recordSuccess(
                    LogSource.CHAT, character.name, config.modelName, chatMessages, result.text,
                    System.currentTimeMillis() - turnStart, turnUsage, chatSegments,
                    toolInfo = result.toolInfo,
                )
                turn = replyDeliverer.deliverAssistantReply(
                    result.text, character, settings, dotsAppearMillis, immediate = false, voicePlan = voicePlan,
                    toolCalendarActions = result.calendarActions,
                    toolOfflineActions = result.offlineActions,
                    hasOfflineMeetingToolCall = result.hasOfflineMeetingToolCall,
                )
                // 「非空回合」= 落库了文字消息，或本次投递了结构化动作（日历/线下卡片，**含文本标记来源**）。
                // 用 turn.deliveredStructuredAction（覆盖 tool-call + 文本标记两来源，而非仅 tool-call 来源）兜住「线下邀约/结束卡
                // 文本标记 + 空正文」——否则会被误判为空响应而重试、重复插一张卡（对齐 iOS 空分支的 !mergedOfflineActions.isEmpty 豁免）。
                // 未来约定见面候选（工具/暗号·8d-3b）也算「非空回合」——否则纯 tool-call 提案（正文空）会被误判空响应而重试。
                if (turn.messages.isNotEmpty() || turn.deliveredStructuredAction ||
                    result.meetingToolCandidates.isNotEmpty() || turn.meetingMarkerCandidates.isNotEmpty()
                ) {
                    break
                }
            }

            // 未来约定见面快路候选（工具 + 文本暗号·8d-3b）：合并两来源；非空则本回合即便正文空也算「有产出」（将冒确认卡），不报失败。
            val meetingFastCandidates = result.meetingToolCandidates + turn.meetingMarkerCandidates
            if (turn.messages.isEmpty() && !turn.deliveredStructuredAction && meetingFastCandidates.isEmpty()) {
                // 重试后清洗仍空 → 错误提示；不跑 embed/记忆/成长/通知等逐回合维护（对齐 iOS 仅成功投递才跑）。
                // 批4 4-8：文案挪资源 + 不再指向不存在的重试按钮（2-2 重试入口随输入排重构另落）。
                errorFlow.value = appContext.getString(R.string.chat_error_empty_reply)
            } else {
                delivered = true
                // P13.4b：媒体降级重试成功投递 → 提示用户语音已转文字发送（1:1 iOS showToast(.audioInputFallback)，仅成功路径）。
                if (mediaFellBackToText) {
                    // 只在**这一轮真的挂过**对应媒体时才那样说。图片名额恒取最近 3 张 → hasAttachedImage 近乎常真，
                    // 若不加这层判断，任何网络抖动导致的降级都会弹「对方没能看到这张图」，而真实原因与图无关。
                    // 判据取「本轮**真的挂上去**的那几张图里，有没有属于这一轮的」。
                    // 不能用「窗口里有图」（名额恒取最近 3 张 → 近乎恒真，任何网络抖动都会误报图片）；
                    // 也不能只看「最后一条 user 消息带图」——合并等待窗会把「先发图、再补一句话」并成一轮，
                    // 那时最后一条是文字，明明剥掉的是图却会弹语音的文案（R2 🔵-6）。
                    val strippedImage = imageAttachments.keys.any { uuid ->
                        turnUserMessageUuids.contains(uuid)
                    }
                    infoToastFlow.value = appContext.getString(
                        if (strippedImage) R.string.chat_image_input_fallback else R.string.voice_audio_input_fallback,
                    )
                }
                // M05：为本轮消息生成嵌入（后台，不阻塞输入与下一轮）
                embedTurnInBackground(userMessageForEmbed, turn.messages)
                // M11 宠物：聊天互动加成（+1 成长 + lastInteractionDate，1:1 iOS ChatViewModel+PostProcess:337）。
                // D1d：锁内重读最新宠物再写——pet 快照在本轮 LLM 流式开始前读出（733 行），整轮数秒内用户可能喂养/
                // 小组件/回前台维护写同一宠物，直接整行写回会用陈旧快照覆盖。与详情页/维护同款 fresh-read-in-lock。
                if (pet != null) {
                    petWriteLock.withPetLock(pet.uuid) {
                        val fresh = petRepo.getByUuid(pet.uuid) ?: return@withPetLock
                        petRepo.upsert(PetCareService.chatInteraction(fresh, settings))
                    }
                }
                // M05 记忆层：回复完成后触发滚动 LLM 摘要 + 结构化记忆抽取（背景、各自带触发判定）。
                // 记忆功能可单独分配 API 配置（APIFunctionRouter，未分配则回退当前激活配置 = config）。
                val memoryConfig = apiConfigRepo.resolveConfigValues(ApiFunction.MEMORY_SUMMARY) ?: config
                memoryAnalysisTrigger.checkAndTriggerMemorySummary(character.uuid, memoryConfig, settings, userName)
                memoryAnalysisTrigger.incrementStructuredMemoryRoundAndCheck(character.uuid, memoryConfig, settings, userName)
                // 场内滚动压缩·前情提要（记忆改造二期·部件⑤·§3.2-B）：见面回合尾后台把本场早期被丢弃部分压缩成前情提要
                // （内部守卫仅见面中生效·单飞 + 冷却）。fire-and-forget 不阻塞回合后段（随既有触发段调度惯例）。
                scope.launch { inSceneRecapCoordinator.checkMeetingRecap(conversationUuid) }
                // M14 成长分析（功能可单独分配 API；未分配回退当前激活 = config）
                val growthConfig = apiConfigRepo.resolveConfigValues(ApiFunction.GROWTH_ANALYSIS) ?: config
                relationshipAnalysisTrigger.incrementGrowthRoundAndCheck(character.uuid, growthConfig, settings, userName, // 卷四层 ①：本轮文本
                    userText = userMessageForEmbed?.content.orEmpty())
                // M14 关系评估：每轮递增 relationshipMessageCount + 保底触发判定（链式触发在成长分析完成后）
                relationshipAnalysisTrigger.incrementRelationshipRoundAndCheck(character.uuid, settings, userName)
                // 未来约定见面·快路（工具/文本暗号·8d-3b）：当场识别的候选即时入库冒确认卡（不过扫描节奏）。
                meetingDetectionTrigger.ingestFastPath(meetingFastCandidates, character)
                // 未来约定见面识别（骨干路·8d-3a）：按节奏后台扫最近对话识别约定 → coordinator 入库（NEW 过确认卡）。
                meetingDetectionTrigger.checkAndTrigger(character, config, userName)
                // 承诺回连（活人感一期 P2·骨干路）：按节奏后台扫最近对话提取「惦记的事」→ 落库 + 排到期 worker。
                openLoopDetectionTrigger.checkAndTrigger(character, config, userName)
                // 长线回访（活人感二期 M2·§3.2）：本轮确实带了回访项 → 置终态 revisited（一次为限·E12）；resolvedAt 原值保留（E8）。
                // 回合失败/被打断走不到此处 → 不标记 → 下回合重新候选（E6）。
                revisitLoop?.let { openLoopRepository.markRevisited(it, System.currentTimeMillis()) }
                // M16 线下节拍状态：仅线下模式生效（内部判定），≥15 user 差 + 3min 防抖触发 LLM 生成（对齐 iOS）。
                incrementSceneProgress()
                // P6.1c：发完消息后重排该角色主动消息通知（对齐 iOS ChatViewModel+Send 发消息后 scheduleNotifications；
                // 内部 shouldRebuild 守卫，状态未变则不重排、不烧 LLM）。
                notificationScheduler.schedule(character)
                // P7.2.5：回完消息顺手补发欠帖（角色发帖时在睡→记欠帖，醒后聊天触发，延迟 40~80s）。
                // 无欠帖即 no-op；先清后排唯一任务，不会重复补发（对齐 iOS triggerCatchUpPostIfNeeded）。
                momentGenerationService.triggerCatchUpPostIfNeeded(character.uuid)
            }
        } catch (e: Exception) {
            // 健康线 2-5（停止钮退役后取消语义统一=丢弃）：CancellationException 直接上抛（下面 rethrow）——
            // 流式半截 sb **不再持久化**（旧 M03「取消保存部分正文」的唯一正当受益场景=停止钮想留住已说的话，
            // 随停止钮退役废止）。已递送段的保留/预览翻转在递送层取消路径完成；零段已插=保未答态由恢复系统补整条。
            if (e is CancellationException) throw e
            // 批4 4-8：异常按类型映射中文短句——不再把 OkHttp 英文原文（"Failed to connect to <host>"）
            // 或原始错误体 JSON 直出 Snackbar；LlmError 自带本地化文案（Http 剥掉附带的原始 body 段）。
            errorFlow.value = userFacingError(e)
        } finally {
            isDelivering.value = false
            replyDeliverer.closeTypingSlot() // B1：回合终态统一清打字占位槽（覆盖完成/异常/取消/空响应；末段已 dedup 此处仅清预留）
            // 13.7b A7：流式回合结束（完成/异常/取消）即撤前台保活；引用计数让并发回合/故事生成共享同一锚点。
            llmForegroundController.clearTyping() // 灵动岛卷一：药丸管等待、消息通知管结果——回合一终结即撤（E13）
            llmForegroundController.release()
        }
    }

    /** 一次流式回合的结果（S3b）：可见正文 + 结构化日历/线下动作 + 未来约定候选（tool call 解析）+ 工具遥测。 */
    private data class TurnStreamResult(
        val text: String,
        val calendarActions: List<CalendarAction>,
        val offlineActions: List<OfflineMeetingAction>,
        val hasOfflineMeetingToolCall: Boolean,
        val meetingToolCandidates: List<MeetingCandidate> = emptyList(),
        /** 工具遥测（上下文日志工具可见性·随 recordSuccess 落库；装配逻辑在 [LogToolInfo] 工厂）。 */
        val toolInfo: LogToolInfo? = null,
    ) {
        companion object {
            val EMPTY = TurnStreamResult("", emptyList(), emptyList(), false)
        }
    }

    /**
     * 跑一次流式回合（1:1 iOS streamResponse 的接收 + 解析段）：
     * - useToolCalling=false → 纯文本流，只收正文。
     * - useToolCalling=true → 发 tools（日历 + 线下，按可主动邀约开关过滤），收正文 + 累积 tool_calls：
     *   ① 流抛异常 → 清空正文 + 降级纯文本重发 + 提示；② tool 参数解析失败（parsingFailed）→ 同一条降级链路；
     *   ③ needsTextFollowUp（有动作且非「只有线下动作」且正文空）→ 发工具结果取文字回复。
     * 取消异常一律上抛给 runAssistantTurn 的取消处理（保存部分正文）。
     */
    private suspend fun streamOneTurn(
        toolMessages: List<ChatMessageDto>,
        config: ApiConfigValues,
        useToolCalling: Boolean,
        canInitiateOffline: Boolean,
        includeCalendarTool: Boolean,
        calendarNeedsConfirmation: Boolean,
        sb: StringBuilder,
        temperature: Double,
        // 批 D 上下文日志：末帧 usage 回调，穿到本轮所有内部 streamChat（tool/plain/fallback）。follow-up completion 不穿（= iOS）。
        onUsage: ((UsageDto) -> Unit)? = null,
        buildFallback: () -> List<ChatMessageDto>,
    ): TurnStreamResult {
        sb.setLength(0)
        if (!useToolCalling) {
            streamPlainContent(toolMessages, config, sb, temperature, onUsage)
            return TurnStreamResult(sb.toString(), emptyList(), emptyList(), false, toolInfo = LogToolInfo.marker())
        }

        val accumulator = ToolCallAccumulator()
        val tools = buildChatToolDefinitions(includeCalendarTool = includeCalendarTool, canInitiateOffline = canInitiateOffline)
        try {
            llmClient.streamChat(messages = toolMessages, config = config, temperature = temperature, tools = tools, onUsage = onUsage).collect { token ->
                when (token) {
                    is StreamToken.Content -> sb.append(token.text)
                    is StreamToken.ToolCallDelta -> accumulator.process(token.chunk)
                    is StreamToken.Reasoning -> Unit // 思考内容已剥离，展示延后
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ① 工具调用流抛异常 → 降级纯文本模式重试 + 提示（1:1 iOS toolCallFailed 分支 + .toolCallFallback）。
            sb.setLength(0)
            streamPlainContent(buildFallback(), config, sb, temperature, onUsage)
            infoToastFlow.value = appContext.getString(R.string.tool_call_fallback_notice)
            return TurnStreamResult(
                sb.toString(), emptyList(), emptyList(), false,
                toolInfo = LogToolInfo.toolTurn(tools.map { it.function.name }, emptyList(), fellBackToPlainText = true),
            )
        }

        val completed = accumulator.completedCalls()
        val calRes = ToolCallActionExtractor.parseCalendarActions(completed)
        val offRes = ToolCallActionExtractor.parseOfflineActions(completed, canInitiateOffline)
        val meetingCandidates = ToolCallActionExtractor.parseFutureMeetingActions(completed)
        val callsForLog = completed.map { it.name to it.arguments }
        if (ToolCallActionExtractor.shouldFallBackToText(calRes, offRes, meetingCandidates)) {
            // ② 工具调用「全军覆没」（有失败、且一个可用动作/候选都没解出）→ 文本降级链路。
            //    部分成功不再走这里：留住已解析的、丢掉坏的那个（H2·治 #5 一坏毁整轮·坏调用已在 extractor 内跳过）。
            sb.setLength(0)
            streamPlainContent(buildFallback(), config, sb, temperature, onUsage)
            infoToastFlow.value = appContext.getString(R.string.tool_call_fallback_notice)
            return TurnStreamResult(
                sb.toString(), emptyList(), emptyList(), false,
                toolInfo = LogToolInfo.toolTurn(tools.map { it.function.name }, callsForLog, fellBackToPlainText = true),
            )
        }

        var text = sb.toString()
        // ③ 只回 tool_calls 没正文时，发工具结果取文字回复（线下卡本身即完整回复 → 不 follow-up）。
        val usedTextFollowUp = AssistantResponsePreprocessor.needsTextFollowUp(calRes.actions, offRes.actions) && text.isBlank()
        if (usedTextFollowUp) {
            text = fetchToolCallFollowUp(toolMessages, completed, config, temperature, calendarNeedsConfirmation)
        }
        return TurnStreamResult(
            text, calRes.actions, offRes.actions, offRes.actions.isNotEmpty(), meetingCandidates,
            toolInfo = LogToolInfo.toolTurn(
                tools.map { it.function.name }, callsForLog,
                parsedCalendarActions = calRes.actions.size,
                parsedOfflineActions = offRes.actions.size,
                parsedMeetingCandidates = meetingCandidates.size,
                usedTextFollowUp = usedTextFollowUp,
            ),
        )
    }

    /** 纯文本流式接收：只累积可见正文（<think> 已在 LlmClient 实时剥离）。 */
    private suspend fun streamPlainContent(
        messages: List<ChatMessageDto>,
        config: ApiConfigValues,
        sb: StringBuilder,
        temperature: Double,
        onUsage: ((UsageDto) -> Unit)? = null,
    ) {
        llmClient.streamChat(messages = messages, config = config, temperature = temperature, onUsage = onUsage).collect { token ->
            if (token is StreamToken.Content) sb.append(token.text)
        }
    }

    /**
     * 模型只返回 tool_calls 没文本时，回传工具调用 + 工具结果，取一段文字回复（1:1 iOS fetchToolCallFollowUp）。
     * 日历工具按 call.id 索引动作给具体执行描述；线下工具给明确结果文案；网络失败回用户友好提示（非内部元数据）。
     * 注：DeepSeek thinking 的同轮 reasoning 回传暂略（安卓 chat 路当前不外显 reasoning）。
     */
    private suspend fun fetchToolCallFollowUp(
        originalMessages: List<ChatMessageDto>,
        completedCalls: List<CompletedToolCall>,
        config: ApiConfigValues,
        temperature: Double,
        calendarNeedsConfirmation: Boolean,
    ): String {
        val assistantMsg = ChatMessageDto(
            role = "assistant",
            content = null,
            toolCalls = completedCalls.map {
                RequestToolCallDto(id = it.id, type = "function", function = RequestToolCallFunctionDto(it.name, it.arguments))
            },
        )

        val followUp = ArrayList<ChatMessageDto>(originalMessages.size + completedCalls.size + 1)
        followUp.addAll(originalMessages)
        followUp.add(assistantMsg)
        for (call in completedCalls) {
            // 每个 call 按自身参数**就地**解析对应日历动作（替代旧的「按位下标映射 calendarActions」——
            // 解析失败 / 线下 / 约见面混调时下标会错配，把别的调用的结果文案安到这条上，见 H3）。
            val calendarAction = if (
                !OfflineMeetingAction.isOfflineMeetingTool(call.name) && !FutureMeetingTool.isFutureMeetingTool(call.name)
            ) {
                runCatching { CalendarAction.fromToolCallArguments(call.arguments) }.getOrNull()
            } else {
                null
            }
            // 据实陈述、绝不预报「已完成」（确认卡待确认 / 自动执行将写入），口吻交角色提示词把关（决定 C）。
            // ③ 大输出安全阀（单点接线）：结果文案过阀截断。当前状态串短、永不触发（0-3 golden 看门）；
            // 将来「内容返回型工具」的大输出经此自动截断、防撑爆对话。
            val resultText = truncateToolResultText(
                toolFollowUpResultText(calendarAction, call.name, calendarNeedsConfirmation),
            )
            followUp.add(ChatMessageDto(role = "tool", content = resultText, toolCallId = call.id))
        }
        return try {
            llmClient.completion(messages = followUp, config = config, temperature = temperature)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appContext.getString(R.string.tool_call_follow_up_failed)
        }
    }

    /** 为本轮消息生成语义嵌入（fire-and-forget；嵌入器不可用时静默跳过）。regenerate 时 userMessage 为 null。 */
    private fun embedTurnInBackground(userMessage: MessageEntity?, assistantMessages: List<MessageEntity>) {
        scope.launch {
            runCatching {
                userMessage?.let { vectorMemory.embedMessageIfNeeded(it) }
                assistantMessages.forEach { vectorMemory.embedMessageIfNeeded(it) }
            }.onFailure { Log.w(TAG, "embed turn 失败(不影响主流程): ${it.message}") }
        }
    }

    private companion object {
        private const val TAG = "AssistantTurnEngine"

        /** 空响应最大流式尝试次数（对齐 iOS：首次空 → 自动重试 1 次 → 共 2 次）。 */
        const val MAX_STREAM_ATTEMPTS = 2
    }
}
