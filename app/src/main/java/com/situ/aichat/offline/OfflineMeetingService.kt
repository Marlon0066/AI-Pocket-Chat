package com.situ.aichat.offline

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.OfflineInviteJson
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.util.DateFormatters
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 线下见面状态机的**数据层核心**（10.2c-3c；1:1 iOS `ChatViewModel+ToolCalling` / `+Offline` / `+OfflineStateGuard`
 * 的 DB 部分）。所有进入/退出/续场都在 [AppDatabase.withTransaction] 内原子写（= iOS 单 `saveContext`，App 被杀
 * 不会卡在半状态）。
 *
 * **分层**：本服务只做 DB 编排（插标记/卡片消息、写线下状态字段、记角色日程）；「打断流式 + 触发 AI 回复」属
 * ViewModel 职责（线下回合 = 普通 [com.situ.aichat.ui.chat.ChatViewModel] runAssistantTurn，由进入/续场后触发），
 * 故进入/续场类方法返回 sessionId / Boolean 让 VM 知道随后触发回合。结构化「邀约/结束卡」由 [handleSuggestMeeting]
 * / [handleEndMeeting] 在流结束处分发（仿既有日历路径）。
 *
 * **AI_ENDED 是 iOS 死分支**（end_offline_meeting 工具只插确认卡、不直接 finalize；用户点卡片走 USER_ENDED）——
 * 枚举保留以备将来，实际只用 USER_ENDED / USER_ABORTED。
 */
