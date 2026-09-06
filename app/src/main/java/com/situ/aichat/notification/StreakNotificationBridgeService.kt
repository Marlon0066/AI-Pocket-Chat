package com.situ.aichat.notification

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.situ.aichat.data.local.dao.NotificationDeliveryDao
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.NotificationDeliveryRecordEntity
import com.situ.aichat.data.local.entity.NotificationDeliveryState
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.offline.OfflineMeetingGate
import com.situ.aichat.prompt.AssistantOutputGate
import com.situ.aichat.prompt.MessageKindInference
import com.situ.aichat.util.DateFormatters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 点击通知的负载（自 deep-link Intent 解析，见 [Notifier.clickPayloadFrom]）。 */
data class NotificationClickPayload(
    val deliveryIdentifier: String?,
    val conversationUuid: String?,
    val characterId: String?,
    val notificationBody: String,
    val category: String,
    val requestKey: String?,
    val scheduledAt: Long,
)

/**
 * 通知落成聊天消息（P6.1d）。1:1 移植 iOS `StreakNotificationBridgeService`：把弹出 / 被点击的主动消息通知
 * 转成会话里真实的 assistant 消息，并让点击跳进对应会话。
 *
 * **平台映射**（非偏离）：iOS 在预登记时建台账、靠系统「已送达」回调；安卓无送达回调 → 通知**发出即投递**
 * 时落 [PendingDeliveryStore] 标记，本服务在「回前台」([materializeDeliveredNotifications]) 把标记排干、
 * 建 [NotificationDeliveryRecordEntity] 台账并物化；「点击」([materializeFromClick]) 则即时物化 + 导航。
 * 物化时机对齐 iOS「回前台扫描已投递 → 转消息」；去重靠 deliveryIdentifier + materializedAt（同 iOS）。
 *
 * 调用点（对齐 iOS AIChatApp 的 .task + scenePhase .active + UNUserNotificationCenterDelegate）：
 * - 回前台：[com.situ.aichat.ui.AppViewModel.onAppForeground]（ON_RESUME）。
 * - 点击：[com.situ.aichat.MainActivity] onCreate / onNewIntent。
 */
