package com.situ.aichat.moments

import com.situ.aichat.data.local.entity.CharacterEntity

/** An existing comment shown to the model, pre-formatted (time + author name + content). */
data class CommentContextLine(val timeDescription: String, val authorName: String, val content: String)

/** The comment being replied to (AI-replies-to-AI / replies-to-user), pre-resolved. */
data class CommentReplyTarget(val timeDescription: String, val authorName: String, val content: String)

/**
 * 1:1 port of iOS `generateCommentContent`'s system-prompt assembly
 * (Services/MomentGenerationActor+Content.swift:237-389). Pure function — localized strings via
 * [MomentCommentPromptStrings], all dynamic data passed in.
 *
 * Section order (verbatim iOS): identity+personality → optional gender/occupation/backstory/
 * speaking-style/catchphrases/interests → (blank) → "friend posted" + quoted post → (blank) →
 * emotion-tone → [AI-AI note] → now-context → [schedule context] → [photo blind/vision note] →
 * [other comments + react note] → (blank) → reply-target-or-write → (blank) → requirements (4) →
 * (blank) → output-only.
 */
object MomentCommentPromptBuilder {

    /** Max prior comments shown to the model (iOS `.prefix(5)`). */
    private const val MAX_EXISTING_COMMENTS = 5

    fun build(
        strings: MomentCommentPromptStrings,
        character: CharacterEntity,
        postAuthorName: String,
        postTimeDescription: String,
        postContent: String,
        isPostByCharacter: Boolean,
        nowContext: String,
        scheduleContext: String?,
        photoCount: Int,
        visionEnabled: Boolean,
        existingComments: List<CommentContextLine>,
        replyTarget: CommentReplyTarget?,
        mentionedByPoster: Boolean = false,
        /** Q2·2A：角色生效的世界书归并段（null/空 ⇒ 逐字节不变）。 */
        worldInfoSection: String? = null,
    ): String {
        val parts = mutableListOf<String>()

        parts.add(strings.intro.format(character.name, character.personalityDescription))
        if (character.gender.isNotEmpty()) parts.add(strings.gender.format(character.gender))
        if (character.occupation.isNotEmpty()) parts.add(strings.occupation.format(character.occupation))
        if (character.backstory.isNotEmpty()) parts.add(strings.backstory.format(character.backstory))
        if (character.speakingStyle.isNotEmpty()) parts.add(strings.speakingStyle.format(character.speakingStyle))
        if (character.catchphrases.isNotEmpty()) parts.add(strings.catchphrases.format(character.catchphrases))
        if (character.initialInterests.isNotEmpty()) parts.add(strings.interests.format(character.initialInterests))
        if (!worldInfoSection.isNullOrBlank()) {
            parts.add("")
            parts.add("## 世界观设定")
            parts.add(worldInfoSection)
        }

        parts.add("")
        parts.add(strings.friendPosted.format(postAuthorName))
        parts.add("[$postTimeDescription] 「$postContent」")
        if (mentionedByPoster) parts.add(strings.mentionedYou.format(postAuthorName))

        parts.add("")
        parts.add(strings.emotionTone)

        if (isPostByCharacter) {
            parts.add("")
            parts.add(strings.aiAi)
        }

        parts.add("")
        parts.add(nowContext)

        if (!scheduleContext.isNullOrEmpty()) {
            parts.add("")
            parts.add(scheduleContext)
        }

        // Photo note (no preceding blank line, per iOS).
        if (photoCount > 0 && !visionEnabled) {
            parts.add(strings.photosBlind.format(photoCount))
        } else if (photoCount > 0 && visionEnabled) {
            parts.add(strings.photosVision.format(photoCount))
        }

        if (existingComments.isNotEmpty()) {
            parts.add("")
            parts.add(strings.othersHeader)
            for (c in existingComments.take(MAX_EXISTING_COMMENTS)) {
                parts.add("- [${c.timeDescription}] ${c.authorName}: ${c.content}")
            }
            parts.add(strings.othersReact)
        }

        parts.add("")
        if (replyTarget != null) {
            parts.add("[${replyTarget.timeDescription}] " + strings.saidToYou.format(replyTarget.authorName, replyTarget.content))
            parts.add(strings.replyInstruction)
        } else {
            parts.add(strings.writeInstruction)
        }

        parts.add("")
        parts.add(strings.requirementsHeader)
        parts.add(strings.reqPersonality)
        parts.add(strings.reqConcise)
        parts.add(strings.reqFriends)
        parts.add(strings.reqNoAi)
        parts.add("")
        parts.add(strings.outputOnly)

        return parts.joinToString("\n")
    }

    /**
     * Comment-time schedule context line (1:1 iOS `buildCommentScheduleContext`). Caller passes the
     * current schedule event's fields (or returns null when there's no current non-userInteraction
     * event / schedule disabled).
     */
    fun scheduleLine(
        strings: MomentCommentPromptStrings,
        activity: String,
        location: String,
        moodText: String?,
    ): String {
        var line = strings.schedCurrent.format(activity)
        if (location.isNotEmpty()) line += strings.schedAt.format(location)
        if (!moodText.isNullOrEmpty()) line += strings.schedFeeling.format(moodText)
        line += strings.schedSuffix
        return line
    }
}
