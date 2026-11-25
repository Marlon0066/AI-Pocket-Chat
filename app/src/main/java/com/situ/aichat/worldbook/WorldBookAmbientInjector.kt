package com.situ.aichat.worldbook

import android.util.Log
import com.situ.aichat.data.local.dao.WorldBookDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.PromptMacros
import com.situ.aichat.util.DateFormatters
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 外围场景世界书无状态激活协调器（Q2·2A）：
 * 为主动消息、角色朋友圈与评论、交换日记与日记评论回复提供统一的世界书激活与宏替换能力。
 *
 * - **入世角色隔离（3A）**：`character.joinedWorld == true` 直接返回 `null`，由 `WorldBookDao.activeBooksForCharacter` 双保险；
 * - **无时效副作用**：与 [com.situ.aichat.story.StoryWorldInfoService] 同口径，不读写会话 `timedStates`；
 * - **短任务预算保护**：字符预算取 `min(用户全局预算, AMBIENT_BUDGET_CAP)`，防止万字大书淹没 30 字主动消息或简短评论；
 * - **零回归承诺**：无书 / 未命中 / 异常时恒返回 `null`，调用方提示词逐字节不变。
 */
@Singleton
class WorldBookAmbientInjector @Inject constructor(
    private val worldBookDao: WorldBookDao,
    private val worldBookVectorService: WorldBookVectorService,
    private val settingsRepository: SettingsRepository,
) {
    private val activator = WorldInfoActivator()

    suspend fun buildAmbientSection(
        character: CharacterEntity,
        userName: String,
        userBio: String = "",
        contextText: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): String? {
        if (character.joinedWorld) return null
        return try {
            activateInternal(character, userName, userBio, contextText, nowMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "外围场景世界书激活跳过（${character.name}）：${e.message}")
            null
        }
    }

    private suspend fun activateInternal(
        character: CharacterEntity,
        userName: String,
        userBio: String,
        contextText: String,
        nowMillis: Long,
    ): String? {
        val books = worldBookDao.activeBooksForCharacter(character.uuid)
        if (books.isEmpty()) return null
        val entries = worldBookDao.entriesForBooks(books.map { it.uuid })
        if (entries.isEmpty()) return null

        val appSettings = settingsRepository.getAppSettings()
        val settings = appSettings.toWorldInfoSettings().copy(
            scanDepth = 1,
            budgetChars = minOf(appSettings.worldInfoBudgetChars, AMBIENT_BUDGET_CAP),
        )

        val trimmedContext = contextText.trim()
        val vectorCandidates = entries.filter { it.vectorized && it.enabled }
        val vectorMatched = if (vectorCandidates.isEmpty() || trimmedContext.isEmpty()) {
            emptySet()
        } else {
            worldBookVectorService.matchedEntryUuids(
                candidates = vectorCandidates,
                queryText = trimmedContext,
                thresholdPercent = appSettings.vectorSearchThreshold,
            )
        }

        val scanMessages = if (trimmedContext.isEmpty()) {
            listOf(ScanMessage(text = character.name, senderName = character.name))
        } else {
            listOf(ScanMessage(text = trimmedContext, senderName = userName))
        }

        val result = activator.activate(
            WorldInfoActivationInput(
                books = books,
                entries = entries,
                messages = scanMessages,
                conversationMessageCount = AMBIENT_MESSAGE_COUNT,
                conversationUuid = "ambient:${character.uuid}",
                timedStates = emptyList(),
                vectorMatchedEntryUuids = vectorMatched,
                settings = settings,
                characterName = character.name,
                userName = userName,
                matchSources = buildCharacterMatchSources(character, userBio),
            ),
        )
        if (result.isEmpty) return null

        val merged = buildList {
            add(result.before)
            add(result.after)
            add(result.suffix)
            result.atDepth.forEach { add(it.content) }
        }.filter { it.isNotBlank() }.joinToString("\n")
        if (merged.isBlank()) return null

        val macros = mapOf(
            PromptMacros.CHAR to character.name,
            PromptMacros.USER to userName,
            PromptMacros.NOW to DateFormatters.yearMonthDayHourMinute(nowMillis),
        )
        return PromptMacros.resolveWorldMacros(merged, macros).takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "WorldBookAmbient"
        const val AMBIENT_BUDGET_CAP = 2500
        const val AMBIENT_MESSAGE_COUNT = Int.MAX_VALUE / 2
    }
}
