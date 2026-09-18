package com.situ.aichat.prompt.notification

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.GiftSender
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.data.model.RedPacketJson
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.notification.ConversationPhase
import com.situ.aichat.notification.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProactiveMessageComposer 单测（主动通知真实感改造 T2-7）。
 *
 * 锁定文本一律在测试里**重新打字**为字面量（不引用实现常量），断言从图纸 §3.6 独立反推。
 */
class ProactiveMessageComposerTest {

    private fun character(
        name: String = "林深",
        gender: String = "女",
        occupation: String = "插画师",
        personality: String = "温和细腻",
        speakingStyle: String = "短句，偶尔调皮",
    ) = CharacterEntity(
        uuid = "c-1",
        name = name,
        creationDate = 0L,
        gender = gender,
        occupation = occupation,
        personalityDescription = personality,
        speakingStyle = speakingStyle,
    )

    private fun state(
        phase: ConversationPhase = ConversationPhase.NORMAL,
        minutesSinceLastMessage: Long? = 60L * 50,
        unanswered: Int = 0,
        days: Int? = 2,
    ) = ConversationState(
        phase = phase,
        minutesSinceLastMessage = minutesSinceLastMessage,
        lastMessageFromUser = false,
        unansweredProactiveCount = unanswered,
        daysSinceLastUserMessage = days,
        latestMessageUuid = "m-1",
    )

    // MARK: - system prompt（角色信息 + 规则五条）

    @Test fun systemPrompt_containsIdentityLines_onlyForNonEmptyFields() {
        val prompt = ProactiveMessageComposer.composeSystemPrompt(
            character(), "阿远", "恋人", StructuredMemory(nicknameFromChar = "小远", nicknameToChar = "深深"),
        )
        assertTrue(prompt.startsWith("你是林深。你现在想给阿远发一条手机消息。"))
        assertTrue(prompt.contains("- 性别：女"))
        assertTrue(prompt.contains("- 身份：插画师"))
        assertTrue(prompt.contains("- 性格：温和细腻"))
        assertTrue(prompt.contains("- 说话风格：短句，偶尔调皮"))
        assertTrue(prompt.contains("- 你和阿远的关系：恋人"))
        assertTrue(prompt.contains("- 你叫TA：小远"))
        assertTrue(prompt.contains("- TA叫你：深深"))
    }

    @Test fun systemPrompt_emptyFields_produceNoLine() {
        val prompt = ProactiveMessageComposer.composeSystemPrompt(
            character(gender = "", occupation = "", personality = "", speakingStyle = ""),
            "阿远", null, StructuredMemory(),
        )
        assertFalse(prompt.contains("- 性别"))
        assertFalse(prompt.contains("- 身份"))
        assertFalse(prompt.contains("- 性格"))
        assertFalse(prompt.contains("- 说话风格"))
        assertFalse(prompt.contains("的关系："))
        assertFalse(prompt.contains("- 你叫TA"))
        assertFalse(prompt.contains("- TA叫你"))
    }

    /** 规则五条逐字（§9 锁定）。 */
    @Test fun rules_areExactlyTheFiveLockedLines() {
        val rules = ProactiveMessageComposer.composeRules()
        assertEquals(
            listOf(
                "规则：",
                "1. 像发微信一样自然，绝对不能有系统通知的感觉",
                "2. 只写一条，10-40 个字",
                "3. 不要用 emoji，不用\"亲爱的\"",
                "4. 现在就是你发出这条消息的时刻，可以自然使用\"刚才\"\"现在\"等时间表达",
                "5. 只输出 JSON 对象：{\"message\":\"你要发的那条消息\"}",
            ).joinToString("\n"),
            rules,
        )
    }

    /**
     * ⑪ 拍板反转：旧规则 8「禁相对时间词」必须**不在**新 prompt 里。
     * 取旧规则的独有句精确否定（PITFALLS §1e 回退断言取独有特征；出图前已核实该句全库唯一出现于被删文件）。
     */
    @Test fun rules_doNotForbidRelativeTimeWordsAnymore() {
        val system = ProactiveMessageComposer.composeSystemPrompt(character(), "阿远", null, StructuredMemory())
        assertFalse(system.contains("不要使用相对时间词"))
        // 正向对照：新规则 4 明确**允许**相对时间表达
        assertTrue(system.contains("可以自然使用\"刚才\"\"现在\"等时间表达"))
    }

    // MARK: - 状态段（§3.6 逐字锁定模板）

    @Test fun stateLine_sameDay_usesHoursSinceLastMessage() {
        val line = ProactiveMessageComposer.composeStateLine(
            state(phase = ConversationPhase.SAME_DAY, minutesSinceLastMessage = 185L, days = 0),
        )
        // 185min / 60 = 3（整数除法）
        assertEquals("你们今天聊过天，距上次说话大约3小时。", line)
    }

