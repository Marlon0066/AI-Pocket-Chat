package com.situ.aichat.worldbook

import android.util.Log
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.WorldBookDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MessageKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AppSettings.worldInfo* → 引擎设置（WB7c·契约 §12.7 收口 WB4 预留的参数单缝）。
 * 每回合由 AssistantTurnEngine 现读现建——触发设置改完即用（热更新 §12.11-3）。
 */
fun AppSettings.toWorldInfoSettings(): WorldInfoSettings = WorldInfoSettings(
    scanDepth = worldInfoScanDepth,
    budgetChars = worldInfoBudgetChars,
    recursiveScan = worldInfoRecursiveScan,
    maxRecursionSteps = worldInfoMaxRecursionSteps,
    caseSensitive = worldInfoCaseSensitive,
    matchWholeWords = worldInfoMatchWholeWords,
    insertionStrategy = runCatching { WorldInfoInsertionStrategy.valueOf(worldInfoInsertionStrategy) }
        .getOrDefault(WorldInfoInsertionStrategy.CHARACTER_FIRST),
)

/** 从角色与用户档案构造条目级 `match*` 扩展匹配源（聊天与外围场景共用单一事实源）。 */
internal fun buildCharacterMatchSources(character: CharacterEntity?, userBio: String = ""): WorldInfoMatchSources {
    if (character == null && userBio.isBlank()) return WorldInfoMatchSources()
    val desc = if (character == null) {
        ""
    } else {
        listOf(
            character.systemPrompt,
            character.backstory,
            character.appearanceDescription,
            character.occupation,
        ).filter { it.isNotBlank() }.joinToString("\n")
    }
    val personality = if (character == null) {
        ""
    } else {
        listOf(
            character.personalityDescription,
            character.speakingStyle,
            character.catchphrases,
            character.initialInterests,
        ).filter { it.isNotBlank() }.joinToString("\n")
    }
    return WorldInfoMatchSources(
        characterDescription = desc,
        characterPersonality = personality,
        characterDepthPrompt = character?.systemPrompt.orEmpty(),
        scenario = character?.backstory.orEmpty(),
        personaDescription = userBio,
    )
}

/** 提取单条消息供世界书扫描的文本：纯图占位若有视觉摘要则用摘要代替 `[图片]`，引用消息拼入被引用原文。 */
internal fun extractWorldBookScanText(msg: MessageEntity): String {
    val raw = msg.content.trim()
    val body = if (raw == IMAGE_PLACEHOLDER_LITERAL && msg.mediaMemorySummary.isNotBlank()) {
        msg.mediaMemorySummary.trim()
    } else {
        msg.content
    }
    val quote = msg.quotedContent?.trim().orEmpty()
    return if (quote.isNotEmpty()) "$quote\n$body" else body
}

private const val IMAGE_PLACEHOLDER_LITERAL = "[图片]"

/**
 * 世界书 → 聊天提示词的回合级编排（WB4·契约 §4.3/§4.4）：
 * 取「未入世角色的启用全局书 ∪ 角色绑定书」→ 跑 [WorldInfoActivator] → 时效状态就地增删 → 交回激活结果。
 */
