package com.situ.aichat.prompt.persona

import com.situ.aichat.data.model.CustomGain
import com.situ.aichat.data.model.PersonaGains
import com.situ.aichat.data.model.PersonaOperator
import com.situ.aichat.data.model.PersonaVocab
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.growth.bytesToLowerHex
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.JSONExtractor
import kotlinx.coroutines.delay
import com.situ.aichat.prompt.growth.intLenient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 编译输入（角色的人设面，**不含对话记录**——编译的是人设，不是相处）。 */
data class PersonaCompileInput(
    val name: String,
    val personalityDescription: String,
    val occupation: String = "",
    val backstory: String = "",
    val speakingStyle: String = "",
    val catchphrases: String = "",
)

/**
 * 一次编译的产物（已过封闭词表校验与钳位）。[droppedCount] = 被丢弃的越界条目数（图纸 Y-6：绝不静默吞）。
 */
data class PersonaCompileResult(
    /** **只含 LLM 真给出的维度**——缺席的维度不强填，落库时保持 50。 */
    val anchors: Map<String, Int>,
    /** 维度 key → 依据短语（≤24 字）；缺席即该维度不显示依据行。 */
    val basis: Map<String, String>,
    val gains: PersonaGains,
    val operators: List<PersonaOperator>,
    val droppedCount: Int,
    /** 一句话总结，**仅进 Logcat，不落库不上屏**（图纸 §3.4）。 */
    val notes: String,
)

/**
 * 人设文本指纹（图纸 Y-5）：SHA-256 前 16 位十六进制，**参与的文本仅 `personalityDescription` 一个字段**
 * ——D-2 的提醒条只针对「性格描述」，纳入职业/背景会让改一句背景就误报。
 *
 * 包级纯函数：编译落库端（[PersonaCompileService.personaHash]）与 UI 的 D-2 判据共用**同一处实现**，
 * 两边各算一遍必然漂移。
 */
internal fun personaTextHash(personalityDescription: String): String =
    bytesToLowerHex(MessageDigest.getInstance("SHA-256").digest(personalityDescription.toByteArray(Charsets.UTF_8)))
        .take(PERSONA_HASH_LENGTH)

/** hash 取 SHA-256 前 16 位十六进制（图纸 §9.2 锁定值）。 */
private const val PERSONA_HASH_LENGTH = 16

/** 编译失败（走 D-5 路径：数值一个字节不动，只置提示态）。 */
sealed class PersonaCompileError(message: String) : Exception(message) {
    data object EmptyPersona : PersonaCompileError("人设为空，不发起调用")
    data class InvalidResponse(val detail: String) : PersonaCompileError("无法解析编译结果：$detail")
}

/**
 * 人设编译器（活人感内核·卷一图纸 §3.4）：把用户写的人设自由文本编译成**锚点 / 增益 / 算子**三样机器可用的产物。
 *
 * 无状态：构建提示词 → 调 LLM（经 [ContextLogService.completion] 记账）→ **宽进严出**解析。「宽进」= JSON 解码开
 * `ignoreUnknownKeys`；「严出」= 逐项校验，**落在封闭词表 [PersonaVocab] 外的条目整条丢弃并计数**（Y-6）
 * ——静默吞会让「编译质量差」无从察觉。LLM 参数照
 * [com.situ.aichat.prompt.growth.GrowthAnalysisService] 现值：`temperature=0.3` + `response_format=json_object`；
 * DeepSeek JSON Output 偶发空响应 → 剥 `<think>` 后若空，等 200ms 重试 1 次。
 *
 * ⚠️ **输出 JSON 的键名与本文件的解析端是同一处契约**：改 [buildCompilePrompt] 里的 schema 示例必须同步改
 * [RawCompileResponse] 与 [parseCompileResponse]，否则模型照旧输出、解析端整份丢弃（`PersonaCompileParseTest` 钉）。
 */
