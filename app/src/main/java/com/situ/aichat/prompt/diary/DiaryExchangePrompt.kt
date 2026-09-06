package com.situ.aichat.prompt.diary

import com.situ.aichat.R
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.util.DateFormatters
import java.time.ZoneId

/**
 * 交换日记提示词模板（R4·契约 §2 F1）。双语走资源 + [PromptStrings]；与用户日记提示词可共用的段
 * （要求头、不暴露 AI、当前时间、只输出正文、**MOOD 尾行**）直接复用既有 `diary_prompt_*` 资源——
 * MOOD 尾行与 [DiaryMoodLineParser] 的强耦合因此天然同步（CLAUDE.md §5）。
 */
data class DiaryExchangePromptStrings(
    val intro: String,
    val setup: String,
    val task: String,
    val reqHeader: String,
    val reqSelf: String,
    val reqStyle: String,
    val reqWords: String,
    // R6-2 质感深化：具体瞬间 + 「日记非社交动态」两要求行（字数之后、不偷看之前）。
    val reqMoment: String,
    val reqNotSocial: String,
    val reqNoPeek: String,
    val reqNoAi: String,
    val moodHeader: String,
    val chatHeader: String,
    val scheduleHeader: String,
    // 角色日记丰富化（2026-07-13·以角色为中心）：③人设框定 + D用户bio段头 + B关系(段头/阶段/里程碑) + A记忆段头。
    val personaFrame: String,
    val aboutUserHeader: String,
    val relationshipHeader: String,
    val phaseLine: String,
    val phaseNames: String,
    val milestoneLine: String,
    val memoryHeader: String,
    val currentTime: String,
    val outputOnly: String,
    val moodOutputRule: String,
    val userMessage: String,
    val userFallback: String,
) {
    companion object {
        fun from(strings: PromptStrings): DiaryExchangePromptStrings = DiaryExchangePromptStrings(
            intro = strings.s(R.string.diary_comment_intro),          // 复用「你是「%1$s」，性格：%2$s」
            setup = strings.s(R.string.diary_comment_setup),          // 复用「你的角色设定：%1$s」
            task = strings.s(R.string.diary_exchange_task),
            reqHeader = strings.s(R.string.diary_prompt_requirements_header),
            reqSelf = strings.s(R.string.diary_exchange_req_self),
            reqStyle = strings.s(R.string.diary_exchange_req_style),
            reqWords = strings.s(R.string.diary_exchange_req_words),
            reqMoment = strings.s(R.string.diary_exchange_req_moment),
            reqNotSocial = strings.s(R.string.diary_exchange_req_not_social),
            reqNoPeek = strings.s(R.string.diary_exchange_req_no_peek),
            reqNoAi = strings.s(R.string.diary_prompt_no_ai),
            moodHeader = strings.s(R.string.diary_exchange_mood_header),
            chatHeader = strings.s(R.string.diary_exchange_chat_header),
            scheduleHeader = strings.s(R.string.diary_exchange_schedule_header),
            personaFrame = strings.s(R.string.diary_exchange_persona_frame),
            aboutUserHeader = strings.s(R.string.diary_exchange_about_user),
            relationshipHeader = strings.s(R.string.diary_exchange_relationship_header),
            phaseLine = strings.s(R.string.diary_exchange_phase_line),
            phaseNames = strings.s(R.string.diary_exchange_phase_names),
            milestoneLine = strings.s(R.string.diary_exchange_milestone_line),
            memoryHeader = strings.s(R.string.diary_exchange_memory_header),
            currentTime = strings.s(R.string.diary_prompt_current_time),
            outputOnly = strings.s(R.string.diary_prompt_output_only),
            moodOutputRule = strings.s(R.string.diary_prompt_mood_output),
            userMessage = strings.s(R.string.diary_exchange_user_message),
            userFallback = strings.s(R.string.diary_user_fallback),
        )
    }
}

/**
 * 角色日记丰富化的注入素材（2026-07-13·以角色为中心·各空则整段省略）：③人设框定 + D用户bio +
 * B关系(阶段+里程碑·预拼) + A记忆(memorySummary) + C约定/惦记(渲染器产出·自带段标题) + E意图块(卷四·渲染器产出两行)。
 */
data class DiaryExchangeEnrichment(
    val personaFrame: String = "",
    val aboutUser: String = "",
    val relationship: String = "",
    val memory: String = "",
    val promiseBlock: String = "",
    val loopBlock: String = "",
    /** 卷四 §4.5 ④：`IntentExitRenderer.diaryBlock` 产出（心里挂着的事 + 日记可以写得坦白些）；空 ⇒ 不插。 */
    val intentBlock: String = "",
)

/**
 * 交换日记 system prompt 装配（纯函数·T1 哨兵测试）。section 顺序（2026-07-13：**要求段移到底部**·
 * recency 让写作指令最后落地；丰富化上下文在任务之后、今日素材之前）：
 * 身份、[角色卡]、设定、[人设框定] → 任务 → [## 关于TA] → [## 你和TA的关系] → [## 你还记得的] → [约定块] → [惦记块] →
 * [此刻心情] → [今日聊天摘要] → [你今天的日程] → 当前时间(含周几) →
 * ## 要求（自称、风格[对{用户名}的在意]、字数、具体瞬间、非社交动态、不偷看对方日记、自己的口吻）→
 * 只输出正文 + MOOD 尾行。可选段空则整段省略。
 */
object DiaryExchangePromptBuilder {

