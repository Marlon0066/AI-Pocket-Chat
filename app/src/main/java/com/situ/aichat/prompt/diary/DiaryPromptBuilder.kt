package com.situ.aichat.prompt.diary

import com.situ.aichat.util.DateFormatters
import java.time.ZoneId

/** 三问引导答案（U2①·撰写页·答案注入生成）：事 / 感觉 / 未说出口。全空 = 不注入引导段。 */
data class DiaryGuideAnswers(val event: String, val feeling: String, val unsaid: String)

/**
 * 日记生成 system prompt 的纯函数装配器（M07 7.1.2）。原为 1:1 iOS，2026-07-13「作为我写日记」优化后
 * 与 iOS 结构分叉：**身份入戏在顶、要求段移到底**（primacy 立人设 / recency 让写作指令最后落地·见提案讨论）。
 *
 * section 顺序：
 * 1. 你就是我（入戏开场·顶部）
 * 2. ## 关于我（bio 人设·可选）
 * 3. ## 我今天最想写的（三问引导·可选）
 * 4. ## 今日心情（手选·可选）
 * 5. ## 今日聊天记录摘要（按角色分组·可选）
 * 6. ## 今日日程安排（可选）
 * 7. ## 今天的见面（可选）
 * 8. ## Pet Status（可选·宠物 P8）
 * 9. 礼物灵感段（可选·无 `##` 标题）
 * 10. 当前时间（含本地化周几 → LLM 自行判断时段/季节）
 * 11. ## 要求（**底部**：人称 / 风格 / 字数 / emoji / 瞬间 / 细节 / 内心 / 不暴露AI / 信息少则简短 + 额外规则）
 * 12. 只输出正文 + MOOD 尾行
 *
 * 纯函数：所有本地化串由 [strings] 传入，时间由 [nowMillis]+[zone] 决定 → 可不依赖设备/资源单测。
 */
object DiaryPromptBuilder {

    /** 宠物状态段标题。iOS `## Pet Status` 无 zh 翻译 → 双语都用英文（宠物 P8 才注入，现恒不出现）。 */
    private const val PET_STATUS_HEADER = "## Pet Status"

    /** 见面素材段标题/引导（涟漪②·§3.9·内容恒中文·硬编码同 PET_STATUS_HEADER 范式·i18n 抽取待独占窗口）。 */
    private const val MEETING_HEADER = "## 今天的见面"
    private const val MEETING_LEAD = "（这次见面对你今天的心情很重要，日记里自然地写到它。）"

    /** 字数范围默认值（2026-07-13：250-450 → 1000，用户拍板写长·配合每角色 150 条素材放宽）。 */
    const val DEFAULT_WORD_COUNT_RANGE = "1000"

