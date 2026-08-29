package com.situ.aichat.moments

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.PersonalitySpectrum

/** A user's recent moment post, pre-formatted for the prompt's "friend has been posting" section. */
data class RecentUserPost(
    val timeDescription: String,
    val content: String,
    /**
     * 该动态是否配了图（契约 §B8）。渲染时补「（附带图片）」——对齐 `MomentChatContextService` 与
     * `MemoryDigestMaterialService` 两处既有口径。不带这个标注时，用户发的**纯图无文案**动态会渲染成
     * 一条空行（`- [昨天] `），角色连「用户昨天发过东西」都读不出来。
     */
    val hasImages: Boolean = false,
)

/**
 * 一条「用户近期动态」的正文渲染：纯图无文案时也要让角色知道「发了张图」，否则那一行是空的。
 *
 * 措辞口径（R3 🔵-5 订正原先「三处逐字一致」的说法）：
 * - **正文非空**时后缀「（附带图片）」——与 `MomentChatContextService` / `MemoryDigestMaterialService` 逐字一致；
 * - **正文为空**时另有一句「（一条只有图片的动态）」——这是本处独有的第四种措辞，有意为之：
 *   另两处对空正文会渲染成 `…发了动态（附带图片）：""` 那样带个空引号的怪句子，这里直接说清是什么。
 */
internal fun renderUserPostLine(post: RecentUserPost): String {
    val body = post.content.trim()
    return when {
        !post.hasImages -> body
        body.isEmpty() -> "（一条只有图片的动态）"
        else -> "$body（附带图片）"
    }
}

/**
 * 1:1 port of iOS `MomentGenerationActor.generatePostContent`'s system-prompt assembly
 * (Services/MomentGenerationActor+Content.swift). Pure function — all localized strings come from
 * [MomentPromptStrings], all dynamic data is passed in, so it unit-tests without a device/DB.
 *
 * Section order (verbatim iOS): identity → personality → optional setup/gender/occupation/backstory/
 * speaking-style/catchphrases/interests → hot interests → personality traits → (blank) → shared
 * memories → pet status → (blank) → friend's recent posts → now-context → schedule → gift inspiration
 * → "now write" → requirements (content/style/word-count/natural/no-AI/tone?/emoji/no-repeat/extra) →
 * (blank) → output-only.
 *
 * Stubs (graceful skip, leave hook): [petStatus] = pet status (P8), [giftInspiration] = gift moment
 * (P9). Both null by default → omitted exactly as iOS omits them when absent.
 */
object MomentPostPromptBuilder {

    /** Word-count fallback when no scene override (iOS `fallback: "50-150"`). */
    const val DEFAULT_WORD_COUNT_RANGE = "50-150"

    /**
     * 宠物状态注入块（moments-logic-1·1:1 iOS MomentGenerationActor+Content）。纯函数：
     * 宠物系统开 + 角色有宠物（name/物种/阶段齐全）+ **未离家出走** → 返回「养宠物」+「可偶尔提到」两行（`\n` 连，
     * 由 [build] 在其前补空行）；任一不满足（系统关/无宠物/已离家出走）→ null（整块省略）。
     */
    fun petStatusBlock(
        strings: MomentPromptStrings,
        petEnabled: Boolean,
        petName: String?,
        speciesDisplay: String?,
        stageDisplay: String?,
        isRanAway: Boolean,
    ): String? {
        if (!petEnabled || isRanAway) return null
        if (petName.isNullOrEmpty() || speciesDisplay.isNullOrEmpty() || stageDisplay.isNullOrEmpty()) return null
        return strings.petStatus.format(petName, speciesDisplay, stageDisplay) + "\n" + strings.petMention
    }

