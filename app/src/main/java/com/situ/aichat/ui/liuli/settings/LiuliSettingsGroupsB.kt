package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliStatusDotRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.LiuliValueRow
import com.situ.aichat.ui.settings.dotColorFor

/**
 * 设置主页 ⑦–⑪ 组（图纸 2026-09-06 卷四 §4.3 · F2 逐行）。规矩同 [personalizeGroup] 那半边：
 * 组函数返回「显了没」，行内一切照暖陶原样。
 */

/** ⑦ 故事（玫）。 */
@Composable
internal fun ColumnScope.storyGroup(term: String, cb: LiuliSettingsCallbacks): Boolean {
    val story = stringResource(R.string.story_global_settings_title)
    val storySub = stringResource(R.string.story_global_settings_subtitle)
    if (!settingsMatches(term, story, storySub)) return false
    LiuliGroup(header = stringResource(R.string.settings_group_story)) {
        LiuliNavRow(
            title = story,
            onClick = cb.onOpenStoryGlobalSettings,
            icon = AppFeatureIcons.Story,
            tileColor = LiuliPalette.tileStory,
            subtitle = storySub,
            divider = false,
        )
    }
    return true
}

/** ⑧ 世界（天蓝）：行尾回显鲜活度档名。 */
@Composable
internal fun ColumnScope.worldGroup(term: String, state: LiuliSettingsState, cb: LiuliSettingsCallbacks): Boolean {
    val world = stringResource(R.string.world_settings_title)
    if (!settingsMatches(term, world)) return false
    LiuliGroup(header = stringResource(R.string.settings_group_world)) {
        LiuliNavRow(
            title = world,
            onClick = cb.onOpenWorldSettings,
            icon = Icons.Filled.Public,
            tileColor = LiuliPalette.tileWorld,
            value = state.worldTierLabel,
            divider = false,
        )
    }
    return true
}

/** ⑨ 系统与通知（石墨）：通知 + 后台保障配对；语言点开弹窗（不是下一层页）。 */
@Composable
internal fun ColumnScope.systemGroup(
    term: String,
    state: LiuliSettingsState,
    cb: LiuliSettingsCallbacks,
    onOpenLanguage: () -> Unit,
): Boolean {
    val notif = stringResource(R.string.notif_settings_title)
    val bg = stringResource(R.string.bg_title)
    val bgSub = stringResource(R.string.bg_entry_subtitle)
    val sys = stringResource(R.string.sys_settings_title)
    val sysSub = stringResource(R.string.sys_settings_entry_subtitle)
    val language = stringResource(R.string.settings_language)
    if (!settingsMatches(term, notif, bg, bgSub, sys, sysSub, language)) return false
    LiuliGroup(header = stringResource(R.string.settings_group_system)) {
        LiuliNavRow(
            title = notif,
            onClick = cb.onOpenNotificationSettings,
            icon = Icons.Filled.Notifications,
            tileColor = LiuliPalette.tileSystem,
            value = liuliOnOffLabel(state.notifEnabled),
            divider = false,
        )
        LiuliNavRow(title = bg, onClick = cb.onOpenBackgroundReliability, icon = Icons.Filled.Bolt, tileColor = LiuliPalette.tileSystem, subtitle = bgSub)
        LiuliNavRow(title = sys, onClick = cb.onOpenSystemToggles, icon = Icons.Filled.Tune, tileColor = LiuliPalette.tileSystem, subtitle = sysSub)
        LiuliValueRow(
            title = language,
            value = liuliLangLabel(state.currentLangTag),
            onClick = onOpenLanguage,
            icon = Icons.Filled.Language,
            tileColor = LiuliPalette.tileSystem,
        )
    }
    return true
}

