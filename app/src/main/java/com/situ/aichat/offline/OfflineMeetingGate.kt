package com.situ.aichat.offline

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.entity.ConversationEntity

/**
 * 「见面进行中」统一行为闸（契约 FABLE5_MEETING_SEAM_PROPOSAL §2·图纸 2026-08-26 卷一）。
 *
 * **语义：按会话不按全局**——谁在见面谁闭嘴，其他角色照常（用户拍板⑩「并发见面允许·闸门按会话」）。
 * 脏态（`isInOfflineMode=true` 而 `currentOfflineSessionId` 空）**视同见面中**（fail-closed：宁可多闭嘴，
 * 不穿帮）；该 fail-closed 只影响「闭嘴」，**不影响打标**——打标仍走
 * [OfflineChatVisibility.outgoingOfflineSessionId]（sessionId 空 → 打线上标，维持既有语义，
 * 防出现剧场收不到的孤儿线下消息）。
 *
 * 闸的形态一律「**if 见面 → 早退**」：else 路径（非见面）行为字节级不变。
 *
 * 消费方清单（**新增消费方须在此登记**）：
 * [com.situ.aichat.notification.ProactiveDeliveryPipeline] / [com.situ.aichat.proactive.ProactiveReplyDeliverer] /
 * [com.situ.aichat.notification.StreakNotificationBridgeService] / [com.situ.aichat.gift.ProactiveGiftExecutor] /
 * [com.situ.aichat.pet.PetChatBubbleService] / [com.situ.aichat.world.live.WorldVisitGreeter] /
 * [com.situ.aichat.world.notify.WorldNotifyService] / [com.situ.aichat.moments.MomentGenerationService] /
 * [com.situ.aichat.moments.MomentInteractionService] / [com.situ.aichat.moments.MomentNewPostNotifier] /
 * [com.situ.aichat.relationship.MilestoneCelebrationNotifier] / [com.situ.aichat.meeting.MeetingMissedReactionService] /
 * [com.situ.aichat.ui.chat.ChatOfflineController] / [com.situ.aichat.widget.CharacterStatusGlanceWidget] /
 * [com.situ.aichat.ui.chat.ChatListViewModel]。
 */
object OfflineMeetingGate {

    /** 会话级判定（已持有会话实体时用这个，零 IO）。会话为 null（不存在/已删）→ 不在见面。 */
    fun inMeeting(conversation: ConversationEntity?): Boolean =
        conversation?.isInOfflineMode == true

    /**
     * 角色级判定（只有 characterUuid 时用）：查该角色最近活跃会话
     * （[ConversationDao.latestActiveForCharacter]·与宠物气泡同一取会话口径）。无活动会话 → 不在见面。
     */
    suspend fun characterInMeeting(
        conversationDao: ConversationDao,
        characterUuid: String,
    ): Boolean = inMeeting(conversationDao.latestActiveForCharacter(characterUuid))
}
