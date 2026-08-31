package com.situ.aichat.offline

import com.situ.aichat.data.model.OfflineInviteJson
import com.situ.aichat.data.remote.llm.FunctionDefinitionDto
import com.situ.aichat.data.remote.llm.FunctionParametersDto
import com.situ.aichat.data.remote.llm.ParameterPropertyDto
import com.situ.aichat.data.remote.llm.ToolDefinitionDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 线下见面操作（AI 从回复里解析出的「邀约 / 结束见面」指令，1:1 iOS `OfflineMeetingAction`）。
 *
 * **安卓架构（双轨·2026-07-12 更正过期注释——旧文称"tool calling 未启用/恒 false"早已不实）**：
 * 结构化 tool calling 已接入——模型支持时 `AssistantTurnEngine.streamOneTurn` 随请求下发 [toolDefinitions]、
 * 流式累积 tool_calls 后经 [com.situ.aichat.offline.ToolCallActionExtractor] 以 [fromToolCallArguments] /
 * [lenientParseToolCallArguments] 两层解码；模型不支持或本轮降级时走**文本标记暗号路径**（[parseFromResponse]
 * 4 级降级，与 [com.situ.aichat.data.calendar.CalendarAction] 的 `parseFromResponse` 同轨）。
 * 本类 co-locate 两轨的 schema / 守卫提示词 / 解析（tool-calling 加固拍板：改任一侧同文件可见）。
 */