@Singleton
class StreakNotificationBridgeService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val characterRepository: CharacterRepository,
    private val deliveryDao: NotificationDeliveryDao,
    private val activeConversationStore: ActiveConversationStore,
    private val navigator: NotificationNavigator,
) {
    /** 串行化「排干标记 + 物化」，避免回前台扫描与点击处理并发交错。 */
    private val mutex = Mutex()

    /** 回前台：把已投递通知物化成会话里的 assistant 消息。对齐 iOS materializeDeliveredNotifications。 */
    suspend fun materializeDeliveredNotifications() = mutex.withLock {
        drainMarkersToRecords()
        materializeAllPending()
    }

    /**
     * 点击通知：物化（含自愈）+ 导航到对应会话。对齐 iOS materializeFromResponse。
     * 即使「待物化标记」因进程被杀而丢失，也能凭点击负载自愈建台账，保证点击始终能物化 + 跳转。
     */
    suspend fun materializeFromClick(payload: NotificationClickPayload) = mutex.withLock {
        drainMarkersToRecords()
        val deliveryId = payload.deliveryIdentifier
        if (deliveryId.isNullOrEmpty()) {
            // 无投递标识的旧通知：仅尝试导航（对齐 iOS 无 deliveryIdentifier 分支）。
            navigateTo(payload.conversationUuid, payload.characterId)
            return@withLock
        }
        if (deliveryDao.getByDeliveryIdentifier(deliveryId) == null) {
            deliveryDao.upsert(recordFromClick(payload, deliveryId))
        }
        materializeAllPending()
        val record = deliveryDao.getByDeliveryIdentifier(deliveryId)
        val targetUuid = record?.let { resolveConversation(it)?.uuid }
            ?: payload.conversationUuid?.takeIf { it.isNotEmpty() }?.let { conversationRepository.get(it)?.uuid }
            ?: resolvePreferredByCharacter(payload.characterId)?.uuid
        targetUuid?.let { navigator.request(it) }
    }

    // MARK: - 物化核心

    /** 把所有「已投递、未物化」的台账转成会话消息。对齐 iOS materialize 主循环（按 scheduledAt 升序）。 */
    private suspend fun materializeAllPending() {
        val pending = deliveryDao.pendingForMaterialization()
        for (record in pending) {
            val deliveredAt = record.deliveredAt ?: continue
            val conversation = resolveConversation(record)
            if (conversation == null) {
                Log.w(TAG, "通知物化跳过：角色已删除 (characterId=${record.characterId})")
                continue
            }
            // 见面闸（卷一 A6·J8）：会话正在线下见面 → 本轮**顺延**不物化（pending 不丢，下轮排干时再落）。
            // 物化会以「线上标 + deliveredAt 时间戳」插一条角色消息，见面期落进去 = 剧场外的幽灵消息。
            if (OfflineMeetingGate.inMeeting(conversation)) {
                Log.d(TAG, "通知物化顺延：会话见面中 (conv=${conversation.uuid})")
                continue
            }
            // 落库前置闸（图纸 2026-09-01 件①）：判脏的通知文案不建消息，但**仍记账**（materializedAt 写上）——
            // 否则这条 pending 每轮重取、永远复活重试。kind 与下方 MessageEntity 同口径。
            if (AssistantOutputGate.shouldDiscard(
                    record.notificationBody,
                    MessageKindInference.forAssistantText(record.notificationBody, isOfflineMode = false),
                    source = "notifMaterialize",
                )
            ) {
                deliveryDao.update(record.copy(conversationUuid = conversation.uuid, materializedAt = deliveredAt))
                continue
            }
            val message = MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversation.uuid,
                roleRaw = ROLE_ASSISTANT,
                content = record.notificationBody,
                messageKindRaw = MessageKindInference.forAssistantText(record.notificationBody, isOfflineMode = false).raw,
                timestamp = deliveredAt,
            )
            messageRepository.upsert(message)
            val markReadNow = activeConversationStore.activeConversationUuid == conversation.uuid
            conversationRepository.applyMaterialization(conversation.uuid, record.notificationBody, deliveredAt, markReadNow)
            deliveryDao.update(
                record.copy(
                    conversationUuid = conversation.uuid,
                    materializedAt = deliveredAt,
                    materializedMessageId = message.messageUUID,
                ),
            )
            // 已转成会话消息 → 从系统托盘撤回这条通知（对齐 iOS removeDeliveredNotifications）。
            NotificationManagerCompat.from(context).cancel(record.requestIdentifier.hashCode())
        }
    }

    /**
     * 把发出时落下的「待物化标记」排干，回填投递时间。
     * 6.1e 起台账在**调度时**就建好（`recordScheduled`，state=scheduled / deliveredAt=null）——这里仅按
     * deliveryIdentifier 找到它、置 deliveredAt（已投递）。找不到（调度台账被清库/异常）则凭标记自愈建一条。
     */
    private suspend fun drainMarkersToRecords() {
        val markers = PendingDeliveryStore.drainAll(context)
        if (markers.isEmpty()) return
        val zone = ZoneId.systemDefault()
        for (marker in markers) {
            val existing = deliveryDao.getByDeliveryIdentifier(marker.deliveryIdentifier)
            if (existing != null) {
                if (existing.deliveredAt == null) {
                    // notification-1：「最新优先(FRESH)」模式下通知正文是到点现写的(marker.notificationBody)，而调度时
                    // 台账存的是兜底文案；尚未物化时回灌新鲜正文，使物化进聊天的消息与用户看到的通知一致
                    // (RELIABLE 模式二者本就相同 → 无害幂等)。
                    val fresh = marker.notificationBody
                    val body = if (fresh.isNotBlank() && existing.materializedAt == null) fresh else existing.notificationBody
                    deliveryDao.update(existing.copy(deliveredAt = marker.deliveredAt, notificationBody = body))
                }
            } else {
                deliveryDao.upsert(recordFromMarker(marker, zone))
            }
        }
    }

    // MARK: - 选 / 建会话（对齐 iOS preferredConversation / ensureConversation / resolveConversation）

    /**
     * 解析台账对应的会话：先按 conversationUuid，找不到再按 characterId 选「首选会话」或新建预留会话。
     * 角色已删 → 返回 null（物化跳过，对齐 iOS）。
     */
    private suspend fun resolveConversation(record: NotificationDeliveryRecordEntity): ConversationEntity? {
        if (record.conversationUuid.isNotEmpty()) {
            conversationRepository.get(record.conversationUuid)?.let { return it }
        }
        if (record.characterId.isEmpty()) return null
        val character = characterRepository.get(record.characterId) ?: return null
        return preferredConversation(record.characterId) ?: createReserved(character.uuid, character.name)
    }

    private suspend fun resolvePreferredByCharacter(characterId: String?): ConversationEntity? {
        if (characterId.isNullOrEmpty()) return null
        val character = characterRepository.get(characterId) ?: return null
        return preferredConversation(characterId) ?: createReserved(character.uuid, character.name)
    }

    private suspend fun preferredConversation(characterUuid: String): ConversationEntity? =
        selectPreferredConversation(conversationRepository.getByCharacter(characterUuid))

    private suspend fun createReserved(characterUuid: String, characterName: String): ConversationEntity {
        val title = "$characterName ${DateFormatters.monthDayHourMinute(System.currentTimeMillis())}"
        return conversationRepository.createReserved(characterUuid, title)
    }

    /** 仅导航（点击无投递标识时）：按会话 uuid，否则按角色解析。 */
    private suspend fun navigateTo(conversationUuid: String?, characterId: String?) {
        val target = conversationUuid?.takeIf { it.isNotEmpty() }?.let { conversationRepository.get(it)?.uuid }
            ?: resolvePreferredByCharacter(characterId)?.uuid
        target?.let { navigator.request(it) }
    }

    // MARK: - 标记 / 点击负载 → 台账

    private fun recordFromMarker(
        marker: PendingDeliveryStore.PendingDelivery,
        zone: ZoneId,
    ): NotificationDeliveryRecordEntity {
        val scheduled = if (marker.scheduledAt > 0) marker.scheduledAt else marker.deliveredAt
        val minute = minuteOfDay(scheduled, zone)
        return NotificationDeliveryRecordEntity(
            characterId = marker.characterId,
            category = marker.category,
            deliveryIdentifier = marker.deliveryIdentifier,
            requestIdentifier = marker.requestIdentifier,
            conversationUuid = marker.conversationUuid,
            notificationBody = marker.notificationBody,
            windowId = "window_$minute",
            windowStartMinute = minute,
            windowEndMinute = minute,
            scheduledAt = scheduled,
            deliveredAt = marker.deliveredAt,
            stateRaw = NotificationDeliveryState.SCHEDULED.raw,
        )
    }

    private fun recordFromClick(payload: NotificationClickPayload, deliveryId: String): NotificationDeliveryRecordEntity {
        val now = System.currentTimeMillis()
        val scheduled = if (payload.scheduledAt > 0) payload.scheduledAt else now
        val minute = minuteOfDay(scheduled, ZoneId.systemDefault())
        return NotificationDeliveryRecordEntity(
            characterId = payload.characterId.orEmpty(),
            category = payload.category,
            deliveryIdentifier = deliveryId,
            requestIdentifier = payload.requestKey ?: deliveryId,
            conversationUuid = payload.conversationUuid.orEmpty(),
            notificationBody = payload.notificationBody,
            windowId = "window_$minute",
            windowStartMinute = minute,
            windowEndMinute = minute,
            scheduledAt = scheduled,
            deliveredAt = now,
            stateRaw = NotificationDeliveryState.SCHEDULED.raw,
        )
    }

    private fun minuteOfDay(millis: Long, zone: ZoneId): Int {
        val zdt = Instant.ofEpochMilli(millis).atZone(zone)
        return zdt.hour * 60 + zdt.minute
    }

    companion object {
        private const val TAG = "StreakNotifBridge"
        private const val ROLE_ASSISTANT = "assistant"

        /**
         * 「首选会话」选择（对齐 iOS preferredConversation）。纯函数，可单测：
         * 1) 有 lastMessageDate 的会话 → 取 lastMessageDate 最新；
         * 2) 否则取预留会话(isReservedForNotifications) → creationDate 最新；
         * 3) 都没有 → null。
         *
         * 归档判据已于 2026-09-06 随聊天归档功能整体删除（图纸 `docs/handoff/2026-09-06-删除聊天归档功能.md`）。
         */
        internal fun selectPreferredConversation(conversations: List<ConversationEntity>): ConversationEntity? {
            val active = conversations
                .filter { it.lastMessageDate != null }
                .maxWithOrNull(compareBy { it.lastMessageDate ?: it.creationDate })
            if (active != null) return active
            return conversations
                .filter { it.isReservedForNotifications }
                .maxWithOrNull(compareBy { it.creationDate })
        }
    }
}
