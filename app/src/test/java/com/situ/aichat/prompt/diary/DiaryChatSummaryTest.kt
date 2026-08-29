package com.situ.aichat.prompt.diary

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.GiftSender
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.data.model.RedPacketEventSenderRole
import com.situ.aichat.data.model.RedPacketJson
import com.situ.aichat.data.model.SystemEventJson
import com.situ.aichat.data.model.SystemEventType
import com.situ.aichat.data.model.makeRedPacketSystemEventData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 守 [DiaryGenerationService.summarizeChatMessages] 的脱敏契约（money-path / 隐私）：结构化卡片
 * （礼物 / 红包 / 通话）**绝不**把原始 JSON、amount/cost 数字喂进日记 LLM 提示词（LogSource.DIARY_GENERATION）。
 *
 * 对齐 PromptBuilder.appendConversationMessages 的 llmRepresentation 收口；金额/金币永不外露
 * （见 RedPacketData.amount「永不露此字段」/ GiftCardData「永不暴露原始 JSON/金币数字」字段契约）。
 */
class DiaryChatSummaryTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    /** 最小可用 strings：只有 [roleMe]/[roleOther]/[chatLine] 参与聊天素材；其余给空即可。 */
    private fun strings() = DiaryPromptStrings(
        intro = "", requirementsHeader = "", firstPerson = "", styleDefault = "",
        wordCount = "", emoji = "", events = "", chatMention = "", innerVoice = "", noAi = "", shortOk = "",
        personaHeader = "", personaCity = "",
        chatSummaryHeader = "", chatGroupHeader = "### 今天和 %1\$s 聊了", scheduleHeader = "", currentTime = "", outputOnly = "",
        moodHeader = "", moodOutputRule = "",
        userMessage = "",
        guideHeader = "", guideLead = "", guideEvent = "", guideFeeling = "", guideUnsaid = "",
        roleMe = "我", roleOther = "对方",
        chatLine = "[%1\$s] %2\$s：%3\$s",
        calendarLine = "%1\$s-%2\$s %3\$s",
        eventUntitled = "无标题", userFallback = "我",
        photosBlind = "photos<%1\$d>",
    )

    private fun msg(
        content: String,
        kind: MessageKind = MessageKind.PLAIN_TEXT,
        role: String = "user",
        ts: Long = 1_700_000_000_000L, // 2023-11-14T22:13:20Z → "11月14日 22:13"，不含任何被测金额数字
        conv: String = "c1",
    ) = MessageEntity(
        messageUUID = content.hashCode().toString() + ts,
        conversationUuid = conv,
        roleRaw = role,
        content = content,
        timestamp = ts,
        messageKindRaw = kind.raw,
    )

    private fun summarize(vararg messages: MessageEntity): String =
        DiaryGenerationService.summarizeChatMessages(messages.toList(), zone, strings())

    @Test fun `gift card never leaks raw cost number or JSON`() {
        val giftJson = GiftCardJson.encode(
            GiftCardData(
                type = "gift_card",
                giftItemId = "g1",
                giftRecordId = "rec-gift-1",
                cost = 888,
                giftName = "钻石项链",
                isHandmade = false,
                senderType = GiftSender.USER,
            ),
        )
        val out = summarize(msg(giftJson, MessageKind.GIFT_CARD, role = "user"))

        assertFalse("绝不露原始金币数字", out.contains("888"))
        assertFalse("绝不露原始 JSON", out.contains("{"))
        assertFalse("绝不露 JSON 字段名", out.contains("giftRecordId"))
        assertFalse("绝不露 cost 键名", out.contains("cost"))
        // 应以脱敏系统记录形式出现（保留日记价值）。
        assertTrue("应保留脱敏后的礼物名", out.contains("钻石项链"))
        assertTrue("应为系统记录脱敏文案", out.contains("用户送出礼物"))
        assertTrue("金额应被替换为心意分档", out.contains("分量="))
    }

    @Test fun `red packet never leaks amount number or JSON`() {
        val rpJson = RedPacketJson.encode(
            RedPacketData(
                type = "red_packet",
                recordUUID = "rec-rp-1",
                amount = 520,
                blessingText = "新年快乐",
            ),
        )
        val out = summarize(msg(rpJson, MessageKind.RED_PACKET, role = "user"))

        assertFalse("永不露红包金额", out.contains("520"))
        assertFalse("绝不露原始 JSON", out.contains("{"))
        assertFalse("绝不露 JSON 字段名", out.contains("recordUUID"))
        assertFalse("绝不露 amount 键名", out.contains("amount"))
        assertTrue("应为系统记录脱敏文案", out.contains("发出红包"))
        assertTrue("祝福语应保留", out.contains("新年快乐"))
    }

    @Test fun `call record card is skipped entirely`() {
        // 通话记录卡无 llmRepresentation 且无日记文本价值 → 整条不进素材，原文 JSON 永不外露。
        val callJson = """{"type":"call_record","durationSeconds":123,"summary":"foo"}"""
        val out = summarize(msg(callJson, MessageKind.CALL_RECORD_CARD, role = "assistant"))

        assertTrue("通话卡应被整条跳过 → 空素材", out.isEmpty())
        assertFalse(out.contains("{"))
        assertFalse(out.contains("call_record"))
    }

    @Test fun `plain text passes through unchanged`() {
        val out = summarize(msg("今天天气很好", MessageKind.PLAIN_TEXT, role = "user"))
        assertTrue(out.contains("今天天气很好"))
        assertTrue(out.contains("我："))
    }

    @Test fun `format chat groups — each character's lines carry that character's own name, user is 我`() {
        val s = strings()
        // 分组由 DAO 侧（每角色各自取）预先切好；纯函数只负责「贴小标题 + 收口标注」。组序由调用方保证。
        val out = DiaryGenerationService.formatChatGroups(
            listOf(
                "夏晴子" to listOf(
                    msg("你睡了吗？", role = "user", ts = 1L),
                    msg("被你吵醒了", role = "assistant", ts = 2L),
                ),
                "小满" to listOf(
                    msg("早呀", role = "user", ts = 3L),
                    msg("早，今天想去爬山", role = "assistant", ts = 4L),
                ),
            ),
            zone, s,
        )
        // 两个分组小标题，按调用方给定组序（夏晴子先·小满后）。
        assertTrue(out.contains("### 今天和 夏晴子 聊了"))
        assertTrue(out.contains("### 今天和 小满 聊了"))
        assertTrue("夏晴子组应在小满组之前", out.indexOf("夏晴子 聊了") < out.indexOf("小满 聊了"))
        // 各角色消息挂各自真名（多角色不串戏）；用户始终是「我」。
        assertTrue(out.contains("夏晴子：被你吵醒了"))
        assertTrue(out.contains("小满：早，今天想去爬山"))
        assertTrue(out.contains("我：你睡了吗？"))
        assertTrue(out.contains("我：早呀"))
        // 小满的话绝不会被挂到夏晴子名下（张冠李戴守卫）。
        assertFalse(out.contains("夏晴子：早，今天想去爬山"))
    }

    @Test fun `per-character take — budget splits evenly, floor keeps every character present`() {
        // 保险丝（2026-07-13 复核 🔵-3 用户拍板）：(600/n).coerceIn(20,150)——≤4 角色不生效、多角色均摊、地板绝不整组丢角色。
        assertEquals(150, DiaryGenerationService.perCharacterTake(1))
        assertEquals(150, DiaryGenerationService.perCharacterTake(4))    // 4×150=600 恰满预算，保险丝边界内
        assertEquals(120, DiaryGenerationService.perCharacterTake(5))
        assertEquals(100, DiaryGenerationService.perCharacterTake(6))
        assertEquals(20, DiaryGenerationService.perCharacterTake(30))    // 600/30=20 恰落地板
        assertEquals(20, DiaryGenerationService.perCharacterTake(100))   // 地板兜底：人人仍露脸
        assertEquals(150, DiaryGenerationService.perCharacterTake(0))    // 防御：非法 0 视作 1
    }

    @Test fun `format chat groups — group emptied by sanitization is dropped, all-empty yields blank`() {
        val s = strings()
        // 某组只剩通话卡（脱敏后整条丢弃）→ 该组不产出小标题；全空 → ""。
        val callJson = """{"type":"call_record","durationSeconds":60}"""
        val out = DiaryGenerationService.formatChatGroups(
            listOf("小满" to listOf(msg(callJson, MessageKind.CALL_RECORD_CARD, role = "assistant", ts = 1L))),
            zone, s,
        )
        assertEquals("", out)
    }

    @Test fun `explicit speaker labels — author is 我, other party named`() {
        // 2026-07-13 统一标注：日记作者本人恒标「我」，另一方标真名（防「指令第一人称 vs 素材点名」的 POV 漂移）。
        val userMsg = msg("那个，你睡了吗？", role = "user", ts = 1L)
        val aiMsg = msg("嗯……在睡觉啦，被你吵醒了", role = "assistant", ts = 2L)
        // 用户日记按角色分组：用户=「我」、该角色=真名（characterLabel 传角色名，多角色靠名字区分）。
        val userDiary = DiaryGenerationService.summarizeChatMessages(
            listOf(userMsg, aiMsg), zone, strings(),
            userLabel = "我", characterLabel = "夏晴子",
        )
        assertTrue(userDiary.lines()[0].contains("我：那个，你睡了吗？"))
        assertTrue(userDiary.lines()[1].contains("夏晴子：嗯……在睡觉啦"))
        // 交换日记（TA 执笔）：角色自己=「我」、用户=用户名。
        val letter = DiaryGenerationService.summarizeChatMessages(
            listOf(userMsg, aiMsg), zone, strings(),
            userLabel = "小明", characterLabel = "我",
        )
        assertTrue(letter.lines()[0].contains("小明：那个，你睡了吗？"))
        assertTrue(letter.lines()[1].contains("我：嗯……在睡觉啦"))
    }

    @Test fun `mixed thread sanitizes cards, keeps plain text, drops call record`() {
        val giftJson = GiftCardJson.encode(
            GiftCardData(
                type = "gift_card", giftItemId = "g1", giftRecordId = "rec-g", cost = 300,
                giftName = "手作贺卡", isHandmade = true, senderType = GiftSender.CHARACTER,
            ),
        )
        val rpJson = RedPacketJson.encode(
            RedPacketData(type = "red_packet", recordUUID = "rec-rp", amount = 1314, blessingText = "生日快乐"),
        )
        val callJson = """{"type":"call_record","durationSeconds":60}"""
        val out = summarize(
            msg("早安", MessageKind.PLAIN_TEXT, role = "user", ts = 1L),
            msg(giftJson, MessageKind.GIFT_CARD, role = "assistant", ts = 2L),
            msg(rpJson, MessageKind.RED_PACKET, role = "user", ts = 3L),
            msg(callJson, MessageKind.CALL_RECORD_CARD, role = "assistant", ts = 4L),
        )

        // 任何原始金额 / 金币 / JSON 都不得出现。
        assertFalse(out.contains("300"))
        assertFalse(out.contains("1314"))
        assertFalse(out.contains("{"))
        assertFalse(out.contains("call_record"))
        // 文本保留 + 卡片脱敏保留 + 通话卡丢弃。
        assertTrue(out.contains("早安"))
        assertTrue(out.contains("手作贺卡"))
        assertTrue(out.contains("生日快乐"))
        // 行数 = 3（早安 / 礼物 / 红包），通话卡被丢弃不占行。
        assertEquals(3, out.lines().size)
    }

    @Test fun `malformed gift JSON is dropped rather than leaked raw`() {
        // GIFT_CARD 但内容非合法礼物 JSON：解析失败 → null → 跳过；绝不把原文当普通文本喂出去。
        val out = summarize(msg("""{"type":"not_a_gift","cost":999}""", MessageKind.GIFT_CARD, role = "user"))
        assertTrue("解析失败的结构化卡应跳过", out.isEmpty())
        assertFalse(out.contains("999"))
        assertFalse(out.contains("{"))
    }

    @Test fun `opened red packet shows amount in diary - status driven user decision`() {
        // 用户拍板「已拆开的红包日记里带金额」：红包卡(信封)永不带金额,但已领取的"领取事件"resolved 后带精确金额→进日记。
        val eventJson = SystemEventJson.encode(
            makeRedPacketSystemEventData(
                eventType = SystemEventType.RED_PACKET_ACCEPTED, amount = 520,
                blessingText = "新年快乐", rejectionReason = null,
                senderRole = RedPacketEventSenderRole.USER, characterName = "小夏", timestampMillis = 1_700_000_000_000L,
            ),
        )
        val out = summarize(msg(eventJson, MessageKind.SYSTEM_EVENT_CARD, role = "system"))
        assertTrue("已拆红包→日记带金额(状态驱动)", out.contains("金额=520"))
        assertFalse("不露原始 JSON", out.contains("{"))
    }
}
