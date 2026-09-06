package com.situ.aichat.ui.liuli.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow

/**
 * 设置主页 ①–⑥ 组（图纸 2026-09-06 卷四 §4.3 · F2 逐行·顺序 = FABLE5_SETTINGS_REORG_PROPOSAL）。
 *
 * 每个组函数返回「本组这次显了没」：搜索词命中不到就整组不组合（含组标题·A-4），
 * 调用方据此统计「全隐 → 无结果」。行内值 / 顺序 / 文案一律照暖陶原样，搜索只决定显不显。
 * 砖色一组一色（A-5·`LiuliPalette` 十色）。
 */

/** 高级门控行（逐字搬暖陶 `AdvancedGatedRow`·F2 :407·机制锁 §9 ④）：展开 / 收起，减少动画时直切。 */
@Composable
internal fun LiuliGatedRow(visible: Boolean, content: @Composable () -> Unit) {
    if (rememberReduceMotion()) {
        if (visible) content()
    } else {
        AnimatedVisibility(
            visible = visible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            content()
        }
    }
}

/** ① 个性化（钴蓝）。 */
@Composable
internal fun ColumnScope.personalizeGroup(term: String, state: LiuliSettingsState, cb: LiuliSettingsCallbacks): Boolean {
    val appearance = stringResource(R.string.appearance_title)
    val sticker = stringResource(R.string.settings_sticker_title)
    if (!settingsMatches(term, appearance, sticker)) return false
    LiuliGroup(header = stringResource(R.string.settings_group_personalize)) {
        LiuliNavRow(
            title = appearance,
            onClick = cb.onOpenAppearance,
            icon = Icons.Filled.Palette,
            tileColor = LiuliPalette.tilePersonalize,
            value = state.appearanceLabel,
            divider = false,
        )
        LiuliNavRow(
            title = sticker,
            onClick = cb.onOpenStickerManagement,
            icon = Icons.Filled.Mood,
            tileColor = LiuliPalette.tilePersonalize,
        )
    }
    return true
}

/** ② API 与模型（靛）。未配置时行尾走警示值（§4.3）；配好了把「服务商 · 模型」放副标（同暖陶）。 */
@Composable
internal fun ColumnScope.apiGroup(term: String, state: LiuliSettingsState, cb: LiuliSettingsCallbacks): Boolean {
    val apiConfig = stringResource(R.string.settings_api_config)
    val fnAssign = stringResource(R.string.api_fn_assign_title)
    val fnAssignSub = stringResource(R.string.api_fn_assign_subtitle)
    if (!settingsMatches(term, apiConfig, state.activeConfigLabel, fnAssign, fnAssignSub)) return false
    LiuliGroup(header = stringResource(R.string.settings_group_api)) {
        LiuliNavRow(
            title = apiConfig,
            onClick = cb.onOpenApiConfig,
            icon = Icons.Filled.Dns,
            tileColor = LiuliPalette.tileApi,
            subtitle = state.activeConfigLabel,
            value = if (state.activeConfigLabel == null) stringResource(R.string.settings_api_not_configured) else null,
            valueWarning = true,
            divider = false,
        )
        LiuliNavRow(
            title = fnAssign,
            onClick = cb.onOpenApiFunctions,
            icon = Icons.Filled.SwapHoriz,
            tileColor = LiuliPalette.tileApi,
            subtitle = fnAssignSub,
        )
    }
    return true
}

/** ③ 聊天行为（青）：管「TA 怎么回消息」。 */
@Composable
internal fun ColumnScope.chatBehaviorGroup(term: String, state: LiuliSettingsState, cb: LiuliSettingsCallbacks, actions: LiuliSettingsActions): Boolean {
    val replyRule = stringResource(R.string.reply_rule_title)
    val replyRuleSub = stringResource(R.string.reply_rule_entry_subtitle)
    val emotion = stringResource(R.string.chat_effect_emotion_title)
    val tone = stringResource(R.string.settings_texting_tone_title)
    val toneSub = stringResource(R.string.settings_texting_tone_desc)
    val immersive = stringResource(R.string.immersive_settings_title)
    val filter = stringResource(R.string.content_filter_title)
    if (!settingsMatches(term, replyRule, replyRuleSub, emotion, tone, toneSub, immersive, filter)) return false
    LiuliGroup(header = stringResource(R.string.settings_group_chat_behavior)) {
        LiuliNavRow(
            title = replyRule,
            onClick = cb.onOpenReplyRules,
            icon = Icons.Filled.Forum,
            tileColor = LiuliPalette.tileChat,
            subtitle = replyRuleSub,
            divider = false,
        )
        LiuliToggleRow(
            title = emotion,
            checked = state.emotionAnimEnabled,
            onCheckedChange = actions.onSetEmotionAnimation,
            icon = Icons.Filled.AutoAwesome,
            tileColor = LiuliPalette.tileChat,
        )
        LiuliToggleRow(
            title = tone,
            subtitle = toneSub,
            checked = state.textingToneEnabled,
            onCheckedChange = actions.onSetTextingTone,
            icon = Icons.AutoMirrored.Filled.Chat,
            tileColor = LiuliPalette.tileChat,
        )
        LiuliNavRow(title = immersive, onClick = cb.onOpenImmersiveSettings, icon = Icons.Filled.Bedtime, tileColor = LiuliPalette.tileChat)
        LiuliNavRow(title = filter, onClick = cb.onOpenContentFilter, icon = Icons.Filled.FilterAlt, tileColor = LiuliPalette.tileChat)
    }
    return true
}

