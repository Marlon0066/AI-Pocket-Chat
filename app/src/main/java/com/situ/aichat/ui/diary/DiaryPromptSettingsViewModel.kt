package com.situ.aichat.ui.diary

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.ui.settings.normalizeCustomPrompt
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 写作规则的两套：我的日记 / TA 的信（图纸 §4.2 两分区）。 */
enum class DiaryRuleSection { MINE, EXCHANGE }

/** 一个分区的四项当前值（文本项显示的是「已自定义的文本」或「默认文案原文」）。 */
data class DiaryRuleForm(
    val wordCount: Int = AppSettings.DEFAULT_DIARY_WORD_COUNT,
    val narrativePerson: String = "",
    val styleHint: String = "",
    val extraRules: String = "",
)

data class DiaryPromptSettingsState(
    val mine: DiaryRuleForm = DiaryRuleForm(),
    val exchange: DiaryRuleForm = DiaryRuleForm(),
)

/**
 * 日记写作规则设置 VM（2026-09-05·图纸 §3.5/§4.2）。
 *
 * **本地态快照播种**（图纸 J-7·同 [com.situ.aichat.ui.settings.MemoryPromptsSettingsViewModel]）：本屏是
 * 这 8 个字段的唯一编辑者，init 经 [SettingsRepository.getAppSettings] 一次性播种，之后每次变更写回本地态
 * + 落盘——用持续 collect DataStore 会在打字时被回灌覆盖。
 *
 * **滑块**（图纸 J-8）：拖动中只更本地态（[onWordCountDrag]），松手才落盘（[commitWordCount]）；
 * 手动输入（[setWordCount]）立即落盘，钳位在 Repository 侧（[SettingsRepository.DIARY_WORDS_MIN]/`MAX`）。
 */
