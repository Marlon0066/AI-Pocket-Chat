package com.situ.aichat.gift

import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.model.GiftCategory
import com.situ.aichat.data.model.GiftContext
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.moodHistory
import com.situ.aichat.data.model.relationshipPressure
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.model.syncedTo
import com.situ.aichat.data.model.toQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.JSONExtractor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 礼物店反应流第二步（1:1 iOS `GiftSendService.generateReaction` / `parseReaction` / `fallbackReaction`）。
 *
 * 在 [GiftSendService.spendAndCreateRecord] 扣币建 record 后调用：LLM（temp 0.9，json_object，**15s 硬超时**）生成
 * 反应文案 + mood emoji + 打分 → 失败/超时走本地兜底 → 时机加成 × 边际衰减算最终心意值 → 拟人化 sense 文案
 * （**不显数字**）→ 原子写回 record.reactionText/affinityGain/relationshipImpactJSON + 角色钱包 affinityFromUser +
 * 8 维关系。**礼物店路径与 iOS 一样不写 L3 growthLog**（只有聊天送礼写）。
 *
 * 最终 gain 公式三处一致（sendInChat / sendUserDIYInChat / 此处）：`applyMultiplier(LLM分或baseline, timing × decay)`，
 * round + clamp[1,20]。LLM 打分先 clamp 到 [baseline, 20]（[parseReaction]），fallback 直接用 baseline。
 */
