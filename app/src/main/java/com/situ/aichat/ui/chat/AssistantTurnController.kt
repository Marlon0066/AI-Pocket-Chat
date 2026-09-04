package com.situ.aichat.ui.chat

import android.content.Context
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.chat.image.ImageMemorySummaryService
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.NotificationLearningService
import com.situ.aichat.offline.OfflineMeetingService
import com.situ.aichat.offline.OfflineReturnPolicy
import com.situ.aichat.offline.outgoingOfflineSessionId
import com.situ.aichat.prompt.PromptBuilder
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.recovery.RecoveryClaimTracker
import com.situ.aichat.sticker.StickerRecentStore
import com.situ.aichat.util.StreakManager
import com.situ.aichat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 助手回合生命周期 + 用户发送入口控制器（ChatViewModel 刀10 抽出；输入排契约 C1 改造发送编排——
 * FABLE5_CHAT_INPUT_BAR_PROPOSAL §3.2「合并等待窗 + 三点态打断丢弃」）。
 *
 * 发送模型（契约 §3.2·「跟真人聊天一样」）：发文字 [send] / 发表情 [sendStickerMessage] / 发语音草稿
 * [sendVoiceDraft] 一律**受理即落库上屏**（+逐条嵌入/火花/通知/忙碌收纳 [acceptStoredUserMessage]），AI 回合
 * 押后进 [dispatcher] 合并等待窗（连发重置计时·停手才答）；AI 话没说完（三点态）再发 = [interruptUndisplayedReplyIfAny]
 * 打断并丢弃未出现内容。窗到期 [launchWindowTurn] join 旧回合后起常规回合，一次读全历史答完窗内全部消息。
 * 另有：重新生成 [regenerate]（立即·不入窗）、串行化包装 [launchSerializedTurn]、
 * 当前会话回合 [runAssistantTurnForCurrentConversation]、断网重试 [maybeAutoRetryAfterReconnect]、
 * 未答恢复 [autoRecoverUnansweredMessage]（后两者窗内让位防双答）、礼物外部入口 [enqueueExternalTurn]。
 *
 * ChatViewModel 持本控制器并保留薄委托（公开 API 与调用点不变）。并发把手 [assistantTurnJob] 在
 * [scope]（健康线 2-5b 起 = 应用级 ChatTurnScope·Main.immediate——退出会话回合后台继续，回复落库 →
 * 列表未读红点；重进会话的新 VM 经 RecoveryClaimTracker 与旧回合互斥）内同步赋值，串行/打断语义不变。
 */
