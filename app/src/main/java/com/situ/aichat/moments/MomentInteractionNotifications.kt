package com.situ.aichat.moments

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.model.MomentNotificationType
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 朋友圈互动通知：落库 + 清旧 + 系统通知。自 [MomentInteractionService] 只搬不改拆出（朋友圈发布页重构·甲
 * chunk 1·图纸 docs/handoff/2026-09-27-朋友圈发布页-甲-底子.md §2.1 / §9 ④）；通知稳定 id 单源仍在
 * [MomentInteractionService.interactionNotificationId]（删角色撤已弹的 [MomentNotificationPurger] 共用）。
 */
@Singleton
class MomentInteractionNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
    private val momentRepo: MomentRepository,
    private val characterRepo: CharacterRepository,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * 创建互动通知记录 + 顺手清理 30 天前已读通知（一次最多 50 条），1:1 iOS `createNotification`。
     * 通知驱动未读红点；系统通知投递 + 帖子详情深链随 7.2.7/7.2.8 路由落地（决策①）。
     */
    suspend fun create(
        type: MomentNotificationType,
        characterUuid: String,
        post: MomentPostEntity,
        contentPreview: String = "",
        nowMillis: Long,
    ) {
        momentRepo.addNotification(
            type = type,
            characterUuid = characterUuid,
            contentPreview = contentPreview,
            postTimestampMillis = post.timestamp,
        )
        momentRepo.deleteOldReadNotifications(nowMillis - THIRTY_DAYS_MS, NOTIFICATION_CLEANUP_LIMIT)
        // 决策①（P7.2.8）：互动同时发系统通知 + 深链进帖子详情（有意偏离 iOS app 内列表；铁律#1 原生达同效）。
        postSystemNotification(type, characterUuid, post, contentPreview)
    }

    /**
     * 朋友圈互动系统通知（决策①）。标题随类型 + 角色名（与 in-app 通知列表共用 4 类串）；正文 = 内容预览
     * （点赞类预览为空 → 仅标题）。notificationId 按 (类型,帖,角色) 稳定 → 同类重复互动替换不刷屏。无通知权限
     * 时 [Notifier] 静默跳过，in-app 红点/列表仍照常驱动。
     */
    private suspend fun postSystemNotification(
        type: MomentNotificationType,
        characterUuid: String,
        post: MomentPostEntity,
        contentPreview: String,
    ) {
        if (!settingsRepository.appSettings.first().notifMomentInteractionEnabled) return
        // 前台判定（卷一 C1）：App 前台（含见面剧场里）不弹横幅——App 内红点即提示（2-5b 拍板同源·
        // 对照 ChatReplyDeliverer.notifyIfNotViewing）。ProcessLifecycleOwner 纯 JVM 缺席 → runCatching 兜底。
        val appForeground = runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }.getOrDefault(false)
        if (appForeground) return
        val name = characterRepo.get(characterUuid)?.name ?: context.getString(R.string.moment_author_ai)
        val titleRes = when (type) {
            MomentNotificationType.COMMENT_ON_USER_POST -> R.string.moment_notif_title_comment
            MomentNotificationType.REPLY_TO_USER_COMMENT -> R.string.moment_notif_title_reply
            MomentNotificationType.LIKE_ON_USER_POST -> R.string.moment_notif_title_like
            MomentNotificationType.CO_LIKE -> R.string.moment_notif_title_colike
        }
        Notifier.postMomentInteraction(
            context = context,
            notificationId = MomentInteractionService.interactionNotificationId(type, post.uuid, characterUuid),
            title = context.getString(titleRes, name),
            body = contentPreview,
            postUuid = post.uuid,
        )
    }

    companion object {
        /** 通知清理阈值：30 天前已读，一次最多清 50 条（iOS createNotification）。 */
        private const val THIRTY_DAYS_MS = 30L * 24 * 3600 * 1000
        private const val NOTIFICATION_CLEANUP_LIMIT = 50
    }
}