@HiltViewModel
class DiaryPromptSettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    /** 默认文案原文（播种 + 「等于默认即存空」的比较基准）——与提示词同一份资源，永不漂移。 */
    val defaults: DiaryPromptSettingsState = PromptStrings(context).let { s ->
        DiaryPromptSettingsState(
            mine = DiaryRuleForm(
                narrativePerson = diaryRuleSeed(s.s(R.string.diary_prompt_first_person)),
                styleHint = diaryRuleSeed(s.s(R.string.diary_prompt_style_default)),
            ),
            exchange = DiaryRuleForm(
                narrativePerson = diaryRuleSeed(s.s(R.string.diary_exchange_req_self)),
                styleHint = diaryRuleSeed(s.s(R.string.diary_exchange_req_style)),
            ),
        )
    }

    private val _state = MutableStateFlow(defaults)
    val state: StateFlow<DiaryPromptSettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settingsRepo.getAppSettings()
            _state.value = DiaryPromptSettingsState(
                mine = DiaryRuleForm(
                    wordCount = s.diaryWordCount,
                    narrativePerson = s.diaryNarrativePerson.ifEmpty { defaults.mine.narrativePerson },
                    styleHint = s.diaryStyleHint.ifEmpty { defaults.mine.styleHint },
                    extraRules = s.diaryExtraRules,
                ),
                exchange = DiaryRuleForm(
                    wordCount = s.diaryExchangeWordCount,
                    narrativePerson = s.diaryExchangeNarrativePerson.ifEmpty { defaults.exchange.narrativePerson },
                    styleHint = s.diaryExchangeStyleHint.ifEmpty { defaults.exchange.styleHint },
                    extraRules = s.diaryExchangeExtraRules,
                ),
            )
        }
    }

    fun defaultsFor(section: DiaryRuleSection): DiaryRuleForm =
        if (section == DiaryRuleSection.MINE) defaults.mine else defaults.exchange

    fun formOf(section: DiaryRuleSection): DiaryRuleForm =
        if (section == DiaryRuleSection.MINE) _state.value.mine else _state.value.exchange

    /** 拖动中：只更本地态，不落盘（18 档逐档写 DataStore 是无谓 I/O）。 */
    fun onWordCountDrag(section: DiaryRuleSection, words: Int) = update(section) { it.copy(wordCount = words) }

    /** 松手：把当前本地值落盘。 */
    fun commitWordCount(section: DiaryRuleSection) = persistWordCount(section, formOf(section).wordCount)

    /** 手动输入（可超滑杆上限·钳位在 Repository）：本地态 + 立即落盘。 */
    fun setWordCount(section: DiaryRuleSection, words: Int) {
        val clamped = words.coerceIn(SettingsRepository.DIARY_WORDS_MIN, SettingsRepository.DIARY_WORDS_MAX)
        update(section) { it.copy(wordCount = clamped) }
        persistWordCount(section, clamped)
    }

    fun onNarrativePersonChange(section: DiaryRuleSection, text: String) {
        update(section) { it.copy(narrativePerson = text) }
        val stored = normalizeCustomPrompt(text, defaultsFor(section).narrativePerson)
        viewModelScope.launch {
            if (section == DiaryRuleSection.MINE) settingsRepo.setDiaryNarrativePerson(stored)
            else settingsRepo.setDiaryExchangeNarrativePerson(stored)
        }
    }

    fun onStyleHintChange(section: DiaryRuleSection, text: String) {
        update(section) { it.copy(styleHint = text) }
        val stored = normalizeCustomPrompt(text, defaultsFor(section).styleHint)
        viewModelScope.launch {
            if (section == DiaryRuleSection.MINE) settingsRepo.setDiaryStyleHint(stored)
            else settingsRepo.setDiaryExchangeStyleHint(stored)
        }
    }

    fun onExtraRulesChange(section: DiaryRuleSection, text: String) {
        update(section) { it.copy(extraRules = text) }
        viewModelScope.launch {
            if (section == DiaryRuleSection.MINE) settingsRepo.setDiaryExtraRules(text)
            else settingsRepo.setDiaryExchangeExtraRules(text)
        }
    }

    /** 一键把该分区**四项**一起还原：字数回 1000、三个文本框回显默认文案且落盘为 ""。 */
    fun resetSection(section: DiaryRuleSection) {
        val d = defaultsFor(section)
        update(section) { d }
        viewModelScope.launch {
            if (section == DiaryRuleSection.MINE) {
                settingsRepo.setDiaryWordCount(d.wordCount)
                settingsRepo.setDiaryNarrativePerson("")
                settingsRepo.setDiaryStyleHint("")
                settingsRepo.setDiaryExtraRules("")
            } else {
                settingsRepo.setDiaryExchangeWordCount(d.wordCount)
                settingsRepo.setDiaryExchangeNarrativePerson("")
                settingsRepo.setDiaryExchangeStyleHint("")
                settingsRepo.setDiaryExchangeExtraRules("")
            }
        }
    }

    private fun persistWordCount(section: DiaryRuleSection, words: Int) = viewModelScope.launch {
        if (section == DiaryRuleSection.MINE) settingsRepo.setDiaryWordCount(words)
        else settingsRepo.setDiaryExchangeWordCount(words)
    }

    private fun update(section: DiaryRuleSection, block: (DiaryRuleForm) -> DiaryRuleForm) {
        _state.value = if (section == DiaryRuleSection.MINE) {
            _state.value.copy(mine = block(_state.value.mine))
        } else {
            _state.value.copy(exchange = block(_state.value.exchange))
        }
    }
}

/**
 * 默认要求行 → 编辑框里显示的「规则原文」（图纸 §4.2 播种文案 · mockup 逐字为准）：
 * ① 剥掉提示词的列表前缀 `- `——那是提示词的**列表格式**不是规则本身，不剥的话用户一改，装配端会
 * 再补一次前缀成「- - …」；② 把资源里的 `%1$s` 显示成 `{用户名}`（与 `applyRulePlaceholders` 同口径，
 * 用户改完仍能替换回昵称）。纯函数，供 T2 断言。
 */
internal fun diaryRuleSeed(line: String): String = line.removePrefix("- ").replace("%1\$s", "{用户名}")