@Singleton
class WorldBookPromptService @Inject constructor(
    private val worldBookDao: WorldBookDao,
    private val worldBookVectorService: WorldBookVectorService,
    private val messageDao: MessageDao,
) {
    private val activator = WorldInfoActivator()

    suspend fun activateForTurn(
        characterUuid: String,
        conversationUuid: String,
        sortedMessages: List<MessageEntity>,
        characterName: String,
        userName: String,
        /** 链接条目的相似度阈值 %（对齐 AppSettings.vectorSearchThreshold；0 = 向量触发关）。 */
        vectorThresholdPercent: Int = 0,
        settings: WorldInfoSettings = WorldInfoSettings(),
        character: CharacterEntity? = null,
        userBio: String = "",
        /** 线上/线下会话过滤（null = 不过滤以兼容无会话上下文的调用；传入真值时严格隔离线上与当前线下场）。 */
        isOfflineMode: Boolean? = null,
        currentOfflineSessionId: String? = null,
    ): WorldInfoActivationResult? {
        if (character?.joinedWorld == true) return null
        val books = worldBookDao.activeBooksForCharacter(characterUuid)
        if (books.isEmpty()) return null
        val entries = worldBookDao.entriesForBooks(books.map { it.uuid })
        if (entries.isEmpty()) return null
        val timedStates = worldBookDao.timedStatesForConversation(conversationUuid)

        val scannable = sortedMessages.filter { m ->
            val kind = MessageKind.fromRaw(m.messageKindRaw)
            if (m.roleRaw == "system" || m.isHeldForDelivery || kind.isStructuredCard || kind == MessageKind.SYSTEM_HINT) {
                return@filter false
            }
            val modePass = when (isOfflineMode) {
                null -> true
                false -> !m.isOfflineMode
                true -> m.isOfflineMode &&
                    (currentOfflineSessionId.isNullOrEmpty() || m.offlineSessionId == currentOfflineSessionId)
            }
            modePass && extractWorldBookScanText(m).isNotBlank()
        }
        val scanMessages = scannable.takeLast(SCAN_MESSAGE_WINDOW).map { m ->
            ScanMessage(
                text = extractWorldBookScanText(m),
                senderName = if (m.roleRaw == "user") userName else characterName,
            )
        }

        // WB5 链接条目：语义相似触发（查询 = 最近一条有效用户消息文本）。
        val vectorCandidates = entries.filter { it.vectorized && it.enabled }
        val vectorMatched = if (vectorCandidates.isEmpty()) {
            emptySet()
        } else {
            worldBookVectorService.matchedEntryUuids(
                candidates = vectorCandidates,
                queryText = scannable.lastOrNull { it.roleRaw == "user" }?.let(::extractWorldBookScanText).orEmpty(),
                thresholdPercent = vectorThresholdPercent,
            )
        }

        val result = activator.activate(
            WorldInfoActivationInput(
                books = books,
                entries = entries,
                messages = scanMessages,
                conversationMessageCount = messageDao.countScannableForWorldBook(conversationUuid),
                conversationUuid = conversationUuid,
                timedStates = timedStates,
                vectorMatchedEntryUuids = vectorMatched,
                settings = settings,
                characterName = characterName,
                userName = userName,
                matchSources = buildCharacterMatchSources(character, userBio),
            ),
        )

        result.expiredTimedStates.forEach {
            worldBookDao.clearTimedState(it.conversationUuid, it.entryUuid, it.effectType)
        }
        result.newTimedStates.forEach { worldBookDao.upsertTimedState(it) }

        val d = result.diagnostics
        if (d.activated.isNotEmpty() || d.droppedByBudget.isNotEmpty() || d.badRegexKeys.isNotEmpty()) {
            Log.i(
                TAG,
                "激活 ${d.activated.size} 条[${d.activated.joinToString { "${it.title}(${it.contentLength}字)" }}]" +
                    " 预算裁掉 ${d.droppedByBudget.size} 条" +
                    (if (d.droppedByBudget.isNotEmpty()) "[${d.droppedByBudget.joinToString { it.title }}]" else "") +
                    " 扫描轮=${d.sweepCount}" +
                    (if (d.badRegexKeys.isNotEmpty()) " 坏正则降级=${d.badRegexKeys}" else ""),
            )
        }
        return result.takeIf { !it.isEmpty }
    }

    private companion object {
        const val TAG = "WorldBookPrompt"

        /** 交给引擎的扫描消息窗上限（覆盖最大合理扫描深度与 minActivations 扩窗需求）。 */
        const val SCAN_MESSAGE_WINDOW = 50
    }
}
