package com.situ.aichat.gift

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentQueueState
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.data.model.ProactiveGiftContext
import com.situ.aichat.data.model.ProactiveGiftTrigger
import com.situ.aichat.data.model.ProactiveGiftTriggerType
import com.situ.aichat.gift.ProactiveGiftLLMService.Decision
import com.situ.aichat.gift.ProactiveGiftLLMService.DecisionAction
import com.situ.aichat.gift.ProactiveGiftLLMService.DecisionError
import com.situ.aichat.gift.ProactiveGiftLLMService.ParseOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动送礼 LLM 决策纯函数单测（断言反推 iOS `ProactiveGiftLLMServiceTests` + `...RedPacketTests`）。覆盖 parseAndValidate
 * 各输入（正常/缺字段/非法 id/message 含 gift_/红包白名单/金额越界/blessing 截 80）+ fallbackDecision/fallbackMessage +
 * buildPrompt（角色名/候选/retry/硬约束/红包说明）+ isRedPacketEligible。LLM 调用链（decide）不单测（同 AffinitySense，靠复核+真机）。
 */
class ProactiveGiftLLMServiceTest {

    private fun candidates(ids: List<String> = listOf("gift_oden", "gift_rose_single")): List<GiftItem> =
        ids.mapNotNull { GiftCatalog.find(it) }

    private fun trig(type: ProactiveGiftTriggerType) = ProactiveGiftTrigger(type, type.displayName, "test", 0L)

    private fun char(personality: String = "", speakingStyle: String = "") =
        CharacterEntity(uuid = "test-uuid", name = "小雨", creationDate = 0L, personalityDescription = personality, speakingStyle = speakingStyle)

    private fun ctx(type: ProactiveGiftTriggerType = ProactiveGiftTriggerType.MISSING_YOU): ProactiveGiftContext =
        ProactiveGiftContext(
            characterUUID = "test-char", characterName = "小雨", occupation = "程序员",
            candidateTriggers = listOf(trig(type)), daysSinceLastProactiveGift = 10,
            economicTier = null, monthlySalary = 10000, coinBalance = 5000,
            relationshipLabel = "朋友", recentMoodSummary = "green/yellow/green",
        )

    private fun success(o: ParseOutcome): Decision = (o as ParseOutcome.Success).decision

    // ── parseAndValidate · 正常路径 ───────────────────────────────────

