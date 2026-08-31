package com.situ.aichat.foreground

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.situ.aichat.MainActivity
import com.situ.aichat.R
import com.situ.aichat.notification.NotificationChannels
import com.situ.aichat.notification.Notifier
import com.situ.aichat.util.AvatarStore

/**
 * 构建通用「后台 LLM/长生成」前台服务的常驻通知（⑤ Live Update）。抽成独立 object（同 [com.situ.aichat.voice.VoiceCallNotification]
 * 范式）便于无框架/仪器测试。
 *
 * 三态（照 [ForegroundActivity] 穷举·仲裁不在这儿，在 controller 双槽）：
 *  - null（备份导出 / 追更自动路等纯保活）→ 通用静默常驻通知（LOW 渠道 + VISIBILITY_SECRET·不打扰）。
 *  - [ForegroundActivity.StoryProgress]（故事章节生成）→ 四段确定性进度：API 36+ 用 `NotificationCompat.ProgressStyle`
 *    的 4 个 Segment（权重 15/60/17/8）+ 羽毛笔 tracker + `setRequestPromotedOngoing` 渲染成「灵动岛/进度药丸」
 *    (HyperOS 超级岛同款)，`setShortCriticalText` 放二字阶段词；API<36 优雅退化为经典进度条。
 *  - [ForegroundActivity.Typing]（用户等角色回复）→ 不确定进度 + 角色头像 largeIcon。
 *
 * **无百分比数字**：四段条 + 阶段词已达意，数字只会暴露「进度是估的」这件事。
 *
 * NotificationCompat 内部自带版本门控，促发不支持时只是不显示、不抛错。
 */
internal object LlmForegroundNotification {

    /** 药丸主题色（陶土玫·= 设计语言 accent 浅色档 #BE8A76）。 */
    private const val ACCENT_COLOR = 0xFFBE8A76.toInt()

    fun build(context: Context, activity: ForegroundActivity?): Notification {
        val builder = NotificationCompat.Builder(context, NotificationChannels.STORY_GENERATING)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        return when (activity) {
            null -> builder
                .setSmallIcon(R.drawable.ic_notif_story)
                .setContentTitle(context.getString(R.string.llm_foreground_notification_title))
                .setVisibility(NotificationCompat.VISIBILITY_SECRET) // 不在锁屏暴露「正在悄悄忙着」
                .setContentIntent(launchAppIntent(context))
                .build()

            is ForegroundActivity.StoryProgress -> builder
                .setSmallIcon(R.drawable.ic_notif_story)
                .setContentTitle("《${activity.title}》 第 ${activity.chapterNumber} 章")
                .setContentText(activity.phaseLabel)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 进度可见（用户主动发起的生成，乐见进度）
                .setColor(ACCENT_COLOR)
                .setContentIntent(
                    Notifier.storyDeepLinkIntent(context, LlmGenerationForegroundService.NOTIFICATION_ID, activity.storyId),
                )
                .applyStoryProgress(context, activity)
                .build()

            is ForegroundActivity.Typing -> builder
                .setSmallIcon(R.drawable.ic_notif_typing)
                .setContentTitle(activity.characterName)
                .setContentText(context.getString(R.string.notif_typing_body))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // 锁屏不暴露角色名 → 走 publicVersion 占位
                .setPublicVersion(typingPublicVersion(context))
                .setColor(ACCENT_COLOR)
                .setContentIntent(typingIntent(context, activity.conversationUuid))
                .apply { AvatarStore.loadBlocking(activity.avatarPath)?.let { setLargeIcon(it) } }
                .applyTypingProgress(context)
                .build()
        }
    }

    /** 四段确定性进度（API36+ 富渲染 / 以下经典条）。段权重与 `StoryProgressModel.SEGMENT_WEIGHTS` 同源口径。 */
    private fun NotificationCompat.Builder.applyStoryProgress(
        context: Context,
        activity: ForegroundActivity.StoryProgress,
    ): NotificationCompat.Builder {
        val pct = (activity.overall * 100).toInt().coerceIn(0, 100)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val style = NotificationCompat.ProgressStyle()
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(15))
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(60))
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(17))
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(8))
                .setProgress(pct)
                .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.ic_notif_feather))
            setStyle(style)
                .setShortCriticalText(activity.shortLabel)
                .setRequestPromotedOngoing(true)
        } else {
            setProgress(100, pct, false)
        }
    }

    /** typing = 不确定进度（等多久由模型说了算，估不出来也不该假估）。 */
    private fun NotificationCompat.Builder.applyTypingProgress(context: Context): NotificationCompat.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            setStyle(NotificationCompat.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(context.getString(R.string.notif_typing_short))
                .setRequestPromotedOngoing(true)
        } else {
            setProgress(0, 0, true)
        }

    /** 锁屏公开版：只说「有人在回你」，不暴露是谁。 */
    private fun typingPublicVersion(context: Context): Notification =
        NotificationCompat.Builder(context, NotificationChannels.STORY_GENERATING)
            .setSmallIcon(R.drawable.ic_notif_typing)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_typing_body))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    /** 点 typing 药丸 → 回到那个会话；会话 id 缺失时退回开 app。 */
    private fun typingIntent(context: Context, conversationUuid: String?): PendingIntent =
        conversationUuid?.let {
            PendingIntent.getActivity(
                context,
                LlmGenerationForegroundService.NOTIFICATION_ID,
                Notifier.conversationShortcutIntent(context, it),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } ?: launchAppIntent(context)

    /** 兜底点击：开 app（此前两态都无 contentIntent，点药丸没反应）。 */
    private fun launchAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        LlmGenerationForegroundService.NOTIFICATION_ID,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
