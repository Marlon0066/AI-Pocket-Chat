package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MemoryService 消化素材接线 + 提取提示词哨兵（记忆改造一期·图纸 §3.6/§3.7 / T2-10）。
 * 断言从图纸独立反推：extraMaterial 拼进 {{聊天记录}}（空/非空两态）；DEFAULT_EXTRACTION_PROMPT 含规则 6 / 规则 7（写入时换算·2026-09-06）且仍含两标题；
 * generateMemorySummary 返回值剥净内联 <think>（非流式 completion 不剥标签，落库前必须在此收口）。
 * Robolectric：MemoryService 内部若干安卓依赖（formatTimestamp 自 2026-09-01 件⑤起改 Locale.ROOT 纯 JVM，不再是保留 Robolectric 的理由）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryServiceTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val conversationDao = mockk<ConversationDao>(relaxed = true)
    private val contextLog = mockk<ContextLogService>(relaxed = true)
    private val service = MemoryService(messageDao, conversationDao, contextLog)
    private val config = mockk<ApiConfigValues>(relaxed = true)

    private fun msg(content: String) = MessageEntity(
        messageUUID = "m1", conversationUuid = "conv1", roleRaw = "user", content = content,
        timestamp = 1_700_000_000_000L, messageKindRaw = "plain_text",
    )

    private fun assistantMsg(content: String) = MessageEntity(
        messageUUID = "a1", conversationUuid = "conv1", roleRaw = "assistant", content = content,
        timestamp = 1_700_000_001_000L, messageKindRaw = "plain_text",
    )

    /** 跑一次 generateMemorySummary（指定角色名/用户名）并捕获发给 LLM 的 system 提示词。 */
    private fun capturedSystemPromptWithNames(characterName: String, userName: String): String = runBlocking {
        val slot = slot<List<ChatMessageDto>>()
        coEvery {
            contextLog.completion(any(), any(), any(), capture(slot), any(), any(), any(), any(), any())
        } returns "结果记忆"
        service.generateMemorySummary(
            existingMemory = "",
            newMessages = listOf(msg("周末去看画展吗"), assistantMsg("好呀，周日下午一起")),
            config = config,
            maxLength = 3000,
            characterName = characterName,
            userName = userName,
        )
        slot.captured.first { it.role == "system" }.content.orEmpty()
    }

    /** 跑一次 generateMemorySummary 并捕获发给 LLM 的 system 提示词。 */
    private fun capturedSystemPrompt(extraMaterial: String): String = runBlocking {
        val slot = slot<List<ChatMessageDto>>()
        coEvery {
            // 记忆护栏 G2：completion 新增 onFinishReason 尾参 → 第 10 位补 any()（PITFALLS 1e：默认参必须显式补位）。
            contextLog.completion(any(), any(), any(), capture(slot), any(), any(), any(), any(), any())
        } returns "结果记忆"
        service.generateMemorySummary(
            existingMemory = "",
            newMessages = listOf(msg("周末去看画展吗")),
            config = config,
            maxLength = 3000,
            extraMaterial = extraMaterial,
        )
        slot.captured.first { it.role == "system" }.content.orEmpty()
    }

    @Test fun extraMaterial_nonBlank_appendedIntoChatMacro() {
        val prompt = capturedSystemPrompt("[以下为同期的非聊天素材] 你发了动态：“今天真好”")
        assertTrue("聊天内容进模板", prompt.contains("周末去看画展吗"))
        assertTrue("素材拼进 {{聊天记录}}", prompt.contains("你发了动态：“今天真好”"))
    }

    @Test fun extraMaterial_blank_notAppended() {
        val prompt = capturedSystemPrompt("")
        assertTrue("聊天内容进模板", prompt.contains("周末去看画展吗"))
        assertFalse("空素材不追加素材头行", prompt.contains("以下为同期的非聊天素材"))
    }

    /**
     * 非流式 completion 不剥内联 <think>（只有流式路径经 ThinkTagParser）——
     * generateMemorySummary 必须返回剥净思考标签的文本，否则思考文本固化进 memorySummary
     * 污染注入并虚增 cjkLength 误触发超长护栏。
     */
    @Test fun generateMemorySummary_stripsInlineThinkTags_beforeReturn() = runBlocking {
        coEvery {
            // 合并补位：completion 第 10 位 onFinishReason（PITFALLS 1e：默认参必须显式 any() 补齐）。
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "<think>用户提到画展，应记录爱好。</think>\n【长期事实】\n- 用户喜欢看画展"
        val result = service.generateMemorySummary(
            existingMemory = "",
            newMessages = listOf(msg("周末去看画展吗")),
            config = config,
            maxLength = 3000,
        )
        assertFalse("思考标签不得进入记忆", result.contains("<think>"))
        assertFalse("思考正文不得进入记忆", result.contains("应记录爱好"))
        assertTrue("正文保留", result.contains("用户喜欢看画展"))
        assertTrue("剥净后已 trim（与协调方再 trim 兼容）", result == result.trim())
    }

    /** 剥标签后为空 = 空响应：重试后仍空须返回 ""（协调方抛 EmptyResponse 进短冷却），不得把纯思考文本当结果。 */
    @Test fun generateMemorySummary_thinkOnlyResponse_treatedAsEmpty() = runBlocking {
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "<think>只有思考没有正文</think>"
        val result = service.generateMemorySummary(
            existingMemory = "旧记忆",
            newMessages = listOf(msg("随便聊聊")),
            config = config,
            maxLength = 3000,
        )
        assertTrue("纯思考响应视同空响应", result.isEmpty())
    }

    @Test fun defaultExtractionPrompt_hasRule6_andKeepsBothHeaders() {
        val p = MemoryService.DEFAULT_EXTRACTION_PROMPT
        assertTrue("含规则 6（约定归系统清单）", p.contains("6. 约定的处理：进行中的约定"))
        assertTrue("进行中约定不写进长期事实", p.contains("由系统的约定清单单独管理，不要写进「长期事实」"))
        assertTrue("仍含长期事实标题", p.contains("【长期事实】"))
        assertTrue("仍含近期经历标题", p.contains("【近期经历】"))
    }

    @Test fun defaultExtractionPrompt_hasRule7_relativeTimeNormalizedAtWrite() {
        // 写入时换算（2026-09-06 用户拍板）：素材行首 [时间] 是发生时间；消息里的相对时间词落记忆时须换成具体日期，
        // 且合并旧记忆时把未换算的也顺手改掉（与规则 2 的旧代称换血同一手法）。不得引入「当前时间」宏（MemoryCompressionModeTest 钉死）。
        val p = MemoryService.DEFAULT_EXTRACTION_PROMPT
        assertTrue("含规则 7（写入时换算）", p.contains("7. 每条消息前方括号里的时间，就是那句话说出来的时间"))
        assertTrue("相对时间词一律换成具体日期", p.contains("写进记忆时一律按那条消息的时间换算成具体日期"))
        assertTrue("规则 2 合并时顺手换算旧记忆", p.contains("也一并按该条开头的 [日期] 换算成具体日期"))
        assertTrue("规则 7 给前→后对照例而非孤立事实句", p.contains("原话「明天要去面试」，就写成「[2026-09-03] {{user}}说 9 月 4 日要去面试」"))
        assertTrue("「刚才」类不硬换日期", p.contains("「刚才」「等会儿」这类说法直接去掉或改成那天的时段"))
        assertFalse("不得引入「当前时间」", p.contains("当前时间"))
        assertTrue("输出格式段零碰（强耦合）", p.contains("每条一行，必须带日期标记，格式为「[YYYY-MM-DD] 内容」"))
    }

    // ── 第三人称指名（2026-07-14·图纸 §7 T-a/T-b/T-c）：断言从 D-3/D-4/E1/E3/E7 规格独立反推 ──

    /**
     * T-a：有真实名字时——① 对话记录说话人渲染成真名（D-3），绝不再出现旧「用户：/角色：」标签；
     * ② 默认模板的 {{char}}/{{user}} 宏被替换为真名。
     */
    @Test fun generateMemorySummary_withNames_conversationAndMacrosUseRealNames() {
        val prompt = capturedSystemPromptWithNames(characterName = "夏晴子", userName = "小明")
        assertTrue("用户说话人用真名", prompt.contains("小明："))
        assertTrue("角色说话人用真名", prompt.contains("夏晴子："))
        assertFalse("对话记录不得再出现旧「用户：」标签", prompt.contains("用户："))
        assertFalse("对话记录不得再出现旧「角色：」标签", prompt.contains("角色："))
        assertTrue("模板宏替换为真名", prompt.contains("夏晴子和小明的对话记录"))
    }

    /** T-a 续 / E1：用户没设昵称（userName=""）→ safeUser=「用户」，对话记录标签为「用户：」= 与今天一致，不空白。 */
    @Test fun generateMemorySummary_noNickname_fallsBackToUserLabel() {
        val prompt = capturedSystemPromptWithNames(characterName = "夏晴子", userName = "")
        assertTrue("无昵称回退为「用户：」标签", prompt.contains("用户："))
        assertTrue("角色名仍生效", prompt.contains("夏晴子："))
    }

    /** T-b / E3：其余 6 消费者不传名字调用 formatMessages → 输出默认「用户：/角色：」标签，字节与今天一致。 */
    @Test fun formatMessages_withoutNameArgs_keepsDefaultLabels() {
        val out = MemoryService.formatMessages(listOf(msg("在吗"), assistantMsg("在的")))
        assertTrue("默认用户标签", out.contains("用户："))
        assertTrue("默认角色标签", out.contains("角色："))
        assertFalse("默认调用不得混入真实名字", out.contains("小明："))
    }

    /** T-c / E4/E7：默认模板保住两标题 + 日期格式（强耦合），接线名字宏与换血规则，清除旧「用户的稳定信息」通用代称。 */
    @Test fun defaultExtractionPrompt_thirdPersonNaming_keepsFormatCoupling() {
        val p = MemoryService.DEFAULT_EXTRACTION_PROMPT
        assertTrue("含长期事实标题（强耦合）", p.contains("【长期事实】"))
        assertTrue("含近期经历标题（强耦合）", p.contains("【近期经历】"))
        assertTrue("含日期格式（强耦合）", p.contains("[YYYY-MM-DD]"))
        assertTrue("接线 {{char}} 宏", p.contains("{{char}}"))
        assertTrue("接线 {{user}} 宏", p.contains("{{user}}"))
        assertTrue("含老记忆换血规则（D-5）", p.contains("改写成对应的名字"))
        assertFalse("清除旧「用户的稳定信息」通用代称（防回退）", p.contains("用户的稳定信息"))
    }

    // ── 截断防线（记忆护栏 G2·微图纸 §5 ⑥⑦）：finish_reason=="length" 的半份输出绝不过闸 ──

    /** 打桩：completion 恒回 [text] 并向 onFinishReason 回调 [finishReason]（参数位 8 = onFinishReason·summarize 已退役左移一位）。 */
    private fun stubCompletion(text: String, finishReason: String?) {
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            arg<((String?) -> Unit)?>(8)?.invoke(finishReason)
            text
        }
    }

    @Test fun generate_truncatedTwice_returnsEmpty_neverHalfMemory() = runBlocking {
        stubCompletion("【长期事实】\n- 半份记忆被掐断", finishReason = "length")
        val result = service.generateMemorySummary(
            existingMemory = "旧记忆",
            newMessages = listOf(msg("你好")),
            config = config,
            maxLength = 3000,
        )
        assertTrue("重试后仍截断必须返空（进短冷却），绝不返半份记忆", result.isEmpty())
        io.mockk.coVerify(exactly = 2) {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun generate_finishReasonStop_returnsContent() = runBlocking {
        stubCompletion("【长期事实】\n- 完整记忆", finishReason = "stop")
        val result = service.generateMemorySummary(
            existingMemory = "",
            newMessages = listOf(msg("你好")),
            config = config,
            maxLength = 3000,
        )
        assertTrue("正常结束原因不得误伤", result.contains("完整记忆"))
    }

    @Test fun compress_truncated_returnsEmpty() = runBlocking {
        stubCompletion("压到一半被掐", finishReason = "length")
        val result = service.compressMemory("很长的记忆", config, maxLength = 3000, characterName = "角色")
        assertTrue("压缩被截断必须返空（调用方垃圾闸不采纳）", result.isEmpty())
    }

    // ── 结构化卡名字三层穿透（人称指名·2026-07-15 T2 补测）───────────────────────────
    // 单测各层齐全，独缺"串起来"的端到端穿透：
    //   formatMessages(userLabel, charLabel)
    //     → messageLlmSafeText(userName, charName)
    //       → GiftCardData.llmRepresentation(characterName, userName) / buildRedPacketLLMRepresentation(userName)
    // 断言从"真实名字应贯穿三层到底"的规格独立反推（不照抄实现串）：
    //   ① 传真名 → 输出现真名、通用代称（用户/角色）消失；
    //   ② 不传名字（其余 6 个未迁移消费者的默认路径）→ 回退通用代称，字节与旧行为一致（保护）。
    // 脱敏铁律：礼物金币（cost，经 tier 脱敏）/ 红包金额（amount）只作输入，绝不写进任何断言。

    /**
     * 礼物卡消息。content = **手写**合法 GiftCardJson（独立于 encode 实现，纯作三层输入）：
     * senderType 决定送礼方向；cost 仅作输入，llmRepresentation 只吐分档文案（"小心意"）永不露金币数字。
     */
    private fun giftMsg(role: String, senderType: String?) = MessageEntity(
        messageUUID = "gift-$role-$senderType", conversationUuid = "conv1", roleRaw = role,
        content = buildString {
            append("""{"type":"gift_card","giftItemId":"g1","giftRecordId":"r1",""")
            append(""""cost":30,"giftName":"暖手宝","isHandmade":false""")
            if (senderType != null) append(""","senderType":"$senderType"""")
            append("}")
        },
        timestamp = 1_700_000_000_000L, messageKindRaw = "gift_card",
    )

    /** 传真名 + 角色送礼（senderType=character）→ 角色真名穿透三层，通用代称清零。 */
    @Test fun formatMessages_giftCard_characterDirection_realNameThreadsThrough() {
        val out = MemoryService.formatMessages(
            listOf(giftMsg(role = "assistant", senderType = "character")),
            userLabel = "小明", charLabel = "夏晴子",
        )
        assertTrue("角色送礼→角色真名穿透到底", out.contains("夏晴子送出礼物"))
        assertFalse("真名路径不得回退「用户」通用代称", out.contains("用户送出礼物"))
        assertFalse("真名路径不得回退「角色」通用代称", out.contains("角色送出礼物"))
    }

    /** 传真名 + 用户送礼（senderType=user）→ 用户真名穿透三层，通用代称清零。 */
    @Test fun formatMessages_giftCard_userDirection_realNameThreadsThrough() {
        val out = MemoryService.formatMessages(
            listOf(giftMsg(role = "user", senderType = "user")),
            userLabel = "小明", charLabel = "夏晴子",
        )
        assertTrue("用户送礼→用户真名穿透到底", out.contains("小明送出礼物"))
        assertFalse("真名路径不得回退「用户」通用代称", out.contains("用户送出礼物"))
        assertFalse("真名路径不得回退「角色」通用代称", out.contains("角色送出礼物"))
    }

    /** 传真名 + senderType 省略（老消息 null）→ llmRepresentation 按「用户送」兜底，且 userLabel 真名仍穿透。 */
    @Test fun formatMessages_giftCard_nullSender_fallsBackToUserDirectionWithRealName() {
        val out = MemoryService.formatMessages(
            listOf(giftMsg(role = "user", senderType = null)),
            userLabel = "小明", charLabel = "夏晴子",
        )
        assertTrue("老消息缺方向→兜底用户方向且真名仍穿透", out.contains("小明送出礼物"))
        assertFalse("兜底后不得残留「用户」通用代称", out.contains("用户送出礼物"))
    }

    /** 不传名字（其余 6 消费者默认路径）→ 礼物卡回退「角色/用户」通用代称，字节与旧行为一致（保护）。 */
    @Test fun formatMessages_giftCard_defaultLabels_keepGenericNouns() {
        val charOut = MemoryService.formatMessages(listOf(giftMsg(role = "assistant", senderType = "character")))
        assertTrue("默认路径·角色送礼回退「角色送出礼物」", charOut.contains("角色送出礼物"))
        assertFalse("默认路径不得凭空冒出真名", charOut.contains("夏晴子"))

        val userOut = MemoryService.formatMessages(listOf(giftMsg(role = "user", senderType = "user")))
        assertTrue("默认路径·用户送礼回退「用户送出礼物」", userOut.contains("用户送出礼物"))
        assertFalse("默认路径不得凭空冒出真名", userOut.contains("小明"))
    }

    /**
     * 红包系统事件（已领取·resolved）content = 手写合法 SystemEventData JSON。
     * senderRole 决定第一人称视角分支：user→「你收下了<用户名>的红包」；character→「<用户名>收下了你的红包」。
     * amount 仅作输入（resolved 后可暴露），绝不进断言。
     */
    private fun redPacketAcceptedMsg(senderRole: String = "user") = MessageEntity(
        messageUUID = "rp-$senderRole", conversationUuid = "conv1", roleRaw = "system",
        content = """{"type":"system_event","eventType":"red_packet_accepted","title":"红包已领取","emoji":"🧧","timestamp":"2023-11-14T00:00:00Z","amount":88,"senderRole":"$senderRole"}""",
        timestamp = 1_700_000_000_000L, messageKindRaw = "system_event_card",
    )

    /** 传真名 → 红包事件里的用户名穿透三层（buildRedPacketLLMRepresentation 的 userName 参）。 */
    @Test fun formatMessages_redPacketEvent_userNameThreadsThrough() {
        val out = MemoryService.formatMessages(
            listOf(redPacketAcceptedMsg()), userLabel = "小明", charLabel = "夏晴子",
        )
        assertTrue("红包事件·用户名穿透到底", out.contains("你收下了小明的红包"))
        assertFalse("真名路径不得回退「用户」通用代称", out.contains("你收下了用户的红包"))
    }

    /** 不传名字（默认路径）→ 红包事件回退「用户」通用代称，字节与旧行为一致（保护）。 */
    @Test fun formatMessages_redPacketEvent_defaultLabels_keepGenericNoun() {
        val out = MemoryService.formatMessages(listOf(redPacketAcceptedMsg()))
        assertTrue("默认路径·红包事件回退「你收下了用户的红包」", out.contains("你收下了用户的红包"))
        assertFalse("默认路径不得凭空冒出真名", out.contains("小明"))
    }

    /**
     * 传真名 + 角色发的红包（senderRole=character，用户收下）→ userName 穿透到 else 分支
     * 「<用户名>收下了你的红包」（补 senderIsUser=false 侧，与上面的 user 分支互补）。
     */
    @Test fun formatMessages_redPacketEvent_characterSender_userNameThreadsThrough() {
        val out = MemoryService.formatMessages(
            listOf(redPacketAcceptedMsg(senderRole = "character")),
            userLabel = "小明", charLabel = "夏晴子",
        )
        assertTrue("角色发红包·用户名穿透 else 分支", out.contains("小明收下了你的红包"))
        assertFalse("真名路径不得回退「用户」通用代称", out.contains("用户收下了你的红包"))
    }

    /** 不传名字（默认路径）+ 角色发的红包 → else 分支回退「用户」通用代称，字节与旧行为一致（保护）。 */
    @Test fun formatMessages_redPacketEvent_characterSender_defaultLabels_keepGenericNoun() {
        val out = MemoryService.formatMessages(listOf(redPacketAcceptedMsg(senderRole = "character")))
        assertTrue("默认路径·角色发红包回退「用户收下了你的红包」", out.contains("用户收下了你的红包"))
        assertFalse("默认路径不得凭空冒出真名", out.contains("小明"))
    }
}
