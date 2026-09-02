package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/** 补账投影：角色 uuid + 该角色最早一条「非空内容」消息时间戳（谓词与 [MessageDao.nonEmptyTimestampsForCharacter] 同源 = 资料页「初次相识」）。 */
data class CharacterFirstMessageRow(val characterUuid: String, val ts: Long)

@Dao
interface MessageDao {
    /** 按消息 UUID 反查其会话 UUID（P14.2b 全天日程的 userInteraction 事件点击→跳对应会话）。 */
    @Query("SELECT conversationUuid FROM messages WHERE messageUUID = :messageUuid LIMIT 1")
    suspend fun conversationUuidForMessage(messageUuid: String): String?

    /**
     * UI 可见消息的【窗口】版（12.3 长列表性能）：最新 [limit] 条可见消息，DESC（仓库层 reverse 成 ASC 显示）。
     * 排除暂扣未投递（P6.2），仅多 LIMIT——长对话不再每次全量加载 + Compose 全量 diff。
     * 1:1 iOS ChatMessageWindowStore.fetchWindow(limit = loadedMessageCount)（取最新 N 条、显示升序）。
     *
     * 过滤三类不该进日常聊天的消息（与 [com.situ.aichat.offline.OfflineChatVisibility] 谓词同源·改一处须同步全部）：
     *  - 系统耳语 `messageKindRaw != 'system_hint'`：只喂模型、用户永不可见的旁白（如取消见面提示）；
     *  - 线下见面细节 `(isOfflineMode = 0 OR messageKindRaw = 'offline_marker_end')`（方案 A·2026-06-18 用户拍板）：
     *    见面期间的叙事/动作块/入场标记/「准备出发」确认/结束确认卡全过滤，只留离场标记（= 收尾分隔条入口）；
     *    细节仍在见面详情页 + 模型上下文中保留。
     *  - 语音通话逐轮转写 `isPartOfVoiceCall = 0`（2026-07-12 用户拍板）：通话双方说的话不以气泡进聊天流，
     *    只在通话记录卡（CALL_RECORD_CARD·isPartOfVoiceCall=false 不受此滤）展开里看；轮次消息仍留在
     *    模型上下文（getRecent）与记忆链路里。
     * 在 LIMIT 之前过滤 → 窗口始终装满 [limit] 条可见行，分页 hasMore 计数才正确。
     */
    @Query(
        "SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND isHeldForDelivery = 0 " +
            "AND messageKindRaw != 'system_hint' " +
            "AND (isOfflineMode = 0 OR messageKindRaw = 'offline_marker_end') " +
            "AND isPartOfVoiceCall = 0 " +
            "ORDER BY timestamp DESC, messageUUID DESC LIMIT :limit",
    )
    fun observeVisibleWindowed(conversationUuid: String, limit: Int): Flow<List<MessageEntity>>

    /**
     * 某会话当前【最新一条可见消息】（与 [observeVisibleWindowed] 同口径的可见过滤 + LIMIT 1）。
     * 删消息后重算会话列表「最后一条」预览快照用（[com.situ.aichat.ui.chat.refreshConversationLastMessage]）——
     * 取删后真正的最新可见消息；整会话删空则返回 null。
     */
    @Query(
        "SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND isHeldForDelivery = 0 " +
            "AND messageKindRaw != 'system_hint' " +
            "AND (isOfflineMode = 0 OR messageKindRaw = 'offline_marker_end') " +
            "AND isPartOfVoiceCall = 0 " +
            "ORDER BY timestamp DESC, messageUUID DESC LIMIT 1",
    )
    suspend fun latestVisibleMessage(conversationUuid: String): MessageEntity?

    /**
     * 某会话最新的 [limit] 条可见消息（WHERE / ORDER 与 [latestVisibleMessage] 逐字节同口径，只把 LIMIT 1 放宽）。
     * 会话列表预览重算用（图纸 2026-09-01 件①）：最新一条恰是库内历史脏行时，往下找第一条非脏的当预览，
     * 免得列表上明晃晃挂着一段模型复读的 schema。
     */
    @Query(
        "SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND isHeldForDelivery = 0 " +
            "AND messageKindRaw != 'system_hint' " +
            "AND (isOfflineMode = 0 OR messageKindRaw = 'offline_marker_end') " +
            "AND isPartOfVoiceCall = 0 " +
            "ORDER BY timestamp DESC, messageUUID DESC LIMIT :limit",
    )
    suspend fun latestVisibleMessages(conversationUuid: String, limit: Int): List<MessageEntity>