/** ④ 记忆与设定（绿）：管「TA 记得什么、是谁」；末行是高级门后的提示词模块。 */
@Composable
internal fun ColumnScope.memoryLoreGroup(
    term: String,
    state: LiuliSettingsState,
    cb: LiuliSettingsCallbacks,
    advancedBadge: String,
): Boolean {
    val memory = stringResource(R.string.mem_settings_title)
    val memorySub = stringResource(R.string.mem_settings_entry_subtitle)
    val worldbook = stringResource(R.string.wb_hub_title)
    val growth = stringResource(R.string.growth_settings_title)
    val growthSub = stringResource(R.string.growth_settings_entry_subtitle)
    val calendar = stringResource(R.string.cal_title)
    val prompt = stringResource(R.string.pm_title)
    if (!settingsMatches(term, memory, memorySub, worldbook, growth, growthSub, calendar, prompt)) return false
    LiuliGroup(header = stringResource(R.string.settings_group_memory_lore)) {
        LiuliNavRow(
            title = memory,
            onClick = cb.onOpenMemorySettings,
            icon = Icons.Filled.Memory,
            tileColor = LiuliPalette.tileMemory,
            subtitle = memorySub,
            divider = false,
        )
        LiuliNavRow(title = worldbook, onClick = cb.onOpenWorldBooks, icon = AppFeatureIcons.Worldbook, tileColor = LiuliPalette.tileMemory)
        LiuliNavRow(
            title = growth,
            onClick = cb.onOpenGrowthSettings,
            icon = Icons.Filled.Insights,
            tileColor = LiuliPalette.tileMemory,
            subtitle = growthSub,
        )
        LiuliNavRow(title = calendar, onClick = cb.onOpenCalendarAwareness, icon = Icons.Filled.CalendarMonth, tileColor = LiuliPalette.tileMemory)
        LiuliGatedRow(state.advancedEnabled) {
            LiuliNavRow(
                title = prompt,
                onClick = cb.onOpenPromptModules,
                icon = Icons.Filled.Extension,
                tileColor = LiuliPalette.tileMemory,
                badge = advancedBadge,
            )
        }
    }
    return true
}

/** ⑤ 语音（琥珀）。 */
@Composable
internal fun ColumnScope.voiceGroup(term: String, state: LiuliSettingsState, cb: LiuliSettingsCallbacks): Boolean {
    val tts = stringResource(R.string.settings_tts_row_title)
    val call = stringResource(R.string.voice_call_settings_title)
    if (!settingsMatches(term, tts, call)) return false
    LiuliGroup(header = stringResource(R.string.settings_group_voice)) {
        LiuliNavRow(
            title = tts,
            onClick = cb.onOpenTtsConfig,
            icon = Icons.Filled.GraphicEq,
            tileColor = LiuliPalette.tileVoice,
            value = state.ttsProviderName,
            divider = false,
        )
        LiuliNavRow(title = call, onClick = cb.onOpenVoiceCallSettings, icon = Icons.Filled.Call, tileColor = LiuliPalette.tileVoice)
    }
    return true
}

/** ⑥ AI 自动创作（橙）。 */
@Composable
internal fun ColumnScope.autoContentGroup(term: String, cb: LiuliSettingsCallbacks): Boolean {
    val diary = stringResource(R.string.diary_settings_title)
    val moment = stringResource(R.string.moment_settings_title)
    if (!settingsMatches(term, diary, moment)) return false
    LiuliGroup(header = stringResource(R.string.settings_group_auto_content)) {
        LiuliNavRow(title = diary, onClick = cb.onOpenDiarySettings, icon = Icons.Filled.Book, tileColor = LiuliPalette.tileCreation, divider = false)
        LiuliNavRow(title = moment, onClick = cb.onOpenMomentSettings, icon = Icons.Filled.Groups, tileColor = LiuliPalette.tileCreation)
    }
    return true
}
