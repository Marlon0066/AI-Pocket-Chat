package com.situ.aichat.gift

import android.util.Log
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AffinitySensePackage
import com.situ.aichat.data.model.AffinitySenseResult
import com.situ.aichat.data.model.AffinitySenseTier
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.JSONExtractor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 心意反馈拟人化层"门面"（1:1 iOS `AffinitySenseService`）。
 *
 * - UI 反应页同步调 [currentSenseText] 瞬时取一条文案（角色缓存包 / 全局 fallback 里随机挑），**永远有结果**。
 * - 送礼完成后调 [generatePackageIfNeeded] 异步检查缓存包过期/缺失 → 满足则 LLM 生成新包写回角色（temp 0.9）。
 * - 用户永远感受不到系统状态（有包用包、没包用 fallback）。缓存 14 天；人设变更/关系里程碑由上层置 generatedAt=null 强刷。
 */
@Singleton
class AffinitySenseService @Inject constructor(
    private val contextLog: ContextLogService,
    private val characterRepo: CharacterRepository,
) {

    /**
     * 过期则 LLM 生成新文案包写回角色（异步，失败静默 → 下次送礼再试，用户全程感受不到）。
     * [config] 由调用方按 CHAT 路由解析后传入（同 GiftSendService/SalaryInference 套路）。
     */
    suspend fun generatePackageIfNeeded(
        character: CharacterEntity,
        config: ApiConfigValues,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!isExpired(character.affinitySensePackageGeneratedAt, now)) return
        val relationship = characterRepo.currentRelationship(character.uuid) ?: "朋友"
        val (system, user) = buildPrompt(character, relationship)
        val messages = listOf(
            ChatMessageDto(role = "system", content = system),
            ChatMessageDto(role = "user", content = user),
        )
        // 温度偏高增加 8 条内部多样性；thinking 模型内部思考但最终 JSON 一致
        val response = try {
            contextLog.completion(
                source = LogSource.AFFINITY_SENSE,
                characterName = character.name,
                config = config,
                messages = messages,
                temperature = 0.9,
                responseFormat = ResponseFormatDto(type = "json_object"),
            )
        } catch (_: Exception) {
            Log.d(TAG, "心意文案包生成·LLM 失败,下次送礼重试 char=${character.name}")
            return
        }
        val pkg = parsePackage(response) ?: run {
            Log.d(TAG, "心意文案包生成·解析失败,保持旧包 char=${character.name}")
            return
        }
        // P12.6 D1b：列级写回两列（包 JSON + 生成时间），不再整行 copy 覆盖分析/心情等并发列。
        // 这是盲写（不依赖读当前值），单条 UPDATE 即原子，无须进每角色锁；与关系评估清空 generatedAt=NULL 之间
        // 为「最后写者生效」语义（与 iOS 跨 await 交错同效），不会丢更新到其它列。
        characterRepo.updateAffinitySensePackage(character.uuid, encode(pkg), now)
    }

    companion object {
        private const val TAG = "AffinitySense"

        /** 文案包有效期（14 天）。 */
        const val PACKAGE_LIFETIME_MS = 14L * 24 * 60 * 60 * 1000

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * 为一次送礼取一条心意反馈文案（**瞬时同步，永远有结果**）。按 [gain] 判档位，从角色缓存包 / 全局 fallback 随机挑；
         * 手作礼物附加 handmade 副标签。[rng] 注入便于确定性单测。
         */
        fun currentSenseText(
            packageJson: String,
            gain: Int,
            isHandmade: Boolean,
            rng: Random = Random.Default,
        ): AffinitySenseResult {
            val pkg = effectivePackage(packageJson)
            val tier = AffinitySenseTier.tier(gain)
            val text = pkg.phrases(tier).randomOrNull(rng)
                ?: AffinitySenseFallback.defaultPackage.phrases(tier).firstOrNull()
                ?: "……"
            val badge = if (isHandmade) {
                pkg.handmade.randomOrNull(rng) ?: AffinitySenseFallback.defaultPackage.handmade.firstOrNull()
            } else {
                null
            }
            return AffinitySenseResult(text = text, handmadeBadge = badge)
        }

        /** 解析角色有效文案包；空/解析失败/结构不完整 → 全局 fallback（1:1 iOS effectivePackage）。 */
        internal fun effectivePackage(packageJson: String): AffinitySensePackage {
            if (packageJson.isBlank()) return AffinitySenseFallback.defaultPackage
            val decoded = runCatching { json.decodeFromString<AffinitySensePackage>(packageJson) }.getOrNull()
            return if (decoded != null && decoded.isWellFormed) decoded else AffinitySenseFallback.defaultPackage
        }

        /** 文案包是否需刷新：没生成过、或超过 14 天（1:1 iOS isExpired）。 */
        fun isExpired(generatedAt: Long?, now: Long): Boolean {
            if (generatedAt == null) return true
            return now - generatedAt >= PACKAGE_LIFETIME_MS
        }

        internal fun encode(pkg: AffinitySensePackage): String =
            runCatching { json.encodeToString(pkg) }.getOrDefault("")

        /** 解析 LLM 响应（1:1 iOS parsePackage：strip think → JSONExtractor 三层兜底 → decode + isWellFormed）。 */
        internal fun parsePackage(response: String): AffinitySensePackage? {
            val cleaned = MemoryService.strippingThinkingTags(response)
            val extracted = JSONExtractor.extract(cleaned)
            val candidates = if (extracted == cleaned) listOf(cleaned) else listOf(extracted, cleaned)
            for (candidate in candidates) {
                val pkg = runCatching { json.decodeFromString<AffinitySensePackage>(candidate) }.getOrNull()
                if (pkg != null && pkg.isWellFormed) return pkg
            }
            return null
        }

        /** 构建生成文案包的 system + user prompt（1:1 iOS buildPrompt）。 */
        internal fun buildPrompt(character: CharacterEntity, relationship: String): Pair<String, String> {
            val personality = character.personalityDescription.ifEmpty { "温和自然" }
            val speakingStyle = character.speakingStyle.ifEmpty { "日常口语" }
            val systemSection = if (character.systemPrompt.isEmpty()) "" else "\n角色设定：${character.systemPrompt}"

            // 逐行拼、不用「原始字符串 + trimIndent()」：后者先插值后去缩进，systemSection 自带换行或人设等任一插值跨行，
            // 最小公共缩进就归零，模板每行的 16 格源码缩进会原样发给模型（2026-09-18 修复·单行输入逐字节不变有金标钉）。
            val system = listOf(
                "你是「${character.name}」。",
                "人设：$personality$systemSection",
                "说话风格：$speakingStyle",
                "与\"我\"（用户）当前的关系：$relationship",
                "",
                "请为你自己生成一组\"收到我送的礼物时的心意反馈文案\"。这些文案会在我送礼后的反应页上显示，",
                "目的是让我感受到你真实的情绪反应，而不是看到\"+10 好感度\"这种冰冷的数字。",
                "",
                "生成要求：",
                "1. 按 3 档情感强度各给 8 条，再给 6 条\"手作礼物专属副标签\"：",
                "   - low（轻微触动）：礼物普通，反应平淡但还是被打动一点",
                "   - mid（明显开心）：送到心坎，看得出来心情变好",
                "   - high（强烈感动）：非常贵 / 非常惊喜 / 罕见心意",
                "   - handmade（手作副标签）：叠加在手作礼物时显示的短标签",
                "2. 每条 8-18 字（handmade 副标签 3-8 字）",
                "3. 用你的性格口吻和说话风格，第一或第三人称皆可（按更自然的选）",
                "4. 避免笼统词（\"好开心\"、\"谢谢你\"），要有画面感或性格特征",
                "5. 8 条之间要有差异，不要重复同一种表达",
                "",
                "严格以 JSON 输出，不要任何其他文字、不要 markdown：",
                """{"version":1,"low":["..."×8],"mid":["..."×8],"high":["..."×8],"handmade":["..."×6]}""",
            ).joinToString("\n")

            val user = "现在请按上述规则为你自己生成这组文案。"
            return system to user
        }
    }
}