    /** 某会话暂扣待投递的消息（按计划投递时间升序）。P6.2 BusyReplyService 投递定时器用。 */
    @Query("SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND isHeldForDelivery = 1 ORDER BY scheduledDeliveryDate ASC")
    suspend fun heldForConversation(conversationUuid: String): List<MessageEntity>

    /** 全部会话中计划投递时间已到的暂扣消息（回前台兜底释放，覆盖 app 被杀错过的投递）。 */
    @Query("SELECT * FROM messages WHERE isHeldForDelivery = 1 AND scheduledDeliveryDate IS NOT NULL AND scheduledDeliveryDate <= :now ORDER BY timestamp ASC")
    suspend fun dueHeldMessages(now: Long): List<MessageEntity>

    /** 所有仍暂扣的消息（关「忙碌延迟」总开关时全部释放）。 */
    @Query("SELECT * FROM messages WHERE isHeldForDelivery = 1")
    suspend fun allHeldMessages(): List<MessageEntity>

    /**
     * 该角色名下任一会话是否有暂扣的助手消息（忙碌回复刚生成、尚未投递的窗口）。1:1 iOS isInBusyMode 的
     * held 分支（角色级，roleRaw='assistant' && isHeldForDelivery=1）。未答恢复 defer 判断用。
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM messages m JOIN conversations c ON m.conversationUuid = c.uuid " +
            "WHERE c.characterUuid = :characterUuid AND m.isHeldForDelivery = 1 AND m.roleRaw = 'assistant')",
    )
    suspend fun hasHeldAssistantMessagesForCharacter(characterUuid: String): Boolean

    /** 清除某会话所有暂扣消息（未答恢复前清残留 held，防新回复与旧 held 叠加，1:1 iOS dropStaleHeldMessages）。 */
    @Query("DELETE FROM messages WHERE conversationUuid = :conversationUuid AND isHeldForDelivery = 1")
    suspend fun deleteHeldForConversation(conversationUuid: String)

    /**
     * 释放一条暂扣消息为可见（D1e 列级写）：只翻 isHeldForDelivery + 清 scheduledDeliveryDate 两列，
     * 不整行 upsert——避免用读快照覆盖并发的列写（messages 表无 D1 那种每行写锁；12.3 已把 embedding 改列级，
     * 此处把忙碌延迟释放也改列级，彻底消除该表整行写残留）。
     */
    @Query("UPDATE messages SET isHeldForDelivery = 0, scheduledDeliveryDate = NULL WHERE messageUUID = :uuid")
    suspend fun releaseHeldDelivery(uuid: String)

    /** 忙碌延迟回复功能删除（2026-07-11）后的存量清扫：一次翻掉全部暂扣（无存量=0 行 no-op·幂等）。 */
    @Query("UPDATE messages SET isHeldForDelivery = 0, scheduledDeliveryDate = NULL WHERE isHeldForDelivery = 1")
    suspend fun releaseAllHeldMessages()

    // 批3 3-9：第二排序键 messageUUID 保证同毫秒消息（打断递送+立即发送时 user 与 AI 段可同 ts）顺序稳定，
    // 消除偶发上屏顺序抖动；与 observeVisibleWindowed/latestVisibleMessage 同向 tiebreak（改一处须同步三处）。
    // 批4 4-1：显式列投影**不含 embedding blob**（~2KB/条 × 500 条 = 每回合 ~1MB 无用堆分配——发送路径只读文本上下文，
    // UI 窗口早在仓库出口剥 embedding，此处对称）。实体字段带默认值，CURSOR_MISMATCH 为有意设计。
    @SuppressWarnings(androidx.room.RoomWarnings.QUERY_MISMATCH)
    @Query(
        "SELECT messageUUID, conversationUuid, roleRaw, content, timestamp, isVoiceMessage, isPartOfVoiceCall, " +
            "audioRelativePath, audioDuration, imageRelativePath, imageThumbnailRelativePath, mediaMemorySummary, " +
            "isContentRevealed, isHeldForDelivery, scheduledDeliveryDate, quotedMessageUUID, quotedContent, " +
            "quotedSenderRole, emotionTag, isPetMessage, isOfflineMode, offlineSessionId, messageKindRaw " +
            "FROM messages WHERE conversationUuid = :conversationUuid ORDER BY timestamp DESC, messageUUID DESC LIMIT :limit",
    )
    suspend fun getRecent(conversationUuid: String, limit: Int): List<MessageEntity>