@Singleton
class PersonaCompileService @Inject constructor(
    private val contextLog: ContextLogService,
) {

    /** 修缮卷 D-12：加 `coerceInputValues`（`null` 回默认）；数值字段一律按 JsonElement 收再 [intLenient] 逐项自解，坏一项丢一项、不判废整份。 */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** 人设文本指纹（转发包级 [personaTextHash]·单源）。 */
    fun personaHash(personalityDescription: String): String = personaTextHash(personalityDescription)

    /**
     * 编译一遍。人设为空直接抛 [PersonaCompileError.EmptyPersona]（**零 LLM 调用**·Y-E1）。
     * [systemGainLabels] = 27 项的当前语言标签，供 custom 查重（Y-E8）；由协调器解析资源后传入
     * ——本服务刻意不碰 Context，保持纯逻辑可直测。
     */
    suspend fun compile(
        input: PersonaCompileInput,
        config: ApiConfigValues,
        systemGainLabels: Set<String> = emptySet(),
    ): PersonaCompileResult {
        if (input.personalityDescription.isBlank()) throw PersonaCompileError.EmptyPersona

        val (systemPrompt, userPrompt) = buildCompilePrompt(input)
        val chatMessages = listOf(
            ChatMessageDto(role = "system", content = systemPrompt),
            ChatMessageDto(role = "user", content = userPrompt),
        )

        var response = ""
        for (attempt in 1..2) {
            val buffer = contextLog.completion(
                source = LogSource.PERSONA_COMPILE,
                characterName = input.name,
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
            if (attempt < 2) delay(EMPTY_RETRY_DELAY_MS)
        }
        if (response.isEmpty()) throw PersonaCompileError.InvalidResponse("LLM 返回空内容（重试后仍为空）")
        return parseCompileResponse(response, systemGainLabels)
    }

    // MARK: - 提示词

    // internal（非 private）：供 PersonaCompileParseTest 直接构造断言（CLAUDE.md §3「纯函数设 internal 便于测」）。
    internal fun buildCompilePrompt(input: PersonaCompileInput): Pair<String, String> {
        val systemPrompt = """
            你是一个人设编译器。你要读一遍作者写的角色设定，把它翻译成三样机器可用的数据：本性数值、敏感点、固定反应。
            你不是在写故事，也不是在评价角色——只做翻译，读不出来的就留空，**不要编**。

            ## 输出格式（严格 JSON，不要任何解释文字）
            {
              "anchors": {"extroversion": 30, "warmth": 25, "humor": 70},
              "anchor_basis": {"warmth": "表面高冷、说话不留情面", "humor": "毒舌"},
              "gains": {"g02": 2, "g04": 0, "g25": 2},
              "custom_gains": [{"label": "被叫全名", "level": 2}],
              "operators": [{"condition": "c01", "action": "a01"}],
              "notes": "一句话总结"
            }

            ## anchors：本性数值（0-100，只写你**真能从人设里读出来**的维度，读不出的整个略去）
            - extroversion 外向性：低=内向安静，高=外向活跃
            - emotionality 情绪化：低=冷静理性，高=情感丰富
            - adventurousness 冒险性：低=保守谨慎，高=冒险大胆
            - warmth 温暖度：低=冷淡疏远，高=温暖关怀
            - humor 幽默感：低=严肃正经，高=风趣幽默
            - independence 独立性：低=依赖他人，高=独立自主
            - curiosity 好奇心：低=安于现状，高=探索求知
            - openness 坦诚度：低=含蓄委婉，高=坦率直接

            ## anchor_basis：每个数值的依据（可选）
            键与 anchors 相同，值是**人设原文里的那几个字**（不超过 24 字），让作者一眼看出你为什么这么打分。

            ## gains：她对什么事格外敏感 / 格外无感（只写与常人不同的项，其余略去）
            一个真实的人，一定有几件别人很在乎、她却无所谓的事。这类「不吃这套」和敏感项同样重要，别只挑敏感的写——只写敏感项等于说她对什么都敏感，那就等于没写。
            档位：0=不吃这套，2=很敏感（1=正常，不用写）。合法 key 只有下面这些，写别的会被丢弃：
            $GAIN_KEY_HINT

            ## custom_gains：人设里读出的、上面 27 项装不下的专属敏感点（没有就给空数组）
            label 不超过 12 字，写**能从聊天里看出来的事**；level 同上三档。

            ## operators：她的固定反应（条件 → 动作，没有就给空数组）
            合法 condition：$CONDITION_KEY_HINT
            合法 action：$ACTION_KEY_HINT
            同一个 condition 只写一条，最多 ${PersonaVocab.MAX_OPERATORS} 条。

            ## 底线
            - 人设里没写的，不要推断出一个「大众平均值」——略去比编造好。
            - 只输出 JSON。
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("角色名：${input.name}")
            appendLine()
            appendLine("性格描述：")
            appendLine(input.personalityDescription)
            if (input.occupation.isNotBlank()) { appendLine(); appendLine("职业：${input.occupation}") }
            if (input.backstory.isNotBlank()) { appendLine(); appendLine("背景故事："); appendLine(input.backstory) }
            if (input.speakingStyle.isNotBlank()) { appendLine(); appendLine("说话风格：${input.speakingStyle}") }
            if (input.catchphrases.isNotBlank()) { appendLine(); appendLine("口头禅：${input.catchphrases}") }
        }.trimEnd()

        return systemPrompt to userPrompt
    }

    // MARK: - 解析与校验（严出）

    @Serializable
    private data class RawCustomGain(val label: String = "", val level: JsonElement? = null)

    @Serializable
    private data class RawOperator(val condition: String = "", val action: String = "")

    @Serializable
    private data class RawCompileResponse(
        val anchors: Map<String, JsonElement>? = null,
        val anchor_basis: Map<String, String>? = null,
        val gains: Map<String, JsonElement>? = null,
        val custom_gains: List<RawCustomGain>? = null,
        val operators: List<RawOperator>? = null,
        val notes: String? = null,
    )

    /**
     * 逐字段校验（图纸 §3.4 锁定表）。三者（anchors / gains / operators）**全空判失败**
     * ——防「成功但什么都没生成」的假成功（Y-E10）。[systemGainLabels] 为空即跳过 custom 查重那一道。
     */
    internal fun parseCompileResponse(response: String, systemGainLabels: Set<String> = emptySet()): PersonaCompileResult {
        val candidates = listOf(response.trim(), JSONExtractor.extract(response))
        var raw: RawCompileResponse? = null
        for (candidate in candidates) {
            raw = runCatching { json.decodeFromString(RawCompileResponse.serializer(), candidate) }.getOrNull()
            if (raw != null) break
        }
        val parsed = raw ?: throw PersonaCompileError.InvalidResponse("所有候选文本均无法解码为有效 JSON")
        var dropped = 0

        // anchors：键必须是合法维度，值钳 [0,100]；缺席的维度不强填；值取不出整数（null / 非数字）⇒ 丢该维计数（D-12）。
        val validDimensions = PersonalitySpectrum.DIMENSION_KEYS.toSet()
        val rawAnchors = parsed.anchors ?: emptyMap()
        dropped += rawAnchors.keys.count { it !in validDimensions }
        val anchors = LinkedHashMap<String, Int>()
        for ((key, element) in rawAnchors) {
            if (key !in validDimensions) continue
            val value = element.intLenient() ?: run { dropped++; null } ?: continue
            anchors[key] = value.coerceIn(0, 100)
        }

        // anchor_basis：键同上，值截 24 字。非法键静默滤掉不计数——它只是给 anchors 配的说明，
        // 丢一句依据短语不影响任何数值（图纸 §3.4 只对 anchors / gains / custom / operators 要求计数）。
        val basis = (parsed.anchor_basis ?: emptyMap())
            .filterKeys { it in validDimensions }
            .mapValues { it.value.trim().take(BASIS_MAX_LENGTH) }
            .filterValues { it.isNotEmpty() }

        // gains：键必须 ∈ g01–g27，值钳 [0,2]；值为 1（正常）的项不入库（缺席即 1·Y-7）；值取不出整数 ⇒ 丢该项计数（D-12）。
        val rawGains = parsed.gains ?: emptyMap()
        dropped += rawGains.keys.count { it !in PersonaVocab.GAINS }
        val systemGains = LinkedHashMap<String, Int>()
        for ((key, element) in rawGains) {
            if (key !in PersonaVocab.GAINS) continue
            val value = element.intLenient() ?: run { dropped++; null } ?: continue
            val level = value.coerceIn(PersonaVocab.LEVEL_NUMB, PersonaVocab.LEVEL_SENSITIVE)
            if (level != PersonaVocab.LEVEL_NORMAL) systemGains[key] = level
        }

        // custom_gains：label 截 12 字、去空白为空则丢；与 27 项标签重名丢；组内自身重名丢；超 10 截断。
        val customs = mutableListOf<CustomGain>()
        val seenLabels = systemGainLabels.map { it.trim().lowercase() }.toMutableSet()
        (parsed.custom_gains ?: emptyList()).forEach { item ->
            val label = item.label.trim().take(CustomGain.MAX_LABEL_LENGTH)
            val key = label.lowercase()
            if (label.isEmpty() || key in seenLabels) { dropped++; return@forEach }
            if (customs.size >= PersonaGains.MAX_CUSTOM) { dropped++; return@forEach }
            seenLabels += key
            customs += CustomGain(
                id = UUID.randomUUID().toString(),
                label = label,
                // D-12：`level` 来成 `"很敏感"` / `null` / `"2"` 都不许拖垮整份——取不出整数回默认「很敏感」
                level = (item.level.intLenient() ?: PersonaVocab.LEVEL_SENSITIVE).coerceIn(PersonaVocab.LEVEL_NUMB, PersonaVocab.LEVEL_SENSITIVE),
                origin = CustomGain.ORIGIN_COMPILED,
            )
        }

        // operators：词表外整条丢；同一 condition 保留第一条；超 8 条截断。
        val operators = mutableListOf<PersonaOperator>()
        val seenConditions = mutableSetOf<String>()
        (parsed.operators ?: emptyList()).forEach { item ->
            val condition = item.condition.trim()
            val action = item.action.trim()
            if (condition !in PersonaVocab.CONDITIONS || action !in PersonaVocab.ACTIONS) { dropped++; return@forEach }
            if (!seenConditions.add(condition)) { dropped++; return@forEach }
            if (operators.size >= PersonaVocab.MAX_OPERATORS) { dropped++; return@forEach }
            operators += PersonaOperator(id = UUID.randomUUID().toString(), condition = condition, action = action)
        }

        if (anchors.isEmpty() && systemGains.isEmpty() && customs.isEmpty() && operators.isEmpty()) {
            throw PersonaCompileError.InvalidResponse("anchors / gains / operators 三者全空")
        }

        return PersonaCompileResult(
            anchors = anchors,
            basis = basis,
            gains = PersonaGains(system = systemGains, custom = customs),
            operators = operators,
            droppedCount = dropped,
            notes = (parsed.notes ?: "").trim().take(NOTES_MAX_LENGTH),
        )
    }

    // 图纸 §9.2 锁定值：依据短语 ≤24 字 · notes ≤60 字（仅进 Logcat）
    // · 空响应重试间隔照 GrowthAnalysisService 现值。
    private companion object {
        const val BASIS_MAX_LENGTH = 24
        const val NOTES_MAX_LENGTH = 60
        const val EMPTY_RETRY_DELAY_MS = 200L

        // 卷三 K-14：key 与中文标签并列（`g13 吵架 · 被凶`）——卷一只给 key，模型看着编号猜档位，投影表全白配。
        val GAIN_KEY_HINT = PersonaVocab.GAIN_KEYS.joinToString("、") { PersonaVocab.gainPromptLine(it) }
        val CONDITION_KEY_HINT = PersonaVocab.CONDITIONS.keys.joinToString("、")
        val ACTION_KEY_HINT = PersonaVocab.ACTIONS.keys.joinToString("、")
    }
}
