package com.situ.aichat.prompt.diary

import com.situ.aichat.data.model.AppSettings

/**
 * 一套日记写作规则的四项取值（图纸 §3.2）。三个文本项**空串 = 用默认文案**（两态·沿用
 * `normalizeCustomPrompt` 的「等于默认即存空」）；[wordCount] 是数值（1000 = 与历史行为逐字节相同·J-5）。
 */
data class DiaryRuleValues(
    val wordCount: Int,
    val narrativePerson: String,
    val styleHint: String,
    val extraRules: String,
)

/**
 * 写作规则 → 提示词覆盖 map 的**取值单源**（2026-09-05·图纸 §3.2·纯函数 T1）。
 * 「我的日记」与「TA 的信」各一套值，经此产出以 [DiaryPromptField.raw] 为键的 map，
 * 分别喂 [DiaryPromptBuilder.buildSystemPrompt] 与 [DiaryExchangePromptBuilder.build]。
 *
 * **锁定**：自定义文本一律走**字面 `replace`**、绝不 `String.format` / `MessageFormat`——用户文本里一个
 * 裸 `%` 就会让 format 抛 `UnknownFormatConversionException`，直接炸掉日记生成（图纸 J-4 / E10）。
 */
object DiaryRuleOverrides {

    /** 「我的日记」的四项取值 → 覆盖 map（`{角色名}` 无对应角色 ⇒ 保持字面量）。 */
    fun forUserDiary(settings: AppSettings, userName: String): Map<String, String> = toOverrides(
        DiaryRuleValues(
            wordCount = settings.diaryWordCount,
            narrativePerson = settings.diaryNarrativePerson,
            styleHint = settings.diaryStyleHint,
            extraRules = settings.diaryExtraRules,
        ),
        userName = userName,
        characterName = null,
    )

    /** 「TA 的信」的四项取值 → 覆盖 map（`{用户名}`/`{角色名}` 都替换）。 */
    fun forExchange(settings: AppSettings, userName: String, characterName: String): Map<String, String> = toOverrides(
        DiaryRuleValues(
            wordCount = settings.diaryExchangeWordCount,
            narrativePerson = settings.diaryExchangeNarrativePerson,
            styleHint = settings.diaryExchangeStyleHint,
            extraRules = settings.diaryExchangeExtraRules,
        ),
        userName = userName,
        characterName = characterName,
    )

    /**
     * 占位替换（锁定·纯字面 replace）。[characterName] 为 null（= 我的日记侧）时 `{角色名}` 保持字面量。
     */
    internal fun applyRulePlaceholders(text: String, userName: String, characterName: String?): String {
        var out = text.replace("{用户名}", userName)
        if (characterName != null) out = out.replace("{角色名}", characterName)
        return out
    }

    /**
     * 四项取值 → 覆盖 map。字数**恒传**（消费端按各自默认常量填模板）；三个文本项为空/纯空白时
     * **不放进 map**，消费端据此回落默认文案。
     */
    fun toOverrides(
        values: DiaryRuleValues,
        userName: String,
        characterName: String?,
    ): Map<String, String> = buildMap {
        put(DiaryPromptField.WORD_COUNT_RANGE.raw, values.wordCount.toString())
        putCustom(DiaryPromptField.NARRATIVE_PERSON, values.narrativePerson, userName, characterName)
        putCustom(DiaryPromptField.STYLE_HINT, values.styleHint, userName, characterName)
        putCustom(DiaryPromptField.EXTRA_RULES, values.extraRules, userName, characterName)
    }

    private fun MutableMap<String, String>.putCustom(
        field: DiaryPromptField,
        raw: String,
        userName: String,
        characterName: String?,
    ) {
        if (raw.isBlank()) return
        put(field.raw, applyRulePlaceholders(raw, userName, characterName))
    }
}
