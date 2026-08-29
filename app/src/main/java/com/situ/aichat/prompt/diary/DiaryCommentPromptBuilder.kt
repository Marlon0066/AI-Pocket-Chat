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
        /**
         * 日记附图张数（图片多模态一期 §B8）。>0 时注入盲图提示——从前日记链路对图片**完全无感知**：
         * 用户写「今天去看海」配 9 张海景照，角色评论时连「有图」都不知道，很容易写出与照片脱节的话。
         * 这里只补「知道有图、但看不到」，真看图属日记二期（本卷不做）。
         */
        photoCount: Int = 0,
    ): String {
        val parts = mutableListOf<String>()
        parts.add(strings.intro.format(characterName, personality))
        if (systemPrompt.isNotEmpty()) parts.add(strings.setup.format(systemPrompt))
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

    /**
     * 角色回应用户回复的 system prompt（R3 评论回复一轮）。与 [build] 同骨架，追加「你之前的评论 +
     * 用户的回复」两行对话上下文，指令换成「回应这条回复」；要求段去掉 reqTone（回应是接话，
     * 不再需要「鼓励/共鸣/追问/调侃」的开题建议）。纯函数可单测。
     */
    fun buildReply(
        strings: DiaryCommentPromptStrings,
        characterName: String,
        personality: String,
        systemPrompt: String,
        userName: String,
        entryContent: String,
        rootComment: String,
        userReply: String,
        /** 同 [build]：>0 时注入盲图提示——回复线程里也该知道日记附了照片（首条评论知道、回复却失忆很割裂）。 */
        photoCount: Int = 0,
    ): String {
        val parts = mutableListOf<String>()
        parts.add(strings.intro.format(characterName, personality))
        if (systemPrompt.isNotEmpty()) parts.add(strings.setup.format(systemPrompt))
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

    /**
     * 作者回应用户在**TA 的交换日记**下的顶层留言（R6-1）。与 [buildReply] 的差别在视角：R3 里角色是
     * 「朋友日记的评论者」，这里角色是「日记作者本人」——上下文换成「你今天写了一篇日记 + 用户读后留言」，
     * 指令换成「回应这条留言」；要求段与 [buildReply] 同（无 reqTone）。纯函数可单测。
     */
    fun buildExchangeReply(
        strings: DiaryCommentPromptStrings,
        characterName: String,
        personality: String,
        systemPrompt: String,
        userName: String,
        entryContent: String,
        userComment: String,
        /** 同 [build]：>0 时注入盲图提示——回复线程里也该知道日记附了照片（首条评论知道、回复却失忆很割裂）。 */
        photoCount: Int = 0,
    ): String {
        val parts = mutableListOf<String>()
        parts.add(strings.intro.format(characterName, personality))
        if (systemPrompt.isNotEmpty()) parts.add(strings.setup.format(systemPrompt))
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