@Singleton
class OfflineMeetingService @Inject constructor(
    private val db: AppDatabase,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val scheduleDao: ScheduleDao,
    private val userProfileDao: UserProfileDao,
) {

    /** 线下模式结束原因（1:1 iOS OfflineExitReason；AI_ENDED 当前死分支保留不用）。 */
    enum class ExitReason { AI_ENDED, USER_ENDED, USER_ABORTED }

    /** 邀约消息提取结果：地点 / 活动 / 完整心事种子（可能为空，1:1 iOS OfflineInviteExtract）。 */
    data class InviteExtract(val location: String, val activity: String, val hiddenTension: String?)

    // MARK: - 进入线下模式

    /**
     * 用户接受邀约卡（1:1 iOS acceptOfflineInvite）：提地点/活动/心事种子 → 进入。返回新 sessionId，
     * 已在线下 / 无会话则 null（调用方据此决定是否触发 AI 开场回合）。
     * [inviteMessageUuid] = 用户实际点的那张卡（卷一 D2）；不传则回落「最近一张」扫描口径。
     */
    suspend fun acceptOfflineInvite(conversationUuid: String, inviteMessageUuid: String? = null): String? {
        val extract = extractInviteInfo(conversationUuid, inviteMessageUuid)
        return enterOfflineMode(
            conversationUuid = conversationUuid,
            location = extract.location,
            activity = extract.activity,
            // 心事种子非空才注入（用户辅助/手动路径无种子，1:1 iOS）
            tensionSeed = extract.hiddenTension?.takeIf { it.isNotEmpty() },
            confirmContent = CONFIRM_ACCEPT,
        )
    }

    /** 用户在 + 菜单主动发起线下见面（1:1 iOS startManualOfflineMeeting，无心事种子）。返回新 sessionId。 */
    suspend fun startManualOfflineMeeting(conversationUuid: String, location: String, activity: String): String? =
        enterOfflineMode(conversationUuid, location, activity, tensionSeed = null, confirmContent = CONFIRM_MANUAL)

    /**
     * 「未来约定见面」到点赴约（Phase 10）：用约定真理源记下的地点 / 活动 / **心事种子**进入线下见面沉浸。与
     * [acceptOfflineInvite] 同 [enterOfflineMode] 核心，但数据直接来自约定（非从邀约卡提取）；心事种子非空才注入
     * （喂线下见面，用户不可见）。返回新 sessionId（调用方据此 markHonored + 触发 AI 开场）；已在线下 / 无会话 → null。
     */
    suspend fun startFromAppointment(conversationUuid: String, location: String, activity: String, tensionSeed: String?): String? =
        enterOfflineMode(
            conversationUuid = conversationUuid,
            location = location,
            activity = activity,
            tensionSeed = tensionSeed?.takeIf { it.isNotEmpty() },
            confirmContent = CONFIRM_ARRIVAL,
        )

    /**
     * 进入线下模式核心流程（1:1 iOS enterOfflineModeCore，同事务原子）：
     * 生成 sessionId → 插入场标记（AI 可见）→ 记角色日程 → 插用户确认 systemHint（触发开场）→ 写线下状态字段。
     * 已在线下 → 返回 null（幂等防重入，对齐 iOS guard !isInOfflineMode）。
     */
    private suspend fun enterOfflineMode(
        conversationUuid: String,
        location: String,
        activity: String,
        tensionSeed: String?,
        confirmContent: String,
    ): String? = db.withTransaction {
        val convo = conversationRepo.get(conversationUuid) ?: return@withTransaction null
        if (convo.isInOfflineMode) return@withTransaction null

        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val timeStr = DateFormatters.shortTime(now)

        // ① 入场标记消息（AI 可见人读文本，用于上下文理解 + 提 tensionSeed/地点）
        messageRepo.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "assistant",
                content = OfflineMarkerStartPayload(location, activity, timeStr, tensionSeed).makeContent(),
                timestamp = now,
                isOfflineMode = true,
                offlineSessionId = sessionId,
                messageKindRaw = MessageKind.OFFLINE_MARKER_START.raw,
            ),
        )

        // ② 记录到角色日程（isPhoneAvailable=false）·activity/location 用户可见（日程时间线）且回流日程 prompt → 用真名（B2）
        val userName = (userProfileDao.get()?.nickname ?: "").ifBlank { "用户" }
        recordOfflineScheduleEvent(convo.characterUuid, "与${userName}在$location$activity", "😊", "期待", isStart = true, now = now, userName = userName)

        // ③ 用户确认消息（systemHint，触发 AI 生成开场叙述）。时间戳 +1 保证严格晚于入场标记。
        val confirmTs = now + 1
        messageRepo.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "user",
                content = confirmContent,
                timestamp = confirmTs,
                isOfflineMode = true,
                offlineSessionId = sessionId,
                messageKindRaw = MessageKind.SYSTEM_HINT.raw,
            ),
        )

        // ④ 同事务写线下状态字段（flag+sessionId+清节拍+入场预览）——withTransaction 提交即落盘（= iOS saveContext）。
        conversationRepo.recordOfflineEntered(conversationUuid, sessionId, PREVIEW_ENTER, confirmTs)
        sessionId
    }

    // MARK: - 结束 / 续场

    /**
     * 统一的线下模式结束处理（1:1 iOS finalizeOfflineMode，同事务）：算时长 → 插离场标记 → 记角色日程 →
     * 标记待生成摘要（pendingOfflineSummarySessionId，10.2d 重试链消费）→ 清线下状态字段。
     * 非线下 → 返回 false（幂等防重复，对齐 iOS guard isInOfflineMode）。
     *
     * 注：iOS 在此前会先打断流式/分段投递——那是 ViewModel 职责（调用方在调本方法前 cancel 流式 job）。
     * 本方法只在事务内 set pendingOfflineSummarySessionId；见面摘要 LLM 提取（10.2d 重试链）由调用方
     * [com.situ.aichat.ui.chat.ChatViewModel.finalizeOffline] 在事务提交后触发——LLM 网络调用不能进 DB 事务。
     */
    suspend fun finalizeOfflineMode(conversationUuid: String, reason: ExitReason): Boolean = db.withTransaction {
        val convo = conversationRepo.get(conversationUuid) ?: return@withTransaction false
        if (!convo.isInOfflineMode) return@withTransaction false

        val sessionId = convo.currentOfflineSessionId
        val now = System.currentTimeMillis()
        val timeStr = DateFormatters.shortTime(now)

        // 见面时长：优先入场标记时间戳，否则首条 session 消息；都没有 → "一段时间"（1:1 iOS nil 分支）。
        val sessionMessages =
            if (!sessionId.isNullOrEmpty()) messageRepo.offlineSessionMessages(conversationUuid, sessionId) else emptyList()
        val startMillis = resolvedStartMillis(sessionMessages)
        val durationText = if (startMillis != null) durationText(startMillis, now) else "一段时间"

        // ② 离场标记消息（AI 可见，标 isOfflineMode 让其留在见面流、从正常聊天过滤掉）
        messageRepo.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "assistant",
                content = OfflineMarkerEndPayload(durationText, timeStr, reasonText(reason)).makeContent(),
                timestamp = now,
                isOfflineMode = true,
                offlineSessionId = sessionId,
                messageKindRaw = MessageKind.OFFLINE_MARKER_END.raw,
            ),
        )

        val userName = (userProfileDao.get()?.nickname ?: "").ifBlank { "用户" }
        // 卷一 F2：结束日程事件取角色**此刻的真实心情**（见面中照常回写 lastMood*），不再写死「😌 满足」——
        // 一场吵崩的见面在日程时间线上显示「满足」是硬穿帮。心情空（从未解析到）→ 回落原常量对。
        val liveMood = characterRepo.get(convo.characterUuid)
        val endMoodEmoji = liveMood?.lastMoodEmoji?.takeIf { it.isNotBlank() } ?: "😌"
        val endMoodText = liveMood?.lastMoodText?.takeIf { it.isNotBlank() } ?: "满足"
        recordOfflineScheduleEvent(
            convo.characterUuid, "线下见面结束（$durationText）", endMoodEmoji, endMoodText,
            isStart = false, now = now, userName = userName,
        )

        // 标记待重试摘要 + 清线下状态（同事务原子，App 被杀不会卡在线下模式）。LLM 提取由 ChatViewModel.finalizeOffline
        // 在事务提交后触发（OfflineSummaryRetryCoordinator.retryOne，退避表 60/300/1800/7200/86400s + 规则兜底，10.2d）。
        conversationRepo.recordOfflineExited(conversationUuid, pendingSummarySessionId = sessionId, PREVIEW_EXIT, now)
        true
    }

    /**
     * 用户点「再待一会儿」后继续见面（1:1 iOS continueOfflineMeeting）：强制 allow_end→false + 插续场 systemHint
     * + 刷新活动时间。返回 true 让 VM 触发 AI 回复；非线下 → false。
     */
    suspend fun continueOfflineMeeting(conversationUuid: String): Boolean = db.withTransaction {
        val convo = conversationRepo.get(conversationUuid) ?: return@withTransaction false
        if (!convo.isInOfflineMode) return@withTransaction false
        val now = System.currentTimeMillis()

        // ① 强制 allow_end → false，防止 AI 立刻再次提议告别。R5#0：用与解析端同一套容错逻辑的单源字段写入器
        // （SceneProgressService.forceFieldValue），不再用字面 contains/replace——LLM 漏空格/全角冒号/写 True 时
        // 旧逻辑静默改不动，「再待一会儿」失效。仅在确有改动时写库（无 allow_end 行或本就 false → 原样不写）。
        val progress = convo.currentSceneProgress
        val updated = SceneProgressService.forceFieldValue(progress, "allow_end", "false")
        if (updated != progress) {
            conversationRepo.updateSceneProgress(conversationUuid, updated)
        }

        // ② 续场 systemHint（AI 可见，用户不可见）。
        messageRepo.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "user",
                content = CONTINUE_HINT,
                timestamp = now,
                isOfflineMode = true,
                offlineSessionId = convo.currentOfflineSessionId,
                messageKindRaw = MessageKind.SYSTEM_HINT.raw,
            ),
        )
        conversationRepo.touchLastMessageDate(conversationUuid, now)
        true
    }

    /**
     * D3 时间感知重进（2026-07-07 拍板）：距当前线下 session 最后一条消息的毫秒数；非线下 / 无 session /
     * 无消息 → null。供未答恢复分档（[OfflineReturnPolicy]）与恢复弹窗「继续见面」衔接共用。
     */
    suspend fun offlineAwayMs(conversationUuid: String, now: Long = System.currentTimeMillis()): Long? {
        val convo = conversationRepo.get(conversationUuid) ?: return null
        if (!convo.isInOfflineMode) return null
        val sessionId = convo.currentOfflineSessionId
        if (sessionId.isNullOrEmpty()) return null
        val last = messageRepo.offlineSessionMessages(conversationUuid, sessionId).lastOrNull() ?: return null
        return (now - last.timestamp).coerceAtLeast(0L)
    }

    /**
     * D3 时间感知重进：插「用户离开片刻后归来」隐藏提示（AI 可见、用户不可见，同 [continueOfflineMeeting]
     * 的续场 hint 形态），随后由调用方触发一拍回合。非线下 / 无 session → false（幂等防误插）。
     */
    suspend fun insertReturnAfterAwayHint(
        conversationUuid: String,
        awayMinutes: Long,
    ): Boolean = db.withTransaction {
        val convo = conversationRepo.get(conversationUuid) ?: return@withTransaction false
        if (!convo.isInOfflineMode) return@withTransaction false
        val sessionId = convo.currentOfflineSessionId
        if (sessionId.isNullOrEmpty()) return@withTransaction false
        val now = System.currentTimeMillis()
        val awayDesc = "了大约 $awayMinutes 分钟"
        messageRepo.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "user",
                content = RETURN_HINT_PREFIX + awayDesc + RETURN_HINT_SUFFIX,
                timestamp = now,
                isOfflineMode = true,
                offlineSessionId = sessionId,
                messageKindRaw = MessageKind.SYSTEM_HINT.raw,
            ),
        )
        conversationRepo.touchLastMessageDate(conversationUuid, now)
        true
    }

    /**
     * 用户打开「发起见面」界面又取消 → 悄悄告诉 AI（用户看不到，1:1 iOS handleMeetingCancelHint）。
     * 非线下普通 systemHint；返回 true 让 VM 触发 AI 回复。
     */
    suspend fun insertMeetingCancelHint(conversationUuid: String): Boolean = db.withTransaction {
        conversationRepo.get(conversationUuid) ?: return@withTransaction false
        val now = System.currentTimeMillis()
        messageRepo.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "user",
                content = CANCEL_HINT,
                timestamp = now,
                messageKindRaw = MessageKind.SYSTEM_HINT.raw,
            ),
        )
        conversationRepo.touchLastMessageDate(conversationUuid, now)
        true
    }

    // MARK: - 流结束处分发：邀约 / 结束确认卡

    /**
     * AI 提议见面（1:1 iOS handleOfflineMeetingAction .suggestMeeting）：插一张邀约卡（普通聊天可见，**非**线下消息）。
     * 卡片本身就是完整回复，用户点「好呀」才走 [acceptOfflineInvite] 真正进入。
     */
    suspend fun handleSuggestMeeting(conversationUuid: String, action: OfflineMeetingAction, emotionTag: String?) {
        db.withTransaction {
            val now = System.currentTimeMillis()
            val json = OfflineInviteJson.makeInvite(
                location = action.location ?: "",
                activity = action.activity ?: "",
                invitation = action.invitation ?: "",
                tensionHint = action.tensionHint,
                hiddenTension = action.hiddenTension,
            )
            messageRepo.upsert(
                MessageEntity(
                    messageUUID = UUID.randomUUID().toString(),
                    conversationUuid = conversationUuid,
                    roleRaw = "assistant",
                    content = json,
                    timestamp = now,
                    emotionTag = emotionTag,
                    messageKindRaw = MessageKind.OFFLINE_INVITE_CARD.raw,
                ),
            )
            // 预览「☕ 活动」（1:1 iOS "☕ \(activity ?? location ?? "[线下邀约]")"；activity 是 suggest 工具 required 参数，正常非空）。
            val previewBody = action.activity?.takeIf { it.isNotBlank() }
                ?: action.location?.takeIf { it.isNotBlank() }
                ?: "[线下邀约]"
            conversationRepo.recordLastMessage(conversationUuid, "☕ $previewBody", "assistant", now)
        }
    }

    /**
     * AI 提议结束（1:1 iOS handleOfflineMeetingAction .endMeeting）：插结束确认卡（**不直接退出**），
     * 用户点「结束见面」才走 [finalizeOfflineMode]。卡标 isOfflineMode+sessionId（留在见面流、从正常聊天过滤）。
     */
    suspend fun handleEndMeeting(conversationUuid: String, action: OfflineMeetingAction, emotionTag: String?) {
        db.withTransaction {
            val convo = conversationRepo.get(conversationUuid) ?: return@withTransaction
            val now = System.currentTimeMillis()
            messageRepo.upsert(
                MessageEntity(
                    messageUUID = UUID.randomUUID().toString(),
                    conversationUuid = conversationUuid,
                    roleRaw = "assistant",
                    content = OfflineInviteJson.makeEnd(finalMood = action.finalMood, farewell = action.farewell),
                    timestamp = now,
                    emotionTag = emotionTag,
                    isOfflineMode = true,
                    offlineSessionId = convo.currentOfflineSessionId,
                    messageKindRaw = MessageKind.OFFLINE_END_CARD.raw,
                ),
            )
            conversationRepo.recordLastMessage(conversationUuid, PREVIEW_END_CARD, "assistant", now)
        }
    }

    /** 改写邀约/结束卡的 responded 状态（accepted/declined/continued）——用户点卡片按钮后置灰（1:1 iOS responded）。 */
    suspend fun markInviteResponded(messageUuid: String, responded: String) {
        val msg = messageRepo.get(messageUuid) ?: return
        val data = OfflineInviteJson.parse(msg.content) ?: return
        messageRepo.upsert(msg.copy(content = OfflineInviteJson.encode(data.copy(responded = responded))))
    }

    // MARK: - 恢复 / 状态守护

    /**
     * 是否需弹「继续/结束」异常恢复提示（1:1 iOS shouldShowOfflineRecoveryPrompt）：读最后一条线下消息时间，
     * 委托纯决策 [OfflineStateGuard.shouldShowRecoveryPrompt]（>10min/无消息→true）。
     */
    suspend fun shouldShowRecoveryPrompt(conversationUuid: String, now: Long = System.currentTimeMillis()): Boolean {
        val convo = conversationRepo.get(conversationUuid) ?: return false
        val sessionId = convo.currentOfflineSessionId
        val lastOfflineAt = if (!sessionId.isNullOrEmpty()) {
            messageRepo.offlineSessionMessages(conversationUuid, sessionId).lastOrNull()?.timestamp
        } else {
            null
        }
        return OfflineStateGuard.shouldShowRecoveryPrompt(convo.isInOfflineMode, sessionId, lastOfflineAt, now)
    }

    /**
     * 幂等修复线下脏状态（1:1 iOS ensureOfflineStateConsistency）：纯决策 [OfflineStateGuard.decide] → 落库修复。
     * 返回是否做了修复（在 onAppear/onResume 静默调用）。
     */
    suspend fun ensureStateConsistency(conversationUuid: String): Boolean {
        val convo = conversationRepo.get(conversationUuid) ?: return false
        val sessionId = convo.currentOfflineSessionId
        // 案例 ③④ 需要 session 消息 kind；①② 不必查库（decide 内部先返回）。
        val kinds = if (convo.isInOfflineMode && !sessionId.isNullOrBlank()) {
            messageRepo.offlineSessionMessages(conversationUuid, sessionId).map { MessageKind.fromRaw(it.messageKindRaw) }
        } else {
            emptyList()
        }
        return when (OfflineStateGuard.decide(convo.isInOfflineMode, sessionId, kinds)) {
            OfflineStateRepair.NONE -> false
            OfflineStateRepair.CLEAR_SESSION_ID -> {
                conversationRepo.clearOfflineSessionId(conversationUuid)
                true
            }
            OfflineStateRepair.FULL_RESET -> {
                conversationRepo.resetOfflineState(conversationUuid)
                true
            }
        }
    }

    // MARK: - 内部

    /**
     * 从对话最近消息中提取邀约卡的地点/活动/心事种子（1:1 iOS extractInviteInfo，走 kind 判定不靠字符串）。
     *
     * [inviteMessageUuid] 非空（卷一 D2）= **用户点的就是这张卡**：直接取该条，不再「扫最近一张」——
     * 用户往回翻点旧卡时，扫描口径会把他带进另一场约（地点/活动全错）。该条已删 / 解析失败 / 不是邀约卡
     * → 回落现状扫描路径（E7）。不传该参 = 行为与改动前逐字一致。
     */
    suspend fun extractInviteInfo(conversationUuid: String, inviteMessageUuid: String? = null): InviteExtract {
        val byUuid = inviteMessageUuid
            ?.let { messageRepo.get(it) }
            ?.takeIf { it.messageKindRaw == MessageKind.OFFLINE_INVITE_CARD.raw }
            ?.let { OfflineInviteJson.parse(it.content) }
            ?.takeIf { it.type == OfflineInviteJson.TYPE_INVITE }
        if (byUuid != null) {
            return InviteExtract(
                location = byUuid.location ?: "某个地方",
                activity = byUuid.activity ?: "一起出去",
                hiddenTension = byUuid.hiddenTension,
            )
        }
        val recent = messageRepo.recentChronological(conversationUuid, INVITE_SCAN_LIMIT)
        val invite = recent.lastOrNull { it.messageKindRaw == MessageKind.OFFLINE_INVITE_CARD.raw }
            ?.let { OfflineInviteJson.parse(it.content) }
            ?.takeIf { it.type == OfflineInviteJson.TYPE_INVITE }
        // iOS 用 `?? 默认`（仅 nil 兜底，空串保留）——suggest 工具 required location/activity，正常非空。
        return InviteExtract(
            location = invite?.location ?: "某个地方",
            activity = invite?.activity ?: "一起出去",
            hiddenTension = invite?.hiddenTension,
        )
    }

    /** 见面起始时间：入场标记时间戳优先，否则首条 session 消息（1:1 iOS resolvedOfflineModeStartDate）；无消息 → null。 */
    private fun resolvedStartMillis(sessionMessages: List<MessageEntity>): Long? =
        sessionMessages.firstOrNull { it.messageKindRaw == MessageKind.OFFLINE_MARKER_START.raw }?.timestamp
            ?: sessionMessages.firstOrNull()?.timestamp

    /** 在角色日程中记录线下见面事件（1:1 iOS recordOfflineScheduleEvent）：找/建今日日程 + 追加 userInteraction 事件。 */
    private suspend fun recordOfflineScheduleEvent(
        characterUuid: String,
        activity: String,
        moodEmoji: String,
        moodText: String,
        isStart: Boolean,
        now: Long,
        userName: String,
    ) {
        val character = characterRepo.get(characterUuid)
        val todayStart = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val schedule = scheduleDao.scheduleFor(characterUuid, todayStart) ?: run {
            val created = CharacterDailyScheduleEntity(
                uuid = UUID.randomUUID().toString(),
                characterUuid = characterUuid,
                date = todayStart,
                cityName = character?.cityName,
            )
            scheduleDao.insertSchedule(created)
            created
        }
        // 追加到 sortOrder 最大值 +1，避免默认 0 被排到「凌晨睡觉」旁污染时间线（1:1 iOS 2026-04-24 根因修复）。
        val nextSortOrder = (scheduleDao.eventsForSchedule(schedule.uuid).maxOfOrNull { it.sortOrder } ?: -1) + 1
        scheduleDao.insertEvents(
            listOf(
                ScheduleEventEntity(
                    uuid = UUID.randomUUID().toString(),
                    scheduleUuid = schedule.uuid,
                    startTime = now,
                    endTime = now,
                    periodLabel = if (isStart) "线下见面开始" else "线下见面结束",
                    location = "与${userName}的见面地点",
                    activity = activity,
                    moodEmoji = moodEmoji,
                    moodText = moodText,
                    // 卷一 F2：结束分支不再写死「今天见面很开心」（同上，吵崩的见面照样「很开心」=穿帮）；
                    // 开始分支文案零碰。
                    innerThought = if (isStart) "终于要和ta见面了" else "和${userName}的见面结束了",
                    isPhoneAvailable = false,
                    eventTypeRaw = EVENT_TYPE_USER_INTERACTION,
                    sortOrder = nextSortOrder,
                ),
            ),
        )
    }

    companion object {
        private const val CONFIRM_ACCEPT = "（接受了邀约，准备出发）"
        private const val CONFIRM_MANUAL = "（主动发起了见面，准备出发）"
        private const val CONFIRM_ARRIVAL = "（如约赴会，来见你了）"
        private const val CANCEL_HINT = "（用户打开了「发起见面」界面，看起来想约你出去，但犹豫了一下取消了）"
        // 续场 hint：1:1 iOS，"留下来" 用全角弯引号 “ ”（字节对齐）。
        private const val CONTINUE_HINT =
            "（对方没有转身离开。告别的话已经说了，但脚步没有迈出去——角色感觉到了这一点。" +
                "从这个意外的“留下来”里，找到一个新的瞬间、新的话题、或新的发现，让见面自然延续。）"
        // D3 归来 hint：中段（"了大约 X 分钟"）由 insertReturnAfterAwayHint 拼接。
        private const val RETURN_HINT_PREFIX = "（用户刚才短暂离开"
        private const val RETURN_HINT_SUFFIX =
            "，现在回来了。请以角色身份自然地接续场景——可以不动声色，也可以轻轻回应这段空档" +
                "（比如注意到对方刚才走神、去了趟洗手间）；如果合适可以用 [时间：…] 标记流逝。" +
                "只推进一小步，然后照常把对话空间留给用户。）"
        // §5③（2026-08-26 过审·选 A）：会话列表在见面期间显示「正在见面中…」——活预览，不再是机械的模式提示。
        private const val PREVIEW_ENTER = "正在见面中…"
        private const val PREVIEW_EXIT = "（线下见面结束）"
        private const val PREVIEW_END_CARD = "[线下结束]"
        private const val EVENT_TYPE_USER_INTERACTION = "userInteraction"
        private const val INVITE_SCAN_LIMIT = 60

        /**
         * 见面时长文案（纯函数，1:1 iOS finalizeOfflineMode/calculateOfflineDuration）：
         * <1 分钟→「不到1分钟」；<60 分钟→「约X分钟」；否则「约X小时Y分钟」（Y=0 省略分钟）。
         * 分钟数 = 秒差整除 60（向零截断，= iOS `Int(timeIntervalSince/60)`）。
         */
        internal fun durationText(startMillis: Long, endMillis: Long): String {
            val minutes = ((endMillis - startMillis) / 60_000L).toInt()
            return when {
                minutes < 1 -> "不到1分钟"
                minutes < 60 -> "约${minutes}分钟"
                else -> {
                    val hours = minutes / 60
                    val rem = minutes % 60
                    if (rem > 0) "约${hours}小时${rem}分钟" else "约${hours}小时"
                }
            }
        }

        /** 结束原因文案（纯函数，1:1 iOS finalizeOfflineMode reasonText switch）。 */
        internal fun reasonText(reason: ExitReason): String = when (reason) {
            ExitReason.AI_ENDED -> "你们自然地结束了这次见面"
            ExitReason.USER_ENDED -> "用户主动结束了这次见面"
            ExitReason.USER_ABORTED -> "这次见面因为中断而结束（用户选择不继续）"
        }
    }
}