    fun buildSystemPrompt(
        strings: DiaryPromptStrings,
        userName: String,
        nowMillis: Long,
        zone: ZoneId,
        chatSummary: String,
        calendarSummary: String,
        // 「关于我」段正文（bio + 城市行·调用方预拼；空 = 不注入人设段）。
        persona: String = "",
        meetingSummary: String = "",
        petSummary: String = "",
        giftInspiration: String? = null,
        moodHint: String = "",
        guide: DiaryGuideAnswers? = null,
        /**
         * 撰写页里用户**已经贴好**的照片张数（契约 §B8）。>0 时给一句「有 N 张照片但你看不到」——
         * 否则用户先贴 9 张海边照再点「AI 帮我写」，生成的正文对照片完全无感知，只会复述当天聊天记录。
         * 本期只补「知道有图」，真看图属日记二期。
         */
        photoCount: Int = 0,
        overrides: Map<String, String> = emptyMap(),
    ): String {
        val wordCountRange = resolveOverride(overrides, DiaryPromptField.WORD_COUNT_RANGE, DEFAULT_WORD_COUNT_RANGE)
        val narrativePerson = resolveOverride(overrides, DiaryPromptField.NARRATIVE_PERSON, "")
        val styleHint = resolveOverride(overrides, DiaryPromptField.STYLE_HINT, "")
        val extraRules = resolveOverride(overrides, DiaryPromptField.EXTRA_RULES, "")

        val parts = mutableListOf<String>()

        // 1) 你就是我（入戏开场·顶部立人设·primacy）。
        parts.add(strings.intro.format(userName))
        parts.add("")

        // 2) ## 关于我（bio 人设·让 AI 以「我」的性格与口吻写·空则不注入）。
        if (persona.isNotEmpty()) {
            parts.add(strings.personaHeader)
            parts.add(persona)
            parts.add("")
        }

        // 3) ## 我今天最想写的（三问引导·最贴用户本意·全空则不注入·add-only 不碰 MOOD 尾行/既有格式 §5）。
        val guideBody = guide?.let { formatGuideBody(it, strings.guideEvent, strings.guideFeeling, strings.guideUnsaid) }.orEmpty()
        if (guideBody.isNotEmpty()) {
            parts.add(strings.guideHeader)
            parts.add(strings.guideLead)
            parts.add(guideBody)
            parts.add("")
        }

        // 4) ## 今日心情（用户手选·撰写页「AI 帮我写」注入 emoji+文案，让基调贴合）。
        if (moodHint.isNotEmpty()) {
            parts.add(strings.moodHeader)
            parts.add(moodHint)
            parts.add("")
        }

        // 4.5) 已贴照片提示（§B8·盲图口径与朋友圈 photosBlind、日记评论同形）。
        if (photoCount > 0) {
            parts.add(strings.photosBlind.format(photoCount))
            parts.add("")
        }

        // 5) ## 今日聊天记录摘要（按角色分组·调用方已拼好）。
        if (chatSummary.isNotEmpty()) {
            parts.add(strings.chatSummaryHeader)
            parts.add(chatSummary)
            parts.add("")
        }
        // 6) ## 今日日程安排。
        if (calendarSummary.isNotEmpty()) {
            parts.add(strings.scheduleHeader)
            parts.add(calendarSummary)
            parts.add("")
        }
        // 7) ## 今天的见面（涟漪②·§3.9）。add-only·不碰 MOOD 尾行/既有格式（§5）。
        if (meetingSummary.isNotEmpty()) {
            parts.add(MEETING_HEADER)
            parts.add(meetingSummary)
            parts.add(MEETING_LEAD)
            parts.add("")
        }
        // 8) ## Pet Status（宠物 P8）。
        if (petSummary.isNotEmpty()) {
            parts.add(PET_STATUS_HEADER)
            parts.add(petSummary)
            parts.add("")
        }
        // 9) 礼物灵感段：hint 自身已是「完整语境+写作指令」，不加外层 `##` 标题。
        if (!giftInspiration.isNullOrEmpty()) {
            parts.add(giftInspiration)
            parts.add("")
        }

        // 10) 当前时间（含本地化周几 → LLM 自行判断时段/季节，不硬编码词、不编造）。
        val dateStr = DateFormatters.yearMonthDayHourMinuteWithWeekday(nowMillis, zone)
        parts.add(strings.currentTime.format(dateStr))
        parts.add("")

        // 11) ## 要求（**移到底部**·recency：写作指令在模型最清醒时落地）。
        parts.add(strings.requirementsHeader)
        parts.add(if (narrativePerson.isNotEmpty()) "- $narrativePerson" else strings.firstPerson)
        parts.add(if (styleHint.isNotEmpty()) "- $styleHint" else strings.styleDefault)
        parts.add(strings.wordCount.format(wordCountRange))
        parts.add(strings.emoji)
        parts.add(strings.events)
        parts.add(strings.chatMention)
        parts.add(strings.innerVoice)
        parts.add(strings.noAi)
        parts.add(strings.shortOk)
        // 追加用户补充规则（每行一条，自动加 - 前缀）。
        for (line in extraRules.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) parts.add("- $trimmed")
        }
        parts.add("")

        // 12) 只输出正文 + MOOD 尾行（outputOnly 的显式唯一例外·格式与 DiaryMoodLineParser 强耦合 §5）。
        parts.add(strings.outputOnly)
        parts.add(strings.moodOutputRule)

        return parts.joinToString("\n")
    }

    private fun resolveOverride(overrides: Map<String, String>, field: DiaryPromptField, fallback: String): String =
        overrides[field.raw]?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    /**
     * 三问引导 → 引导段正文（U2①·纯函数 T1）：非空答案按对应「标签：内容」模板逐行；全空/全空白 → ""
     * （调用方据此决定是否注入整段·空答案绝不产出空标签行）。
     */
    internal fun formatGuideBody(
        guide: DiaryGuideAnswers,
        eventFmt: String,
        feelingFmt: String,
        unsaidFmt: String,
    ): String = buildList {
        guide.event.trim().takeIf { it.isNotEmpty() }?.let { add(eventFmt.format(it)) }
        guide.feeling.trim().takeIf { it.isNotEmpty() }?.let { add(feelingFmt.format(it)) }
        guide.unsaid.trim().takeIf { it.isNotEmpty() }?.let { add(unsaidFmt.format(it)) }
    }.joinToString("\n")
}
