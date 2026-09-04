package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.MessageEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引用一期 · 被引用消息预取（图纸 §3.1 / 边界 B3–B5）：[MessageRepository.quotedRefs] 的**查库次数**与内容口径。
 *
 * 断言从图纸规格独立反推：窗口内命中零次 `getByUuid`（被引用的多半就在窗口里，逐条查库=白白多 N 次 IO）、
 * 窗口外才落库一次、窗口里一条引用都没有时**一次都不查**、查不到的 uuid 不进表（留给调用方无锚降级）。
 */
class MessageRepositoryQuotedRefsTest {

    private val dao = mockk<MessageDao>(relaxed = true)
    private val repo = MessageRepository(dao)

    private fun msg(
        uuid: String,
        content: String = "正文$uuid",
        timestamp: Long = 1_000L,
        quotedUuid: String? = null,
        quotedContent: String? = null,
    ) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "conv1",
        roleRaw = "user",
        content = content,
        timestamp = timestamp,
        quotedMessageUUID = quotedUuid,
        quotedContent = quotedContent,
    )

    @Test
    fun 被引用消息就在窗口里时零次查库() = runBlocking {
        val quoted = msg("q1", content = "晚上七点老地方", timestamp = 5_000L)
        val quoting = msg("u1", quotedUuid = "q1", quotedContent = "晚上七点老地方")

        val refs = repo.quotedRefs(listOf(quoted, quoting))

        assertEquals(QuotedMessageRef(timestampMillis = 5_000L, rawContent = "晚上七点老地方"), refs["q1"])
        coVerify(exactly = 0) { dao.getByUuid(any()) }
    }

    @Test
    fun 结构化卡的原始JSON绝不进预取表_红包金额零泄漏() = runBlocking {
        // 复核 R1 🔴：收紧之前（9b848de3 以前）右滑能引用红包/礼物卡，库里因此存得下「引用了一张红包卡」的老行。
        // 落库那一侧当年**特意**只存脱敏串（AssistantTurnController 注释点名三处泄漏面，其中就有
        // 「③PromptBuilder 引用上下文喂 LLM」）；预取表若把原始 JSON 端回去，等于把那个修复原样拆掉——
        // 红包 amount「永远不露给 LLM」是 RedPacketData 的硬规矩。
        val cardJson = """{"type":"red_packet","recordUUID":"9f2c8a41-1111-2222-3333-444455556666","amount":88,"blessingText":"新年快乐"}"""
        val card = MessageEntity(
            messageUUID = "rp1",
            conversationUuid = "conv1",
            roleRaw = "user",
            content = cardJson,
            timestamp = 7_000L,
            messageKindRaw = com.situ.aichat.data.model.MessageKind.RED_PACKET.raw,
        )
        val quoting = msg("u1", quotedUuid = "rp1", quotedContent = "🧧 红包")

        val refs = repo.quotedRefs(listOf(card, quoting))

        assertNull("卡片原文绝不进预取表（否则红包 amount 直喂 LLM）", refs["rp1"]?.rawContent)
        assertEquals("时间戳照给——老引用也该拿到时间锚", 7_000L, refs["rp1"]?.timestampMillis)
    }

    @Test
    fun 图片消息不进预取表正文_内部哨兵不外泄() = runBlocking {
        // 同源：图片正文是内部哨兵 `[图片]`，落库快照也是它；原文没有额外信息，统一按「不安全」处理。
        val img = MessageEntity(
            messageUUID = "im1",
            conversationUuid = "conv1",
            roleRaw = "user",
            content = "[图片]",
            timestamp = 8_000L,
            imageRelativePath = "img/a.jpg",
        )
        val quoting = msg("u1", quotedUuid = "im1", quotedContent = "[图片]")

        val refs = repo.quotedRefs(listOf(img, quoting))

        assertNull(refs["im1"]?.rawContent)
        assertEquals(8_000L, refs["im1"]?.timestampMillis)
    }

    @Test
    fun 被引用消息在窗口外时落库一次() = runBlocking {
        coEvery { dao.getByUuid("old") } returns msg("old", content = "半个月前那句", timestamp = 42L)
        val quoting = msg("u1", quotedUuid = "old", quotedContent = "半个月前那句")

        val refs = repo.quotedRefs(listOf(quoting))

        assertEquals(QuotedMessageRef(timestampMillis = 42L, rawContent = "半个月前那句"), refs["old"])
        coVerify(exactly = 1) { dao.getByUuid("old") }
    }

    @Test
    fun 窗口里一条引用都没有时零次查库且返回空表() = runBlocking {
        val refs = repo.quotedRefs(listOf(msg("m1"), msg("m2")))

        assertTrue("没有引用就不该有条目", refs.isEmpty())
        coVerify(exactly = 0) { dao.getByUuid(any()) }
    }

    @Test
    fun 原消息已被删除时该uuid不进表() = runBlocking {
        // B3：用户删掉了被引用的那条 → getByUuid 返 null → 不进表，调用方据此走无锚 + 回退落库快照。
        coEvery { dao.getByUuid("gone") } returns null
        val refs = repo.quotedRefs(listOf(msg("u1", quotedUuid = "gone", quotedContent = "删掉了")))

        assertNull(refs["gone"])
        assertTrue(refs.isEmpty())
        coVerify(exactly = 1) { dao.getByUuid("gone") }
    }

    @Test
    fun 有uuid但落库正文为空时不算需要预取() = runBlocking {
        // 与 PromptBuilderHistory 的注入守卫同口径：quotedContent 空 = 根本不产引用行，别为它查库。
        val refs = repo.quotedRefs(listOf(msg("u1", quotedUuid = "q1", quotedContent = "")))

        assertTrue(refs.isEmpty())
        coVerify(exactly = 0) { dao.getByUuid(any()) }
    }

    @Test
    fun 一轮多条引用一次预取全覆盖且同一目标只查一次() = runBlocking {
        // B4：三条用户消息，两条引用窗口外的同一条、一条引用窗口内的。
        coEvery { dao.getByUuid("far") } returns msg("far", content = "很久以前", timestamp = 7L)
        val inWindow = msg("near", content = "刚才那句", timestamp = 9_000L)
        val window = listOf(
            inWindow,
            msg("u1", quotedUuid = "far", quotedContent = "很久以前"),
            msg("u2", quotedUuid = "far", quotedContent = "很久以前"),
            msg("u3", quotedUuid = "near", quotedContent = "刚才那句"),
        )

        val refs = repo.quotedRefs(window)

        assertEquals(setOf("far", "near"), refs.keys)
        assertEquals("很久以前", refs["far"]?.rawContent)
        assertEquals(9_000L, refs["near"]?.timestampMillis)
        coVerify(exactly = 1) { dao.getByUuid("far") }
        coVerify(exactly = 0) { dao.getByUuid("near") }
    }

    @Test
    fun 预取的是原始正文而不是落库显示串() = runBlocking {
        // 决策三的数据侧前提：表里必须是含 `[sticker:…]` 的原文，下游那一步才转得成语义。
        val quoted = msg("q1", content = "[sticker:s1]", timestamp = 3_000L)
        val quoting = msg("u1", quotedUuid = "q1", quotedContent = "[表情包]")

        val refs = repo.quotedRefs(listOf(quoted, quoting))

        assertEquals("[sticker:s1]", refs["q1"]?.rawContent)
        assertFalse("显示串绝不能进预取表", refs["q1"]?.rawContent?.contains("[表情包]") == true)
    }
}
