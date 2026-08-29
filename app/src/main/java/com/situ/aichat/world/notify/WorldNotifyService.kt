package com.situ.aichat.world.notify

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.NotificationAlarmScheduler
import com.situ.aichat.notification.NotificationPayload
import com.situ.aichat.notification.NotificationPostLedger
import com.situ.aichat.notification.NotifierWorld
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.bulletin.WorldLlmBudget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界到达通知服务（W8 图纸 §3·契约 §15/决策 33）：**预烤**——邀请/出发成功那一刻把到达时刻烤成精确闹钟
 * （[onVisitInvited]/[onUserDeparted]）；到点由闹钟蹦床进 [WorldNotifyWorker] 调 [fire] **验真再发**（八道门全过才发）。
 * 配套：每日封顶 ≤2 超出合并摘要、与全 app 通知统一排队绝不连震、世界自己安静（只降频不静音·回世界即恢复）。
 *
 * ⚠️ **钱路零碰**：全包不 import 任何 economy/wallet/redpacket/gift 类。**验真只读**：fire 对 travel/character/world_state
 * 只读，唯一写 = [WorldDao.markEventNotified]（NULL 守卫幂等）+ 台账 [WorldLlmBudget.tryConsume]。派生 uuid 用
 * `row.departAt`（禁 [System.currentTimeMillis]·§9）；fire 的 now 由 worker 注入。
 */
