package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.offline.OfflineContentParser
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.JSONExtractor
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// MARK: - 成长分析结果

/** LLM 返回的成长分析结果（解析 + 钳位后）。对齐 iOS `GrowthAnalysisResult`。 */
data class GrowthAnalysisResult(
    val personalityChanges: Map<String, Int>,    // dimensionKey → 变化量（已钳 -10..10）
    val relationshipChanges: Map<String, Int>,   // dimensionKey → 变化量（已钳 -5..5）
    val newInterests: List<NewInterest>,         // 新发现的兴趣
    val interestHeatChanges: Map<String, Int>,   // 兴趣名 → 热度变化量（已钳 -15..15）
    val events: List<GrowthEvent>,               // 变化事件
    val narrative: String,                       // 一句话总结
) {
    data class NewInterest(val name: String, val initialHeat: Int)
    data class GrowthEvent(val type: GrowthEventType, val summary: String)
}

/** 成长分析错误（对齐 iOS `GrowthAnalysisError`）。 */
sealed class GrowthAnalysisError(message: String) : Exception(message) {
    data object NoMessages : GrowthAnalysisError("没有可分析的消息")
    data class InvalidResponse(val detail: String) : GrowthAnalysisError("无法解析分析结果：$detail")
}

/**
 * 1:1 port of iOS `Services/GrowthAnalysisService.swift`。无状态：构建分析提示词、调用 LLM（经
 * [ContextLogService.completion] 记录）、解析钳位返回的 JSON。也是「分析用消息收集」的**规范所有者**（结构化记忆
 * 协调器复用，对齐 iOS 让 StructuredMemoryCoordinator 复用 GrowthAnalysisService.collectMessagesForAnalysis）。
 *
 * LLM：`temperature=0.3`、`response_format=json_object`。DeepSeek JSON Output 偶发空响应（官方已知 bug）
 * → 剥 `<think>` 后若空，等 200ms 重试 1 次，仍空抛 [GrowthAnalysisError.InvalidResponse]。
 */
