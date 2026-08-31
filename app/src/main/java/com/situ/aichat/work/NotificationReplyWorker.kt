package com.situ.aichat.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.situ.aichat.R
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.notification.NotificationChannels
import com.situ.aichat.notification.NotificationReplyThread
import com.situ.aichat.notification.Notifier
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.quickreply.ListQuickReplyService
import com.situ.aichat.ui.chat.MessagePreviewText
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 13.8·B1 通知直接回复加急 worker（**安卓超越 iOS**：iOS 通知零回复能力）。[com.situ.aichat.notification.NotificationReplyReceiver]
 * 取到通知栏回复文字后起本 worker，**一整轮对话在通知栏完成**、不用进 App：
 * 1. 立即回推「你刚打的那句 + 正在回复…」（[Notifier.postChatReply]）；
 * 2. 复用 B5 同款管线 [ListQuickReplyService.sendAndAwait]（落用户消息 + 占坑 + 跑完整一轮 LLM + 物化，**不重构
 *    ChatViewModel.runAssistantTurn**），等回合跑完；
 * 3. 读会话最近可见消息（含 AI 回复）回推最终态气泡（失败 → 「稍后回复你」，用户消息已落、恢复扫描兜底）；
 *    **目标会话在线下见面中**（卷一 A2b）→ 改回推「你那句 + 已送达 · 你们正在见面中」，不读可见消息（剧场叙事不外显）。
 *
 * 加急（[OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST]）= 用户发起的通知动作，HyperOS 通常允许起前台；配额耗尽
 * 降级普通任务但仍会跑。[getForegroundInfo] 在 minSdk 29/30 必需（复用故事生成的安静常驻渠道 + VISIBILITY_SECRET）。
 *
 * **有意简化**（同 [ListQuickReplyService] / 未答恢复路径）：纯文字、不打字动画、不走语音 / 工具卡 / 逐回合维护——
 * 留给用户下次正常进会话的前台回合兜账。
 */