/** ⑩ 数据与诊断（灰）：备份恒显，其余三行在高级门后。 */
@Composable
internal fun ColumnScope.dataGroup(
    term: String,
    state: LiuliSettingsState,
    cb: LiuliSettingsCallbacks,
    advancedBadge: String,
): Boolean {
    val backup = stringResource(R.string.backup_title)
    val contextLog = stringResource(R.string.settings_context_log_title)
    val perf = stringResource(R.string.perf_title)
    val embedder = stringResource(R.string.embedder_status_title)
    if (!settingsMatches(term, backup, contextLog, perf, embedder)) return false
    val statusWord = stringResource(
        when (state.embedderState) {
            TextEmbedder.LoadState.LOADED -> R.string.embedder_status_ready
            TextEmbedder.LoadState.NOT_ATTEMPTED -> R.string.embedder_status_idle
            TextEmbedder.LoadState.FAILED -> R.string.embedder_status_unavailable
        },
    )
    val hint = stringResource(
        when (state.embedderState) {
            TextEmbedder.LoadState.LOADED -> R.string.embedder_status_hint_ready
            TextEmbedder.LoadState.NOT_ATTEMPTED -> R.string.embedder_status_hint_idle
            TextEmbedder.LoadState.FAILED -> R.string.embedder_status_hint_unavailable
        },
    )
    val dot = dotColorFor(state.embedderState, AppTheme.colors)
    LiuliGroup(header = stringResource(R.string.settings_group_data)) {
        LiuliNavRow(title = backup, onClick = cb.onOpenBackup, icon = Icons.Filled.Backup, tileColor = LiuliPalette.tileData, divider = false)
        LiuliGatedRow(state.advancedEnabled) {
            LiuliNavRow(
                title = contextLog,
                onClick = cb.onOpenContextLog,
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                tileColor = LiuliPalette.tileData,
                badge = advancedBadge,
            )
        }
        LiuliGatedRow(state.advancedEnabled) {
            LiuliNavRow(
                title = perf,
                onClick = cb.onOpenPerfCollect,
                icon = Icons.Filled.Speed,
                tileColor = LiuliPalette.tileData,
                badge = advancedBadge,
            )
        }
        LiuliGatedRow(state.advancedEnabled) {
            LiuliStatusDotRow(title = embedder, status = statusWord, dotColor = dot, subtitle = hint)
        }
    }
    return true
}

/** ⑪ 关于（灰）：高级开关并入本组（门后只剩那几行，开关文案点名它们）。 */
@Composable
internal fun ColumnScope.aboutGroup(term: String, state: LiuliSettingsState, cb: LiuliSettingsCallbacks, actions: LiuliSettingsActions): Boolean {
    val about = stringResource(R.string.about_title)
    val advanced = stringResource(R.string.settings_advanced_toggle_title)
    val advancedSub = stringResource(R.string.settings_advanced_toggle_subtitle)
    if (!settingsMatches(term, about, advanced, advancedSub)) return false
    LiuliGroup(header = stringResource(R.string.settings_group_about)) {
        LiuliNavRow(
            title = about,
            onClick = cb.onOpenAbout,
            icon = Icons.Filled.Info,
            tileColor = LiuliPalette.tileData,
            value = state.version,
            divider = false,
        )
        LiuliToggleRow(
            title = advanced,
            subtitle = advancedSub,
            checked = state.advancedEnabled,
            onCheckedChange = actions.onSetAdvancedMode,
            icon = Icons.Filled.Settings,
            tileColor = LiuliPalette.tileData,
        )
    }
    return true
}

/** 布尔回显文案（逐字搬暖陶 `onOffLabel`·F2）。 */
@Composable
internal fun liuliOnOffLabel(enabled: Boolean): String =
    stringResource(if (enabled) R.string.settings_state_on else R.string.settings_state_off)

/** 语言回显文案（逐字搬暖陶 `langLabel`·F2）。 */
@Composable
internal fun liuliLangLabel(tag: String): String =
    if (tag == "en") stringResource(R.string.lang_option_en) else stringResource(R.string.lang_option_zh)
