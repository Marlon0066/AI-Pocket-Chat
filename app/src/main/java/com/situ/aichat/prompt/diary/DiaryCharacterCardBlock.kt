package com.situ.aichat.prompt.diary

import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.currentAge
import com.situ.aichat.prompt.PromptBuilder
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.util.ZodiacCalculator
import java.time.Instant
import java.time.ZoneId

/**
 * 角色卡十行的本地化模板（复用聊天侧 `pb_ident_*` 资源 · 图纸 §3.1）。城市行是日记独有的新资源。
 * 与 [DiaryExchangePromptStrings] 同范式：存原始模板串，由 [DiaryCharacterCardBlock] `format` 填值，
 * 从而保持装配纯函数、可脱资源单测。
 */
data class DiaryCharacterCardStrings(
    val gender: String,
    val age: String,
    val zodiac: String,
    val occupation: String,
    val appearance: String,
    val backstory: String,
    val speaking: String,
    val catchphrases: String,
    val interests: String,
    val city: String,
) {
    companion object {
        fun from(strings: PromptStrings): DiaryCharacterCardStrings = DiaryCharacterCardStrings(
            gender = strings.s(R.string.pb_ident_gender),
            age = strings.s(R.string.pb_ident_age),
            zodiac = strings.s(R.string.pb_ident_zodiac),
            occupation = strings.s(R.string.pb_ident_occupation),
            appearance = strings.s(R.string.pb_ident_appearance),
            backstory = strings.s(R.string.pb_ident_backstory),
            speaking = strings.s(R.string.pb_ident_speaking),
            catchphrases = strings.s(R.string.pb_ident_catchphrases),
            interests = strings.s(R.string.pb_ident_interests),
            city = strings.s(R.string.diary_exchange_city),
        )
    }
}

/**
 * 交换日记的角色卡块（2026-09-05·图纸 §3.1·纯函数 T1）：性别 / 年龄 / 星座 / 职业 / 外貌 / 背景 /
 * 说话风格 / 口头禅 / 兴趣 / 住哪——顺序照抄聊天侧 `buildCharacterIdentityContent`（去掉名字、性格、
 * 示例对话、角色设定：前两样已在 intro 行、后两样另有段落/有意不进）。
 *
 * 规矩（锁定）：① 每项**先过 `{{char}}`/`{{user}}` 宏替换、后判空**——替换后 trim 仍为空的整行省略；
 * ② 全空 → `""`，调用方据此整段省略（绝不产出空行）；③ 无段标题——它是身份段的延续，紧贴 intro 之下。
 */
object DiaryCharacterCardBlock {

    /**
     * @param cityName 当天日程行上的城市（角色加入世界后这里已是世界城名·图纸 J-2）；空 → 回落
     *   [CharacterEntity.cityName]；两者皆空 → 省略住址行。
     */
    fun build(
        character: CharacterEntity,
        cityName: String?,
        userName: String,
        now: Instant,
        zone: ZoneId,
        strings: DiaryCharacterCardStrings,
    ): String {
        val macros = mapOf("{{char}}" to character.name, "{{user}}" to userName)
        fun resolved(raw: String?): String? =
            PromptBuilder.applyPromptMacros(raw.orEmpty(), macros).trim().takeIf { it.isNotEmpty() }

        val lines = mutableListOf<String>()
        resolved(character.gender)?.let { lines.add(strings.gender.format(it)) }
        character.currentAge(now)?.let { age -> if (age > 0) lines.add(strings.age.format(age)) }
        character.birthday?.let { bday ->
            ZodiacCalculator.zodiacSign(bday, zone).takeIf { it.isNotEmpty() }
                ?.let { lines.add(strings.zodiac.format(it)) }
        }
        resolved(character.occupation)?.let { lines.add(strings.occupation.format(it)) }
        resolved(character.appearanceDescription)?.let { lines.add(strings.appearance.format(it)) }
        resolved(character.backstory)?.let { lines.add(strings.backstory.format(it)) }
        resolved(character.speakingStyle)?.let { lines.add(strings.speaking.format(it)) }
        resolved(character.catchphrases)?.let { lines.add(strings.catchphrases.format(it)) }
        resolved(character.initialInterests)?.let { lines.add(strings.interests.format(it)) }
        (resolved(cityName) ?: resolved(character.cityName))?.let { lines.add(strings.city.format(it)) }
        return lines.joinToString("\n")
    }
}
