package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MessageContentSentinels
import com.situ.aichat.data.remote.llm.ChatContentPart
import com.situ.aichat.data.remote.llm.ChatMessageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 图片消息的**报文装配**（`PromptBuilderHistory` 的图片分支）。
 *
 * 补这组的理由：这是本卷最高危的一面（决定图片是真挂进请求还是退成文字、决定内部哨兵会不会漏进提示词），
 * 而它此前唯一的证据是一次模拟器手跑——独立复核直接点名「零测试覆盖」。图片段只在私有
 * `appendConversationMessages` 里成型，必须端到端走 [PromptBuilder.buildMessages] 才验得到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBuilderImagePartTest {

    private val dataUri = "data:image/jpeg;base64,QQ=="

    private fun imageMessage(
        uuid: String = "m1",
        content: String = MessageContentSentinels.IMAGE_PLACEHOLDER,
        summary: String = "",
    ) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "c1",
        roleRaw = "user",
        content = content,
        timestamp = 1L,
        imageRelativePath = "/data/x.jpg",
        mediaMemorySummary = summary,
    )

    private fun build(
        message: MessageEntity,
        visionEnabled: Boolean,
        attachments: Map<String, String>,
    ): List<ChatMessageDto> = PromptBuilder.buildMessages(
        character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L),
        sortedMessages = listOf(message),
        userProfile = null,
        appSettings = AppSettings(),
        strings = PromptStrings(RuntimeEnvironment.getApplication()),
        visionEnabled = visionEnabled,
        imageAttachments = attachments,
    )

    private fun List<ChatMessageDto>.userParts(): List<ChatContentPart>? =
        lastOrNull { it.role == PromptBuilder.ROLE_USER }?.contentParts

    private fun List<ChatMessageDto>.userText(): String =
        lastOrNull { it.role == PromptBuilder.ROLE_USER }?.content.orEmpty()

    // ---------- 挂图路径 ----------

    @Test
    fun `视觉开启且有附件时挂 image 段`() {
        val parts = build(imageMessage(), visionEnabled = true, attachments = mapOf("m1" to dataUri)).userParts()
        assertEquals(2, parts?.size)
        assertTrue("text 段必须在前（OpenAI 兼容口径）", parts?.get(0) is ChatContentPart.Text)
        assertEquals(ChatContentPart.ImageUrl(dataUri), parts?.get(1))
    }

    @Test
    fun `纯图片消息的 text 段不得漏出内部哨兵`() {
        val parts = build(imageMessage(), visionEnabled = true, attachments = mapOf("m1" to dataUri)).userParts()
        val text = (parts?.get(0) as ChatContentPart.Text).text
        assertFalse("`[图片]` 是内部哨兵，绝不能喂给模型，实际：$text", text.contains("[图片]"))
        assertEquals("发送了一张图片", text)
    }

    @Test
    fun `有配文时 text 段用用户配文`() {
        val parts = build(
            imageMessage(content = "你看这个"),
            visionEnabled = true,
            attachments = mapOf("m1" to dataUri),
        ).userParts()
        assertEquals("你看这个", (parts?.get(0) as ChatContentPart.Text).text)
    }

    // ---------- 不挂图的三种回落 ----------

    @Test
    fun `视觉关闭时退语义占位且不带 image 段`() {
        val msgs = build(imageMessage(summary = "一只橘猫"), visionEnabled = false, attachments = emptyMap())
        assertNull("视觉关就不该有 contentParts", msgs.userParts())
        assertEquals("发送了一张图片：一只橘猫", msgs.userText())
    }

    @Test
    fun `超出最近 N 张窗口的图退语义占位`() {
        // 视觉开着，但这条不在 attachments 里（= 被更新的图挤出名额）
        val msgs = build(imageMessage(summary = "生日蛋糕"), visionEnabled = true, attachments = emptyMap())
        assertNull(msgs.userParts())
        assertEquals("发送了一张图片：生日蛋糕", msgs.userText())
    }

    @Test
    fun `文件读不到时退语义占位而非留裸哨兵`() {
        val msgs = build(imageMessage(), visionEnabled = true, attachments = emptyMap())
        assertFalse("绝不能把 `[图片]` 原样送进提示词，实际：${msgs.userText()}", msgs.userText().contains("[图片]"))
        assertEquals("发送了一张图片", msgs.userText())
    }

    @Test
    fun `摘要未回来时的占位仍是有意义的一句`() {
        val msgs = build(imageMessage(summary = ""), visionEnabled = false, attachments = emptyMap())
        assertEquals("发送了一张图片", msgs.userText())
    }

    @Test
    fun `带配文且不挂图时配文保留`() {
        val msgs = build(
            imageMessage(content = "今天做的", summary = "生日蛋糕"),
            visionEnabled = false,
            attachments = emptyMap(),
        )
        assertEquals("今天做的（图片内容：生日蛋糕）", msgs.userText())
    }

    // ---------- 无图消息不受影响（回归守卫） ----------

    @Test
    fun `无图普通消息装配不变`() {
        val plain = MessageEntity(
            messageUUID = "m2",
            conversationUuid = "c1",
            roleRaw = "user",
            content = "普通一句话",
            timestamp = 1L,
        )
        val msgs = build(plain, visionEnabled = true, attachments = mapOf("m1" to dataUri))
        assertNull("无图消息不该被挂上 contentParts", msgs.userParts())
        assertEquals("普通一句话", msgs.userText())
    }
}
