package com.situ.aichat.meeting

import com.situ.aichat.data.model.MeetingCandidate
import com.situ.aichat.data.model.MeetingCandidateIntent
import com.situ.aichat.data.model.MeetingConfidence
import com.situ.aichat.data.model.MeetingProposedBy
import com.situ.aichat.data.model.MeetingSource
import com.situ.aichat.data.remote.llm.FunctionDefinitionDto
import com.situ.aichat.data.remote.llm.FunctionParametersDto
import com.situ.aichat.data.remote.llm.ParameterPropertyDto
import com.situ.aichat.data.remote.llm.ToolDefinitionDto
import com.situ.aichat.tooling.MarkerJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 未来约定见面 · 工具快路（1:1 iOS `Models/FutureMeetingTool.swift`，安卓「双轨」实现）。
 *
 * AI 在对话里达成「未来某天见面」时即时上报，解析成候选（intent=new）。**双轨**（与
 * [com.situ.aichat.offline.OfflineMeetingAction] 同款）：
 * - **工具路**（toolCallingEnabled 时）：模型调 [TOOL_NAME] 工具，参数经 [candidateFromToolCall]（source=tool）。
 * - **文本暗号路**（降级 / 默认）：模型在回复末尾附 `[future_meeting]{...json...}` 标记，经 [parseProposalMarkers]
 *   提取并从正文擦除（用户不可见，source=fallback）。
 *
 * 与现有工具的边界（写进描述避免模型混淆）：
 * - `suggest_offline_meeting`：**当下立刻**见面。
 * - [TOOL_NAME]（本工具）：**未来某天**的约定。
 * - `calendar_action`：写进手机**真日历**，与「和角色的虚拟约会」无关。
 *
 * 本块只做定义 + 解析（引擎）+ 文本暗号规则文案。加进请求 tools 数组 / 分发 / 改 suggest 描述 / 接 prompt
 * 这些接线在 Phase 6（候选入库）一起做。纯函数为主，可单测。
 */
object FutureMeetingTool {

    const val TOOL_NAME = "propose_future_meeting"

    fun isFutureMeetingTool(name: String): Boolean = name == TOOL_NAME

    // ── 工具定义（toolCallingEnabled 时下发，描述逐字对齐 iOS） ──

    val toolDefinition: ToolDefinitionDto = ToolDefinitionDto(
        type = "function",
        function = FunctionDefinitionDto(
            name = TOOL_NAME,
            description = """
                Propose a FUTURE in-person meeting (on a later day/time, NOT right now) that you and the user have just agreed on. Call this when EITHER:
                1. You invite the user to meet on a future day and they agree, OR
                2. The user proposes a future meetup and you agree.

                IMPORTANT boundaries:
                - ONLY for FUTURE plans (e.g. 周末 / 下周三 / 明天晚上). For meeting RIGHT NOW, use suggest_offline_meeting instead.
                - This is an in-app meetup between you and the user — it is NOT a phone calendar entry. Do NOT use calendar_action for this.
                - Only call when BOTH sides have clearly agreed. Do NOT call for casual pleasantries like "改天约" / "有空再说".
            """.trimIndent(),
            parameters = FunctionParametersDto(
                type = "object",
                properties = linkedMapOf(
                    "when_text" to ParameterPropertyDto("string", "The time exactly as said in the conversation, e.g. 周六下午 / 下周三晚上 / 明天"),
                    "iso_datetime" to ParameterPropertyDto("string", "If you can compute the concrete time, give ISO8601 with timezone (e.g. 2026-06-27T15:00:00+08:00). Date-only intent → that day at 19:00. Leave empty if unsure."),
                    "location" to ParameterPropertyDto("string", "Where you will meet (e.g. 美术馆, 公园). Empty if not decided."),
                    "activity" to ParameterPropertyDto("string", "What you will do together (e.g. 看展, 吃饭, 散步)."),
                    "invitation" to ParameterPropertyDto("string", "Your invitation line in character voice (e.g. 周六一起去看那个展吧~). Optional."),
                    "tension_hint" to ParameterPropertyDto("string", "A very short (≤12 chars) indirect hint shown on the user-facing card. Optional."),
                    "hidden_tension" to ParameterPropertyDto("string", "A short unspoken inner state you secretly carry into this future meeting, in character voice. NOT shown to the user. Optional."),
                    "proposed_by" to ParameterPropertyDto("string", "Who initiated this meetup.", listOf("character", "user")),
                ),
                required = listOf("when_text", "activity"),
            ),
        ),
    )

    // ── 工具路：tool_call 参数 → 候选 ──

    /** 从工具调用 arguments JSON 解析候选（intent=new, source=tool, 高把握度）；空壳 / 非法 → null。 */
    fun candidateFromToolCall(argumentsJson: String): MeetingCandidate? {
        val args = runCatching { lenientJson.decodeFromString(FutureMeetingArgs.serializer(), argumentsJson) }
            .getOrNull() ?: return null
        return candidateFrom(args, MeetingSource.TOOL)
    }

