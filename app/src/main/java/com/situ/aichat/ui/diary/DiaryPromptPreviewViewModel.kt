package com.situ.aichat.ui.diary

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.prompt.diary.DiaryExchangePromptStrings
import com.situ.aichat.prompt.diary.DiaryPreviewSlots
import com.situ.aichat.prompt.diary.DiaryPromptPreview
import com.situ.aichat.prompt.diary.DiaryPromptStrings
import com.situ.aichat.prompt.diary.DiaryRuleValues
import com.situ.aichat.prompt.diary.PreviewLine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 只读预览 VM（2026-09-05·图纸 §3.7/§4.3）：取设置 + 用户昵称（+ TA 的信侧取笔友名），调纯函数
 * [DiaryPromptPreview] 出行模型。装配本身不查库不联网；这里只读这三样喂进去。
 */
@HiltViewModel
class DiaryPromptPreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val settingsRepo: SettingsRepository,
    private val userProfileDao: UserProfileDao,
    private val characterDao: CharacterDao,
) : ViewModel() {

    private val isExchange: Boolean = savedStateHandle.get<String>(ARG_SECTION) == SECTION_EXCHANGE

    @StringRes
    val titleRes: Int =
        if (isExchange) R.string.diary_rules_preview_exchange else R.string.diary_rules_preview_mine

    private val _lines = MutableStateFlow<List<PreviewLine>>(emptyList())
    val lines: StateFlow<List<PreviewLine>> = _lines.asStateFlow()

    init {
        viewModelScope.launch {
            val ps = PromptStrings(context)
            val slots = DiaryPreviewSlots.from(ps)
            val settings = settingsRepo.getAppSettings()
            val profile = userProfileDao.get()
            _lines.value = if (isExchange) {
                val exStrings = DiaryExchangePromptStrings.from(ps)
                val userName = profile?.nickname?.trim()?.takeIf { it.isNotEmpty() } ?: exStrings.userFallback
                DiaryPromptPreview.buildExchange(
                    strings = exStrings,
                    slots = slots,
                    userName = userName,
                    characterName = resolvePenpalName(settings.diaryExchangePartnerUuid, slots.characterFallback),
                    values = DiaryRuleValues(
                        wordCount = settings.diaryExchangeWordCount,
                        narrativePerson = settings.diaryExchangeNarrativePerson,
                        styleHint = settings.diaryExchangeStyleHint,
                        extraRules = settings.diaryExchangeExtraRules,
                    ),
                )
            } else {
                val strings = DiaryPromptStrings.from(ps)
                val userName = profile?.nickname?.trim()?.takeIf { it.isNotEmpty() } ?: strings.userFallback
                DiaryPromptPreview.buildMine(
                    strings = strings,
                    slots = slots,
                    userName = userName,
                    values = DiaryRuleValues(
                        wordCount = settings.diaryWordCount,
                        narrativePerson = settings.diaryNarrativePerson,
                        styleHint = settings.diaryStyleHint,
                        extraRules = settings.diaryExtraRules,
                    ),
                )
            }
        }
    }

    /** 固定笔友优先 → 否则任取一个角色（预览只为「看清位置」，不复算当天笔友）→ 一个角色都没有 → 兜底名。 */
    private suspend fun resolvePenpalName(fixedUuid: String, fallback: String): String {
        fixedUuid.trim().takeIf { it.isNotEmpty() }?.let { uuid ->
            characterDao.getByUuid(uuid)?.name?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return characterDao.getAll().firstOrNull()?.name?.takeIf { it.isNotEmpty() } ?: fallback
    }

    companion object {
        const val ARG_SECTION = "section"
        const val SECTION_EXCHANGE = "exchange"
    }
}