    /**
     * 近期「可见」消息（倒序），与日常聊天列表 [observeVisibleWindowed] 同源过滤：排除暂扣未投递的忙碌延迟消息（B5）、
     * 系统耳语 SYSTEM_HINT、线下见面细节（isOfflineMode=1 且非离场标记·方案A）、语音通话逐轮转写（isPartOfVoiceCall=1）。
     * 供列表内联快捷回复面板预览 + 通知栏回复线程显示——两者都给用户看，故须与日常聊天同口径隐藏耳语、见面细节
     * 与通话转写，避免叙事/标记/通话原文泄漏给用户
     * （见 [com.situ.aichat.offline.OfflineChatVisibility]；模型上下文走独立 [getRecent]·不受影响）。
     */
    @Query(
        "SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND isHeldForDelivery = 0 " +
            "AND messageKindRaw != 'system_hint' " +
            "AND (isOfflineMode = 0 OR messageKindRaw = 'offline_marker_end') " +
            "AND isPartOfVoiceCall = 0 " +
            "ORDER BY timestamp DESC LIMIT :limit",
    )
    suspend fun getRecentVisible(conversationUuid: String, limit: Int): List<MessageEntity>

    /** 近期「非系统、非空」消息（倒序），供故事 ChatInfluence 提取最近话题（1:1 iOS StoryChatInfluenceBuilder per-conv 查询）。 */
    @Query(
        "SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND content != '' AND roleRaw != 'system' " +
            "ORDER BY timestamp DESC LIMIT :limit",
    )
    suspend fun recentNonSystemForConversation(conversationUuid: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationUuid = :conversationUuid ORDER BY timestamp ASC")
    suspend fun getAllForConversation(conversationUuid: String): List<MessageEntity>

    // 14.1a 资料页陪伴统计：角色全会话「非系统、非空」消息计数（1:1 iOS CompanionStats messageCount 口径）。
    @Query(
        "SELECT COUNT(*) FROM messages m JOIN conversations c ON m.conversationUuid = c.uuid " +
            "WHERE c.characterUuid = :characterUuid AND m.content != '' AND m.roleRaw != 'system'",
    )
    suspend fun countNonSystemForCharacter(characterUuid: String): Int

    /** 同上计数的 Flow 版：消息表变化即重发，供资料页统计实时刷新（超越 iOS 的退出再进）。 */
    @Query(
        "SELECT COUNT(*) FROM messages m JOIN conversations c ON m.conversationUuid = c.uuid " +
            "WHERE c.characterUuid = :characterUuid AND m.content != '' AND m.roleRaw != 'system'",
    )
    fun observeNonSystemForCharacter(characterUuid: String): Flow<Int>

    // 14.1c 共同记忆统计：角色全会话「非空内容」消息时间戳（升序）。含 system（1:1 iOS StructuredMemoryStats 谓词仅 content!=''）。
    @Query(
        "SELECT m.timestamp FROM messages m JOIN conversations c ON m.conversationUuid = c.uuid " +
            "WHERE c.characterUuid = :characterUuid AND m.content != '' ORDER BY m.timestamp ASC",
    )
    suspend fun nonEmptyTimestampsForCharacter(characterUuid: String): List<Long>

    /** 「第一次聊天时间」补账（相识天数图纸 §4.1）：每个有消息的角色一行 = 其最早一条「非空内容」消息时间戳。 */
    @Query(
        "SELECT c.characterUuid AS characterUuid, MIN(m.timestamp) AS ts FROM messages m JOIN conversations c ON m.conversationUuid = c.uuid " +
            "WHERE m.content != '' GROUP BY c.characterUuid",
    )
    suspend fun earliestNonEmptyTimestampByCharacter(): List<CharacterFirstMessageRow>

    /**
     * 仅取一会话中「带媒体」消息的磁盘路径（音频/图片/缩略图），用于删会话前清磁盘文件
     * （1:1 iOS CharacterMediaCleanupService 的 propertiesToFetch 投影——不整行加载，对长会话友好）。
     */
    @Query(
        "SELECT audioRelativePath, imageRelativePath, imageThumbnailRelativePath FROM messages " +
            "WHERE conversationUuid = :conversationUuid AND " +
            "(audioRelativePath IS NOT NULL OR imageRelativePath IS NOT NULL OR imageThumbnailRelativePath IS NOT NULL)",
    )
    suspend fun mediaPathsForConversation(conversationUuid: String): List<ConversationMediaPaths>

    @Query("SELECT * FROM messages WHERE messageUUID = :uuid")
    suspend fun getByUuid(uuid: String): MessageEntity?

    /** Unread = assistant messages with non-empty content newer than lastReadDate (mirrors iOS Conversation.fetchUnreadCount). */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversationUuid = :conversationUuid
          AND roleRaw = 'assistant'
          AND content != ''
          AND (:lastReadDate IS NULL OR timestamp > :lastReadDate)
        """,
    )
    suspend fun unreadCount(conversationUuid: String, lastReadDate: Long?): Int

    @Query("SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun search(conversationUuid: String, query: String): List<MessageEntity>

    /**
     * Vector-memory (M05): one page of embedded candidate messages, newest first. Paged (LIMIT/OFFSET) so retrieval
     * scans the WHOLE conversation instead of a fixed newest-200 window — the old cap made anything older than the
     * 200 most recent embedded messages permanently unretrievable. Callers loop pages; memory stays bounded at one
     * page of embedding blobs (~1MB @ 500 rows).
     */
    @Query("SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND embedding IS NOT NULL ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getEmbeddedPage(conversationUuid: String, limit: Int, offset: Int): List<MessageEntity>

    /**
     * Vector-memory backfill (12.3): fast EXISTS probe — is any *delivered* message still missing an embedding?
     * Excludes `isHeldForDelivery = 1`: undelivered busy-reply messages are not history yet (must not become
     * retrievable memory), and skipping them also means backfill never races BusyReply's release upsert.
     *
     * Also excludes structured cards (`messageKindRaw NOT IN …`, = [com.situ.aichat.data.model.MessageKind.isStructuredCard]):
     * their `content` is JSON / marker text whose raw form (red-packet amount, gift cost, call transcript) must never be
     * embedded into the searchable vector store. These rows keep `embedding IS NULL` forever, so this denylist (mirrored in
     * [messagesMissingEmbedding]) is what stops the EXISTS probe from forever reporting "missing" and the backfill loop's
     * structured-card `continue` from re-fetching the same cards every round. Raw literals pinned by `MessageKindTest`.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM messages WHERE embedding IS NULL AND isHeldForDelivery = 0 " +
            "AND roleRaw != 'system' AND content != '' " +
            "AND messageKindRaw NOT IN ('offline_invite_card','offline_end_card','call_record_card'," +
            "'offline_marker_start','offline_marker_end','system_event_card','gift_card','red_packet'," +
            "'future_meeting_proposal','future_meeting_change') AND messageKindRaw != 'system_hint')",
    )
    suspend fun hasMissingEmbedding(): Boolean

    /**
     * Vector-memory backfill (12.3): up to [limit] *delivered* messages still lacking an embedding, newest-first.
     * Excludes held (undelivered) rows (see [hasMissingEmbedding]); released busy-reply messages become eligible
     * once their hold flips off. Self-advancing — once a row is written (real bytes or empty sentinel) it leaves
     * this set, so repeated calls walk the whole backlog without a separate cursor.
     *
     * Structured cards are excluded by the same `messageKindRaw NOT IN …` denylist as [hasMissingEmbedding] (their raw JSON
     * must not enter the vector store). They are never written a sentinel — they simply never appear here — so the denylist
     * is mandatory: without it the backfill loop's `if (isStructuredCard) continue` would skip them yet leave `embedding
     * IS NULL`, re-fetching the same cards every batch (busy-loop). Raw literals pinned by `MessageKindTest`.
     */
    @Query(
        "SELECT * FROM messages WHERE embedding IS NULL AND isHeldForDelivery = 0 " +
            "AND roleRaw != 'system' AND content != '' " +
            "AND messageKindRaw NOT IN ('offline_invite_card','offline_end_card','call_record_card'," +
            "'offline_marker_start','offline_marker_end','system_event_card','gift_card','red_packet'," +
            "'future_meeting_proposal','future_meeting_change') AND messageKindRaw != 'system_hint' " +
            "ORDER BY timestamp DESC LIMIT :limit",
    )
    suspend fun messagesMissingEmbedding(limit: Int): List<MessageEntity>

    /**
     * Vector-memory (12.3): **column-level** embedding write. The messages table has no D1-style row lock,
     * so the per-turn embed must NOT full-row upsert — that would clobber concurrent column writes (e.g. the
     * held-delivery flip that BusyReplyService still does via full-row upsert). This touches only the embedding
     * column. (Backfill additionally skips held rows entirely — see [messagesMissingEmbedding] — so it never
     * races that flip.) An empty ByteArray is the "un-embeddable" sentinel (NOT NULL ⇒ not re-probed;
     * deserialize ⇒ null ⇒ skipped at search), mirroring iOS's empty-Data sentinel.
     */
    @Query("UPDATE messages SET embedding = :embedding WHERE messageUUID = :uuid")
    suspend fun updateEmbedding(uuid: String, embedding: ByteArray)

    /**
     * 哨兵洗白（图纸 2026-09-01 件④·一次性迁移用）：把空 blob 哨兵重置为 NULL 回到待回填集，
     * 由回填按「只有永久不可嵌才写哨兵」的新规则复评——历史上被瞬态推理失败冤枉的行由此拿回真向量。
     * 只碰 length=0 的行，真向量（512 维 = 2048 字节）分毫不动。
     * @return 重置的行数。
     */
    @Query("UPDATE messages SET embedding = NULL WHERE embedding IS NOT NULL AND length(embedding) = 0")
    suspend fun resetSentinelEmbeddings(): Int

    /**
     * 图片理解摘要回填（[com.situ.aichat.chat.image.ImageMemorySummaryService]）。
     * 单列 UPDATE 而非整行 @Update：摘要是**异步**生成的，期间同一条消息可能已被别处改过
     *（送达回执 / 嵌入回填），整行 copy 回写会用陈旧快照覆盖它们（钱路审计的老教训）。
     */
    @Query("UPDATE messages SET mediaMemorySummary = :summary WHERE messageUUID = :uuid")
    suspend fun updateMediaMemorySummary(uuid: String, summary: String)

    /**
     * Vector-memory startup self-heal (14.5a; 1:1 iOS `VectorMemoryService.clearAllEmbeddings`): wipe ALL
     * stored embeddings so the backfill pass re-embeds them with the new model. Called only when the embedding
     * model signature changed (a different ONNX asset baked into a future build) — old vectors live in an
     * incompatible coordinate system and the per-search dimension guard can't catch a same-dim model swap.
     *
     * Predicate `embedding IS NOT NULL` deliberately matches both real vectors AND the empty-blob sentinel:
     * mirrors iOS clearing `embeddingData != nil` (un-embeddable short messages may behave differently under a
     * new model, so they must be re-evaluated too). Single in-DB UPDATE — the Android idiom, no need for iOS's
     * 500-row materialized batch loop (SwiftData loads objects; Room updates in place).
     * @return rows cleared.
     */
    @Query("UPDATE messages SET embedding = NULL WHERE embedding IS NOT NULL")
    suspend fun clearAllEmbeddings(): Int

    /** Timestamps of the most recent non-empty user messages — used to compute the short-term window cutoff. */
    @Query("SELECT timestamp FROM messages WHERE conversationUuid = :conversationUuid AND roleRaw = 'user' AND content != '' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentUserTimestamps(conversationUuid: String, limit: Int): List<Long>

    /** 一批会话中最后一条非系统非空消息的 (timestamp, roleRaw, messageUUID)（对话状态判定 + 竞态终查共用）。 */
    @Query("SELECT timestamp, roleRaw, messageUUID FROM messages WHERE conversationUuid IN (:conversationUuids) AND roleRaw != 'system' AND content != '' ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestNonSystemAcross(conversationUuids: List<String>): LatestMessageMeta?

    /** 一批会话中最后一条用户消息时间戳（连发闸基准）。 */
    @Query("SELECT MAX(timestamp) FROM messages WHERE conversationUuid IN (:conversationUuids) AND roleRaw = 'user' AND content != ''")
    suspend fun latestUserTimestampAcross(conversationUuids: List<String>): Long?

    /**
     * Memory-summary (M05): non-empty, non-system messages **strictly after the summary cursor** (null = from the
     * beginning), oldest-first, capped. The cursor predicate lives in SQL — the old Kotlin-side filter over an
     * uncursored `LIMIT 500` window permanently stalled the rolling summary once a conversation grew past 500
     * messages (the oldest-500 window was fully behind the cursor forever). Backlogs > [limit] drain incrementally:
     * each successful summary advances the cursor, the next fetch resumes right after it.
     *
     * `isOfflineMode = 0`: offline-meeting narrative is compressed by its dedicated pipeline
     * (OfflineSummaryRetryCoordinator → offlineMeetingMemorySummary) and must not double-enter the general
     * rolling summary (narration tone pollutes the texting-style memory).
     *
     * `isHeldForDelivery = 0`（图纸 2026-09-01 件⑦）：暂扣行尚未投递给用户，口径对齐其余可见性谓词——
     * 未上屏的内容不该先进长期记忆（忙碌延迟功能虽已删，老库仍可能有残留暂扣行）。
     */
    @Query(
        "SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND content != '' AND roleRaw != 'system' " +
            "AND isHeldForDelivery = 0 " +
            "AND isOfflineMode = 0 AND (:cursor IS NULL OR timestamp > :cursor) ORDER BY timestamp ASC LIMIT :limit",
    )
    suspend fun summarizableMessages(conversationUuid: String, cursor: Long?, limit: Int): List<MessageEntity>

    /**
     * World-book timed-effects anchor (WB3): TOTAL scannable message count for the conversation — same filter as
     * WorldBookPromptService's scan input (non-system, non-structured-card, non-empty), but over the whole table
     * instead of the 500-row prompt fetch window. The anchor must keep growing monotonically forever: the old
     * `scannable.size` plateaued at ~500, freezing sticky/cooldown/delay math permanently. Structured-card
     * denylist mirrors [hasMissingEmbedding] (= MessageKind.isStructuredCard, raw literals pinned by MessageKindTest).
     */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE conversationUuid = :conversationUuid " +
            "AND roleRaw != 'system' AND content != '' " +
            "AND messageKindRaw NOT IN ('offline_invite_card','offline_end_card','call_record_card'," +
            "'offline_marker_start','offline_marker_end','system_event_card','gift_card','red_packet'," +
            "'future_meeting_proposal','future_meeting_change')",
    )
    suspend fun countScannableForWorldBook(conversationUuid: String): Int

    /** Analysis collection (M05 structured / M14 growth): most-recent non-empty, non-system messages (mirrors iOS GrowthAnalysisService fetchLimit 200). */
    @Query("SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND content != '' AND roleRaw != 'system' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentForAnalysis(conversationUuid: String, limit: Int): List<MessageEntity>

    /** Schedule "recent conversation" (P5.1): non-empty messages since [since], newest first, capped (mirrors iOS recentConversationSummary fetchLimit 6, no system filter). */
    @Query("SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND timestamp >= :since AND content != '' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentSinceForSummary(conversationUuid: String, since: Long, limit: Int): List<MessageEntity>

    /**
     * Count of unsummarized user rounds before [windowCutoff] and after [summaryCutoff] (null = no cursor yet).
     * `isOfflineMode = 0` mirrors [summarizableMessages]: offline narrative never enters the rolling summary, so it
     * must not count toward the trigger/window-expansion either (otherwise stranded offline rounds would pin the
     * dynamic window at 2× forever).
     */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversationUuid = :conversationUuid
          AND content != ''
          AND roleRaw = 'user'
          AND isOfflineMode = 0
          AND timestamp < :windowCutoff
          AND (:summaryCutoff IS NULL OR timestamp > :summaryCutoff)
        """,
    )
    suspend fun countUnsummarizedUserRounds(conversationUuid: String, windowCutoff: Long, summaryCutoff: Long?): Int

