package com.situ.aichat.ui.chat

import android.content.Context
import android.util.Log
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.gift.ProactiveGiftMaintenanceService
import com.situ.aichat.meeting.MeetingAppointmentStore
import com.situ.aichat.meeting.MeetingArrivalPolicy
import com.situ.aichat.meeting.MeetingFulfillmentService
import com.situ.aichat.offline.OfflineChatVisibility
import com.situ.aichat.offline.OfflineMarkerStartPayload
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.offline.OfflineMeetingService
import com.situ.aichat.offline.OfflineReturnPolicy
import com.situ.aichat.offline.OfflineSummaryRetryCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 线下见面生命周期协作者——从 ChatViewModel 抽出（对齐 iOS ChatViewModel+ToolCalling/+Offline），方法体字节级不变。
 * 管用户面的见面动作：进页修脏状态/恢复弹窗、接受/拒绝邀约、主动发起、取消提示、续场、退出/异常恢复结束、退出后摘要重试。
 *
 * **引擎相关一律经回调注入**（保持本协作者不碰助手回合引擎内部）：
 * [runAssistantTurn] = VM runAssistantTurnForCurrentConversation；[serialize] = VM launchSerializedTurn（串行化防并发回合）；
 * [cancelActiveTurn] = VM `assistantTurnJob?.cancelAndJoin()`；[afterOfflineMemorySummary] = VM triggerMemorySummaryAfterOffline。
 * [recoveryPromptVisibleFlow]/[infoToastFlow] = VM 的 _offlineRecoveryPromptVisible/_infoToast（与日历/语音协作者同款 flow 注入）。
 * 自动恢复未答消息（autoRecoverUnansweredMessage）非线下专属（通用回合恢复），有意留 VM。
 */