internal class AssistantTurnController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val conversationUuid: String,
    private val db: AppDatabase,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val apiConfigRepo: ApiConfigRepository,
    private val settingsRepo: SettingsRepository,
    private val userProfileDao: UserProfileDao,
    private val notificationLearningService: NotificationLearningService,
    private val offlineMeetingService: OfflineMeetingService,
    private val recoveryClaimTracker: RecoveryClaimTracker,
    private val assistantTurnEngine: AssistantTurnEngine,
    private val replyDeliverer: ChatReplyDeliverer,
    private val voiceController: ChatVoiceController,
    private val vectorMemory: VectorMemoryService,
    private val imageMemorySummaryService: ImageMemorySummaryService,
    private val dispatcher: ChatMessageDispatcher,
    private val typingSlot: StateFlow<TypingSlot?>,
    private val conversationFlow: StateFlow<ConversationEntity?>,
    private val isSending: MutableStateFlow<Boolean>,
    private val errorFlow: MutableStateFlow<String?>,
    private val isDelivering: MutableStateFlow<Boolean>,
    private val replyTarget: MutableStateFlow<MessageEntity?>,
) {

    init {
        dispatcher.onReadyToSend = { launchWindowTurn() }
    }

    /** 发图落库链（图片多模态一期）：落盘 + 建消息 + 摘要触发；落库/受理仍复用本类既有实现。 */
    private val imageSender = ChatImageSender(
        appContext = appContext,
        conversationUuid = conversationUuid,
        conversationRepo = conversationRepo,
        characterRepo = characterRepo,
        imageMemorySummaryService = imageMemorySummaryService,
        errorFlow = errorFlow,
        storeUserMessage = ::storeUserMessage,
        acceptStoredUserMessage = ::acceptStoredUserMessage,
        embedImageMessage = { uuid -> messageRepo.get(uuid)?.let { vectorMemory.embedImageMessageAfterSummary(it) } },
    )

    /** 当前助手回合 job（发消息/重生成/线下触发）——线下结束时打断在投递/流式的回合（对齐 iOS streamingTask.cancel）。 */
    private var assistantTurnJob: Job? = null

    /** 未答恢复 job（进入聊天页 600ms 后静默补发未回复消息，1:1 iOS unansweredRecoveryTask）。 */
    private var unansweredRecoveryJob: Job? = null

    /** 取消当前活动回合并等待收尾（线下打断入口用·原 VM offlineController 回调 `{ assistantTurnJob?.cancelAndJoin() }`）。 */
    suspend fun cancelActiveTurn() {
        assistantTurnJob?.cancelAndJoin()
    }

    /**
     * ChatViewModel onCleared 收尾。健康线 2-5b（用户拍板 2026-07-03「IM 语义」）：回合系已迁应用级
     * [scope]——退出会话**不再取消**在跑回合，回复后台落库 → 列表未读红点。此处只处理等待窗：
     * dispatcher 计时器随 viewModelScope 死亡，窗仍武装（有已受理消息在等回合）→ **立即开火**
     * （合并等待「等用户继续打字」的意义随离开消失）；窗回合在应用级作用域照常跑完。
     * 判据用 [ChatMessageDispatcher.windowArmed] 而非 windowPending——viewModelScope 先于 onCleared
     * 死亡，Job 存活判据此刻恒假。
     */
    fun disposeOnCleared() {
        unansweredRecoveryJob?.cancel()
        val armed = dispatcher.windowArmed
        dispatcher.reset()
        if (armed) launchWindowTurn()
    }

    /**
     * 网络恢复后，若本会话最后一条是「未被回复的用户消息」（断网导致没回）→ 自动补一轮（P0-2 Part E·超越 iOS）。
     * 多重守卫防误发：未在发送中、无在跑的回合、非线下沉浸会话（其有独立恢复逻辑）、最后一条确为 user。
     * [runAssistantTurn] 顶部网络 guard 二次兜底；复用既有 [runAssistantTurnForCurrentConversation]、不新增发送路径。
     */
    internal fun maybeAutoRetryAfterReconnect() {
        if (conversationFlow.value?.currentOfflineSessionId != null) return
        if (dispatcher.windowPending) return // C1：等待窗内有在途新消息 → 窗回合统一作答，防双答
        // 复核 #10：必须走 launchSerializedTurn（同步置 isSending，堵住与用户 send() 的多秒竞态）+ recoveryClaimTracker
        // 占坑（与后台恢复扫描互斥，防同一条消息双答/双扣 LLM），与既有 autoRecover 路径同范式，不裸 launch。
        launchSerializedTurn {
            val last = messageRepo.recentChronological(conversationUuid, 1).lastOrNull() ?: return@launchSerializedTurn
            if (last.roleRaw != "user") return@launchSerializedTurn
            if (!recoveryClaimTracker.tryBegin(conversationUuid)) return@launchSerializedTurn
            try {
                android.util.Log.d("ChatVM", "网络恢复，自动重试未答用户消息")
                runAssistantTurnForCurrentConversation()
            } finally {
                recoveryClaimTracker.end(conversationUuid)
            }
        }
    }

    /**
     * 三点态打断（契约 §3.2-3「未出现的内容彻底丢弃」·取代原 interruptAssistantDeliveryIfNeeded）：
     * AI 话没说完——流式生成期（typing 槽亮）或分段递送期（[deliveringJob] 身份校验防误杀后继·对抗复核 MED 保留）
     * ——用户再发 → 取消回合。健康线 2-5 后取消语义全局统一=丢弃：递送层丢未递送段（已插段=定局保留）、
     * 引擎不再持久化流式半截 = 未出现内容不落库、不进下一轮上下文（旧 interruptDropRemaining 旗标随之退役）。
     * **起步相位**（窗到期 → 打字槽亮起）同样打断（V3·用户 2026-09-04 拍板）：那一段全是读操作、取消零损失，不取消则角色会拿着不含新消息的材料自说自话答完整轮。收尾维护相位（已产出过内容·三点未亮）不打断。
     */
    private fun interruptUndisplayedReplyIfAny() {
        val job = assistantTurnJob ?: return
        if (!job.isActive) return
        val delivering = isDelivering.value && job === replyDeliverer.deliveringJob
        val typing = typingSlot.value != null
        // 起步相位（窗到期 → 打字槽亮起）：三点未亮、未在递送、且本回合从未产出过内容。取消零损失——该相位只有读操作（读历史 / 附件预取 / 向量检索 / 组装提示词），未落库、未上屏、未调用模型（图纸 F19/F20/F21）。
        val starting = !typing && !delivering && job !== replyDeliverer.lastOutputJob
        if (!delivering && !typing && !starting) return
        job.cancel()
    }

    /**
     * 发送语音草稿（能力门控双路，1:1 iOS sendVoiceDraft + 安卓 Q2 加严）：
     * - 路由配置支持音频输入（effectiveAudioInputEnabled）→ 直发语音条（音频段在 runAssistantTurn 预读挂载），
     *   转写当参考、不阻塞（即使仍 pending 也用当前值/占位）；误报降级在流式失败时由 13.4b-1 retry 兜底。
     * - 否则走文字路：等 STT（最多 16s）；**失败/超时/占位 → Q2 阻止发送 + 保留草稿可重试**（有意比 iOS 严，
     *   iOS 是总发出 + 占位）。
     */
    fun sendVoiceDraft() {
        val draft = voiceController.voiceDraft.value ?: return
        // C1：三点态打断（丢未出现内容）后受理；不再拒发（合并等待窗随后统一作答）。
        interruptUndisplayedReplyIfAny()
        scope.launch {
            // config 前置保留：文字路发送门控依赖 STT 结果（audioInputEnabled 决定走哪条转写路），无 API 即报错留草稿。
            val config = apiConfigRepo.resolveConfigValues(ApiFunction.CHAT)
            if (config == null) {
                errorFlow.value = ERROR_NO_API_CONFIG
                return@launch
            }
            val convo = conversationRepo.get(conversationUuid) ?: return@launch

            val transcript: String = if (config.audioInputEnabled) {
                draft.transcript // 直发音频路：转写仅作参考，不阻塞
            } else {
                resolveTranscriptForSend(draft) ?: run {
                    // Q2：文字路 STT 失败/超时 → 不发，保留草稿，提示重试。
                    // P1-41：读 voiceController.voiceDraft 最新失败粒度（勿用闭包旧 draft——16s 等待窗内重试可能已改写）；
                    // UNAVAILABLE 给「改用文字」专属文案（重录/重试无意义），其余维持通用重试提示。
                    val failure = voiceController.voiceDraft.value?.takeIf { it.id == draft.id }?.transcriptFailure
                    errorFlow.value = appContext.getString(
                        if (failure == VoiceTranscriptFailure.UNAVAILABLE) R.string.voice_stt_unavailable else R.string.voice_stt_failed_retry,
                    )
                    return@launch
                }
            }

            // 通过门控 → 停试听、停转写、清草稿（委托 voiceController），落用户语音消息并受理入窗。
            voiceController.consumeDraftOnSend(draft)

            val now = System.currentTimeMillis()
            // 见面期间语音消息同样须随会话打线下标记（与文字/表情同源），否则漏进普通聊天 + 缺席沉浸剧场。
            val offlineSessionId = outgoingOfflineSessionId(convo.isInOfflineMode, convo.currentOfflineSessionId)
            val userMessage = MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "user",
                content = transcript,
                timestamp = now,
                isVoiceMessage = true,
                audioRelativePath = draft.audioPath,
                audioDuration = draft.durationSec,
                isOfflineMode = offlineSessionId != null,
                offlineSessionId = offlineSessionId,
            )
            // 列表预览「[语音] +前40字转写」（1:1 iOS lastMessagePreview = "[语音] " + text.prefix(40)；占位转写也照拼）。
            storeUserMessage(userMessage, preview = "[语音] " + transcript.take(40), inMeeting = offlineSessionId != null) // 2-4+3-1：NonCancellable+单事务

            acceptStoredUserMessage(userMessage, collectText = transcript)
        }
    }

    /** 文字路发送前等 STT 完成（最多 16s，poll 80ms）。成功转写→返回；仍 pending/空/占位→null（阻止发送）。 */
    private suspend fun resolveTranscriptForSend(draft: VoiceDraftState): String? {
        val deadline = System.currentTimeMillis() + STT_SEND_WAIT_MS
        while (voiceController.voiceDraft.value?.id == draft.id && voiceController.voiceDraft.value?.isTranscriptPending == true) {
            if (System.currentTimeMillis() >= deadline) break
            delay(STT_POLL_MS)
        }
        val cur = voiceController.voiceDraft.value?.takeIf { it.id == draft.id } ?: return null
        val text = cur.transcript.trim()
        return if (cur.isTranscriptPending || text.isEmpty() || text == PromptBuilder.VOICE_MESSAGE_PLACEHOLDER) null else text
    }

    /**
     * 用户消息落库 + 会话预览快照（健康线 2-4 + 3-1·文字/语音/表情三路共通）：
     * - **NonCancellable**（对齐 delete/regenerate 的 R1 打法）：发送后秒退聊天屏（VM 清理取消 scope）时，
     *   已受理的消息绝不半途丢失——用户看到「发出去了」就必须真的在库里。
     * - **单事务**（3-2 无头恢复同款·遵 CurrencyService 契约：事务内仅 suspend DAO 调用、不切调度器）：
     *   消息落库与列表预览翻转原子——中途死不再留下「消息在库、预览陈旧」的裂快照。
     * - **[inMeeting]=true（线下见面中·卷一 A1）**：不写预览，仅 `touchLastMessageDate`（与 AI 侧
     *   [assistantDeliveryPreview] 方案 A 同源）；三调用点按本条消息的线下打标结果传入（`offlineSessionId != null`）。
     */
    private suspend fun storeUserMessage(userMessage: MessageEntity, preview: String, inMeeting: Boolean) {
        withContext(NonCancellable) {
            db.withTransaction {
                messageRepo.upsert(userMessage)
                if (inMeeting) {
                    // 见面中（卷一 A1）：用户这句同样属「见面期间产生的消息」，绝不外显进日常聊天列表预览
                    // （方案 A 同源·AI 侧守卫见 [assistantDeliveryPreview]）。仅刷新最后活动时间保鲜排序，
                    // 列表预览保持入场标记直到见面收尾覆写。
                    conversationRepo.touchLastMessageDate(conversationUuid, userMessage.timestamp)
                } else {
                    conversationRepo.recordLastMessage(conversationUuid, preview, "user", userMessage.timestamp)
                }
            }
        }
    }

    /**
     * 受理已落库的用户消息（契约 §3.2·文字/语音/表情三路共通尾段）：① 受理即嵌（iOS 发送即嵌
     * ChatViewModel+Send.swift:130——窗回合 userMessageForEmbed=null，嵌入责任移到这里逐条完成；后台不阻塞、
     * 失败不影响主流程）→ ② M14 火花续期（P12.6 D1b 列级 UPDATE）+ P6.1e 通知正反馈 → ③ P6.2 忙碌收纳
     * （true=收编直接返回**不入窗**，AI 回复由忙碌管道推迟递送）→ ④ 合并等待窗 [ChatMessageDispatcher.enqueue]
     * （角色缺失时跳过②③仍入窗——窗回合闸门统一报「角色不存在」，与文字路 U1「先落库不丢输入」同精神）。
     */
    private suspend fun acceptStoredUserMessage(userMessage: MessageEntity, collectText: String) {
        scope.launch {
            runCatching { vectorMemory.embedMessageIfNeeded(userMessage) }
                .onFailure { android.util.Log.w("ChatVM", "受理嵌入失败(不影响主流程): ${it.message}") }
        }
        val convo = conversationRepo.get(conversationUuid)
        val character = convo?.let { characterRepo.get(it.characterUuid) }
        if (character != null) {
            val now = userMessage.timestamp
            val chatCharacter = StreakManager.recordChat(character, now)
            if (chatCharacter !== character) {
                characterRepo.updateStreak(character.uuid, chatCharacter.streakCount, chatCharacter.lastChatDate ?: now)
            }
            // 相识天数图纸 §4.1：首条消息落「第一次聊天时间」（SQL 只往早改；老角色由冷启补账改成真最早）。
            if (character.firstMessageDate == null) characterRepo.markFirstMessageDate(character.uuid, now)
            notificationLearningService.recordUserResponse(character.uuid, now)
            // 忙碌延迟回复功能已删除（2026-07-11 用户拍板）：忙碌时段照常即时回复，
            // 分心/简短语气由现在卡【此刻】的 ⚠️ 提示承担。
        }
        enqueueTurnWindow()
    }

    /**
     * 入合并等待窗 + 秒退兜底（健康线 2-5b）：发送流程跑在应用级 [scope]，用户发送后秒退会话时本方法仍会执行，
     * 但 dispatcher 计时器挂在已死的 viewModelScope——enqueue 后窗未活（windowPending=false）即视为计时器无处跑，
     * 直接起窗回合，保证「已受理的消息必有回合」。存活路径 enqueue 后窗恒活（等待窗 ≥0.5s），不受影响。
     */
    private fun enqueueTurnWindow() {
        dispatcher.enqueue()
        if (!dispatcher.windowPending) launchWindowTurn()
    }

    /**
     * 窗到期回合（契约 §3.2-1·[ChatMessageDispatcher.onReadyToSend] 接线）：join 旧回合尾段（三点态打断后的
     * NonCancellable 收尾 / 未打断的装配·维护相位）自然串行 → 闸门（无 API / 角色缺失=报错不跑，消息已落库由
     * 恢复系统补答）→ R3#0 清残留忙碌（防忙碌→空闲过渡期双答）→ isSending 包住常规回合（读全历史一次回答窗内
     * 全部消息；userMessageForEmbed=null——嵌入已在受理时逐条完成）。
     */
    private fun launchWindowTurn() {
        val previousJob = assistantTurnJob
        assistantTurnJob = scope.launch {
            previousJob?.join()
            // 健康线 2-10：与无头回合（通知直接回复/列表快捷回复/后台恢复扫描/分享/见面反应——皆经
            // RecoveryClaimTracker）占坑互斥。无头在飞时本窗让位重排（requeueWindow 不记自适应样本），
            // 其读全历史统一作答；页内消息落库晚于其读历史时，重排的下一窗兜底（坑释放后照常作答）。
            if (!recoveryClaimTracker.tryBegin(conversationUuid)) {
                dispatcher.requeueWindow()
                return@launch
            }
            try {
                val config = apiConfigRepo.resolveConfigValues(ApiFunction.CHAT)
                if (config == null) {
                    errorFlow.value = ERROR_NO_API_CONFIG
                    return@launch
                }
                val convo = conversationRepo.get(conversationUuid) ?: return@launch
                val character = characterRepo.get(convo.characterUuid)
                if (character == null) {
                    errorFlow.value = ERROR_CHARACTER_MISSING
                    return@launch
                }
                val settings = settingsRepo.getAppSettings()
                val userProfile = userProfileDao.get()
                isSending.value = true
                try {
                    assistantTurnEngine.runAssistantTurn(config, character, settings, userProfile, userMessageForEmbed = null)
                } finally {
                    isSending.value = false
                }
            } finally {
                recoveryClaimTracker.end(conversationUuid)
            }
        }
    }

    /** 外部已落库的用户动作（礼物）触发回合=同窗（契约 §3.2-4）：三点态打断 + 入窗（忙碌判定由调用方完成）。 */
    fun enqueueExternalTurn() {
        interruptUndisplayedReplyIfAny()
        enqueueTurnWindow()
    }

    /** @return 是否接受了本次发送（C1：只拒空文本——三点态打断后一律受理入窗，UI 据 true 清空输入框）。 */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        // C1（契约 §3.2-3）：AI 话没说完（三点态）再发 = 打断并丢弃未出现内容；不再有「流式期拒发」。
        interruptUndisplayedReplyIfAny()
        scope.launch {
            // 会话存在性先查：messages 对 conversations 有 FK CASCADE，会话不存在则落库会触发 FK 违反，必须前置
            //（会话删于发送途中=极罕见病态，silent return 保留原语义）。
            val convo = conversationRepo.get(conversationUuid) ?: return@launch

            // 引用回复：消费并清空当前引用目标（UI 长按「引用」由 P1.4 设置；PromptBuilder 已注入引用上下文）
            val quoted = replyTarget.value
            replyTarget.value = null

            // U1（A2 健壮）：用户消息**先落库**——闸门之后（无 API/角色缺失）只阻止 AI 回复，不丢用户输入
            //（闸门报错时机随 C1 移到窗到期回合 [launchWindowTurn]，语义不变）。
            val now = System.currentTimeMillis()
            // 见面期间用户消息须随会话打线下标记（与助手投递 deliverTextReply 同源），否则漏进普通聊天 + 缺席沉浸剧场。
            val offlineSessionId = outgoingOfflineSessionId(convo.isInOfflineMode, convo.currentOfflineSessionId)
            val userMessage = MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "user",
                content = trimmed,
                timestamp = now,
                quotedMessageUUID = quoted?.messageUUID,
                // 引用预览存「给人看的脱敏文本」而非原始 content：红包/礼物/通话等结构化卡的 content 是 JSON（含红包 amount /
                // 礼物 cost），原先会原样露进 ①引用预览 UI ②气泡引用头 ③PromptBuilder 引用上下文喂 LLM。MessagePreviewText
                // 按类型转人话（红包→「🧧 红包」无金额·礼物→「[礼物]名」·普通文本→剥日历/贴纸标签），三处同口径不漏。
                quotedContent = quoted?.let { MessagePreviewText.forMessage(it) },
                quotedSenderRole = quoted?.roleRaw,
                isOfflineMode = offlineSessionId != null,
                offlineSessionId = offlineSessionId,
            )
            storeUserMessage(userMessage, preview = trimmed, inMeeting = offlineSessionId != null) // 2-4+3-1：NonCancellable+单事务（秒退不丢消息·快照不裂）

            acceptStoredUserMessage(userMessage, collectText = trimmed)
        }
        return true
    }

    /**
     * 发送用户选中的表情包（1:1 iOS `sendStickerMessage`）：插 `[sticker:id]` 用户消息、会话预览「[表情包]」、
     * 记最近使用（仅用户发记，AI 发不记），然后与文字发送同路径触发 AI 回复（含忙碌延迟、火花、通知反馈）。
     */
    fun sendStickerMessage(stickerId: String) {
        // C1：同 send——三点态打断后受理入窗（=iOS sendStickerMessage 也 enqueue·契约 §3.2-4）。
        interruptUndisplayedReplyIfAny()
        scope.launch {
            val convo = conversationRepo.get(conversationUuid) ?: return@launch

            val now = System.currentTimeMillis()
            val content = "[sticker:$stickerId]"
            // 见面期间表情包同样须随会话打线下标记（与文字/语音同源），否则漏进普通聊天 + 缺席沉浸剧场。
            val offlineSessionId = outgoingOfflineSessionId(convo.isInOfflineMode, convo.currentOfflineSessionId)
            val userMessage = MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "user",
                content = content,
                timestamp = now,
                isOfflineMode = offlineSessionId != null,
                offlineSessionId = offlineSessionId,
            )
            storeUserMessage(userMessage, preview = "[表情包]", inMeeting = offlineSessionId != null) // 2-4+3-1：NonCancellable+单事务
            StickerRecentStore.recordUsage(appContext, stickerId)

            acceptStoredUserMessage(userMessage, collectText = content)
        }
    }

    /**
     * 发送用户选中的图片（图片多模态一期·拍板③「选完即发」）。落库链在协作者
     * [ChatImageSender] 里（加进本类会越 600 行硬上限）；此处只保留与文字/表情同款的
     * 「三点态打断 + 起协程」两件事，保证四路发送入口的编排语义一致。
     */
    fun sendImages(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        interruptUndisplayedReplyIfAny()
        scope.launch { imageSender.send(scope, uris) }
    }

    /**
     * 重新生成：删除最后一轮 assistant 消息（连续尾段），用当前历史重跑助手回合。
     *
     * **范围判据与菜单同源（2026-09-04 用户拍板根治·复核 R2 🔴-1）**：删哪些由 [RegenerableTurn] 单源决定，
     * 菜单给不给这一项用的是同一个对象、同一份可见流——**改判据只改 [RegenerableTurn]，两侧自动一致**。
     * 这里读 `recentVisibleChronological`（可见流）而非 `getRecent`（DB 全量）：全量里夹着用户看不见的
     * 系统旁白（`roleRaw="user"` 的 SYSTEM_HINT）、通话逐轮转写、见面期叙事，按全量算会删到「长按的那条」
     * 之外的东西——根治前的两类事故即由此而来（假选项静默 return / 误删通话记录卡与见面结束条）。
     * 重跑本身仍用全量上下文（[AssistantTurnEngine] 内部取数不变）。
     */
    fun regenerate() {
        if (isSending.value || assistantTurnJob?.isActive == true) return // 复核 MED#4：同 send 的同步并发门。
        assistantTurnJob = scope.launch {
            val config = apiConfigRepo.resolveConfigValues(ApiFunction.CHAT)
            if (config == null) {
                errorFlow.value = ERROR_NO_API_CONFIG
                return@launch
            }
            val convo = conversationRepo.get(conversationUuid) ?: return@launch
            val character = characterRepo.get(convo.characterUuid)
            if (character == null) {
                errorFlow.value = ERROR_CHARACTER_MISSING
                return@launch
            }
            // 与菜单判据**同源**（RegenerableTurn·2026-09-04 根治）：取【可见流】而非 DB 全量——全量里夹着
            // 用户看不见的系统旁白 / 通话转写 / 见面叙事，按它算会删到用户长按的那条之外的东西（见上方 KDoc）。
            val visible = messageRepo.recentVisibleChronological(conversationUuid, HISTORY_FETCH_LIMIT)
            val trailing = RegenerableTurn.trailing(visible)
            if (trailing.isEmpty()) {
                // 菜单已按同一判据不给这一项 → 走到这里说明两侧漂了（或竞态：点击与新消息同帧）。留痕而非纯静默。
                android.util.Log.w("ChatVM", "重新生成：可见流末尾无可重来的一轮，本次不做")
                return@launch
            }
            // 审计 R1：删除循环 + 快照重算包 NonCancellable（镜像 ChatViewModel.deleteMessage）——点「重新生成」后
            // 立刻退出会话（VM 清理取消本 scope）时，绝不留下「删一半 + 预览停在已删消息」的半截状态；
            // 新回合本身仍可取消（用户已离开，无需重新生成）。
            withContext(NonCancellable) {
                trailing.forEach { messageRepo.deleteByUuid(it.messageUUID) }
                // 删尾段后即刷新会话「最后一条」快照：新回合若失败，列表也不会停在已删的旧 AI 消息上（问题②同源）。
                refreshConversationLastMessage(conversationUuid, messageRepo, conversationRepo)
            }
            // 取消发生在删除期间：上面已保全一致性，这里显式退出、新回合不再跑（用户已离开）——
            // 不依赖后续挂起点「恰好」配合取消（真调度会停，但显式判定才是确定性的）。
            if (!isActive) return@launch

            val settings = settingsRepo.getAppSettings()
            val userProfile = userProfileDao.get()
            isSending.value = true
            try {
                assistantTurnEngine.runAssistantTurn(config, character, settings, userProfile, userMessageForEmbed = null)
            } finally {
                isSending.value = false
            }
        }
    }

    /**
     * 自动恢复未回复消息（1:1 iOS autoRecoverUnansweredMessage）：进入聊天页时若最后一条是用户消息且无 AI 回复
     * （崩溃/被杀/流式中断），延迟 600ms 后静默重新请求。守卫防与正在进行的回合 / 错误态 / 忙碌延迟 / 线下模式并发；
     * 串行回合经 [launchSerializedTurn] 的 `isSending` 门复检并发（与普通 send / 再次点击互斥）。
     */
    internal fun autoRecoverUnansweredMessage(startDelayMs: Long = 600L) {
        unansweredRecoveryJob?.cancel()
        unansweredRecoveryJob = scope.launch {
            delay(startDelayMs) // 让界面先稳定（对齐 iOS 600ms；T2 注入 0 同步跑）
            if (isSending.value || errorFlow.value != null) return@launch
            if (dispatcher.windowPending) return@launch // C1：等待窗内有在途新消息 → 窗回合统一作答，防双答
            val convo = conversationRepo.get(conversationUuid) ?: return@launch
            // D3 时间感知重进（2026-07-07 拍板·取代复核 MED#3 的「线下不短路」）：见面期间线下叙事不回写会话
            // 预览，convo.lastMessageRole 恒为入场 hint 的 "user"——旧判据把每次进屏都当「未答」，无条件自动推进
            // 一拍（观感=角色自说自话）。改按【线下 session 实际最后一条】+ 离开时长分档（OfflineReturnPolicy）：
            // 真未答→照旧恢复（被杀的线下回合仍能续上）；已答→≤3min 静默 / 3–10min 插归来 hint 轻推一拍 /
            // >10min 交恢复弹窗（handleChatAppear 已弹，由「继续见面」路径带时长衔接）。
            if (convo.isInOfflineMode) {
                val sessionId = convo.currentOfflineSessionId
                val lastOffline = if (!sessionId.isNullOrEmpty()) {
                    messageRepo.offlineSessionMessages(conversationUuid, sessionId).lastOrNull()
                } else {
                    null
                }
                val awayMs = lastOffline?.let { (System.currentTimeMillis() - it.timestamp).coerceAtLeast(0L) } ?: 0L
                when (OfflineReturnPolicy.decide(lastOffline?.roleRaw, awayMs)) {
                    OfflineReturnPolicy.Action.NONE -> return@launch
                    OfflineReturnPolicy.Action.NUDGE -> {
                        launchSerializedTurn {
                            if (!recoveryClaimTracker.tryBegin(conversationUuid)) return@launchSerializedTurn
                            try {
                                if (offlineMeetingService.insertReturnAfterAwayHint(
                                        conversationUuid, OfflineReturnPolicy.awayMinutes(awayMs),
                                    )
                                ) {
                                    runAssistantTurnForCurrentConversation()
                                }
                            } finally {
                                recoveryClaimTracker.end(conversationUuid)
                            }
                        }
                        return@launch
                    }
                    OfflineReturnPolicy.Action.RECOVER_UNANSWERED -> Unit // 走下方通用恢复
                }
            } else if (convo.lastMessageRole != "user" || convo.lastMessagePreview.isEmpty()) {
                return@launch
            }
            val character = characterRepo.get(convo.characterUuid) ?: return@launch
            launchSerializedTurn {
                // 原子占坑：后台扫描正在恢复这个对话 → 让位，防同一条消息双答（复核 HIGH#1）。占坑跨整个回合，finally 释放。
                if (!recoveryClaimTracker.tryBegin(conversationUuid)) return@launchSerializedTurn
                try {
                    runAssistantTurnForCurrentConversation()
                } finally {
                    recoveryClaimTracker.end(conversationUuid)
                }
            }
        }
    }

    /**
     * 触发一次助手回合（线下进入/续场用，无新用户消息、跳过忙碌延迟；userMessageForEmbed=null）。
     * **不管理 [isSending]**——由调用方在 launch 前同步置位（serialize 防并发回合，见各线下入口）。
     */
    internal suspend fun runAssistantTurnForCurrentConversation() {
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) ?: run {
            errorFlow.value = ERROR_NO_API_CONFIG
            return
        }
        val convo = conversationRepo.get(conversationUuid) ?: return
        val character = characterRepo.get(convo.characterUuid) ?: run {
            errorFlow.value = ERROR_CHARACTER_MISSING
            return
        }
        val settings = settingsRepo.getAppSettings()
        val userProfile = userProfileDao.get()
        assistantTurnEngine.runAssistantTurn(config, character, settings, userProfile, userMessageForEmbed = null)
    }

    /**
     * 助手回合的统一串行化包装（线下入口 + 未答恢复共用）。修 MED：原各入口在 launch 前不查 isSending、且
     * isSending 在 DB 工作后才置，留下与普通 send/再次点击 并发起两个回合的窗口。**同步**查 + 置 isSending
     * （scope=Main.immediate，查与置之间无挂起点 → 原子），DB 工作 + 回合在 launch 内、finally 复位。
     * 已在进行 → 丢弃本次（= send 既有约定，未答恢复亦据此与 send 互斥）。
     */
    internal fun launchSerializedTurn(block: suspend () -> Unit) {
        if (isSending.value || assistantTurnJob?.isActive == true) return // 复核 MED#4：也挡 send/regenerate 较晚置 isSending 的窗口。
        isSending.value = true
        assistantTurnJob = scope.launch {
            try {
                block()
            } finally {
                isSending.value = false
            }
        }
    }


    private companion object {
        /** 审计 S8：用户可见文案单源（原逐处硬编码 ×5/×5；迁 strings.xml 挂账②同批另议）。 */
        internal const val ERROR_NO_API_CONFIG = "请先在「我 → API 配置」添加并启用一个 API"
        internal const val ERROR_CHARACTER_MISSING = "角色不存在"

        /** 文字路发送前等转写的最长时间（毫秒，1:1 iOS transcriptionWaitTimeout 的 16s）。 */
        const val STT_SEND_WAIT_MS = 16_000L

        /** 等转写的轮询间隔（毫秒，1:1 iOS transcriptionPollInterval 的 80ms）。 */
        const val STT_POLL_MS = 80L
    }
}
