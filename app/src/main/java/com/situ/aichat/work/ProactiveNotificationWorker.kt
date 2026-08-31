package com.situ.aichat.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.NotificationDeliveryDao
import com.situ.aichat.notification.NotificationChannels
import com.situ.aichat.notification.NotificationPayload
import com.situ.aichat.notification.Notifier
import com.situ.aichat.notification.PendingDeliveryStore
import com.situ.aichat.notification.ProactiveDeliveryInput
import com.situ.aichat.notification.ProactiveDeliveryPipeline
import com.situ.aichat.notification.ProactiveVerdict
import com.situ.aichat.notification.StreakNotificationBridgeService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * 到点现做主动消息的加急 worker（主动通知真实感改造 C5 起**壳化**）。
 *
 * 触发底座：精确闹钟到点唤醒 [com.situ.aichat.notification.NotificationAlarmReceiver]（App 被杀也弹），
 * receiver 经 [enqueueFresh] 起本加急 worker——把会超时的 LLM 调用挪出 receiver 的 ~10s 窗口。
 *
 * **本类只是壳**：解 inputData → 调 [ProactiveDeliveryPipeline] → 按 [ProactiveVerdict] 行动
 * （弹通知 / 静默物化 / 重试 / 放弃）。一切闸门与文案决策都在 pipeline 里（纯类·MockK 可测，D-14）。
 *
 * 失败重试走 WorkManager 原生指数退避（初始 2min → 4min，第 3 次尝试改走兜底链）；每次 attempt 都整管线
 * 重跑 → 状态闸/保质期每次重新把关（免费的正确性）。
 */
