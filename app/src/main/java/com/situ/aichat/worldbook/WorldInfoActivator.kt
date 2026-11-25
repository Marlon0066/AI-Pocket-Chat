package com.situ.aichat.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.data.worldbook.decodeStringList
import com.situ.aichat.data.worldbook.lenientBool
import com.situ.aichat.data.worldbook.parseExtras

/**
 * 世界书激活引擎（WB3·契约 §4.2 八步算法·纯逻辑零 Android 依赖）。
 * 每次发消息前跑一遍：输入 =（生效书 + 条目 + 最近消息 + 会话时效状态 + 设置），
 * 输出 = 按四锚点分桶的注入文本 + 时效状态增删 + 诊断（WB4 接进 PromptBuilder）。
 *
 * 流程：主扫（时效门 → 蓝灯/向量/关键词 → 概率）→ 分组决胜 → minActivations 扩窗 →
 * 递归轮（激活内容并入扫描源·delayUntilRecursion 按层级解锁·干涸或达步数上限即止）→
 * 时效状态产出 → 预算裁剪 → 分桶。
 */
class WorldInfoActivator(private val rng: WorldInfoRng = WorldInfoRng.fromRandom()) {

    fun activate(input: WorldInfoActivationInput): WorldInfoActivationResult {
        val settings = input.settings
        val booksByUuid = input.books.filter { it.enabled }.associateBy { it.uuid }
        val candidates = input.entries.filter { it.enabled && it.bookUuid in booksByUuid }
        val outletSkipped = candidates.count { it.position == 7 }
        val eligible = candidates.filter { it.position != 7 }

        val matcher = WorldInfoMatcher(
            settings = settings,
            characterName = input.characterName,
            userName = input.userName,
        )
        val timed = WorldInfoTimedEffects(input.timedStates, input.conversationMessageCount)
        val groups = WorldInfoGroupResolver(rng, settings.useGroupScoring)

        val activated = LinkedHashMap<String, ActivatedCandidate>()
        val probabilityFailed = mutableSetOf<String>()
        val recursionText = StringBuilder()
        var sweeps = 0

        val bufferCache = HashMap<Int, String>()
        val extraSourceCache = HashMap<String, String>()
        fun chatBuffer(depth: Int) = bufferCache.getOrPut(depth) { matcher.buildBuffer(input.messages, depth) }
        fun effectiveDepth(e: WorldBookEntryEntity): Int =
            e.scanDepth ?: booksByUuid[e.bookUuid]?.scanDepth ?: settings.scanDepth

        fun entryExtraBuffer(e: WorldBookEntryEntity): String {
            if (input.matchSources.isEmpty || e.extraJson.isEmpty() || !e.extraJson.contains("match")) return ""
            return extraSourceCache.getOrPut(e.uuid) { buildExtraMatchBuffer(e.extraJson, input.matchSources) }
        }

        fun withExtraSources(base: String, e: WorldBookEntryEntity): String {
            val extra = entryExtraBuffer(e)
            return when {
                extra.isEmpty() -> base
                base.isEmpty() -> extra
                else -> "$base\n$extra"
            }
        }

        /** 一轮扫描：时效门 → 命中判定 → 概率 → 分组决胜 → 落账 + 并入递归源。 */
        fun sweep(
            pool: List<WorldBookEntryEntity>,
            bufferFor: (WorldBookEntryEntity) -> String,
        ): List<ActivatedCandidate> {
            sweeps++
            val newly = mutableListOf<ActivatedCandidate>()
            for (e in pool) {
                if (activated.containsKey(e.uuid) || e.uuid in probabilityFailed) continue
                if (timed.delayBlocked(e)) continue
                val viaSticky = timed.stickyActive(e.uuid)
                if (!viaSticky && timed.cooldownBlocked(e.uuid)) continue
                var matchCount = 0
                val hit = when {
                    viaSticky -> true
                    e.constant -> true
                    e.vectorized -> e.uuid in input.vectorMatchedEntryUuids
                    else -> {
                        val m = matcher.matchEntry(e, withExtraSources(bufferFor(e), e))
                        matchCount = m.matchCount
                        m.matched
                    }
                }
                if (!hit) continue
                if (!viaSticky && e.useProbability && e.probability < 100) {
                    if (e.probability <= 0 || rng.nextInt(100) >= e.probability) {
                        probabilityFailed.add(e.uuid)
                        continue
                    }
                }
                newly += ActivatedCandidate(e, booksByUuid.getValue(e.bookUuid), matchCount, viaSticky)
            }
            val winners = groups.filter(newly, activated.values)
            winners.forEach { c ->
                activated[c.entry.uuid] = c
                if (!c.entry.preventRecursion && c.entry.content.isNotBlank()) {
                    recursionText
                        .append(expandInlineEntityMacros(c.entry.content, input.characterName, input.userName))
                        .append('\n')
                }
            }
            return winners
        }

        // ── 第 1 步：主扫（delayUntilRecursion 条目按义只在递归轮参战） ──
        val primaryPool = eligible.filter { it.delayUntilRecursion <= 0 }
        sweep(primaryPool) { e -> chatBuffer(effectiveDepth(e)) }

        // ── 第 2 步：激活数不足 → 向更早历史逐级扩窗（受 maxDepth 上限） ──
        if (settings.minActivations > 0 && activated.size < settings.minActivations) {
            val depthCap = if (settings.minActivationsMaxDepth > 0) {
                minOf(settings.minActivationsMaxDepth, input.messages.size)
            } else {
                input.messages.size
            }
            var depth = settings.scanDepth + 1
            while (activated.size < settings.minActivations && depth <= depthCap) {
                sweep(primaryPool) { chatBuffer(depth) }
                depth++
            }
        }

        // ── 第 3 步：递归轮（全局开关或书级 recursiveScanning 开启；层级解锁 + 步数上限） ──
        val bookRecursive = booksByUuid.values.any { it.recursiveScanning == true }
        val recursiveEnabled = settings.recursiveScan || bookRecursive
        val maxSteps = if (!settings.recursiveScan && bookRecursive && settings.maxRecursionSteps == 0) {
            DEFAULT_BOOK_RECURSION_CAP
        } else {
            settings.maxRecursionSteps
        }
        if (recursiveEnabled) {
            var level = 1
            var steps = 0
            fun nextUnlockLevel(): Int? = eligible
                .filter { !activated.containsKey(it.uuid) && !it.excludeRecursion && it.delayUntilRecursion > level }
                .minOfOrNull { it.delayUntilRecursion }
            while (true) {
                val pool = eligible.filter {
                    !activated.containsKey(it.uuid) && !it.excludeRecursion && it.delayUntilRecursion <= level
                }
                val newly = if (pool.isEmpty()) {
                    emptyList()
                } else {
                    sweep(pool) { e ->
                        val base = chatBuffer(effectiveDepth(e))
                        if (recursionText.isEmpty()) base else base + "\n" + recursionText
                    }
                }
                if (newly.isNotEmpty()) {
                    steps++
                    if (maxSteps > 0 && steps >= maxSteps) break
                    continue
                }
                level = nextUnlockLevel() ?: break
            }
        }

        // ── 第 4 步：时效状态产出（续贴不重复产；冷却接在保持窗之后） ──
        val newTimedStates = activated.values
            .filter { !it.viaSticky }
            .flatMap { timed.statesForTrigger(it.entry, input.conversationUuid) }

        // ── 第 5/6 步：预算裁剪 + 分桶 ──
        val outcome = WorldInfoBudgeter.applyBudget(activated.values.toList(), settings.budgetChars)
        val (before, after, suffix) = WorldInfoBudgeter.assembleBuckets(outcome.kept, settings.insertionStrategy)
        val atDepth = WorldInfoBudgeter.assembleAtDepth(outcome.kept, settings.insertionStrategy)

        return WorldInfoActivationResult(
            before = before,
            after = after,
            suffix = suffix,
            atDepth = atDepth,
            newTimedStates = newTimedStates,
            expiredTimedStates = timed.expired,
            diagnostics = WorldInfoDiagnostics(
                activated = outcome.kept.map { it.summary() },
                droppedByBudget = outcome.dropped.map { it.summary() },
                badRegexKeys = matcher.badRegexKeys.toList(),
                sweepCount = sweeps,
                outletSkippedCount = outletSkipped,
            ),
        )
    }

