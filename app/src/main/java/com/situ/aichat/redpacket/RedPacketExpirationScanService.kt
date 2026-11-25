package com.situ.aichat.redpacket

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import android.util.Log
import com.situ.aichat.data.local.dao.RedPacketDao
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import com.situ.aichat.data.model.WalletOwnerType
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.NotificationAlarmScheduler
import com.situ.aichat.notification.NotificationPayload
import com.situ.aichat.notification.NotificationPermission
import com.situ.aichat.notification.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 红包过期扫描 + 22h 预警（1:1 iOS `Services/RedPacketExpirationScanService.swift`，阶段 5.5 · Sub D.2）。
 *
 * 扫 pending 红包做两件事：
 * 1. **过期处理**：`now > expiresAt` → [RedPacketService.expireRedPacket]（退钱回发送方，status=expired）。
 * 2. **22h 预警**：`now >= expiresAt - 2h` 且 `!notifiedExpiringSoon` 且 **receiverType==user** → 推本地通知（**不含金额**）+
 *    置 notifiedExpiringSoon 防重复。仅对「角色发给用户」的红包预警（用户发的由决策服务立即处理，正常不会 pending 超 24h；
 *    极端 app 被杀 Task 中断 → 走 expired 兜底，无需预警）。
 *
 * **触发（国产 ROM 韧性三路）**：① 回前台（AppViewModel.onAppForeground，1:1 iOS scenePhase active）② WorkManager 周期兜底
 * （[com.situ.aichat.work.RedPacketExpirationWorker]）③ **精确闹钟**——角色发红包时（[scheduleWarningAlarm]，executor 调用）
 * 烤一个 22h 闹钟，到点由 [NotificationAlarmReceiver] 发预警（app 被杀也能弹）；用户拆开 / 过期时取消（[cancelWarningAlarm]）。
 * 预警闹钟与前台扫描用同通知 id + onlyAlertOnce，最多出声一次。
 *
 * iOS 是 `@MainActor enum`；安卓 `@Singleton`。纯判定（[isExpired]/[shouldWarn]/[buildExpiringContent]）在 companion 单测；
 * 过期触发 [RedPacketService.expireRedPacket]（退钱）走真机 + 钱路径复核。
 */