    @Test fun parse_shouldSend_true_ok() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "giftId": "gift_oden", "message": "天冷,吃点热的", "reason": "关心"}""",
            candidates(),
        )
        val d = success(o)
        assertTrue(d.shouldSend)
        assertEquals("gift_oden", d.giftId)
        assertEquals("天冷,吃点热的", d.message)
        assertEquals("关心", d.reason)
        assertFalse(d.isFromFallback)
        assertEquals(DecisionAction.GIFT, d.action)
    }

    @Test fun parse_shouldSend_false_ok() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": false, "giftId": null, "message": null, "reason": "上周刚送过,今天不送"}""",
            emptyList(),
        )
        val d = success(o)
        assertFalse(d.shouldSend)
        assertNull(d.giftId)
        assertNull(d.message)
    }

    @Test fun parse_markdown_fenced_json() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            "```json\n{\"shouldSend\": true, \"giftId\": \"gift_oden\", \"message\": \"小东西给你\", \"reason\": \"想你\"}\n```",
            candidates(),
        )
        assertTrue(o is ParseOutcome.Success)
    }

    // ── parseAndValidate · 错误路径 ───────────────────────────────────

    @Test fun parse_non_json_fails() {
        val o = ProactiveGiftLLMService.parseAndValidate("这不是 JSON", emptyList())
        assertTrue((o as ParseOutcome.Failure).error is DecisionError.NotValidJSON)
    }

    @Test fun parse_missing_shouldSend_fails() {
        val o = ProactiveGiftLLMService.parseAndValidate("""{"reason": "没说要不要送"}""", emptyList())
        val e = (o as ParseOutcome.Failure).error
        assertTrue(e is DecisionError.MissingField)
        assertTrue(e.description.contains("shouldSend"))
    }

    @Test fun parse_missing_reason_fails() {
        val o = ProactiveGiftLLMService.parseAndValidate("""{"shouldSend": false}""", emptyList())
        val e = (o as ParseOutcome.Failure).error
        assertTrue(e is DecisionError.MissingField)
        assertTrue(e.description.contains("reason"))
    }

    @Test fun parse_shouldSend_true_missing_giftId_fails() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "giftId": null, "message": "哟", "reason": "想送"}""", candidates(),
        )
        assertTrue((o as ParseOutcome.Failure).error is DecisionError.InvalidField)
    }

    @Test fun parse_giftId_not_in_candidates_fails() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "giftId": "gift_nonexistent", "message": "这是啥", "reason": "乱编"}""",
            candidates(listOf("gift_oden")),
        )
        val e = (o as ParseOutcome.Failure).error
        assertTrue(e is DecisionError.InvalidField)
        assertTrue(e.description.contains("不在候选列表"))
    }

    @Test fun parse_shouldSend_true_empty_message_fails() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "giftId": "gift_oden", "message": "", "reason": "送"}""", candidates(),
        )
        assertTrue((o as ParseOutcome.Failure).error is DecisionError.InvalidField)
    }

    @Test fun parse_message_contains_gift_prefix_fails() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "giftId": "gift_oden", "message": "送你 gift_oden 吃", "reason": "送"}""", candidates(),
        )
        val e = (o as ParseOutcome.Failure).error
        assertTrue(e is DecisionError.InvalidField)
        assertTrue(e.description.contains("gift_"))
    }

    @Test fun parse_invalid_action_value_fails() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "action": "cash_transfer", "reason": "test"}""", emptyList(), ProactiveGiftTriggerType.FESTIVAL,
        )
        assertTrue(o is ParseOutcome.Failure)
    }

    // ── fallbackDecision ──────────────────────────────────────────────

    @Test fun fallback_non_empty_picks_one() {
        val d = ProactiveGiftLLMService.fallbackDecision(trig(ProactiveGiftTriggerType.MISSING_YOU), candidates(listOf("gift_oden")))
        assertTrue(d.shouldSend)
        assertEquals("gift_oden", d.giftId)
        assertTrue(d.message!!.contains("关东煮"))
        assertTrue(d.isFromFallback)
    }

    @Test fun fallback_empty_picks_from_cheap_global_pool() {
        val d = ProactiveGiftLLMService.fallbackDecision(trig(ProactiveGiftTriggerType.MISSING_YOU), emptyList())
        assertTrue(d.shouldSend)
        assertTrue(d.giftId != null)
        val item = GiftCatalog.find(d.giftId!!)!!
        assertTrue(item.price <= 30)
        assertFalse(item.isHandmade)
        assertTrue(d.isFromFallback)
    }

    // ── fallbackMessage ───────────────────────────────────────────────

    @Test fun fallback_message_each_type_contains_gift_name_no_tech_id() {
        for (type in ProactiveGiftTriggerType.entries) {
            val msg = ProactiveGiftLLMService.fallbackMessage(type, "关东煮")
            assertTrue(msg.isNotEmpty())
            assertTrue("$type 文案应含礼物名字", msg.contains("关东煮"))
            assertFalse("$type 文案不应含技术 id", msg.contains("gift_"))
        }
    }

    @Test fun fallback_message_birthday_contains_birthday_word() {
        assertTrue(ProactiveGiftLLMService.fallbackMessage(ProactiveGiftTriggerType.BIRTHDAY, "玫瑰").contains("生日"))
    }

    // ── buildPrompt ───────────────────────────────────────────────────

    @Test fun prompt_contains_character_and_trigger() {
        val trigger = ProactiveGiftTrigger(ProactiveGiftTriggerType.FESTIVAL, "情人节", "valentines_day", 0L)
        val context = ctx(ProactiveGiftTriggerType.FESTIVAL).copy(candidateTriggers = listOf(trigger))
        val (sys, usr) = ProactiveGiftLLMService.buildPrompt(context, trigger, candidates(), char("温柔细腻", "爱用句末语气词"))
        assertTrue(sys.contains("小雨"))
        assertTrue(sys.contains("温柔细腻"))
        assertTrue(sys.contains("爱用句末语气词"))
        assertTrue(usr.contains("情人节"))
        assertTrue(usr.contains("gift_oden"))
    }

    @Test fun prompt_contains_candidate_ids_and_metadata() {
        val (_, usr) = ProactiveGiftLLMService.buildPrompt(
            ctx(), trig(ProactiveGiftTriggerType.MISSING_YOU), candidates(listOf("gift_oden", "gift_rose_single")), char(),
        )
        assertTrue(usr.contains("gift_oden"))
        assertTrue(usr.contains("gift_rose_single"))
        assertTrue(usr.contains("关东煮"))
        assertTrue(usr.contains("单枝玫瑰"))
    }

    @Test fun prompt_empty_candidates_hint() {
        val (_, usr) = ProactiveGiftLLMService.buildPrompt(ctx(), trig(ProactiveGiftTriggerType.MISSING_YOU), emptyList(), char())
        assertTrue(usr.contains("候选为空"))
    }

    // ── 卷四 T2-6 ⑤（图纸 §4.5 / §2.2 / K-20）：意图块在「最近心情摘要」之后；用 context.userName、空回退「用户」；无意图不含 ──

    @Test fun prompt_intentBlock_afterMoodLine_usesContextUserName_fallsBack_absentWithoutIntent() {
        val now = System.currentTimeMillis()
        val queue = GrowthJson.encode(
            IntentQueueState(
                intents = listOf(CharacterIntent(id = "i", kind = IntentKind.WANT_APOLOGIZE, state = IntentState.ACTIVE, strength = 50, bornAt = now, lastChangeAt = now)),
            ),
        )
        val withIntent = char().copy(intentQueueJSON = queue)
        val trigger = trig(ProactiveGiftTriggerType.MISSING_YOU)
        val (_, named) = ProactiveGiftLLMService.buildPrompt(ctx().copy(userName = "小明"), trigger, candidates(), withIntent, nowMillis = now)
        assertTrue(
            named,
            named.contains(
                "最近心情摘要:green/yellow/green\n你心里挂着的事：你想跟小明道个歉，话到嘴边又咽了回去。\n（这件事可以影响你今天送不送、送什么、说什么。）\n\n## 候选礼物",
            ),
        )
        val (_, fallback) = ProactiveGiftLLMService.buildPrompt(ctx(), trigger, candidates(), withIntent, nowMillis = now)
        assertTrue(fallback.contains("你心里挂着的事：你想跟用户道个歉，话到嘴边又咽了回去。"))
        val (_, none) = ProactiveGiftLLMService.buildPrompt(ctx().copy(userName = "小明"), trigger, candidates(), char(), nowMillis = now)
        assertFalse(none.contains("你心里挂着的事"))
        assertTrue(none.contains("最近心情摘要:green/yellow/green\n\n## 候选礼物"))
    }

    @Test fun prompt_previous_error_appended() {
        val (sys, _) = ProactiveGiftLLMService.buildPrompt(
            ctx(), trig(ProactiveGiftTriggerType.MISSING_YOU), candidates(), char(), previousError = "上次 giftId 为空",
        )
        assertTrue(sys.contains("上次尝试出错"))
        assertTrue(sys.contains("上次 giftId 为空"))
    }

    @Test fun prompt_hard_constraints() {
        val (sys, _) = ProactiveGiftLLMService.buildPrompt(ctx(), trig(ProactiveGiftTriggerType.MISSING_YOU), candidates(), char())
        assertTrue(sys.contains("giftId 必须严格等于候选列表中某一个 id"))
        assertTrue(sys.contains("不要出现 gift_"))
    }

    // ── 红包白名单 + 决策 ─────────────────────────────────────────────

    @Test fun red_packet_eligibility() {
        assertTrue(ProactiveGiftLLMService.isRedPacketEligible(ProactiveGiftTriggerType.BIRTHDAY))
        assertTrue(ProactiveGiftLLMService.isRedPacketEligible(ProactiveGiftTriggerType.ANNIVERSARY))
        assertTrue(ProactiveGiftLLMService.isRedPacketEligible(ProactiveGiftTriggerType.FESTIVAL))
        assertFalse(ProactiveGiftLLMService.isRedPacketEligible(ProactiveGiftTriggerType.SENSE_LOW_MOOD))
        assertFalse(ProactiveGiftLLMService.isRedPacketEligible(ProactiveGiftTriggerType.MISSING_YOU))
    }

    @Test fun prompt_whitelist_trigger_has_red_packet_option() {
        val trigger = ProactiveGiftTrigger(ProactiveGiftTriggerType.FESTIVAL, "情人节", "valentines_day", 0L)
        val (sys, _) = ProactiveGiftLLMService.buildPrompt(ctx(ProactiveGiftTriggerType.FESTIVAL), trigger, emptyList(), char())
        assertTrue(sys.contains("红包"))
        assertTrue(sys.contains("red_packet"))
        assertTrue(sys.contains("redPacketAmount"))
    }

    @Test fun prompt_non_whitelist_trigger_no_red_packet_option() {
        val (sys, _) = ProactiveGiftLLMService.buildPrompt(
            ctx(ProactiveGiftTriggerType.SENSE_LOW_MOOD), trig(ProactiveGiftTriggerType.SENSE_LOW_MOOD), emptyList(), char(),
        )
        assertFalse(sys.contains("red_packet"))
        assertFalse(sys.contains("redPacketAmount"))
        assertTrue(sys.contains("不允许选择红包") || sys.contains("\"gift\""))
    }

    @Test fun parse_omit_action_defaults_gift() {
        val candidate = GiftCatalog.allItems.first()
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "giftId": "${candidate.id}", "message": "送给你", "reason": "test"}""",
            listOf(candidate), ProactiveGiftTriggerType.FESTIVAL,
        )
        val d = success(o)
        assertEquals(DecisionAction.GIFT, d.action)
        assertEquals(candidate.id, d.giftId)
    }

    @Test fun parse_red_packet_success() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "action": "red_packet", "redPacketAmount": 520, "redPacketBlessing": "我爱你", "reason": "情人节"}""",
            emptyList(), ProactiveGiftTriggerType.FESTIVAL,
        )
        val d = success(o)
        assertEquals(DecisionAction.RED_PACKET, d.action)
        assertEquals(520, d.redPacketAmount)
        assertEquals("我爱你", d.redPacketBlessing)
        assertNull(d.giftId)
        assertNull(d.message)
    }

    @Test fun parse_red_packet_missing_amount_fails() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "action": "red_packet", "reason": "test"}""", emptyList(), ProactiveGiftTriggerType.FESTIVAL,
        )
        assertTrue(o is ParseOutcome.Failure)
    }

    @Test fun parse_red_packet_amount_out_of_range_fails() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "action": "red_packet", "redPacketAmount": 99999, "reason": "test"}""",
            emptyList(), ProactiveGiftTriggerType.FESTIVAL,
        )
        assertTrue(o is ParseOutcome.Failure)
    }

    @Test fun parse_red_packet_blessing_with_gift_prefix_fails() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "action": "red_packet", "redPacketAmount": 88, "redPacketBlessing": "买了 gift_rose 给你", "reason": "test"}""",
            emptyList(), ProactiveGiftTriggerType.FESTIVAL,
        )
        assertTrue(o is ParseOutcome.Failure)
    }

    @Test fun parse_red_packet_blessing_over_80_truncates() {
        val long = "祝".repeat(200)
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "action": "red_packet", "redPacketAmount": 88, "redPacketBlessing": "$long", "reason": "test"}""",
            emptyList(), ProactiveGiftTriggerType.FESTIVAL,
        )
        assertEquals(80, success(o).redPacketBlessing?.length)
    }

    @Test fun parse_red_packet_non_whitelist_trigger_fails() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "action": "red_packet", "redPacketAmount": 88, "reason": "test"}""",
            emptyList(), ProactiveGiftTriggerType.SENSE_LOW_MOOD,
        )
        assertTrue(o is ParseOutcome.Failure)
    }

    @Test fun parse_red_packet_null_trigger_skips_whitelist() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": true, "action": "red_packet", "redPacketAmount": 168, "reason": "test"}""",
            emptyList(), null,
        )
        val d = success(o)
        assertEquals(DecisionAction.RED_PACKET, d.action)
        assertEquals(168, d.redPacketAmount)
    }

    @Test fun parse_shouldSend_false_skips_action_validation() {
        val o = ProactiveGiftLLMService.parseAndValidate(
            """{"shouldSend": false, "reason": "不送"}""", emptyList(), ProactiveGiftTriggerType.FESTIVAL,
        )
        assertFalse(success(o).shouldSend)
    }

    @Test fun decision_default_action_is_gift() {
        val d = Decision(shouldSend = true, giftId = "gift_rose_single", message = "送你", reason = "test", isFromFallback = false)
        assertEquals(DecisionAction.GIFT, d.action)
        assertNull(d.redPacketAmount)
        assertNull(d.redPacketBlessing)
    }
}
