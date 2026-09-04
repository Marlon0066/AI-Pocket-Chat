package com.situ.aichat.ui.chat

import android.content.Context
import android.util.Log
import com.situ.aichat.R
import com.situ.aichat.content.ContentFilterService
import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MeetingCandidate
import com.situ.aichat.data.model.MoodHistoryEntry
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.StickerRepository
import com.situ.aichat.notification.NotificationPayload
import com.situ.aichat.notification.Notifier
import com.situ.aichat.offline.OfflineMeetingAction
import com.situ.aichat.offline.OfflineMeetingActionType
import com.situ.aichat.offline.OfflineMeetingService
import com.situ.aichat.prompt.AssistantOutputGate
import com.situ.aichat.prompt.CalendarItemParser
import com.situ.aichat.prompt.MessageKindInference
import com.situ.aichat.prompt.MessageSplitter
import com.situ.aichat.prompt.ReplyParser
import com.situ.aichat.sticker.StickerService
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.tts.TtsProviderType
import com.situ.aichat.tts.TtsService
import com.situ.aichat.tts.VoiceResponseChunker
import com.situ.aichat.tts.provider.MiniMaxCatalog
import com.situ.aichat.util.AudioStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * 一次投递的结果（P12.6 D5）：实际落库的助手文字消息 + 本次是否投递了结构化动作（日历应用 / 线下卡片，
 * 含 tool-call 与文本标记两来源）。供空响应判定区分「清洗后真为空」与「卡片即完整回复（正文空但已投递卡）」。
 */
internal data class DeliveredTurn(
    val messages: List<MessageEntity>,
    val deliveredStructuredAction: Boolean,
    /** 文本暗号路解析出的未来约定候选（快路·8d-3b）；编排层回复后经 trigger.ingestFastPath 入库。标记已从正文剥离。 */
    val meetingMarkerCandidates: List<MeetingCandidate> = emptyList(),
) {
    companion object {
        val EMPTY = DeliveredTurn(emptyList(), false)
    }
}

/**
 * 聊天 AI 回复的「投递层」（刀7·从 ChatViewModel 抽出·只搬不改）：把流式拿到的整段回复清洗 → 分发结构化卡 →
 * 按语音/文字分条、带打字延迟逐条落库。编排层 [AssistantTurnEngine]（暂留 VM 的 runAssistantTurn）调
 * [deliverAssistantReply] 并经 [openTypingSlot]/[closeTypingSlot] 与本层协作；打字/递送/错误等 UI 态由 VM
 * 持有并注入（公开 API 不变）。取消语义（健康线 2-5·停止钮退役后统一）：任何取消一律丢弃未递送内容——
 * 已插段=定局保留（输入排契约 §3.2-2），未出现内容不落库；零段已插=保未答态由恢复系统补整条。
 */
