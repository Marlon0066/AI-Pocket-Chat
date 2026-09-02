package com.situ.aichat.gift

import android.util.Log
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.ProactiveGiftContext
import com.situ.aichat.data.model.ProactiveGiftTrigger
import com.situ.aichat.data.model.ProactiveGiftTriggerType
import com.situ.aichat.data.model.RedPacketAmountCatalog
import com.situ.aichat.data.model.intentQueue
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.prompt.IntentExitRenderer
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.JSONExtractor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 主动送礼 LLM 决策服务（1:1 iOS `Services/ProactiveGiftLLMService.swift`，4 层架构的决策层）。
 *
 * 让 LLM 以「角色第一人称」决定今天是否送、送什么、说什么，可选送礼物或发红包（仅 festival/birthday/anniversary
 * 允许红包）。核心：LLM 是决策者（app 只给 context）；严格校验（giftId 必须在候选内、message 不含技术 id）；永不静默失败。
 *
 * ## 稳定性 4 层
 * 1. **Prompt 严格约束**：明确告知 JSON schema + 硬性约束。
 * 2. **Schema 校验**：解析后严格检查，不合规 → retry-with-feedback（[MAX_RETRIES_ON_SCHEMA_FAILURE] 次）。
 * 3. **网络重试**：LLM 调用失败 → 指数退避 1s→2s→4s（[MAX_RETRIES_ON_NETWORK_FAILURE] 次）。
 * 4. **Rule-based 兜底**：全失败 → 从候选随机挑 1 件 + 预置模板文案（永不 shouldSend=false 除非候选与全局池皆空）。
 *
 * iOS 是 `@MainActor enum`；安卓改 `@Singleton` class（注 [LlmClient]）。决策的纯逻辑（parseAndValidate / fallback /
 * buildPrompt / isRedPacketEligible）在 companion，便于单测；[decide] 编排走 LLM（同 AffinitySenseService，不单测，靠
 * 独立复核 + 真机）。config 由调用方（c-6）按 CHAT 路由解析后传入（null = 无 API → 直接兜底）。
 */
