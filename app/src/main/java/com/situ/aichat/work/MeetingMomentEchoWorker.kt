package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.situ.aichat.moments.MeetingMomentEchoService
import com.situ.aichat.offline.MeetingMomentEchoPlanner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import kotlin.random.Random

/**
 * 见面后「朋友圈呼应帖」一次性延迟 worker（卷二 §5④·图纸 §3.3）：见面结束时掷点中签才排（首排延迟
 * 3–7 小时·KEEP），到点驱动 [MeetingMomentEchoService.maybePost] 走八道守卫。
 *
 * 两种改期都用同一 uniqueName 以 REPLACE 自链：条件不合适（又在见面 / 在睡 / 摘要没熟）→ 30 分钟后再看，
 * **计**次数至多 [MAX_DEFERS] 次、到顶带简版见面事实照发；撞上深夜 → 顺延到次日上午，**不计**次数
 * （那是时刻修正不是等材料）。doWork 恒 success：呼应发不出去一律静默（与朋友圈自动发帖同口径）。
 */
@HiltWorker
class MeetingMomentEchoWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val service: MeetingMomentEchoService,
    private val backgroundScheduler: BackgroundScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val conversationUuid = inputData.getString(KEY_CONVERSATION_UUID) ?: return Result.success()
        val characterUuid = inputData.getString(KEY_CHARACTER_UUID) ?: return Result.success()
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.success()
        val deferCount = inputData.getInt(KEY_DEFER_COUNT, 0)
        when (val outcome = runGuarded(conversationUuid, characterUuid, sessionId, acceptInstantRow = false)) {
            is MeetingMomentEchoService.EchoOutcome.DeferLateNight -> {
                Log.d(TAG, "见面呼应帖撞上深夜，顺延 ${outcome.minutes} 分钟 session=$sessionId")
                reschedule(conversationUuid, characterUuid, sessionId, outcome.minutes, deferCount) // 深夜不计次数
            }
            MeetingMomentEchoService.EchoOutcome.Defer -> {
                if (deferCount < MAX_DEFERS) {
                    Log.d(TAG, "见面呼应帖条件未就绪，延后 $DEFER_DELAY_MINUTES 分钟（第 ${deferCount + 1} 次）session=$sessionId")
                    reschedule(conversationUuid, characterUuid, sessionId, DEFER_DELAY_MINUTES, deferCount + 1)
                } else {
                    Log.i(TAG, "见面呼应帖延后已达上限，带简版见面事实再试一次 session=$sessionId")
                    runGuarded(conversationUuid, characterUuid, sessionId, acceptInstantRow = true)
                }
            }
            else -> Unit // Posted / Drop：这场见面到此为止
        }
        return Result.success() // 恒 success：守卫/生成失败一律静默不重试
    }

    /** 生成异常一律吞成 Drop（静默不重试，也不触发自链）。 */
    private suspend fun runGuarded(
        conversationUuid: String,
        characterUuid: String,
        sessionId: String,
        acceptInstantRow: Boolean,
    ): MeetingMomentEchoService.EchoOutcome =
        runCatching { service.maybePost(conversationUuid, characterUuid, sessionId, acceptInstantRow) }
            .onFailure { Log.w(TAG, "见面呼应帖失败(静默不重试): ${it.message}") }
            .getOrDefault(MeetingMomentEchoService.EchoOutcome.Drop)

    private fun reschedule(
        conversationUuid: String,
        characterUuid: String,
        sessionId: String,
        delayMinutes: Long,
        nextDeferCount: Int,
    ) = backgroundScheduler.scheduleOneShot(
        uniqueName = uniqueName(sessionId),
        workerClass = MeetingMomentEchoWorker::class.java,
        initialDelay = Duration.ofMinutes(delayMinutes),
        requireNetwork = true,
        existingPolicy = ExistingWorkPolicy.REPLACE, // 自链改期：首排的 KEEP 不动，这里必须顶掉自己
        inputData = workDataOf(
            KEY_CONVERSATION_UUID to conversationUuid,
            KEY_CHARACTER_UUID to characterUuid,
            KEY_SESSION_ID to sessionId,
            KEY_DEFER_COUNT to nextDeferCount,
        ),
    )

    companion object {
        private const val TAG = "MeetingMomentEchoWorker"

        const val KEY_CONVERSATION_UUID = "conversationUuid"
        const val KEY_CHARACTER_UUID = "characterUuid"
        const val KEY_SESSION_ID = "sessionId"

        /** 已延后次数（首排不带此键 → 默认 0）。 */
        const val KEY_DEFER_COUNT = "deferCount"

        /** 条件未就绪时的复看间隔（卷二 M5 锁定·与余温同值）。 */
        const val DEFER_DELAY_MINUTES = 30L

        /** 复看次数上限（卷二 M5 锁定·与余温同值）；到顶带简版照发。 */
        const val MAX_DEFERS = 6

        /** 排程唯一任务名（同 session 一条链·首排 KEEP·自链 REPLACE）。 */
        fun uniqueName(sessionId: String): String = "meeting_moment_echo_$sessionId"

        /**
         * 首排（卷二 §5④）：见面结束掷点中签后调一次——延迟 3–7 小时随机、KEEP（同 session 不重排）。
         * 掷点在调用方（见面结束那一刻掷完，未中签压根不排）；这里只管把任务放进队列。
         */
        fun scheduleFirst(
            scheduler: BackgroundScheduler,
            conversationUuid: String,
            characterUuid: String,
            sessionId: String,
        ) = scheduler.scheduleOneShot(
            uniqueName = uniqueName(sessionId),
            workerClass = MeetingMomentEchoWorker::class.java,
            initialDelay = Duration.ofMinutes(MeetingMomentEchoPlanner.initialDelayMinutes(Random)),
            requireNetwork = true,
            existingPolicy = ExistingWorkPolicy.KEEP,
            inputData = workDataOf(
                KEY_CONVERSATION_UUID to conversationUuid,
                KEY_CHARACTER_UUID to characterUuid,
                KEY_SESSION_ID to sessionId,
                KEY_DEFER_COUNT to 0,
            ),
        )
    }
}