    /**
     * 交换日记字数默认值（与 [DiaryPromptBuilder.DEFAULT_WORD_COUNT_RANGE] 对称）。改动前 `reqWords`
     * 资源里写死「约 1000 字」，2026-09-05 起改带 `%1$s` 占位由本常量/用户设置填。
     */
    const val DEFAULT_EXCHANGE_WORD_COUNT = "1000"

    fun build(
        strings: DiaryExchangePromptStrings,
        characterName: String,
        personality: String,
        systemPrompt: String,
        userName: String,
        nowMillis: Long,
        zone: ZoneId,
        moodLine: String,
        chatSummary: String,
        scheduleSummary: String,
        enrichment: DiaryExchangeEnrichment = DiaryExchangeEnrichment(),
        /** 角色卡块（[DiaryCharacterCardBlock] 预渲染·空 = 与本参数引入前逐字节相同）。 */
        characterCard: String = "",
        /**
         * 写作规则覆盖（[DiaryRuleOverrides.toOverrides] 产出·键 = [DiaryPromptField.raw]）。
         * 只作用于要求段的**人称行 / 文风行 / 字数行 + 段末追加行**；其余要求行、只输出正文、
         * MOOD 尾行**绝不**进入可覆盖面（图纸 §9 机制锁 / REDLINES §1）。空 map = 全默认。
         */
        overrides: Map<String, String> = emptyMap(),
    ): String {
        val parts = mutableListOf<String>()
        parts.add(strings.intro.format(characterName, personality))
        // 角色卡（2026-09-05）：性别/年龄/星座/职业/外貌/背景/说话风格/口头禅/兴趣/住哪——过去只有聊天和日程
        // 看得到，写日记时角色是盲的、只好自编设定。示例对话有意不进（那是聊天格式样本·会带成短句聊天腔）。
        if (characterCard.isNotEmpty()) parts.add(characterCard)
        if (systemPrompt.isNotEmpty()) parts.add(strings.setup.format(systemPrompt))
        // ③ 人设框定（紧跟身份/设定·保留声音但别套聊天短句/表情）。
        if (enrichment.personaFrame.isNotEmpty()) parts.add(enrichment.personaFrame)
        parts.add("")
        parts.add(strings.task.format(userName))
        parts.add("")
        // 以角色为中心的上下文（任务之后、今日素材之前）：D关于TA → B关系 → A记忆 → C约定 → C惦记。
        if (enrichment.aboutUser.isNotEmpty()) {
            parts.add(strings.aboutUserHeader.format(userName))
            parts.add(enrichment.aboutUser)
            parts.add("")
        }
        if (enrichment.relationship.isNotEmpty()) {
            parts.add(strings.relationshipHeader.format(userName))
            parts.add(enrichment.relationship)
            parts.add("")
        }
        if (enrichment.memory.isNotEmpty()) {
            parts.add(strings.memoryHeader)
            parts.add(enrichment.memory)
            parts.add("")
        }
        // 约定 / 惦记块：渲染器已自带段标题（【我们的约定】等），直接注入。
        if (enrichment.promiseBlock.isNotEmpty()) {
            parts.add(enrichment.promiseBlock)
            parts.add("")
        }
        if (enrichment.loopBlock.isNotEmpty()) {
            parts.add(enrichment.loopBlock)
            parts.add("")
        }
        // 卷四 §4.5 ④：意图块在惦记块之后、此刻心情之前；空则整段省略。
        if (enrichment.intentBlock.isNotEmpty()) {
            parts.add(enrichment.intentBlock)
            parts.add("")
        }
        if (moodLine.isNotEmpty()) {
            parts.add(strings.moodHeader)
            parts.add(moodLine)
            parts.add("")
        }
        if (chatSummary.isNotEmpty()) {
            parts.add(strings.chatHeader)
            parts.add(chatSummary)
            parts.add("")
        }
        if (scheduleSummary.isNotEmpty()) {
            parts.add(strings.scheduleHeader)
            parts.add(scheduleSummary)
            parts.add("")
        }
        // E：当前时间含本地化周几（与用户日记对齐·LLM 自判时段/季节）。
        parts.add(strings.currentTime.format(DateFormatters.yearMonthDayHourMinuteWithWeekday(nowMillis, zone)))
        parts.add("")
        // ## 要求（**底部**·recency）。reqStyle 现填「对{用户名}的在意」（%1$s ← userName）。
        parts.add(strings.reqHeader)
        parts.add(overrides[DiaryPromptField.NARRATIVE_PERSON.raw]?.let { "- $it" } ?: strings.reqSelf)
        parts.add(overrides[DiaryPromptField.STYLE_HINT.raw]?.let { "- $it" } ?: strings.reqStyle.format(userName))
        parts.add(strings.reqWords.format(overrides[DiaryPromptField.WORD_COUNT_RANGE.raw] ?: DEFAULT_EXCHANGE_WORD_COUNT))
        parts.add(strings.reqMoment)
        parts.add(strings.reqNotSocial)
        parts.add(strings.reqNoPeek)
        parts.add(strings.reqNoAi)
        // 追加用户补充规则（每行一条，自动加 - 前缀·空行跳过）。
        for (line in (overrides[DiaryPromptField.EXTRA_RULES.raw] ?: "").split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) parts.add("- $trimmed")
        }
        parts.add("")
        parts.add(strings.outputOnly)
        parts.add(strings.moodOutputRule)
        return parts.joinToString("\n")
    }
}