    /**
     * 跨全部会话、时间落在 `[start, end)` 的消息，时间正序，最多 [limit] 条（M07 日记生成·笔友判定）。
     * 1:1 iOS：predicate 仅按时间窗 + fetchLimit；系统/空/脏消息过滤在 Kotlin 侧做。
     */
    @Query("SELECT * FROM messages WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC LIMIT :limit")
    suspend fun messagesInRange(start: Long, end: Long, limit: Int): List<MessageEntity>

    /**
     * 当天 `[start, end)` 有消息的去重角色 uuid，按各角色**当天首条消息时间**正序（日记按角色分组）。
     * JOIN conversations 拿 characterUuid。**根治**「全局取最早 N 条再分组」会让上午聊爆后、傍晚才聊的角色被
     * 前面消息挤出上限而整段丢失——改为「先列出聊过的角色、每角色各自取」（配 [messagesForCharacterInRange]）。
     */
    @Query(
        """
        SELECT c.characterUuid FROM messages m
        JOIN conversations c ON m.conversationUuid = c.uuid
        WHERE m.timestamp >= :start AND m.timestamp < :end
        GROUP BY c.characterUuid
        ORDER BY MIN(m.timestamp) ASC
        """,
    )
    suspend fun characterUuidsWithMessagesInRange(start: Long, end: Long): List<String>

