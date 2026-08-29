package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.situ.aichat.offline.OfflineAfterglowService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration

/**
 * 见面后「余温消息」一次性延迟 worker（梦剧场 B 部·涟漪①·图纸 §3.10）：见面结束成功分支排一次（延迟
 * 135–225 分钟·[com.situ.aichat.work.BackgroundScheduler.scheduleOneShot]·首排 KEEP），到点驱动
 * [OfflineAfterglowService.maybeGenerate] 走四道守卫 + LLM 生成 + 落库 + 通知。
 *
 * **卷二 G2 自链重排**：到点时见面摘要还没熟（无行 / 还是即时要点骨架）→ 同 uniqueName 以 REPLACE 再排
 * [DEFER_DELAY_MINUTES] 分钟，至多 [MAX_DEFERS] 次；到顶仍未熟 → 带着即时要点骨架照发
 * （`acceptInstantRow=true`）。首排的 KEEP 不动；每次重排都重新过一遍全部守卫（用户回来聊过 → 守卫③让位）。
 *
 * **非加急**（仿 [com.situ.aichat.world.notify.WorldNotifyWorker] 但去 getForegroundInfo/setExpedited）：余温是
 * 低优先延迟消息，无需短时前台服务；requireNetwork=true 由 scheduler 加约束。doWork 恒
 * [androidx.work.ListenableWorker.Result.success]——守卫不满足/生成失败一律静默不重试（§3.10 拍板：不发模板兜底）。
 */
@HiltWorker
class OfflineAfterglowWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val service: OfflineAfterglowService,
    private val backgroundScheduler: BackgroundScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val conversationUuid = inputData.getString(KEY_CONVERSATION_UUID) ?: return Result.success()
        val characterUuid = inputData.getString(KEY_CHARACTER_UUID) ?: return Result.success()
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.success()
        val deferCount = inputData.getInt(KEY_DEFER_COUNT, 0)
        val outcome = runGuarded(conversationUuid, characterUuid, sessionId, acceptInstantRow = false)
        if (outcome == OfflineAfterglowService.AfterglowOutcome.DEFER_SUMMARY) {
            if (deferCount < MAX_DEFERS) {
                Log.d(TAG, "见面摘要未熟，余温延后 $DEFER_DELAY_MINUTES 分钟再看（第 ${deferCount + 1} 次）session=$sessionId")
                backgroundScheduler.scheduleOneShot(
                    uniqueName = uniqueName(sessionId),
                    workerClass = OfflineAfterglowWorker::class.java,
                    initialDelay = Duration.ofMinutes(DEFER_DELAY_MINUTES),
                    requireNetwork = true,
                    existingPolicy = ExistingWorkPolicy.REPLACE, // 自链改期：首排的 KEEP 不动，这里必须顶掉自己
                    inputData = workDataOf(
                        KEY_CONVERSATION_UUID to conversationUuid,
                        KEY_CHARACTER_UUID to characterUuid,
                        KEY_SESSION_ID to sessionId,
                        KEY_DEFER_COUNT to deferCount + 1,
                    ),
                )
            } else {
                // 到顶：不再等摘要，带着即时要点骨架照发（骨架有时段/地点/时长，够一条余温）。
                Log.i(TAG, "余温延后已达上限，带简版见面事实照发 session=$sessionId")
                runGuarded(conversationUuid, characterUuid, sessionId, acceptInstantRow = true)
            }
        }
        return Result.success() // 恒 success：守卫/生成失败均静默不重试
    }

    /** 生成异常一律吞成 [OfflineAfterglowService.AfterglowOutcome.HANDLED]（静默不重试，也不触发自链）。 */
    private suspend fun runGuarded(
        conversationUuid: String,
        characterUuid: String,
        sessionId: String,
        acceptInstantRow: Boolean,
    ): OfflineAfterglowService.AfterglowOutcome =
        runCatching { service.maybeGenerate(conversationUuid, characterUuid, sessionId, acceptInstantRow) }
            .onFailure { Log.w(TAG, "见面余温消息生成失败(静默不重试): ${it.message}") }
            .getOrDefault(OfflineAfterglowService.AfterglowOutcome.HANDLED)

    companion object {
        private const val TAG = "OfflineAfterglowWorker"

        const val KEY_CONVERSATION_UUID = "conversationUuid"
        const val KEY_CHARACTER_UUID = "characterUuid"
        const val KEY_SESSION_ID = "sessionId"

        /** 已延后次数（卷二 G2·首排不带此键 → 默认 0）。 */
        const val KEY_DEFER_COUNT = "deferCount"

        /** 摘要未熟时的复看间隔（卷二 M5 锁定值）。 */
        const val DEFER_DELAY_MINUTES = 30L

        /** 复看次数上限（卷二 M5 锁定值）；到顶带简版照发。 */
        const val MAX_DEFERS = 6

        /** 排程唯一任务名前缀（同 session 不重复入队·首排 existingPolicy=KEEP·自链 REPLACE）。 */
        fun uniqueName(sessionId: String): String = "offline_afterglow_$sessionId"
    }
}
