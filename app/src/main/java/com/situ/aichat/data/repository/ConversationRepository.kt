package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.ConversationWithWallpaper
import com.situ.aichat.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val dao: ConversationDao,
) {
    fun observeActive(): Flow<List<ConversationEntity>> = dao.observeActive()
    /** 活跃会话一次性快照（桌面小组件 13.9a：选主对话用，无需挂 Flow 观察者）。 */
    suspend fun activeSnapshot(): List<ConversationEntity> = dao.activeSnapshot()
    /** 已归档会话（聊天列表页归档入口 / 归档页用，13.5 chat-ui-11）。 */
    fun observeArchived(): Flow<List<ConversationEntity>> = dao.observeArchived()
    /** 归档会话数（K5）：只判「>0 显示入口」时用它，别订全量实体流。 */
    fun observeArchivedCount(): Flow<Int> = dao.observeArchivedCount()
    /** 未读消息总数（非归档），底部「聊天」Tab 角标用（nav-shell-2）。 */
    fun observeTotalUnread(): Flow<Int> = dao.observeTotalUnread()

    /** 有「简版」见面摘要兜底的角色 uuid（联系人头像红点·14.1b），Flow 实时刷新。 */
    fun observeCharacterUuidsWithOfflineFallback(): Flow<List<String>> = dao.observeCharacterUuidsWithOfflineFallback()

    // MARK: - 聊天列表页操作（13.5 chat-ui-11）。

    /** 置顶/取消置顶（1:1 iOS swipe Pin/Unpin）。 */
    suspend fun setPinned(conversationUuid: String, pinned: Boolean) = dao.setPinned(conversationUuid, pinned)

    /** 归档/取消归档（1:1 iOS swipe Archive/Unarchive）。 */
    suspend fun setArchived(conversationUuid: String, archived: Boolean) = dao.setArchived(conversationUuid, archived)

    /** 删会话（FK CASCADE 连带删消息行；磁盘媒体须先经 [ConversationMediaCleaner] 清理，对齐 iOS 删序）。 */
    suspend fun deleteById(conversationUuid: String) = dao.deleteById(conversationUuid)
    fun observe(uuid: String): Flow<ConversationEntity?> = dao.observeByUuid(uuid)
    /** 过渡丝滑化：会话 + 壁纸路径合并查询（一次 JOIN·同帧返回），消除壁纸/状态栏/手势条相对内容的"晚一拍"。 */
    fun observeWithWallpaper(uuid: String): Flow<ConversationWithWallpaper?> = dao.observeWithWallpaper(uuid)
    suspend fun get(uuid: String): ConversationEntity? = dao.getByUuid(uuid)

    /** 未答恢复候选会话（最后一条是用户消息、未归档、非线下；1:1 iOS lastMessageRole=="user" 扫描）。 */
    suspend fun conversationsAwaitingReply(): List<ConversationEntity> = dao.conversationsAwaitingReply()

    /** 某角色的全部会话（创建序）。供通知物化挑会话（[com.situ.aichat.notification.StreakNotificationBridgeService]）。 */
    suspend fun getByCharacter(characterUuid: String): List<ConversationEntity> = dao.getByCharacter(characterUuid)

    /**
     * 某角色最近活跃的会话（1:1 iOS `ProactiveGiftExecutor.recentActiveConversation`：按 `lastMessageDate ?? creationDate`
     * 降序取首）。主动送礼把礼物卡 + 陪送文案投到此会话；无任何会话 → null（执行器据此 skip）。
     */
    suspend fun recentActiveConversationFor(characterUuid: String): ConversationEntity? =
        dao.getByCharacter(characterUuid).maxByOrNull { it.lastMessageDate ?: it.creationDate }

    /**
     * 新建一条「为通知预留」的会话并返回（对齐 iOS ensureConversation 找不到时新建 isReservedForNotifications=true）。
     * 仅当某角色尚无任何会话、却要物化它的通知时才会触发。
     */
    suspend fun createReserved(characterUuid: String, title: String): ConversationEntity {
        val conv = ConversationEntity(
            uuid = UUID.randomUUID().toString(),
            title = title,
            characterUuid = characterUuid,
            creationDate = System.currentTimeMillis(),
            isReservedForNotifications = true,
        )
        dao.upsert(conv)
        return conv
    }

    /**
     * 把一条通知物化为会话消息后，更新会话末条信息（对齐 iOS materialize 分支）：清「预留」标记、
     * 写 lastMessage*；正看着该会话则 markRead，否则未读数 +1。[preview] 取前 60 字（同 iOS prefix(60)）。
     *
     * 13.8·B1 复核 MED：列表行写入**单调化**。通知栏直接回复会让用户/AI 消息先以更新时间戳落库（不触发物化），
     * 之后回前台才把原主动消息按其较早 deliveredAt 物化——若无条件覆写会把列表行「时间倒退」（陈旧预览 + 排序回退
     * + 虚假未读 + 角标膨胀）。故据 [materializationRowDecision]：仅当本次时间戳不早于现有 lastMessageDate 才覆写末条列
     * 并 +1 未读；更早（回退物化）则只清「预留」标记、保留更新的行、不加未读。正常前台物化（主动消息即最新活动）行为不变。
     */
    suspend fun applyMaterialization(
        conversationUuid: String,
        preview: String,
        timestamp: Long,
        markReadNow: Boolean,
    ) {
        val c = dao.getByUuid(conversationUuid) ?: return
        val decision = materializationRowDecision(c.lastMessageDate, timestamp, markReadNow)
        val base = if (decision.overwriteColumns) {
            c.copy(
                isReservedForNotifications = false,
                lastMessageDate = timestamp,
                lastMessagePreview = preview.take(60),
                lastMessageRole = "assistant",
            )
        } else {
            c.copy(isReservedForNotifications = false) // 回退物化：保留更新的末条列，仅清预留标记
        }
        dao.upsert(
            when {
                decision.markRead -> base.copy(lastReadDate = System.currentTimeMillis(), cachedUnreadCount = 0)
                decision.bumpUnread -> base.copy(cachedUnreadCount = c.cachedUnreadCount + 1)
                else -> base // 回退物化且非在看：不加虚假未读（用户已在更新活动里）
            },
        )
    }

    /** Minimal slice: one conversation per character; create on first use. */
    suspend fun getOrCreateForCharacter(characterUuid: String, title: String): String {
        dao.getByCharacter(characterUuid).firstOrNull()?.let { return it.uuid }
        val uuid = UUID.randomUUID().toString()
        dao.upsert(
            ConversationEntity(
                uuid = uuid,
                title = title,
                characterUuid = characterUuid,
                creationDate = System.currentTimeMillis(),
            ),
        )
        return uuid
    }

    /** 定向三列更新（审计 R2）：不整行 RMW，与并发写入的 mood/voiceRounds 等其他列互不覆写；会话不存在 = 无操作（与旧行为一致）。 */
    suspend fun recordLastMessage(conversationUuid: String, preview: String, role: String, timestamp: Long) {
        dao.updateLastMessageSnapshot(conversationUuid, preview, role, timestamp)
    }

    /**
     * 整会话删空后清「最后一条」快照：lastMessageDate=null → 退出活跃聊天列表（对齐 iOS predicate
     * `lastMessageDate != nil`，= 无消息的会话不在列表占位）。删消息重算路径（[com.situ.aichat.ui.chat.refreshConversationLastMessage]）
     * 在删后无任何可见消息时调用。定向三列更新同 [recordLastMessage]（审计 R2）。
     */
    suspend fun clearLastMessage(conversationUuid: String) {
        dao.updateLastMessageSnapshot(conversationUuid, "", "", null)
    }

    suspend fun markRead(conversationUuid: String) {
        val c = dao.getByUuid(conversationUuid) ?: return
        dao.upsert(c.copy(lastReadDate = System.currentTimeMillis(), cachedUnreadCount = 0))
    }

    /** 写回本轮解析到的情绪（M04 mood 显示，对齐 iOS conversation.mood*）。 */
    suspend fun recordMood(conversationUuid: String, emoji: String, text: String, colorName: String) {
        val c = dao.getByUuid(conversationUuid) ?: return
        dao.upsert(c.copy(moodEmoji = emoji, moodText = text, moodColorName = colorName))
    }

    /** 语音回复轮次计数更新（P10.1c，对齐 iOS conversation.voiceRoundsSinceLastVoice/voiceNextThreshold）。 */
    suspend fun updateVoiceRounds(conversationUuid: String, rounds: Int, threshold: Int) =
        dao.updateVoiceRounds(conversationUuid, rounds, threshold)

    /**
     * 记录一次记忆总结的结果（双轨判定用，对齐 iOS performMemorySummary）。
     * 成功：写成功时间轨 + 清空失败时间戳；失败：写失败短冷却起点。两者都更新 attempt（老字段，仅历史）。
     * 定向列 UPDATE（图纸件⑥·PITFALLS §1b）：原「getByUuid→整行 copy→upsert」会把并发列打回旧值；
     * 「会话不存在则静默返回」的语义由 UPDATE 零命中天然等价。
     */
    suspend fun recordMemorySummaryResult(conversationUuid: String, success: Boolean, now: Long) {
        if (success) dao.recordMemorySummarySuccess(conversationUuid, now)
        else dao.recordMemorySummaryFailure(conversationUuid, now)
    }

    /** 记忆整理遇阻计数流（记忆护栏第二层 MG-U1）：>0 = 资料页共同记忆卡显示遇阻状态条。 */
    fun observeMemorySummaryBlockedCount(characterUuid: String): Flow<Int> =
        dao.observeMemorySummaryBlockedCount(characterUuid)

    /** 遇阻会话列表（「立即整理」结果回写用，谓词同上）。 */
    suspend fun memorySummaryBlockedByCharacter(characterUuid: String): List<ConversationEntity> =
        dao.memorySummaryBlockedByCharacter(characterUuid)

    /** 某角色最新未归档会话（手动整理时作短期窗口排除锚点）。 */
    suspend fun latestActiveForCharacter(characterUuid: String): ConversationEntity? =
        dao.latestActiveForCharacter(characterUuid)

    /**
     * 记录一次未来约定见面识别扫描的结果（双轨判定用，仿 [recordMemorySummaryResult]）。
     * 成功：写成功时间轨 + 清失败短冷却；失败：写失败短冷却起点。定向单列更新，避免整行 copy 覆写并发列。
     */
    suspend fun recordMeetingScanResult(conversationUuid: String, success: Boolean, now: Long) {
        if (success) dao.markMeetingScanSuccess(conversationUuid, now) else dao.markMeetingScanFailure(conversationUuid, now)
    }

    /**
     * 记录一次「惦记的事」扫描的结果（双轨判定用·活人感一期 P2·仿 [recordMeetingScanResult]）。
     * 成功：写成功时间轨 + 清失败短冷却；失败：写失败短冷却起点。定向单列更新，避免整行 copy 覆写并发列。
     */
    suspend fun recordOpenLoopScanResult(conversationUuid: String, success: Boolean, now: Long) {
        if (success) dao.markOpenLoopScanSuccess(conversationUuid, now) else dao.markOpenLoopScanFailure(conversationUuid, now)
    }

    // MARK: - 线下模式状态（P10.2c-3c，对齐 iOS enterOfflineModeCore / finalizeOfflineMode / 状态守护）

    /**
     * 进入线下模式（同事务原子写 flag+sessionId+清节拍 + 入场预览/活动，对齐 iOS enterOfflineModeCore）。
     * 由 [com.situ.aichat.offline.OfflineMeetingService] 在 withTransaction 内调用。
     */
    suspend fun recordOfflineEntered(conversationUuid: String, sessionId: String, preview: String, timestamp: Long) {
        val c = dao.getByUuid(conversationUuid) ?: return
        dao.upsert(
            c.copy(
                isInOfflineMode = true,
                currentOfflineSessionId = sessionId,
                currentSceneProgress = "",
                lastMessagePreview = preview,
                lastMessageRole = "user",
                lastMessageDate = timestamp,
            ),
        )
    }

    /**
     * 退出线下模式（同事务原子清 flag/sessionId/节拍 + 标记待生成摘要 + 离场预览，对齐 iOS finalizeOfflineMode）。
     * [pendingSummarySessionId] = 待生成见面摘要的 session（10.2d 重试链消费）。
     */
    suspend fun recordOfflineExited(
        conversationUuid: String,
        pendingSummarySessionId: String?,
        preview: String,
        timestamp: Long,
    ) {
        val c = dao.getByUuid(conversationUuid) ?: return
        dao.upsert(
            c.copy(
                isInOfflineMode = false,
                currentOfflineSessionId = null,
                currentSceneProgress = "",
                pendingOfflineSummarySessionId = pendingSummarySessionId,
                lastMessagePreview = preview,
                lastMessageRole = "assistant",
                lastMessageDate = timestamp,
            ),
        )
    }

    /** 写回节拍状态（SceneProgress 协调器落库用，对齐 iOS conversation.currentSceneProgress）。 */
    suspend fun updateSceneProgress(conversationUuid: String, progress: String) {
        val c = dao.getByUuid(conversationUuid) ?: return
        dao.upsert(c.copy(currentSceneProgress = progress))
    }

    /**
     * 写回场内前情提要三列（记忆改造二期·部件⑤·图纸 §3.2-B）——转发 DAO 的**列级** UPDATE，
     * **绝不**仿 [updateSceneProgress] 的整行 copy-upsert（那会在回合尾覆写并发列·D1 教训）。
     */
    suspend fun updateInSceneRecap(conversationUuid: String, text: String, sessionKey: String, untilMillis: Long) =
        dao.updateInSceneRecap(conversationUuid, text, sessionKey, untilMillis)

    /** 仅刷新「最后活动时间」（用户不可见的 systemHint 触发后不改预览，对齐 iOS 仅设 lastMessageDate）。 */
    suspend fun touchLastMessageDate(conversationUuid: String, timestamp: Long) {
        val c = dao.getByUuid(conversationUuid) ?: return
        dao.upsert(c.copy(lastMessageDate = timestamp))
    }

    /** 脏状态守护：仅清残留 sessionId（flag=false 但 sessionId 非空，iOS orphanSession）。 */
    suspend fun clearOfflineSessionId(conversationUuid: String) {
        val c = dao.getByUuid(conversationUuid) ?: return
        dao.upsert(c.copy(currentOfflineSessionId = null))
    }

    /** 脏状态守护：整体重置线下字段（flag+sessionId+节拍，iOS resetOfflineState）。 */
    suspend fun resetOfflineState(conversationUuid: String) {
        val c = dao.getByUuid(conversationUuid) ?: return
        dao.upsert(c.copy(isInOfflineMode = false, currentOfflineSessionId = null, currentSceneProgress = ""))
    }

    // MARK: - 见面摘要重试链退避/兜底状态（10.2d，对齐 iOS OfflineSummaryRetryCoordinator + ChatViewModel+ToolCalling）

    /** 记录本次摘要尝试时间（成功/失败都调，退避窗口判断用）。 */
    suspend fun updateOfflineSummaryLastAttemptAt(conversationUuid: String, now: Long) =
        dao.updateOfflineSummaryLastAttemptAt(conversationUuid, now)

    /** 摘要失败计数 +1（原子自增）。 */
    suspend fun incrementOfflineSummaryFailCount(conversationUuid: String) =
        dao.incrementOfflineSummaryFailCount(conversationUuid)

    /** 成功 / 兜底后清空 pending 三字段（sessionId/failCount/lastAttemptAt）。 */
    suspend fun clearPendingOfflineSummary(conversationUuid: String) =
        dao.clearPendingOfflineSummary(conversationUuid)

    /** 所有待生成见面摘要的会话（全局扫描重试，10.2d-3）。 */
    suspend fun conversationsWithPendingOfflineSummary(): List<ConversationEntity> =
        dao.conversationsWithPendingOfflineSummary()

    /** 所有有 fallback 记录的会话（24h 低频自愈，10.2d-3）。 */
    suspend fun conversationsWithOfflineFallback(): List<ConversationEntity> =
        dao.conversationsWithOfflineFallback()

    /** 手动/自愈重试：把 sessionId 重塞回 pending + 清零计数/时间戳（绕过退避，1:1 iOS manuallyRetry）。 */
    suspend fun restorePendingOfflineSummary(conversationUuid: String, sessionId: String) =
        dao.restorePendingOfflineSummary(conversationUuid, sessionId)

    /** 把 sessionId 追加进 fallback 列表（逗号分隔，去重；1:1 iOS appendFallbackSessionId）。 */
    suspend fun appendFallbackSessionId(conversationUuid: String, sessionId: String) {
        val c = dao.getByUuid(conversationUuid) ?: return
        val ids = c.offlineSummaryFallbackSessionIds.split(",").filter { it.isNotEmpty() }
        if (sessionId in ids) return
        dao.updateOfflineSummaryFallbackIds(conversationUuid, (ids + sessionId).joinToString(","))
    }

    /** 从 fallback 列表移除一个 sessionId（1:1 iOS removeFallbackSessionId）。 */
    suspend fun removeFallbackSessionId(conversationUuid: String, sessionId: String) {
        val c = dao.getByUuid(conversationUuid) ?: return
        val ids = c.offlineSummaryFallbackSessionIds.split(",").filter { it.isNotEmpty() && it != sessionId }
        dao.updateOfflineSummaryFallbackIds(conversationUuid, ids.joinToString(","))
    }

    /** 批量从 fallback 列表移除（软上限合并一次移除多个；1:1 iOS removeFallbackSessionIds）。 */
    suspend fun removeFallbackSessionIds(conversationUuid: String, sessionIds: List<String>) {
        val c = dao.getByUuid(conversationUuid) ?: return
        val toRemove = sessionIds.toSet()
        val ids = c.offlineSummaryFallbackSessionIds.split(",").filter { it.isNotEmpty() && it !in toRemove }
        dao.updateOfflineSummaryFallbackIds(conversationUuid, ids.joinToString(","))
    }
}