@HiltWorker
class NotificationReplyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val listQuickReply: ListQuickReplyService,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val text = inputData.getString(KEY_TEXT)?.takeIf { it.isNotBlank() } ?: return Result.success()
        val characterId = inputData.getString(KEY_CHARACTER_ID)?.ifEmpty { null }
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val avatarPath = inputData.getString(KEY_AVATAR_PATH)?.ifEmpty { null }
        val notificationId = inputData.getInt(KEY_NOTIFICATION_ID, 0)
        // 主动消息 payload 的 conversationUuid 可能为空（角色尚无会话）→ 据 characterId 解析 / 建会话（对齐物化兜底）。
        val conversationUuid = inputData.getString(KEY_CONVERSATION)?.ifEmpty { null }
            ?: characterId?.let { conversationRepository.getOrCreateForCharacter(it, title) }
            ?: run {
                Log.w(TAG, "通知回复无会话且无角色，放弃")
                return Result.success()
            }

        val now = System.currentTimeMillis()
        // ① 立即回推：你刚打的那句 + 「正在回复…」（用户消息此刻尚未落库，合成展示；落库由 ② sendAndAwait 完成）。
        Notifier.postChatReply(
            applicationContext, notificationId, conversationUuid, characterId, title, avatarPath,
            messages = listOf(NotificationReplyThread.ReplyThreadMessage(text, isUser = true, timestamp = now)),
            statusHint = applicationContext.getString(R.string.notif_reply_status_replying),
        )

        // 见面闸（卷一 A2b）：目标会话正在线下见面 → 这一轮的 AI 回复是剧场叙事（带 [叙述]/[对话] 标签且被
        // 「见面细节不进日常聊天」的 SQL 滤掉），绝不能外显进通知栏。此处先读一次会话旗标，供 ③ 分终态。
        val inMeeting = OfflineMeetingGate.inMeeting(conversationRepository.get(conversationUuid))

        // ② 跑完整一轮（复用 B5 同款落库 + 占坑 + LLM + 物化；异常不外泄，按失败处理）。
        val ok = runCatching { listQuickReply.sendAndAwait(conversationUuid, text) }
            .onFailure { Log.w(TAG, "通知回复跑回合失败：$conversationUuid", it) }
            .getOrDefault(false)

        // ③-见面中（卷一 A2b·J4）：合成线程只放用户刚打的那句 + 「已送达 · 你们正在见面中」状态行——
        // 既不把剧场叙事外显进通知（OfflineChatVisibility 铁则），也不静默吞（用户要知道话到了）。
        // 故意**跳过** recentVisibleChronological：见面中刚落的两条会被「见面细节不进日常聊天」的 SQL 滤光，
        // 读出来的是更早的旧对话，回推等于把过期内容当新回复弹给用户。ok=false 走下面的现状 deferred 分支（E6）。
        if (ok && inMeeting) {
            Notifier.postChatReply(
                applicationContext, notificationId, conversationUuid, characterId, title, avatarPath,
                messages = listOf(NotificationReplyThread.ReplyThreadMessage(text, isUser = true, timestamp = now)),
                statusHint = applicationContext.getString(R.string.notif_reply_status_in_meeting),
            )
            return Result.success()
        }

        // ③ 回推最终态：读会话最近可见消息（含真用户消息 + AI 回复）建气泡；失败 → 「稍后回复你」（用户消息已落、恢复扫描兜底）。
        val recent = messageRepository.recentVisibleChronological(conversationUuid, NotificationReplyThread.MAX_THREAD_MESSAGES)
        val threadMessages = recent
            .map { NotificationReplyThread.ReplyThreadMessage(MessagePreviewText.forMessage(it), isUser = it.roleRaw == ROLE_USER, timestamp = it.timestamp) }
            .ifEmpty { listOf(NotificationReplyThread.ReplyThreadMessage(text, isUser = true, timestamp = now)) }
        Notifier.postChatReply(
            applicationContext, notificationId, conversationUuid, characterId, title, avatarPath,
            messages = threadMessages,
            statusHint = if (ok) null else applicationContext.getString(R.string.notif_reply_status_deferred),
        )
        return Result.success()
    }

    /**
     * 加急前台信息（minSdk 29/30 必需：加急任务以短时前台服务实现，缺此 override 运行期抛 IllegalStateException）。
     * 复用故事生成的安静常驻渠道 + VISIBILITY_SECRET（锁屏不暴露），dataSync 类型（拉 LLM）。窗口通常很短（一轮回复）。
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        NotificationChannels.ensureCreated(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.STORY_GENERATING)
            .setSmallIcon(R.drawable.ic_notif_typing)
            .setContentTitle(applicationContext.getString(R.string.notif_fg_notif_reply_title))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        return ForegroundInfo(FGS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "NotifReplyWorker"
        private const val ROLE_USER = "user"
        /** 加急前台服务常驻通知 id（避开故事 0x57081 / 智能合并 0x57082）。 */
        private const val FGS_NOTIFICATION_ID = 0x57083
        private const val KEY_CONVERSATION = "conversationUuid"
        private const val KEY_CHARACTER_ID = "characterId"
        private const val KEY_TITLE = "title"
        private const val KEY_AVATAR_PATH = "avatarPath"
        private const val KEY_NOTIFICATION_ID = "notificationId"
        private const val KEY_TEXT = "text"

        /**
         * 起加急回复 worker。唯一任务名按 [notificationId] 区分；REPLACE：同一通知短时间连发两次回复 → 最新覆盖（先前
         * 用户消息已落库不丢、由 sendAndAwait/恢复扫描兜底），与 [ProactiveNotificationWorker] 一致。
         */
        fun enqueue(
            context: Context,
            conversationUuid: String?,
            characterId: String?,
            title: String,
            avatarPath: String?,
            notificationId: Int,
            text: String,
        ) {
            val data = workDataOf(
                KEY_CONVERSATION to conversationUuid.orEmpty(),
                KEY_CHARACTER_ID to characterId.orEmpty(),
                KEY_TITLE to title,
                KEY_AVATAR_PATH to avatarPath.orEmpty(),
                KEY_NOTIFICATION_ID to notificationId,
                KEY_TEXT to text,
            )
            val request = OneTimeWorkRequest.Builder(NotificationReplyWorker::class.java)
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "notif_reply_$notificationId", ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}