    @Test fun stateLine_overnight_isFixedSentence() {
        val line = ProactiveMessageComposer.composeStateLine(
            state(phase = ConversationPhase.OVERNIGHT, days = 1),
        )
        assertEquals("你们昨天聊过天。", line)
    }

    @Test fun stateLine_normal_statesDayCount() {
        val line = ProactiveMessageComposer.composeStateLine(
            state(phase = ConversationPhase.NORMAL, days = 3),
        )
        assertEquals("你们已经3天没说话了。", line)
    }

    @Test fun stateLine_distant_addsRestraintInstruction() {
        val expected = "你们已经6天没说话了，对方一直没有回你，语气要克制、不要埋怨，轻轻地表达想念就好。"
        assertEquals(
            expected,
            ProactiveMessageComposer.composeStateLine(state(phase = ConversationPhase.DISTANT_EARLY, days = 6)),
        )
        assertEquals(
            "你们已经10天没说话了，对方一直没有回你，语气要克制、不要埋怨，轻轻地表达想念就好。",
            ProactiveMessageComposer.composeStateLine(state(phase = ConversationPhase.DISTANT_LATE, days = 10)),
        )
    }

    /**
     * R1 🟡-2：用户从未发过消息（daysSinceLastUserMessage == null）但角色有消息 → 天数短语整体替成「很久」。
     * 该态可达且不罕见（首装后角色先开口 / 用户从不回复），相位仍由「最后一条消息（任意方）」定。
     */
    @Test fun stateLine_nullDayCount_saysLongTime_notBrokenPhrase() {
        val line = ProactiveMessageComposer.composeStateLine(
            state(phase = ConversationPhase.DISTANT_LATE, days = null),
        )
        assertTrue("天数未知时说「很久」", line.contains("你们已经很久没说话了"))
        assertFalse("绝不产出病句「很久天」", line.contains("很久天"))
    }

    /** LONG_ABSENCE 且从未有过消息（minutesSinceLastMessage == null）→ 专属开场文案。 */
    @Test fun stateLine_neverChatted_usesOpeningGreetingSentence() {
        val line = ProactiveMessageComposer.composeStateLine(
            state(phase = ConversationPhase.LONG_ABSENCE, minutesSinceLastMessage = null, days = null),
        )
        assertEquals("你们还没怎么聊过天，这是你主动开启的问候。", line)
    }

    /** 连发计数=1 → 追加「有分寸」一行；=0 不追加；≥2 由 Pipeline 拦下不到此。 */
    @Test fun stateLine_oneUnanswered_appendsTactLine() {
        val tact = "你上一条消息对方还没回，这条要有分寸，别催促，可以体贴地表示\"不着急回\"。"
        val withOne = ProactiveMessageComposer.composeStateLine(
            state(phase = ConversationPhase.NORMAL, days = 2, unanswered = 1),
        )
        assertEquals("你们已经2天没说话了。\n$tact", withOne)

        val withZero = ProactiveMessageComposer.composeStateLine(
            state(phase = ConversationPhase.NORMAL, days = 2, unanswered = 0),
        )
        assertFalse(withZero.contains(tact))
    }

    // MARK: - user prompt（段序 + 空段跳过 + 由头段）

    @Test fun userPrompt_ordersSectionsAndSkipsEmptyOnes() {
        val prompt = ProactiveMessageComposer.composeUserPrompt(
            state = state(phase = ConversationPhase.NORMAL, days = 2),
            weatherInfo = "☀️晴，12~23°C",
            recentSnippet = "阿远：今天好累\n林深：早点休息",
            memory = StructuredMemory(insideJoke = "咖啡梗", comfortStyle = "先听完再说"),
            occasion = "TA 的日程：[18:00-19:00] 画稿收尾",
        )
        val stateIdx = prompt.indexOf("你们已经2天没说话了。")
        val weatherIdx = prompt.indexOf("今天天气：☀️晴，12~23°C")
        val snippetIdx = prompt.indexOf("最近聊过：\n阿远：今天好累")
        val memoryIdx = prompt.indexOf("你们的梗：咖啡梗")
        val occasionIdx = prompt.indexOf("你现在想说话的由头：TA 的日程：[18:00-19:00] 画稿收尾")
        // 五段俱在且按 §3.6 顺序
        assertTrue(stateIdx >= 0 && weatherIdx > stateIdx && snippetIdx > weatherIdx)
        assertTrue(memoryIdx > snippetIdx && occasionIdx > memoryIdx)
        // 由头段的收束指令逐字
        assertTrue(prompt.contains("围绕这个由头，结合上面的状态和最近聊过的内容，自然地说一句。"))
        assertTrue(prompt.contains("你的安慰方式：先听完再说"))
    }