    /**
     * 某角色（跨其全部会话）当天 `[start, end)` 最早 [limit] 条消息，时间正序（日记素材·**每角色各自取**·
     * 不受别的角色消息量挤占）。JOIN conversations 按 characterUuid 过滤。
     */
    @Query(
        """
        SELECT m.* FROM messages m
        JOIN conversations c ON m.conversationUuid = c.uuid
        WHERE c.characterUuid = :characterUuid AND m.timestamp >= :start AND m.timestamp < :end
        ORDER BY m.timestamp ASC LIMIT :limit
        """,
    )
    suspend fun messagesForCharacterInRange(characterUuid: String, start: Long, end: Long, limit: Int): List<MessageEntity>

    /**
     * 成长分析取材（活人感内核卷零 §3.4）：某角色**跨全部会话**的最近 [limit] 条非空非 system 消息，**倒序**。
     * 与 [recentForAnalysis]（per-conversation）的分工：本查询一次 JOIN 拿全角色，供「按轮切窗」使用
     * （按轮切窗必须在同一条时间轴上切，逐会话取再合并会在会话边界切错轮）。
     */
    @Query(
        "SELECT m.* FROM messages AS m INNER JOIN conversations AS c ON m.conversationUuid = c.uuid " +
            "WHERE c.characterUuid = :characterUuid AND m.content != '' AND m.roleRaw != 'system' " +
            "ORDER BY m.timestamp DESC LIMIT :limit",
    )
    suspend fun recentForCharacterAnalysis(characterUuid: String, limit: Int): List<MessageEntity>

