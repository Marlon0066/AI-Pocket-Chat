package com.situ.aichat.seam

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.OfflineInviteJson
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.offline.OfflineMeetingService
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 卷一 C10「邀约卡按 uuid 取」行为测试（图纸 §7 T2-C10·E7）：用户往回翻点**旧卡**时，必须进那张旧卡的约，
 * 而不是被「扫最近一张」带进另一场约；uuid 失效（已删/非邀约卡/解析失败）→ 回落现状扫描口径；
 * 不传 uuid → 与改动前逐字一致（N1）。
 */
class InviteCardByUuidTest {

    private lateinit var messageRepo: MessageRepository
    private lateinit var service: OfflineMeetingService

    private fun card(uuid: String, location: String, activity: String, ts: Long) = MessageEntity(
        messageUUID = uuid, conversationUuid = "conv-1", roleRaw = "assistant",
        content = OfflineInviteJson.makeInvite(location = location, activity = activity, invitation = "一起？"),
        timestamp = ts, messageKindRaw = MessageKind.OFFLINE_INVITE_CARD.raw,
    )

    private val oldCard = card("old", "图书馆", "看书", 1L)
    private val newCard = card("new", "球场", "看比赛", 2L)

    @Before
    fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        messageRepo = mockk(relaxed = true)
        coEvery { messageRepo.recentChronological("conv-1", any()) } returns listOf(oldCard, newCard)
        service = OfflineMeetingService(
            db = mockk<AppDatabase>(relaxed = true), messageRepo = messageRepo,
            conversationRepo = mockk(relaxed = true), characterRepo = mockk(relaxed = true),
            scheduleDao = mockk(relaxed = true), userProfileDao = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() = unmockkStatic("androidx.room.RoomDatabaseKt")

    @Test
    fun 点旧卡_进旧卡的约() = runBlocking {
        coEvery { messageRepo.get("old") } returns oldCard
        val extract = service.extractInviteInfo("conv-1", "old")
        assertEquals("图书馆", extract.location)
        assertEquals("看书", extract.activity)
    }

    @Test
    fun 不传uuid_维持扫最近一张() = runBlocking {
        val extract = service.extractInviteInfo("conv-1")
        assertEquals("球场", extract.location)
        assertEquals("看比赛", extract.activity)
    }

    /** E7：uuid 指向的消息已删 → 回落扫描口径（不崩、不空）。 */
    @Test
    fun uuid已删_回落扫描() = runBlocking {
        coEvery { messageRepo.get("gone") } returns null
        val extract = service.extractInviteInfo("conv-1", "gone")
        assertEquals("球场", extract.location)
    }

    /** E7：uuid 指向的不是邀约卡（普通文本）→ 回落扫描口径。 */
    @Test
    fun uuid非邀约卡_回落扫描() = runBlocking {
        coEvery { messageRepo.get("plain") } returns MessageEntity(
            messageUUID = "plain", conversationUuid = "conv-1", roleRaw = "assistant",
            content = "明天见？", timestamp = 3L,
        )
        val extract = service.extractInviteInfo("conv-1", "plain")
        assertEquals("球场", extract.location)
    }

    /** E7：kind 是邀约卡但正文解析失败（脏数据）→ 回落扫描口径。 */
    @Test
    fun uuid正文解析失败_回落扫描() = runBlocking {
        coEvery { messageRepo.get("broken") } returns MessageEntity(
            messageUUID = "broken", conversationUuid = "conv-1", roleRaw = "assistant",
            content = "{不是合法JSON", timestamp = 3L, messageKindRaw = MessageKind.OFFLINE_INVITE_CARD.raw,
        )
        val extract = service.extractInviteInfo("conv-1", "broken")
        assertEquals("球场", extract.location)
    }

    /** 一张卡都没有 → 兜底默认（现状不变）。 */
    @Test
    fun 无任何卡_兜底默认() = runBlocking {
        coEvery { messageRepo.recentChronological("conv-1", any()) } returns emptyList()
        val extract = service.extractInviteInfo("conv-1", null)
        assertEquals("某个地方", extract.location)
        assertEquals("一起出去", extract.activity)
    }
}