@Singleton
class GiftReactionService @Inject constructor(
    private val db: AppDatabase,
    private val contextLog: ContextLogService,
    private val giftDao: GiftDao,
    private val currencyService: com.situ.aichat.economy.CurrencyService,
    private val characterRepo: CharacterRepository,
    private val characterWriteLock: CharacterWriteLock,
) {

    /** 反应结果（1:1 iOS `ReactionOutcome`），UI 直接用。affinityGain 仅供调试，UI 显 [senseText] 不显数字。 */
    data class ReactionOutcome(
        val reactionText: String,
        val moodEmoji: String,
        val affinityGain: Int,
        /** 是否走了 LLM（false=降级本地兜底）。 */
        val usedLLM: Boolean,
        /** 拟人化心意反馈主文案（代替「+N 心意值」数字）。 */
        val senseText: String,
        /** 手作礼物专属副标签（仅手作时非 null）。 */
        val handmadeBadge: String?,
    )

    /**
     * 生成反应并原子写回（1:1 iOS `generateReaction`）。[config] 由调用方按 CHAT 路由解析；null = 无 API → 直接兜底。
     *
     * @param record 第一步建好的 record（已在 DB，affinityGain=0/反应字段空）。
     * @param item record 对应的目录项（DIY 不走礼物店反应流，故必为目录项）。
     * @param character 收礼角色（读 birthday/moodHistory/affinitySensePackageJSON 快照）。
     */
    suspend fun generateReaction(
        record: GiftRecordEntity,
        item: GiftItem,
        character: CharacterEntity,
        config: ApiConfigValues?,
        now: Long = System.currentTimeMillis(),
    ): ReactionOutcome {
        // ── LLM 尝试（单次 completion + 15s 硬超时；超时/网络错/解析失败 → null → 兜底）──
        val parsed: ReactionPayload? = tryLLM(item, GiftContext.fromRaw(record.context), character, config)
        val usedLLM = parsed != null
        if (!usedLLM) Log.d(TAG, "礼物反应·走本地兜底(LLM 无结果/超时) char=${character.name} item=${item.id}")
        val base = parsed ?: fallbackReaction(item)

        // ── 时机加成 × 边际衰减 → 最终心意值（与聊天送礼三处一致）──
        val timing = GiftTimingBonusService.multiplier(character.birthday, character.moodHistory, now)
        // record 已在 DB，按 uuid 排除自身，避免把本次也算进 7 天同品类衰减
        val decay = GiftMarginalDecayService.multiplier(item, character.uuid, record.uuid, giftDao, now)
        val finalGain = GiftMarginalDecayService.applyMultiplier(base.affinityGain, timing * decay)

        // ── 拟人化心意文案（档位基于 decay 后的最终 gain；包缺失/过期自动回落 30 条兜底）──
        val sense = AffinitySenseService.currentSenseText(
            character.affinitySensePackageJSON, finalGain, item.isHandmade,
        )

        val outcome = ReactionOutcome(
            reactionText = base.reactionText,
            moodEmoji = base.moodEmoji,
            affinityGain = finalGain,
            usedLLM = usedLLM,
            senseText = sense.text,
            handmadeBadge = sense.handmadeBadge,
        )

        // ── 原子写回（钱已在第一步扣完，这里保证 record 反应 + 钱包 affinityFromUser + 8 维关系一次落盘，杜绝半写）──
        // P12.6 D1b：LLM 已在锁外完成（不持锁烧秒级网络）；写回先拿每角色写锁、再开事务（Mutex→SQLite 固定序，
        // 与成长/关系/结构化分析一致，无顺序反转死锁）。锁内 fresh 读角色行 → 关系质感列级写回，不整行覆盖分析刚写的列。
        val impact = GiftRelationshipImpactService.compute(item, finalGain)
        characterWriteLock.withCharacterLock(character.uuid) {
            db.withTransaction {
                giftDao.update(
                    record.copy(
                        reactionText = outcome.reactionText,
                        reactionMoodEmoji = outcome.moodEmoji,
                        affinityGain = finalGain,
                        relationshipImpactJSON = impact.toJson(),
                    ),
                )
                currencyService.addAffinityFromUser(character.uuid, finalGain, now)
                val fresh = characterRepo.get(character.uuid) ?: character
                // 卷二表1 ④：同②③——impact 本体零碰，只把目标净额经写口翻译成压强。
                val newRel = GiftRelationshipImpactService.apply(impact, fresh.relationshipQuality)
                val pressure = fresh.relationshipPressure.syncedTo(newRel)
                characterRepo.updateRelationshipQuality(
                    character.uuid,
                    GrowthJson.encode(pressure.toQuality()),
                    GrowthJson.encode(pressure),
                )
            }
        }

        return outcome
    }

    /** 单次 LLM 反应生成（temp 0.9 / json_object / 15s 硬超时）。无 config / 超时 / 网络错 / 解析失败 → null。 */
    private suspend fun tryLLM(
        item: GiftItem,
        context: GiftContext,
        character: CharacterEntity,
        config: ApiConfigValues?,
    ): ReactionPayload? {
        if (config == null) return null
        val (system, user) = buildPrompt(item, character, context)
        val messages = listOf(
            ChatMessageDto(role = "system", content = system),
            ChatMessageDto(role = "user", content = user),
        )
        val response = withTimeoutOrNull(REACTION_TIMEOUT_MS) {
            try {
                contextLog.completion(
                    source = LogSource.GIFT_REACTION,
                    characterName = character.name,
                    config = config,
                    messages = messages,
                    temperature = 0.9,
                    responseFormat = ResponseFormatDto(type = "json_object"),
                )
            } catch (e: CancellationException) {
                throw e // 超时/父协程取消须向上传播，不可吞
            } catch (_: Exception) {
                null // 真实网络/解码错 → 兜底
            }
        } ?: return null
        return parseReaction(response, item)
    }

    companion object {
        private const val TAG = "GiftReaction"

        /** LLM 反应硬超时（1:1 iOS 15s）：超时走本地兜底，保证「扣款即时、反应不卡死转圈」。 */
        const val REACTION_TIMEOUT_MS = 15_000L

        private val json = Json { ignoreUnknownKeys = true }

        /** LLM 反应原始 JSON（解码用，字段必填——缺字段则解码失败 → 兜底，1:1 iOS 非可选字段）。 */
        @Serializable
        private data class RawReaction(
            val reactionText: String,
            val moodEmoji: String,
            val affinityGain: Int,
        )

        /** 解析后的反应（已 clamp + 安全 emoji）。 */
        data class ReactionPayload(
            val reactionText: String,
            val moodEmoji: String,
            val affinityGain: Int,
        )

        /**
         * 解析 LLM 反应 JSON（1:1 iOS `parseReaction`）。strip think → JSONExtractor → decode；reactionText 空则跳过；
         * emoji 空用品类默认；**affinityGain clamp 到 [baseline, 20]**（先 [0,20] 再取 max(baseline, _)，LLM 不能压到
         * baseline 以下，手作即使廉价也保底）。全失败返 null（调用方兜底）。internal 供单测验各种异常 JSON。
         */
        internal fun parseReaction(response: String, item: GiftItem): ReactionPayload? {
            val cleaned = MemoryService.strippingThinkingTags(response)
            val extracted = JSONExtractor.extract(cleaned)
            val candidates = if (extracted == cleaned) listOf(cleaned) else listOf(extracted, cleaned)
            for (candidate in candidates) {
                val raw = runCatching { json.decodeFromString<RawReaction>(candidate) }.getOrNull() ?: continue
                val text = raw.reactionText.trim()
                if (text.isEmpty()) continue
                val emoji = raw.moodEmoji.trim().ifEmpty { defaultEmoji(item.category) }
                val clampedGain = maxOf(GiftAffinity.baseline(item), raw.affinityGain.coerceIn(0, 20))
                return ReactionPayload(text, emoji, clampedGain)
            }
            return null
        }

        /** 本地兜底反应（1:1 iOS `fallbackReaction`）：按品类 3 选 1 模板 + 品类默认 emoji + baseline 心意。internal 供单测。 */
        internal fun fallbackReaction(item: GiftItem, rng: Random = Random.Default): ReactionPayload {
            val templates = when (item.category) {
                GiftCategory.FOOD -> listOf("这个好吃诶，谢谢～", "哇正好饿了。", "你记得我喜欢这个吗？")
                GiftCategory.FLOWER -> listOf("好美，谢谢你。", "花香让人心情变好。", "这个要怎么养？")
                GiftCategory.ACCESSORY -> listOf("会一直戴着的。", "眼光真好，谢谢。", "诶，好漂亮。")
                GiftCategory.DAILY -> listOf("实用贴心，谢谢。", "你真的很会挑东西。", "感觉你很了解我。")
                GiftCategory.LUXURY -> listOf("这也太破费了吧！", "真的好喜欢，谢谢你。", "会好好收着的。")
                GiftCategory.EXPERIENCE -> listOf("好期待一起去！", "听起来就很棒。", "这次一定要好好玩。")
                GiftCategory.HANDMADE -> listOf("手作的？太珍贵了。", "能感受到心意。", "我会一直收藏着。")
            }
            val text = templates.randomOrNull(rng) ?: "谢谢你。"
            return ReactionPayload(text, defaultEmoji(item.category), GiftAffinity.baseline(item))
        }

        /** 品类默认 emoji（1:1 iOS `defaultEmoji`）。 */
        internal fun defaultEmoji(category: GiftCategory): String = when (category) {
            GiftCategory.FOOD -> "😋"
            GiftCategory.FLOWER -> "🥰"
            GiftCategory.ACCESSORY -> "✨"
            GiftCategory.DAILY -> "☺️"
            GiftCategory.LUXURY -> "😳"
            GiftCategory.EXPERIENCE -> "🤩"
            GiftCategory.HANDMADE -> "🥹"
        }

        /** 反应 prompt（1:1 iOS `buildPrompt`）：system 人设 + 任务 + JSON schema + 打分参考；user 礼物信息 + 场景。 */
        internal fun buildPrompt(
            item: GiftItem,
            character: CharacterEntity,
            context: GiftContext,
        ): Pair<String, String> {
            val system = buildList {
                add("你是「${character.name}」，人设：${character.personalityDescription}")
                if (character.systemPrompt.isNotEmpty()) add("角色设定：${character.systemPrompt}")
                if (character.speakingStyle.isNotEmpty()) add("说话风格：${character.speakingStyle}")
                add("")
                add("用户刚送了你一份礼物。请以你的人设做出真实、有个性的第一反应——不要客套话，要结合礼物本身的特点和你的性格/和用户的关系说。")
                add("")
                add("严格输出一行 JSON，格式如下（不要任何其他内容，不要 markdown）：")
                add("{\"reactionText\":\"25-45字的反应\",\"moodEmoji\":\"单个emoji\",\"affinityGain\":整数0-20}")
                add("")
                add("affinityGain 打分参考：")
                add("- 反应真诚且惊喜：15-20")
                add("- 反应正面但普通：8-14")
                add("- 反应平淡或不合心意：1-7")
                add("- 手作小礼物即使廉价也可以高分（心意重于价格）")
            }
            val tags = item.emotionalTags.joinToString("、") { it.displayName }
            val user = buildList {
                add("礼物名称：${item.name}")
                add("品类：${item.category.displayName}")
                add("情感标签：$tags")
                add("价格：${item.price} 金币")
                add("特征：${item.subtitle}")
                if (item.isHandmade) add("（这是手作礼物，心意权重更高）")
                if (context != GiftContext.RANDOM) add("场景：${context.displayName}")
            }
            return system.joinToString("\n") to user.joinToString("\n")
        }
    }
}