internal class ChatReplyDeliverer(
    private val appContext: Context,
    private val conversationUuid: String,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val stickerRepo: StickerRepository,
    private val ttsService: TtsService,
    private val offlineMeetingService: OfflineMeetingService,
    private val calendarHandler: ChatCalendarActionHandler,
    private val errorFlow: MutableStateFlow<String?>,
    private val isDelivering: MutableStateFlow<Boolean>,
    private val pendingAssistantSlot: MutableStateFlow<TypingSlot?>,
    private val isViewVisible: StateFlow<Boolean>,
) {
    // 打字占位槽预留 uuid（契约 FABLE5_CHAT_BUBBLE_REFACTOR B1）：下一段 insert 取用，消费一次即清，防同组多插重复主键。
    private var reservedSlotUuid: String? = null

    /** 正在递送的回合 Job（=置 [isDelivering] 的那个协程）。VM 的打断只认它——身份校验防误杀排队后继回合。 */
    internal var deliveringJob: Job? = null

    // 本回合已产出过用户可见内容（消息落库 或 结构化卡分发）的 Job——供 Controller 区分「起步相位」（未产出=可打断）与「收尾相位」（已产出=不打断）：V1 后两者的 typingSlot/isDelivering 输入完全相同，唯一区别就是有没有产出过。
    // 用 Job 引用而非布尔=天然带身份校验，排队的后继回合不会因前一回合的产出被误判成「已产出」。**绝不清空**（它记的是「产出过没有」不是「正在递送」——后者是 deliveringJob，照旧 finally 清 null；加清空 = 起步/收尾又不可区分）。
    internal var lastOutputJob: Job? = null

    /** 打字点亮起：分配下一段 AI 消息的 uuid 并发布为渲染占位槽。 */
    internal fun openTypingSlot() {
        val uuid = UUID.randomUUID().toString()
        reservedSlotUuid = uuid
        pendingAssistantSlot.value = TypingSlot(uuid)
    }

    /** 打字点熄灭 + 清预留（幂等）：递送层末段落地即调一次，Engine 回合 finally 再兜一次（终态/空响应/取消统一清理）。 */
    internal fun closeTypingSlot() {
        reservedSlotUuid = null
        pendingAssistantSlot.value = null
    }

    /** 取用预留 slot uuid 给将落库的首段（落地后 builder 以同 key dedup 成正文=变身）；无预留则随机（卡片/同组后续段不参与变身）。 */
    private fun consumeReservedSlotUuid(): String =
        reservedSlotUuid?.also { reservedSlotUuid = null } ?: UUID.randomUUID().toString()

    /** 一条语音 chunk 合成校验结果（对齐 iOS VoiceChunkSynthesisResult）。 */
    private sealed interface VoiceChunkResult {
        data class Audio(val originalText: String, val path: String, val durationSec: Double) : VoiceChunkResult
        data class NeedsSplit(val chunks: List<String>) : VoiceChunkResult
        data object Failed : VoiceChunkResult
    }

    /**
     * 流结束后的清洗 + 分发 + 投递（1:1 iOS processAndDeliverFullResponse）：汇合结构化/文本双路（preprocess）→
     * 应用日历动作 + 分发线下邀约/结束卡 → extractPetSpeech → parseMood → sanitize → 表情包归一 →
     * 据 [voicePlan]/线下状态决定 语音 / 文字 / 线下单段 投递。
     * [immediate]=true（取消兜底）恒走文字、不动语音计数、不分发动作；[voicePlan]=null 同（取消路径）。
     * @return 投递出的**文字消息**（供嵌入）；纯卡片/空文本回合返回空列表（卡片已落库）。
     */
    internal suspend fun deliverAssistantReply(
        raw: String,
        character: CharacterEntity,
        settings: AppSettings,
        dotsAppearMillis: Long,
        immediate: Boolean,
        voicePlan: VoicePlan?,
        toolCalendarActions: List<CalendarAction> = emptyList(),
        toolOfflineActions: List<OfflineMeetingAction> = emptyList(),
        hasOfflineMeetingToolCall: Boolean = false,
    ): DeliveredTurn {
        // 汇合双路（结构化优先否则文本兜底）+ 常驻清理标签（防泄漏成气泡）。
        val allowSuggestions = settings.characterCanInitiateOfflineMeeting
        val pre = AssistantResponsePreprocessor.preprocess(raw, toolCalendarActions, hasOfflineMeetingToolCall, allowSuggestions)
        val mergedOfflineActions = AssistantResponsePreprocessor.deduplicateOfflineActions(toolOfflineActions + pre.offlineActions)
        val hasOfflineMeetingAction = pre.hasOfflineMeetingAction || toolOfflineActions.isNotEmpty()
        // 本次是否投递了结构化动作（日历应用 / 线下卡片，含 tool-call 与文本标记两来源）——供 D5 空响应判定：
        // 卡片即完整回复，即使正文空也不算空响应、不重试（仅 !immediate 真正分发时才算；immediate=取消兜底不分发动作）。
        val deliveredStructuredAction = !immediate && (pre.calendarActions.isNotEmpty() || mergedOfflineActions.isNotEmpty())
        // 快路未来约定候选：取消兜底(immediate)不从半截回复入库；标记无论如何已在 preprocess 剥离（防泄露）。
        val meetingMarkerCandidates = if (immediate) emptyList() else pre.futureMeetingCandidates

        // 应用日历动作 + 分发线下卡（1:1 iOS 在投递前、即使正文为空也执行；取消兜底 immediate=true 不写入）。
        // 卡片 emotionTag 取 null —— iOS 分发先于 parseMood，pendingEmotionTag 此刻恒为 nil（每轮末重置）。
        if (!immediate) {
            calendarHandler.applyParsedCalendarActions(pre.calendarActions, settings)
            for (action in mergedOfflineActions) {
                when (action.action) {
                    OfflineMeetingActionType.SUGGEST_MEETING -> offlineMeetingService.handleSuggestMeeting(conversationUuid, action, emotionTag = null)
                    OfflineMeetingActionType.END_MEETING -> {
                        // 批2 2-9：非线下会话幻觉 [offline_end] 直接丢弃（标记已在 preprocess 剥离，不会漏进气泡）。
                        // 无守卫时会插一张 sessionId=null、任何界面都看不到的孤儿结束卡，但列表预览被改写成
                        // 「[线下结束]」——列表与聊天内容对不上的幽灵状态。
                        if (conversationRepo.get(conversationUuid)?.isInOfflineMode == true) {
                            offlineMeetingService.handleEndMeeting(conversationUuid, action, emotionTag = null)
                        } else {
                            Log.w(TAG, "非线下会话收到幻觉 offline_end，已丢弃 conv=$conversationUuid")
                        }
                    }
                }
            }
            if (pre.calendarActions.isNotEmpty() || mergedOfflineActions.isNotEmpty()) lastOutputJob = coroutineContext[Job] // J8 第 3 记账点：卡片即回复，正文空也算产出
        }

        // 工具调用来源的 suggestMeeting：邀约卡即完整回复，丢弃 LLM 附带正文（文本标记路径不抑制——剩余文字是角色对话）。
        val hasSuggestFromToolCall = toolOfflineActions.any { it.action == OfflineMeetingActionType.SUGGEST_MEETING }
        val effectiveRaw = if (hasSuggestFromToolCall) "" else pre.responseAfterOffline
        if (effectiveRaw.isBlank()) return DeliveredTurn(emptyList(), deliveredStructuredAction, meetingMarkerCandidates) // 卡片/日历已处理，无正文可投递

        // 线下状态：决定语音冻结 / 标签保留 / 单段投递 / 消息 isOfflineMode 标记。
        val convo = conversationRepo.get(conversationUuid)
        val isOffline = convo?.isInOfflineMode == true
        val offlineSessionId = if (isOffline) convo.currentOfflineSessionId else null

        val (petCleaned, _) = ReplyParser.extractPetSpeech(effectiveRaw) // 宠物发言提取（M11 接入后使用）
        // commit 7 前门：MiniMax speech-2.8 语音路径保留语气标签喂 TTS；其余路径继续无条件剥（对齐 iOS）。
        // 线下模式保留叙事标签（[叙述][对话]…）供沉浸渲染解析（preserveOfflineTags=isOffline，1:1 iOS）。
        val preserveVoiceTags = voicePlan?.capability?.shouldPreserveVoiceTagsWhenCleaning == true
        val mood = ReplyParser.parseMood(petCleaned, preserveOfflineTags = isOffline, preserveMiniMaxVoiceTags = preserveVoiceTags)
        val sanitized = ReplyParser.sanitizeAssistantResponse(
            mood.cleanText, characterName = character.name, preserveOfflineTags = isOffline, preserveMiniMaxVoiceTags = preserveVoiceTags,
        )
        // 14.3c 用户自定义内容过滤（预设规则 + 自定义正则）：在 sanitize 之后、表情包归一/分条之前净化 AI 正文
        // （1:1 iOS ChatViewModel+PostProcess 调用位置；取消兜底也经本路径，覆盖 iOS 的两处 applyFilters）。
        val filtered = ContentFilterService.applyFilters(
            sanitized, ContentFilterService.loadRules(settings.contentFilterRulesJSON),
        )
        // M17 表情包归一（第三道防线，1:1 iOS ChatViewModel+PostProcess:167-181）：开关开 → 别名转 UUID
        // 再剥无效标签（与历史渲染同一全集校验）；开关关 → 全剥。MessageSplitter 不拆 [sticker:] 段。
        val customStickers = stickerRepo.getAllForPrompt()
        val stickerNormalized = StickerService.normalizeAssistantStickerTags(
            filtered, customStickers, settings.characterCanSendStickersEnabled,
        )

        // M04 mood 持久化（解析到情绪时）：conversation.mood* + character.lastMood*；emotionTag 附到每条消息。
        // 复核修（iOS 对照）：取消兜底（immediate）不记 mood、不附 emotionTag——截断文本解析的情绪不可信
        // （iOS PostProcess 取消分支根本不 parseMood）。
        val emotionTag = if (immediate) null else mood.emoji.ifEmpty { null }
        if (!immediate && (mood.emoji.isNotEmpty() || mood.text.isNotEmpty())) {
            conversationRepo.recordMood(conversationUuid, mood.emoji, mood.text, mood.colorName)
            // P12.6 D1b：列级写回心情三列（分析/计数器不写这三列），不再整行 upsert——本回合 LLM/投递耗时内后台
            // 成长/关系/结构化分析的列级写不会被这条用旧快照的整行 upsert 覆盖回旧值（D1 丢更新的核心场景之一）。
            characterRepo.updateMood(character.uuid, mood.emoji, mood.text, mood.colorName)
            // 情绪历史归档（成长系统）：与 lastMood 同源同点写；轻锁内读-改-写，复活情绪低落送礼加成 / 主动暖心送礼 /
            // 善解人意印象标签（消费方读角色级 moodHistory，此前全工程无写入方恒空）。timestamp 必填此刻（默认 0L 会让 24h 窗口落空）。
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

        // 语音/文字决策 + 轮次计数更新（对齐 iOS resolveAssistantDelivery）：线下模式或含线下动作 → 强制文字 + 冻结计数。
        val plannedVoice = voicePlan?.plan?.isVoice == true && !immediate
        if (voicePlan != null) {
            val outcome = resolveAssistantDelivery(
                isOffline = isOffline,
                hasOfflineMeetingAction = hasOfflineMeetingAction,
                plannedVoice = plannedVoice,
                voiceRoundsSinceLastVoice = voicePlan.roundsSinceLastVoice,
            )
            if (outcome.shouldBeVoice) {
                conversationRepo.updateVoiceRounds(conversationUuid, 0, nextVoiceReplyThreshold(settings))
                val stored = deliverVoiceReply(stickerNormalized, character, voicePlan, emotionTag, mood.emoji, immediate, dotsAppearMillis)
                if (stored.isNotEmpty()) {
                    finalizeDelivery(stored)
                    notifyIfNotViewing(character, settings, stored, immediate, isOfflineConversation = isOffline)
                    return DeliveredTurn(stored, deliveredStructuredAction, meetingMarkerCandidates)
                }
                // 语音 chunk 全空/全失败 → 回落文字投递（计数已按语音重置，罕见兜底）。
            } else {
                conversationRepo.updateVoiceRounds(conversationUuid, outcome.nextVoiceRoundsSinceLastVoice, voicePlan.threshold)
            }
        }

        val stored = deliverTextReply(stickerNormalized, settings, emotionTag, immediate, dotsAppearMillis, offlineSessionId)
        if (stored.isEmpty()) return DeliveredTurn(emptyList(), deliveredStructuredAction, meetingMarkerCandidates)
        finalizeDelivery(stored)
        notifyIfNotViewing(character, settings, stored, immediate, isOfflineConversation = isOffline)
        return DeliveredTurn(stored, deliveredStructuredAction, meetingMarkerCandidates)
    }

    /**
     * 文字分条投递：分句 → 剥语气标签 → 逐条打字延迟入库（1:1 iOS deliverSegments）。
     * [offlineSessionId] 非空 = 线下模式：**不分句**（整条叙事单段，保留 offline 标签供沉浸渲染）+ 消息标 isOfflineMode
     * + offlineSessionId（从正常聊天过滤、进沉浸视图，1:1 iOS insertMessage 的线下标记）。
     */
    private suspend fun deliverTextReply(
        stickerNormalized: String,
        settings: AppSettings,
        emotionTag: String?,
        immediate: Boolean,
        dotsAppearMillis: Long,
        offlineSessionId: String?,
    ): List<MessageEntity> {
        // 后门兜底：对每个 segment 无条件再剥一次 MiniMax 语气标签（对齐 iOS）。
        // 复核修（iOS 对照）：immediate=取消兜底不分段——整条合并单泡落库（=iOS PostProcess 取消分支单次
        // insertMessage；原先重分段会一次性掉出多个泡）。
        val split = if (offlineSessionId != null || immediate) {
            listOfNotNull(ReplyParser.stripMiniMaxVoiceTags(stickerNormalized).trim().takeIf { it.isNotEmpty() })
        } else {
            val range = settings.sanitizedReplySegmentRange
            MessageSplitter.split(stickerNormalized, maxSegments = range.last, minSegments = range.first)
                .map { ReplyParser.stripMiniMaxVoiceTags(it).trim() }
                .filter { it.isNotEmpty() }
        }
        // 落库前置闸（图纸 2026-09-01 件①）：判脏的段直接丢弃不落库；kind 与下方 insertSegment 同口径。
        // 全段皆脏 → 返回空表 = 空回合，接 AssistantTurnEngine 既有重试链（零新循环）。
        val segments = AssistantOutputGate.filterSegments(split, isOfflineMode = offlineSessionId != null, source = "chat")
        if (segments.isEmpty()) return emptyList()

        val stored = mutableListOf<MessageEntity>()
        var lastTs = 0L
        // 局部插入器（取消收尾路径复用同一构造，避免两份 MessageEntity 拼装逻辑）。
        suspend fun insertSegment(seg: String): MessageEntity {
            val ts = maxOf(System.currentTimeMillis(), lastTs + 1) // 严格递增，保证顺序
            lastTs = ts
            // P5.3b：含 [#E1]/[#R1] 的段 = AI 照抄的日历卡片行 → 标 SCHEDULE_CARD（线下叙事不识别）。
            // 单源推断：前台与忙碌/恢复/通知三后台路共用 MessageKindInference，口径一致。
            val kindRaw = MessageKindInference.forAssistantText(seg, isOfflineMode = offlineSessionId != null).raw
            val message = MessageEntity(
                messageUUID = consumeReservedSlotUuid(), // B1：首段取用打字占位槽预留的 uuid → 落地后同 key 原地变身
                conversationUuid = conversationUuid,
                roleRaw = "assistant",
                content = seg,
                timestamp = ts,
                emotionTag = emotionTag,
                isOfflineMode = offlineSessionId != null,
                offlineSessionId = offlineSessionId,
                messageKindRaw = kindRaw,
            )
            // 复核修（对抗复核 MED）：落库+记账对取消原子——Room suspend 写可能「已提交但 resume 抛
            // CancellationException」，stored 少记一条 → 取消收尾把已提交段再插一遍（段重复残窗）。
            withContext(NonCancellable) {
                messageRepo.upsert(message)
                stored.add(message)
            }
            return message
        }

        if (!immediate) {
            isDelivering.value = true
            deliveringJob = coroutineContext[Job] // 复核修：登记递送中的回合身份，打断只认它。
        }
        try {
            for ((index, seg) in segments.withIndex()) {
                // P1-4 per-segment 可见性（=iOS deliverSegments 每段读 isViewVisible）：不可见跳过打字延迟，
                // 段照常逐条插入；回到可见自动恢复节奏。immediate（取消兜底/后台一次性落库）恒跳过。
                if (!immediate && isViewVisible.value) delay(segmentDelayMillis(index, seg.length, dotsAppearMillis))
                insertSegment(seg)
                // chat-logic-2 / D6：非末段在气泡落地后停 2-3s「阅读停顿」并隐藏→重显打字点（对齐 iOS
                // revealOrInsertSegment 停点 → sleep interBubblePause → createTypingPlaceholder）。immediate=后台/取消
                // 一次性落库跳过；P1-4：不可见同样跳过（iOS 不在看时无段间停顿与 typing 占位）。
                if (!immediate && index < segments.lastIndex && isViewVisible.value) {
                    delay(interBubblePauseMillis(seg))
                    openTypingSlot() // B1：阅读停顿后重显打字点 = 预分配下一段 uuid + 发布占位槽
                }
            }
        } catch (e: CancellationException) {
            // 健康线 2-5（停止钮退役后取消语义统一=丢弃）：任何取消（用户打断/退出会话/线下收场）一律丢弃
            // 未递送段——已插段=定局保留（输入排契约 §3.2-2）、未出现内容不落库不进下一轮上下文；零段已插
            // =保持未答态、重进由 autoRecover 补整条（旧「余量合并落库」的烂尾句语义随停止钮退役废止）。
            // 已插段非空时仍须翻转会话预览（finalizeDelivery）——否则 last 停在 user、恢复系统把已答会话再答一轮。
            if (!immediate && stored.isNotEmpty()) {
                withContext(NonCancellable) { finalizeDelivery(stored) }
            }
            throw e
        } finally {
            if (!immediate) {
                isDelivering.value = false
                deliveringJob = null
                // 末段落地即清打字槽 + 记「本回合已产出」（J8 第 1 记账点）。清槽的意义：否则收尾维护期（embed/记忆/成长）槽仍非空 → 判据 typing 恒真 → 用户此刻发消息会打断维护相位（契约 §3.2-3 未列该相位）；零视觉变化（末段 uuid 已在 messages 里，渲染层 dedup 本就不画占位）。stored 空 = 没递送任何内容 → 槽留给 Engine 回合 finally 兜底（空响应重试期三点须继续显示）、也不算产出。
                if (stored.isNotEmpty()) { closeTypingSlot(); lastOutputJob = coroutineContext[Job] }
            }
        }
        return stored
    }

    /**
     * 语音分条投递（对齐 iOS deliverVoiceChunks）：VoiceResponseChunker 分卷 → 逐条合成存音频 → 落
     * isVoiceMessage 消息（content=原文 chunk，带 emotionTag）。>60s 的合成结果按 46/54 再分卷重投。
     */
    private suspend fun deliverVoiceReply(
        stickerNormalized: String,
        character: CharacterEntity,
        voicePlan: VoicePlan,
        emotionTag: String?,
        moodEmoji: String,
        immediate: Boolean,
        dotsAppearMillis: Long,
    ): List<MessageEntity> {
        // 落库前置闸（图纸件①）：语音路各 chunk 落库 kind 恒 PLAIN_TEXT，同口径判脏后丢弃。
        val queue = ArrayDeque(
            AssistantOutputGate.filterPlainChunks(VoiceResponseChunker.chunkForVoice(stickerNormalized), source = "chatVoice"),
        )
        if (queue.isEmpty()) return emptyList()

        val stored = mutableListOf<MessageEntity>()
        var lastTs = 0L
        var index = 0
        var toastShown = false
        // P1-2/4：当前正处理（已出队未落库）的 chunk——取消收尾时与队列剩余一起回退为文字（iOS insertMessage remaining）。
        var inFlightChunk: String? = null

        // 审计 S8：语音路落库三式（Audio / 合成失败回退文字 / 取消余量合并）同源——单调时间戳（maxOf(now, lastTs+1)）+
        // 预留占位 uuid（B1 同 key 变身）+ 落库+记账对取消原子（复核修：防已提交条被取消收尾重投）。照文字路 insertSegment 手法。
        suspend fun insertVoiceRound(content: String, audioPath: String? = null, audioDurationSec: Double = 0.0) {
            val ts = maxOf(System.currentTimeMillis(), lastTs + 1)
            lastTs = ts
            val message = MessageEntity(
                messageUUID = consumeReservedSlotUuid(),
                conversationUuid = conversationUuid,
                roleRaw = "assistant",
                content = content,
                timestamp = ts,
                emotionTag = emotionTag,
                isVoiceMessage = audioPath != null,
                audioRelativePath = audioPath,
                audioDuration = audioDurationSec,
            )
            withContext(NonCancellable) {
                messageRepo.upsert(message)
                stored.add(message)
            }
        }
        if (!immediate) {
            isDelivering.value = true
            deliveringJob = coroutineContext[Job] // 复核修：登记递送中的回合身份，打断只认它。
        }
        try {
            while (queue.isNotEmpty()) {
                val chunk = queue.removeFirst()
                inFlightChunk = chunk
                // chat-logic-3：语音分条用专属延迟（1:1 iOS deliverVoiceChunks），与文字版 segmentDelayMillis 常量不同。
                // P1-4：不可见跳过延迟（=iOS deliverSegments isViewVisible gating，语音条同样适用）。
                if (!immediate && isViewVisible.value) delay(voiceChunkDelayMillis(index, chunk.length, dotsAppearMillis))
                var storedThisRound = false // chat-logic-2：本轮是否真落了一条气泡（NeedsSplit 不算）
                when (val result = synthesizeVoiceChunk(chunk, character, voicePlan, moodEmoji)) {
                    is VoiceChunkResult.Audio -> {
                        insertVoiceRound(result.originalText, result.path, result.durationSec)
                        index++
                        storedThisRound = true
                    }
                    is VoiceChunkResult.NeedsSplit -> {
                        for (sub in result.chunks.asReversed()) queue.addFirst(sub)
                    }
                    VoiceChunkResult.Failed -> {
                        // 合成失败 → 该 chunk 退回文字消息（剥语气标签，对齐 iOS .failed 分支）。
                        val text = ReplyParser.stripMiniMaxVoiceTags(chunk).trim().ifEmpty { chunk.trim() }
                        if (text.isNotEmpty()) {
                            insertVoiceRound(text)
                            index++
                            storedThisRound = true
                        }
                        if (!toastShown) {
                            errorFlow.value = appContext.getString(R.string.tts_failed)
                            toastShown = true
                        }
                    }
                }
                inFlightChunk = null // 该 chunk 已处理完（落库/拆分/失败回退），取消收尾不再回退它。
                // chat-logic-2 / D6：非末条（队列仍有）且本轮真落了气泡时停 2-3s + 隐藏→重显打字点；
                // NeedsSplit 当轮不显气泡故不停（对齐 iOS deliverVoiceChunks）。immediate 跳过；P1-4 不可见跳过。
                if (!immediate && queue.isNotEmpty() && storedThisRound && isViewVisible.value) {
                    delay(interBubblePauseMillis(chunk))
                    openTypingSlot() // B1：阅读停顿后重显打字点 = 预分配下一条语音 uuid + 发布占位槽
                }
            }
        } catch (e: CancellationException) {
            // 健康线 2-5（同 deliverTextReply 取消语义）：任何取消一律丢弃在飞 chunk 与队列剩余——已落语音条
            // =定局保留，未出现内容不落库；已插条非空时翻转会话预览防恢复系统对已答会话再答一轮。
            if (!immediate && stored.isNotEmpty()) {
                withContext(NonCancellable) { finalizeDelivery(stored) }
            }
            throw e
        } finally {
            if (!immediate) {
                isDelivering.value = false
                deliveringJob = null
                // 同 deliverTextReply：末条清槽 + 记产出（J8 第 2 记账点）。**stored 非空条件绝不可去**（J2/E4）：语音 chunk 全失败时本函数返回空表回落文字路，无条件清槽会把预留 uuid 一并清掉 → 回落首段拿随机 uuid = 同 key 变身失效（契约 B1 根因）。
                if (stored.isNotEmpty()) { closeTypingSlot(); lastOutputJob = coroutineContext[Job] }
            }
        }
        return stored
    }

    /**
     * 合成并校验一条语音 chunk（对齐 iOS synthesizeValidatedVoiceChunk）：按 provider/model 选保留或剥
     * 语气标签的清洗 → 合成 → 存音频 + 探时长；>60s 且可再分卷则丢弃重投（NeedsSplit），否则 Audio。
     */
    private suspend fun synthesizeVoiceChunk(
        chunk: String,
        character: CharacterEntity,
        voicePlan: VoicePlan,
        moodEmoji: String,
    ): VoiceChunkResult {
        val config = voicePlan.config
        val isMiniMax28 = config?.providerType == TtsProviderType.MINIMAX &&
            MiniMaxCatalog.capability(config.modelName).supportsInterpolationTags
        val forVoice = sanitizeForVoice(chunk, character.name, preserveVoiceTags = isMiniMax28)
        if (forVoice.isBlank()) return VoiceChunkResult.Failed

        val audio = ttsService.synthesize(forVoice, voicePlan.profile, config, voicePlan.apiKey, moodEmoji)
            ?: run {
                Log.w(TAG, "语音分段失败·合成返回空 provider=${config?.providerType?.raw ?: "system"}")
                return VoiceChunkResult.Failed
            }
        val ext = if (config?.providerType == TtsProviderType.SYSTEM) "wav" else (config?.responseFormat?.raw ?: "mp3")
        val path = AudioStore.saveBytes(appContext, audio, ext) ?: run {
            Log.w(TAG, "语音分段失败·落盘失败 provider=${config?.providerType?.raw ?: "system"}")
            return VoiceChunkResult.Failed
        }
        val durationSec = AudioStore.durationSeconds(path) ?: VoiceResponseChunker.estimatedSpeechDuration(forVoice)

        if (durationSec <= 60.0) return VoiceChunkResult.Audio(originalText = chunk, path = path, durationSec = durationSec)
        // 实测超 60s（估算与真实不符）：按 46/54 再分卷，>1 段则丢弃本次合成重投（对齐 iOS）。
        val fallback = VoiceResponseChunker.chunkForVoice(forVoice, preferredMaxDuration = 46.0, hardMaxDuration = 54.0)
        return if (fallback.size > 1) {
            AudioStore.delete(path)
            VoiceChunkResult.NeedsSplit(fallback)
        } else {
            VoiceChunkResult.Audio(originalText = chunk, path = path, durationSec = durationSec)
        }
    }

    /** 送 TTS 前的清洗（对齐 iOS sanitizeAssistantResponseForVoice / …ForMiniMaxVoice）：剥表情包 + 日历 ref，
     *  再 sanitize（MiniMax 2.8 保留语气标签喂服务端，否则剥）。 */
    private fun sanitizeForVoice(content: String, characterName: String?, preserveVoiceTags: Boolean): String {
        val stripped = CalendarItemParser.stripCalendarRefs(StickerTagParser.stripStickerTags(content))
        return ReplyParser.sanitizeAssistantResponse(
            stripped, characterName = characterName, preserveMiniMaxVoiceTags = preserveVoiceTags,
        )
    }

    /** 投递收尾：会话预览（文字/语音两路共用·**单调写**=打断瞬间用户新消息的更晚快照已落库时不用旧 ts 覆写回去）。日历应用已上移到 deliverAssistantReply。 */
    private suspend fun finalizeDelivery(stored: List<MessageEntity>) {
        val last = stored.last()
        // 见面期（线下）AI 叙事回合：正文带 [叙述]/[对话]/[场景:…] 沉浸标签（preserveOfflineTags 落库），绝不外显进
        // 会话列表预览（方案 A·见 OfflineChatVisibility）。判定取「这条消息标了线下」或「会话当前在线下」——后者兜底
        // 脏态（isInOfflineMode 但 sessionId 缺失 → 消息漏标 isOfflineMode 但正文仍保留了标签）。
        val isOffline = last.isOfflineMode || conversationRepo.get(conversationUuid)?.isInOfflineMode == true
        val preview = assistantDeliveryPreview(last.content, isOffline)
        if (preview == null) {
            // 线下：不写预览，仅刷新「最后活动时间」保鲜列表排序（同系统耳语 touchLastMessageDate）；预览文案保持
            // 入场标记「正在见面中…」直到 recordOfflineExited 收尾覆写。
            conversationRepo.touchLastMessageDate(conversationUuid, last.timestamp)
        } else {
            conversationRepo.recordLastMessageIfNewer(conversationUuid, preview, "assistant", last.timestamp)
        }
    }

    /**
     * 批2 2-11（用户拍板 2026-07-02）：用户此刻看不到这条回复（App 后台/锁屏/别的页面/别的会话——单旗标
     * [isViewVisible] 全覆盖）时，回复落库即发本地通知，像真人发消息。复用 [Notifier.post] 全套现成能力
     * （MessagingStyle 头像气泡 + 分组防轰炸 + 权限检查 + try/catch）。deliveryIdentifier=null=仅提醒不物化
     * （正文已在库里）；同会话同 id=后一条覆盖前一条不堆叠；immediate=取消兜底不打扰；尊重通知总开关。
     * 线下沉浸叙事不通知（正文是带标签的叙事体，预览会漏标签；线下本就是沉浸场景）——判定双源
     * （消息标记 || [isOfflineConversation] 会话旗标），与 [finalizeDelivery] 的预览侧同口径（卷一 C3）。
     */
    private fun notifyIfNotViewing(
        character: CharacterEntity,
        settings: AppSettings,
        stored: List<MessageEntity>,
        immediate: Boolean,
        isOfflineConversation: Boolean,
    ) {
        if (immediate) return
        if (isViewVisible.value) return
        if (!settings.notificationsEnabled) return
        val last = stored.last()
        // 双源判定（卷一 C3）：本条标了线下 **或** 会话当前在见面中——后者兜底脏态（isInOfflineMode 而
        // sessionId 缺失 → 消息漏标 isOfflineMode，但正文按 preserveOfflineTags 保留了叙事标签，
        // 原先会把 [叙述]/[对话] 原样弹进通知栏）。本函数非 suspend 不能查库，故由 suspend 调用侧传入。
        if (last.isOfflineMode || isOfflineConversation) return
        val body = MessagePreviewText.forMessage(last).take(100)
        if (body.isBlank()) return
        // 通知是尽力而为：任何失败（权限/渠道/系统异常）绝不反噬投递主链路（消息已落库）。
        runCatching {
            // 2-5b 收敛（用户拍板 2026-07-03「App 内红点就够」）：App 前台（在列表/别的页面/别的会话）
            // 不弹系统通知——列表未读红点即提示；仅 App 真在后台/锁屏时弹（原 2-11 批准语义本义）。
            // ProcessLifecycleOwner 在纯 JVM 单测缺席 → 置于 runCatching 内一并兜底（等效不弹）。
            val appForeground = androidx.lifecycle.ProcessLifecycleOwner.get()
                .lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            if (appForeground) return@runCatching
            Notifier.post(
                appContext,
                NotificationPayload(
                    notificationId = "chat_reply_$conversationUuid".hashCode(),
                    title = character.name,
                    body = body,
                    conversationUuid = conversationUuid,
                    characterId = character.uuid,
                    avatarPath = character.avatarPath,
                ),
            )
        }.onFailure { Log.w(TAG, "后台回复通知投递失败: ${it.message}") }
    }

    /** 分条打字延迟（毫秒，对齐 iOS deliverSegments：首条扣除已流式耗时，后续按长度，均加长度奖励）。 */
    private fun segmentDelayMillis(index: Int, segmentLen: Int, dotsAppearMillis: Long): Long {
        val lengthBonus = minOf(0.4, segmentLen * 0.015)
        val seconds = if (index == 0) {
            val minTyping = Random.nextDouble(2.0, 3.0)
            val elapsed = (System.currentTimeMillis() - dotsAppearMillis) / 1000.0
            maxOf(0.3, minTyping - elapsed) + lengthBonus
        } else {
            val scale = minOf(1.0, segmentLen / 30.0)
            val minDelay = 0.6 + scale * 0.9
            Random.nextDouble(minDelay, minDelay + 1.0) + lengthBonus
        }
        return (seconds * 1000).toLong()
    }

    /**
     * chat-logic-3：语音分条投递的「下一条出现前」延迟（1:1 iOS `deliverVoiceChunks` +TTS.swift:158-167，
     * 与文字版 [segmentDelayMillis] 常量不同）——首条 = max(0.3, random(2~3s) − 已流式耗时) + min(0.5, len*0.01)；
     * 非首条 = random(0.9,1.5) + min(0.35, len*0.006)。长度加成项见 [voiceChunkLengthBonusSeconds]（纯函数·单测）。
     */
    private fun voiceChunkDelayMillis(index: Int, chunkLen: Int, dotsAppearMillis: Long): Long {
        val seconds = if (index == 0) {
            val minTyping = Random.nextDouble(2.0, 3.0)
            val elapsed = (System.currentTimeMillis() - dotsAppearMillis) / 1000.0
            maxOf(0.3, minTyping - elapsed) + voiceChunkLengthBonusSeconds(index, chunkLen)
        } else {
            Random.nextDouble(0.9, 1.5) + voiceChunkLengthBonusSeconds(index, chunkLen)
        }
        return (seconds * 1000).toLong()
    }

    private companion object {
        const val TAG = "ChatReplyDeliverer"
    }
}