    // ── 文本暗号路：`[future_meeting]{...}` → 候选 + 从正文擦除 ──

    /**
     * 提取并擦除回复里的 `[future_meeting]{json}` 暗号，返回 (清理后正文, 候选列表)。
     * 用户绝不可见标记原文（与 OfflineMeetingAction 文本标记路一致）。JSON 用**配平花括号扫描**
     * （[matchingBraceEnd]·跳过字符串字面量内的 }/转义），容忍值里含 `}`——否则会在串内 `}` 处截断、
     * 把真正的 JSON 尾巴漏给用户、且候选也建不成。
     */
    fun parseProposalMarkers(response: String): Pair<String, List<MeetingCandidate>> {
        val candidates = ArrayList<MeetingCandidate>()
        val removeRanges = ArrayList<IntRange>()
        for (m in MARKER_REGEX.findAll(response)) {
            val open = response.indexOf('{', m.range.last + 1)
            val close = if (open >= 0) matchingBraceEnd(response, open) else -1
            if (open < 0 || close < 0) {
                // 标记后无合法 JSON：仍擦掉裸标记，绝不让 [future_meeting] 泄露给用户。
                removeRanges.add(m.range)
                continue
            }
            // 不论能否建候选，都擦除整段 [future_meeting]{...}（防泄露，与 offline 文本标记一致）。
            removeRanges.add(m.range.first..close)
            val args = runCatching {
                lenientJson.decodeFromString(FutureMeetingArgs.serializer(), response.substring(open, close + 1))
            }.getOrNull() ?: continue
            candidateFrom(args, MeetingSource.FALLBACK)?.let { candidates.add(it) }
        }
        var cleanText = response
        for (range in removeRanges.asReversed()) {
            if (range.last < cleanText.length) cleanText = cleanText.removeRange(range)
        }
        return cleanText.trim() to candidates
    }

    /** 文本暗号降级规则（接进 PromptBuilder 的非工具版，与 [parseProposalMarkers] 格式强耦合）。 */
    const val FALLBACK_MARKER_RULE = """【约定未来见面】
当你和对方在聊天里明确约好了「未来某天」（不是现在立刻）线下见面——你邀请对方且对方答应，或对方提议且你答应——请在这条回复的**最末尾**附上一行标记（对方看不到，App 用它记下约定）：
[future_meeting]{"when_text":"对话里的原话时间，如 周六下午","location":"地点，没有留空","activity":"一起做什么","invitation":"你口吻的一句邀约，可留空","tension_hint":"≤12字给对方看的隐晦暗示，可留空","hidden_tension":"你藏着的小心事，对方不可见，可留空"}
规则：① 只在双方都明确同意时附；「改天约」「有空再说」这种客套**不要**附。② 只用于未来某天；现在立刻见面用线下见面邀约。③ 标记必须是合法 JSON 且放在回复最末尾，正常聊天内容照常写在前面。"""

    // ── 内部 ──

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val MARKER_REGEX = Regex("""\[future_meeting\]""")

    /**
     * 配平花括号扫描（算法单源 [MarkerJson.matchingBraceEnd]·2026-09-06 与 `[promise]` 暗号共用后抽出，行为不变）。
     * 保留本私有转发，[parseProposalMarkers] 的两处调用与 KDoc 引用零改。
     */
    private fun matchingBraceEnd(s: String, open: Int): Int = MarkerJson.matchingBraceEnd(s, open)

    /** 工具参数 / 文本暗号 JSON → 候选（共用）。至少要有「时间或活动」，否则空壳 → null。 */
    private fun candidateFrom(args: FutureMeetingArgs, source: MeetingSource): MeetingCandidate? {
        val activity = args.activity.trim()
        val whenText = args.whenText.trim()
        val iso = args.isoDatetime.trim().ifEmpty { null }
        if (activity.isEmpty() && whenText.isEmpty() && iso == null) return null
        return MeetingCandidate(
            intent = MeetingCandidateIntent.NEW,
            isoDateTime = iso,
            rawWhen = whenText,
            proposedBy = MeetingProposedBy.fromRaw(args.proposedBy),
            source = source,
            location = args.location.trim(),
            activity = activity,
            invitationText = args.invitation.trim(),
            tensionHint = args.tensionHint.trim(),
            hiddenTensionSeed = args.hiddenTension.trim(),
            confidence = MeetingConfidence.HIGH,
        )
    }

    @Serializable
    private data class FutureMeetingArgs(
        @SerialName("when_text") val whenText: String = "",
        @SerialName("iso_datetime") val isoDatetime: String = "",
        val location: String = "",
        val activity: String = "",
        val invitation: String = "",
        @SerialName("tension_hint") val tensionHint: String = "",
        @SerialName("hidden_tension") val hiddenTension: String = "",
        @SerialName("proposed_by") val proposedBy: String = "character",
    )
}
