package com.situ.aichat.prompt

import com.situ.aichat.data.model.PersonaGains
import com.situ.aichat.data.model.PersonaVocab

/**
 * 「她吃哪套」敏感点行（活人感内核修缮卷 §3.7 · 用户 2026-09-02 拍板 ④）：把增益里最突出的 2–3 项写成**一行**接在
 * Growth 段性格块末尾（`PromptBuilderGrowth.buildPersonalityDescription` 的 `extraLine`），让聊天模型直接知道她在意什么。
 *
 * 取材：`sensitive` = 专属项里「很敏感」的标签 + 27 项里「很敏感」的中文标签（[PersonaVocab.gainLabel]·含 ` · ` 原样），取前 3；
 * `numb` = 27 项里「不吃这套」的标签，取前 2。两者皆空 ⇒ `""`（`personaGains` 全默认 ⇒ 输出逐字节同前·回归钉）。
 *
 * 文本（§9 锁定）：`你特别在意A、B` / `对C、D不太吃这套`；两者都有 ⇒ `- 你特别在意…，对…不太吃这套。`；只 s ⇒ `- 你特别在意…。`；
 * 只 n ⇒ `- 你对…不太吃这套。`。长度守卫：整行 > [MAX_CHARS] 字则依次去掉 numb 末项、再 sensitive 末项（各至少留 1，仍超则保留）。
 * 渲染层零写零数值再判定（§2.3）。
 */
internal fun buildPersonaGainsLine(gains: PersonaGains): String {
    val sensitive = (
        gains.custom.filter { it.level == PersonaVocab.LEVEL_SENSITIVE }.map { it.label } +
            PersonaVocab.GAIN_KEYS.filter { gains.system[it] == PersonaVocab.LEVEL_SENSITIVE }.mapNotNull(PersonaVocab::gainLabel)
        ).take(MAX_SENSITIVE).toMutableList()
    val numb = PersonaVocab.GAIN_KEYS.filter { gains.system[it] == PersonaVocab.LEVEL_NUMB }
        .mapNotNull(PersonaVocab::gainLabel).take(MAX_NUMB).toMutableList()
    if (sensitive.isEmpty() && numb.isEmpty()) return ""

    fun render(): String {
        val sPart = "你特别在意${sensitive.joinToString("、")}"
        val nPart = "对${numb.joinToString("、")}不太吃这套"
        return when {
            sensitive.isNotEmpty() && numb.isNotEmpty() -> "- $sPart，$nPart。"
            sensitive.isNotEmpty() -> "- $sPart。"
            else -> "- 你$nPart。"
        }
    }
    var line = render()
    while (line.length > MAX_CHARS) {
        when {
            numb.size > 1 -> numb.removeAt(numb.lastIndex)
            sensitive.size > 1 -> sensitive.removeAt(sensitive.lastIndex)
            else -> break
        }
        line = render()
    }
    return line
}

/** §3.11 锁定：整行 ≤ 60 字 / 敏感 ≤ 3 项 / 不吃这套 ≤ 2 项。 */
private const val MAX_CHARS = 60
private const val MAX_SENSITIVE = 3
private const val MAX_NUMB = 2