internal class ChatOfflineController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val conversationUuid: String,
    private val infoToastFlow: MutableStateFlow<String?>,
    private val recoveryPromptVisibleFlow: MutableStateFlow<Boolean>,
    private val messageRepo: MessageRepository,
    // D1a：赴约撞见面时读会话线下态（卷一）。
    private val conversationRepo: ConversationRepository,
    private val settingsRepo: SettingsRepository,
    private val offlineMeetingService: OfflineMeetingService,
    private val offlineSummaryRetryCoordinator: OfflineSummaryRetryCoordinator,
    private val meetingAppointmentStore: MeetingAppointmentStore,
    // 图纸 2026-08-31 C2：任意入口进见面即核销本会话到期的已确认约定（不再只认赴约按钮/通知两路）。
    private val meetingFulfillmentService: MeetingFulfillmentService,
    private val runAssistantTurn: suspend () -> Unit,
    private val serialize: (suspend () -> Unit) -> Unit,
    private val cancelActiveTurn: suspend () -> Unit,
    private val afterOfflineMemorySummary: suspend () -> Unit,
    // 见面结束成功分支排「余温消息」一次性 worker（§3.10·涟漪①）——VM 侧读 pending session + 排程（BackgroundScheduler 在 VM）。
    private val scheduleOfflineAfterglow: suspend () -> Unit,
    // 见面结束成功分支掷点排「朋友圈呼应帖」一次性 worker（卷二 §5④）——掷点与排程同在 VM 侧（BackgroundScheduler 在 VM）。
    private val scheduleMeetingMomentEcho: suspend () -> Unit,
    // 见面结束后补跑一次主动送礼维护线（卷一 A4b）：见面期被闸掉的礼物/红包在结束当天补送。
    private val proactiveGiftMaintenanceService: ProactiveGiftMaintenanceService,
) {

    // offline-1：点聊天流里的「线下见面结束」分隔条 → 只读见面回顾覆盖层（对齐 iOS OfflineMarkerCard onTapReview）。
    // 审计 S3 自 VM 只搬不改收编（本类=线下见面生命周期的家）。
    private val _offlineReviewInfo = MutableStateFlow<String?>(null)
    val offlineReviewInfo: StateFlow<String?> = _offlineReviewInfo.asStateFlow()
    private val _offlineReviewMessages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val offlineReviewMessages: StateFlow<List<MessageEntity>> = _offlineReviewMessages.asStateFlow()

    // D3：恢复弹窗弹出时的离开时长（毫秒）——UI 按 OfflineReturnPolicy.isLongAbsence 切换文案；null=未知。
    private val _recoveryAwayMs = MutableStateFlow<Long?>(null)
    val recoveryAwayMs: StateFlow<Long?> = _recoveryAwayMs.asStateFlow()

    /** 打开某次见面的只读回顾（1:1 iOS OfflineReviewView.loadMessages 过滤，与 OfflineMeetingMemoryViewModel 同口径）。 */
    fun openOfflineReview(sessionId: String) {
        scope.launch {
            val all = messageRepo.offlineSessionMessages(conversationUuid, sessionId)
            val info = all.firstOrNull { MessageKind.fromRaw(it.messageKindRaw) == MessageKind.OFFLINE_MARKER_START }
                ?.let { OfflineMarkerStartPayload.parse(it.content) }
                ?.let { "${it.location} · ${it.activity}" }
                ?: ""
            _offlineReviewMessages.value = all.filter { m ->
                !OfflineChatVisibility.isHiddenFromReview(MessageKind.fromRaw(m.messageKindRaw)) // S8 单源
            }
            _offlineReviewInfo.value = info
        }
    }

    fun closeOfflineReview() {
        _offlineReviewInfo.value = null
        _offlineReviewMessages.value = emptyList()
    }

    /**
     * 进入会话时的线下处理（搬自 VM onChatAppear 的线下两段·字节级不变）：幂等修复线下脏状态 + 判定是否弹异常恢复提示；
     * 独立协程跑见面摘要重试链 ②重进对话层（前台兜底弹 Toast）。自动恢复未答消息由 VM 侧 autoRecoverUnansweredMessage 接着跑。
     */
    fun handleChatAppear() {
        scope.launch {
            offlineMeetingService.ensureStateConsistency(conversationUuid)
            if (offlineMeetingService.shouldShowRecoveryPrompt(conversationUuid)) {
                // D3：弹窗前记下离开时长——超长离开（>3h）弹窗文案引导「结束见面」；「继续见面」按它衔接时间流逝。
                _recoveryAwayMs.value = offlineMeetingService.offlineAwayMs(conversationUuid)
                recoveryPromptVisibleFlow.value = true
            }
        }
        // 见面摘要重试链 ②重进对话层（带退避判断，1:1 iOS retryPendingOfflineSummaryIfNeeded）。独立协程，
        // 不让 LLM 重试阻塞恢复弹窗判定；前台触发的兜底弹 Toast。
        scope.launch {
            if (offlineSummaryRetryCoordinator.retryOne(conversationUuid) == OfflineSummaryRetryCoordinator.RetryOutcome.FELL_BACK) {
                infoToastFlow.value = appContext.getString(R.string.offline_meeting_summary_fallback_notice)
            }
        }
    }

    /** 用户接受最近一张邀约卡（卡片「好呀」按钮）：置卡片 responded + 进入线下模式 + 触发 AI 开场。 */
    fun acceptOfflineInvite(messageUuid: String) {
        serialize {
            offlineMeetingService.markInviteResponded(messageUuid, "accepted")
            // 卷一 D2：把被点的那张卡的 uuid 传下去——往回翻点旧卡时不能被「扫最近一张」带进另一场约。
            val sessionId = offlineMeetingService.acceptOfflineInvite(conversationUuid, messageUuid)
            if (sessionId != null) {
                honorDueAppointmentsSafely(sessionId) // C2：邀约卡进的同一场约也算赴约
                runAssistantTurn()
            }
        }
    }

    /** 用户拒绝邀约卡（卡片「下次吧」按钮）：仅置卡片 responded=declined（不进入、不触发，无需串行化）。 */
    fun declineOfflineInvite(messageUuid: String) {
        scope.launch { offlineMeetingService.markInviteResponded(messageUuid, "declined") }
    }

    /** 用户在 + 菜单主动发起线下见面（填地点活动后）：进入线下模式 + 触发 AI 开场。 */
    fun startManualOfflineMeeting(location: String, activity: String) {
        serialize {
            val sessionId = offlineMeetingService.startManualOfflineMeeting(conversationUuid, location, activity)
            if (sessionId != null) {
                honorDueAppointmentsSafely(sessionId) // C2：手动发起的同一场约也算赴约
                runAssistantTurn()
            }
        }
    }

    /**
     * 「未来约定见面」到点赴约（Phase 10·到点通知点击 / 在 App「出发赴约」按钮共用）：取约定真理源 →
     * **仍在宽限窗口内**才用其地点/活动/心事种子进入线下见面沉浸 → markHonored 链 sessionId → 触发 AI 开场
     * （同 [acceptOfflineInvite]）。过宽限 / 非 confirmed / 找不到 / 已在线下 → 不进（过宽限交 Phase 11 爽约扫描置
     * missed）。串行化防与在投递回合并发。
     */
    fun arriveAtAppointment(appointmentUuid: String) {
        scope.launch {
            val appt = meetingAppointmentStore.get(appointmentUuid) ?: return@launch
            if (MeetingStatus.fromRaw(appt.status) != MeetingStatus.CONFIRMED) return@launch
            val withinWindow = MeetingArrivalPolicy.isWithinArrivalWindow(
                appt.scheduledAt,
                MeetingTimeGranularity.fromRaw(appt.timeGranularity),
                System.currentTimeMillis(),
                ZoneId.systemDefault(),
            )
            if (!withinWindow) return@launch // 过宽限：不赴约，交 Phase 11 爽约扫描置 missed
            // 复核 MED：通知点击 / 按钮可能撞上在投递的回合——若用 serialize 会被「isSending 门」整体丢弃，
            // 用户已点赴约却没进沉浸、随后被 Phase 11 当爽约「怪你没来」。故**不经 serialize 的丢弃门**：先打断在投递
            // 的回合（同 finalizeOffline·防其残留落进刚进入的线下态），进入 + markHonored **必落**；开场回合再串行化。
            cancelActiveTurn()
            val sessionId =
                offlineMeetingService.startFromAppointment(conversationUuid, appt.location, appt.activity, appt.hiddenTensionSeed)
            if (sessionId != null) {
                meetingAppointmentStore.markHonored(appointmentUuid, sessionId)
                honorDueAppointmentsSafely(sessionId) // C2：同窗口的重复/幽灵约定顺手一并核销（幂等）
                serialize { runAssistantTurn() } // 已打断·isSending 已清 → 开场回合不会被丢
            } else {
                // 卷一 D1a（拍板⑪）：进不去的唯一常见原因 = **已经在见面中**（enterOfflineMode 幂等返 null）——
                // 用户正是在赴这场约，绝不能放着不管让 Phase 11 爽约扫描把它判成「你没来」。这里补 markHonored
                // 链上当前 sessionId；守卫拒绝（并发已取消/已赴约）返 null → 不再处理，不抛错。
                val convo = conversationRepo.get(conversationUuid)
                if (OfflineMeetingGate.inMeeting(convo)) {
                    meetingAppointmentStore.markHonored(appointmentUuid, convo?.currentOfflineSessionId.orEmpty())
                    honorDueAppointmentsSafely(convo?.currentOfflineSessionId.orEmpty()) // C2：同上
                }
            }
        }
    }

    /**
     * 图纸 2026-08-31 C2：进见面成功后核销本会话到期的已确认约定 + 撤其到点通知
     * （[MeetingFulfillmentService.honorDueAppointmentsOnMeetingStart]·幂等）。失败只记日志，
     * 绝不拖垮见面开场——核销漏掉还有爽约扫描的真见面闸兜底（C1）。
     */
    private suspend fun honorDueAppointmentsSafely(sessionId: String) {
        runCatching { meetingFulfillmentService.honorDueAppointmentsOnMeetingStart(conversationUuid, sessionId) }
            .onFailure { Log.w(TAG_INSTANT_GIST, "进见面核销约定失败（不影响见面）：${it.message}") }
    }

    /** 用户打开发起见面界面又取消：插用户不可见的取消提示 + 触发 AI 回复（1:1 iOS handleMeetingCancelHint）。 */
    fun handleMeetingCancelHint() {
        serialize {
            if (offlineMeetingService.insertMeetingCancelHint(conversationUuid)) runAssistantTurn()
        }
    }

    /** 用户点结束确认卡「再待一会儿」：置卡片 responded=continued + 散场硬闸 3 轮（图纸 2026-09-06 七件 §3.E）+ 续场 hint + 触发 AI 回复。 */
    fun continueOfflineMeeting(endCardMessageUuid: String) {
        serialize {
            offlineMeetingService.markInviteResponded(endCardMessageUuid, "continued")
            if (offlineMeetingService.continueOfflineMeeting(conversationUuid)) runAssistantTurn()
        }
    }

    /** 用户主动结束见面（导航栏「结束」/结束确认卡「结束见面」）：打断在投递的回合 → finalize → 补常规记忆。 */
    fun exitOfflineMode() {
        scope.launch { finalizeOffline(OfflineMeetingService.ExitReason.USER_ENDED) }
    }

    /** 异常恢复弹窗「结束见面」：finalize(USER_ABORTED) + 隐藏弹窗。 */
    fun endMeetingFromRecovery() {
        scope.launch {
            finalizeOffline(OfflineMeetingService.ExitReason.USER_ABORTED)
            recoveryPromptVisibleFlow.value = false
        }
    }

    /** 点弹窗外 / 系统返回关闭恢复弹窗：仅隐藏、不触发（原「继续见面」的只 dismiss 语义留给被动关闭）。 */
    fun dismissOfflineRecoveryPrompt() {
        recoveryPromptVisibleFlow.value = false
    }

    /**
     * D3 时间感知重进（2026-07-07 拍板·取代旧「继续见面=只 dismiss」）：恢复弹窗点「继续见面」——
     * 插「归来」隐藏提示（带离开时长）+ 触发一拍让角色用 [时间：…] 级别的跳跃自然衔接。
     * 时长取不到（极端：弹窗期间见面被结束）→ 只关弹窗不触发。
     */
    fun continueMeetingFromRecovery() {
        recoveryPromptVisibleFlow.value = false
        serialize {
            val awayMs = offlineMeetingService.offlineAwayMs(conversationUuid) ?: return@serialize
            if (offlineMeetingService.insertReturnAfterAwayHint(
                    conversationUuid, OfflineReturnPolicy.awayMinutes(awayMs),
                )
            ) {
                runAssistantTurn()
            }
        }
    }

    /** 统一结束流程：打断在投递/流式的回合（= iOS finalize 前 cancel）→ 写离场标记/清状态 → 退出后补常规记忆。 */
    private suspend fun finalizeOffline(reason: OfflineMeetingService.ExitReason) {
        cancelActiveTurn() // 防 isInOfflineMode 清除后残留流式被当普通消息处理（1:1 iOS）
        if (offlineMeetingService.finalizeOfflineMode(conversationUuid, reason)) {
            // 线下期间常规摘要被 isInOfflineMode guard 跳过，退出后补一次让常规双轨判定接管（1:1 iOS）。
            afterOfflineMemorySummary()
            // 见面摘要重试链 ①前台即时层（1:1 iOS finalizeOfflineMode 末尾 async extractOfflineMeetingMemory）。
            triggerOfflineMeetingSummary()
            // 涟漪①：排「余温消息」一次性延迟 worker（recordOfflineExited 已设 pendingOfflineSummarySessionId·§3.10）。
            scheduleOfflineAfterglow()
            // 涟漪②（卷二 §5④）：掷点决定这场见面要不要在 3–7 小时后发一条朋友圈呼应帖（未中签只打日志）。
            scheduleMeetingMomentEcho()
            // 卷一 A4b：见面期被见面闸早退（Skipped·未写幂等流水）的主动送礼/红包，在结束后补跑一次维护线
            // 补送——不建显式队列（Skipped 不占 relatedKey，下次评估自然重来）。fire-and-forget，失败静默：
            // App 被杀也有 AppViewModel 回前台的日常维护调用兜底。
            scope.launch { runCatching { proactiveGiftMaintenanceService.runMaintenance() } }
        }
    }

    /**
     * 见面摘要重试链 ①前台即时层（退出后立即提取见面长期记忆，1:1 iOS finalizeOfflineMode 末尾的异步 extract）。
     * 独立协程不阻塞退出 UI；pending 已在 finalizeOfflineMode 落库，本协程即便被取消也由 ②③④⑤ 层兜底。前台兜底弹 Toast。
     */
    private fun triggerOfflineMeetingSummary() {
        scope.launch {
            // 卷二 G1：先落一行「简版」即时要点（source=instant），顶住 LLM 摘要未落前的失忆空窗；
            // 随后 retryOne 成功会按 sessionId 原位覆盖成 llm 行（=「替换」，Repository E6 幂等）。
            // sessionId 与 retryOne 同源（会话的 pendingOfflineSummarySessionId）；即时要点失败绝不拖垮重试链。
            runCatching {
                val pendingSessionId = conversationRepo.get(conversationUuid)
                    ?.pendingOfflineSummarySessionId
                    ?.takeIf { it.isNotEmpty() }
                if (pendingSessionId != null) {
                    offlineSummaryRetryCoordinator.applyInstantGist(conversationUuid, pendingSessionId)
                }
            }.onFailure { Log.w(TAG_INSTANT_GIST, "见面即时要点落行失败（不影响重试链）：${it.message}") }
            if (offlineSummaryRetryCoordinator.retryOne(conversationUuid) == OfflineSummaryRetryCoordinator.RetryOutcome.FELL_BACK) {
                infoToastFlow.value = appContext.getString(R.string.offline_meeting_summary_fallback_notice)
            }
        }
    }

    private companion object {
        /** 卷二 G1 即时要点落行失败日志标签（只打 sessionId/异常摘要，绝不打见面内容）。 */
        const val TAG_INSTANT_GIST = "ChatOfflineController"
    }
}
