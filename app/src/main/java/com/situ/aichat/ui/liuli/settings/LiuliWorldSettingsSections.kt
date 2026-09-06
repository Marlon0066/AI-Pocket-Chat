package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliRadioRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.settings.WORLD_TIMEZONES
import com.situ.aichat.ui.settings.WorldSettingsUiState
import com.situ.aichat.ui.settings.gmtOffsetShort
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import java.time.ZoneId

/** 世界设置页的写口（与暖陶 `WorldSettingsViewModel` 一一对应）。 */
@Immutable
data class LiuliWorldSettingsCallbacks(
    val onSetVividness: (String) -> Unit,
    val onSetNotification: (String) -> Unit,
    val onOpenTimezoneSheet: () -> Unit,
    val onSetRelationships: (Boolean) -> Unit,
    val onSetRomance: (Boolean) -> Unit,
    val onOpenResidentSheet: () -> Unit,
)

/**
 * 世界设置五组（琉璃·图纸 2026-09-06 卷五 §4.1 屏 18）。节序 / 单选项 / 推荐角标 / 脚注逐字继承暖陶
 * `WorldSettingsScreen`；三档的**存储值**走 `AppSettings.WORLD_*` 常量（存串零碰）。
 *
 * 暖陶把「推荐 / 默认」角标画在名字右侧、说明画在名字下方；琉璃的单选行只有标题 + 副标两槽，
 * 故角标并进标题（`名字 · 推荐`），说明进副标——同一句话、同一处信息，不多占一行。
 */
@Composable
internal fun ColumnScope.liuliWorldSettingsGroups(
    state: WorldSettingsUiState,
    callbacks: LiuliWorldSettingsCallbacks,
) {
    LiuliGroup(
        header = stringResource(R.string.world_vividness_section),
        footer = stringResource(R.string.world_vividness_foot),
    ) {
        LiuliRadioRow(
            title = stringResource(R.string.world_vividness_lite_name),
            subtitle = stringResource(R.string.world_vividness_lite_desc),
            selected = state.vividnessTier == AppSettings.WORLD_VIVIDNESS_LITE,
            onSelect = { callbacks.onSetVividness(AppSettings.WORLD_VIVIDNESS_LITE) },
            divider = false,
        )
        LiuliRadioRow(
            title = stringResource(R.string.world_vividness_std_name) + TAG_SEP +
                stringResource(R.string.world_vividness_recommended),
            subtitle = stringResource(R.string.world_vividness_std_desc),
            selected = state.vividnessTier == AppSettings.WORLD_VIVIDNESS_STANDARD,
            onSelect = { callbacks.onSetVividness(AppSettings.WORLD_VIVIDNESS_STANDARD) },
        )
        LiuliRadioRow(
            title = stringResource(R.string.world_vividness_rich_name),
            subtitle = stringResource(R.string.world_vividness_rich_desc),
            selected = state.vividnessTier == AppSettings.WORLD_VIVIDNESS_RICH,
            onSelect = { callbacks.onSetVividness(AppSettings.WORLD_VIVIDNESS_RICH) },
        )
    }

    LiuliGroup(
        header = stringResource(R.string.world_notification_section),
        footer = stringResource(R.string.world_notification_foot),
    ) {
        LiuliRadioRow(
            title = stringResource(R.string.world_notification_silent_name),
            subtitle = stringResource(R.string.world_notification_silent_desc),
            selected = state.notificationTier == AppSettings.WORLD_NOTIFICATION_SILENT,
            onSelect = { callbacks.onSetNotification(AppSettings.WORLD_NOTIFICATION_SILENT) },
            divider = false,
        )
        LiuliRadioRow(
            title = stringResource(R.string.world_notification_gentle_name) + TAG_SEP +
                stringResource(R.string.world_notification_default_tag),
            subtitle = stringResource(R.string.world_notification_gentle_desc),
            selected = state.notificationTier == AppSettings.WORLD_NOTIFICATION_GENTLE,
            onSelect = { callbacks.onSetNotification(AppSettings.WORLD_NOTIFICATION_GENTLE) },
        )
        LiuliRadioRow(
            title = stringResource(R.string.world_notification_all_name),
            subtitle = stringResource(R.string.world_notification_all_desc),
            selected = state.notificationTier == AppSettings.WORLD_NOTIFICATION_ALL,
            onSelect = { callbacks.onSetNotification(AppSettings.WORLD_NOTIFICATION_ALL) },
        )
    }

    LiuliGroup(header = stringResource(R.string.world_tz_section)) {
        // 右值 = 「跟随设备 · GMT+8」或「北京 · GMT+8」；表外的钉值直接显 zoneId（逐字照暖陶 :164–172）。
        val tzValue = if (state.timezoneId == null) {
            stringResource(R.string.world_tz_follow_device) + GMT_PREFIX + gmtOffsetShort(ZoneId.systemDefault().id)
        } else {
            val name = WORLD_TIMEZONES.firstOrNull { it.zoneId == state.timezoneId }
                ?.let { stringResource(it.nameRes) } ?: state.timezoneId
            "$name$GMT_PREFIX" + gmtOffsetShort(state.timezoneId)
        }
        LiuliNavRow(
            title = stringResource(R.string.world_tz_row_title),
            subtitle = stringResource(R.string.world_tz_row_sub),
            value = tzValue,
            onClick = callbacks.onOpenTimezoneSheet,
            icon = Icons.Filled.Schedule,
            tileColor = LiuliPalette.tileWorld,
            divider = false,
        )
    }

    LiuliGroup(
        header = stringResource(R.string.world_pairs_section),
        footer = stringResource(R.string.world_pairs_foot),
    ) {
        LiuliToggleRow(
            title = stringResource(R.string.world_pairs_rel_title),
            subtitle = stringResource(R.string.world_pairs_rel_sub),
            checked = state.relationshipsEnabled,
            onCheckedChange = callbacks.onSetRelationships,
            divider = false,
        )
        LiuliToggleRow(
            title = stringResource(R.string.world_pairs_rom_title),
            subtitle = stringResource(R.string.world_pairs_rom_sub),
            checked = state.romanceEnabled,
            onCheckedChange = callbacks.onSetRomance,
        )
    }

    // 居民节（战役 B·图纸 §4.3）：入口行「让一位新居民搬来」+ 已有 n/50 计数 → ResidentCreateSheet。
    LiuliGroup(
        header = stringResource(R.string.world_resident_section),
        footer = stringResource(R.string.world_resident_foot),
    ) {
        LiuliNavRow(
            title = stringResource(R.string.world_resident_entry_title),
            subtitle = stringResource(R.string.world_resident_entry_sub, state.residentCount, state.residentCap),
            onClick = callbacks.onOpenResidentSheet,
            icon = Icons.Filled.Add,
            tileColor = LiuliPalette.tileWorld,
            divider = false,
        )
    }
}

/** 名字与角标之间的分隔（暖陶是并排两个 Text·琉璃并进一句）。 */
private const val TAG_SEP = " · "

/** 「· GMT」拼接前缀（与 [LiuliWorldTimezoneSheet] 同一枚·改一处必须改两处）。 */
private const val GMT_PREFIX = " · GMT"