@Singleton
class WorldNotifyService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val worldDao: WorldDao,
    private val characterDao: CharacterDao,
    private val alarmScheduler: NotificationAlarmScheduler,
    private val budget: WorldLlmBudget,
    private val settingsRepository: SettingsRepository,
    private val stateStore: WorldNotifyStateStore,
    private val conversationDao: ConversationDao,
) {

    /**
     * 前台判定注入缝（§3.3 门 6·测试可替换）：默认主线程读 [ProcessLifecycleOwner]（同 [com.situ.aichat.relationship.MilestoneCelebrationNotifier]）。
     * 无现成全局 isForeground 状态，故须在 Main 线程读生命周期。
     */
    internal var foregroundCheck: suspend () -> Boolean = {
        withContext(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
    }

    /** 邀请成功后排「TA 到达你的城」闹钟（§3.1·[WorldTravelService] 事务外挂钩·文案兜底不被 worker 路径消费）。 */
    suspend fun onVisitInvited(characterUuid: String, arriveAtMs: Long) {
        val state = worldDao.getState() ?: return
        val character = characterDao.getByUuid(characterUuid) ?: return
        val row = worldDao.getTravel(characterUuid) ?: return
        val requestKey = NotifierWorld.REQUEST_KEY_VISIT_PREFIX + characterUuid
        val text = arrivalText(isUserLeg = false, characterName = character.name, cityName = cityNameOf(state, row.toCityId))
        alarmScheduler.scheduleExact(requestKey, arriveAtMs, worldPayload(requestKey, characterUuid, text, arriveAtMs))
    }

    /** 出发成功后排「你到达目的地」闹钟（§3.1·[WorldTravelService] 事务外挂钩）。 */
    suspend fun onUserDeparted(arriveAtMs: Long) {
        val state = worldDao.getState() ?: return
        val row = worldDao.getTravel(WorldIds.USER_ID) ?: return
        val text = arrivalText(isUserLeg = true, characterName = null, cityName = cityNameOf(state, row.toCityId))
        val key = NotifierWorld.REQUEST_KEY_USER_ARRIVAL
        alarmScheduler.scheduleExact(key, arriveAtMs, worldPayload(key, null, text, arriveAtMs))
    }

    /**
     * 重烤全部在途到达闹钟（W14 图纸 §3.2·开机重排 worker + 备份导入后共用）：精确闹钟不跨重启也不跨换机，
     * 而 `world_travel` 行在（Room / 备份都有）——扫行分发给既有两预烤方法（各自重读 state/角色/行·守卫齐全）。
     * 已到点的行不烤（懒结算路径接管；fire 的门 3 未到达 / 门 4 过期本就会拦·此为双保险）。档位/静默不在此判——
     * fire 时八道门读当下档位（重烤无条件·契约 §15 决策 33 语义不变）。**纯读 + 排闹钟**：绝不生成/修改 travel 行、绝不写库。
     */
    suspend fun rescheduleArrivals(nowMs: Long) {
        for (row in worldDao.getAllTravels()) {
            if (row.arriveAt <= nowMs) continue
            if (row.ownerId == WorldIds.USER_ID) onUserDeparted(row.arriveAt)
            else onVisitInvited(row.ownerId, row.arriveAt)
        }
    }

    /** 回 app 撤合并摘要（§3.5·[WorldLinkRunner] 前台通行证 step 0.5·回 app 即撤）。 */
    fun onAppForeground() {
        NotifierWorld.cancelSummary(context)
    }

    /**
     * 到点验真再发（§3.3 八道门·顺序锁死·全过才发·跳过一律静默返回 + 一行 Log.i）。[now] 由 [WorldNotifyWorker] 注入。
     */
    suspend fun fire(requestKey: String, now: Long) {
        val leg = parseRequestKey(requestKey) ?: return skip("未知 requestKey")
        val isUserLeg = leg.first
        val characterUuid = leg.second

        // 门 1 全局开关。
        val settings = settingsRepository.appSettings.first()
        if (!settings.notificationsEnabled) return skip("门1 全局通知关")
        // 门 2 档位（决策 33）。
        if (!WorldNotifyRules.tierAllows(settings.worldNotificationTier, isUserLeg)) return skip("门2 档位·tier=${settings.worldNotificationTier} userLeg=$isUserLeg")
        // 门 3 验真（只读·行为准绳）。
        val state = worldDao.getState() ?: return skip("门3 无 world_state")
        val owner = if (isUserLeg) WorldIds.USER_ID else characterUuid!!
        val row = worldDao.getTravel(owner) ?: return skip("门3 无在途行(已结算/角色已删)")
        var character: CharacterEntity? = null
        if (!isUserLeg) {
            character = characterDao.getByUuid(characterUuid!!) ?: return skip("门3 角色已删")
            if (!character.joinedWorld) return skip("门3 角色未入世")
            if (row.toCityId == character.worldHomeCityId) return skip("门3 来访腿已被返程腿替换")
        }
        if (now < row.arriveAt) return skip("门3 未到达(回拨/换行)")
        // 门 4 过期（迟到半天的「刚到」是谎言·小报会讲）。
        if (WorldNotifyRules.isStale(now, row.arriveAt)) return skip("门4 过期(>12h)")
        // 门 5 seenAt 守卫（仅来访腿·小报已讲过就闭嘴）。
        val eventUuid = if (isUserLeg) null else visitEventUuid(characterUuid!!, row.departAt)
        if (!isUserLeg && worldDao.getEvent(eventUuid!!)?.seenAt != null) return skip("门5 小报已讲过")
        // 门 6 前台（人在 app 里·世界卡红点归 W11）。
        if (foregroundCheck()) return skip("门6 前台")
        // 门 9 见面（卷一 C4·新增）：到达角色正在与用户线下见面 → 静默吞（人就在对面，「我到啦」当场穿帮）。
        // 位置在门 7/8 之前：那两道有副作用（顺延排闹钟 / 扣每日额度），先判见面才不会白扣一格。用户腿无角色 → 不判。
        if (!isUserLeg && OfflineMeetingGate.characterInMeeting(conversationDao, characterUuid!!)) {
            return skip("门9 见面进行中")
        }

        val text = arrivalText(isUserLeg, character?.name, cityNameOf(state, row.toCityId))
        // 门 7 统一排队（世界永远让路·不消费额度·顺延同 key 闹钟 now+120s·连环顺延由门 4 过期兜底）。
        if (WorldNotifyRules.shouldDefer(now, NotificationPostLedger.lastPostAt(context))) {
            alarmScheduler.scheduleExact(requestKey, now + WorldNotifyRules.PACER_GAP_MS, worldPayload(requestKey, if (isUserLeg) null else characterUuid, text, row.arriveAt))
            return skip("门7 顺延120s(让路·零额度消费)")
        }
        // 门 8 每日封顶（先验后扣·扣了必发）。
        val zone = WorldClock.resolveZone(state.userTimezoneId)
        val epochDay = WorldClock.localDateOf(now, zone).toEpochDay()
        val anchor = if (stateStore.lastWorldEnteredAt > 0) stateStore.lastWorldEnteredAt else state.createdAt
        val daysSinceEntered = epochDay - WorldClock.localDateOf(anchor, zone).toEpochDay()
        val cap = WorldNotifyRules.dailyCapFor(daysSinceEntered)
        if (budget.tryConsume(CATEGORY_NOTIF, epochDay, cap)) {
            NotifierWorld.postArrival(context, requestKey.hashCode(), text.first, text.second) // fire 现读名/城
            if (!isUserLeg) worldDao.markEventNotified(eventUuid!!, now) // 来访腿·NULL 守卫幂等
            Log.i(TAG, "fire 发个体(消费1格·cap=$cap·days=$daysSinceEntered)")
        } else {
            budget.tryConsume(CATEGORY_NOTIF_OVERFLOW, epochDay, WorldNotifyRules.OVERFLOW_COUNTER_CAP)
            NotifierWorld.postSummary(context, budget.spentCount(CATEGORY_NOTIF_OVERFLOW, epochDay))
            Log.i(TAG, "fire 封顶→摘要")
        }
    }

    /** requestKey → (isUserLeg, characterUuid?)；未知串 → null（孤儿闹钟自灭·§2）。 */
    private fun parseRequestKey(requestKey: String): Pair<Boolean, String?>? = when {
        requestKey == NotifierWorld.REQUEST_KEY_USER_ARRIVAL -> true to null
        requestKey.startsWith(NotifierWorld.REQUEST_KEY_VISIT_PREFIX) ->
            requestKey.removePrefix(NotifierWorld.REQUEST_KEY_VISIT_PREFIX).takeIf { it.isNotBlank() }?.let { false to it }
        else -> null
    }

    /** 来访事件 uuid（派生串与 W7 landVisitEvent 同源·`world:visit:{charUuid}:{departAt}`·禁掺 now·§9）。 */
    private fun visitEventUuid(characterUuid: String, departAt: Long): String =
        UUID.nameUUIDFromBytes("world:visit:$characterUuid:$departAt".toByteArray()).toString()

    /** §4.3 文案（title, body）：城名由调用点从 DB 现读传入。来访腿 title = 角色名（fire 现读·绝不用烤旧名）。 */
    private fun arrivalText(isUserLeg: Boolean, characterName: String?, cityName: String): Pair<String, String> =
        if (isUserLeg) {
            context.getString(R.string.notif_world_user_arrival_title) to context.getString(R.string.notif_world_user_arrival_body, cityName)
        } else {
            (characterName ?: "") to context.getString(R.string.notif_world_visit_arrival_body, cityName)
        }

    /** 城名（§4.3·W7 §4.3 同源取法·兜底「远方」）。 */
    private fun cityNameOf(state: WorldStateEntity, cityId: String): String =
        WorldAtlas.of(state.seed).cityById(cityId)?.name ?: FALLBACK_CITY

    /** 世界到达 payload（不物化·deliveryIdentifier=null·notificationId = requestKey.hashCode()·§3.1）。 */
    private fun worldPayload(requestKey: String, characterId: String?, text: Pair<String, String>, arriveAtMs: Long) =
        NotificationPayload(
            notificationId = requestKey.hashCode(),
            title = text.first,
            body = text.second,
            characterId = characterId,
            category = NotifierWorld.CATEGORY_WORLD_ARRIVAL,
            requestKey = requestKey,
            scheduledAtMillis = arriveAtMs,
            deliveryIdentifier = null,
        )

    private fun skip(reason: String) {
        Log.i(TAG, "fire 跳过·$reason")
    }

    private companion object {
        const val TAG = "WorldNotify"

        /** 台账类目串（§9 锁死）：个体计数 / 溢出计数——复用 world_llm_spend 台账（零迁移）。 */
        const val CATEGORY_NOTIF = "notif"
        const val CATEGORY_NOTIF_OVERFLOW = "notifOverflow"

        /** 城名兜底（§4.3/§9 锁死）。 */
        const val FALLBACK_CITY = "远方"
    }
}