    @Test fun userPrompt_noWeatherNoSnippetNoMemory_keepsStateAndOccasionOnly() {
        val prompt = ProactiveMessageComposer.composeUserPrompt(
            state = state(phase = ConversationPhase.OVERNIGHT, days = 1),
            weatherInfo = null,
            recentSnippet = null,
            memory = StructuredMemory(),
            occasion = "早安问候",
        )
        assertFalse(prompt.contains("今天天气"))
        // 反向断言取**独有句**：由头段的锁定指令自身含「最近聊过的内容」，裸 contains("最近聊过") 必误红
        // （PITFALLS §2.21 同款）——片段段的独有形态是带全角冒号的「最近聊过：」。
        assertFalse(prompt.contains("最近聊过："))
        assertEquals(
            "你们昨天聊过天。\n\n你现在想说话的由头：早安问候\n围绕这个由头，结合上面的状态和最近聊过的内容，自然地说一句。",
            prompt,
        )
    }

    // MARK: - 解析（json / 裸行 / 引号 / 60 字上限 / think 剥离）

    @Test fun parse_jsonObject_extractsMessage() {
        assertEquals("画完最后一笔，想起你说的那家面包店了", ProactiveMessageComposer.parseSingle("""{"message":"画完最后一笔，想起你说的那家面包店了"}"""))
    }

    @Test fun parse_jsonWithUnknownKeysAndWhitespace_stillWorks() {
        val raw = """  {"message":"今天收工早","mood":"轻松"}  """
        assertEquals("今天收工早", ProactiveMessageComposer.parseSingle(raw))
    }

    @Test fun parse_bareLine_fallsBackToFirstNonBlankLine() {
        assertEquals("刚忙完，想跟你说句话", ProactiveMessageComposer.parseSingle("\n\n刚忙完，想跟你说句话\n第二行不要"))
    }

    @Test fun parse_stripsThinkTagsBeforeParsing() {
        val raw = "<think>先想想语气</think>{\"message\":\"外面下雨了，你带伞没\"}"
        assertEquals("外面下雨了，你带伞没", ProactiveMessageComposer.parseSingle(raw))
    }

    // 2026-09-18 五处小修 #5：模型给 JSON 套代码围栏时，修前解码失败 → 兜底取首个非空行 = 「```json」，
    // 这 7 个字符会过 60 字上限原样当通知正文发出去。

    @Test fun parse_jsonFencedWithLanguageTag_extractsMessage() {
        val raw = "```json\n{\"message\":\"外面下雨了，你带伞没\"}\n```"
        assertEquals("外面下雨了，你带伞没", ProactiveMessageComposer.parseSingle(raw))
    }

    @Test fun parse_jsonInBareFence_extractsMessage() {
        assertEquals("刚到家，累瘫了", ProactiveMessageComposer.parseSingle("```\n{\"message\":\"刚到家，累瘫了\"}\n```"))
    }

    @Test fun parse_jsonAfterPreamble_extractsMessageNotPreamble() {
        assertEquals("晚安，早点睡", ProactiveMessageComposer.parseSingle("好的：\n{\"message\":\"晚安，早点睡\"}"))
    }

    /** 未闭合围栏 + 裸文本：JSONExtractor 取不出东西，兜底行也绝不能是围栏行本身。 */
    @Test fun parse_unclosedFenceWithBareText_skipsFenceLine() {
        assertEquals("刚忙完，想跟你说句话", ProactiveMessageComposer.parseSingle("```\n刚忙完，想跟你说句话"))
        assertEquals("刚忙完，想跟你说句话", ProactiveMessageComposer.parseSingle("```text\n刚忙完，想跟你说句话\n"))
    }

    /** 只有围栏、没有正文 → 无效（null 交调用方重试 / 走兜底链），而不是发出「```」。 */
    @Test fun parse_fenceOnly_isNull() {
        assertNull(ProactiveMessageComposer.parseSingle("```json\n```"))
    }

    @Test fun parse_blankOrEmpty_isNull() {
        assertNull(ProactiveMessageComposer.parseSingle(""))
        assertNull(ProactiveMessageComposer.parseSingle("   \n  "))
    }

    @Test fun clean_stripsSurroundingQuotes_bothStyles() {
        assertEquals("今天云很好看", ProactiveMessageComposer.cleanSingleResponse("\"今天云很好看\""))
        assertEquals("今天云很好看", ProactiveMessageComposer.cleanSingleResponse("“今天云很好看”"))
        assertEquals("他说\"好\"就走了", ProactiveMessageComposer.cleanSingleResponse("他说\"好\"就走了"))
    }

