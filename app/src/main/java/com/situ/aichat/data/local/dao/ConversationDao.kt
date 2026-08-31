package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

/**
 * 过渡丝滑化：会话 + 该角色聊天壁纸路径的合并查询结果（[ConversationDao.observeWithWallpaper] 一次 LEFT JOIN）。
 * 让「会话(标题/状态)」与「壁纸路径」同帧返回 → 壁纸(含状态栏/底部手势条)与内容同步出现、不再割裂。
 */
data class ConversationWithWallpaper(
    @Embedded val conversation: ConversationEntity,
    val chatWallpaperPath: String?,
)

@Dao
interface ConversationDao {
    /** Active list: pinned first, then most-recent activity (mirrors iOS chat list ordering). */
    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY isPinned DESC, lastMessageDate DESC, creationDate DESC")
    fun observeActive(): Flow<List<ConversationEntity>>

    /** 活跃会话一次性快照（与 [observeActive] 同序），供桌面小组件 provideGlance 直读（13.9a；无需挂 Flow 观察者）。 */
    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY isPinned DESC, lastMessageDate DESC, creationDate DESC")
    suspend fun activeSnapshot(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE isArchived = 1 ORDER BY lastMessageDate DESC")
    fun observeArchived(): Flow<List<ConversationEntity>>

    /** 归档会话**数**（K5·2026-07-12）：列表页只拿它判「>0 显示归档入口」——WHERE 与 [observeArchived] 同（平价测试钉死）。 */
    @Query("SELECT COUNT(*) FROM conversations WHERE isArchived = 1")
    fun observeArchivedCount(): Flow<Int>

    @Query("SELECT * FROM conversations WHERE uuid = :uuid")
    fun observeByUuid(uuid: String): Flow<ConversationEntity?>

    /**
     * 过渡丝滑化：一次 LEFT JOIN 同时取「会话 + 该角色聊天壁纸路径」。让会话(标题/状态)与壁纸路径从**同一次查询、
     * 同一次 emission** 返回 → 进会话时壁纸(含状态栏与底部手势条)与聊天内容**同帧出现、不再晚一拍/割裂**
     * （原先壁纸路径是独立第二查询、比会话查询晚几帧到，在慢设备上尤其明显）。
     */
    @Query(
        "SELECT c.*, ch.chatWallpaperPath AS chatWallpaperPath FROM conversations c " +
            "LEFT JOIN characters ch ON c.characterUuid = ch.uuid WHERE c.uuid = :uuid",
    )
    fun observeWithWallpaper(uuid: String): Flow<ConversationWithWallpaper?>

    @Query("SELECT * FROM conversations WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE characterUuid = :characterUuid ORDER BY creationDate ASC")
    suspend fun getByCharacter(characterUuid: String): List<ConversationEntity>

    /**
     * 某角色最新「未归档」会话（宠物聊天气泡 M11：插宠物独白用）。1:1 iOS `PetChatBubbleService.latestConversation`：
     * 排除已归档、按 `lastMessageDate ?? creationDate` 降序取首。无活动会话 → null（调用方静默跳过）。
     */
    @Query(
        "SELECT * FROM conversations WHERE characterUuid = :characterUuid AND isArchived = 0 " +
            "ORDER BY COALESCE(lastMessageDate, creationDate) DESC LIMIT 1",
    )
    suspend fun latestActiveForCharacter(characterUuid: String): ConversationEntity?

    /**
     * 记忆整理遇阻会话计数（记忆护栏第二层 MG-U1·契约 FABLE5_MEMORY_GUARD_UI_PROPOSAL §3）：
     * 谓词 = `lastMemorySummaryFailureDate IS NOT NULL`——成功写回恒清失败旗标
     * （[com.situ.aichat.data.repository.ConversationRepository.recordMemorySummaryResult]），
     * 故等价于「最近一次整理失败且失败后未再成功」，零新增存储。
     */
    @Query(
        "SELECT COUNT(*) FROM conversations WHERE characterUuid = :characterUuid " +
            "AND lastMemorySummaryFailureDate IS NOT NULL",
    )
    fun observeMemorySummaryBlockedCount(characterUuid: String): Flow<Int>

    /** 同谓词的实体列表版（「立即整理」成功/失败后逐会话回写结果用）。 */
    @Query(
        "SELECT * FROM conversations WHERE characterUuid = :characterUuid " +
            "AND lastMemorySummaryFailureDate IS NOT NULL",
    )
    suspend fun memorySummaryBlockedByCharacter(characterUuid: String): List<ConversationEntity>

    /** 未读会话计数和（非归档），用于 app 图标角标基数（P6.1e，对齐 iOS baseUnread）。 */
    @Query("SELECT COALESCE(SUM(cachedUnreadCount), 0) FROM conversations WHERE isArchived = 0")
    suspend fun totalUnread(): Int

    /** 同 [totalUnread] 的响应式版本，底部「聊天」Tab 未读角标用（nav-shell-2，对齐 iOS MainTabView chats .badge）。 */
    @Query("SELECT COALESCE(SUM(cachedUnreadCount), 0) FROM conversations WHERE isArchived = 0")
    fun observeTotalUnread(): Flow<Int>

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    /** Targeted voice-reply counter update (P10.1c) — avoids clobbering other columns at turn end. */
    @Query("UPDATE conversations SET voiceRoundsSinceLastVoice = :rounds, voiceNextThreshold = :threshold WHERE uuid = :uuid")
    suspend fun updateVoiceRounds(uuid: String, rounds: Int, threshold: Int)

    /**
     * 「最后一条」快照定向三列更新（审计 R2）——替代原「getByUuid→整行 copy→upsert」的读改写：整行 RMW 与
     * AI 分条递送并发时会把递送层刚写的 mood 三列 / voiceRounds 等覆写回旧值（同 updateVoiceRounds 的钱路教训）。
     * timestamp=null = 清空快照（删空整会话 → 退出活跃列表，对齐 iOS `lastMessageDate != nil` predicate）。
     */
    @Query("UPDATE conversations SET lastMessagePreview = :preview, lastMessageRole = :role, lastMessageDate = :timestamp WHERE uuid = :uuid")
    suspend fun updateLastMessageSnapshot(uuid: String, preview: String, role: String, timestamp: Long?)

    /** 摘要游标推进（批1修复）——定向单列更新，替代原「getByCharacter 快照→整行 upsert」（陈旧快照会覆写并发列）。 */
    @Query("UPDATE conversations SET lastSummarizedMessageDate = :cursor WHERE uuid = :uuid")
    suspend fun updateSummaryCursor(uuid: String, cursor: Long)

    /** 记忆整理成功记账（图纸件⑥·PITFALLS §1b 整行 upsert 反模式修正）：成功轨+attempt 同写、失败旗标清空。 */
    @Query(
        "UPDATE conversations SET lastMemorySummarySuccessDate = :now, lastMemorySummaryFailureDate = NULL, " +
            "lastMemorySummaryAttemptDate = :now WHERE uuid = :uuid",
    )
    suspend fun recordMemorySummarySuccess(uuid: String, now: Long)

    /** 记忆整理失败记账：失败短冷却起点+attempt，成功轨不动。 */
    @Query("UPDATE conversations SET lastMemorySummaryFailureDate = :now, lastMemorySummaryAttemptDate = :now WHERE uuid = :uuid")
    suspend fun recordMemorySummaryFailure(uuid: String, now: Long)

    /** 见面识别扫描成功（清失败短冷却）——定向单列更新，避免整行 copy 在回合尾覆写并发列（钱路审计教训）。 */
    @Query("UPDATE conversations SET lastMeetingScanSuccessDate = :now, lastMeetingScanFailureDate = NULL WHERE uuid = :uuid")
    suspend fun markMeetingScanSuccess(uuid: String, now: Long)

    /** 见面识别扫描失败（写短冷却起点）。 */
    @Query("UPDATE conversations SET lastMeetingScanFailureDate = :now WHERE uuid = :uuid")
    suspend fun markMeetingScanFailure(uuid: String, now: Long)

    /** 惦记的事扫描成功（清失败短冷却·活人感一期 P2）——定向单列更新，避免整行 copy 在回合尾覆写并发列。 */
    @Query("UPDATE conversations SET lastOpenLoopScanSuccessDate = :now, lastOpenLoopScanFailureDate = NULL WHERE uuid = :uuid")
    suspend fun markOpenLoopScanSuccess(uuid: String, now: Long)

    /** 惦记的事扫描失败（写短冷却起点·活人感一期 P2）。 */
    @Query("UPDATE conversations SET lastOpenLoopScanFailureDate = :now WHERE uuid = :uuid")
    suspend fun markOpenLoopScanFailure(uuid: String, now: Long)

    // MARK: - 聊天列表页操作（13.5 chat-ui-11）：定向 UPDATE 单列原子，避免整行 @Upsert 与并发写互相覆盖。

    /** 置顶/取消置顶（1:1 iOS swipe Pin/Unpin → conversation.isPinned.toggle()）。 */
    @Query("UPDATE conversations SET isPinned = :pinned WHERE uuid = :uuid")
    suspend fun setPinned(uuid: String, pinned: Boolean)

    /** 归档/取消归档（1:1 iOS swipe Archive/Unarchive → conversation.isArchived = ...）。 */
    @Query("UPDATE conversations SET isArchived = :archived WHERE uuid = :uuid")
    suspend fun setArchived(uuid: String, archived: Boolean)

    /** 按 uuid 删会话（FK CASCADE 连带删其消息行；磁盘媒体须先经 ConversationMediaCleaner 清理）。 */
    @Query("DELETE FROM conversations WHERE uuid = :uuid")
    suspend fun deleteById(uuid: String)

    // MARK: - 见面摘要重试链退避状态（10.2d）：targeted UPDATE 单列原子，避免整行 @Upsert 与并发写互相覆盖。

    /** 记录本次摘要尝试时间（无论成功/失败都更新，用于退避窗口判断，1:1 iOS lastAttemptAt = Date()）。 */
    @Query("UPDATE conversations SET pendingOfflineSummaryLastAttemptAt = :now WHERE uuid = :uuid")
    suspend fun updateOfflineSummaryLastAttemptAt(uuid: String, now: Long)

    /** 摘要失败计数 +1（原子自增，1:1 iOS failCount += 1）。 */
    @Query("UPDATE conversations SET pendingOfflineSummaryFailCount = pendingOfflineSummaryFailCount + 1 WHERE uuid = :uuid")
    suspend fun incrementOfflineSummaryFailCount(uuid: String)

    /** 成功 / 兜底后清空 pending 三字段（sessionId/failCount/lastAttemptAt，1:1 iOS 清理）。 */
    @Query(
        "UPDATE conversations SET pendingOfflineSummarySessionId = NULL, pendingOfflineSummaryFailCount = 0, " +
            "pendingOfflineSummaryLastAttemptAt = NULL WHERE uuid = :uuid",
    )
    suspend fun clearPendingOfflineSummary(uuid: String)

    /** 写回 fallback sessionId 列表（逗号分隔，repo 层读改后定向写本列）。 */
    @Query("UPDATE conversations SET offlineSummaryFallbackSessionIds = :ids WHERE uuid = :uuid")
    suspend fun updateOfflineSummaryFallbackIds(uuid: String, ids: String)

    /**
     * 场内前情提要三列原子写回（记忆改造二期·部件⑤·图纸 §3.2-B）——定向三列 UPDATE（照 [updateSummaryCursor]
     * 先例），**绝不整行 copy-upsert**：前情提要在见面/通话回合尾生成，与 AI 分条递送并发时整行 RMW 会覆写
     * 递送层刚写的 mood/voiceRounds/lastMessage* 等列（钱路审计教训）。
     */
    @Query(
        "UPDATE conversations SET inSceneRecapText = :text, inSceneRecapSessionKey = :sessionKey, " +
            "inSceneRecapUntilMillis = :untilMillis WHERE uuid = :uuid",
    )
    suspend fun updateInSceneRecap(uuid: String, text: String, sessionKey: String, untilMillis: Long)

    /** 手动/自愈重试：把 sessionId 重塞回 pending + 清零计数与时间戳（绕过退避，1:1 iOS manuallyRetry）。 */
    @Query(
        "UPDATE conversations SET pendingOfflineSummarySessionId = :sessionId, pendingOfflineSummaryFailCount = 0, " +
            "pendingOfflineSummaryLastAttemptAt = NULL WHERE uuid = :uuid",
    )
    suspend fun restorePendingOfflineSummary(uuid: String, sessionId: String)

    /** 所有待生成见面摘要的会话（全局扫描重试用，10.2d-3）。 */
    @Query("SELECT * FROM conversations WHERE pendingOfflineSummarySessionId IS NOT NULL")
    suspend fun conversationsWithPendingOfflineSummary(): List<ConversationEntity>

    /** 所有有 fallback 记录的会话（24h 低频自愈用，10.2d-3）。 */
    @Query("SELECT * FROM conversations WHERE offlineSummaryFallbackSessionIds != ''")
    suspend fun conversationsWithOfflineFallback(): List<ConversationEntity>

    /** 有「简版」见面摘要兜底的角色 uuid（联系人头像红点·1:1 iOS hasFallbackSummaries），Flow 实时刷新。 */
    @Query("SELECT DISTINCT characterUuid FROM conversations WHERE offlineSummaryFallbackSessionIds != ''")
    fun observeCharacterUuidsWithOfflineFallback(): Flow<List<String>>

    /**
     * 未答恢复候选（10.2g）：最后一条是用户消息（含非空预览）、未归档、非线下的会话。1:1 iOS lastMessageRole=="user"
     * 谓词——列直查不 JOIN，避免全表加载（小米14 数据量大时关键）；archived/isInOfflineMode 折进 SQL，
     * 角色存在 + busy-defer 留 Kotlin 过滤（需服务查询）。
     */
    @Query(
        "SELECT * FROM conversations WHERE lastMessageRole = 'user' AND lastMessagePreview != '' " +
            "AND isArchived = 0 AND isInOfflineMode = 0",
    )
    suspend fun conversationsAwaitingReply(): List<ConversationEntity>

    @Delete
    suspend fun delete(conversation: ConversationEntity)
}