@Singleton
class ProactiveGiftLLMService @Inject constructor(
    private val contextLog: ContextLogService,
) {

    /** 决策动作类型（gift / redPacket），默认 gift 向后兼容。 */
    enum class DecisionAction(val raw: String) {
        GIFT("gift"),
        RED_PACKET("red_packet");

        companion object {
            fun fromRaw(raw: String): DecisionAction? = entries.firstOrNull { it.raw == raw }
        }
    }

    /** LLM 或兜底层的决策结果（1:1 iOS `Decision`）。 */
    data class Decision(
        val shouldSend: Boolean,
        /** 送礼时必填，必须是候选列表中的某个 id */
        val giftId: String?,
        /** 送礼时必填，陪送文案（用礼物名字不用 id） */
        val message: String?,
        /** 决策理由（必填，供日志） */
        val reason: String,
        /** true = 兜底层产出，false = LLM 真的决定了 */
        val isFromFallback: Boolean,
        /** 决策动作（默认 gift 向后兼容） */
        val action: DecisionAction = DecisionAction.GIFT,
        /** 红包金额（action=redPacket 时必填，范围 1-20000，≤ 角色余额，Executor 再校验） */
        val redPacketAmount: Int? = null,
        /** 红包祝福（80 字内，可选） */
        val redPacketBlessing: String? = null,
    )

    /** 校验错误（1:1 iOS `DecisionError`）。[description] 喂回 prompt 做 retry-with-feedback。 */
    sealed class DecisionError(val description: String) {
        class NotValidJSON(detail: String) : DecisionError("JSON 解析失败: $detail")
        class MissingField(detail: String) : DecisionError("字段缺失: $detail")
        class InvalidField(detail: String) : DecisionError("字段无效: $detail")
    }

    /** parseAndValidate 结果（替 iOS `Result<Decision, DecisionError>`）。 */
    sealed interface ParseOutcome {
        data class Success(val decision: Decision) : ParseOutcome
        data class Failure(val error: DecisionError) : ParseOutcome
    }

    /**
     * 决策主入口（**返回一定不为 nil**，失败走兜底）。[config] 由调用方按 CHAT 路由解析；null = 无 API → 直接兜底。
     */
    suspend fun decide(
        context: ProactiveGiftContext,
        trigger: ProactiveGiftTrigger,
        candidates: List<GiftItem>,
        character: CharacterEntity,
        config: ApiConfigValues?,
    ): Decision {
        if (config == null) {
            Log.w(TAG, "无可用 API 配置,走兜底 for ${character.name}")
            return fallbackDecision(trigger, candidates)
        }
        val attempt = tryLLMDecision(context, trigger, candidates, character, config)
        if (attempt != null) return attempt
        Log.w(TAG, "LLM 决策全失败,走兜底层 for ${character.name}")
        return fallbackDecision(trigger, candidates)
    }

    /** LLM 决策链：schema-retry-with-feedback（最多 [MAX_RETRIES_ON_SCHEMA_FAILURE] 次再试）。 */
    private suspend fun tryLLMDecision(
        context: ProactiveGiftContext,
        trigger: ProactiveGiftTrigger,
        candidates: List<GiftItem>,
        character: CharacterEntity,
        config: ApiConfigValues,
    ): Decision? {
        var previousError: String? = null
        for (attempt in 0..MAX_RETRIES_ON_SCHEMA_FAILURE) {
            val (system, user) = buildPrompt(context, trigger, candidates, character, previousError)
            val response = callLLMWithBackoff(system, user, character.name, config) ?: return null // 网络全失败 → 兜底
            when (val outcome = parseAndValidate(response, candidates, trigger.type)) {
                is ParseOutcome.Success -> return outcome.decision
                is ParseOutcome.Failure -> {
                    previousError = outcome.error.description
                    Log.i(TAG, "Schema 校验失败 (第 ${attempt + 1} 次):${outcome.error.description}")
                    // 同 loop 继续 retry-with-feedback
                }
            }
        }
        return null
    }

    /** 网络指数退避 1s→2s→4s（1:1 iOS `callLLMWithBackoff`，[MAX_RETRIES_ON_NETWORK_FAILURE] 次）。 */
    private suspend fun callLLMWithBackoff(system: String, user: String, characterName: String, config: ApiConfigValues): String? {
        val messages = listOf(
            ChatMessageDto(role = "system", content = system),
            ChatMessageDto(role = "user", content = user),
        )
        for (attempt in 0 until MAX_RETRIES_ON_NETWORK_FAILURE) {
            try {
                return contextLog.completion(
                    source = LogSource.PROACTIVE_GIFT,
                    characterName = characterName,
                    config = config,
                    messages = messages,
                    temperature = 0.7,
                    responseFormat = ResponseFormatDto(type = "json_object"),
                )
            } catch (e: Exception) {
                Log.i(TAG, "LLM 调用失败 (第 ${attempt + 1} 次):${e.message}")
                if (attempt < MAX_RETRIES_ON_NETWORK_FAILURE - 1) {
                    delay((2.0.pow(attempt) * 1000).toLong()) // 1s → 2s → 4s
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "ProactiveGiftLLM"

        /** Schema 校验失败时的 retry-with-feedback 最多次数。 */
        const val MAX_RETRIES_ON_SCHEMA_FAILURE = 2

        /** 网络错误时的指数退避重试最多次数。 */
        const val MAX_RETRIES_ON_NETWORK_FAILURE = 3

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 判断某 trigger type 是否允许 LLM 选红包路径（1:1 iOS `isRedPacketEligible`）：
         * festival/birthday/anniversary → 允许；senseLowMood/missingYou → 只能送礼物（安慰/日常想念送红包违和）。
         */
        fun isRedPacketEligible(triggerType: ProactiveGiftTriggerType): Boolean = when (triggerType) {
            ProactiveGiftTriggerType.BIRTHDAY,
            ProactiveGiftTriggerType.ANNIVERSARY,
            ProactiveGiftTriggerType.FESTIVAL,
            -> true
            ProactiveGiftTriggerType.SENSE_LOW_MOOD,
            ProactiveGiftTriggerType.MISSING_YOU,
            -> false
        }

        /**
         * 严格校验 LLM 响应（1:1 iOS `parseAndValidate`）：strip think → JSONExtractor → 校验 shouldSend(Bool)+reason(非空)
         * → action 分派。[triggerType] 非空时校验红包白名单（仅 festival/birthday/anniversary 允许）。失败返回具体错误（喂回
         * prompt 做 retry-with-feedback）。
         */
        fun parseAndValidate(
            response: String,
            candidates: List<GiftItem>,
            triggerType: ProactiveGiftTriggerType? = null,
        ): ParseOutcome {
            val cleaned = MemoryService.strippingThinkingTags(response)
            val jsonStr = JSONExtractor.extract(cleaned)

            val element = runCatching { json.parseToJsonElement(jsonStr) }.getOrNull()
            val obj = element as? JsonObject
                ?: return ParseOutcome.Failure(DecisionError.NotValidJSON("JSON 根对象不是 dict"))

            // 必填：shouldSend（Bool）
            val shouldSend = obj.boolField("shouldSend")
                ?: return ParseOutcome.Failure(DecisionError.MissingField("shouldSend 必须是 Bool"))
            // 必填：reason（非空字符串）
            val reason = obj.stringField("reason")
            if (reason == null || reason.trim().isEmpty()) {
                return ParseOutcome.Failure(DecisionError.MissingField("reason 必须是非空字符串"))
            }

            // action（可选，省略 / 非字符串 = gift 向后兼容）
            val actionRaw = obj.stringField("action") ?: DecisionAction.GIFT.raw
            val action = DecisionAction.fromRaw(actionRaw)
                ?: return ParseOutcome.Failure(
                    DecisionError.InvalidField("action 必须是 \"gift\" 或 \"red_packet\",收到:$actionRaw"),
                )

            // 不送时无论 action 都不校验礼物/红包字段
            if (!shouldSend) {
                return ParseOutcome.Success(
                    Decision(shouldSend = false, giftId = null, message = null, reason = reason, isFromFallback = false, action = action),
                )
            }

            return when (action) {
                DecisionAction.GIFT -> validateGiftAction(obj, candidates, reason)
                DecisionAction.RED_PACKET -> {
                    if (triggerType != null && !isRedPacketEligible(triggerType)) {
                        ParseOutcome.Failure(
                            DecisionError.InvalidField("触发类型 ${triggerType.raw} 不允许选 red_packet,只能选 gift"),
                        )
                    } else {
                        validateRedPacketAction(obj, reason)
                    }
                }
            }
        }

        private fun validateGiftAction(obj: JsonObject, candidates: List<GiftItem>, reason: String): ParseOutcome {
            val giftId = obj.stringField("giftId")?.trim()
            if (giftId.isNullOrEmpty()) {
                return ParseOutcome.Failure(DecisionError.InvalidField("shouldSend=true + action=gift 时 giftId 必填"))
            }
            if (candidates.none { it.id == giftId }) {
                val availableIds = candidates.joinToString(", ") { it.id }
                return ParseOutcome.Failure(DecisionError.InvalidField("giftId '$giftId' 不在候选列表中。可选:$availableIds"))
            }
            val message = obj.stringField("message")?.trim()
            if (message.isNullOrEmpty()) {
                return ParseOutcome.Failure(DecisionError.InvalidField("shouldSend=true + action=gift 时 message 必填非空"))
            }
            if (message.contains("gift_")) {
                return ParseOutcome.Failure(DecisionError.InvalidField("message 不得含技术 id(gift_ 前缀)。用礼物中文名字。"))
            }
            return ParseOutcome.Success(
                Decision(shouldSend = true, giftId = giftId, message = message, reason = reason, isFromFallback = false, action = DecisionAction.GIFT),
            )
        }

        private fun validateRedPacketAction(obj: JsonObject, reason: String): ParseOutcome {
            val amount = obj.intField("redPacketAmount")
                ?: return ParseOutcome.Failure(DecisionError.MissingField("action=red_packet 时 redPacketAmount 必填(整数)"))
            if (!RedPacketAmountCatalog.isValidAmount(amount)) {
                return ParseOutcome.Failure(
                    DecisionError.InvalidField(
                        "redPacketAmount 超出范围 [${RedPacketAmountCatalog.MIN_AMOUNT}, ${RedPacketAmountCatalog.MAX_AMOUNT}]:$amount",
                    ),
                )
            }
            var blessing: String? = null
            val raw = obj.stringField("redPacketBlessing")?.trim()
            if (!raw.isNullOrEmpty()) {
                if (raw.contains("gift_") || raw.contains("red_packet")) {
                    return ParseOutcome.Failure(DecisionError.InvalidField("redPacketBlessing 不得含技术 id"))
                }
                blessing = if (raw.length > 80) raw.take(80) else raw
            }
            return ParseOutcome.Success(
                Decision(
                    shouldSend = true, giftId = null, message = null, reason = reason, isFromFallback = false,
                    action = DecisionAction.RED_PACKET, redPacketAmount = amount, redPacketBlessing = blessing,
                ),
            )
        }

        /**
         * Rule-based 兜底（1:1 iOS `fallbackDecision`）：从候选随机挑 1 件 + 按触发类型给模板文案。**永不 shouldSend=false**
         * （除非候选和全局池都无礼物，极端情况）。[rng] 注入便于确定性单测。
         */
        fun fallbackDecision(
            trigger: ProactiveGiftTrigger,
            candidates: List<GiftItem>,
            rng: Random = Random.Default,
        ): Decision {
            candidates.randomOrNull(rng)?.let { gift ->
                return Decision(
                    shouldSend = true, giftId = gift.id,
                    message = fallbackMessage(trigger.type, gift.name),
                    reason = "兜底 · LLM 决策失败,从候选随机挑一件", isFromFallback = true,
                )
            }
            // 候选空：从全局池挑便利店级别小物
            val cheapGifts = GiftCatalog.allItems.filter { it.price <= 30 && !it.isHandmade }
            cheapGifts.randomOrNull(rng)?.let { defaultGift ->
                return Decision(
                    shouldSend = true, giftId = defaultGift.id,
                    message = fallbackMessage(trigger.type, defaultGift.name),
                    reason = "兜底 · 候选为空,从全局便利店级别池挑", isFromFallback = true,
                )
            }
            // 极端：全局池也空（不应发生）
            return Decision(
                shouldSend = false, giftId = null, message = null,
                reason = "兜底 · 候选和全局池都无可用礼物(不应发生)", isFromFallback = true,
            )
        }

        /** 按触发类型返回预置陪送文案模板（1:1 iOS `fallbackMessage`，5 套）。 */
        fun fallbackMessage(triggerType: ProactiveGiftTriggerType, giftName: String): String = when (triggerType) {
            ProactiveGiftTriggerType.BIRTHDAY -> "今天是你的生日,给你带了$giftName。生日快乐。"
            ProactiveGiftTriggerType.ANNIVERSARY -> "算着日子,咱们认识已经一段时间了。送你$giftName 留个纪念。"
            ProactiveGiftTriggerType.FESTIVAL -> "节日快乐,给你带了$giftName。"
            ProactiveGiftTriggerType.SENSE_LOW_MOOD -> "最近感觉你不太开心,买了${giftName}给你,希望能让你好一点。"
            ProactiveGiftTriggerType.MISSING_YOU -> "路过店里突然想起你,买了$giftName。"
        }

        /**
         * 构造 system + user prompt（1:1 iOS `buildPrompt`）。[previousError] 非 null 时末尾追加「上次出错提示」让 LLM
         * 自纠正（Instructor 模式）。红包白名单触发额外提示 gift vs red_packet 选择。
         */
        fun buildPrompt(
            context: ProactiveGiftContext,
            trigger: ProactiveGiftTrigger,
            candidates: List<GiftItem>,
            character: CharacterEntity,
            previousError: String? = null,
            /** 卷四 §4.5 ⑤：意图惰性衰减的参考时刻（测试可注入）；默认取当前。 */
            nowMillis: Long = System.currentTimeMillis(),
        ): Pair<String, String> {
            val redPacketEligible = isRedPacketEligible(trigger.type)
            val sys = mutableListOf<String>()
            sys.add("你是「${context.characterName}」。")
            if (character.personalityDescription.isNotEmpty()) sys.add("性格:${character.personalityDescription}")
            if (character.speakingStyle.isNotEmpty()) sys.add("说话风格:${character.speakingStyle}")
            if (context.occupation.isNotEmpty()) sys.add("职业:${context.occupation}")
            sys.add("")
            sys.add("## 任务")
            if (redPacketEligible) {
                sys.add("决定今天是否主动给用户**送礼物或发红包**,像真人一样综合考虑。")
            } else {
                sys.add("决定今天是否主动给用户送一件礼物,像真人一样综合考虑。")
            }
            sys.add("")
            sys.add("## 决策要点")
            sys.add("1. 看候选触发理由(生日/节日/纪念日/察觉不开心/想你),值不值得今天主动送")
            sys.add("2. 考虑经济档位:紧张时少送或不送,宽裕时适度送,避免超预算")
            sys.add("3. 避免过于频繁:距上次送礼太近就别送,除非是重要日子(如生日)")
            if (redPacketEligible) {
                sys.add("4. 选**礼物**还是**红包**:礼物更有心意(挑选过),红包更直接(单纯钱)。")
                sys.add("   节日氛围浓、关系铁或你这个性格比较「大方」时可以选红包;想表心意 / 刚认识不久时选礼物")
                sys.add("5. 如果选礼物:从候选礼物列表中挑最合适的一件")
                sys.add("6. 如果选红包:自己决定金额(1-20000 范围,必须 ≤ 余额),可以是吉利数 88/168/520/888 也可以是整数")
                sys.add("7. 写一句陪送文案(message),体现 TA 的性格和经济压力感")
            } else {
                sys.add("4. 如果送,从候选礼物列表中选最合适的一件")
                sys.add("5. 写一句陪送文案(message),体现 TA 的性格和经济压力感")
            }
            sys.add("")
            sys.add("## 输出格式(严格 JSON,不要 markdown 代码块)")
            if (redPacketEligible) {
                sys.add("""{"shouldSend": <true/false>, "action": "gift" 或 "red_packet", "giftId": <候选 id 字符串 或 null>, "message": <陪送文案 或 null>, "redPacketAmount": <红包金额 或 null>, "redPacketBlessing": <红包祝福 或 null>, "reason": "<决策理由,必填>"}""")
            } else {
                sys.add("""{"shouldSend": <true/false>, "giftId": <候选 id 字符串 或 null>, "message": <陪送文案 或 null>, "reason": "<决策理由,必填>"}""")
                sys.add("""注意:本次触发类型**不允许选择红包**,action 必须是 "gift" 或省略""")
            }
            sys.add("")
            sys.add("## 硬性约束")
            if (redPacketEligible) {
                sys.add("- shouldSend=true 时:若 action=\"gift\",giftId 和 message 必填;若 action=\"red_packet\",redPacketAmount 必填")
                sys.add("- giftId 必须严格等于候选列表中某一个 id(不要自编 id)")
                sys.add("- message 中不要出现 gift_ 开头的技术 id,用礼物中文名字")
                sys.add("- redPacketAmount 必须是整数 · 范围 1-20000 · 不超过余额")
                sys.add("- redPacketBlessing 可选,非空时 ≤ 80 字,不含技术 id")
            } else {
                sys.add("- shouldSend=true 时 giftId 和 message 必填")
                sys.add("- giftId 必须严格等于候选列表中某一个 id(不要自编 id)")
                sys.add("- message 中不要出现 gift_ 开头的技术 id,用礼物中文名字")
            }
            sys.add("- reason 字段必填,不送也要写清楚理由")

            if (previousError != null) {
                sys.add("")
                sys.add("## ⚠️ 上次尝试出错")
                sys.add(previousError)
                sys.add("请严格按格式重新输出。")
            }

            val usr = mutableListOf<String>()
            usr.add("## 今日情况")
            if (context.candidateTriggers.isEmpty()) {
                usr.add("候选触发:无")
            } else {
                usr.add("候选触发(按优先级):")
                for (t in context.candidateTriggers) {
                    usr.add("- ${t.type.displayName}(优先级 ${t.type.priority}):${t.label}")
                }
            }
            val days = context.daysSinceLastProactiveGift
            if (days != null) {
                usr.add("距上次 TA 主动送礼:$days 天")
            } else {
                usr.add("距上次 TA 主动送礼:还没送过")
            }
            context.economicTier?.let { usr.add("当前经济状况:${it.promptLabel}") }
            usr.add("月薪:${context.monthlySalary} 金币 · 余额:${context.coinBalance} 金币")
            context.relationshipLabel?.let { usr.add("当前关系:$it") }
            usr.add("最近心情摘要:${context.recentMoodSummary}")
            // 卷四 §4.5 ⑤：心里挂着的事（只此 3 行·decide / 校验 / 兜底零碰·钱路零碰）
            IntentExitRenderer.giftBlock(character.intentQueue.intents, context.userName.ifEmpty { "用户" }, nowMillis)
                .takeIf { it.isNotEmpty() }?.let { usr.add(it) }
            usr.add("")
            usr.add("## 候选礼物")
            if (candidates.isEmpty()) {
                usr.add("(候选为空,请返回 shouldSend=false)")
            } else {
                for (item in candidates) {
                    val tags = item.emotionalTags.joinToString(", ") { it.raw }
                    val handmadeMark = if (item.isHandmade) " · 手作" else ""
                    usr.add("- id=\"${item.id}\", name=\"${item.name}\", price=${item.price}, tags=[$tags]$handmadeMark · ${item.subtitle}")
                }
            }
            usr.add("")
            usr.add("现在请决定是否送 + 送什么 + 说什么,输出 JSON。")

            return sys.joinToString("\n") to usr.joinToString("\n")
        }

        // ── JSON 字段读取（对齐 iOS `json[key] as? Type` 严格语义） ──
        // bool/int 须为非字符串原语；string 须为 JSON 字符串（isString），否则视为缺失/类型不符。

        private fun JsonObject.boolField(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        private fun JsonObject.stringField(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.intField(key: String): Int? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    }
}