    fun build(
        strings: MomentPromptStrings,
        character: CharacterEntity,
        hotInterestNames: List<String>,
        personalityTraits: List<String>,
        recentUserPosts: List<RecentUserPost>,
        recentOwnContents: String,
        nowContext: String,
        schedulePrompt: String,
        giftInspiration: String? = null,
        petStatus: String? = null,
        overrides: Map<String, String> = emptyMap(),
        userName: String,
    ): String {
        val wordCountRange = resolveOverride(overrides, MomentsPromptField.WORD_COUNT_RANGE, DEFAULT_WORD_COUNT_RANGE)
        val toneStyle = resolveOverride(overrides, MomentsPromptField.TONE_STYLE, "")
        val emojiPolicy = resolveOverride(overrides, MomentsPromptField.EMOJI_POLICY, "")
        val extraRules = resolveOverride(overrides, MomentsPromptField.EXTRA_RULES, "")

        val parts = mutableListOf<String>()

        parts.add(strings.youAre.format(character.name))
        parts.add(strings.personality.format(character.personalityDescription))
        if (character.systemPrompt.isNotEmpty()) parts.add(strings.characterSetup.format(character.systemPrompt))
        if (character.gender.isNotEmpty()) parts.add(strings.gender.format(character.gender))
        if (character.occupation.isNotEmpty()) parts.add(strings.occupation.format(character.occupation))
        if (character.backstory.isNotEmpty()) parts.add(strings.backstory.format(character.backstory))
        if (character.speakingStyle.isNotEmpty()) parts.add(strings.speakingStyle.format(character.speakingStyle))
        if (character.catchphrases.isNotEmpty()) parts.add(strings.catchphrases.format(character.catchphrases))
        if (character.initialInterests.isNotEmpty()) parts.add(strings.interests.format(character.initialInterests))
        if (hotInterestNames.isNotEmpty()) parts.add(strings.hotInterests.format(hotInterestNames.joinToString(", ")))
        if (personalityTraits.isNotEmpty()) parts.add(strings.traits.format(personalityTraits.joinToString(", ")))

        parts.add("")
        if (character.memorySummary.isNotEmpty()) {
            parts.add(strings.memoriesHeader)
            parts.add(character.memorySummary)
        }
        // Pet status (P8): iOS inserts a blank line then the pet lines. Null → omit entirely.
        if (!petStatus.isNullOrEmpty()) {
            parts.add("")
            parts.add(petStatus)
        }
        parts.add("")

        if (recentUserPosts.isNotEmpty()) {
            parts.add(strings.userPostsHeader)
            for (post in recentUserPosts) parts.add("- [${post.timeDescription}] " + renderUserPostLine(post))
            // 背景声明（2026-07-07 加固）：明确这段只是参考背景，防弱模型被带成「回复」口吻
            // 输出聊天腔短句而非动态（当日假模型「嗯嗯，刚看到消息。」入库教训）。
            parts.add(strings.userPostsFooter)
            parts.add("")
        }

        parts.add(nowContext)
        parts.add("")

        if (schedulePrompt.isNotEmpty()) {
            parts.add(schedulePrompt)
            parts.add("")
        }

        // Gift inspiration (P9): hint is a full "context + writing instruction" block, no `##` header.
        if (!giftInspiration.isNullOrEmpty()) {
            parts.add(giftInspiration)
            parts.add("")
        }

        parts.add(strings.nowWrite)
        parts.add("")

        parts.add(strings.requirementsHeader)
        parts.add(strings.reqContent.format(userName)) // 图纸一·B5：reqContent 含 %s → 用真实用户名（你=角色+用户名）
        parts.add(strings.reqStyle)
        parts.add(strings.reqWordCount.format(wordCountRange))
        parts.add(strings.reqNatural)
        parts.add(strings.reqNoAi)
        if (toneStyle.isNotEmpty()) parts.add("- Tone/style preference: $toneStyle")
        if (emojiPolicy.isNotEmpty()) parts.add("- $emojiPolicy") else parts.add(strings.reqEmoji)
        if (recentOwnContents.isNotEmpty()) {
            parts.add(strings.reqNoRepeat)
            parts.add(recentOwnContents)
        }
        for (line in extraRules.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) parts.add("- $trimmed")
        }
        parts.add("")
        parts.add(strings.outputOnly)

        return parts.joinToString("\n")
    }

    /**
     * Hot interests for the prompt: dynamic interests with heat ≥ 60, sorted by descending heat, top 5,
     * names only (1:1 iOS `decodeDynamicInterests.filter{heat>=60}.sorted{heat>}.prefix(5).map{name}`).
     */
    fun hotInterestNames(dynamicInterests: List<DynamicInterest>): List<String> =
        dynamicInterests.filter { it.heat >= 60 }
            .sortedByDescending { it.heat }
            .take(5)
            .map { it.name }

    /**
     * Personality traits for the prompt: dimension value ≥ 70 → "high {name}", ≤ 30 → "low {name}",
     * in [PersonalitySpectrum.DIMENSION_NAMES] order (1:1 iOS). [spectrumValues] is `spectrum.values`.
     */
    fun personalityTraits(strings: MomentPromptStrings, spectrumValues: List<Int>): List<String> {
        val names = PersonalitySpectrum.DIMENSION_NAMES
        val traits = mutableListOf<String>()
        spectrumValues.forEachIndexed { i, value ->
            if (i < names.size) {
                if (value >= 70) traits.add(strings.traitHigh.format(names[i]))
                else if (value <= 30) traits.add(strings.traitLow.format(names[i]))
            }
        }
        return traits
    }

    private fun resolveOverride(overrides: Map<String, String>, field: MomentsPromptField, fallback: String): String =
        overrides[field.raw]?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}
