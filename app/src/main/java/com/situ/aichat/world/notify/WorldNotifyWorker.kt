package com.situ.aichat.world.notify

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
import com.situ.aichat.notification.NotificationChannels
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 世界到达通知蹦床 worker（W8 图纸 §3.2）：精确闹钟到点 → [com.situ.aichat.notification.NotificationAlarmReceiver] 入队本
 * worker（receiver 保持零 IO）→ [WorldNotifyService.fire] 走八道门验真再发。薄壳：inputData 只有 requestKey。
 *
 * 底座逐字仿 [com.situ.aichat.work.ProactiveNotificationWorker]（@HiltWorker + @AssistedInject·加急·[getForegroundInfo]）。
 * doWork 恒 [androidx.work.ListenableWorker.Result.success]——**失败不重试**（验真兜底·宁静默勿重复）。
 */
@HiltWorker
class WorldNotifyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val service: WorldNotifyService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val requestKey = inputData.getString(KEY_REQUEST_KEY) ?: return Result.success()
        runCatching { service.fire(requestKey, System.currentTimeMillis()) }
            .onFailure { Log.w(TAG, "世界到达 fire 失败(静默不重试): ${it.message}") }
        return Result.success() // 恒 success：失败不重试·验真兜底
    }

    /**
     * 加急任务的前台信息（minSdk=29 必需·同 [com.situ.aichat.work.ProactiveNotificationWorker.getForegroundInfo]）：API 29/30 上
     * 加急 worker 以短时前台服务实现，缺此 override 会运行期抛 IllegalStateException（API≥31 走 JobScheduler 加急任务·不调本方法）。
     * 复用故事生成的安静常驻渠道 + VISIBILITY_SECRET（锁屏不暴露）；本 worker 只查库发通知、前台窗口极短。
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        NotificationChannels.ensureCreated(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.STORY_GENERATING)
            .setSmallIcon(R.drawable.ic_notif_world)
            .setContentTitle(applicationContext.getString(R.string.notif_fg_world_title))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        return ForegroundInfo(FGS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "WorldNotifyWorker"
        private const val KEY_REQUEST_KEY = "requestKey"

        /** 加急前台服务常驻通知 id（避开故事 0x57081 / 主动通知 0x57082）。 */
        private const val FGS_NOTIFICATION_ID = 0x57083

        /**
         * 精确闹钟到点 → 入队本 worker（逐字仿 [com.situ.aichat.work.ProactiveNotificationWorker.enqueueFresh] 底座·加急无联网约束）。
         * uniqueName 含 requestKey → 用户腿/来访腿两枚不同闹钟同时到点互不吞（REPLACE 只作用于同 key 重复入队）。
         */
        fun enqueue(context: Context, requestKey: String) {
            val request = OneTimeWorkRequest.Builder(WorldNotifyWorker::class.java)
                .setInputData(workDataOf(KEY_REQUEST_KEY to requestKey))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "world_notify_$requestKey", ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}