/** [ConversationRepository.applyMaterialization] 的列表行写入决策（13.8·B1 复核 MED 单调化）。 */
internal data class MaterializationRowDecision(
    /** 是否用本次时间戳/预览覆写 lastMessage* 末条列（false=回退物化，保留更新的行）。 */
    val overwriteColumns: Boolean,
    /** 是否未读 +1（仅本次为最新活动且非在看时）。 */
    val bumpUnread: Boolean,
    /** 是否标记已读（正在看该会话）。 */
    val markRead: Boolean,
)

/**
 * 纯函数：据「本次物化时间戳 vs 现有 lastMessageDate」决定列表行如何更新（13.8·B1 复核 MED）。
 * - 正在看（[markReadNow]）：标记已读、清未读；仅当本次最新才覆写末条列（回退物化不改列）。
 * - 不在看 + 本次最新（timestamp ≥ 现有 lastMessageDate，含会话首条 null）：覆写末条列 + 未读 +1（=原行为）。
 * - 不在看 + 回退（timestamp < 现有 lastMessageDate，B1 通知回复后才物化原主动消息）：不覆写、不加未读（防时间倒退 + 虚假未读）。
 */
internal fun materializationRowDecision(
    currentLastMessageDate: Long?,
    timestamp: Long,
    markReadNow: Boolean,
): MaterializationRowDecision {
    val isNewest = timestamp >= (currentLastMessageDate ?: Long.MIN_VALUE)
    return when {
        markReadNow -> MaterializationRowDecision(overwriteColumns = isNewest, bumpUnread = false, markRead = true)
        isNewest -> MaterializationRowDecision(overwriteColumns = true, bumpUnread = true, markRead = false)
        else -> MaterializationRowDecision(overwriteColumns = false, bumpUnread = false, markRead = false)
    }
}
