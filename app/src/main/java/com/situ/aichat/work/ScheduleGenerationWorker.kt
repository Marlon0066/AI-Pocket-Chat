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
import com.situ.aichat.prompt.schedule.ScheduleCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 后台日程生成任务（P5.1）。先补算最近缺失的历史日程（14.7a [ScheduleCoordinator.backfillMissedDays]），
 * 再为缺今日日程的角色生成今日日程（[ScheduleCoordinator.ensureTodaySchedules]）。
 * 由 [BackgroundScheduler] 排程（app 启动一次性 + 回前台一次性 + 每日周期）——三入口经此 worker 统一 backfill→ensure，
 * 一举对齐 iOS `runWithBackgroundRunner`（每次回前台）与 2AM `BGAppRefreshTask`（都先 backfill 后 ensure）。@HiltWorker 验证 5.0 注入链。
 *
 * **生成窗口前台化**（图纸 2026-07-10 日程专项 C2）：doWork 起手尝试 [setForeground]——抗国产 ROM 后台断网
 * （日程失败日志里的 connection abort 主源）+ 解除普通 worker ~10 分钟执行窗（思考模型 × 多角色可能超）。
 * Android 12+ app 在后台时系统可拒绝前台提升 → catch 降级按普通任务继续（前台化是增强不是依赖）。
 */
@HiltWorker
class ScheduleGenerationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: ScheduleCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Log.i(TAG, "前台化不可用(按普通后台任务继续): ${it.message}") }
        // 顺序关键：必须先补算历史缺日再 ensure 今天（否则今日生成会把补算锚点越过昨天，历史缺日永远补不上）。
        coordinator.backfillMissedDays()
        coordinator.ensureTodaySchedules()
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "日程生成 worker 异常，将重试", e)
        Result.retry()
    }

    /**
     * 长任务前台信息（图纸 C2·逐字仿 [ProactiveNotificationWorker.getForegroundInfo]）：复用故事生成的
     * 安静常驻渠道 + 分场景专属标题 + VISIBILITY_SECRET（锁屏不暴露），dataSync 类型（网络拉 LLM）。
     * 通知 id 用 [FGS_NOTIFICATION_ID]（避开 0x57081 故事 / 0x57082 proactive / 0x57083 world·notifreply）。
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        NotificationChannels.ensureCreated(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.STORY_GENERATING)
            .setSmallIcon(R.drawable.ic_notif_schedule)
            .setContentTitle(applicationContext.getString(R.string.notif_fg_schedule_title))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        return ForegroundInfo(FGS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val TAG = "ScheduleWorker"
        const val UNIQUE_DAILY = "schedule_daily_generation"
        const val UNIQUE_ENSURE_TODAY = "schedule_ensure_today"

        /** 失败角色的自动延迟重试（P15·P0-6）；REPLACE 唯一名 = iOS 单一 retry task。 */
        const val UNIQUE_RETRY = "schedule_retry"

        /** 日程生成前台服务常驻通知 id（图纸 C2·避开 0x57081/0x57082/0x57083）。 */
        private const val FGS_NOTIFICATION_ID = 0x57084
    }
}
