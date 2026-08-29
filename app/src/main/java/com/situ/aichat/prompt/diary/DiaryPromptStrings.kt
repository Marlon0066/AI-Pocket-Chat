package com.situ.aichat.prompt.diary

import com.situ.aichat.R
import com.situ.aichat.prompt.PromptStrings

/**
 * 日记生成提示词所需的本地化模板串（M07 7.1.2）。iOS `DiaryPromptBuilder` 用 `String(localized:)`，
 * 是全项目唯一**双语**的生成提示词（日程/成长是硬编码中文）——故这里走字符串资源（values 中文 /
 * values-en 英文），由 [PromptStrings] 按当前 locale 解析，1:1 对齐 iOS 行为。
 *
 * 含格式参数的字段（[intro] / [wordCount] / [currentTime] / [chatLine] / [calendarLine]）存的是**原始模板**
 * （`%1$s` 占位），由 [DiaryPromptBuilder] / 收集逻辑用 `String.format` 填入——这样 builder 保持纯函数、可单测。
 *
 * 注意：[wordCount] 在 iOS 无 zh-Hans 翻译、回落英文基串 → 此处中文设备也是英文，忠实 iOS。
 */
data class DiaryPromptStrings(
    val intro: String,
    val requirementsHeader: String,
    val firstPerson: String,
    val styleDefault: String,
    val wordCount: String,
    val emoji: String,
    val events: String,
    val chatMention: String,
    // R6-2 质感深化：内心独白要求行（时间线/细节之后、不暴露AI之前）。
    val innerVoice: String,
    val noAi: String,
    val shortOk: String,
    // 「作为我」写日记：人设段标题 + 城市行模板（撰写页/自动生成注入 bio·让 AI 以「我」的人设写）。
    val personaHeader: String,
    val personaCity: String,
    val chatSummaryHeader: String,
    // 多角色聊天摘要按角色分组的小标题（`### 今天和 %1$s 聊了`）。
    val chatGroupHeader: String,
    val scheduleHeader: String,
    val currentTime: String,
    val outputOnly: String,
    // R2 心情闭环：手选心情段标题 + MOOD 尾行输出指令（与 DiaryMoodLineParser 强耦合·见其 KDoc）。
    val moodHeader: String,
    /** 「这篇还附了 N 张照片，你看不到」——与日记评论、朋友圈盲图同一条资源（§B8）。 */
    val photosBlind: String,
    val moodOutputRule: String,
    val userMessage: String,
    // U2① 三问引导：段标题 + 前导 + 三条「标签：内容」模板（撰写页答案注入·add-only §5 安全）。
    val guideHeader: String,
    val guideLead: String,
    val guideEvent: String,
    val guideFeeling: String,
    val guideUnsaid: String,
    // 收集聊天/日历素材时用的格式与兜底
    val roleMe: String,
    val roleOther: String,
    val chatLine: String,
    val calendarLine: String,
    val eventUntitled: String,
    val userFallback: String,
) {
    companion object {
        fun from(strings: PromptStrings): DiaryPromptStrings = DiaryPromptStrings(
            intro = strings.s(R.string.diary_prompt_intro),
            requirementsHeader = strings.s(R.string.diary_prompt_requirements_header),
            firstPerson = strings.s(R.string.diary_prompt_first_person),
            styleDefault = strings.s(R.string.diary_prompt_style_default),
            wordCount = strings.s(R.string.diary_prompt_word_count),
            emoji = strings.s(R.string.diary_prompt_emoji),
            events = strings.s(R.string.diary_prompt_events),
            chatMention = strings.s(R.string.diary_prompt_chat_mention),
            innerVoice = strings.s(R.string.diary_prompt_inner_voice),
            noAi = strings.s(R.string.diary_prompt_no_ai),
            shortOk = strings.s(R.string.diary_prompt_short_ok),
            personaHeader = strings.s(R.string.diary_prompt_persona_header),
            personaCity = strings.s(R.string.diary_prompt_persona_city),
            chatSummaryHeader = strings.s(R.string.diary_prompt_chat_summary_header),
            chatGroupHeader = strings.s(R.string.diary_chat_group_header),
            scheduleHeader = strings.s(R.string.diary_prompt_schedule_header),
            currentTime = strings.s(R.string.diary_prompt_current_time),
            outputOnly = strings.s(R.string.diary_prompt_output_only),
            moodHeader = strings.s(R.string.diary_prompt_mood_header),
            photosBlind = strings.s(R.string.diary_comment_photos_blind),
            moodOutputRule = strings.s(R.string.diary_prompt_mood_output),
            userMessage = strings.s(R.string.diary_prompt_user_message),
            guideHeader = strings.s(R.string.diary_prompt_guide_header),
            guideLead = strings.s(R.string.diary_prompt_guide_lead),
            guideEvent = strings.s(R.string.diary_prompt_guide_event),
            guideFeeling = strings.s(R.string.diary_prompt_guide_feeling),
            guideUnsaid = strings.s(R.string.diary_prompt_guide_unsaid),
            roleMe = strings.s(R.string.diary_role_me),
            roleOther = strings.s(R.string.diary_role_other),
            chatLine = strings.s(R.string.diary_chat_line),
            calendarLine = strings.s(R.string.diary_calendar_line),
            eventUntitled = strings.s(R.string.diary_event_untitled),
            userFallback = strings.s(R.string.diary_user_fallback),
        )
    }
}

/**
 * 日记场景的可覆盖字段（对齐 iOS `DiaryPromptField`）。当前无设置 UI（→ P12），覆盖恒为空 = 默认行为。
 * [raw] 为稳定的 JSON 键名，与 iOS 一致，便于未来场景覆盖框架接入。
 */
enum class DiaryPromptField(val raw: String) {
    WORD_COUNT_RANGE("wordCountRange"),
    NARRATIVE_PERSON("narrativePerson"),
    STYLE_HINT("styleHint"),
    EXTRA_RULES("extraRules"),
}
