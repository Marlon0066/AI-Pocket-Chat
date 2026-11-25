package com.situ.aichat.worldbook

import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.WorldBookDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.WorldBookTimedStateEntity
import com.situ.aichat.data.model.MessageKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 世界书回合编排 T2（WB4·MockK 假 DAO）：无书/无条目早退零多余查询、命中出结果、
 * 时效状态过期删 + 新触发落库、结构化卡与 system 消息不进扫描缓冲。
 */
class WorldBookPromptServiceTest {

    private val dao = mockk<WorldBookDao>()
    private val vectorService = mockk<WorldBookVectorService> {
        coEvery { matchedEntryUuids(any(), any(), any()) } returns emptySet()
    }

    // 批1 1-3：计数锚改 DAO 真实 COUNT——mock 成与本组用例扫描消息数同量级（时效用例 3 条），语义等价旧锚。
    private val messageDao = mockk<MessageDao> {
        coEvery { countScannableForWorldBook(any()) } returns 3
    }
    private val service = WorldBookPromptService(dao, vectorService, messageDao)

    private fun msg(uuid: String, role: String, content: String, kindRaw: String? = null): MessageEntity =
        if (kindRaw != null) {
            MessageEntity(messageUUID = uuid, conversationUuid = "conv1", roleRaw = role, content = content, timestamp = 0L, messageKindRaw = kindRaw)
        } else {
            MessageEntity(messageUUID = uuid, conversationUuid = "conv1", roleRaw = role, content = content, timestamp = 0L)
        }

    private fun run(messages: List<MessageEntity>) = runBlocking {
        service.activateForTurn(
            characterUuid = "c1",
            conversationUuid = "conv1",
            sortedMessages = messages,
            characterName = "小雨",
            userName = "阿檀",
        )
    }

    @Test
    fun 无书_返回null且不再查条目() {
        coEvery { dao.activeBooksForCharacter("c1") } returns emptyList()
        assertNull(run(listOf(msg("u1", "user", "你好"))))
        coVerify(exactly = 0) { dao.entriesForBooks(any()) }
    }

    @Test
    fun 有书无条目_返回null且不查时效() {
        coEvery { dao.activeBooksForCharacter("c1") } returns listOf(wbBook("b1"))
        coEvery { dao.entriesForBooks(listOf("b1")) } returns emptyList()
        assertNull(run(listOf(msg("u1", "user", "你好"))))
        coVerify(exactly = 0) { dao.timedStatesForConversation(any()) }
    }

    @Test
    fun 关键词命中_出激活结果() {
        coEvery { dao.activeBooksForCharacter("c1") } returns listOf(wbBook("b1"))
        coEvery { dao.entriesForBooks(listOf("b1")) } returns
            listOf(wbEntry("e1", keys = listOf("青云宗"), content = "门派设定"))
        coEvery { dao.timedStatesForConversation("conv1") } returns emptyList()

        val result = run(listOf(msg("u1", "user", "聊聊青云宗")))
        assertEquals("门派设定", result?.after)
    }

    @Test
    fun 时效状态_过期删除_新触发落库() {
        val expired = WorldBookTimedStateEntity("conv1", "e1", "cooldown", 0, 1)
        coEvery { dao.activeBooksForCharacter("c1") } returns listOf(wbBook("b1"))
        coEvery { dao.entriesForBooks(listOf("b1")) } returns
            listOf(wbEntry("e1", keys = listOf("青云宗"), sticky = 2, content = "门派设定"))
        coEvery { dao.timedStatesForConversation("conv1") } returns listOf(expired)
        coEvery { dao.clearTimedState(any(), any(), any()) } just runs
        coEvery { dao.upsertTimedState(any()) } just runs

        val messages = listOf(msg("u1", "user", "一"), msg("a1", "assistant", "二"), msg("u2", "user", "聊聊青云宗"))
        val result = run(messages)

        assertEquals("门派设定", result?.after)
        coVerify { dao.clearTimedState("conv1", "e1", "cooldown") }
        coVerify {
            dao.upsertTimedState(
                match { it.effectType == "sticky" && it.durationMessages == 2 && it.entryUuid == "e1" },
            )
        }
    }