    /** 60 字上限（§9 锁定，旧实现为 50）：恰 60 有效、61 无效。 */
    @Test fun clean_lengthCap_isSixtyInclusive() {
        val exactly60 = "字".repeat(60)
        val sixtyOne = "字".repeat(61)
        assertEquals(exactly60, ProactiveMessageComposer.cleanSingleResponse(exactly60))
        assertNull(ProactiveMessageComposer.cleanSingleResponse(sixtyOne))
    }

    @Test fun clean_emptyAfterTrim_isNull() {
        assertNull(ProactiveMessageComposer.cleanSingleResponse("   "))
        assertNull(ProactiveMessageComposer.cleanSingleResponse("\"\""))
    }

    /** 超长正文经 parseSingle 也判无效（上限在末端统一把关）。 */
    @Test fun parse_overlongMessage_isNull() {
        val raw = """{"message":"${"字".repeat(61)}"}"""
        assertNull(ProactiveMessageComposer.parseSingle(raw))
    }

    // MARK: - formatRecentSnippet（C6a 自 DynamicNotificationContentServiceTest 迁入·断言原样保留）
    // 结构化卡走 messageLlmSafeText 脱敏 · money-path / 隐私红线

    private fun snippetMsg(content: String, kind: MessageKind, role: String, ts: Long): MessageEntity =
        MessageEntity(
            messageUUID = "m$ts", conversationUuid = "c", roleRaw = role, content = content,
            timestamp = ts, messageKindRaw = kind.raw,
        )

    @Test fun `snippet 红包卡绝不露金额或原始 JSON`() {
        val rp = snippetMsg(
            RedPacketJson.encode(RedPacketData(type = "red_packet", recordUUID = "rp1", amount = 520, blessingText = "生日快乐")),
            MessageKind.RED_PACKET, role = "user", ts = 1L,
        )
        val plain = snippetMsg("今天好开心", MessageKind.PLAIN_TEXT, role = "assistant", ts = 2L)
        val out = ProactiveMessageComposer.formatRecentSnippet(listOf(rp, plain), "小明", "小夏")!!
        assertFalse("永不露红包金额", out.contains("520"))
        assertFalse("不露原始 JSON", out.contains("{"))
        assertFalse("不露字段名", out.contains("recordUUID"))
        assertTrue("祝福语脱敏保留", out.contains("生日快乐"))
        assertTrue("普通消息原文保留", out.contains("小夏：今天好开心"))
    }

    @Test fun `snippet 礼物卡绝不露金币`() {
        val gift = snippetMsg(
            GiftCardJson.encode(
                GiftCardData(
                    type = "gift_card", giftItemId = "g1", giftRecordId = "rec1",
                    cost = 888, giftName = "钻石项链", isHandmade = false, senderType = GiftSender.USER,
                ),
            ),
            MessageKind.GIFT_CARD, role = "user", ts = 1L,
        )
        val out = ProactiveMessageComposer.formatRecentSnippet(listOf(gift), "小明", "小夏")!!
        assertFalse("不露金币数字", out.contains("888"))
        assertFalse("不露原始 JSON", out.contains("{"))
        assertTrue("脱敏礼物名保留", out.contains("钻石项链"))
    }

    @Test fun `snippet 通话与线下事件卡整条丢弃只留可读文本`() {
        val call = snippetMsg("""{"type":"call_record","summary":"foo"}""", MessageKind.CALL_RECORD_CARD, "assistant", 3L)
        val offlineEnd = snippetMsg("{}", MessageKind.OFFLINE_END_CARD, "assistant", 2L)
        val plain = snippetMsg("在干嘛", MessageKind.PLAIN_TEXT, "user", 1L)
        val out = ProactiveMessageComposer.formatRecentSnippet(listOf(call, offlineEnd, plain), "小明", "小夏")
        assertEquals("小明：在干嘛", out)
    }

    @Test fun `snippet 表情包标签替换为友好文本且角色前缀正确`() {
        val u = snippetMsg("看这个[sticker:abc]", MessageKind.PLAIN_TEXT, "user", 1L)
        val a = snippetMsg("哈哈", MessageKind.PLAIN_TEXT, "assistant", 2L)
        val out = ProactiveMessageComposer.formatRecentSnippet(listOf(u, a), "小明", "小夏")!!
        assertTrue("用户名前缀 + 表情包友好文本", out.contains("小明：看这个[表情包]"))
        assertTrue("角色名前缀", out.contains("小夏：哈哈"))
        assertFalse("不露原始 sticker 标签", out.contains("sticker:abc"))
    }

    @Test fun `snippet 空入参或全部丢弃返回 null`() {
        assertNull(ProactiveMessageComposer.formatRecentSnippet(emptyList(), "小明", "小夏"))
        val onlyCard = snippetMsg("{}", MessageKind.OFFLINE_MARKER_START, "assistant", 1L)
        assertNull(ProactiveMessageComposer.formatRecentSnippet(listOf(onlyCard), "小明", "小夏"))
    }
}
