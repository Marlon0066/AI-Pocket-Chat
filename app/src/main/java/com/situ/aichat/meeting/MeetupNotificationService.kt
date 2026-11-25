package com.situ.aichat.meeting

import android.content.Context
import android.util.Log
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.NotificationAlarmScheduler
import com.situ.aichat.notification.NotificationPayload
import com.situ.aichat.notification.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「未来约定见面」到点通知调度器（Phase 10）。把每条**已确认且见面时刻在未来**的约定烤进
 * [NotificationAlarmScheduler] 精确闹钟，到点由 [com.situ.aichat.notification.NotificationAlarmReceiver] 经
 * [Notifier.postMeetup] 发出——无需 App 存活、无需联网、扛 Doze（HyperOS 精确闹钟配额降级见 scheduler）。
 * 通知 / 闹钟 key = `meetup_<uuid>`（撤 / 重排同 key 幂等覆盖，对齐 §6 参数表「通知 id」）。
 *
 * 设计同 [com.situ.aichat.pet.PetReminderScheduler]：精确闹钟**不跨重启 / force-stop**，故由
 * [com.situ.aichat.work.NotificationRescheduleWorker]（开机）+ [com.situ.aichat.ui.AppViewModel.onAppForeground]
 * （前台启动）调 [rescheduleAll] 重烤；约定状态每次变动（确认 / 改期 / 取消 / 赴约 / 爽约）由 ChatViewModel 在
 * 协调器写完真理源后调 [rescheduleAll] 重算。
 *
 * [rescheduleAll] 是**无台账的全量对账**：遍历真理源全部约定，最近 [MAX_MEETUP_NOTIFICATIONS] 条 confirmed-future
 * 排程、其余一律撤——自动收敛任何过期 / 取消 / 赴约 / 超额的悬挂闹钟，无需单独记「排过哪些」。
 *
 * **删角色 / 删会话**例外：删行后约定从真理源消失，全量对账再也够不着 → 必须在删行**之前**由
 * [MeetingAppointmentStore.deleteForCharacter] / [MeetingAppointmentStore.deleteForConversation] 逐条调 [cancel]
 * 撤通知（§7 坑：先枚举 uuid 撤通知、再删记录，防孤儿通知）。
 */
@Singleton
class MeetupNotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: MeetingAppointmentDao,
    private val characterRepository: CharacterRepository,
    private val alarmScheduler: NotificationAlarmScheduler,
    private val settingsRepository: SettingsRepository,
) {

    /** 全量对账重排：最近 [MAX_MEETUP_NOTIFICATIONS] 条 confirmed-future 排到点闹钟，其余约定一律撤（幂等可反复调）。 */
    suspend fun rescheduleAll(now: Long = System.currentTimeMillis()) {
        val all = dao.getAllAppointments()
        if (!settingsRepository.appSettings.first().notifMeetingReminderEnabled) {
            Log.i(TAG, "约见面到点提醒已关闭·撤销全部 ${all.size} 条约定闹钟")
            for (appt in all) alarmScheduler.cancel(requestKey(appt.uuid))
            return
        }
        val toSchedule = upcomingToSchedule(all, now).map { it.uuid }.toSet()
        Log.i(TAG, "约定通知全量对账·排 ${toSchedule.size} 条/共 ${all.size} 条")
        for (appt in all) {
            if (appt.uuid in toSchedule) schedule(appt) else alarmScheduler.cancel(requestKey(appt.uuid))
        }
    }

    /** 撤某条约定的到点闹钟（删角色 / 删会话清理：删行前逐条调，防孤儿通知）。cancel 不存在的 key = no-op。 */
    fun cancel(uuid: String) = alarmScheduler.cancel(requestKey(uuid))

    /** 烤单条到点闹钟。角色已删（理论上清理会先撤·防御性再查一次）→ 撤而非排，免到点弹孤儿通知。 */
    private suspend fun schedule(appt: MeetingAppointmentEntity) {
        val key = requestKey(appt.uuid)
        val character = characterRepository.get(appt.characterUuid) ?: run {
            Log.w(TAG, "约定通知撤销·角色已删 uuid=${appt.uuid} char=${appt.characterUuid}")
            alarmScheduler.cancel(key); return
        }
        val payload = NotificationPayload(
            notificationId = key.hashCode(),
            title = character.name,
            body = meetupBody(context, appt.activity),
            conversationUuid = appt.conversationUuid,
            characterId = appt.characterUuid,
            category = Notifier.CATEGORY_MEETUP,
            requestKey = key, // postMeetup 据此剥 uuid 烤进赴约深链
            scheduledAtMillis = appt.scheduledAt,
            avatarPath = character.avatarPath, // postMeetup 升 MessagingStyle 头像气泡（角色喊你赴约）
        )
        alarmScheduler.scheduleExact(key, appt.scheduledAt, payload)
        Log.i(TAG, "约定通知已排 uuid=${appt.uuid} char=${appt.characterUuid} at=${appt.scheduledAt}")
    }

    companion object {
        private const val TAG = "MeetupNotifService"

        /** 同时挂起的到点通知软上限（§6 参数表「约定通知软上限 16 条」·防超额烧系统闹钟配额）。 */
        const val MAX_MEETUP_NOTIFICATIONS = 16

        /**
         * 通知 / 闹钟稳定 key（= `meetup_<uuid>`·§6 参数表「通知 id」·撤 / 重排同 key 幂等）。前缀单源自
         * [Notifier.CATEGORY_MEETUP]——与 [Notifier.meetupUuidFromRequestKey] 的剥前缀严丝合缝（改一处即断另一处的测试）。
         */
        internal fun requestKey(uuid: String): String = "${Notifier.CATEGORY_MEETUP}_$uuid"

        /**
         * 从全部约定里挑「该排到点通知」的：confirmed 且见面时刻**在未来**，按时刻升序取最近
         * [MAX_MEETUP_NOTIFICATIONS] 条（proposed 还没确认不排·过点交 Phase 11 爽约扫描·终态不排）。纯函数便于单测。
         */
        internal fun upcomingToSchedule(all: List<MeetingAppointmentEntity>, now: Long): List<MeetingAppointmentEntity> =
            all.filter { MeetingStatus.fromRaw(it.status) == MeetingStatus.CONFIRMED && it.scheduledAt > now }
                .sortedBy { it.scheduledAt }
                .take(MAX_MEETUP_NOTIFICATIONS)

        /** 到点通知正文（角色口吻轻提醒·「灵魂」留给进会话 / 沉浸后的 AI 开场）。有活动 → 带活动；否则通用。纯函数。 */
        internal fun meetupBody(context: Context, activity: String): String {
            val trimmed = activity.trim()
            return if (trimmed.isNotEmpty()) {
                context.getString(R.string.meetup_notif_body_activity, trimmed)
            } else {
                context.getString(R.string.meetup_notif_body)
            }
        }
    }
}
