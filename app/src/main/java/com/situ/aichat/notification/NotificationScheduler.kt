package com.situ.aichat.notification

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.util.StreakManager
import com.situ.aichat.util.StreakStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主动消息调度器（P6.1c；主动通知真实感改造 C4 起「只定时刻与由头」）。根据「火花状态 + 今日日程」为每个
 * 角色安排主动消息的**触发时刻**，避开睡觉时段与夜间免打扰，每天每角色 ≤3 条。
 *
 * **本调度器不再产生任何文案、不再调 LLM**（改造前：排程时批量预烤 + 当日缓存）。排程只记下「什么时候、
 * 因为什么事想找你」（时刻 + occasion 由头），话到那一刻由 `ProactiveDeliveryPipeline` / `ProactiveMessageComposer`
 * 看着真实对话状态现写——错位内容会物化进聊天记录当场假，故原则是**宁可不发，绝不发错**。
 *
 * 时刻来源两支：① 日程支（主路径）= 事件结束 + 确定性抖动（0..15min，djb2 种子；不再对齐活跃桶）；
 * ② 回退支（无日程）= 火花状态选类别，时机交 [NotificationTimePlanner]。到点文案链（模板 → 保底文案）
 * 由到点侧负责，本文件只烤空 body 的精确闹钟（[NotificationAlarmScheduler]，App 被杀也弹、HyperOS 最稳）。
 *
 * 安卓无「列出待发闹钟」API，故用 [NotificationSchedulerStore] 自记账（取消旧 / 跨角色错峰 / 重建判定）。
 * **学习反馈层**留 6.1e；**通知落成聊天消息**留 6.1d。
 *
 * 当前时刻 / 时区一律取自注入的 [clock]（生产绑定=系统钟，见 di.ClockModule；测试给 `Clock.fixed`
 * 钉死——抖动散列吃日期、睡眠/免打扰闸吃时段，不钉死则断言随测试在哪天几点跑漂移）。
 */
