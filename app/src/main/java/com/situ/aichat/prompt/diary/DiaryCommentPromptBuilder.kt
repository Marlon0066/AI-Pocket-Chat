package com.situ.aichat.prompt.diary

import com.situ.aichat.R
import com.situ.aichat.prompt.PromptStrings

/**
 * 日记角色评论提示词的本地化模板（M07 7.1.3）。同 [DiaryPromptStrings]，iOS 评论提示词也是双语
 * （`String(localized:)`）→ 走资源 + [PromptStrings] 解析。含参字段保留 `%1$s`/`%2$s` 占位。
 */
data class DiaryCommentPromptStrings(
    val intro: String,
    val setup: String,
    val friendWrote: String,
    val entryQuote: String,
    val write: String,
    val reqHeader: String,
    val reqPersonality: String,
    val reqConcise: String,
    val reqGenuine: String,
    val reqTone: String,
    val reqNoAi: String,
    val outputOnly: String,
    val userMessage: String,
    // R3 评论回复一轮：角色回应用户回复（原评论 + 用户回复两行上下文 + 回应指令）。
    val replyPrevComment: String,
    val replyUserReplied: String,
    val replyWrite: String,
    val replyUserMessage: String,
    // R6-1 交换日记留言一轮：作者回应用户在 TA 的信下的顶层留言（角色是作者，不是评论者）。
    val exchangeYouWrote: String,
    val exchangeUserCommented: String,
    val exchangeReplyWrite: String,
    /** 「这篇日记附了 N 张照片，你看不到」——照朋友圈 photosBlind 同构（图片多模态一期 §B8）。 */
    val photosBlind: String,
) {
    companion object {
        fun from(strings: PromptStrings): DiaryCommentPromptStrings = DiaryCommentPromptStrings(
            intro = strings.s(R.string.diary_comment_intro),
            setup = strings.s(R.string.diary_comment_setup),
            friendWrote = strings.s(R.string.diary_comment_friend_wrote),
            photosBlind = strings.s(R.string.diary_comment_photos_blind),
            entryQuote = strings.s(R.string.diary_comment_entry_quote),
            write = strings.s(R.string.diary_comment_write),
            reqHeader = strings.s(R.string.diary_comment_req_header),
            reqPersonality = strings.s(R.string.diary_comment_req_personality),
            reqConcise = strings.s(R.string.diary_comment_req_concise),
            reqGenuine = strings.s(R.string.diary_comment_req_genuine),
            reqTone = strings.s(R.string.diary_comment_req_tone),
            reqNoAi = strings.s(R.string.diary_comment_req_no_ai),
            outputOnly = strings.s(R.string.diary_comment_output_only),
            userMessage = strings.s(R.string.diary_comment_user_message),
            replyPrevComment = strings.s(R.string.diary_reply_prev_comment),
            replyUserReplied = strings.s(R.string.diary_reply_user_replied),
            replyWrite = strings.s(R.string.diary_reply_write),
            replyUserMessage = strings.s(R.string.diary_reply_user_message),
            exchangeYouWrote = strings.s(R.string.diary_exchange_reply_you_wrote),
            exchangeUserCommented = strings.s(R.string.diary_exchange_reply_user_commented),
            exchangeReplyWrite = strings.s(R.string.diary_exchange_reply_write),
        )
    }
}

/**
 * 单条日记评论 system prompt 的纯函数装配（1:1 iOS `generateDiaryCommentContent`）。
 * 角色设定行仅 [systemPrompt] 非空时出现。纯函数：可不依赖资源单测 section 顺序/条件行。
 */
object DiaryCommentPromptBuilder {
    fun build(
        strings: DiaryCommentPromptStrings,
        characterName: String,
        personality: String,
        systemPrompt: String,
        userName: String,
        entryContent: String,
        photoCount: Int = 0,
        worldInfoSection: String? = null,
    ): String {
        val parts = mutableListOf<String>()
        parts.add(strings.intro.format(characterName, personality))
        if (systemPrompt.isNotEmpty()) parts.add(strings.setup.format(systemPrompt))
        if (!worldInfoSection.isNullOrBlank()) {
            parts.add("")
            parts.add("## 世界观设定")
            parts.add(worldInfoSection)
        }
        parts.add("")
        parts.add(strings.friendWrote.format(userName))
        parts.add(strings.entryQuote.format(entryContent))
        if (photoCount > 0) parts.add(strings.photosBlind.format(photoCount))
        parts.add("")
        parts.add(strings.write)
        parts.add("")
        parts.add(strings.reqHeader)
        parts.add(strings.reqPersonality)
        parts.add(strings.reqConcise)
        parts.add(strings.reqGenuine)
        parts.add(strings.reqTone)
        parts.add(strings.reqNoAi)
        parts.add("")
        parts.add(strings.outputOnly)
        return parts.joinToString("\n")
    }

    fun buildReply(
        strings: DiaryCommentPromptStrings,
        characterName: String,
        personality: String,
        systemPrompt: String,
        userName: String,
        entryContent: String,
        rootComment: String,
        userReply: String,
        photoCount: Int = 0,
        worldInfoSection: String? = null,
    ): String {
        val parts = mutableListOf<String>()
        parts.add(strings.intro.format(characterName, personality))
        if (systemPrompt.isNotEmpty()) parts.add(strings.setup.format(systemPrompt))
        if (!worldInfoSection.isNullOrBlank()) {
            parts.add("")
            parts.add("## 世界观设定")
            parts.add(worldInfoSection)
        }
        parts.add("")
        parts.add(strings.friendWrote.format(userName))
        parts.add(strings.entryQuote.format(entryContent))
        if (photoCount > 0) parts.add(strings.photosBlind.format(photoCount))
        parts.add("")
        parts.add(strings.replyPrevComment.format(rootComment))
        parts.add(strings.replyUserReplied.format(userName, userReply))
        parts.add("")
        parts.add(strings.replyWrite)
        parts.add("")
        parts.add(strings.reqHeader)
        parts.add(strings.reqPersonality)
        parts.add(strings.reqConcise)
        parts.add(strings.reqGenuine)
        parts.add(strings.reqNoAi)
        parts.add("")
        parts.add(strings.outputOnly)
        return parts.joinToString("\n")
    }

    fun buildExchangeReply(
        strings: DiaryCommentPromptStrings,
        characterName: String,
        personality: String,
        systemPrompt: String,
        userName: String,
        entryContent: String,
        userComment: String,
        photoCount: Int = 0,
        worldInfoSection: String? = null,
    ): String {
        val parts = mutableListOf<String>()
        parts.add(strings.intro.format(characterName, personality))
        if (systemPrompt.isNotEmpty()) parts.add(strings.setup.format(systemPrompt))
        if (!worldInfoSection.isNullOrBlank()) {
            parts.add("")
            parts.add("## 世界观设定")
            parts.add(worldInfoSection)
        }
        parts.add("")
        parts.add(strings.exchangeYouWrote)
        parts.add(strings.entryQuote.format(entryContent))
        if (photoCount > 0) parts.add(strings.photosBlind.format(photoCount))
        parts.add("")
        parts.add(strings.exchangeUserCommented.format(userName, userComment))
        parts.add("")
        parts.add(strings.exchangeReplyWrite)
        parts.add("")
        parts.add(strings.reqHeader)
        parts.add(strings.reqPersonality)
        parts.add(strings.reqConcise)
        parts.add(strings.reqGenuine)
        parts.add(strings.reqNoAi)
        parts.add("")
        parts.add(strings.outputOnly)
        return parts.joinToString("\n")
    }
}
