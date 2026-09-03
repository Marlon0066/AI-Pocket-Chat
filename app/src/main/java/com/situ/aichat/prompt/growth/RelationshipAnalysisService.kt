package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.DateFormatters
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import com.situ.aichat.util.JSONExtractor

// MARK: - 关系评估结果

/** LLM 返回的关系评估结果（解析后）。对齐 iOS `RelationshipAnalysisResult`。 */
data class RelationshipAnalysisResult(
    val changed: Boolean,
    val newRelationship: String,
    val newPhase: String?,    // 关系时期（2-4 字），LLM 未输出时 null
    val reason: String,
)

/** 关系评估错误（对齐 iOS `RelationshipAnalysisError`）。 */
sealed class RelationshipAnalysisError(message: String) : Exception(message) {
    data object NoMessages : RelationshipAnalysisError("没有可分析的消息")
    data class InvalidResponse(val detail: String) : RelationshipAnalysisError("无法解析关系评估结果：$detail")
}

/** 把「关系标签 + 时期」拼成展示串（"恋人 · 蜜月期"）；phase 空时只返回标签。对齐 iOS composeRelationshipDisplay。 */
internal fun composeRelationshipDisplay(name: String, phase: String?): String =
    if (!phase.isNullOrEmpty()) "$name · $phase" else name

/**
 * 1:1 port of iOS `Services/RelationshipAnalysisService.swift`（仅服务部分；写回 = [RelationshipAnalysisCoordinator]）。
 * 无状态：构建关系评估提示词、调用 LLM（经 [ContextLogService.completion] 记录，`temperature=0.2` + json_object）、
 * 解析返回 JSON。DeepSeek 空响应剥 `<think>` 后等 200ms 重试 1 次。同时用于 AI 自动评估和用户手动推进。
 */