@Singleton
class NotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val characterRepository: CharacterRepository,
    private val scheduleDao: ScheduleDao,
    private val conversationDao: ConversationDao,
    private val settingsRepository: SettingsRepository,
    private val alarmScheduler: NotificationAlarmScheduler,
    private val store: NotificationSchedulerStore,
    private val learningService: NotificationLearningService,
    private val activityBucketAnalyzer: ActivityBucketAnalyzer,
    private val clock: Clock,
) {

    // MARK: - 公共入口

    /** 为所有角色调度（开 App / 每日 worker / 开机重排时调）。全局开关关 → 撤销全部。 */
    suspend fun scheduleAll() {
        val settings = settingsRepository.getAppSettings()
        if (!settings.notificationsEnabled) {
            Log.d(TAG, "全局通知开关关闭，撤销所有已排通知")
            cancelAll()
            return
        }
        // P6.1e：每次全量重排先清算过期台账（>2h 未响应 → 负反馈），对齐 iOS scheduleAllNotifications。
        learningService.finalizeExpiredRecords()
        val characters = characterRepository.observeAll().first()
        // C1#2：per-character 隔离——单个角色调度抛异常（精确闹钟配额、坏数据等）不应中断整轮，
        // 否则后续角色全部漏排（HyperOS 配额尤甚）。逐角色 try/catch，失败记日志并继续下一个。
        for (character in characters) {
            try {
                scheduleInternal(character, settings)
            } catch (e: Exception) {
                Log.w(TAG, "角色通知调度失败，跳过该角色继续: ${character.uuid}", e)
            }
        }
        Log.d(TAG, "已为 ${characters.size} 个角色完成通知调度")
    }

    /** 为单个角色调度（发完消息后 / 偏好变更时调）。 */
    suspend fun schedule(character: CharacterEntity) {
        val settings = settingsRepository.getAppSettings()
        if (!settings.notificationsEnabled) {
            cancel(character)
            return
        }
        scheduleInternal(character, settings)
    }

    /** 角色每角色开关变更：开 → 重排；关 → 撤销。对齐 iOS `handlePreferenceChange`。 */
    suspend fun handlePreferenceChange(character: CharacterEntity, isEnabled: Boolean) {
        if (isEnabled) schedule(character) else cancel(character)
    }

    /** 撤销某角色全部已排通知（两种模式都撤 + 把未投递的 scheduled 台账标 canceled）。 */
    suspend fun cancel(character: CharacterEntity) = cancelCharacter(character.uuid)

    /**
     * 撤销某角色已排通知 + 把对应**未投递**(deliveredAt==null)的 scheduled 台账标 canceled
     * （对齐 iOS cancelNotifications→cancelScheduledNotifications；已投递的不动，仍能等响应/过期反馈）。
     */
    private suspend fun cancelCharacter(characterId: String) {
        val keys = store.scheduledFor(characterId).map { it.requestKey }
        cancelByCharacterId(characterId)
        learningService.cancelScheduled(keys)
    }

    private fun cancelByCharacterId(characterId: String) {
        val workManager = WorkManager.getInstance(context)
        store.scheduledFor(characterId).forEach {
            alarmScheduler.cancel(it.requestKey)        // 可靠优先：精确闹钟
            workManager.cancelUniqueWork(it.requestKey) // 最新优先：WorkManager（切模式后也清掉旧路）
        }
        store.clearScheduled(characterId)
    }

    /** 撤销所有角色全部已排通知（全局关 / 退出时）。 */
    suspend fun cancelAll() {
        store.allCharacterIds().toList().forEach { cancelCharacter(it) }
    }

    /**
     * 删除角色时清掉它的全部通知调度状态（1:1 iOS removeSystemNotifications + cleanupNotificationData 的
     * UserDefaults 清理部分）：撤销待发闹钟/WorkManager + 清调度 registry（cancel）+ 清状态快照 + 清「今天已判断」标记。
     * 通知台账 DB 记录（模板/投递/窗口统计）由 [com.situ.aichat.data.repository.CharacterDeletionCleaner] 另删。
     */
    suspend fun purgeCharacterState(character: CharacterEntity, conversationUuids: List<String>) {
        // P1-25：必须在 CharacterDeletionCleaner 删台账（deleteForCharacter）之前调——日历 key 不含
        // characterId，只能经台账 characterId 列定位（两处互指注释，复核专查此顺序）。
        val ledgerKeys = learningService.requestKeysFor(character.uuid)
        cancel(character)
        store.clearSnapshot(character.uuid)
        store.clearRandomDecidedDate(character.uuid)
        // P1-25：撤已弹通知（1:1 iOS removeSystemNotifications 的 deliveredNotifications 半边；删角色后台账被删，
        // 「物化后 cancel」机制对已删角色 skip，已弹未读通知否则成永久孤儿）。
        val nm = NotificationManagerCompat.from(context)
        purgeNotificationIds(character.uuid, conversationUuids, ledgerKeys).forEach { nm.cancel(it) }
        // 拆待发闹钟（=iOS removePendingNotificationRequests 半边）：台账内不在调度 registry 的（日历；删台账后
        // cancelStale 路径永远够不着）。忙碌回复段闹钟已随功能删除（2026-07-11）——老版本可能残留的忙碌闹钟
        // 到点只弹一次性通知（不物化,仅跳转）,无害自然耗尽。
        ledgerKeys.forEach { alarmScheduler.cancel(it) }
    }

    /**
     * 「正在看该会话 → 撤回 15 分钟内将触发的该角色通知，并重排」。对齐 iOS
     * `suppressImpendingNotificationsIfNeeded`。由 ChatViewModel 打开会话时调。
     */
    suspend fun suppressImpending(characterUuid: String, thresholdMillis: Long = 15L * 60 * 1000) {
        if (!settingsRepository.isCharacterNotificationEnabled(characterUuid)) return
        val now = clock.millis()
        val current = store.scheduledFor(characterUuid)
        val impending = current.filter { it.fireAtMillis - now in 1..thresholdMillis }
        if (impending.isEmpty()) return
        val workManager = WorkManager.getInstance(context)
        impending.forEach {
            alarmScheduler.cancel(it.requestKey)
            workManager.cancelUniqueWork(it.requestKey)
        }
        store.setScheduled(characterUuid, current - impending.toSet())
        learningService.cancelScheduled(impending.map { it.requestKey })
        Log.d(TAG, "用户正在看会话，撤回即将触发的通知后重排：$characterUuid")
        characterRepository.get(characterUuid)?.let { schedule(it) }
    }

    // MARK: - 单角色调度核心（对齐 iOS scheduleNotifications）

    private suspend fun scheduleInternal(character: CharacterEntity, settings: AppSettings) {
        val charId = character.uuid
        // 每角色开关（默认开）。关 → 撤销并清快照（重新开启时会重建）。
        if (!settingsRepository.isCharacterNotificationEnabled(charId)) {
            cancelCharacter(charId)
            store.clearSnapshot(charId)
            return
        }

        val now = clock.millis()
        val zone: ZoneId = clock.zone
        val status = StreakManager.checkStreak(character, now, zone)
        val streakLabel = StreakManager.streakStatusLabel(status)
        val todayString = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val eventCount = todayEventCount(charId, now, zone)

        // 跳过判断：已有待发 + 真实闹钟仍活着 + 状态没变（日期 / 火花 / 事件数）→ 不重建。
        // E2 修：仅查台账非空不够——registry 存 SharedPreferences 跨重启/force-stop 存活，而 AlarmManager 精确闹钟
        // 不跨重启/force-stop。若只看台账非空就跳过，重启后开机重排会被这条跳过架空（台账说已排、实际闹钟已没）→
        // 主动消息当日静默断档至多一整天。故须探测真实闹钟存活：registry 任一闹钟仍 live 才允许跳过；全没了（重启/
        // force-stop 清掉）则强制重排。三触发口（启动/每日/开机）共享此判定，自愈且不误触 LLM 重建（探测为廉价 binder 调用）。
        val existing = store.scheduledFor(charId)
        if (existing.isNotEmpty() &&
            existing.any { alarmScheduler.isAlarmLive(it.requestKey) } &&
            !NotificationSchedulerStore.shouldRebuild(store.snapshot(charId), todayString, streakLabel, eventCount)
        ) {
            return
        }

        // 是否首次调度（iOS 用 NotificationDeliveryRecord；6.1c 无该表，用「从未存过快照」近似，6.1e 精确化）。
        val isFirstSchedule = store.snapshot(charId) == null

        // 删旧（两种模式都撤 + 把未投递的旧 scheduled 台账标 canceled）
        cancelCharacter(charId)

        val activityBuckets = activityBucketAnalyzer.analyzeActivityBucketMinutes(charId, now, zone)
        val reservedDates = store.reservedFireTimesExcluding(charId).toMutableList()
        val scheduled = mutableListOf<NotificationSchedulerStore.ScheduledRef>()
        val conversationUuid = conversationDao.getByCharacter(charId).firstOrNull()?.uuid
        // app 图标角标基数（P6.1e）：未读会话计数和；每条通知烤进递增角标值 baseUnread + 本次序号（对齐 iOS）。
        val baseUnread = conversationDao.totalUnread()

        // ── 日程驱动分支（主路径）：纯本地组「由头排程」，零 LLM ──
        // 时刻挂事件本身（结束 + 确定性抖动），不再对齐活跃桶：TA 忙完这件事顺手发消息才像真人（V3）。
        val todayEvents = selectTodayNotificationEvents(charId, now, zone)
        if (todayEvents.isNotEmpty()) {
            todayEvents.take(MAX_DAILY_NOTIFICATIONS).forEachIndexed { index, event ->
                scheduleAtEvent(
                    charId, character.name, index, event, reservedDates, scheduled,
                    conversationUuid, settings, baseUnread, now, zone,
                )
            }
            store.setScheduled(charId, scheduled)
            store.saveSnapshot(charId, NotificationSchedulerStore.Snapshot(todayString, streakLabel, eventCount))
            Log.i(TAG, "日程驱动通知已排：${character.name}（${scheduled.size} 条）")
            return
        }

        // ── 回退分支（无日程 / 无合适事件）：火花状态选类别，时机交 planner ──
        var scheduledCount = 0
        suspend fun trySchedule(
            category: String,
            daysFromNow: Int,
            prefersTodayForFirstSchedule: Boolean,
            firstSchedule: Boolean,
        ) {
            if (scheduledCount >= MAX_DAILY_NOTIFICATIONS) return
            scheduleNotification(
                charId, character.name, category,
                daysFromNow, prefersTodayForFirstSchedule, firstSchedule,
                activityBuckets, reservedDates, scheduled, conversationUuid, settings, baseUnread, now, zone,
            )
            scheduledCount++
        }

        // V4：NeedsChat 不再 remind+urgent+broken 三连（催活式连发已退役），各状态各一条。
        when (status) {
            is StreakStatus.Active ->
                trySchedule("morning", daysFromNow = 1, prefersTodayForFirstSchedule = false, firstSchedule = isFirstSchedule)
            is StreakStatus.NeedsChat ->
                trySchedule("streak_remind", daysFromNow = 0, prefersTodayForFirstSchedule = true, firstSchedule = isFirstSchedule)
            StreakStatus.Broken ->
                trySchedule("streak_remind", daysFromNow = 0, prefersTodayForFirstSchedule = true, firstSchedule = isFirstSchedule)
        }

        // 晚上问候（21 点前）
        val currentHour = Instant.ofEpochMilli(now).atZone(zone).hour
        if (currentHour < 21 && scheduledCount < MAX_DAILY_NOTIFICATIONS) {
            trySchedule("evening", daysFromNow = 0, prefersTodayForFirstSchedule = false, firstSchedule = false)
        }

        // 随机通知（每天每角色判断一次，30%）
        if (scheduledCount < MAX_DAILY_NOTIFICATIONS) {
            scheduleRandomIfNeeded(
                charId, character.name, isFirstSchedule,
                activityBuckets, reservedDates, scheduled, conversationUuid, settings, baseUnread, now, zone,
            )
        }

        // 「兜底保证今天至少一条」已删除（V1 宁缺毋滥）：没有合适时机就今天不说话，不硬凑。

        store.setScheduled(charId, scheduled)
        store.saveSnapshot(charId, NotificationSchedulerStore.Snapshot(todayString, streakLabel, eventCount))
        Log.i(TAG, "回退通知已排：${character.name}（${scheduled.size} 条）")
    }

    // MARK: - 注册单条（模板路径，对齐 iOS scheduleNotification）

    @Suppress("LongParameterList")
    private suspend fun scheduleNotification(
        characterId: String,
        characterName: String,
        category: String,
        daysFromNow: Int,
        prefersTodayForFirstSchedule: Boolean,
        isFirstSchedule: Boolean,
        activityBucketMinutes: List<Int>,
        reservedDates: MutableList<Long>,
        scheduled: MutableList<NotificationSchedulerStore.ScheduledRef>,
        conversationUuid: String?,
        settings: AppSettings,
        baseUnread: Int,
        now: Long,
        zone: ZoneId,
    ) {
        // P6.1e：取该分类窗口学习数据喂给 planner（learnedScore + 重复惩罚 + 非冷启动取窗心）。
        val statsByWindowId = loadWindowStats(characterId, category)
        val isColdStart = statsByWindowId.values.sumOf { it.scheduledCount } == 0
        val targetDay = NotificationTimePlanner.resolvedTargetDate(
            now, daysFromNow, category, characterId, prefersTodayForFirstSchedule, isFirstSchedule,
            activityBucketMinutes, reservedDates, zone, statsByWindowId, isColdStart,
        )
        val selection = NotificationTimePlanner.chooseSchedule(
            characterId, category, targetDay, activityBucketMinutes, reservedDates, zone, statsByWindowId, isColdStart,
        ) ?: return
        val scheduledAt = selection.scheduledAt
        if (scheduledAt <= now) return
        if (isInQuietHours(scheduledAt, settings, zone)) {
            Log.d(TAG, "夜间免打扰时段，跳过：$characterName [$category]")
            return
        }
        if (shouldSkipWhileSleeping(characterId, scheduledAt, settings.scheduleSystemEnabled, zone)) {
            Log.d(TAG, "角色睡眠时段，跳过：$characterName [$category]")
            return
        }
        val badgeCount = baseUnread + scheduled.size + 1
        val deliveryId = deliver(
            characterId, characterName, category, scheduledAt, conversationUuid, badgeCount,
            ProactiveOccasionText.occasionForCategory(category), scheduled, reservedDates,
        )
        // P6.1e：调度时记 scheduled 台账 + 更新窗口 lastScheduledAt（发出后由 bridge 回填 deliveredAt 并物化）。
        // 台账 body 存空串——正文到点才现做，排程时不存在（V1）。
        learningService.recordScheduled(
            characterId, category, deliveryId, requestKeyFor(characterId, category), conversationUuid.orEmpty(), "",
            selection.windowId, selection.startMinute, selection.endMinute, scheduledAt,
        )
    }

    /** 取某角色某分类各窗口的学习数据 → planner 的 [NotificationTimePlanner.WindowStat] 映射。 */
    private suspend fun loadWindowStats(
        characterId: String,
        category: String,
    ): Map<String, NotificationTimePlanner.WindowStat> =
        learningService.windowStatsFor(characterId, category).associate {
            it.windowId to NotificationTimePlanner.WindowStat(it.smoothedScore, it.lastScheduledAt, it.scheduledCount)
        }

    // MARK: - 注册单条（日程驱动：时刻 = 事件结束 + 确定性抖动）

    /**
     * 把第 [index] 个日程事件排成一条主动消息：`fireAt = event.endTime + 抖动(0..15 整分钟)`。
     * 抖动用 djb2 确定性散列（同角色同事件同日重排结果恒同 → `shouldRebuild` 节流与闹钟时刻不漂移；禁无种 Random）。
     */
    @Suppress("LongParameterList")
    private suspend fun scheduleAtEvent(
        characterId: String,
        characterName: String,
        index: Int,
        event: ScheduleEventEntity,
        reservedDates: MutableList<Long>,
        scheduled: MutableList<NotificationSchedulerStore.ScheduledRef>,
        conversationUuid: String?,
        settings: AppSettings,
        baseUnread: Int,
        now: Long,
        zone: ZoneId,
    ) {
        val category = "schedule_$index"
        val todayString = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val jitterMinutes = jitterMinutesFor(characterId, category, todayString)
        val scheduledAt = event.endTime + jitterMinutes * 60_000L
        if (scheduledAt <= now) return
        if (isInQuietHours(scheduledAt, settings, zone)) {
            Log.d(TAG, "夜间免打扰时段，跳过日程通知：$characterName [$category]")
            return
        }
        if (shouldSkipWhileSleeping(characterId, scheduledAt, settings.scheduleSystemEnabled, zone)) {
            Log.d(TAG, "角色睡眠时段，跳过日程通知：$characterName [$category]")
            return
        }
        val badgeCount = baseUnread + scheduled.size + 1
        val deliveryId = deliver(
            characterId, characterName, category, scheduledAt, conversationUuid, badgeCount,
            ProactiveOccasionText.occasionForEvent(event, zone), scheduled, reservedDates,
        )
        // 日程通知归类 streak_remind 复用学习数据，窗口据触发时刻派生。台账 body 空串（正文到点现做）。
        val zdt = Instant.ofEpochMilli(scheduledAt).atZone(zone)
        val windowStart = zdt.hour * 60 + (zdt.minute / 30) * 30
        learningService.recordScheduled(
            characterId, "streak_remind", deliveryId, requestKeyFor(characterId, category), conversationUuid.orEmpty(), "",
            "$windowStart-${windowStart + 30}", windowStart, windowStart + 30, scheduledAt,
        )
    }

    // MARK: - 随机通知（对齐 iOS scheduleRandomIfNeeded）

    @Suppress("LongParameterList")
    private suspend fun scheduleRandomIfNeeded(
        characterId: String,
        characterName: String,
        isFirstSchedule: Boolean,
        activityBucketMinutes: List<Int>,
        reservedDates: MutableList<Long>,
        scheduled: MutableList<NotificationSchedulerStore.ScheduledRef>,
        conversationUuid: String?,
        settings: AppSettings,
        baseUnread: Int,
        now: Long,
        zone: ZoneId,
    ) {
        val todayKey = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        if (store.randomDecidedDate(characterId) == todayKey) return
        store.setRandomDecidedDate(characterId, todayKey)
        if (Random.nextInt(1, 11) > 3) return // 30% 概率（1..10 <= 3）
        scheduleNotification(
            characterId, characterName, "random",
            daysFromNow = 0, prefersTodayForFirstSchedule = true, isFirstSchedule = isFirstSchedule,
            activityBucketMinutes, reservedDates, scheduled, conversationUuid, settings, baseUnread, now, zone,
        )
    }

    // MARK: - 投递（烤进精确闹钟：只有时刻，正文到点现做）+ 记账

    @Suppress("LongParameterList")
    private suspend fun deliver(
        characterId: String,
        characterName: String,
        category: String,
        fireAtMillis: Long,
        conversationUuid: String?,
        badgeCount: Int,
        occasion: String,
        scheduled: MutableList<NotificationSchedulerStore.ScheduledRef>,
        reservedDates: MutableList<Long>,
    ): String {
        val requestKey = requestKeyFor(characterId, category)
        val notificationId = requestKey.hashCode()
        // 6.1d：每条调度生成唯一投递标识，随精确闹钟 extras 透传到发出时刻，用于「发出即投递」标记 → 回前台物化成
        // 会话消息 + 点击命中台账去重。返回供 6.1e recordScheduled 记台账。
        val deliveryIdentifier = UUID.randomUUID().toString()
        // 13.8·B3：烤进角色头像路径，发出时 Notifier.post 据此把通知升级为 MessagingStyle 头像气泡（调度时一次性查，
        // 不在发出时刻碰 DB）。
        val avatarPath = characterRepository.get(characterId)?.avatarPath
        // 精确闹钟底座（到点一定弹、App 被杀也补发）。body 恒空串——排程时不存在正文；freshResolution 恒 true，
        // 由 [NotificationAlarmReceiver] 起加急 worker 到点现做（失败走模板 → 保底文案兜底链，全空则不发）。
        val payload = NotificationPayload(
            notificationId = notificationId,
            title = characterName,
            body = "",
            conversationUuid = conversationUuid,
            characterId = characterId,
            deliveryIdentifier = deliveryIdentifier,
            category = category,
            requestKey = requestKey,
            scheduledAtMillis = fireAtMillis,
            badgeCount = badgeCount,
            freshResolution = true,
            avatarPath = avatarPath,
            occasion = occasion,
        )
        alarmScheduler.scheduleExact(requestKey, fireAtMillis, payload)
        scheduled.add(NotificationSchedulerStore.ScheduledRef(requestKey, fireAtMillis, category))
        reservedDates.add(fireAtMillis)
        return deliveryIdentifier
    }

    // MARK: - 免打扰 / 睡眠 / 事件 / 活跃时段辅助

    /** 排程期夜间免打扰预过滤（到点侧 Pipeline 另有同闸复查——ROM 延迟投递可能把到点拖进窗内）。 */
    private fun isInQuietHours(fireAtMillis: Long, settings: AppSettings, zone: ZoneId): Boolean {
        if (!settings.quietHoursEnabled) return false
        val zdt = Instant.ofEpochMilli(fireAtMillis).atZone(zone)
        return NotificationScheduleRules.isInQuietHours(
            zdt.hour * 60 + zdt.minute,
            settings.quietHoursStartMinute,
            settings.quietHoursEndMinute,
        )
    }

    /** [at] 所在自然日的全部日程事件（无日程 → 空）。日程支 / 睡眠闸 / 重建判定共用。 */
    private suspend fun dayEventsFor(characterId: String, at: Long, zone: ZoneId): List<ScheduleEventEntity> {
        val dayStart = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()
        val schedule = scheduleDao.scheduleFor(characterId, dayStart) ?: return emptyList()
        return scheduleDao.eventsForSchedule(schedule.uuid)
    }

    /** 今日可通知事件（日程支排程用；[todayEventCount] 取其条数，口径天然同源）。 */
    private suspend fun selectTodayNotificationEvents(
        characterId: String,
        now: Long,
        zone: ZoneId,
    ): List<ScheduleEventEntity> =
        NotificationScheduleRules.selectNotificationEvents(dayEventsFor(characterId, now, zone), now)

    private suspend fun shouldSkipWhileSleeping(
        characterId: String,
        scheduledAt: Long,
        scheduleSystemEnabled: Boolean,
        zone: ZoneId,
    ): Boolean {
        if (!scheduleSystemEnabled) return false
        return NotificationScheduleRules.shouldSkipWhileSleeping(
            true, dayEventsFor(characterId, scheduledAt, zone), scheduledAt, zone,
        )
    }

    /** 今日可通知事件数（重建判定用）。 */
    private suspend fun todayEventCount(characterId: String, now: Long, zone: ZoneId): Int =
        selectTodayNotificationEvents(characterId, now, zone).size

    companion object {
        private const val TAG = "NotifScheduler"
        const val NOTIFICATION_PREFIX = "aichat_streak_"

        /** 每角色每日主动消息上限（V4：5→3。精确闹钟配额 + 小米后台限制 + 「宁缺毋滥」）。 */
        const val MAX_DAILY_NOTIFICATIONS = 3

        /** 日程支触发抖动上限（分钟，开区间）：事件结束后 0..15 整分钟内随机发，不卡秒。 */
        private const val JITTER_MINUTES_BOUND = 16L

        /**
         * 日程支抖动分钟数（0..15）：djb2 确定性散列，同角色同事件同日恒同——重排不漂移（禁无种 Random）。
         * 纯函数（internal 供单测）。
         */
        internal fun jitterMinutesFor(characterId: String, category: String, todayString: String): Long =
            (NotificationTimePlanner.stableHash("$characterId|$category|$todayString") and Long.MAX_VALUE) %
                JITTER_MINUTES_BOUND

        // occasion（由头）两函数已只搬不改迁出 → [ProactiveOccasionText]（拆分账本预授权拆缝·锁定文案随迁）。

        /** 稳定唯一 key（对齐 iOS identifier `aichat_streak_<charId>_<cat>`）。P1-25 移入 companion 供前向枚举。 */
        internal fun requestKeyFor(characterId: String, category: String): String =
            "$NOTIFICATION_PREFIX${characterId}_$category"

        /**
         * 历史排程上限（schedule_0..4），**撤销覆盖面用，勿随 [MAX_DAILY_NOTIFICATIONS] 缩小**。
         * 老版本按 5 条上限排过 schedule_3 / schedule_4，其已弹通知在删角色时仍须撤得掉。
         */
        private const val LEGACY_MAX_SCHEDULE_CATEGORIES = 5

        /** P1-25：scheduleInternal 用到的类别闭集（schedule_0..4 + 回退分支 6 类）。新增通知类别必须同步此表，
         *  否则该类别已弹通知删角色撤不掉（单测以 11 类别全枚举锁住）。
         *  streak_urgent 已退役（不再排新的），但**必须留在本闭集**——老版本已弹的那条要撤得掉。 */
        private val PURGE_CATEGORIES = List(LEGACY_MAX_SCHEDULE_CATEGORIES) { "schedule_$it" } +
            listOf("morning", "evening", "random", "streak_remind", "streak_urgent", "streak_broken")

        /**
         * P1-25：删角色需撤的已弹通知 id 全集（前向枚举候选 requestKey → hashCode；id 是 hashCode 无法反推 key，
         * 且全工程 notify 均 2 参无 tag、extras 无可靠 characterId——枚举候选再 cancel 不存在的 id=no-op 是唯一通路）。
         * 对应 iOS MomentCleanupService.removeSystemNotifications 的覆盖面（iOS 按 userInfo 开集匹配，安卓闭集枚举）：
         * ① streak 前缀×类别闭集 ② 台账 characterId 列（覆盖日历 aichat_calendar_<eventId>，key 不含 charId）
         * ③ 会话 uuid×段序枚举（忙碌回复 busyReply_<convUuid>_<index>）；外加 MERGED 兜底 id（characterId.hashCode）。
         * 红包 22h 预警（red_packet_expiring_<recordUuid>，iOS 经 conversationUUID 路覆盖）走
         * RedPacketExpirationScanService.purgeForConversations 专线（批7 复核修，key 归属红包模块）。
         * 朋友圈新帖/互动走 MomentNotificationPurger 专线、里程碑走 MilestoneCelebrationNotifier 单槽
         * （均 P1-44，id 归属各模块）。**有意不覆盖**：经济（聚合通知跨角色，深链=开 App 安全）；
         * 故事（iOS userInfo 仅 type/storyID 无 charId/convUuid=三路结构性够不着，等深排除）。
         * 纯函数（internal 供单测）。
         */
        /** 老版本忙碌回复通知 id 枚举上限（=原 BusyReplyService.PURGE_KEY_ENUMERATION·功能已删除仅撤销用）。 */
        private const val LEGACY_BUSY_PURGE_KEY_ENUMERATION = 32

        internal fun purgeNotificationIds(
            characterId: String,
            conversationUuids: List<String>,
            ledgerRequestKeys: Collection<String>,
        ): Set<Int> = buildSet {
            PURGE_CATEGORIES.forEach { add(requestKeyFor(characterId, it).hashCode()) }
            conversationUuids.forEach { conv ->
                // 忙碌回复功能已删除（2026-07-11）：id 枚举内联保留（拼法=原 BusyReplyService
                // busyReply_<convUuid>_<index>·上限=原 PURGE_KEY_ENUMERATION），继续覆盖老版本已弹通知的删角色撤销。
                repeat(LEGACY_BUSY_PURGE_KEY_ENUMERATION) { add("busyReply_${conv}_$it".hashCode()) }
            }
            ledgerRequestKeys.forEach { add(it.hashCode()) }
            add(characterId.hashCode()) // ProactiveNotificationWorker KEY_NOTIFICATION_ID 缺省兜底 id
        }

        // P12.6 D3：删除原「64 条待发上限 − 预留 10 = 54」死天花板（MAX_TOTAL_PENDING/RESERVED_FOR_OTHER_SOURCES/
        // EFFECTIVE_PENDING_LIMIT + 两处 reservedDates.size 守卫）。那是 iOS「每 App 最多 64 条待发本地通知」逼出的数字，
        // 安卓 AlarmManager 无此硬限；且其计数口径只数本角色火花通知、不含日历/红包/故事，与 iOS 系统级语义不符——
        // 基本跑不到的死分支，万一触发还会以错误口径误跳过。每角色每日上限（`MAX_DAILY_NOTIFICATIONS`，上方）保留：
        // 那在安卓有真实理由（精确闹钟配额 + 小米后台限制），是经判断保留的。

    }
}