    /**
     * 某角色最近 [cutoff] 后与用户的非 system 消息数（朋友圈相关性「活跃度」维度，M06 7.2.4）。
     * 1:1 iOS `MomentGenerationActor.fetchRecentMessageCount`（遍历该角色所有会话求和）—— JOIN conversations
     * 一次统计跨该角色全部会话，等价于 iOS 的逐会话计数求和。
     */
    @Query(
        "SELECT COUNT(*) FROM messages AS m INNER JOIN conversations AS c ON m.conversationUuid = c.uuid " +
            "WHERE c.characterUuid = :characterUuid AND m.timestamp > :cutoff AND m.roleRaw != 'system'"
    )
    suspend fun countRecentNonSystemForCharacter(characterUuid: String, cutoff: Long): Int

    /**
     * 某角色全部会话的消息总数（宠物领养门槛「消息≥100」，M11）。1:1 iOS
     * `character.conversations.reduce(0) { $0 + $1.messages.count }`（含所有消息，JOIN 一次求和）。
     */
    @Query(
        "SELECT COUNT(*) FROM messages AS m INNER JOIN conversations AS c ON m.conversationUuid = c.uuid " +
            "WHERE c.characterUuid = :characterUuid"
    )
    suspend fun countAllForCharacter(characterUuid: String): Int

