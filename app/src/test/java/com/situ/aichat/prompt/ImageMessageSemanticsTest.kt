package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageContentSentinels
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.prompt.memory.MemoryService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：图片消息的**语义占位单源**。
 *
 * 背景（勘察结论）：图片消息照 iOS 口径不新增 [MessageKind]（= PLAIN_TEXT + 侧车 `imageRelativePath`），
 * 所以 [messageLlmSafeText] 那个「新增 Kind 编译器就报错」的护栏对图片天然失效——日记 / 日程 / 主动通知 /
 * 故事 / 见面记忆五条旁路都只调 safeText，从前会拿到三个字符的 `[图片]` 噪音，而同一条消息在记忆链路里
 * 却是「发送了一张图片：{摘要}」。本组断言把图片语义钉死在 safeText 内，五条旁路自动受益。
 */
class ImageMessageSemanticsTest {

    private fun msg(
        content: String,
        image: String? = null,
        summary: String = "",
        kind: MessageKind = MessageKind.PLAIN_TEXT,
    ) = MessageEntity(
        messageUUID = "m1",
        conversationUuid = "c1",
        content = content,
        roleRaw = "user",
        timestamp = 0L,
        messageKindRaw = kind.raw,
        imageRelativePath = image,
        mediaMemorySummary = summary,
    )

    // ---------- renderImageSemantics 四象限 ----------

    @Test
    fun `纯图片无摘要`() {
        assertEquals("发送了一张图片", MemoryService.renderImageSemantics(MessageContentSentinels.IMAGE_PLACEHOLDER, ""))
    }

    @Test
    fun `纯图片有摘要`() {
        assertEquals(
            "发送了一张图片：一只趴在窗台的橘猫",
            MemoryService.renderImageSemantics("[图片]", "一只趴在窗台的橘猫"),
        )
    }

    @Test
    fun `带配文无摘要`() {
        assertEquals("你看这个（并发送了一张图片）", MemoryService.renderImageSemantics("你看这个", ""))
    }

    @Test
    fun `带配文有摘要`() {
        assertEquals(
            "你看这个（图片内容：黄昏的海）",
            MemoryService.renderImageSemantics("你看这个", "黄昏的海"),
        )
    }

    @Test
    fun `空正文等同占位`() {
        assertEquals("发送了一张图片", MemoryService.renderImageSemantics("   ", ""))
    }

    // ---------- 哨兵常量与判等端同源 ----------

    @Test
    fun `哨兵常量就是渲染端判等用的那个字面量`() {
        // 两端一旦不同源，`[图片]` 会原样漏进提示词/长期记忆
        val rendered = MemoryService.renderImageSemantics(MessageContentSentinels.IMAGE_PLACEHOLDER, "x")
        assertFalse(rendered.contains(MessageContentSentinels.IMAGE_PLACEHOLDER))
    }

    // ---------- messageLlmSafeText 收口（五条旁路的共同入口） ----------

    @Test
    fun `带图消息经 safeText 得到语义文本而非裸占位`() {
        val out = messageLlmSafeText(msg("[图片]", image = "/data/x.jpg", summary = "生日蛋糕"))
        assertEquals("发送了一张图片：生日蛋糕", out)
    }

    @Test
    fun `带图且有配文时配文保留`() {
        val out = messageLlmSafeText(msg("今天做的", image = "/data/x.jpg", summary = "生日蛋糕"))
        assertEquals("今天做的（图片内容：生日蛋糕）", out)
    }

    @Test
    fun `无图普通消息原样返回`() {
        assertEquals("普通一句话", messageLlmSafeText(msg("普通一句话")))
    }

    @Test
    fun `尚未生成摘要时也不留裸占位`() {
        // 摘要是异步生成的，图片刚发出的那几秒 mediaMemorySummary 还是空
        val out = messageLlmSafeText(msg("[图片]", image = "/data/x.jpg", summary = ""))
        assertEquals("发送了一张图片", out)
        assertTrue(out?.contains("[") == false)
    }
}
