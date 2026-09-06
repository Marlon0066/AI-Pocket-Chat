package com.situ.aichat.prompt.diary

import com.situ.aichat.R
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.util.DateFormatters
import java.time.ZoneId

/** 预览行的三种成色：普通提示词行 / 用户自定义的那几行 / 生成时才填的素材占位。 */
enum class PreviewLineKind { PLAIN, CUSTOM, SLOT }

data class PreviewLine(val text: String, val kind: PreviewLineKind)

/** 骨架式预览的占位素材（图纸 §3.6 第二张表·随资源本地化）。 */
data class DiaryPreviewSlots(
    val persona: String,
    val chat: String,
    val schedule: String,
    val time: String,
    val card: String,
    val personality: String,
    val setup: String,
    val aboutUser: String,
    val relationship: String,
    val memory: String,
    val mood: String,
    val exchangeChat: String,
    val exchangeSchedule: String,
    val characterFallback: String,
) {
    companion object {
        fun from(strings: PromptStrings): DiaryPreviewSlots = DiaryPreviewSlots(
            persona = strings.s(R.string.diary_preview_slot_persona),
            chat = strings.s(R.string.diary_preview_slot_chat),
            schedule = strings.s(R.string.diary_preview_slot_schedule),
            time = strings.s(R.string.diary_preview_slot_time),
            card = strings.s(R.string.diary_preview_slot_card),
            personality = strings.s(R.string.diary_preview_slot_personality),
            setup = strings.s(R.string.diary_preview_slot_setup),
            aboutUser = strings.s(R.string.diary_preview_slot_about_user),
            relationship = strings.s(R.string.diary_preview_slot_relationship),
            memory = strings.s(R.string.diary_preview_slot_memory),
            mood = strings.s(R.string.diary_preview_slot_mood),
            exchangeChat = strings.s(R.string.diary_preview_slot_exchange_chat),
            exchangeSchedule = strings.s(R.string.diary_preview_slot_exchange_schedule),
            characterFallback = strings.s(R.string.diary_rules_preview_character_fallback),
        )
    }
}

/**
 * 骨架式提示词预览（2026-09-05·图纸 §3.7·纯函数 T1·P-5 拍板）：**走真装配函数**，当天素材用尖括号占位，
 * 不查库、不联网 —— 预览与真生成共用同一段装配代码，永不漂移。
 *
 * 分类（锁定）：① 行首是 `〈` 或 `<` → [PreviewLineKind.SLOT]；② 行文本命中「本次覆盖值渲染出的那几行」
 * → [PreviewLineKind.CUSTOM]（**字数行只有用户改过时才算**）；③ 其余 → [PreviewLineKind.PLAIN]。
 */
object DiaryPromptPreview {

    /** 预览用的固定时刻（渲染后整体替换为时间占位行，故取值本身不影响输出）。 */
    private const val PREVIEW_NOW = 1_700_000_000_000L

    fun buildMine(
        strings: DiaryPromptStrings,
        slots: DiaryPreviewSlots,
        userName: String,
        values: DiaryRuleValues,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PreviewLine> {
        val overrides = DiaryRuleOverrides.toOverrides(values, userName, characterName = null)
        val text = DiaryPromptBuilder.buildSystemPrompt(
            strings = strings,
            userName = userName,
            nowMillis = PREVIEW_NOW,
            zone = zone,
            chatSummary = slots.chat,
            calendarSummary = slots.schedule,
            persona = slots.persona,
            meetingSummary = "",
            petSummary = "",
            giftInspiration = null,
            moodHint = "",
            guide = null,
            photoCount = 0,
            overrides = overrides,
        )
        val custom = customLines(
            overrides = overrides,
            wordCountLine = strings.wordCount.format(values.wordCount.toString()),
            wordCountChanged = values.wordCount != DEFAULT_WORD_COUNT,
        )
        return classify(text, slots.time, zone, custom)
    }

    fun buildExchange(
        strings: DiaryExchangePromptStrings,
        slots: DiaryPreviewSlots,
        userName: String,
        characterName: String,
        values: DiaryRuleValues,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PreviewLine> {
        val overrides = DiaryRuleOverrides.toOverrides(values, userName, characterName)
        val text = DiaryExchangePromptBuilder.build(
            strings = strings,
            characterName = characterName,
            // R1 复核修（🟡-1）：这两处原先传空串 ⇒ 身份行渲染成「你是「X」，性格：」尾巴空着、
            // 「你的角色设定：」整段被 isNotEmpty 判空省掉 —— 预览的用途正是「看清自己写的设定落在哪」，
            // 缺这两样等于把最该看的东西藏了。改传占位串，与其它素材段同口径。
            personality = slots.personality,
            systemPrompt = slots.setup,
            userName = userName,
            nowMillis = PREVIEW_NOW,
            zone = zone,
            moodLine = slots.mood,
            chatSummary = slots.exchangeChat,
            scheduleSummary = slots.exchangeSchedule,
            enrichment = DiaryExchangeEnrichment(
                personaFrame = strings.personaFrame,
                aboutUser = slots.aboutUser,
                relationship = slots.relationship,
                memory = slots.memory,
            ),
            characterCard = slots.card,
            overrides = overrides,
        )
        val custom = customLines(
            overrides = overrides,
            wordCountLine = strings.reqWords.format(values.wordCount.toString()),
            wordCountChanged = values.wordCount != DEFAULT_WORD_COUNT,
        )
        return classify(text, slots.time, zone, custom)
    }

    /** 由本次覆盖值渲染出的那几行（人称 / 文风 / 字数 / 每条补充规则）。 */
    private fun customLines(
        overrides: Map<String, String>,
        wordCountLine: String,
        wordCountChanged: Boolean,
    ): Set<String> = buildSet {
        overrides[DiaryPromptField.NARRATIVE_PERSON.raw]?.let { add("- $it") }
        overrides[DiaryPromptField.STYLE_HINT.raw]?.let { add("- $it") }
        if (wordCountChanged) add(wordCountLine)
        for (line in (overrides[DiaryPromptField.EXTRA_RULES.raw] ?: "").split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) add("- $trimmed")
        }
    }

    private fun classify(text: String, timeSlot: String, zone: ZoneId, custom: Set<String>): List<PreviewLine> {
        // 当前时间行：真日期串换成「〈生成时的时间〉」占位（图纸 §3.7 锁定）。
        val dateStr = DateFormatters.yearMonthDayHourMinuteWithWeekday(PREVIEW_NOW, zone)
        return text.replace(dateStr, timeSlot).split("\n").map { line ->
            val kind = when {
                line.startsWith("〈") || line.startsWith("<") -> PreviewLineKind.SLOT
                line in custom -> PreviewLineKind.CUSTOM
                else -> PreviewLineKind.PLAIN
            }
            PreviewLine(line, kind)
        }
    }

    /** 两套共用的字数默认值（= `AppSettings.DEFAULT_DIARY_WORD_COUNT`，此处不引数据层保持纯函数）。 */
    private const val DEFAULT_WORD_COUNT = 1000
}
