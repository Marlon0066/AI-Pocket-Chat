package com.situ.aichat.notification

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.situ.aichat.data.local.dao.NotificationDeliveryDao
import com.situ.aichat.data.local.dao.NotificationTemplateDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.prompt.notification.ProactiveMessageComposer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** 到点投递的输入（全部来自闹钟 inputData，自足、无内存态 → 重试可从头重跑）。 */
data class ProactiveDeliveryInput(
    val characterId: String,
    val category: String,
    val occasion: String?,
    val scheduledAt: Long,
)

/** 放弃原因（只进日志，不外显；枚举名即 Logcat 观测点）。 */
enum class ProactiveDropReason {
    CHARACTER_GONE, NOTIFICATIONS_OFF, CHARACTER_NOTIFICATIONS_OFF, STALE, QUIET_HOURS, SLEEPING,
    HOT, AFTERGLOW, UNANSWERED_STREAK, BACKOFF_WINDOW, DUPLICATE_BODY, EMPTY_BODY, RACE_NEW_MESSAGE,
    /** 目标会话正在线下见面中（卷一 B2）：人就在对面，绝不再从「手机那头」发消息。 */
    IN_MEETING,
}

/** 到点决策结果。投递动作留在 worker 壳，本管线只产决策与文案。 */
sealed interface ProactiveVerdict {
    /** 发：[foreground] 为真 → 静默物化进会话（不弹横幅）；否则走 Notifier 弹通知。 */
    data class Deliver(val body: String, val foreground: Boolean) : ProactiveVerdict

    /** 生成失败且还有重试额度 → worker 返回 Result.retry()（指数退避）。 */
    data object Retry : ProactiveVerdict

    /** 放弃（用户完全无感，就当 TA 今天没想起来说）。 */
    data class Drop(val reason: ProactiveDropReason) : ProactiveVerdict
}

/**
 * 到点决策管线（主动通知真实感改造 C5）：闸门 → 现做 → 兜底 → 竞态终查 → 产出投递指令。
 *
 * **原则：宁可不发，绝不发错**——主动消息会物化成聊天记录里的真实角色消息，错位内容当场假、
 * 带偏后续生成、污染长期记忆。故任一闸不过即静默放弃，绝不「顺延补发」。
 *
 * **幂等前提**：全部闸门只读，唯一写 = 投递成功后的 PendingDelivery/notify（在 worker 壳里）。
 * 失败前零写库副作用 → WorkManager 按 backoff 重投时整管线从头重跑，每次都重新把关（免费的正确性）。
 */