data class OfflineMeetingAction(
    val action: OfflineMeetingActionType,
    /** 见面地点（suggestMeeting）。 */
    val location: String? = null,
    /** 活动描述（suggestMeeting）。 */
    val activity: String? = null,
    /** 邀约台词（suggestMeeting）。 */
    val invitation: String? = null,
    /** 完整心事 / 场景种子（suggestMeeting，常驻 prompt 指令，用户不可见）。 */
    val hiddenTension: String? = null,
    /** 给用户看的隐晦暗示短语（suggestMeeting，显示在邀约卡上，不剧透）。 */
    val tensionHint: String? = null,
    /** 告别叙述（endMeeting，已废弃；仅兼容老历史数据 / 降级路径的 JSON 解析）。 */
    val farewell: String? = null,
    /** 结束见面的整体情绪基调（endMeeting）：warm/sweet/melancholic/awkward/neutral。 */
    val finalMood: String? = null,
) {
    companion object {
        /** 工具名是否为线下见面相关（1:1 iOS isOfflineMeetingTool）。 */
        fun isOfflineMeetingTool(name: String): Boolean =
            name == "suggest_offline_meeting" || name == "end_offline_meeting"

        // — 结构化工具调用定义（请求侧，1:1 iOS OfflineMeetingAction.toolDefinitions） —

        /** 完整工具集（suggest + end）；schema/描述逐字对齐 iOS。 */
        val toolDefinitions: List<ToolDefinitionDto> = listOf(
            ToolDefinitionDto(
                type = "function",
                function = FunctionDefinitionDto(
                    name = "suggest_offline_meeting",
                    description = """
                        Suggest an in-person offline meeting with the user. Call this tool in ANY of these situations:
                        1. You (the character) want to invite the user to go somewhere together
                        2. The user has JUST clearly said they want to meet right now, and you agree
                        3. Both of you naturally agree to meet up right now
                        IMPORTANT: Only use this for IMMEDIATE meetings (right now), NOT for future plans (e.g., "周末再约"). The user will see an invitation card and can accept or decline.
                        Before calling, check the conversation for [系统记录：…线下见面邀约…] records of your own recent invitations: if the last one is still unanswered or was just declined, do not repeat it as if nothing happened — follow the chat rules about re-inviting.
                        You MUST also provide a hidden tension seed (small unspoken inner state of the character that will drive the emotional arc of the meeting) together with a short hint phrase for the user-facing card.
                    """.trimIndent(),
                    parameters = FunctionParametersDto(
                        type = "object",
                        properties = linkedMapOf(
                            "location" to ParameterPropertyDto("string", "The meeting location (e.g., 星巴克, 公园, 电影院)"),
                            "activity" to ParameterPropertyDto("string", "What you plan to do together (e.g., 喝咖啡聊天, 散步, 看电影)"),
                            "invitation" to ParameterPropertyDto("string", "Your invitation message in character voice (e.g., 走吧，我知道一家新开的咖啡厅很不错~)"),
                            "hidden_tension" to ParameterPropertyDto(
                                "string",
                                "A short unspoken inner state the character secretly carries into this meeting, in character voice, one sentence. Examples: '她今天其实有点心事没说'; '你昨天没回消息她一直在意'; '他今天收到一个让他犹豫的消息，想找你聊但又不好直说'; '她偷偷带了一件小礼物，但还没想好什么时候拿出来'. This will NOT be shown to the user. It must not be mentioned directly in the invitation text. It is a hidden seed that should naturally surface during the meeting (not before round 4) but never be fully resolved in the first reply.",
                            ),
                            "tension_hint" to ParameterPropertyDto(
                                "string",
                                "A very short (≤ 12 chars), indirect hint phrase for the user-facing invitation card. It should softly suggest there is something on the character's mind without spoiling the actual tension. Examples: '她的表情有点不一样'; '他今天有点心事'; '今天的她比平时安静'.",
                            ),
                        ),
                        required = listOf("location", "activity", "invitation", "hidden_tension", "tension_hint"),
                    ),
                ),
            ),
            ToolDefinitionDto(
                type = "function",
                function = FunctionDefinitionDto(
                    name = "end_offline_meeting",
                    description = """
                        End the current offline meeting. Call this tool ONLY when at least one of these 4 clear signals has just appeared in the current turn:

                        TRIGGER SIGNALS (call the tool):
                        1. Either character has explicitly said a farewell phrase in this turn ("该走了 / 时候不早了 / 我先走了 / 下次再约 / 送你到地铁口")
                        2. A complete emotional closure has been reached in this turn (hug, wave goodbye, watching the other leave, a promise of next time)
                        3. The 【节拍状态】 block at the end of the system prompt (if present) says allow_end = true
                        4. Physical separation is already unfolding in your narration (walking toward parting, getting into a taxi, opening the car door)

                        MANDATORY ORDER OF OPERATIONS:
                        Before calling this tool you MUST first emit, IN THE SAME TURN, a complete farewell scene containing at least one [场景] or [环境] block, one [动作] block, and one [对话] block. Only call the tool AFTER those content blocks have been written. The farewell narrative lives in the content blocks — do NOT pass it as a tool parameter and do NOT call this tool on an empty reply.

                        DO NOT call this tool when:
                        - Fewer than 3 user messages have been exchanged in this meeting
                        - The user just asked a question and is awaiting an answer
                        - An emotional peak is still unfolding (confession, first touch, vulnerability moment)
                        - 【节拍状态】 explicitly says allow_end = false
                        - The hidden_tension seed you introduced at meeting start is still completely unresolved
                    """.trimIndent(),
                    parameters = FunctionParametersDto(
                        type = "object",
                        properties = linkedMapOf(
                            "final_mood" to ParameterPropertyDto(
                                "string",
                                "The overall emotional tone you are leaving the user with as the meeting ends. Choose the single best fit. warm = cozy and affectionate; sweet = a light happy butterflies feeling; melancholic = a soft aching not-enough feeling; awkward = something slightly off or unfinished; neutral = ordinary pleasant ending with nothing particularly striking.",
                                listOf("warm", "sweet", "melancholic", "awkward", "neutral"),
                            ),
                        ),
                        required = listOf("final_mood"),
                    ),
                ),
            ),
        )

        /**
         * 按「角色主动邀约」开关过滤（1:1 iOS toolDefinitions(canInitiate:)）：
         * canInitiate=true 返回完整工具集；false 剔除 suggest_offline_meeting，保留 end_offline_meeting。
         */
        fun toolDefinitions(canInitiate: Boolean): List<ToolDefinitionDto> =
            if (canInitiate) toolDefinitions
            else toolDefinitions.filter { it.function.name != "suggest_offline_meeting" }

        // — 双模式系统提示词（①·从 PromptBuilderGuards 搬来·与 schema/inviteRegex co-located·逐字不变） —
        // 装配末尾「角色可主动发起线下见面」时按模式注入；暗号版的 [offline_invite|地点|活动|邀约台词] 格式与
        // 上面 [inviteRegex] 强耦合（§5），搬到同文件后改任一处即看见另一处。由 OfflineChatTool.stepFiveGuardPrompt 消费。

        /**
         * 知情邀约规则（留痕改造 2026-08-31·工具/暗号双模式共用·拼在两版守卫提示词末尾）。
         *
         * ⚠️ **必须声明在 [TOOL_CALLING_PROMPT] / [FALLBACK_PROMPT] 之前**——companion object 属性按声明序
         * 初始化，后置引用会把空值拼进两个 prompt（静默失效）。
         * 文中 `[系统记录：…发出了线下见面邀约…]` 与
         * [com.situ.aichat.data.model.OfflineInviteData.llmRepresentation] 的措辞单源同步。
         */
        internal val INFORMED_INVITE_RULES: String = """
            【邀约的分寸】
            历史里的 [系统记录：…发出了线下见面邀约…] 这类记录，是你自己以前发出的邀约和它的结果——记录里一律用名字相称，你的名字指的就是你自己。必须当作已经发生过的事实对待：
            - 上一次邀约的状态还是「还没回应」时，不要再发起新的邀约——先正常聊天，最多用一句话轻轻问问对方的意思。
            - 你们刚结束一次见面、或对方刚婉拒了你的邀约，你依然可以再次发起（如果你此刻真的很想见对方），但邀约台词必须体现出你记得这件事（比如「我知道我们刚见过面」「我知道你刚说了下次」），绝不能当作无事发生地重发一遍一样的邀约。
            - 对方连续两次婉拒之后，不要再发起邀约——改用文字自然地表达想念或一点点遗憾，等对方主动提起再约。
        """.trimIndent()

        /** 工具模式：要求模型调 suggest_offline_meeting 工具、严禁正文「表演」工具调用（1:1 iOS）。 */
        val TOOL_CALLING_PROMPT: String = """
            【线下见面规则】
            当你要邀请用户线下见面时，必须调用 suggest_offline_meeting 工具。
            绝不要在回复中用文字描述邀请（如"发出了一张线下见面邀请"），系统不会识别纯文字邀请。
            上下文中出现的 <recent_offline_events> / <current_state> 等 XML 标签是系统元数据，不要模仿或复读。

            **严禁用以下任何方式在回复正文中"表演"工具调用**（这是模型最常见的错误，
            会直接作为聊天气泡显示给用户，体验非常差）：
            - Markdown 列表 + 反引号字段名（如 - `activity`: `散步`）
            - JSON 格式（如 {"type":"offline_invite",...}）
            - 自然语言描述工具参数（如"我要调用 suggest_offline_meeting，参数是..."）

            要么**真正调用工具**，要么**完全不邀约**（继续正常聊天）。

            如果工具调用确实不可用，在回复末尾使用此文本标记：[offline_invite|地点|活动|邀约台词]
        """.trimIndent() + "\n\n" + INFORMED_INVITE_RULES

        /** 暗号降级模式：在回复末尾附 [offline_invite|…] 文本标记（与 [inviteRegex] 强耦合·1:1 iOS）。 */
        val FALLBACK_PROMPT: String = """
            【线下见面功能】
            当出现以下任何一种情况时，你可以在回复末尾附上一个特殊标记来邀请用户线下见面：
            1. 你（角色）主动想约用户出去
            2. 用户邀请你见面，而你同意了
            3. 你们自然地商量好了立刻见面

            标记格式：[offline_invite|地点|活动|邀约台词]
            示例：[offline_invite|附近的咖啡店|喝咖啡聊天|走吧，我知道一家不错的咖啡厅~]

            重要判断标准：
            - 只在「当下立即见面」时使用，未来的约定（如"周末再约"）不要使用
            - 必须是双方都明确同意见面了才使用
            - 单方面表达愿望（如"我想见你"）不使用，除非对方也同意了
            - 标记放在你正常回复文字的末尾，系统会自动解析并显示为邀约卡片
        """.trimIndent() + "\n\n" + INFORMED_INVITE_RULES

        // — 结构化 tool-call 参数解码（S2，1:1 iOS fromToolCallArguments / lenientParseToolCallArguments） —

        /** 严格解码用（ignoreUnknownKeys = Swift JSONDecoder 默认忽略未知键；非 isLenient）。 */
        private val toolArgsJson = Json { ignoreUnknownKeys = true }

        /**
         * 从工具调用 arguments JSON 解析（1:1 iOS fromToolCallArguments）。snake_case 字段
         * （hidden_tension/tension_hint/final_mood）由 [OfflineActionFallbackJson] 的 @SerialName 映射。
         * @throws IllegalArgumentException 未知工具名；JSON 非法时底层抛 SerializationException。
         */
        fun fromToolCallArguments(name: String, json: String): OfflineMeetingAction {
            val args = toolArgsJson.decodeFromString(OfflineActionFallbackJson.serializer(), json)
            return when (name) {
                "suggest_offline_meeting" -> OfflineMeetingAction(
                    action = OfflineMeetingActionType.SUGGEST_MEETING,
                    location = args.location,
                    activity = args.activity,
                    invitation = args.invitation,
                    hiddenTension = args.hiddenTension,
                    tensionHint = args.tensionHint,
                )
                "end_offline_meeting" -> OfflineMeetingAction(
                    action = OfflineMeetingActionType.END_MEETING,
                    farewell = args.farewell,
                    finalMood = args.finalMood,
                )
                else -> throw IllegalArgumentException("未知的线下模式工具名：$name")
            }
        }

        /**
         * 宽容解析工具参数（1:1 iOS lenientParseToolCallArguments）：严格解码失败时的兜底。
         * 用 JsonObject 提取、同时接受 snake_case 与 camelCase 键；JSON 损坏 / 未知工具名 → null。
         */
        fun lenientParseToolCallArguments(name: String, json: String): OfflineMeetingAction? {
            val obj = runCatching { fallbackJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            fun str(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull }
            return when (name) {
                "suggest_offline_meeting" -> OfflineMeetingAction(
                    action = OfflineMeetingActionType.SUGGEST_MEETING,
                    location = str("location"),
                    activity = str("activity"),
                    invitation = str("invitation"),
                    hiddenTension = str("hidden_tension", "hiddenTension"),
                    tensionHint = str("tension_hint", "tensionHint"),
                )
                "end_offline_meeting" -> OfflineMeetingAction(
                    action = OfflineMeetingActionType.END_MEETING,
                    farewell = str("farewell"),
                    finalMood = str("final_mood", "finalMood"),
                )
                else -> null
            }
        }

        // — 文本标记降级解析（不支持 tool calling 的模型 + 安卓全量走此路径） —

        /** 匹配 [offline_invite|地点|活动|台词]（1:1 iOS inviteRegex）。 */
        private val inviteRegex = Regex("""\[offline_invite\|([^|]+)\|([^|]+)\|([^\]]+)\]""")

        /** 匹配 [offline_end]（1:1 iOS endRegex）。 */
        private val endRegex = Regex("""\[offline_end\]""")

        /**
         * 匹配 LLM 复读的 [系统记录：…邀约卡片 | 地点=X | 活动=Y …]（1:1 iOS sysRecordInviteRegex）。
         * 部分模型（如 DeepSeek）不调工具，而是复读 llmRepresentation 系统标签；从中提取地点/活动建卡。
         */
        private val sysRecordInviteRegex =
            Regex("""\[系统记录：\S+的?线下见面邀约卡片\s*\|\s*地点=([^|]+?)\s*\|\s*活动=([^|\]]+)""")

        /** 匹配任意 [系统记录：…] 标签（兜底清除，1:1 iOS sysRecordAnyRegex）。 */
        private val sysRecordAnyRegex = Regex("""\[系统记录：[^\]]*\]""")

        /** JSON 兜底解码器：lenient + 忽略未知键；snake_case 字段由 [OfflineActionFallbackJson] 的 @SerialName 映射。 */
        private val fallbackJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * 从 LLM 回复解析线下文本标记，返回 (清理后正文, 操作列表)（1:1 iOS `parseFromResponse`）。
         *
         * 降级顺序：① [offline_invite|…] ② [offline_end] ③（前两者皆未命中时）[系统记录…邀约卡片…] 复读
         * ④（仍未命中时）正文里的 JSON 对象兜底。无论是否命中都清除残留 [系统记录：…] 标签。操作按在原文中
         * 的出现位置排序后返回；正文 trim 首尾空白/换行。
         */
        fun parseFromResponse(response: String): Pair<String, List<OfflineMeetingAction>> {
            var cleanText = response
            // (出现位置, 操作)——位置取自原文，用于最终按原文顺序排序。
            val ranged = ArrayList<Pair<Int, OfflineMeetingAction>>()

            // ① 邀约标记
            for (m in inviteRegex.findAll(response)) {
                val (location, activity, invitation) = m.destructured
                ranged.add(
                    m.range.first to OfflineMeetingAction(
                        action = OfflineMeetingActionType.SUGGEST_MEETING,
                        location = location,
                        activity = activity,
                        invitation = invitation,
                    ),
                )
            }
            cleanText = inviteRegex.replace(cleanText, "")

            // ② 结束标记（无子组，存在即转结束动作）
            for (m in endRegex.findAll(response)) {
                ranged.add(m.range.first to OfflineMeetingAction(action = OfflineMeetingActionType.END_MEETING))
            }
            cleanText = endRegex.replace(cleanText, "")

            // ③ [系统记录] 邀约复读兜底（仅在前两者皆未命中时）
            if (ranged.isEmpty()) {
                for (m in sysRecordInviteRegex.findAll(response)) {
                    ranged.add(
                        m.range.first to OfflineMeetingAction(
                            action = OfflineMeetingActionType.SUGGEST_MEETING,
                            location = m.groupValues[1].trim(),
                            activity = m.groupValues[2].trim(),
                        ),
                    )
                }
            }

            // 清除所有残留 [系统记录：…] 标签（含 markerStart/End 回声）
            cleanText = sysRecordAnyRegex.replace(cleanText, "")

            // ④ JSON 兜底（仍未命中时）：从正文提取 JSON 对象的邀约/结束数据，并倒序移除其文本
            if (ranged.isEmpty()) {
                val jsonFallback = parseJSONFallback(response)
                for ((range, action) in jsonFallback) ranged.add(range.first to action)
                for ((range, _) in jsonFallback.asReversed()) {
                    if (range.last < cleanText.length) cleanText = cleanText.removeRange(range)
                }
            }

            val actions = ranged.sortedBy { it.first }.map { it.second }
            return cleanText.trim() to actions
        }

        /**
         * 从文本提取 JSON 格式的线下邀约/结束数据（兜底路径，1:1 iOS `parseJSONFallback`）。
         * 简单非嵌套花括号匹配；解码成功且 type ∈ {offline_invite, offline_end} 才生成动作。
         * 返回 (在原文中的闭区间, 操作)，供上层倒序清理正文。
         */
        private fun parseJSONFallback(text: String): List<Pair<IntRange, OfflineMeetingAction>> {
            val results = ArrayList<Pair<IntRange, OfflineMeetingAction>>()
            var searchStart = 0
            while (searchStart < text.length) {
                val open = text.indexOf('{', searchStart)
                if (open < 0) break
                val close = text.indexOf('}', open + 1)
                if (close < 0) break

                val jsonStr = text.substring(open, close + 1)
                val invite = runCatching {
                    fallbackJson.decodeFromString(OfflineActionFallbackJson.serializer(), jsonStr)
                }.getOrNull()
                if (invite != null) {
                    when (invite.type) {
                        OfflineInviteJson.TYPE_INVITE -> results.add(
                            open..close to OfflineMeetingAction(
                                action = OfflineMeetingActionType.SUGGEST_MEETING,
                                location = invite.location,
                                activity = invite.activity,
                                invitation = invite.invitation,
                                hiddenTension = invite.hiddenTension,
                                tensionHint = invite.tensionHint,
                            ),
                        )
                        OfflineInviteJson.TYPE_END -> results.add(
                            open..close to OfflineMeetingAction(
                                action = OfflineMeetingActionType.END_MEETING,
                                farewell = invite.farewell,
                                finalMood = invite.finalMood,
                            ),
                        )
                    }
                }
                searchStart = close + 1
            }
            return results
        }
    }
}

/** 线下见面操作类型（1:1 iOS OfflineMeetingActionType，rawValue 对齐）。 */
enum class OfflineMeetingActionType(val raw: String) {
    SUGGEST_MEETING("suggest_meeting"),
    END_MEETING("end_meeting"),
}

/**
 * JSON 兜底解码用（snake_case 键 → camelCase 属性）。LLM tool schema 用 snake_case（hidden_tension /
 * tension_hint / final_mood），故显式 @SerialName 映射（= iOS parseJSONFallback 的 convertFromSnakeCase）。
 */
@Serializable
private data class OfflineActionFallbackJson(
    val type: String? = null,
    val location: String? = null,
    val activity: String? = null,
    val invitation: String? = null,
    @SerialName("hidden_tension") val hiddenTension: String? = null,
    @SerialName("tension_hint") val tensionHint: String? = null,
    val farewell: String? = null,
    @SerialName("final_mood") val finalMood: String? = null,
)
