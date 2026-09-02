package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.PRESSURE_DELTA_MAX
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

// MARK: - 成长分析结果

/** LLM 返回的成长分析结果（解析 + 钳位后）。对齐 iOS `GrowthAnalysisResult`。 */
data class GrowthAnalysisResult(
    val personalityChanges: Map<String, Int>,    // dimensionKey → 变化量（已钳 -10..10）
    val relationshipChanges: Map<String, PressureDelta>, // dimensionKey → 本次新增的正负两股力（各已钳 0..5）
    val newInterests: List<NewInterest>,         // 新发现的兴趣
    val interestHeatChanges: Map<String, Int>,   // 兴趣名 → 热度变化量（已钳 -15..15）
    val events: List<GrowthEvent>,               // 变化事件
    val narrative: String,                       // 一句话总结
    /** 卷三 §3.3：27 项增益 key 的命中，已过滤 ∈ GAIN_KEYS、去重、≤ [GrowthAnalysisService.GAIN_HITS_MAX]；缺席即空。 */
    val gainHits: List<String> = emptyList(),
    /** 卷三 §3.3：专属敏感点命中，label 已对上传入的清单；缺席即空。 */
    val customHits: List<CustomHit> = emptyList(),
    /** 卷四 §3.5：层 ② 意图判定 `intent_status`（key = `IntentKind.key` → open / expressed / resolved，已过滤）；缺席即空。 */
    val intentStatus: Map<String, String> = emptyMap(),
    /** 修缮卷 🔵-1：`gain_hits` 里归一后仍认不出的项数（非字符串 / 不在 GAIN_KEYS）——只上观测行，不参与数值。 */
    val droppedHits: Int = 0,
) {
    /**
     * 某维**这一次**新增的两股力（活人感内核·卷二 §3.3）。各自 `0..PRESSURE_DELTA_MAX`，都是增量不是总量。
     *
     * 取代旧的单个净额值——旧结构每维只能报一个数，「说了很多心里话(+3) 但有句话让她介意(-2)」
     * 在分析 AI 开口之前就已经被相抵成 `+1`，那两股力再也找不回来了。
     */
    data class PressureDelta(val pos: Int, val neg: Int)

    /** 专属敏感点命中（卷三 §3.3）：[label] 已对上人设增益 custom 清单（忽略大小写 / 首尾空白），[positive] = tone `pos`。 */
    data class CustomHit(val label: String, val positive: Boolean)

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
        return strippingOfflineTags(trimmed)
    }

    /**
     * 成长分析取材窗口（活人感内核卷零 §3.4）：**按轮切**而不是按条切。
     *
     * **为什么换**：旧口径「最近 200 条」与「上次分析到哪」无关 —— 用户把「AI 回复条数」设成 1 时
     * 200 条 ≈ 100 轮，设成 6 时 ≈ 29 轮，同样的相处被记的分差着好几倍；且一条轮次线以外的旧内容
     * 会被反复计分、新内容却可能挤不进窗口。改「轮」后取材与设置解耦（回归钉 = 本卷 T1-7）。
     *
     * 窗口 = 「[sinceMillis] 之后的全部消息」（[fresh]，超 [WINDOW_MAX_MESSAGES] 截断保留最近）
     * ∪ 「之前的最后 [WINDOW_LEAD_IN_ROUNDS] 轮」（[AnalysisWindow.leadIn]，只供理解语境）。
     * [sinceMillis] 为 null（首次分析）⇒ 全部消息进 fresh、leadIn 空。
     *
     * 两段各自过**与 [collectMessagesForAnalysis] 同一个**剥标签 helper（线下见面行行为完全一致）。
     */
    suspend fun collectAnalysisWindow(characterUuid: String, sinceMillis: Long?): AnalysisWindow {
        val raw = messageDao.recentForCharacterAnalysis(characterUuid, WINDOW_MAX_MESSAGES + WINDOW_FETCH_SLACK)
        val sorted = raw.sortedBy { it.timestamp }
        if (sinceMillis == null) {
            val firstFresh = if (sorted.size > WINDOW_MAX_MESSAGES) sorted.takeLast(WINDOW_MAX_MESSAGES) else sorted
            return AnalysisWindow(leadIn = emptyList(), fresh = strippingOfflineTags(firstFresh))
        }
        val fresh = sorted.filter { it.timestamp > sinceMillis }
        val older = sorted.filter { it.timestamp <= sinceMillis }
        val leadIn = lastNRounds(older, WINDOW_LEAD_IN_ROUNDS)
        val trimmedFresh = if (fresh.size > WINDOW_MAX_MESSAGES) fresh.takeLast(WINDOW_MAX_MESSAGES) else fresh
        return AnalysisWindow(leadIn = strippingOfflineTags(leadIn), fresh = strippingOfflineTags(trimmedFresh))
    }

    /**
     * 线下见面行剥标签（**[collectMessagesForAnalysis] 与 [collectAnalysisWindow] 的共用单源**）：
     * 只对线下行做内存副本改写，线上行字节不变、条数与顺序不变。
     */
    private fun strippingOfflineTags(messages: List<MessageEntity>): List<MessageEntity> =
        messages.map { if (it.isOfflineMode) it.copy(content = OfflineContentParser.stripAllTags(it.content)) else it }

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
        leadInMessageCount: Int = 0,
        /** 卷三 §3.3：角色专属敏感点标签（`personaGains.custom` 的 label），提示词列出 + 解析端按它对标签。默认空 = 旧行为。 */
        customGainLabels: List<String> = emptyList(),
        /** 卷四 §3.5：层 ② 意图段（[IntentStatusParsing.section] 产物），追加到 user 框末尾；默认空 = 不追加、旧行为逐字节不变。 */
        intentSection: String = "",
    ): GrowthAnalysisResult {
        if (messages.isEmpty()) throw GrowthAnalysisError.NoMessages

        // 成长分析补充材料：最近一周日程活动模式（1:1 iOS buildScheduleAnalysis，仅日程系统开启时查）。
        val scheduleAnalysis = if (scheduleSystemEnabled) {
            val sevenDaysAgo = nowMillis - 7L * 86_400_000L
            buildScheduleAnalysis(scheduleDao.eventsForCharacterSince(characterUuid, sevenDaysAgo), nowMillis)
        } else {
            ""
        }
        val (systemPrompt, userPrompt) = buildAnalysisPrompt(messages, characterName, spectrum, quality, interests, userName, scheduleAnalysis, leadInMessageCount, customGainLabels, intentSection)
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
        return parseAnalysisResponse(response, customGainLabels)
    }

    // MARK: - 提示词构建

    // internal（非 private）：供 T2-B1（GrowthAnalysisServiceTest）直接断言提示词用真名指名——
    // 图纸 §7 明令测 buildAnalysisPrompt，此为其必然推论（图纸一 D-1 先例 + CLAUDE.md §3「纯函数设 internal 便于测」）。
    /**
     * [leadInMessageCount]：[messages] 开头有多少条属于「上次已计过分的前置上下文」（活人感内核卷零 §3.4）。
     * **默认 0 = 逐字节回退到旧行为**（不切段、不输出标注行），老调用方与既有测试零影响。
     */
    internal fun buildAnalysisPrompt(
        messages: List<MessageEntity>,
        characterName: String,
        spectrum: PersonalitySpectrum,
        quality: RelationshipQuality,
        interests: List<DynamicInterest>,
        userName: String,
        scheduleAnalysis: String,
        leadInMessageCount: Int = 0,
        customGainLabels: List<String> = emptyList(),
        intentSection: String = "",
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
            - 每次分析关系变化：正向、负向各 0~5，**分开报**（格式见下）
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
            - **关系变化要正负分开报**：同一次相处里往往两股力同时在推——比如「说了很多心里话」推正向、
              「有句话让她介意」推负向。**不要把它们相抵成一个数**，而是各报各的（没有那一侧就写 0）。
            - pos / neg 各自范围 0~5，都是**这一次**新增的力，不是总量。

            $SENSITIVITY_SECTION_MARKER

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
              "relationship_changes": {"维度key": {"pos": 正向压强, "neg": 负向压强}, ...},
              "new_interests": [{"name": "兴趣名"}],
              "interest_heat_changes": {"兴趣名": 变化量, ...},
              "gain_hits": ["g04", "g13"],
              "custom_hits": [{"label": "怕黑", "tone": "neg"}],
              "intent_status": {"意图key": "open 或 expressed 或 resolved", ...},
              "events": [{"type": "personalityShift", "summary": "描述"}],
              "narrative": "一句话总结"
            }

            注意：
            - personality_changes 和 relationship_changes 只包含有变化的维度
            - type 可选值：personalityShift / relationshipChange / interestDiscovered / interestCooled / majorEvent
            - 如果没有明显变化，对应字典可以为空 {}
            - events 至少要有一条，描述最显著的变化
            - events 的 summary 和 narrative 里提到角色和对方时，用「${characterName}」「${resolvedUserName}」的名字，不要写「用户」「角色」
            - intent_status 只对「${characterName}${IntentStatusParsing.SECTION_KEYWORD}」段里列出的意图作答，key 用每行末尾方括号里的英文；没有列出就写 {}
            - 所有文字用中文

            ⚠️ 最后再强调一次（最容易出错）：new_interests 里每个 name 只能是简短的名词或短语，控制在8字以内（如"手冲咖啡""解剖学""日本动漫"），绝对不要填入整句话、动作描述或长句。这是硬性要求，务必遵守。
        """.trimIndent().replace(SENSITIVITY_SECTION_MARKER, buildSensitivitySection(customGainLabels))

        val conversationText = buildConversationText(messages, leadInMessageCount, resolvedUserName, characterName)
        // scheduleAnalysis（最近一周日程活动模式）由 analyzeGrowth 预查日程后传入（1:1 iOS 注入 userPrompt 末尾）。
        // 卷四 §3.5（K-17）：层 ② 意图段挂在 user 框最末（对话记录 + 日程模式之后）；空 ⇒ 逐字节回退旧行为。
        val userPrompt = "以下是最近的对话记录，请分析角色的成长变化：\n\n$conversationText$scheduleAnalysis" +
            if (intentSection.isEmpty()) "" else "\n\n$intentSection"

        return systemPrompt to userPrompt
    }

    // MARK: - JSON 解析（多候选容错 + 钳位）

    // internal（非 private）：图纸 §7.2 T1-6 明令测「双形状解析 + 非法 key 丢弃」，而 Kotlin private 对同模块
    // 测试不可见 ⇒ 提可见性是该要求的必然推论（卷零 D-1 先例）。函数体逐字未动。
    internal fun parseAnalysisResponse(response: String, customGainLabels: List<String> = emptyList()): GrowthAnalysisResult {
        val candidates = listOf(response.trim(), JSONExtractor.extract(response))
        var raw: RawAnalysisResponse? = null
        for (candidate in candidates) {
            raw = runCatching { json.decodeFromString(RawAnalysisResponse.serializer(), candidate) }.getOrNull()
            if (raw != null) break
        }
        val parsed = raw ?: throw GrowthAnalysisError.InvalidResponse("解析失败：所有候选文本均无法解码为有效 JSON")

        // 钳位性格变化值（-10 ~ +10），只接受合法 dimensionKey；修缮卷 D-9：值按 intLenient 宽松取整，取不出的项丢弃、不判废
        val validPersonalityKeys = PersonalitySpectrum.DIMENSION_KEYS.toSet()
        val personalityChanges = (parsed.personality_changes ?: emptyMap())
            .filterKeys { it in validPersonalityKeys }
            .mapNotNull { (key, element) -> element.intLenient()?.let { key to it.coerceIn(-10, 10) } }
            .toMap()

        // 关系变化：双形状解析 + 各钳 [0, PRESSURE_DELTA_MAX]（图纸 §3.3 表）
        val validRelationshipKeys = RelationshipQuality.DIMENSION_KEYS.toSet()
        val relationshipChanges = (parsed.relationship_changes ?: emptyMap())
            .filterKeys { it in validRelationshipKeys }
            .mapNotNull { (key, element) -> parsePressureDelta(element)?.let { key to it } }
            .toMap()

        // 新兴趣：trim 非空，统一初始热度 40
        val newInterests = (parsed.new_interests ?: emptyList()).mapNotNull { item ->
            val name = item.name.trim()
            if (name.isEmpty()) null else GrowthAnalysisResult.NewInterest(name = name, initialHeat = 40)
        }

        // 兴趣热度变化（delta -15 ~ +15）；旧格式（全部 >15 = 绝对值格式）整批丢弃；值同样 intLenient 宽松取整（D-9）
        val rawHeatChanges = (parsed.interest_heat_changes ?: emptyMap())
            .mapNotNull { (name, element) -> element.intLenient()?.let { name to it } }
            .toMap()
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

        // 卷三 §3.3：敏感点命中——两字段缺席 / 非法形状一律空列表，**绝不抛**（按 JsonElement 收再自解，坏形状不拖垮整份分析）。
        // 修缮卷 🔵-1：`gain_hits` 前缀归一（`G04` / `g4` / `g13 吵架 · 被凶` ⇒ `g04` / `g13`）并计丢弃数上观测行。
        val gainHits = parseGainHits(parsed.gain_hits)
        val customHits = parseCustomHits(parsed.custom_hits, customGainLabels)

        return GrowthAnalysisResult(
            personalityChanges = personalityChanges,
            relationshipChanges = relationshipChanges,
            newInterests = newInterests,
            interestHeatChanges = interestHeatChanges,
            events = events,
            narrative = parsed.narrative ?: "无明显变化",
            gainHits = gainHits.hits,
            customHits = customHits,
            droppedHits = gainHits.dropped,
            // 卷四 §3.5：意图判定同样按 JsonElement 收再自解，坏形状 ⇒ 空 map、绝不抛（E37）。
            intentStatus = IntentStatusParsing.parse(parsed.intent_status),
        )
    }

    /**
     * 把 `relationship_changes` 的一个值解析成 [GrowthAnalysisResult.PressureDelta]，**同时认两种形状**（图纸 P-5）：
     *
     * | 输入 | 结果 |
     * |---|---|
     * | `{"pos": 3, "neg": 2}`（新） | `pos=3, neg=2`（各钳 `[0,5]`） |
     * | `3`（旧·正数） | `pos=3, neg=0` |
     * | `-2`（旧·负数） | `pos=0, neg=2`（按符号拆） |
     * | 其它（字符串 / 数组 / null） | `null` ⇒ 该维丢弃 |
     *
     * 认旧形状不是过渡期妥协：模型不会 100% 听话，回落到旧形状时行为**不劣于现状**，且老日志、老响应可重放。
     * ⚠️ 生成端（`buildAnalysisPrompt` 的「## 输出格式」段）与本解析端在**同一个类**里，改任一侧必须同时改
     * 另一侧，并跑格式锁测试 `PressureParseTest`（图纸 §6.1）。
     */
    private fun parsePressureDelta(element: JsonElement): GrowthAnalysisResult.PressureDelta? {
        (element as? JsonObject)?.let { obj ->
            // 修缮卷 D-13：子键值经 intLenient（小数 / 数字串也认）
            val pos = obj["pos"].intLenient() ?: 0
            val neg = obj["neg"].intLenient() ?: 0
            return GrowthAnalysisResult.PressureDelta(
                pos = pos.coerceIn(0, PRESSURE_DELTA_MAX),
                neg = neg.coerceIn(0, PRESSURE_DELTA_MAX),
            )
        }
        val single = element.intLenient() ?: return null
        return if (single >= 0) {
            GrowthAnalysisResult.PressureDelta(pos = single.coerceAtMost(PRESSURE_DELTA_MAX), neg = 0)
        } else {
            GrowthAnalysisResult.PressureDelta(pos = 0, neg = (-single).coerceAtMost(PRESSURE_DELTA_MAX))
        }
    }

    /** 与 LLM 输出 JSON 一一对应的原始结构（snake_case，全可选容错；对齐 iOS RawAnalysisResponse）。 */
    @Serializable
    private data class RawAnalysisResponse(
        /** 修缮卷 D-9：按 JsonElement 收（`2.6` / `"3"` / `null` / `{pos,neg}` 都不许拖垮整份），[intLenient] 逐项自解。 */
        val personality_changes: Map<String, JsonElement>? = null,
        /** 双形状（图纸 P-5）：新 `{"pos":3,"neg":2}` 与旧 `3` 都要认，故按 JsonElement 收再自解。 */
        val relationship_changes: Map<String, JsonElement>? = null,
        val new_interests: List<RawNewInterest>? = null,
        /** 修缮卷 D-9：同 personality_changes。 */
        val interest_heat_changes: Map<String, JsonElement>? = null,
        val events: List<RawGrowthEvent>? = null,
        val narrative: String? = null,
        /** 卷三：按 JsonElement 收（字符串数组之外的形状不许拖垮整份解析），[parseGainHits] 自解。 */
        val gain_hits: JsonElement? = null,
        /** 卷三：同上，[parseCustomHits] 自解。 */
        val custom_hits: JsonElement? = null,
        /**
         * 卷四 §3.5：层 ② 意图判定，[IntentStatusParsing.parse] 自解（只认对象·key ∈ IntentKind·值 ∈ open/expressed/resolved）。
         * ⚠️ 与 `buildAnalysisPrompt`「## 输出格式」里的 `"intent_status"` 行 + 注意行是同一对生成/解析（图纸 §6）：改任一侧必须同改另一侧，
         * 格式锁 = `IntentStatusFormatLockTest`。
         */
        val intent_status: JsonElement? = null,
    ) {
        @Serializable
        data class RawNewInterest(val name: String = "", val initial_heat: Int? = null)

        @Serializable
        data class RawGrowthEvent(val type: String = "", val summary: String = "")
    }

    internal companion object {
        const val MAX_MESSAGES = 200

        /** 窗口前置上下文轮数（活人感内核纪要附录 A.2）。 */
        internal const val WINDOW_LEAD_IN_ROUNDS = 4

        /** 窗口消息硬上限（防久别爆聊一次灌爆上下文；纪要附录 A.5-2）。 */
        internal const val WINDOW_MAX_MESSAGES = 300

        /** 卷三 §3.3：一次分析最多认多少个增益命中（提示词说「通常 0~3」，8 是解析端的硬帽）。 */
        internal const val GAIN_HITS_MAX = 8

        /** 提示词里占一整行的替换标记，见 [buildSensitivitySection]。 */
        private const val SENSITIVITY_SECTION_MARKER = "@@SENSITIVITY_SECTION@@"

        /** 取数余量：4 轮前置最多 4×7=28 条，取 40 留裕度。 */
        private const val WINDOW_FETCH_SLACK = 40

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
