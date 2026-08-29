package com.situ.aichat.ui.offline

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.offline.OfflineChatVisibility
import com.situ.aichat.offline.OfflineMarkerStartPayload
import com.situ.aichat.offline.OfflineMeetingSession
import com.situ.aichat.offline.OfflineMeetingSessionExtractor
import com.situ.aichat.offline.OfflineMeetingSummarySchema
import com.situ.aichat.offline.OfflineSummaryRetryCoordinator
import com.situ.aichat.prompt.messageLlmSafeText
import com.situ.aichat.util.DateFormatters
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 角色档案「见面回忆」页 ViewModel（10.2e-4）：按角色 uuid 提取见面会话列表（[OfflineMeetingSessionExtractor]），
 * 手动重试规则兜底摘要（[OfflineSummaryRetryCoordinator.manuallyRetry]），加载只读回顾的会话消息。
 */
@HiltViewModel
class OfflineMeetingMemoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val extractor: OfflineMeetingSessionExtractor,
    private val retryCoordinator: OfflineSummaryRetryCoordinator,
    private val characterRepo: CharacterRepository,
    private val offlineMeetingMemoryRepository: OfflineMeetingMemoryRepository,
    private val userProfileDao: UserProfileDao,
    private val messageRepo: MessageRepository,
    private val messageDao: MessageDao,
    private val apiConfigRepo: ApiConfigRepository,
    private val contextLog: ContextLogService,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    private val characterUuid: String = savedStateHandle["characterUuid"] ?: ""

    private val _character = MutableStateFlow<CharacterEntity?>(null)
    val character: StateFlow<CharacterEntity?> = _character.asStateFlow()

    private val _sessions = MutableStateFlow<List<OfflineMeetingSession>>(emptyList())
    val sessions: StateFlow<List<OfflineMeetingSession>> = _sessions.asStateFlow()

    private val _userProfile = MutableStateFlow<UserProfileEntity?>(null)
    val userProfile: StateFlow<UserProfileEntity?> = _userProfile.asStateFlow()

    val appSettings: StateFlow<AppSettings> =
        settingsRepo.appSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** 当前正在回顾的会话（null = 未打开回顾）。 */
    private val _reviewSession = MutableStateFlow<OfflineMeetingSession?>(null)
    val reviewSession: StateFlow<OfflineMeetingSession?> = _reviewSession.asStateFlow()

    /** 回顾会话的可见消息（已过滤标记/系统提示）。 */
    private val _reviewMessages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val reviewMessages: StateFlow<List<MessageEntity>> = _reviewMessages.asStateFlow()

    init {
        viewModelScope.launch {
            _userProfile.value = userProfileDao.get()
            reload()
        }
    }

    private suspend fun reload() {
        val char = characterRepo.get(characterUuid) ?: return
        _character.value = char
        // Design M（图纸记录 §5.1·B3 收尾）：列表骨架仍从 marker 组装（见面一结束即显示）+ 摘要/简版徽章从**行**
        // override（注入宏已直读行、blob 冻结不再刷 → 回忆屏摘要必须走行）。无行（pending）→ 保 extractSessions 原值。
        offlineMeetingMemoryRepository.ensureSeeded(characterUuid)
        val rowBySession = offlineMeetingMemoryRepository.byCharacter(characterUuid).associateBy { it.sessionId }
        _sessions.value = extractor.extractSessions(char).map { s ->
            val row = rowBySession[s.id] ?: return@map s
            s.copy(
                summaryText = row.summary.ifEmpty { null },
                // 卷二 G1：instant（即时要点骨架）与 fallback 同属「简版」——徽章谓词只扩来源值不改形。
                usedFallbackSummary = row.sourceRaw == "fallback" ||
                    row.sourceRaw == OfflineSummaryRetryCoordinator.SOURCE_INSTANT,
            )
        }
    }

    /** 手动重试进行中的会话集合（驱动「简版」徽章转圈；状态在 coordinator 单源，批2 复核修 LOW#1）。 */
    val retryingSessionIds: StateFlow<Set<String>> = retryCoordinator.retryingSessionIds

    /** 手动重试某次见面的规则兜底摘要（绕过退避），完成后刷新让「简版」标识按新状态更新。 */
    fun retryFallback(sessionId: String) {
        viewModelScope.launch {
            retryCoordinator.manuallyRetry(sessionId)
            reload()
        }
    }

    fun openReview(session: OfflineMeetingSession) {
        _reviewSession.value = session
        viewModelScope.launch {
            _reviewMessages.value = loadReviewMessages(session.conversationUuid, session.id)
        }
    }

    fun closeReview() {
        _reviewSession.value = null
        _reviewMessages.value = emptyList()
    }

    /** 当前正在编辑的会话（null = 未打开编辑）。 */
    private val _editSession = MutableStateFlow<OfflineMeetingSession?>(null)
    val editSession: StateFlow<OfflineMeetingSession?> = _editSession.asStateFlow()

    fun openEdit(session: OfflineMeetingSession) {
        _editSession.value = session
    }

    fun closeEdit() {
        _editSession.value = null
    }

    /**
     * LLM 重新生成本次见面摘要（填入编辑框，不持久化；1:1 iOS regenerateSummary，temp 0.4）。
     * 无 MEMORY_SUMMARY 配置 / 无对话消息 / 调用失败 → null（UI 据此提示「生成失败」）。
     */
    suspend fun regenerateSummary(session: OfflineMeetingSession, editedLocation: String, editedActivity: String): String? {
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.MEMORY_SUMMARY) ?: return null
        val charName = _character.value?.name?.takeIf { it.isNotEmpty() } ?: "角色"
        val messages = messageRepo.offlineSessionMessages(session.conversationUuid, session.id)
        // 记录名字化（D-2·与 OfflineSummaryRetryCoordinator.userRecordLabel / buildUserPrompt.partner 同口径）：
        // 用户侧标签用昵称，无昵称 / 恰为「用户」→「对方」，绝不让「用户：」标签漏进喂 LLM 的记录。
        val nick = _userProfile.value?.nickname.orEmpty()
        val userRecordLabel = nick.trim().takeIf { it.isNotEmpty() && it != "用户" } ?: "对方"
        val record = buildMeetingConversationText(messages, charName, userRecordLabel)
        if (record.isEmpty()) return null
        val endMillis = messages.lastOrNull()?.timestamp ?: session.startMillis
        val user = OfflineMeetingSummarySchema.buildUserPrompt(
            characterName = charName,
            startText = DateFormatters.yearMonthDayHourMinute(session.startMillis),
            endText = hhmmFormatter.format(Instant.ofEpochMilli(endMillis).atZone(ZoneId.systemDefault())),
            durationText = session.durationText.removePrefix("约"),
            location = editedLocation,
            activity = editedActivity,
            messageCount = messages.size,
            conversationRecord = record,
            userName = _userProfile.value?.nickname.orEmpty(),
        )
        val response = runCatching {
            contextLog.completion(
                source = LogSource.OFFLINE_MEETING_MEMORY,
                characterName = charName,
                config = config,
                messages = listOf(ChatMessageDto(role = "user", content = user)),
                temperature = 0.4,
                responseFormat = ResponseFormatDto(type = "json_object"),
            )
        }.getOrNull() ?: return null
        return (OfflineMeetingSummarySchema.parseAndValidate(response) as? OfflineMeetingSummarySchema.ParseResult.Success)
            ?.draft?.summary
    }

    /**
     * 保存编辑：重写入场标记 payload 的地点/活动（保留 timeString/tensionSeed·供 marker 骨架显示）+
     * 把摘要写回该次见面的**行**（[OfflineMeetingMemoryRepository.updateEdited]·sourceRaw="manual"·刷 blob 缓存注入·图纸 §3.11）。
     */
    fun saveEdit(session: OfflineMeetingSession, newLocation: String, newActivity: String, newSummary: String) {
        viewModelScope.launch {
            val loc = newLocation.trim()
            val act = newActivity.trim()
            val summary = newSummary.trim()
            val now = System.currentTimeMillis()

            // 入场 marker payload 重写（地点/活动·§3.11 保留）：供 extractSessions 的 marker 骨架显示新地点/活动。
            val markerMsg = messageRepo.offlineSessionMessages(session.conversationUuid, session.id)
                .firstOrNull { it.messageKindRaw == MessageKind.OFFLINE_MARKER_START.raw }
            val payload = markerMsg?.let { OfflineMarkerStartPayload.parse(it.content) }
            if (markerMsg != null && payload != null) {
                val newContent = OfflineMarkerStartPayload(loc, act, payload.timeString, payload.tensionSeed).makeContent()
                messageDao.update(markerMsg.copy(content = newContent))
            }

            // 摘要写**行**（§3.11·行是真相源·Repository 内刷 blob 缓存注入）。先播种旧 blob 再按 sessionId 定位行；
            // 无行（罕见·尚未生成摘要就编辑）→ 建 sourceRaw="manual" 新行。
            offlineMeetingMemoryRepository.ensureSeeded(characterUuid)
            val existing = offlineMeetingMemoryRepository.byCharacter(characterUuid).firstOrNull { it.sessionId == session.id }
            if (existing != null) {
                offlineMeetingMemoryRepository.updateEdited(existing.uuid, loc, act, summary, now)
            } else {
                offlineMeetingMemoryRepository.upsertMeeting(
                    OfflineMeetingMemoryEntity(
                        uuid = java.util.UUID.randomUUID().toString(),
                        characterUuid = characterUuid,
                        conversationUuid = session.conversationUuid,
                        sessionId = session.id,
                        kindRaw = "meeting",
                        startedAtMillis = session.startMillis,
                        endedAtMillis = session.startMillis,
                        location = loc,
                        activity = act,
                        moodRaw = session.finalMood.orEmpty(),
                        initiatedByUser = session.initiatedByUser,
                        messageCount = 0,
                        summary = summary,
                        sourceRaw = "manual",
                        createdAtMillis = now,
                        updatedAtMillis = now,
                    ),
                )
            }

            reload()
            _editSession.value = null
        }
    }

    /** 该次见面的可见消息（1:1 iOS OfflineReviewView.loadMessages 过滤）：排除入场/离场标记 + 系统耳语（含旧版「准备出发」确认·均为 SYSTEM_HINT）。 */
    private suspend fun loadReviewMessages(conversationUuid: String, sessionId: String): List<MessageEntity> =
        messageRepo.offlineSessionMessages(conversationUuid, sessionId).filter { m ->
            !OfflineChatVisibility.isHiddenFromReview(MessageKind.fromRaw(m.messageKindRaw)) // 审计 S8 单源
        }
}

