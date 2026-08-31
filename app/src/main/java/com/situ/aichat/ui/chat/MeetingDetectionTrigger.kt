package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.MeetingCandidate
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.meeting.MeetingAppointmentStore
import com.situ.aichat.meeting.MeetingDetectionService
import com.situ.aichat.meeting.MeetingDisplayFormatter
import com.situ.aichat.meeting.MeetingProposalCoordinator
import com.situ.aichat.meeting.MeetupNotificationService
import com.situ.aichat.prompt.memory.MemoryService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 「回合后台分析触发」之未来约定见面识别簇（骨干路·8d-3a）。回复完成后按节奏（[MeetingDetectionService.scanTriggerDecision]）
 * 后台扫一次最近对话，识别新/改期/取消/确认约定 → [MeetingProposalCoordinator.ingestCandidate]（NEW 过确认卡·
 * confirmed 的改期/取消过变更卡·决策①）。fire-and-forget·错误全静默（仅写失败短冷却）·不碰 UI。
 *
 * 与 [MemoryAnalysisTrigger] 同款形态（VM 持有·viewModelScope·防并发标志）。扫描节奏跨进程持久化在 conversations
 * 的 lastMeetingScan* 列（8d-1），防进程死亡后扫描风暴。线下见面期间跳过（避免「你有约定」与正在见面矛盾）。
 */