@HiltWorker
class ProactiveNotificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val pipeline: ProactiveDeliveryPipeline,
    private val bridge: StreakNotificationBridgeService,
    private val deliveryDao: NotificationDeliveryDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val characterId = inputData.getString(KEY_CHARACTER_ID) ?: return Result.success()
        val category = inputData.getString(KEY_CATEGORY) ?: return Result.success()
        val title = inputData.getString(KEY_TITLE) ?: return Result.success()
        val conversationUuid = inputData.getString(KEY_CONVERSATION)?.ifEmpty { null }
        val notificationId = inputData.getInt(KEY_NOTIFICATION_ID, characterId.hashCode())
        val deliveryIdentifier = inputData.getString(KEY_DELIVERY_ID)?.ifEmpty { null }
        val requestKey = inputData.getString(KEY_REQUEST_KEY)?.ifEmpty { null }
        val scheduledAt = inputData.getLong(KEY_SCHEDULED_AT, 0L)
        val badgeCount = inputData.getInt(KEY_BADGE, 0)
        val avatarPath = inputData.getString(KEY_AVATAR_PATH)?.ifEmpty { null }
        val occasion = inputData.getString(KEY_OCCASION)?.ifEmpty { null }

        val verdict = pipeline.execute(
            ProactiveDeliveryInput(characterId, category, occasion, scheduledAt),
            runAttemptCount,
        )

        return when (verdict) {
            is ProactiveVerdict.Drop -> {
                // 用户完全无感——就当 TA 今天没想起来说。原因枚举名 = Logcat 观测点。
                Log.i(TAG, "主动消息放弃：$characterId [$category] reason=${verdict.reason.name}")
                Result.success()
            }
            ProactiveVerdict.Retry -> {
                Log.w(TAG, "主动消息现做失败，退避重试：$characterId [$category] attempt=$runAttemptCount")
                Result.retry()
            }
            is ProactiveVerdict.Deliver -> {
                val payload = NotificationPayload(
                    notificationId = notificationId,
                    title = title,
                    body = verdict.body,
                    conversationUuid = conversationUuid,
                    characterId = characterId,
                    deliveryIdentifier = deliveryIdentifier,
                    category = category,
                    requestKey = requestKey,
                    scheduledAtMillis = scheduledAt,
                    badgeCount = badgeCount,
                    avatarPath = avatarPath,
                )
                if (verdict.foreground) {
                    materializeSilently(payload)
                    Log.i(TAG, "主动消息静默落库（App 在前台，不弹横幅）：$title [$category]")
                } else {
                    Notifier.post(applicationContext, payload)
                    markDeliveredInLedger(deliveryIdentifier, verdict.body)
                    Log.i(TAG, "主动消息已发：$title [$category]")
                }
                Result.success()
            }
        }
    }

    /**
     * 后台分支（R1 🔴-1）：投递成功即在台账置 deliveredAt + 正文。
     *
     * 三闸（连发 / 降频 / 查重）只认 `deliveredAt` 非空的行，而该列原本只在**回前台物化 / 点通知**时才置位
     * ——「用户不开 App」恰是拍板⑤⑥要收紧的主场景，读数却恒 0、闸永不合拢。故后台弹完当场置位。
     *
     * 正文同写：台账在排程时存的是空串（正文到点才现做），不回写则查重闸永远比不到东西。
     * 与既有 drain 回灌天然幂等互斥——它只在 `deliveredAt == null` 时动作，本 UPDATE 的
     * `WHERE deliveredAt IS NULL` 守卫同样只认未置位的行，谁先到都不覆盖对方的值。
     *
     * 边界：无通知权限时 [Notifier.post] 静默不弹也会走到这里置位 → 方向保守（宁可少发），可接受。
     * 前台分支不走本方法：[materializeSilently] 经既有物化管线（drain）已即时置位。
     */
    private suspend fun markDeliveredInLedger(deliveryIdentifier: String?, body: String) {
        val deliveryId = deliveryIdentifier ?: return
        deliveryDao.markDelivered(deliveryId, System.currentTimeMillis(), body)
    }

    /**
     * 前台分支（D-11）：不弹横幅，直接落投递标记 + 当场物化成会话消息（列表红点即提示）。
     * 字段组法照 [Notifier.post] 的 PendingDelivery 同源；物化自带 Mutex 防并发。
     */
    private suspend fun materializeSilently(payload: NotificationPayload) {
        val deliveryId = payload.deliveryIdentifier ?: return
        PendingDeliveryStore.appendDelivered(
            applicationContext,
            PendingDeliveryStore.PendingDelivery(
                deliveryIdentifier = deliveryId,
                characterId = payload.characterId.orEmpty(),
                category = payload.category.orEmpty(),
                conversationUuid = payload.conversationUuid.orEmpty(),
                notificationBody = payload.body,
                requestIdentifier = payload.requestKey ?: deliveryId,
                scheduledAt = payload.scheduledAtMillis,
                deliveredAt = System.currentTimeMillis(),
            ),
        )
        bridge.materializeDeliveredNotifications()
    }

    /**
     * 加急任务的前台信息（minSdk=29 必需：API 29/30 上加急 worker 以短时前台服务实现，缺此 override 会运行期抛
     * IllegalStateException；API≥31 走 JobScheduler 加急任务、不调本方法）。复用故事生成的安静常驻渠道 +
     * VISIBILITY_SECRET（锁屏不暴露），dataSync 类型（拉 LLM）。
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        NotificationChannels.ensureCreated(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.STORY_GENERATING)
            .setSmallIcon(R.drawable.ic_notif_companion)
            .setContentTitle(applicationContext.getString(R.string.notif_fg_proactive_title))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        return ForegroundInfo(FGS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val TAG = "ProactiveNotifWorker"

        /** 加急前台服务的常驻通知 id（避开故事前台服务 0x57081 与各类业务通知 id）。 */
        private const val FGS_NOTIFICATION_ID = 0x57082

        /** 唯一任务名后缀：与调度侧 enqueueUniqueWork(requestKey) 错开（闹钟已触发，此 worker 无需被取消）。 */
        private const val WORKER_FIRE_SUFFIX = "_fire"

        /** 退避初值（分钟）：2 → 4；第 3 次尝试不再重试，走兜底链（图纸 §9 锁定）。 */
        private const val BACKOFF_MINUTES = 2L

        /**
         * 精确闹钟到点 → receiver 调本方法起一个**加急** worker 现做文案。加急
         * （[OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST]）= 尽快跑、不挂联网约束（无网时管线自动
         * 走重试/兜底链）；配额耗尽则降级为普通任务但仍会跑。从 [payload] 搬运 worker 所需全部字段——
         * **不再搬预烤正文**（正文一律到点现做/现取，body 在排程期恒空串）。
         */
        fun enqueueFresh(context: Context, payload: NotificationPayload) {
            val requestKey = payload.requestKey ?: return
            val data = workDataOf(
                KEY_CHARACTER_ID to payload.characterId.orEmpty(),
                KEY_CATEGORY to payload.category.orEmpty(),
                KEY_TITLE to payload.title,
                KEY_CONVERSATION to payload.conversationUuid.orEmpty(),
                KEY_NOTIFICATION_ID to payload.notificationId,
                KEY_DELIVERY_ID to payload.deliveryIdentifier.orEmpty(),
                KEY_REQUEST_KEY to requestKey,
                KEY_SCHEDULED_AT to payload.scheduledAtMillis,
                KEY_BADGE to payload.badgeCount,
                KEY_AVATAR_PATH to payload.avatarPath.orEmpty(),
                KEY_OCCASION to payload.occasion.orEmpty(),
            )
            val request = OneTimeWorkRequest.Builder(ProactiveNotificationWorker::class.java)
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                requestKey + WORKER_FIRE_SUFFIX, ExistingWorkPolicy.REPLACE, request,
            )
        }

        const val KEY_CHARACTER_ID = "characterId"
        const val KEY_CATEGORY = "category"
        const val KEY_TITLE = "title"
        const val KEY_CONVERSATION = "conversationUuid"
        const val KEY_NOTIFICATION_ID = "notificationId"
        const val KEY_DELIVERY_ID = "deliveryIdentifier"
        const val KEY_REQUEST_KEY = "requestKey"
        const val KEY_SCHEDULED_AT = "scheduledAt"
        const val KEY_BADGE = "badgeCount"
        const val KEY_AVATAR_PATH = "avatarPath"
        const val KEY_OCCASION = "occasion"
    }
}