@Singleton
class RelationshipAnalysisService @Inject constructor(
    private val contextLog: ContextLogService,
) {

    suspend fun analyzeRelationship(
        messages: List<MessageEntity>,
        characterName: String,
        currentRelationship: String,
        currentPhase: String?,
        quality: RelationshipQuality,
        milestones: List<MilestoneEntity>,
        config: ApiConfigValues,
        userName: String,
    ): RelationshipAnalysisResult {
        if (messages.isEmpty()) throw RelationshipAnalysisError.NoMessages

        val (systemPrompt, userPrompt) = buildAnalysisPrompt(messages, characterName, currentRelationship, currentPhase, quality, milestones, userName)
        val chatMessages = listOf(
            ChatMessageDto(role = "system", content = systemPrompt),
            ChatMessageDto(role = "user", content = userPrompt),
        )

        var response = ""
        for (attempt in 1..2) {
            val buffer = contextLog.completion(
                source = LogSource.RELATIONSHIP_ANALYSIS,
                characterName = characterName,
                config = config,
                messages = chatMessages,
                temperature = 0.2,
                responseFormat = ResponseFormatDto(type = "json_object"),
            )
            val candidate = MemoryService.strippingThinkingTags(buffer)
            if (candidate.isNotEmpty()) {
                response = candidate
                break
            }
            if (attempt < 2) delay(200)
        }

        if (response.isEmpty()) {
            throw RelationshipAnalysisError.InvalidResponse("LLM 返回空内容（重试后仍为空）")
        }
        return parseResponse(response)
    }

    // MARK: - 提示词构建

    // internal（非 private）：供 T2-B1（RelationshipAnalysisServiceTest）直接断言提示词用真名指名——
    // 图纸 §7 明令测 buildAnalysisPrompt，此为其必然推论（CLAUDE.md §3「纯函数设 internal 便于测」）。
    internal fun buildAnalysisPrompt(
        messages: List<MessageEntity>,
        characterName: String,
        currentRelationship: String,
        currentPhase: String?,
        quality: RelationshipQuality,
        milestones: List<MilestoneEntity>,
        userName: String,
    ): Pair<String, String> {
        val resolvedUserName = userName.ifEmpty { "用户" }
        val currentRelationshipDisplay = composeRelationshipDisplay(currentRelationship, currentPhase)

        // 最近 5 条里程碑（列首无缩进，配合下方 trimMargin 的多行插值）
        val recentMilestones = milestones.takeLast(5)
        val milestonesText = if (recentMilestones.isEmpty()) {
            "暂无历史记录"
        } else {
            recentMilestones.joinToString("\n") { m ->
                val dateStr = milestoneDateFormatter.format(Instant.ofEpochMilli(m.establishedDate))
                val relative = DateFormatters.relativeDay(m.establishedDate, System.currentTimeMillis())
                val nameWithPhase = composeRelationshipDisplay(m.relationshipName, m.phase)
                if (relative.isEmpty()) "${dateStr}：${nameWithPhase}（${m.reason}）" else "$dateStr · $relative：${nameWithPhase}（${m.reason}）"
            }
        }

        val qualityText = listOf(
            "- 熟悉度：${quality.familiarity}/100",
            "- 信任感：${quality.trust}/100",
            "- 亲近感：${quality.closeness}/100",
            "- 默契度：${quality.rapport}/100",
            "- 尊重感：${quality.respect}/100",
            "- 趣味性：${quality.funValue}/100",
            "- 张力值：${quality.tension}/100",
            "- 依恋度：${quality.attachment}/100",
        ).joinToString("\n")

        // trimMargin（而非 trimIndent）：qualityText/milestonesText 为多行插值，续行无 "|" 前缀会被原样保留。
        // 「## 关于 reason」一节（+ 关系历史段末的免模仿行 + JSON 示例的 reason 描述）**逐字锁定**：
        // 契约 = 图纸《2026-09-03 关系历程注入根治》§3 件 5a/5b/5c，它修订了《2026-07-14 人称指名统一·图纸一》
        // §9①B1 的旧 reason 描述串（双名字 + 禁「用户」「角色」的原意由件 5a 规矩 2 承接并加强）。
        // 改这几段必须同步 RelationshipAnalysisServiceTest（逐字断言）——两侧互指，勿单改一侧。
        // 注：注入端（[com.situ.aichat.prompt.buildRelationshipMilestoneDescription]）**不设任何校验闸**
        // （用户 2026-09-03 裁决·图纸 §4-C），reason 的文体只在这里从源头管。
        val systemPrompt = """
            |你是一个关系分析师。你的任务是根据对话记录判断${characterName}和${resolvedUserName}之间的关系是否发生了变化。
            |
            |## 角色信息
            |- 角色名：$characterName
            |- 用户名：$resolvedUserName
            |- 当前关系：$currentRelationshipDisplay
            |
            |## 当前关系质感评分（0-100）
            |$qualityText
            |
            |## 关系历史
            |$milestonesText
            |（以上是历史记录。其中旧条目的写法可能不符合下面对 reason 的要求，不必模仿。）
            |
            |## 分析规则
            |1. 关系变化必须有充分的对话证据支持，不能凭猜测
            |2. 日常寒暄不构成关系变化的理由
            |3. 关系变化需要对话中持续的情感表达、深度交流、或关键事件作为支撑
            |4. 关系可以升级也可以降级（比如从恋人变回朋友，甚至变成陌生人）
            |5. 新关系名称应自然合理，不限于传统类型。参考但不限于：陌生人、网友、点头之交、普通朋友、好朋友、死党、损友、暧昧对象、若即若离、暗恋中、恋人、热恋期、冷战中、前任、藕断丝连、复合期、老夫老妻、知己、灵魂伴侣、相爱相杀、互相依赖……
            |6. 关系可以非线性变化——可以跳跃、倒退、反复：刚认识就来电可以直接跳到暧昧（跳过普通朋友）；恋人吵架可以变成冷战中（倒退）；分手后可以复合（再升级）。判断时要参考关系历史，曾经的经历会留下痕迹
            |7. 结合上方的关系质感评分综合判断：评分反映了量化的关系状态，如果评分与当前关系标签明显不符，应考虑调整
            |8. 如果当前关系已经很准确地描述了两人状态，就不要改
            |9. 不要因为${resolvedUserName}说了一句浪漫的话就判定关系升级——需要双方持续的互动模式变化
            |
            |## 关于"时期"（newPhase）
            |除了关系标签，还需输出当前所处的"时期"——一个 2-4 字的短语，刻画此刻关系的氛围。
            |10. **即使关系标签未变**（仍是"恋人"），如果时期发生变化（如从"蜜月期"到"倦怠期"，从"平静期"到"风波期"），也算关系发生变化（changed=true）
            |11. 时期判断结合关系质感数值的趋势：信任/亲近/依恋上升 → 升温/亲近/相依类；张力上升或趣味下降 → 倦怠/距离/疏远类；冲突后回暖 → 复燃/和解/缓和类
            |12. 时期参考词（必须 2-4 字，可在以下范围内选，也可根据语境自创合适的 2-4 字短语）：
            |    · 早期：试探、新鲜、好奇、暧昧、靠近
            |    · 升温：升温、磨合、亲近、依赖
            |    · 稳定：稳固、安宁、平静、相守、默契
            |    · 长期：相依、相伴、深植、相熟
            |    · 张力：倦怠、冷静、距离、疏远、风波
            |    · 修复：和解、复燃、重逢、缓和
            |13. 如果当前关系标签和时期都还准确描述两人状态，返回 changed=false（不要硬变）
            |
            |## 关于 reason（变化原因怎么写）
            |这句话${characterName}本人会读到，也会显示给用户看。要像在说一件真实发生过的事，不是写系统报告。
            |1. 只写**发生了什么**。不要写"关系从 X 变成 Y"、"进入 Y 阶段"——关系变成什么系统另有记录，不用你复述。
            |2. 两个人都用名字：「${characterName}」「${resolvedUserName}」。不要出现"用户""角色""AI""系统"。
            |3. 要具体。写得出细节就写细节；不要写"确认了彼此的心意归属""感情得到升华"这类放在谁身上都成立的空话。
            |4. 一句话，40 字以内。
            |
            |对照：
            |✅ ${characterName}说出了一直没敢提的那件事，${resolvedUserName}没有回避，认真接住了。
            |❌ 双方在信任试探中确认了彼此的心意归属，关系从热恋进入更成熟的坦诚沟通阶段。（全是空话，还在复述关系变化）
            |❌ 对话中涉及多个亲密话题，用户明确提出邀约。（像在写监控日志，还写了"用户"）
            |
            |## 输出格式
            |请严格以 JSON 格式输出，不要包含任何其他文字：
            |如果关系没有变化：
            |{"changed": false}
            |
            |如果关系发生了变化（标签变 / 时期变 / 两者都变 都算变化）：
            |{"changed": true, "newRelationship": "新关系名称", "newPhase": "时期(2-4字)", "reason": "见上节要求：写发生了什么，两人用名字，具体，40 字内"}
        """.trimMargin()

        val conversationText = MemoryService.formatMessages(messages, userLabel = resolvedUserName, charLabel = characterName)
        val userPrompt = "以下是最近的对话记录，请评估关系是否发生变化：\n\n$conversationText"

        return systemPrompt to userPrompt
    }

    // MARK: - JSON 解析

    private fun parseResponse(response: String): RelationshipAnalysisResult {
        val candidates = listOf(response.trim(), JSONExtractor.extract(response))
        var raw: RawRelationshipResponse? = null
        for (candidate in candidates) {
            raw = runCatching { json.decodeFromString(RawRelationshipResponse.serializer(), candidate) }.getOrNull()
            if (raw != null) break
        }
        val parsed = raw ?: throw RelationshipAnalysisError.InvalidResponse("解析失败：所有候选文本均无法解码为有效 JSON")

        val changed = parsed.changed ?: false
        if (!changed) {
            return RelationshipAnalysisResult(changed = false, newRelationship = "", newPhase = null, reason = "")
        }
        val newRelationship = (parsed.newRelationship ?: "").trim()
        if (newRelationship.isEmpty()) {
            throw RelationshipAnalysisError.InvalidResponse("changed=true 但缺少 newRelationship")
        }
        val reason = (parsed.reason ?: "关系自然变化").trim()
        val phaseTrimmed = (parsed.newPhase ?: "").trim()
        val normalizedPhase = if (phaseTrimmed.isEmpty()) null else phaseTrimmed.take(4) // 兜底 LLM 不听话，截 4 字
        return RelationshipAnalysisResult(changed = true, newRelationship = newRelationship, newPhase = normalizedPhase, reason = reason)
    }

    @Serializable
    private data class RawRelationshipResponse(
        val changed: Boolean? = null,
        val newRelationship: String? = null,
        val newPhase: String? = null,
        val reason: String? = null,
    )

    private companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        private val milestoneDateFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy年M月d日").withZone(ZoneId.systemDefault())
    }
}