@Singleton
class GrowthAnalysisService @Inject constructor(
    private val contextLog: ContextLogService,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val scheduleDao: ScheduleDao,
) {

    // MARK: - 消息收集（最近 200 条；规范所有者）

    /**
     * 收集角色跨所有会话的最近 [maxMessages] 条非空非 system 消息，按时间升序。
     *
     * **线下见面行剥标签（卷一 B4）**：analysis 查询含见面期消息（`recentForAnalysis` 不滤 isOfflineMode，
     * 且这是永久性的——见面后再跑分析同样读得到），正文带 `[叙述]/[对话]/[场景：…]` 沉浸标签，原样喂给
     * 成长/结构化记忆分析会把「标签本身」当成语言习惯学走。照向量记忆先例只对线下行剥标签（内存副本，
     * 不落库不进注入）；线上行字节不变，条数/顺序不变（**见面轮次照常计入成长**=拍板零碰）。
     */
    suspend fun collectMessagesForAnalysis(characterUuid: String, maxMessages: Int = MAX_MESSAGES): List<MessageEntity> {
        val conversations = conversationDao.getByCharacter(characterUuid)
        val all = conversations.flatMap { messageDao.recentForAnalysis(it.uuid, maxMessages) }
        val sorted = all.sortedBy { it.timestamp }
        val trimmed = if (sorted.size > maxMessages) sorted.takeLast(maxMessages) else sorted
        return trimmed.map { if (it.isOfflineMode) it.copy(content = OfflineContentParser.stripAllTags(it.content)) else it }
    }

    // MARK: - 主入口

    /**
     * 分析对话记录，返回结构化成长变化。[spectrum]/[quality]/[interests] 为**当前（已种子化+淡化）值**，
     * 由协调器传入以反映分析前状态。
     */
    suspend fun analyzeGrowth(
        messages: List<MessageEntity>,
        characterName: String,
        spectrum: PersonalitySpectrum,
        quality: RelationshipQuality,
        interests: List<DynamicInterest>,
        config: ApiConfigValues,
        userName: String,
        scheduleSystemEnabled: Boolean,
        characterUuid: String,
        nowMillis: Long,
    ): GrowthAnalysisResult {
        if (messages.isEmpty()) throw GrowthAnalysisError.NoMessages

        // 成长分析补充材料：最近一周日程活动模式（1:1 iOS buildScheduleAnalysis，仅日程系统开启时查）。
        val scheduleAnalysis = if (scheduleSystemEnabled) {
            val sevenDaysAgo = nowMillis - 7L * 86_400_000L
            buildScheduleAnalysis(scheduleDao.eventsForCharacterSince(characterUuid, sevenDaysAgo), nowMillis)
        } else {
            ""
        }
        val (systemPrompt, userPrompt) = buildAnalysisPrompt(messages, characterName, spectrum, quality, interests, userName, scheduleAnalysis)
        val chatMessages = listOf(
            ChatMessageDto(role = "system", content = systemPrompt),
            ChatMessageDto(role = "user", content = userPrompt),
        )

        // 完整缓冲后解析；DeepSeek JSON Output 空响应后等 200ms 重试 1 次（对齐 iOS）
        var response = ""
        for (attempt in 1..2) {
            val buffer = contextLog.completion(
                source = LogSource.GROWTH_ANALYSIS,
                characterName = characterName,
                config = config,
                messages = chatMessages,
                temperature = 0.3,
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
            throw GrowthAnalysisError.InvalidResponse("LLM 返回空内容（重试后仍为空）")
        }
        return parseAnalysisResponse(response)
    }

    // MARK: - 提示词构建

    // internal（非 private）：供 T2-B1（GrowthAnalysisServiceTest）直接断言提示词用真名指名——
    // 图纸 §7 明令测 buildAnalysisPrompt，此为其必然推论（图纸一 D-1 先例 + CLAUDE.md §3「纯函数设 internal 便于测」）。
    internal fun buildAnalysisPrompt(
        messages: List<MessageEntity>,
        characterName: String,
        spectrum: PersonalitySpectrum,
        quality: RelationshipQuality,
        interests: List<DynamicInterest>,
        userName: String,
        scheduleAnalysis: String,
    ): Pair<String, String> {
        val interestsText = if (interests.isEmpty()) {
            "暂无"
        } else {
            interests.sortedByDescending { it.heat }.joinToString("、") { "${it.name}(热度${it.heat})" }
        }
        val resolvedUserName = userName.ifEmpty { "用户" }

        val systemPrompt = """
            你是一个角色成长分析师。你的任务是分析对话记录，评估角色的性格变化、与${resolvedUserName}的关系发展和兴趣变化。

            ## 角色信息
            - 角色名：$characterName
            - 用户名：$resolvedUserName

            ## 当前性格光谱（0-100）
            - 外向性(extroversion): ${spectrum.extroversion} — 低=内向安静，高=外向活跃
            - 情绪化(emotionality): ${spectrum.emotionality} — 低=冷静理性，高=情感丰富
            - 冒险性(adventurousness): ${spectrum.adventurousness} — 低=保守谨慎，高=冒险大胆
            - 温暖度(warmth): ${spectrum.warmth} — 低=冷淡疏远，高=温暖关怀
            - 幽默感(humor): ${spectrum.humor} — 低=严肃正经，高=风趣幽默
            - 独立性(independence): ${spectrum.independence} — 低=依赖他人，高=独立自主
            - 好奇心(curiosity): ${spectrum.curiosity} — 低=安于现状，高=探索求知
            - 坦诚度(openness): ${spectrum.openness} — 低=含蓄委婉，高=坦率直接

            ## 当前关系质感（0-100）
            - 熟悉度(familiarity): ${quality.familiarity} — 对彼此的了解程度
            - 信任感(trust): ${quality.trust} — 相互信任的深度
            - 亲近感(closeness): ${quality.closeness} — 情感上的亲密程度
            - 默契度(rapport): ${quality.rapport} — 沟通默契和理解力
            - 尊重感(respect): ${quality.respect} — 相互尊重的程度
            - 趣味性(fun): ${quality.funValue} — 相处的愉悦程度
            - 张力值(tension): ${quality.tension} — 关系中的紧张和冲突
            - 依恋度(attachment): ${quality.attachment} — 情感依赖和牵挂

            ## 当前兴趣列表
            $interestsText

            ## 分析规则

            ### 绝对禁止规则（最高优先级）
            - 性别、年龄、生日、姓名等身份属性不属于成长分析范围，绝对不能改变
            - 任何单个维度单次变化绝对不能超过 ±10
            - 性格维度不能出现短期内大幅反转（例如从 20 直接跳到 70 以上）
            - 变化方向必须符合现实逻辑（例如被温暖对待不应该降低温暖度）
            - 没有对话证据支撑的维度一律不改

            ### 性格变化规则
            - 日常对话产生的变化很小：每个维度 ±1~3
            - 重大情感事件（深度倾诉、争吵、重大发现）可以 ±5~8
            - 性格的变化是长期渐进的过程，不会因为一两句话就产生大幅变化
            - 维度越接近极值（0 或 100）变化应越小：80+ 或 20- 时 ±1~2 为宜，中间段（30-70）变化最活跃
            - 没有相关对话内容的维度不要改

            ### 关系变化规则
            - 每次分析关系变化范围为 ±1~5
            - 关系发展是非线性的，可以前进、倒退、反复横跳
            - 维度之间可以矛盾共存（例如高张力+高依恋 = 离不开又痛苦）
            - 不同互动对维度的影响举例（不限于此）：
              · 深度倾诉 → 亲近↑ 信任↑
              · 激烈争吵 → 张力↑，但可能亲近↑（越吵越近）或↓（伤透心），取决于争吵性质
              · 暧昧试探 → 张力↑ 趣味↑ 但信任变化不大
              · 冷处理/已读不回 → 亲近↓ 张力↑ 依恋可能↑（越得不到越想要）
              · 背叛/欺骗 → 信任大幅↓ 但熟悉度不变
              · 日常陪伴 → 熟悉↑ 默契↑，缓慢但稳定
              · 表白/求婚等重大事件 → 多个维度可能同时大幅变化
            - 没有相关互动的维度不要改

            ### 兴趣变化规则
            - 对话中反复讨论某个新话题 → 发现新兴趣（系统自动设初始热度，不需要你控制）
            - 对话中积极讨论已有兴趣 → 给正向变化值 +5~+15
            - 对话中消极评价已有兴趣 → 给负向变化值 -5~-10
            - 如果对话中完全没提到某个兴趣，不要改它的热度
            - interest_heat_changes 的值是**变化量（delta）**，不是绝对值
            - 新兴趣必须是具体的（如"烘焙""日本动漫"），不要太宽泛（如"生活""聊天"）
            - 兴趣名必须是简短的名词或短语（如"手冲咖啡""日本动漫""解剖学"），控制在8字以内；绝不能写成一句话、一段动作或一段心理描述（错误示范："和朋友一起喝手冲咖啡"）

            ## 分析步骤
            请按以下步骤分析：
            1. 通读对话，找出关键时刻（情感波动、重要话题、冲突、深度交流等）
            2. 对每个关键时刻，分析它对性格、关系、兴趣的影响
            3. 综合所有影响，给出最终的变化值

            ## 输出格式
            请严格以 JSON 格式输出，不要包含任何其他文字：
            {
              "personality_changes": {"维度key": 变化值, ...},
              "relationship_changes": {"维度key": 变化值, ...},
              "new_interests": [{"name": "兴趣名"}],
              "interest_heat_changes": {"兴趣名": 变化量, ...},
              "events": [{"type": "personalityShift", "summary": "描述"}],
              "narrative": "一句话总结"
            }

            注意：
            - personality_changes 和 relationship_changes 只包含有变化的维度
            - type 可选值：personalityShift / relationshipChange / interestDiscovered / interestCooled / majorEvent
            - 如果没有明显变化，对应字典可以为空 {}
            - events 至少要有一条，描述最显著的变化
            - events 的 summary 和 narrative 里提到角色和对方时，用「${characterName}」「${resolvedUserName}」的名字，不要写「用户」「角色」
            - 所有文字用中文

            ⚠️ 最后再强调一次（最容易出错）：new_interests 里每个 name 只能是简短的名词或短语，控制在8字以内（如"手冲咖啡""解剖学""日本动漫"），绝对不要填入整句话、动作描述或长句。这是硬性要求，务必遵守。
        """.trimIndent()

        val conversationText = MemoryService.formatMessages(messages, userLabel = resolvedUserName, charLabel = characterName)
        // scheduleAnalysis（最近一周日程活动模式）由 analyzeGrowth 预查日程后传入（1:1 iOS 注入 userPrompt 末尾）。
        val userPrompt = "以下是最近的对话记录，请分析角色的成长变化：\n\n$conversationText$scheduleAnalysis"

        return systemPrompt to userPrompt
    }

    // MARK: - JSON 解析（多候选容错 + 钳位）

    private fun parseAnalysisResponse(response: String): GrowthAnalysisResult {
        val candidates = listOf(response.trim(), JSONExtractor.extract(response))
        var raw: RawAnalysisResponse? = null
        for (candidate in candidates) {
            raw = runCatching { json.decodeFromString(RawAnalysisResponse.serializer(), candidate) }.getOrNull()
            if (raw != null) break
        }
        val parsed = raw ?: throw GrowthAnalysisError.InvalidResponse("解析失败：所有候选文本均无法解码为有效 JSON")

        // 钳位性格变化值（-10 ~ +10），只接受合法 dimensionKey
        val validPersonalityKeys = PersonalitySpectrum.DIMENSION_KEYS.toSet()
        val personalityChanges = (parsed.personality_changes ?: emptyMap())
            .filterKeys { it in validPersonalityKeys }
            .mapValues { it.value.coerceIn(-10, 10) }

        // 钳位关系变化值（-5 ~ +5）
        val validRelationshipKeys = RelationshipQuality.DIMENSION_KEYS.toSet()
        val relationshipChanges = (parsed.relationship_changes ?: emptyMap())
            .filterKeys { it in validRelationshipKeys }
            .mapValues { it.value.coerceIn(-5, 5) }

        // 新兴趣：trim 非空，统一初始热度 40
        val newInterests = (parsed.new_interests ?: emptyList()).mapNotNull { item ->
            val name = item.name.trim()
            if (name.isEmpty()) null else GrowthAnalysisResult.NewInterest(name = name, initialHeat = 40)
        }

        // 兴趣热度变化（delta -15 ~ +15）；旧格式（全部 >15 = 绝对值格式）整批丢弃
        val rawHeatChanges = parsed.interest_heat_changes ?: emptyMap()
        val isLegacyFormat = rawHeatChanges.isNotEmpty() && rawHeatChanges.values.all { it > 15 }
        val interestHeatChanges = if (isLegacyFormat) {
            emptyMap()
        } else {
            rawHeatChanges.mapValues { it.value.coerceIn(-15, 15) }
        }

        // 成长事件：trim summary 非空，type 非法回落 majorEvent
        val events = (parsed.events ?: emptyList()).mapNotNull { item ->
            val summary = item.summary.trim()
            if (summary.isEmpty()) null else GrowthAnalysisResult.GrowthEvent(type = GrowthEventType.fromRaw(item.type), summary = summary)
        }

        return GrowthAnalysisResult(
            personalityChanges = personalityChanges,
            relationshipChanges = relationshipChanges,
            newInterests = newInterests,
            interestHeatChanges = interestHeatChanges,
            events = events,
            narrative = parsed.narrative ?: "无明显变化",
        )
    }

    /** 与 LLM 输出 JSON 一一对应的原始结构（snake_case，全可选容错；对齐 iOS RawAnalysisResponse）。 */
    @Serializable
    private data class RawAnalysisResponse(
        val personality_changes: Map<String, Int>? = null,
        val relationship_changes: Map<String, Int>? = null,
        val new_interests: List<RawNewInterest>? = null,
        val interest_heat_changes: Map<String, Int>? = null,
        val events: List<RawGrowthEvent>? = null,
        val narrative: String? = null,
    ) {
        @Serializable
        data class RawNewInterest(val name: String = "", val initial_heat: Int? = null)

        @Serializable
        data class RawGrowthEvent(val type: String = "", val summary: String = "")
    }

    private companion object {
        const val MAX_MESSAGES = 200
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    }
}

// MARK: - 日程活动模式补充材料（internal 纯函数，便于单测；1:1 iOS GrowthAnalysisService.buildScheduleAnalysis:299-361）

private const val SCHEDULE_EVENT_TYPE_USER_INTERACTION = "userInteraction"

/**
 * 从角色近一周日程事件汇总「最近一周的日常活动模式」补充材料（1:1 iOS GrowthAnalysisService.swift:299-361）：
 * 滤掉 userInteraction 事件 → 统计活动出现次数取 Top5（次数降序、同次数按活动名升序）→ 列出近 2 天新出现的
 * 活动 → 拼成注入成长分析 userPrompt 末尾的段落。无可用事件 → 空串（不注入）。
 *
 * @param events 该角色近 7 天（schedule.date >= now-7d）的全部日程事件
 * @param nowMillis 当前时刻（用于划「近 2 天」新活动窗口）
 */
internal fun buildScheduleAnalysis(events: List<ScheduleEventEntity>, nowMillis: Long): String {
    val relevant = events.filter { it.eventTypeRaw != SCHEDULE_EVENT_TYPE_USER_INTERACTION }
    if (relevant.isEmpty()) return ""

    val activityCount = HashMap<String, Int>()
    for (event in relevant) {
        activityCount[event.activity] = (activityCount[event.activity] ?: 0) + 1
    }
    // 次数降序；同次数按活动名升序（1:1 iOS sorted { if ==value return key< else value> }）
    val topActivities = activityCount.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(5)

    val lines = mutableListOf("", "【最近一周的日常活动模式】")
    for ((activity, count) in topActivities) {
        lines.add("- $activity（${count}次）")
    }

    val twoDaysAgo = nowMillis - 2L * 86_400_000L
    val recentActivities = relevant.filter { it.startTime >= twoDaysAgo }.map { it.activity }.toSet()
    val olderActivities = relevant.filter { it.startTime < twoDaysAgo }.map { it.activity }.toSet()
    val newActivities = (recentActivities - olderActivities).sorted()
    if (newActivities.isNotEmpty()) {
        lines.add("最近新出现的活动：${newActivities.joinToString("、")}")
    }

    lines.add("请参考这些活动模式来分析角色的兴趣变化和成长趋势。")
    return lines.joinToString("\n")
}
