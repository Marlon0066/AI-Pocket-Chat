package com.situ.aichat.notification

import com.situ.aichat.data.local.entity.ConversationEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * StreakNotificationBridgeService 纯逻辑单测（P6.1d）。
 *
 * 核心保护对象 = `selectPreferredConversation`「选会话」三分支，断言从 iOS
 * StreakNotificationBridgeServiceTests.swift 的对应用例反推（同一组业务语义）：
 * 1) 有 lastMessageDate 的活跃会话 → 取 lastMessageDate 最新；
 * 2) 否则取 reserved 会话 → creationDate 最新；
 * 3) 都没有 → null。
 *
 * 原「归档一律不参与」的两例已随聊天归档功能删除（2026-09-06·图纸
 * `docs/handoff/2026-09-06-删除聊天归档功能.md` §4.5）。
 *
 * 另测「待物化标记」JSON 往返（[PendingDeliveryStore] 发出即落、回前台排干所依赖的序列化契约）。
 */
class StreakNotificationBridgeServiceTest {

    private fun conv(
        uuid: String,
        creationDate: Long,
        lastMessageDate: Long? = null,
        isReserved: Boolean = false,
    ) = ConversationEntity(
        uuid = uuid,
        title = uuid,
        characterUuid = "char",
        creationDate = creationDate,
        lastMessageDate = lastMessageDate,
        isReservedForNotifications = isReserved,
    )

    @Test
    fun `活跃对话取 lastMessageDate 最新`() {
        val older = conv("older", creationDate = 1L, lastMessageDate = 1_000_000L)
        val newer = conv("newer", creationDate = 1L, lastMessageDate = 2_000_000L)
        val picked = StreakNotificationBridgeService.selectPreferredConversation(listOf(older, newer))
        assertEquals("newer", picked?.uuid)
    }

    @Test
    fun `无活跃对话回退到最新 reserved`() {
        val olderReserved = conv("olderR", creationDate = 500_000L, isReserved = true)
        val newerReserved = conv("newerR", creationDate = 900_000L, isReserved = true)
        val picked = StreakNotificationBridgeService.selectPreferredConversation(listOf(olderReserved, newerReserved))
        assertEquals("newerR", picked?.uuid)
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(StreakNotificationBridgeService.selectPreferredConversation(emptyList()))
    }

    @Test
    fun `活跃对话优先于 reserved`() {
        val active = conv("active", creationDate = 1L, lastMessageDate = 1_000_000L)
        // reserved 的 creationDate 再大也不敌一条有 lastMessageDate 的活跃会话（活跃分支优先）。
        val reserved = conv("reserved", creationDate = 999_999_999L, isReserved = true)
        val picked = StreakNotificationBridgeService.selectPreferredConversation(listOf(reserved, active))
        assertEquals("active", picked?.uuid)
    }

    @Test
    fun `待物化标记 JSON 可往返`() {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(PendingDeliveryStore.PendingDelivery.serializer())
        val markers = listOf(
            PendingDeliveryStore.PendingDelivery(
                deliveryIdentifier = "d1",
                characterId = "c1",
                category = "morning",
                conversationUuid = "conv1",
                notificationBody = "早安~",
                requestIdentifier = "rk1",
                scheduledAt = 111L,
                deliveredAt = 222L,
            ),
        )
        val encoded = json.encodeToString(serializer, markers)
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(markers, decoded)
    }
}