    /**
     * 某会话今日（[startOfDay] 起）宠物独白消息（isPetMessage=1）数量（宠物聊天气泡节流，M11）。
     * 1:1 iOS `PetChatBubbleService.isOverDailyLimit` 的计数：每会话每天最多 3 条宠物独白。
     */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationUuid = :conversationUuid AND isPetMessage = 1 AND timestamp >= :startOfDay")
    suspend fun countPetMessagesSince(conversationUuid: String, startOfDay: Long): Int

    /** 全表消息条数（性能采集规模数 `messages`·图纸 §3.2·只在 flush 时取一次）。 */
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countAll(): Int

    /**
     * 最近 [since, ∞) 全局宠物独白消息（isPetMessage=1）正文，降序、最多 [limit] 条（宠物状态 prompt 去重用，M11）。
     * 1:1 iOS `PromptBuilder+Pet.recentPetMessageContents`（无会话过滤、fetchLimit=30、desc）：用于剔除已在独白里
     * 出现过的购买物品，避免和聊天历史重复。
     */
    @Query("SELECT content FROM messages WHERE isPetMessage = 1 AND timestamp >= :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentPetMessageContents(since: Long, limit: Int): List<String>

    /**
     * 指定线下见面会话（[sessionId]）的全部消息，升序（1:1 iOS `fetchOfflineMessagesForSession`）。
     * 供线下状态守护 / 异常恢复判定 / 见面摘要提取（10.2c-3c 状态机 + 10.2d 摘要链）统一读取见面消息。
     */
    @Query("SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND isOfflineMode = 1 AND offlineSessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun offlineSessionMessages(conversationUuid: String, sessionId: String): List<MessageEntity>

    /**
     * 见面会话全部消息的【响应式】流（12.3：线下沉浸剧场专用，**全 session 不窗口化**）。常规聊天列表已窗口化到
     * 最新 N 条，但沉浸剧场须呈现完整见面弧线——独立全量订阅本会话，避免长会话(>窗口)丢见面开头。1:1 iOS
     * refreshOfflineSessionMessages 的独立非窗口 fetch。含 `isHeldForDelivery = 0`：保持与改造前（剧场读窗口化
     * observeVisible，本就排除暂扣未投递）完全一致的可见性。
     */
    @Query("SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND isOfflineMode = 1 AND offlineSessionId = :sessionId AND isHeldForDelivery = 0 ORDER BY timestamp ASC")
    fun observeOfflineSessionMessages(conversationUuid: String, sessionId: String): Flow<List<MessageEntity>>

    /**
     * 某会话指定 [kindRaw] 的全部消息（升序）。供见面摘要兜底元数据提取（邀约卡判发起方 / 入场标记取地点活动，
     * 10.2d）；邀约卡 isOfflineMode=false，走不了 [offlineSessionMessages]。
     */
    @Query("SELECT * FROM messages WHERE conversationUuid = :conversationUuid AND messageKindRaw = :kindRaw ORDER BY timestamp ASC")
    suspend fun messagesByKind(conversationUuid: String, kindRaw: String): List<MessageEntity>

    /**
     * 按 [sessionId] 反查所属会话（取该 session 指定 [kindRaw] 标记消息的 conversationUuid）。供 24h 自愈手动重试
     * 反查会话（10.2d-3，1:1 iOS manuallyRetry 经 markerStart 关联 conversation）。
     */
    @Query("SELECT conversationUuid FROM messages WHERE offlineSessionId = :sessionId AND messageKindRaw = :kindRaw LIMIT 1")
    suspend fun conversationUuidForSession(sessionId: String, kindRaw: String): String?

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE messageUUID = :uuid")
    suspend fun deleteByUuid(uuid: String)

    /**
     * 单条消息的磁盘媒体路径投影（14.7c：删单条消息前据此清音频/图片/缩略图文件，堵单删媒体泄漏）。
     * 无媒体或消息不存在 → null。
     */
    @Query(
        "SELECT audioRelativePath, imageRelativePath, imageThumbnailRelativePath FROM messages " +
            "WHERE messageUUID = :uuid LIMIT 1",
    )
    suspend fun mediaPathsForMessage(uuid: String): ConversationMediaPaths?
}
