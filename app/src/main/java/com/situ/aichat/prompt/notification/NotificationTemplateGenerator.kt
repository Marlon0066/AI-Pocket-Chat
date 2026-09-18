package com.situ.aichat.prompt.notification

import android.content.Context
import android.util.Log
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.NotificationTemplateDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.NotificationTemplateEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.JSONExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1:1 port of iOS `Services/NotificationTemplateGenerator.swift`。建角色时调用 LLM 按角色性格批量生成
 * 各场景通知文案存库；API 不可用 / 调用失败时回退保底默认文案，全程不抛错（不影响角色创建）。
 *
 * LLM：temperature=0.8、maxTokens=1000、json_object；解析失败先尝试 LLM 修复 JSON（temp 0.1）再回退默认。
 * 9 个分类（含宠物 3 类，P8 才消费；现一并生成避免日后重生成）。文案生成 / 选取 = 内容层；
 * 「何时生成」（建角色 / 启动补生成）与「何时选取发送」属调度（6.1c）。
 *
 * 取文案见 [NotificationTemplateDao.pickUnused]；都没有时调度侧回退保底文案（6.1c）。
 */
@Singleton
class NotificationTemplateGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmClient: LlmClient,
    private val contextLog: ContextLogService,
    private val templateDao: NotificationTemplateDao,
    private val userProfileDao: UserProfileDao,
) {

    /**
     * 为角色生成并保存通知文案。[config] 为 null（未配置 API）或全部尝试失败 → 存默认文案。
     * [relationship] 为当前关系名（调用方经 `CharacterRepository.currentRelationship` 取，可空）。
     */
    suspend fun generateAndSave(
        character: CharacterEntity,
        relationship: String?,
        config: ApiConfigValues?,
    ) {
        // 不在这里先删旧池：下面要调模型（最多 3 次 + 间隔），途中进程被杀会留下空池。
        // 两条写路径（模型文案 / 默认文案）都在拿到新文案后经 replaceForCharacter 一个事务换掉整池。
        if (config == null || config.apiKey.isEmpty() || config.baseUrl.isEmpty() || config.modelName.isEmpty()) {
            Log.i(TAG, "未配置 API，使用默认文案：${character.name}")
            saveDefaultTemplates(character.uuid)
            return
        }

        // 预烘焙通知文案用户可见（喂 LLM 写「和用户的关系」「TA 对用户的称呼」）→ 统一真名字（盲区补扫 B4）。
        // 置于 config 兜底之后，默认文案路径不做无谓查库。空昵称回退「用户」= 旧字节。
        val userName = (userProfileDao.get()?.nickname ?: "").ifBlank { "用户" }

        val maxAttempts = 3
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                Log.i(TAG, "文案生成重试第 ${attempt - 1} 次：${character.name}")
                delay(2000)
            }
            try {
                val templates = callApiForTemplates(character, relationship, config, userName)
                templateDao.replaceForCharacter(character.uuid, templates)
                Log.i(TAG, "文案生成成功：${character.name}，共 ${templates.size} 条")
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "文案生成第 $attempt 次尝试失败：${e.message}")
            }
        }
        Log.e(TAG, "文案生成全部 $maxAttempts 次尝试失败，使用默认文案：${lastError?.message}")
        saveDefaultTemplates(character.uuid)
    }

    /** 角色当前是否在用保底默认文案（用内容比对判断，无需额外字段）。对齐 iOS `isUsingDefaultTemplates`。 */
    suspend fun isUsingDefaultTemplates(characterId: String): Boolean {
        val templates = templateDao.allForCharacter(characterId)
        if (templates.isEmpty()) return true
        val defaults = defaultTemplates().flatMap { it.second }.toSet()
        return templates.all { it.content in defaults }
    }

    // MARK: - 调用 LLM

    private suspend fun callApiForTemplates(
        character: CharacterEntity,
        relationship: String?,
        config: ApiConfigValues,
        userName: String,
    ): List<NotificationTemplateEntity> {
        val messages = listOf(
            ChatMessageDto(role = "system", content = buildSystemPrompt()),
            ChatMessageDto(role = "user", content = buildUserPrompt(character, relationship, userName)),
        )
        val response = contextLog.completion(
            source = LogSource.NOTIFICATION_TEMPLATE,
            characterName = character.name,
            config = config,
            messages = messages,
            temperature = 0.8,
            maxTokens = 1000,
            responseFormat = ResponseFormatDto(type = "json_object"),
        )
        return parseResponse(response, character.uuid, config)
    }

    private suspend fun parseResponse(
        response: String,
        characterId: String,
        config: ApiConfigValues,
    ): List<NotificationTemplateEntity> {
        val cleaned = MemoryService.strippingThinkingTags(response)
        var decoded = parseTemplatesMap(cleaned)
        if (decoded == null) {
            Log.i(TAG, "JSON 直接解析失败，尝试 LLM 修复")
            decoded = repairJson(cleaned, config)
        }
        val map = decoded ?: throw IllegalStateException("无法从回复中提取合法 JSON")
        val templates = buildTemplatesFromMap(map, characterId)
        val totalExpected = CATEGORY_REQUIREMENTS.sumOf { it.second }
        if (templates.size < totalExpected / 2) {
            throw IllegalStateException("从回复解析出的文案过少（${templates.size}/$totalExpected）")
        }
        return templates
    }

    /** 把解析失败的回复发给 LLM 修复为合法 JSON（低温度、小 token）。对齐 iOS `repairJSON`。 */
    private suspend fun repairJson(raw: String, config: ApiConfigValues): Map<String, List<String>>? {
        val messages = listOf(
            ChatMessageDto(role = "system", content = buildRepairPrompt()),
            ChatMessageDto(role = "user", content = raw),
        )
        val repaired = runCatching {
            llmClient.completion(
                messages = messages,
                config = config,
                temperature = 0.1,
                maxTokens = 800,
                responseFormat = ResponseFormatDto(type = "json_object"),
            )
        }.getOrNull() ?: return null
        return parseTemplatesMap(repaired)
    }

    // MARK: - Prompt（逐字对齐 iOS）

    private fun buildSystemPrompt(): String = """
        你是一个专业的手机推送通知文案写手。你需要以指定角色的身份，批量生成一系列通知文案。

        写作铁则：
        1. 每条文案必须像角色本人发的微信消息，绝对不能有系统通知的感觉
        2. 控制在 10-25 个字之间
        3. 不要用 emoji
        4. 不要用"亲爱的""dear"之类的客套话
        5. 每条文案都要有辨识度和个性，体现角色的说话风格
        6. 不同分类的文案情绪基调要明显不同
        7. 只返回 JSON，不要任何额外说明
    """.trimIndent()

    internal fun buildUserPrompt(character: CharacterEntity, relationship: String?, userName: String): String {
        val info = buildList {
            add("角色名字：${character.name}")
            if (character.gender.isNotEmpty()) add("性别：${character.gender}")
            if (character.occupation.isNotEmpty()) add("身份/职业：${character.occupation}")
            if (character.personalityDescription.isNotEmpty()) add("性格：${character.personalityDescription}")
            if (character.speakingStyle.isNotEmpty()) add("说话风格：${character.speakingStyle}")
            if (character.catchphrases.isNotEmpty()) add("口头禅：${character.catchphrases}")
            if (!relationship.isNullOrEmpty()) add("和${userName}的关系：$relationship")
            if (character.systemPrompt.isNotEmpty()) add("角色设定摘要：${character.systemPrompt.take(300)}")
            // 预烘焙喂记忆（活人感二期 M3·图纸 §3.3）：把结构化记忆的称呼 / 内部梗 / 共同喜欢喂进文案生成，让日常
            // 推送个性化。仅非空字段出行；三行全空 → 整段不追加，输出与现状**逐字节一致**（E7）。不喂 memorySummary
            // 长文（刻意决策：太长且易过期）。此文件既有惯例=硬编码中文提示词，不走字符串资源。
            val sm = StructuredMemory.decode(character.structuredMemoryJSON)
            val memoryLines = buildList {
                if (sm.nicknameFromChar.isNotEmpty()) add("TA 对${userName}的称呼：${sm.nicknameFromChar}")
                if (sm.insideJoke.isNotEmpty()) add("你们之间的内部梗：${sm.insideJoke}")
                if (sm.sharedLikes.isNotEmpty()) add("你们共同喜欢：${sm.sharedLikes}")
            }
            if (memoryLines.isNotEmpty()) {
                addAll(memoryLines)
                add("上面这些相处痕迹可以自然融进部分文案（比如用称呼、提到共同喜欢的东西），不必每条都用。")
            }
        }.joinToString("\n")

        val categoryDescriptions = """
            各分类的场景说明：
            - streak_remind（5条）：今天还没聊天，自然地找话题搭话
            - streak_urgent（3条）：快到深夜了还没聊，有点急了，用角色性格表达催促
            - streak_broken（3条）：连续聊天中断了，表达失落或不满
            - morning（3条）：早上打招呼，可以提天气或今天的计划
            - evening（3条）：晚上关心对方，聊聊今天的事
            - random（3条）：随机想找对方聊聊，分享日常或突然想到的事
            - pet_hungry（3条）：宠物饿了，以角色口吻提醒用户喂宠物（如果角色有宠物的话）
            - pet_sick（2条）：宠物生病了，以角色口吻表达担心催促照顾
            - pet_milestone（2条）：宠物达成成就（学会技能/升级），以角色口吻表达开心
        """.trimIndent()

        val fewShotExamples = """
            参考示例（仅供风格参考，不要直接复制）：
            温柔女性角色："你今天有没有好好吃饭" / "在干嘛呀，我无聊了"
            高冷男性角色："嗯。你今天没来。" / "随便你"
            活泼角色："哈喽！你醒了没！" / "我发现了一个超好玩的东西"
        """.trimIndent()

        val outputFormat = """{"streak_remind":["文案1","文案2","文案3","文案4","文案5"],"streak_urgent":["文案1","文案2","文案3"],"streak_broken":["文案1","文案2","文案3"],"morning":["文案1","文案2","文案3"],"evening":["文案1","文案2","文案3"],"random":["文案1","文案2","文案3"],"pet_hungry":["文案1","文案2","文案3"],"pet_sick":["文案1","文案2"],"pet_milestone":["文案1","文案2"]}"""

        return buildString {
            append(info)
            append("\n\n")
            append(categoryDescriptions)
            append("\n\n")
            append(fewShotExamples)
            append("\n\n")
            append("请以这个角色的口吻，生成通知文案，返回以下格式的 JSON：\n")
            append(outputFormat)
        }
    }

    // MARK: - 默认保底文案

    private suspend fun saveDefaultTemplates(characterId: String) {
        val templates = defaultTemplates().flatMap { (category, contents) ->
            contents.map {
                NotificationTemplateEntity(characterId = characterId, category = category, content = it)
            }
        }
        templateDao.replaceForCharacter(characterId, templates)
    }

    /** 保底默认文案（API 不可用 / 失败时使用）。en 逐字对齐 iOS；zh 为本地化译文（仅极少触发的兜底）。 */
    private fun defaultTemplates(): List<Pair<String, List<String>>> = listOf(
        "streak_remind" to context.resources.getStringArray(R.array.notif_default_streak_remind).toList(),
        "streak_urgent" to context.resources.getStringArray(R.array.notif_default_streak_urgent).toList(),
        "streak_broken" to context.resources.getStringArray(R.array.notif_default_streak_broken).toList(),
        "morning" to context.resources.getStringArray(R.array.notif_default_morning).toList(),
        "evening" to context.resources.getStringArray(R.array.notif_default_evening).toList(),
        "random" to context.resources.getStringArray(R.array.notif_default_random).toList(),
        "pet_hungry" to context.resources.getStringArray(R.array.notif_default_pet_hungry).toList(),
        "pet_sick" to context.resources.getStringArray(R.array.notif_default_pet_sick).toList(),
        "pet_milestone" to context.resources.getStringArray(R.array.notif_default_pet_milestone).toList(),
    )

    companion object {
        private const val TAG = "NotifTemplateGen"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val mapSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

        /** 各分类及需要的条数（对齐 iOS `categoryRequirements`）。 */
        val CATEGORY_REQUIREMENTS: List<Pair<String, Int>> = listOf(
            "streak_remind" to 5,
            "streak_urgent" to 3,
            "streak_broken" to 3,
            "morning" to 3,
            "evening" to 3,
            "random" to 3,
            "pet_hungry" to 3,
            "pet_sick" to 2,
            "pet_milestone" to 2,
        )

        /**
         * JSON 修复提示词。键数与骨架由 [CATEGORY_REQUIREMENTS] 派生——原先写死「6 个键」且漏了宠物 3 类，
         * 走修复路径时模型会把宠物分类丢掉（剩 20 条仍过半数门槛照常入库），宠物提醒随之无文案。
         */
        internal fun buildRepairPrompt(): String {
            val skeleton = CATEGORY_REQUIREMENTS.joinToString(",", "{", "}") { (category, _) -> "\"$category\":[...]" }
            return listOf(
                "你是 JSON 修复器。以下文本应该是一个合法的 JSON 对象，包含 ${CATEGORY_REQUIREMENTS.size} 个键，每个键对应一个字符串数组：",
                skeleton,
                "",
                "修复规则：",
                "1. 只输出修复后的合法 JSON，不要任何解释或 markdown 代码块",
                "2. 保留原始文案内容，只修复 JSON 格式问题",
                "3. 字符串内的双引号用 \\\" 转义",
                "4. 确保所有括号和引号正确匹配",
            ).joinToString("\n")
        }

        /** 多候选解析 LLM 回复为 `分类→文案数组`：清理后原文 + JSONExtractor 提取，均失败返回 null。纯函数。 */
        internal fun parseTemplatesMap(cleaned: String): Map<String, List<String>>? {
            val candidates = listOf(cleaned.trim(), JSONExtractor.extract(cleaned))
            for (candidate in candidates) {
                runCatching { json.decodeFromString(mapSerializer, candidate) }.getOrNull()?.let { return it }
            }
            return null
        }

        /** 按 [CATEGORY_REQUIREMENTS] 顺序与上限裁剪、去空白，构建模板实体。纯函数。 */
        internal fun buildTemplatesFromMap(
            map: Map<String, List<String>>,
            characterId: String,
            now: Long = System.currentTimeMillis(),
        ): List<NotificationTemplateEntity> {
            val result = mutableListOf<NotificationTemplateEntity>()
            for ((category, count) in CATEGORY_REQUIREMENTS) {
                val contents = map[category] ?: continue
                for (content in contents.take(count)) {
                    val trimmed = content.trim()
                    if (trimmed.isEmpty()) continue
                    result.add(
                        NotificationTemplateEntity(
                            characterId = characterId,
                            category = category,
                            content = trimmed,
                            createdAt = now,
                        ),
                    )
                }
            }
            return result
        }
    }
}
