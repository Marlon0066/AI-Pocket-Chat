package com.situ.aichat.story

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.prompt.DirtyMessageDetector
import com.situ.aichat.prompt.messageLlmSafeText
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.util.takeCodePoints
import java.text.Collator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从聊天和记忆数据中提取故事可用的影响信息（1:1 iOS `Services/StoryChatInfluenceBuilder.swift`）。
 *
 * 按 `chatInfluenceWeight` 四档输出不同深度：none 空 / light 最近话题 / medium 关系+互动摘要 / heavy 全量。
 * 深 DB 耦合（会话/消息/里程碑/成长/结构化记忆），故为 @Singleton 服务；纯格式化/裁剪助手抽到
 * [Helpers]（internal）便于单测，DB 编排部分由真机集中验。
 *
 * 取最近话题：逐会话取近期「非系统非空」消息(末 6) → 全局并 → 按时间倒序 → 过滤线下卡+脏消息 → 取前 6 →
 * 贴纸标记归一+换行转空格+裁 36 字（防故事 LLM 看到邀约卡 JSON 原文，P3-R11）。
 */
@Singleton
class StoryChatInfluenceBuilder @Inject constructor(
    private val characterDao: CharacterDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val milestoneDao: MilestoneDao,
) {

    suspend fun extractChatInfluence(
        chatInfluenceWeight: String,
        roles: List<StoryCharacterRoleEntity>,
    ): String {
        if (chatInfluenceWeight == StoryChatInfluenceWeight.NONE) return ""

        val characters = resolvedStoryCharacters(roles)
        if (characters.isEmpty()) return "当前故事未关联到可参考的聊天角色。"

        return when (chatInfluenceWeight) {
            StoryChatInfluenceWeight.LIGHT -> {
                val topics = characters.mapNotNull { summarizeRecentTopics(it) }
                if (topics.isEmpty()) {
                    "最近聊天中暂无明显主题。"
                } else {
                    "用户最近与角色聊天中提到的话题：\n" + topics.joinToString("\n")
                }
            }

            StoryChatInfluenceWeight.HEAVY -> {
                val profiles = characters.map { buildHeavyProfile(it) }
                "用户与角色的完整互动概况：\n" + profiles.joinToString("\n")
            }

            else -> { // medium（含未知值兜底 = iOS default 分支）
                val summaries = characters.map { buildMediumSummary(it) }
                "用户与角色的当前关系状态与近期互动：\n" + summaries.joinToString("\n")
            }
        }
    }

    // MARK: - 角色解析

    private suspend fun resolvedStoryCharacters(roles: List<StoryCharacterRoleEntity>): List<CharacterEntity> {
        val ids = roles.mapNotNull { it.characterId }.toSet()
        if (ids.isEmpty()) return emptyList()
        val characters = ids.mapNotNull { characterDao.getByUuid(it) }
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
        return characters.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    // MARK: - 各档 profile（DB 编排）

    private suspend fun buildHeavyProfile(character: CharacterEntity): String =
        "- ${character.name}：当前关系 ${currentRelationshipSummary(character)}" +
            "；最近情绪 ${Helpers.currentMoodSummary(character.lastMoodText, character.lastMoodEmoji)}" +
            "；最近话题 ${recentTopicsSummary(character) ?: "暂无"}" +
            "；长期记忆摘要：${Helpers.longMemorySummary(character.memorySummary)}" +
            "；成长阶段 ${growthStageSummary(character)}" +
            "；结构化记忆 ${structuredMemorySummary(character)}"

    private suspend fun buildMediumSummary(character: CharacterEntity): String =
        "- ${character.name}：关系 ${currentRelationshipSummary(character)}" +
            "；最近互动摘要：${mediumInteractionSummary(character)}"

    // MARK: - 最近话题（逐会话查消息）

    private suspend fun summarizeRecentTopics(character: CharacterEntity): String? {
        val conversationUuids = conversationDao.getByCharacter(character.uuid).map { it.uuid }
        if (conversationUuids.isEmpty()) return null

        val recentMessages = buildList {
            for (convUuid in conversationUuids) {
                addAll(messageDao.recentNonSystemForConversation(convUuid, RECENT_PER_CONVERSATION))
            }
        }.sortedByDescending { it.timestamp }

        // 结构化卡走单一事实源 messageLlmSafeText 脱敏 + 脏消息丢弃（避免故事生成 prompt 看到 JSON 原文，P3-R11）。
        val previews = Helpers.recentTopicPreviews(recentMessages, RECENT_GLOBAL)
        if (previews.isEmpty()) return null
        return "- ${character.name}：" + previews.joinToString(" / ")
    }

    private suspend fun recentTopicsSummary(character: CharacterEntity): String? {
        val line = summarizeRecentTopics(character) ?: return null
        return line.replace("- ${character.name}：", "")
    }

    private suspend fun mediumInteractionSummary(character: CharacterEntity): String {
        val memory = Helpers.longMemorySummary(character.memorySummary)
        val topics = recentTopicsSummary(character)
        return if (!topics.isNullOrEmpty()) "$memory；最近话题：$topics" else memory
    }

    // MARK: - 关系 / 成长 / 结构化记忆（decode + 纯助手）

    private suspend fun currentRelationshipSummary(character: CharacterEntity): String {
        // Android：当前关系 = 最后一条里程碑的 relationshipName（CharacterRepository.currentRelationship 同源），
        // 其 reason 即 iOS sortedMilestones.last.reason —— 一次查询两用。
        val last = milestoneDao.getForCharacter(character.uuid).lastOrNull()
        return Helpers.relationshipSummary(last?.relationshipName, last?.reason)
    }

    private fun growthStageSummary(character: CharacterEntity): String {
        val lastThree = GrowthJson.decodeGrowthLog(character.growthLogJSON).takeLast(3).map { it.summary }
        val quality = GrowthJson.decodeRelationshipQuality(character.relationshipQualityJSON)
        return Helpers.growthStageSummary(lastThree, quality)
    }

    private fun structuredMemorySummary(character: CharacterEntity): String {
        val sm = StructuredMemory.decode(character.structuredMemoryJSON)
        return Helpers.structuredMemorySummary(sm.nicknameFromChar, sm.insideJoke, sm.sharedLikes)
    }

    companion object {
        /** 每会话取近期消息条数（1:1 iOS per-conv fetchLimit）。 */
        private const val RECENT_PER_CONVERSATION = 6
        /** 全局取最近话题条数。 */
        private const val RECENT_GLOBAL = 6
    }

    /** 纯格式化/裁剪助手（无 DB，1:1 iOS 各 summary，便于单测）。 */
    internal object Helpers {
        /** 裁剪到 limit 字（超出加「…」），1:1 iOS `clipped`；截断按 codePoint 走，绝不切开代理对（图纸件⑧）。 */
        fun clipped(text: String, limit: Int): String {
            val trimmed = text.trim()
            return if (trimmed.codePointCount(0, trimmed.length) > limit) trimmed.takeCodePoints(limit) + "…" else trimmed
        }

        /** 最近情绪：空文本→未记录；无 emoji→纯文本；否则「emoji 文本」（1:1 iOS `currentMoodSummary`）。 */
        fun currentMoodSummary(lastMoodText: String, lastMoodEmoji: String): String {
            val moodText = lastMoodText.trim()
            if (moodText.isEmpty()) return "未记录"
            if (lastMoodEmoji.isEmpty()) return moodText
            return "$lastMoodEmoji $moodText"
        }

        /** 长期记忆摘要：空→暂无；否则裁 220（1:1 iOS `longMemorySummary`）。 */
        fun longMemorySummary(memorySummary: String): String {
            val summary = memorySummary.trim()
            return if (summary.isEmpty()) "暂无" else clipped(summary, 220)
        }

        /** 关系摘要：有名→（有 reason 时附「（reason）」）；无名→未知（1:1 iOS `currentRelationshipSummary`）。 */
        fun relationshipSummary(relationshipName: String?, reason: String?): String {
            if (!relationshipName.isNullOrEmpty()) {
                return if (!reason.isNullOrEmpty()) "$relationshipName（$reason）" else relationshipName
            }
            return "未知"
        }

        /**
         * 成长阶段：近 3 条成长日志摘要(非空)有则裁 120；否则用关系质量三维（1:1 iOS `growthStageSummary`）。
         * @param lastThreeGrowthSummaries 已取末 3 条的 summary（调用方 decode+takeLast(3)）
         */
        fun growthStageSummary(lastThreeGrowthSummaries: List<String>, quality: RelationshipQuality): String {
            val growth = lastThreeGrowthSummaries.filter { it.isNotEmpty() }.joinToString("；")
            if (growth.isNotEmpty()) return clipped(growth, 120)
            return "熟悉度${quality.familiarity}、信任感${quality.trust}、亲近感${quality.closeness}"
        }

        /** 结构化记忆摘要：称呼/梗/共同喜欢，空→暂无，否则裁 120（1:1 iOS `structuredMemorySummary`）。 */
        fun structuredMemorySummary(nicknameFromChar: String, insideJoke: String, sharedLikes: String): String {
            val parts = mutableListOf<String>()
            if (nicknameFromChar.isNotEmpty()) parts.add("TA 对用户的称呼是$nicknameFromChar")
            if (insideJoke.isNotEmpty()) parts.add("共同梗：$insideJoke")
            if (sharedLikes.isNotEmpty()) parts.add("共同喜欢：$sharedLikes")
            return if (parts.isEmpty()) "暂无" else clipped(parts.joinToString("；"), 120)
        }

        /** 单条话题预览：贴纸标记归一 + 换行转空格 + 裁 36（1:1 iOS topic preview）。 */
        fun formatTopicPreview(content: String): String =
            clipped(StickerTagParser.replaceStickerTagsForDisplay(content).replace("\n", " "), 36)

        /**
         * 把取回的近期消息（已按时间倒序）装配成「最近话题」预览串（最多 [limit] 条·保留入参顺序）：脏消息丢弃；
         * 结构化卡走单一事实源 [messageLlmSafeText] 脱敏（礼物 / 红包信封 → 无金额·通话 / 线下卡 → 丢弃；已拆红包结算
         * 事件 roleRaw='system'·因调用方 recentNonSystemForConversation 已排 system 故不达此 → 故事不带金额）。
         * **替代原 isOfflineEventCard 黑名单**——它只堵了线下卡，放行了礼物 / 红包 / 通话卡的原始 JSON（金币 / 金额 / 逐字稿，P3-R11）。
         * 每条再走 [formatTopicPreview]（贴纸归一 + 换行转空格 + 裁 36）。纯函数·DB 取数留给调用方。
         */
        fun recentTopicPreviews(messages: List<MessageEntity>, limit: Int): List<String> =
            messages.mapNotNull { msg ->
                val kind = MessageKind.fromRaw(msg.messageKindRaw)
                if (DirtyMessageDetector.isDirty(msg.content, kind)) return@mapNotNull null
                messageLlmSafeText(msg)?.let { formatTopicPreview(it) }
            }.take(limit)
    }
}