    private fun ActivatedCandidate.summary() = ActivatedEntrySummary(
        uid = entry.uid,
        title = entry.comment.ifBlank { decodeStringList(entry.keysJson).firstOrNull() ?: "(无标题)" },
        bookName = book.name,
        contentLength = entry.content.length,
    )

    private fun buildExtraMatchBuffer(extraJson: String, sources: WorldInfoMatchSources): String {
        val obj = parseExtras(extraJson)
        if (obj.isEmpty()) return ""
        val ext = obj["extensions"] as? kotlinx.serialization.json.JsonObject
        fun flag(camel: String, snake: String): Boolean =
            obj.lenientBool(camel) ?: ext?.lenientBool(snake) ?: false

        return buildList {
            if (flag("matchCharacterDescription", "match_character_description") && sources.characterDescription.isNotBlank()) {
                add(sources.characterDescription)
            }
            if (flag("matchCharacterPersonality", "match_character_personality") && sources.characterPersonality.isNotBlank()) {
                add(sources.characterPersonality)
            }
            if (flag("matchCharacterDepthPrompt", "match_character_depth_prompt") && sources.characterDepthPrompt.isNotBlank()) {
                add(sources.characterDepthPrompt)
            }
            if (flag("matchScenario", "match_scenario") && sources.scenario.isNotBlank()) {
                add(sources.scenario)
            }
            if (flag("matchCreatorNotes", "match_creator_notes") && sources.creatorNotes.isNotBlank()) {
                add(sources.creatorNotes)
            }
            if (flag("matchPersonaDescription", "match_persona_description") && sources.personaDescription.isNotBlank()) {
                add(sources.personaDescription)
            }
        }.joinToString("\n")
    }

    private companion object {
        /** 书级显式开启递归但全局未配上限时的安全默认轮数上限。 */
        const val DEFAULT_BOOK_RECURSION_CAP = 3
    }
}

/** 展开文本中的 `{{char}}` / `{{user}}`（大小写无关；无 `{{` 快速短路零分配）。 */
internal fun expandInlineEntityMacros(text: String, characterName: String, userName: String): String {
    if (!text.contains("{{")) return text
    var resolved = text
    if (characterName.isNotEmpty()) resolved = resolved.replace("{{char}}", characterName, ignoreCase = true)
    if (userName.isNotEmpty()) resolved = resolved.replace("{{user}}", userName, ignoreCase = true)
    return resolved
}