/**
 * 见面记忆摘要的「对话正文」装配（纯函数·便于单测）：结构化卡走单一事实源 [messageLlmSafeText] 脱敏
 * （礼物 / 红包信封 → 无金额·通话 / 入场离场标记 → 丢弃），格式 `<userLabel> | <角色>：<脱敏文本>`（用户侧
 * 标签由调用方名字化传入·无昵称 / 恰「用户」回退「对方」·D-2；**不带时间戳**——
 * 与自动 / 重试摘要的 [com.situ.aichat.prompt.memory.MemoryService.formatMessages] 路径有意不同，本手动重生成路径
 * 刻意省时间戳）。见面期主动送礼卡现随会话打线下标记（见 [com.situ.aichat.gift.ProactiveGiftExecutor]）会落进本
 * offlineSession → 必须脱敏，否则礼物 cost / 红包 amount 原始 JSON 漏进见面记忆摘要（喂 LLM + 用户可见可编辑）。
 */
internal fun buildMeetingConversationText(messages: List<MessageEntity>, characterName: String, userLabel: String): String =
    messages.mapNotNull { m ->
        val safe = messageLlmSafeText(m)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val role = if (m.roleRaw == "user") userLabel else characterName
        "$role：$safe"
    }.joinToString("\n")

/** 见面结束时刻「HH:mm」（§3.5 摘要事实行·系统时区·regenerateSummary 用）。 */
private val hhmmFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
