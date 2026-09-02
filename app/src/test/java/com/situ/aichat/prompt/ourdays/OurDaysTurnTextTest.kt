package com.situ.aichat.prompt.ourdays

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1-2（卷二图纸 §7.2）：「用户当前消息组」文本。断言从 §3.3 规格独立反推：尾部连续 user 组 / 遇 assistant 停 /
 * 过滤耳语与结构化卡 / 渲染同源（表情包标签语义化·图片摘要）/ 600 码点保尾。
 */
class OurDaysTurnTextTest {

    private var seq = 0L
    private fun msg(role: String, content: String, kind: MessageKind = MessageKind.PLAIN_TEXT, image: String? = null, summary: String = "") =
        MessageEntity(
            messageUUID = "m${seq}", conversationUuid = "c", roleRaw = role, content = content, timestamp = ++seq,
            messageKindRaw = kind.raw, imageRelativePath = image, mediaMemorySummary = summary,
        )

    @Test fun 尾部连续user组合并连发_升序换行拼接() {
        val text = OurDaysTurnText.from(listOf(msg("assistant", "嗯"), msg("user", "上周三"), msg("user", "我们聊了啥")))
        assertEquals("上周三\n我们聊了啥", text)
    }

    @Test fun 遇到assistant即停_更早的user不算() {
        val text = OurDaysTurnText.from(listOf(msg("user", "更早的"), msg("assistant", "回"), msg("user", "只有这句")))
        assertEquals("只有这句", text)
    }

    @Test fun 尾条是assistant则为空_空列表为空() {
        assertEquals("", OurDaysTurnText.from(listOf(msg("user", "x"), msg("assistant", "y"))))
        assertEquals("", OurDaysTurnText.from(emptyList()))
    }

    @Test fun 过滤耳语与结构化卡_不打断连续组() {
        val text = OurDaysTurnText.from(
            listOf(
                msg("user", "真正的话"),
                msg("user", "{\"gift\":1}", kind = MessageKind.GIFT_CARD),
                msg("user", "系统旁白", kind = MessageKind.SYSTEM_HINT),
                msg("user", ""),
            ),
        )
        assertEquals("真正的话", text)
    }

    @Test fun 渲染同源_表情包标签语义化() {
        assertEquals("发送了表情包「开心」", OurDaysTurnText.from(listOf(msg("user", "[sticker:开心_1]"))))
    }

    @Test fun 渲染同源_图片带摘要() {
        val text = OurDaysTurnText.from(listOf(msg("user", "[图片]", image = "/img/1.jpg", summary = "海边的黄昏")))
        assertEquals("发送了一张图片：海边的黄昏", text)
    }

    @Test fun 超600码点从头逐行删至不超_保尾() {
        val a = "甲".repeat(400)
        val b = "乙".repeat(300)
        val c = "丙".repeat(200)
        val text = OurDaysTurnText.from(listOf(msg("user", a), msg("user", b), msg("user", c)))
        assertEquals("$b\n$c", text) // 300 + 1 + 200 = 501 ≤ 600；带上 a 则 902 超
    }

    @Test fun 单行超600码点截尾600() {
        val long = "前".repeat(100) + "后".repeat(600)
        assertEquals("后".repeat(600), OurDaysTurnText.from(listOf(msg("user", long))))
    }

    @Test fun 码点按代理对计数() {
        // 每个 emoji 是 2 个 UTF-16 单元、1 个码点：600 个 emoji 恰好 600 码点 ⇒ 不裁。
        val emojis = "😀".repeat(600)
        assertEquals(emojis, OurDaysTurnText.from(listOf(msg("user", emojis))))
    }
}
