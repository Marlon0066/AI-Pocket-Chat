package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.QuotedMessageRef
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * 引用行的**端到端报文装配**（`PromptBuilderHistory` 的引用块 → [PromptBuilder.buildMessages]）。
 *
 * 补这组的理由：引用行只在私有 `appendConversationMessages` 里成型，纯函数单测（[PromptQuoteLineTest]）
 * 证不到「预取表真的接上了 / now 门控真的传下去了 / 引用行里的表情标签真的被下游转成了语义」这三件；
 * 尤其最后一件是图纸 §0.2 决策三（引用注入排在表情转语义之前）的**唯一证据**。
 *
 * 断言从图纸 §3.2/§3.4 独立反推，整串重新打字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PromptBuilderQuoteLineTest {

    // 引用行内部用 ZoneId.systemDefault()（与时间分割线同一个 dividerZone）——钉死 Asia/Shanghai 保证锚断言确定。
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var originalTz: TimeZone

    @Before
    fun pinTimeZone() {
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTz)
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** 现在 = 2026-08-20 21:00；被引用那句 = 2026-08-17 09:12（该日真实星期 = 周一）。 */
    private val now: Instant = Instant.ofEpochMilli(at(2026, 8, 20, 21, 0))
    private val quotedAt: Long = at(2026, 8, 17, 9, 12)

    private fun quotedMessage(content: String = "晚上七点老地方") = MessageEntity(
        messageUUID = "q1",
        conversationUuid = "conv1",
        roleRaw = "assistant",
        content = content,
        timestamp = quotedAt,
    )

    /** 带引用的用户消息；[snapshot] = 落库的 `quotedContent` 显示串（预取失败时的回退源）。 */
    private fun quotingMessage(snapshot: String = "晚上七点老地方") = MessageEntity(
        messageUUID = "u1",
        conversationUuid = "conv1",
        roleRaw = "user",
        content = "好",
        timestamp = at(2026, 8, 20, 20, 55),
        quotedMessageUUID = "q1",
        quotedContent = snapshot,
        quotedSenderRole = "assistant",
    )

    private fun build(
        history: List<MessageEntity>,
        refs: Map<String, QuotedMessageRef> = emptyMap(),
        profile: UserProfileEntity? = UserProfileEntity(nickname = "司徒"),
        conversation: ConversationEntity? = null,
        stickers: List<CustomStickerEntity> = emptyList(),
    ): List<ChatMessageDto> = PromptBuilder.buildMessages(
        character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L),
        conversation = conversation,
        sortedMessages = history,
        userProfile = profile,
        appSettings = AppSettings(),
        strings = PromptStrings(RuntimeEnvironment.getApplication()),
        customStickers = stickers,
        quotedRefs = refs,
        now = now,
    )

    /** 带引用的那条用户消息最终落进报文的正文（引用行在最前、`\n` 后接原正文）。 */
    private fun quotingTurnText(msgs: List<ChatMessageDto>): String =
        msgs.last { it.role == PromptBuilder.ROLE_USER }.content.orEmpty()

    // ---- 预取表接没接上（有锚 / 无锚） ----

    @Test
    fun `传了预取表就带精确时间锚`() {
        val msgs = build(
            history = listOf(quotedMessage(), quotingMessage()),
            refs = mapOf("q1" to QuotedMessageRef(timestampMillis = quotedAt, rawContent = "晚上七点老地方")),
        )
        assertTrue(
            "实际：${quotingTurnText(msgs)}",
            quotingTurnText(msgs).startsWith("【司徒在回复你 8月17日 周一 09:12 说的这句：「晚上七点老地方」】\n好"),
        )
    }

    @Test
    fun `没有预取表时走无锚降级并回退落库快照`() {
        // B2/B3：uuid 为 ghost 值 / 被引用消息已被删 → 该 uuid 不进表；此时既没锚，正文也只能用落库那份。
        val msgs = build(
            history = listOf(quotingMessage(snapshot = "落库那份快照")),
            refs = emptyMap(),
        )
        assertTrue(
            "实际：${quotingTurnText(msgs)}",
            quotingTurnText(msgs).startsWith("【司徒在回复你先前说的这句：「落库那份快照」】\n好"),
        )
    }

    @Test
    fun `线下见面场景恒无锚（与时间分割线共用 now 门控）`() {
        // §3.2-D-a：线下叙事刻意不打时间戳线，引用锚跟着一起不出——即便预取表是满的。
        val offlineHistory = listOf(quotedMessage(), quotingMessage())
            .map { it.copy(isOfflineMode = true, offlineSessionId = "sess1") }
        val msgs = build(
            history = offlineHistory,
            refs = mapOf("q1" to QuotedMessageRef(timestampMillis = quotedAt, rawContent = "晚上七点老地方")),
            conversation = ConversationEntity(
                uuid = "conv1", title = "会话", characterUuid = "c1", creationDate = 0L,
                isInOfflineMode = true, currentOfflineSessionId = "sess1",
            ),
        )
        val text = quotingTurnText(msgs)
        assertTrue("实际：$text", text.startsWith("【司徒在回复你先前说的这句：「晚上七点老地方」】\n好"))
        assertFalse("线下不许冒出时间锚", text.contains("8月17日"))
    }

    // ---- 决策三：引用到的表情走语义，而不是零信息的 `[表情包]` ----

    @Test
    fun `引用一条表情消息时报文里是语义描述而非裸标签`() {
        val msgs = build(
            // 落库的 quotedContent 是给人看的显示串（`[表情包]`），预取表给的是原始 content（含标签）。
            history = listOf(quotedMessage(content = "[sticker:s1]"), quotingMessage(snapshot = "[表情包]")),
            refs = mapOf("q1" to QuotedMessageRef(timestampMillis = quotedAt, rawContent = "[sticker:s1]")),
            stickers = listOf(CustomStickerEntity(stickerUuid = "s1", name = "摊手", semanticDescription = "无奈地摊手")),
        )
        val text = quotingTurnText(msgs)
        assertTrue(
            "实际：$text",
            text.startsWith("【司徒在回复你 8月17日 周一 09:12 说的这句：「[非语言情绪：无奈地摊手]」】\n好"),
        )
        assertFalse("内部标签绝不许漏给模型", text.contains("[sticker:"))
        assertFalse("零信息的显示串不该进提示词", text.contains("[表情包]"))
    }

    // ---- 昵称回退（纯函数那侧只拿到算好的名字，真正的回退在这一跳） ----

    @Test
    fun `无用户资料时昵称回退「用户」`() {
        val msgs = build(
            history = listOf(quotedMessage(), quotingMessage()),
            refs = mapOf("q1" to QuotedMessageRef(timestampMillis = quotedAt, rawContent = "晚上七点老地方")),
            profile = null,
        )
        assertTrue("实际：${quotingTurnText(msgs)}", quotingTurnText(msgs).startsWith("【用户在回复你 "))
    }

    @Test
    fun `空昵称同样回退「用户」`() {
        val msgs = build(
            history = listOf(quotedMessage(), quotingMessage()),
            refs = mapOf("q1" to QuotedMessageRef(timestampMillis = quotedAt, rawContent = "晚上七点老地方")),
            profile = UserProfileEntity(nickname = ""),
        )
        assertTrue("实际：${quotingTurnText(msgs)}", quotingTurnText(msgs).startsWith("【用户在回复你 "))
    }

    // ---- 回归守卫：不带引用的消息一个字都不许变（B1） ----

    @Test
    fun `没有引用的用户消息不注入任何旁注`() {
        val plain = MessageEntity(
            messageUUID = "u2",
            conversationUuid = "conv1",
            roleRaw = "user",
            content = "在干嘛",
            timestamp = at(2026, 8, 20, 20, 55),
        )
        val msgs = build(history = listOf(plain), refs = emptyMap())
        assertTrue("实际：${quotingTurnText(msgs)}", quotingTurnText(msgs) == "在干嘛")
    }

    @Test
    fun `quotedContent 为空串时不注入旁注`() {
        // 现有 isNullOrEmpty 守卫原样保留：有 uuid 没正文（老数据）也不产旁注。
        val msgs = build(
            history = listOf(quotedMessage(), quotingMessage(snapshot = "")),
            refs = mapOf("q1" to QuotedMessageRef(timestampMillis = quotedAt, rawContent = "晚上七点老地方")),
        )
        assertTrue("实际：${quotingTurnText(msgs)}", quotingTurnText(msgs) == "好")
    }
    @Test
    fun `引用一张老红包卡时_报文里只有脱敏快照绝无金额`() {
        // 复核 R1 🔴 的端到端钉：收紧之前右滑能引用红包卡，库里存得下这样的老行。预取层已改为
        // 「非纯文字目标不端原文」，这里证它一路传到报文——落库快照上场、amount 一个数字都不许出现。
        val card = MessageEntity(
            messageUUID = "q1",
            conversationUuid = "conv1",
            roleRaw = "user",
            content = """{"type":"red_packet","recordUUID":"9f2c8a41","amount":88,"blessingText":"新年快乐"}""",
            timestamp = quotedAt,
            messageKindRaw = com.situ.aichat.data.model.MessageKind.RED_PACKET.raw,
        )
        val msgs = build(
            history = listOf(card, quotingMessage(snapshot = "🧧 红包")),
            // = MessageRepository.quotedRefs 对卡片目标的产出：时间戳照给、原文不给。
            refs = mapOf("q1" to QuotedMessageRef(timestampMillis = quotedAt, rawContent = null)),
        )
        val text = quotingTurnText(msgs)

        assertTrue("应回退落库脱敏快照并照给时间锚，实际：$text",
            text.startsWith("【司徒在回复你 8月17日 周一 09:12 说的这句：「🧧 红包」】\n好"))
        val whole = msgs.joinToString("\n") { it.content.orEmpty() }
        assertFalse("红包金额绝不许进提示词", whole.contains("88"))
        assertFalse("原始 JSON 绝不许进提示词", whole.contains("red_packet"))
    }

}