@Singleton
class RedPacketExpirationScanService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val redPacketDao: RedPacketDao,
    private val redPacketService: RedPacketService,
    private val characterRepo: CharacterRepository,
    private val alarmScheduler: NotificationAlarmScheduler,
    private val settingsRepo: SettingsRepository,
) {

    /** 主入口：一次扫完过期 + 预警（1:1 iOS `scan`）。 */
    suspend fun scan(now: Long = System.currentTimeMillis()) {
        val pending = redPacketDao.pendingRecords()
        if (pending.isEmpty()) return
        processExpired(pending, now) // 先过期（避免对已过期的还推预警）
        processExpiringSoon(now) // 后预警（重新 fetch，过期已移走一批）
    }

    /** 过期处理：`now > expiresAt` 的逐条 expireRedPacket（抛错记日志继续），并取消其预警闹钟（1:1 iOS `processExpired`）。 */
    private suspend fun processExpired(pending: List<RedPacketRecordEntity>, now: Long) {
        val expired = pending.filter { isExpired(it, now) }
        for (record in expired) {
            try {
                redPacketService.expireRedPacket(record.uuid, now)
                cancelWarningAlarm(record.uuid) // 闹钟通常已在 22h 触发，取消为防御性（非 character→user 的为 no-op）
                Log.i(TAG, "红包 ${record.uuid} 已自动过期 (sender=${record.senderType})")
            } catch (e: Exception) {
                Log.e(TAG, "红包 ${record.uuid} 过期处理失败: ${e.message}")
            }
        }
    }

    /**
     * 22h 预警：未授权通知 → 静默跳过；重新 fetch pending → 过滤 [shouldWarn] → 逐条推预警（**不含金额**）+ 置 notifiedExpiringSoon。
     */
    private suspend fun processExpiringSoon(now: Long) {
        if (!settingsRepo.appSettings.first().notifRedPacketExpiringEnabled) {
            Log.d(TAG, "红包快过期提醒已关闭,跳过 22h 预警扫描")
            return
        }
        if (!NotificationPermission.isGranted(context)) {
            Log.d(TAG, "未获通知授权,跳过 22h 预警扫描")
            return
        }
        val candidates = redPacketDao.pendingRecords().filter { shouldWarn(it, now) }
        for (record in candidates) {
            // 仅「角色发给用户」的有发送方角色；缺失则跳过（防御，shouldWarn 已限 receiver==user）
            if (WalletOwnerType.fromRaw(record.senderType) != WalletOwnerType.CHARACTER) continue
            val sender = characterRepo.get(record.senderCharacterUUID)
            if (sender == null) {
                Log.w(TAG, "红包 ${record.uuid} 找不到发送方角色,跳过预警")
                continue
            }
            Notifier.postRedPacketExpiring(context, buildExpiringPayload(record, sender.name))
            // 仅置 notifiedExpiringSoon 的**条件单列写**（非整行 update）：candidates 是 line-74 快照，置标记前的
            // characterRepo.get + 通知推送拉开了窗口；这 2h 窗内用户可能并发拆开（status→accepted）。整行 @Update 从陈旧
            // record 回写会把 status 打回 pending → 红包重复结算凭空造币。markExpiringSoonNotified 仅当仍 pending 才写、且只动单列。
            val marked = redPacketDao.markExpiringSoonNotified(record.uuid)
            if (marked == 0) {
                Log.w(TAG, "红包 ${record.uuid} 预警标记 CAS 落空(2h 窗内已被拆/退·通知可能已发)")
            }
            Log.i(TAG, "红包 ${record.uuid} 22h 预警已推送 (${sender.name})")
        }
    }

    // ── 精确闹钟（预警，Android 韧性层） ──

    /**
     * 角色发红包时排一个 22h 预警精确闹钟（executor 调用，post-commit）。直读 DataStore 开关，避免冷启动
     * 未扫描时的陈旧内存缓存；仅门控 22h 提醒，不触碰任何过期/退钱路径。
     */
    suspend fun scheduleWarningAlarm(record: RedPacketRecordEntity, characterName: String) {
        if (!settingsRepo.appSettings.first().notifRedPacketExpiringEnabled) {
            Log.d(TAG, "红包快过期提醒已关闭,跳过排 22h 预警闹钟 ${record.uuid}")
            return
        }
        val warningAt = record.expiresAt - WARNING_LEAD_MS
        alarmScheduler.scheduleExact(warningRequestKey(record.uuid), warningAt, buildExpiringPayload(record, characterName))
        Log.d(TAG, "红包 ${record.uuid} 22h 预警闹钟已排 at=$warningAt")
    }

    /** B-4：开关切换时即时对账 22h 预警闹钟——关则撤销全部待触发预警，开则重排未过期且未预警的待拆红包。 */
    suspend fun onExpiringNotificationSettingChanged(
        enabled: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val pending = redPacketDao.pendingRecords()
        if (!enabled) {
            pending.forEach { cancelWarningAlarm(it.uuid) }
            return
        }
        for (record in pending) {
            if (isExpired(record, now) || record.notifiedExpiringSoon) continue
            if (WalletOwnerType.fromRaw(record.receiverType) != WalletOwnerType.USER) continue
            if (WalletOwnerType.fromRaw(record.senderType) != WalletOwnerType.CHARACTER) continue
            val sender = characterRepo.get(record.senderCharacterUUID) ?: continue
            val warningAt = record.expiresAt - WARNING_LEAD_MS
            alarmScheduler.scheduleExact(warningRequestKey(record.uuid), warningAt, buildExpiringPayload(record, sender.name))
        }
    }

    /** 取消预警闹钟（用户拆开 / 过期时调；无对应闹钟为 no-op）。 */
    fun cancelWarningAlarm(recordUuid: String) {
        alarmScheduler.cancel(warningRequestKey(recordUuid))
    }

    /**
     * P1-25（批7 复核修）：删角色专用——撤这些会话全部红包的 22h 预警**闹钟 + 已弹通知**（=iOS
     * removeSystemNotifications 经 userInfo.conversationUUID 对红包的 pending+delivered 双撤；安卓红包
     * deliveryIdentifier=null 不落台账、key=recordUuid 不含 characterId，三路枚举均够不着，须本模块专线）。
     * 由 [com.situ.aichat.data.repository.CharacterDeletionCleaner] 在删会话级数据前调（记录无 FK 不级联，
     * 但须趁会话列表仍在时枚举）。普通过期/拆开路径走既有 [cancelWarningAlarm]，勿混用。
     */
    suspend fun purgeForConversations(conversationUuids: List<String>) {
        if (conversationUuids.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        redPacketDao.uuidsForConversations(conversationUuids).forEach { uuid ->
            cancelWarningAlarm(uuid)
            nm.cancel(warningRequestKey(uuid).hashCode())
        }
    }

    private fun buildExpiringPayload(record: RedPacketRecordEntity, characterName: String): NotificationPayload {
        val (title, body) = buildExpiringContent(characterName)
        val key = warningRequestKey(record.uuid)
        return NotificationPayload(
            notificationId = key.hashCode(),
            title = title,
            body = body,
            conversationUuid = record.conversationUuid,
            characterId = record.senderCharacterUUID,
            deliveryIdentifier = null, // 不物化成聊天消息
            category = Notifier.CATEGORY_RED_PACKET_EXPIRING,
            requestKey = key,
            scheduledAtMillis = record.expiresAt - WARNING_LEAD_MS,
            badgeCount = 0,
        )
    }

    companion object {
        private const val TAG = "RedPacketExpScan"

        /** 预警提前量：过期前 2 小时（1:1 iOS `warningLeadSeconds = 2*3600`；createdAt+24h 过期 → createdAt+22h 预警）。 */
        const val WARNING_LEAD_MS = 2L * 60 * 60 * 1000

        /** 预警通知 / 闹钟 requestKey 前缀（1:1 iOS `notificationIdentifierPrefix`）。 */
        const val WARNING_KEY_PREFIX = "red_packet_expiring_"

        fun warningRequestKey(recordUuid: String): String = WARNING_KEY_PREFIX + recordUuid

        /** 是否已过期（1:1 iOS `now > expiresAt`，纯函数可测）。 */
        fun isExpired(record: RedPacketRecordEntity, now: Long): Boolean = now > record.expiresAt

        /**
         * 是否应推 22h 预警（1:1 iOS `processExpiringSoon` 候选条件，纯函数可测）：
         * receiverType==user && !notifiedExpiringSoon && now >= expiresAt - 2h。
         */
        fun shouldWarn(record: RedPacketRecordEntity, now: Long): Boolean =
            WalletOwnerType.fromRaw(record.receiverType) == WalletOwnerType.USER &&
                !record.notifiedExpiringSoon &&
                now >= record.expiresAt - WARNING_LEAD_MS

        /**
         * 预警通知文案（**不含金额**，T4 神秘感，1:1 iOS `buildExpiringNotificationContent`）：
         * title=`🧧 {角色名} 给你发的红包快过期啦`，body=`还有 2 小时不拆就要退回咯`。
         */
        fun buildExpiringContent(characterName: String): Pair<String, String> =
            "🧧 $characterName 给你发的红包快过期啦" to "还有 2 小时不拆就要退回咯"
    }
}