@Singleton
class ProactiveDeliveryPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val characterRepository: CharacterRepository,
    private val settingsRepository: SettingsRepository,
    private val scheduleDao: ScheduleDao,
    private val evaluator: ConversationStateEvaluator,
    private val composer: ProactiveMessageComposer,
    private val apiConfigRepository: ApiConfigRepository,
    private val templateDao: NotificationTemplateDao,
    private val deliveryDao: NotificationDeliveryDao,
    private val conversationRepository: ConversationRepository,
) {

    /**
     * 跑一遍到点决策。闸门顺序**锁定** a→g（图纸 §3.2）：保质期先于一切联网动作。
     * @param runAttemptCount WorkManager 的本次尝试序号（0 起）；≥[MAX_RETRY_ATTEMPT] 时不再重试、走兜底链。
     * @param isForeground 前台判定接缝（默认读 ProcessLifecycleOwner；测试注入假值）。
     */
    suspend fun execute(
        input: ProactiveDeliveryInput,
        runAttemptCount: Int,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        isForeground: suspend () -> Boolean = { readProcessForeground() },
    ): ProactiveVerdict {
        // a. 硬前提
        val character = characterRepository.get(input.characterId)
            ?: return ProactiveVerdict.Drop(ProactiveDropReason.CHARACTER_GONE)
        val settings = settingsRepository.getAppSettings()
        if (!settings.notificationsEnabled) {
            return ProactiveVerdict.Drop(ProactiveDropReason.NOTIFICATIONS_OFF)
        }
        if (!settingsRepository.isCharacterNotificationEnabled(input.characterId)) {
            return ProactiveVerdict.Drop(ProactiveDropReason.CHARACTER_NOTIFICATIONS_OFF)
        }

        // b. 保质期：由头过期就作废，绝不补发（真人不会早上补发昨晚没说的话）。先于联网动作。
        if (now - input.scheduledAt > FRESHNESS_WINDOW_MS) {
            return ProactiveVerdict.Drop(ProactiveDropReason.STALE)
        }

        // b2. 见面闸（卷一 B2·统一闸门三翼之「行为闸」）：目标会话正在线下见面 = 人就坐在对面，
        // 主动消息从「手机那头」冒出来当场穿帮。按会话判定（并发见面允许·拍板⑩），脏态视同见面
        // （fail-closed）。放在保质期之后、一切联网动作之前——不发就不必现做文案。
        conversationRepository.recentActiveConversationFor(input.characterId)?.let {
            if (OfflineMeetingGate.inMeeting(it)) return ProactiveVerdict.Drop(ProactiveDropReason.IN_MEETING)
        }

        // c. 免打扰（App 级，日程系统关也生效）
        if (settings.quietHoursEnabled &&
            NotificationScheduleRules.isInQuietHours(
                minuteOfDay(now, zone), settings.quietHoursStartMinute, settings.quietHoursEndMinute,
            )
        ) {
            return ProactiveVerdict.Drop(ProactiveDropReason.QUIET_HOURS)
        }

        // d. 睡眠（角色日程侧，与 c 独立叠加，任一命中即静默）
        if (settings.scheduleSystemEnabled && isSleeping(input.characterId, now, zone)) {
            return ProactiveVerdict.Drop(ProactiveDropReason.SLEEPING)
        }

        // e. 对话状态
        val state = evaluator.evaluate(input.characterId, now, zone)
        when (state.phase) {
            ConversationPhase.HOT -> return ProactiveVerdict.Drop(ProactiveDropReason.HOT)
            ConversationPhase.AFTERGLOW -> return ProactiveVerdict.Drop(ProactiveDropReason.AFTERGLOW)
            else -> Unit
        }
        if (state.unansweredProactiveCount >= UNANSWERED_LIMIT) {
            return ProactiveVerdict.Drop(ProactiveDropReason.UNANSWERED_STREAK)
        }
        backoffWindowMs(state.phase, state.daysSinceLastUserMessage)?.let { window ->
            if (deliveryDao.countDeliveredSince(input.characterId, now - window) > 0) {
                return ProactiveVerdict.Drop(ProactiveDropReason.BACKOFF_WINDOW)
            }
        }

        // f. 现做（失败 → 重试；重试用尽 → 兜底链）
        val config = apiConfigRepository.resolveConfigValues(ApiFunction.NOTIFICATION_TEMPLATE)
        val occasion = input.occasion?.takeIf { it.isNotBlank() } ?: ProactiveMessageComposer.FALLBACK_OCCASION
        val fresh = composer.compose(character, occasion, state, config, now, zone)
        val body = when {
            fresh != null -> fresh
            runAttemptCount < MAX_RETRY_ATTEMPT -> return ProactiveVerdict.Retry
            else -> resolveFallbackBody(input.characterId, input.category)
                ?: return ProactiveVerdict.Drop(ProactiveDropReason.EMPTY_BODY)
        }
        // 兜底文案查重（现做文案不查重：它本就是照当下状态写的，重复概率低且内容天然带时效）
        if (fresh == null && isDuplicateOfRecent(input.characterId, body)) {
            return ProactiveVerdict.Drop(ProactiveDropReason.DUPLICATE_BODY)
        }

        // g. 竞态终查：生成期间用户/系统写入了新消息 → 整条丢弃（错位内容比不发更糟）
        if (evaluator.latestMessageUuid(input.characterId) != state.latestMessageUuid) {
            return ProactiveVerdict.Drop(ProactiveDropReason.RACE_NEW_MESSAGE)
        }

        return ProactiveVerdict.Deliver(body, foreground = isForeground())
    }

    /** 兜底链：模板池 → 静态保底文案；全空 → null（宁可不发）。 */
    private suspend fun resolveFallbackBody(characterId: String, category: String): String? {
        val fallbackCategory = fallbackCategoryFor(category)
        val candidate = templateDao.pickUnused(characterId, fallbackCategory)
            ?: NotificationFallbackText.pick(context, fallbackCategory)
        return candidate.takeIf { it.isNotBlank() }
    }

    private suspend fun isDuplicateOfRecent(characterId: String, body: String): Boolean =
        deliveryDao.recentDeliveredBodies(characterId, DUPLICATE_LOOKBACK)
            .any { it.trim() == body.trim() }

    private suspend fun isSleeping(characterId: String, at: Long, zone: ZoneId): Boolean {
        val dayStart = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()
        val schedule = scheduleDao.scheduleFor(characterId, dayStart) ?: return false
        val events = scheduleDao.eventsForSchedule(schedule.uuid)
        return NotificationScheduleRules.shouldSkipWhileSleeping(true, events, at, zone)
    }

    companion object {
        /** 由头保质期 2h：到点被 ROM 拖延/重试拖过此窗即作废不补（图纸 §9 锁定）。 */
        internal const val FRESHNESS_WINDOW_MS = 7_200_000L

        /** 连发硬闸：已有 2 条未回应 → 冻结（用户一回复计数自然归零 = 瞬间解冻）。 */
        internal const val UNANSWERED_LIMIT = 2

        /** 重试上限：runAttemptCount ≥ 2（第 3 次尝试）不再重试，走兜底链。 */
        internal const val MAX_RETRY_ATTEMPT = 2

        /** 兜底查重回看条数。 */
        internal const val DUPLICATE_LOOKBACK = 3

        private const val SCHEDULE_CATEGORY_PREFIX = "schedule_"

        /** 降频窗（D-6）：距最后用户消息 4–7 天 → 24h；8–14 天 → 48h；≥15 天 → 168h（永不归零）。 */
        internal const val DISTANT_EARLY_WINDOW_MS = 24L * 60 * 60 * 1000
        internal const val DISTANT_LATE_WINDOW_MS = 48L * 60 * 60 * 1000
        internal const val LONG_ABSENCE_WINDOW_MS = 168L * 60 * 60 * 1000

        /**
         * 「看眼色」降频窗（D-6）。**是否降频**由相位决定（§3.5 尾：仅 DISTANT_EARLY / DISTANT_LATE /
         * LONG_ABSENCE 三相位叠加）；
         * **窗口大小**由「距最后一条**用户**消息」的天数决定（D-6 明文 + §3.5 步骤 5，null 视同 ≥15 天档）。
         * 返回 null = 该相位不降频。纯函数（internal 供单测）。
         */
        internal fun backoffWindowMs(phase: ConversationPhase, daysSinceLastUserMessage: Int?): Long? = when (phase) {
            ConversationPhase.DISTANT_EARLY,
            ConversationPhase.DISTANT_LATE,
            ConversationPhase.LONG_ABSENCE,
            -> when {
                daysSinceLastUserMessage == null -> LONG_ABSENCE_WINDOW_MS
                daysSinceLastUserMessage >= 15 -> LONG_ABSENCE_WINDOW_MS
                daysSinceLastUserMessage >= 8 -> DISTANT_LATE_WINDOW_MS
                daysSinceLastUserMessage >= 4 -> DISTANT_EARLY_WINDOW_MS
                else -> null // 相位为 DISTANT_* 时用户天数必 ≥ 消息天数 ≥ 4，此分支不可达
            }
            else -> null
        }

        /** 兜底取文案的类别：日程类无对应模板池 → 借 "random" 池；其余用自身类别。纯函数。 */
        internal fun fallbackCategoryFor(category: String): String =
            if (category.startsWith(SCHEDULE_CATEGORY_PREFIX)) "random" else category

        private fun minuteOfDay(at: Long, zone: ZoneId): Int =
            Instant.ofEpochMilli(at).atZone(zone).let { it.hour * 60 + it.minute }

        /** 前台判定：主线程读 ProcessLifecycleOwner；读失败按后台处理（照 ProactiveReplyDeliverer 范式）。 */
        private suspend fun readProcessForeground(): Boolean = runCatching {
            withContext(Dispatchers.Main) {
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
        }.getOrDefault(false)
    }
}