internal class MeetingDetectionTrigger(
    private val scope: CoroutineScope,
    private val conversationUuid: String,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val meetingStore: MeetingAppointmentStore,
    private val coordinator: MeetingProposalCoordinator,
    private val meetupNotificationService: MeetupNotificationService,
    private val contextLog: ContextLogService,
) {
    /** 防止并发扫描（仅 Main 调度协程读写，无需同步）。 */
    private var isScanning = false

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** 扫描取最近消息条数（=参数表「30 条·排除确认卡」）。 */
    private val recentWindow = 30

    private companion object {
        /** C3：识别提示词携带「近 7 天已赴约」约定的回看窗（防旧事重提被立成幽灵新约）。 */
        const val HONORED_LOOKBACK_MS = 7L * 24 * 3600 * 1000
    }

    /**
     * AI 回复完成后：判定是否到点扫描 → 扫 → 入库候选 → 记成功/失败冷却。
     * [config] = 本回合解析的 LLM 配置（识别无独立 ApiFunction，复用当前激活）。
     */
    /**
     * 快路（工具 / 文本暗号）候选即时入库（8d-3b）——不过扫描节奏，回复后立刻 ingest（NEW 当场冒确认卡）。
     * 候选均 intent=new（工具/暗号只提案）；coordinator 自带查重，重复同天同活动不重复建卡。
     */
    fun ingestFastPath(candidates: List<MeetingCandidate>, character: CharacterEntity) {
        if (candidates.isEmpty()) return
        scope.launch {
            // 线下见面期间暂停识别（§7·避免「你有约定」与正在见面矛盾）——与 checkAndTrigger 同口径。
            if (conversationRepo.get(conversationUuid)?.isInOfflineMode == true) return@launch
            for (candidate in candidates) {
                coordinator.ingestCandidate(candidate, character.uuid, conversationUuid, zone)
            }
            // 复核 HIGH：识别来的 confirm/改期/取消可能改了 confirmed 约定的到点状态 → 刷到点通知（与卡按钮路
            // launchMeetingMutation 同口径·NEW 仅落 proposed 不排程时 rescheduleAll 为幂等 no-op）。
            meetupNotificationService.rescheduleAll()
        }
    }

    fun checkAndTrigger(character: CharacterEntity, config: ApiConfigValues, userName: String) {
        if (isScanning) return

        isScanning = true
        scope.launch {
            try {
                val conversation = conversationRepo.get(conversationUuid) ?: return@launch
                if (conversation.isInOfflineMode) return@launch
                val now = System.currentTimeMillis()

                // 最近可见消息（getRecentVisible 已滤系统耳语）；用于轮数 + 对话文本（再剔结构化卡·防卡原文进 prompt）。
                val recent = messageRepo.recentVisibleChronological(conversationUuid, recentWindow)
                val sinceScan = recent.filter { it.timestamp > (conversation.lastMeetingScanSuccessDate ?: 0L) }
                val rounds = MemoryService.countRounds(sinceScan)

                val decision = MeetingDetectionService.scanTriggerDecision(
                    roundsSinceLastScan = rounds,
                    lastScanMillis = conversation.lastMeetingScanSuccessDate,
                    lastFailureMillis = conversation.lastMeetingScanFailureDate,
                    nowMillis = now,
                )
                if (decision !is MeetingDetectionService.ScanTriggerDecision.Trigger) return@launch

                // 图纸 2026-08-31 C3：每条消息带说出时刻（与「当前时间」同格式）——治幽灵约定根因①：
                // 无时间戳时，昨天说的「明天买裙子」在见面后重扫会被当成今天说的、重新立约。
                val convText = recent
                    .filter { !MessageKind.fromRaw(it.messageKindRaw).isStructuredCard && it.content.isNotBlank() }
                    .joinToString("\n") { m ->
                        val who = if (m.roleRaw == "user") userName.ifEmpty { "用户" } else character.name.ifEmpty { "AI" }
                        "[${MeetingDisplayFormatter.nowText(m.timestamp, zone)}] $who：${m.content}"
                    }
                if (convText.isBlank()) return@launch

                val existing = meetingStore.activeForCharacter(character.uuid).map {
                    MeetingDetectionService.ExistingAppointmentBrief(
                        uuid = it.uuid,
                        whenText = MeetingDisplayFormatter.whenDisplay(it.scheduledAt, MeetingTimeGranularity.fromRaw(it.timeGranularity), zone),
                        activity = it.activity,
                    )
                }
                // C3：近 7 天已赴约的约定喂进提示词（旧事重提不算新约）——治幽灵约定根因②：
                // 核销后约定从 activeForCharacter 消失，识别 LLM 不知道这事已经办完了。
                val recentlyHonored = meetingStore.recentlyHonoredForCharacter(character.uuid, now, HONORED_LOOKBACK_MS).map {
                    MeetingDetectionService.ExistingAppointmentBrief(
                        uuid = it.uuid,
                        whenText = MeetingDisplayFormatter.whenDisplay(it.scheduledAt, MeetingTimeGranularity.fromRaw(it.timeGranularity), zone),
                        activity = it.activity,
                    )
                }

                val prompt = MeetingDetectionService.buildScanPrompt(
                    conversationText = convText,
                    existing = existing,
                    characterName = character.name,
                    userName = userName,
                    nowText = MeetingDisplayFormatter.nowText(now, zone),
                    recentlyHonored = recentlyHonored,
                )

                val candidates = MeetingDetectionService.scanForCandidates(prompt) { messages, temperature ->
                    contextLog.completion(
                        source = LogSource.MEETING_DETECTION,
                        characterName = character.name,
                        config = config,
                        messages = messages,
                        temperature = temperature,
                        responseFormat = ResponseFormatDto(type = "json_object"),
                    )
                }

                for (candidate in candidates) {
                    // confirm/cancel/reschedule 防御：target 给了就必须是**本角色**的进行中约定（LLM 偶发串 id / 跨角色）；
                    // 不满足整条跳过（coordinator 自身也守空/终态，这里额外拦跨角色 + 提前过滤）。
                    val targetUuid = candidate.targetAppointmentUuid
                    if (targetUuid != null) {
                        val t = meetingStore.get(targetUuid)
                        if (t == null || t.characterUuid != character.uuid || !MeetingStatus.fromRaw(t.status).isActive) continue
                    }
                    coordinator.ingestCandidate(candidate, character.uuid, conversationUuid, zone, now)
                }
                // 复核 HIGH：有候选入库时刷到点通知（识别 confirm/改期/取消改了 confirmed 约定·与卡按钮路同口径）。
                if (candidates.isNotEmpty()) meetupNotificationService.rescheduleAll()
                // 扫成功（即便 0 候选）→ 写成功冷却（600s）。
                conversationRepo.recordMeetingScanResult(conversationUuid, success = true, now = now)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // LLM 调用失败等 → 失败短冷却（300s），静默。
                conversationRepo.recordMeetingScanResult(conversationUuid, success = false, now = System.currentTimeMillis())
            } finally {
                isScanning = false
            }
        }
    }
}
