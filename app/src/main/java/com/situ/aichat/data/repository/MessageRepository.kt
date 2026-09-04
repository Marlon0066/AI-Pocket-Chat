package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.util.AudioStore
import com.situ.aichat.util.ContentImageStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val dao: MessageDao,
) {
    /**
     * UI 可见消息的【窗口】流（12.3 长列表性能）：最新 [limit] 条，已 reverse 成 ASC 显示顺序。
     * see [MessageDao.observeVisibleWindowed]（1:1 iOS ChatMessageWindowStore.fetchWindow）。
     *
     * 审计 P1：出口剥离 [MessageEntity.embedding]——UI 窗口不背 ~2KB 向量 blob，且后台向量 backfill
     * （列级写 embedding）触发的重查询产出与上次 equals 相等的列表 → 下游 StateFlow 去重直接吞掉，
     * 屏上零像素变化的「幽灵整屏重组」根除（配合 MessageEntity 全字段 equals + compose_stability.conf）。
     */
    fun observeVisibleWindowed(conversationUuid: String, limit: Int): Flow<List<MessageEntity>> =
        dao.observeVisibleWindowed(conversationUuid, limit).map { rows ->
            rows.asReversed().map { if (it.embedding == null) it else it.copy(embedding = null) }
        }

    /** 某会话当前【最新一条可见消息】（删消息后重算列表「最后一条」预览快照用）；删空则 null。 */
    suspend fun latestVisibleMessage(conversationUuid: String): MessageEntity? =
        dao.latestVisibleMessage(conversationUuid)

    /** 最新 [limit] 条可见消息（同上口径放宽 LIMIT）：预览重算跳过库内历史脏行用（图纸 2026-09-01 件①）。 */
    suspend fun latestVisibleMessages(conversationUuid: String, limit: Int): List<MessageEntity> =
        dao.latestVisibleMessages(conversationUuid, limit)

    /** 某会话当前仍暂扣待投递的消息（按计划投递时间升序）。 */
    suspend fun heldForConversation(conversationUuid: String): List<MessageEntity> =
        dao.heldForConversation(conversationUuid)

    /** 所有会话中计划投递时间已到（<= [now]）的暂扣消息（回前台兜底释放用）。 */
    suspend fun dueHeldMessages(now: Long): List<MessageEntity> = dao.dueHeldMessages(now)

    /** 所有仍暂扣的消息（关「忙碌延迟」总开关时全部释放用）。 */
    suspend fun allHeldMessages(): List<MessageEntity> = dao.allHeldMessages()

    /** 该角色名下任一会话是否有暂扣助手消息（未答恢复 busy-defer 判断；1:1 iOS isInBusyMode held 分支）。 */
    suspend fun hasHeldAssistantMessagesForCharacter(characterUuid: String): Boolean =
        dao.hasHeldAssistantMessagesForCharacter(characterUuid)

    /** 清除某会话所有暂扣消息（未答恢复前清残留 held，1:1 iOS dropStaleHeldMessages）。 */
    suspend fun dropHeldForConversation(conversationUuid: String) =
        dao.deleteHeldForConversation(conversationUuid)

    /** 释放一条暂扣消息为可见（D1e 列级写，只翻 isHeldForDelivery + 清 scheduledDeliveryDate）。 */
    suspend fun releaseHeldDelivery(uuid: String) = dao.releaseHeldDelivery(uuid)

    /** 忙碌延迟回复功能删除后的存量清扫（回前台调·幂等）：翻掉全部暂扣消息,防老数据永久隐藏。 */
    suspend fun releaseAllHeldMessages() = dao.releaseAllHeldMessages()

    /** 某会话最近一条非空用户消息的时间戳（无则 null）。未答恢复 busy 超时兜底窗口用。 */
    suspend fun lastUserMessageTimestamp(conversationUuid: String): Long? =
        dao.recentUserTimestamps(conversationUuid, 1).firstOrNull()

    /** Most recent [limit] messages, returned in chronological order (for prompt context). */
    suspend fun recentChronological(conversationUuid: String, limit: Int): List<MessageEntity> =
        dao.getRecent(conversationUuid, limit).asReversed()

    /** 近期「可见」消息（时间正序，排除暂扣未投递消息），供 B5 列表内联快捷回复面板预览。 */
    suspend fun recentVisibleChronological(conversationUuid: String, limit: Int): List<MessageEntity> =
        dao.getRecentVisible(conversationUuid, limit).asReversed()

    /** 指定线下见面会话的全部消息（升序）。1:1 iOS fetchOfflineMessagesForSession。 */
    suspend fun offlineSessionMessages(conversationUuid: String, sessionId: String): List<MessageEntity> =
        dao.offlineSessionMessages(conversationUuid, sessionId)

    /** 见面会话全部消息的【响应式】流（线下沉浸剧场用，全 session 不窗口化）。see [MessageDao.observeOfflineSessionMessages]. */
    fun observeOfflineSessionMessages(conversationUuid: String, sessionId: String): Flow<List<MessageEntity>> =
        dao.observeOfflineSessionMessages(conversationUuid, sessionId)

    /** 某会话指定 kind 的全部消息（升序）。供见面摘要兜底元数据（邀约卡发起方判定 / 入场标记，10.2d）。 */
    suspend fun messagesByKind(conversationUuid: String, kindRaw: String): List<MessageEntity> =
        dao.messagesByKind(conversationUuid, kindRaw)

    /** 按 sessionId 反查所属会话（经入场标记）。供见面摘要 24h 自愈手动重试反查会话（10.2d-3）。 */
    suspend fun conversationUuidForOfflineSession(sessionId: String): String? =
        dao.conversationUuidForSession(sessionId, MessageKind.OFFLINE_MARKER_START.raw)

    /**
     * 引用一期：为提示词窗口 [window] 预取「被引用消息」的时间戳 + 原始正文（图纸 §3.1）。
     *
     * 键集合 = 窗口里**真会产出引用行**的那些消息的 `quotedMessageUUID`（与 `PromptBuilderHistory` 的注入守卫
     * 同口径：`quotedContent` 非空才算），所以窗口里一条引用都没有时**零次查库**。命中窗口自身的消息直接复用
     * （被引用的多半就在窗口里），只有窗口外的才逐条 `getByUuid`；查不到（用户已删原消息）的 uuid **不进表**，
     * 由调用方走无锚降级 + 回退落库快照。
     */
    suspend fun quotedRefs(window: List<MessageEntity>): Map<String, QuotedMessageRef> {
        val needed = window.mapNotNullTo(LinkedHashSet()) { m ->
            m.quotedMessageUUID?.takeIf { it.isNotEmpty() && !m.quotedContent.isNullOrEmpty() }
        }
        if (needed.isEmpty()) return emptyMap()
        val inWindow = window.associateBy { it.messageUUID }
        val refs = LinkedHashMap<String, QuotedMessageRef>(needed.size)
        for (uuid in needed) {
            val target = inWindow[uuid] ?: dao.getByUuid(uuid) ?: continue
            // 复核 R1 🔴：只有「正文即人话」的目标才端原文。结构化卡的 content 是 JSON（红包 amount /
            // 礼物 cost），图片的是内部哨兵——2026-09-04 收紧之前这些都能被右滑引用，库里存得下老行；
            // 端回去等于拆掉 AssistantTurnController 落库侧那道脱敏。这类目标 rawContent=null，
            // 调用方回退落库快照；时间戳照给，老引用一样有时间锚。
            val plainText = MessageKind.fromRaw(target.messageKindRaw) == MessageKind.PLAIN_TEXT &&
                target.imageRelativePath == null
            refs[uuid] = QuotedMessageRef(
                timestampMillis = target.timestamp,
                rawContent = target.content.takeIf { plainText },
            )
        }
        return refs
    }

    suspend fun get(uuid: String): MessageEntity? = dao.getByUuid(uuid)
    suspend fun upsert(message: MessageEntity) = dao.upsert(message)

    /**
     * 删单条消息（14.7c：先清其磁盘媒体再删库行，堵单删媒体泄漏）。删会话走 [ConversationMediaCleaner]、
     * 删角色走 [CharacterDeletionCleaner] 各自清媒体；单删此前**独缺**——deleteByUuid 纯 SQL DELETE 不碰磁盘，
     * 用户删一条带图/语音消息 → 文件永久残留 filesDir，存储无界增长（对齐 iOS 周期 cleanupOrphanedFiles 的回收目标，
     * 安卓改「删时即清」=即时精确回收、规避 content_images/tts_audio 多源共享目录的全局扫误删风险）。
     * 媒体文件为该消息独占（每次保存铸新 UUID 文件），删之安全。
     */
    suspend fun deleteByUuid(uuid: String) {
        dao.mediaPathsForMessage(uuid)?.let { m ->
            AudioStore.delete(m.audioRelativePath)
            ContentImageStore.delete(m.imageRelativePath)
            ContentImageStore.delete(m.imageThumbnailRelativePath)
        }
        dao.deleteByUuid(uuid)
    }
}
