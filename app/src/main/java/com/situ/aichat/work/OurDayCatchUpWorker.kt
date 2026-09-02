package com.situ.aichat.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.situ.aichat.R
import com.situ.aichat.notification.NotificationChannels
import com.situ.aichat.ourdays.OurDayCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 「我们的日子」翻篇 worker（卷一图纸 §2.1 · 总图纸 §3.8）：**不含判定逻辑**——前台化（降级容错·照 [ScheduleGenerationWorker]）
 * → [OurDayCoordinator.catchUp] → success；异常 retry（指数退避 30s·已写页不回滚·E33）。
 * 三个唯一名：[UNIQUE_DAILY]（24h 周期·KEEP）/ [UNIQUE_ENSURE]（冷启 + 回前台一次性·KEEP）/ [UNIQUE_CONTINUE]（有剩余时协调器自排·REPLACE·60s）。
 */
@HiltWorker
class OurDayCatchUpWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: OurDayCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Log.i(TAG, "前台化不可用(按普通后台任务继续): ${it.message}") }
        coordinator.catchUp()
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "我们的日子 worker 异常，将重试", e)
        Result.retry()
    }

    /** 前台信息照 [ScheduleGenerationWorker.getForegroundInfo]：安静常驻渠道 + 专属标题 + VISIBILITY_SECRET + dataSync；id 避开 0x57081–0x57084。 */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        NotificationChannels.ensureCreated(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.STORY_GENERATING)
            .setSmallIcon(R.drawable.ic_notif_schedule)
            .setContentTitle(applicationContext.getString(R.string.notif_fg_our_days_title))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        return ForegroundInfo(FGS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val TAG = "OurDaysWorker"
        const val UNIQUE_DAILY = "our_days_daily"
        const val UNIQUE_ENSURE = "our_days_ensure"
        const val UNIQUE_CONTINUE = "our_days_continue"

        /** 前台服务常驻通知 id（总图纸 §3.8 锁定·避开 0x57081–0x57084）。 */
        private const val FGS_NOTIFICATION_ID = 0x57085
    }
}