    @Test
    fun 向量条目_经服务链路激活() {
        coEvery { dao.activeBooksForCharacter("c1") } returns listOf(wbBook("b1"))
        coEvery { dao.entriesForBooks(listOf("b1")) } returns
            listOf(wbEntry("e1", vectorized = true, content = "语义设定"))
        coEvery { dao.timedStatesForConversation("conv1") } returns emptyList()
        coEvery { vectorService.matchedEntryUuids(any(), any(), any()) } returns setOf("e1")

        val result = run(listOf(msg("u1", "user", "完全无关的话")))
        assertEquals("向量命中的链接条目应激活", "语义设定", result?.after)
    }

    @Test
    fun 结构化卡与system消息_不进扫描缓冲() {
        coEvery { dao.activeBooksForCharacter("c1") } returns listOf(wbBook("b1"))
        coEvery { dao.entriesForBooks(listOf("b1")) } returns
            listOf(wbEntry("e1", keys = listOf("红包"), content = "不该被机制文本触发"))
        coEvery { dao.timedStatesForConversation("conv1") } returns emptyList()

        val cardKind = MessageKind.entries.first { it.isStructuredCard }.raw
        val messages = listOf(
            msg("s1", "system", "系统提到了红包机制"),
            msg("a1", "assistant", "红包卡JSON", kindRaw = cardKind),
            msg("u1", "user", "普通聊天"),
        )
        assertNull("扫描缓冲必须排除 system 与结构化卡", run(messages))
    }

    @Test
    fun SYSTEM_HINT与待投递消息不进扫描_引用与图片摘要可触发() = runBlocking {
        coEvery { dao.activeBooksForCharacter("c1") } returns listOf(wbBook("b1"))
        coEvery { dao.entriesForBooks(listOf("b1")) } returns listOf(
            wbEntry("e-hint", keys = listOf("耳语暗号"), content = "不该被耳语触发"),
            wbEntry("e-held", keys = listOf("未送达暗号"), content = "不该被延迟未送达消息触发"),
            wbEntry("e-quote", keys = listOf("古籍残页"), content = "引用文本触发成功"),
            wbEntry("e-img", keys = listOf("青铜古剑"), content = "图片摘要触发成功"),
        )
        coEvery { dao.timedStatesForConversation("conv1") } returns emptyList()

        val messages = listOf(
            MessageEntity(
                messageUUID = "h1",
                conversationUuid = "conv1",
                roleRaw = "user",
                content = "耳语暗号",
                timestamp = 1L,
                messageKindRaw = MessageKind.SYSTEM_HINT.raw,
            ),
            MessageEntity(
                messageUUID = "h2",
                conversationUuid = "conv1",
                roleRaw = "assistant",
                content = "未送达暗号",
                timestamp = 2L,
                isHeldForDelivery = true,
            ),
            MessageEntity(
                messageUUID = "u1",
                conversationUuid = "conv1",
                roleRaw = "user",
                content = "你看这个",
                quotedContent = "上面写着古籍残页四个字",
                timestamp = 3L,
            ),
            MessageEntity(
                messageUUID = "u2",
                conversationUuid = "conv1",
                roleRaw = "user",
                content = "[图片]",
                imageRelativePath = "chat_images/sword.jpg",
                mediaMemorySummary = "一把刻着铭文的青铜古剑",
                timestamp = 4L,
            ),
        )

        val result = service.activateForTurn(
            characterUuid = "c1",
            conversationUuid = "conv1",
            sortedMessages = messages,
            characterName = "小雨",
            userName = "阿檀",
        )
        val after = result?.after.orEmpty()
        assertEquals(false, after.contains("不该被耳语触发"))
        assertEquals(false, after.contains("不该被延迟未送达消息触发"))
        assertEquals(true, after.contains("引用文本触发成功"))
        assertEquals(true, after.contains("图片摘要触发成功"))
    }
}
