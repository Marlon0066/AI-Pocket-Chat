package com.situ.aichat.proactive

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.notification.NotificationPayload
import com.situ.aichat.notification.Notifier
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.prompt.AssistantOutputGate
import com.situ.aichat.prompt.MessageKindInference
import com.situ.aichat.prompt.MessageSplitter
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.ui.chat.MessagePreviewText
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 无头「TA 主动发来一条」统一投递器——见面余温（OfflineAfterglowService）与惦记回连（OpenLoopDueMessenger）
 * 共用（2026-07-07 用户拍板修订时自两处逐字重复的落库+通知段抽取）。
 *
 * 职责：把 LLM 生成的主动短消息按**普通聊天同口径**分段（[MessageSplitter] + 用户「回复分条」设置；
 * 原「单条可不分段」作废——空行/长句主动消息也要像真人发微信一样多气泡）→ 时间戳严格递增、整批 +
 * 会话预览翻转包同一事务（照 RecoveryReplyGenerator 原子写范式）→ 当场嵌入向量记忆 → App 真在后台/锁屏
 * 才弹通知（前台=列表红点即提示·复刻 ChatReplyDeliverer.notifyIfNotViewing·深链
 * [Notifier.ACTION_OPEN_CONVERSATION]）。
 */
@Singleton
class ProactiveReplyDeliverer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val vectorMemory: VectorMemoryService,
    private val db: AppDatabase,
) {

    /** 分段落库 + 会话预览 + 嵌入 + 后台通知。[logTag] 用于失败日志归属（余温/回连各自可辨）。 */
    suspend fun persistAndNotify(
        conversationUuid: String,
        character: CharacterEntity,
        settings: AppSettings,
        text: String,
        logTag: String,
    ) {
        // 见面闸（卷一 A7）：目标会话正在线下见面 = 人就在对面，绝不再从「手机那头」冒一条主动消息
        // （余温/惦记回连都属「隔着手机想起你」的话术，当场穿帮）。按会话判定，脏态视同见面（fail-closed）。
        if (OfflineMeetingGate.inMeeting(conversationRepo.get(conversationUuid))) {
            Log.i(logTag, "目标会话见面中，放弃投递")
            return
        }
        val range = settings.sanitizedReplySegmentRange
        val split = MessageSplitter.split(text, maxSegments = range.last, minSegments = range.first)
            .ifEmpty { listOf(text) }
        // 落库前置闸（图纸 2026-09-01 件①）：判脏的段丢弃不落库；kind 与下方 entities 同口径。
        // 主动消息无重试链（无用户在等），整条判脏即放弃投递——宁可这次不出声，也不让脏内容进库污染后续提示词。
        val segments = AssistantOutputGate.filterSegments(split, isOfflineMode = false, source = logTag)
        if (segments.isEmpty()) {
            Log.i(logTag, "主动消息整条判脏，放弃投递")
            return
        }
        val baseTs = System.currentTimeMillis()
        val entities = segments.mapIndexed { index, segment ->
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "assistant",
                content = segment,
                messageKindRaw = MessageKindInference.forAssistantText(segment, isOfflineMode = false).raw,
                timestamp = baseTs + index,
                isOfflineMode = false,
            )
        }
        db.withTransaction {
            entities.forEach { messageRepo.upsert(it) }
            conversationRepo.applyMaterialization(
                conversationUuid = conversationUuid,
                preview = StickerTagParser.replaceStickerTagsForDisplay(segments.last()).take(60),
                timestamp = entities.last().timestamp,
                markReadNow = false,
            )
        }
        runCatching {
            messageRepo.recentChronological(conversationUuid, segments.size).forEach { vectorMemory.embedMessageIfNeeded(it) }
        }
        notifyIfBackground(conversationUuid, character, settings, entities.last(), logTag)
    }

    /** 仅 App 真在后台/锁屏时弹（前台=列表红点即提示）。通知正文取末段=与会话预览同源。 */
    private fun notifyIfBackground(
        conversationUuid: String,
        character: CharacterEntity,
        settings: AppSettings,
        entity: MessageEntity,
        logTag: String,
    ) {
        if (!settings.notificationsEnabled) return
        val body = MessagePreviewText.forMessage(entity).take(100)
        if (body.isBlank()) return
        runCatching {
            val appForeground = ProcessLifecycleOwner.get()
                .lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (appForeground) return@runCatching
            Notifier.post(
                context,
                NotificationPayload(
                    notificationId = "chat_reply_$conversationUuid".hashCode(),
                    title = character.name,
                    body = body,
                    conversationUuid = conversationUuid,
                    characterId = character.uuid,
                    avatarPath = character.avatarPath,
                ),
            )
        }.onFailure { Log.w(logTag, "主动消息通知投递失败: ${it.message}") }
    }
}
