package com.situ.aichat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppRadio
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.world.resident.ResidentCreateSheet
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** 世界时区 8 常用区（图纸 §4.8·顺序锁死·显示名走资源）。onboarding 与设置共用。 */
internal data class WorldTimezoneOption(val zoneId: String, val nameRes: Int)

internal val WORLD_TIMEZONES: List<WorldTimezoneOption> = listOf(
    WorldTimezoneOption("Asia/Shanghai", R.string.world_tz_city_beijing),
    WorldTimezoneOption("Asia/Tokyo", R.string.world_tz_city_tokyo),
    WorldTimezoneOption("Asia/Singapore", R.string.world_tz_city_singapore),
    WorldTimezoneOption("Australia/Sydney", R.string.world_tz_city_sydney),
    WorldTimezoneOption("Europe/London", R.string.world_tz_city_london),
    WorldTimezoneOption("Europe/Paris", R.string.world_tz_city_paris),
    WorldTimezoneOption("America/New_York", R.string.world_tz_city_newyork),
    WorldTimezoneOption("America/Los_Angeles", R.string.world_tz_city_la),
)

/** 时区 → 「+8 / -5 / +5:30」短偏移串（图纸 §4.4·`ZoneId.rules.getOffset(now)`·非法串安全空）。 */
internal fun gmtOffsetShort(zoneId: String): String = runCatching {
    val secs = ZoneId.of(zoneId).rules.getOffset(Instant.now()).totalSeconds
    val h = secs / 3600
    val m = abs(secs % 3600) / 60
    val sign = if (secs >= 0) "+" else "-"
    if (m == 0) "$sign${abs(h)}" else "$sign${abs(h)}:${"%02d".format(m)}"
}.getOrDefault("")

/**
 * 世界设置二级页（图纸 §4.4）：鲜活度 / 世界通知 / 我的时区 / 角色之间 四节，全部即时写库（无「保存」）。
 * M3 token 区（照设置家族·不混 AppTheme 世界深色件）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldSettingsScreen(
    onBack: () -> Unit,
    viewModel: WorldSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showTzSheet by remember { mutableStateOf(false) }
    var showResidentSheet by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.world_settings_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 鲜活度节。
            WorldSettingsGroup(stringResource(R.string.world_vividness_section)) {
                WorldRadioRow(
                    name = stringResource(R.string.world_vividness_lite_name),
                    tag = null,
                    desc = stringResource(R.string.world_vividness_lite_desc),
                    selected = state.vividnessTier == AppSettings.WORLD_VIVIDNESS_LITE,
                    onSelect = { viewModel.setVividness(AppSettings.WORLD_VIVIDNESS_LITE) },
                )
                WorldRadioRow(
                    name = stringResource(R.string.world_vividness_std_name),
                    tag = stringResource(R.string.world_vividness_recommended),
                    desc = stringResource(R.string.world_vividness_std_desc),
                    selected = state.vividnessTier == AppSettings.WORLD_VIVIDNESS_STANDARD,
                    onSelect = { viewModel.setVividness(AppSettings.WORLD_VIVIDNESS_STANDARD) },
                )
                WorldRadioRow(
                    name = stringResource(R.string.world_vividness_rich_name),
                    tag = null,
                    desc = stringResource(R.string.world_vividness_rich_desc),
                    selected = state.vividnessTier == AppSettings.WORLD_VIVIDNESS_RICH,
                    onSelect = { viewModel.setVividness(AppSettings.WORLD_VIVIDNESS_RICH) },
                )
            }
            WorldSectionFoot(stringResource(R.string.world_vividness_foot))

            // 世界通知节。
            WorldSettingsGroup(stringResource(R.string.world_notification_section)) {
                WorldRadioRow(
                    name = stringResource(R.string.world_notification_silent_name),
                    tag = null,
                    desc = stringResource(R.string.world_notification_silent_desc),
                    selected = state.notificationTier == AppSettings.WORLD_NOTIFICATION_SILENT,
                    onSelect = { viewModel.setNotification(AppSettings.WORLD_NOTIFICATION_SILENT) },
                )
                WorldRadioRow(
                    name = stringResource(R.string.world_notification_gentle_name),
                    tag = stringResource(R.string.world_notification_default_tag),
                    desc = stringResource(R.string.world_notification_gentle_desc),
                    selected = state.notificationTier == AppSettings.WORLD_NOTIFICATION_GENTLE,
                    onSelect = { viewModel.setNotification(AppSettings.WORLD_NOTIFICATION_GENTLE) },
                )
                WorldRadioRow(
                    name = stringResource(R.string.world_notification_all_name),
                    tag = null,
                    desc = stringResource(R.string.world_notification_all_desc),
                    selected = state.notificationTier == AppSettings.WORLD_NOTIFICATION_ALL,
                    onSelect = { viewModel.setNotification(AppSettings.WORLD_NOTIFICATION_ALL) },
                )
            }
            WorldSectionFoot(stringResource(R.string.world_notification_foot))

            // 我的时区节。
            WorldSettingsGroup(stringResource(R.string.world_tz_section)) {
                val tzValue = if (state.timezoneId == null) {
                    stringResource(R.string.world_tz_follow_device) + " · GMT" + gmtOffsetShort(ZoneId.systemDefault().id)
                } else {
                    val name = WORLD_TIMEZONES.firstOrNull { it.zoneId == state.timezoneId }
                        ?.let { stringResource(it.nameRes) } ?: state.timezoneId!!
                    "$name · GMT" + gmtOffsetShort(state.timezoneId!!)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTzSheet = true }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.world_tz_row_title), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.world_tz_row_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(tzValue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }

            // 角色之间节。
            WorldSettingsGroup(stringResource(R.string.world_pairs_section)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.world_pairs_rel_title),
                    subtitle = stringResource(R.string.world_pairs_rel_sub),
                    checked = state.relationshipsEnabled,
                    onCheckedChange = { viewModel.setRelationships(it) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.world_pairs_rom_title),
                    subtitle = stringResource(R.string.world_pairs_rom_sub),
                    checked = state.romanceEnabled,
                    onCheckedChange = { viewModel.setRomance(it) },
                )
            }
            WorldSectionFoot(stringResource(R.string.world_pairs_foot))

            // 居民节（战役 B·图纸 §4.3）：入口行「让一位新居民搬来」+ 已有 n/50 计数 → ResidentCreateSheet。
            WorldSettingsGroup(stringResource(R.string.world_resident_section)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showResidentSheet = true }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.world_resident_entry_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.world_resident_entry_sub, state.residentCount, state.residentCap),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            WorldSectionFoot(stringResource(R.string.world_resident_foot))

            Spacer(Modifier.width(0.dp))
        }
    }

    if (showResidentSheet) {
        ResidentCreateSheet(onDismiss = { showResidentSheet = false })
    }

    if (showTzSheet) {
        WorldTimezoneSheet(
            currentZoneId = state.timezoneId,
            onPick = {
                viewModel.setTimezone(it)
                showTzSheet = false
            },
            onDismiss = { showTzSheet = false },
        )
    }
}

/** 分组卡（照设置家族 SettingsGroupCard 同款：标题 labelLarge/primary + heading + 圆角 Surface）。 */
@Composable
private fun WorldSettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            // start 4 → 屏缘缩进 20+4=24（一期组标题先例）；appCardSurface 取代 tonal Surface。
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp).semantics { heading() },
        )
        Column(Modifier.fillMaxWidth().appCardSurface(), content = content)
    }
}

/** 节脚注（bodySmall/onSurfaceVariant·padding horizontal 24dp）。 */
@Composable
private fun WorldSectionFoot(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

/** 单选行（heightIn 56dp·RadioButton + 档名[+推荐/默认缀] + 描述·整行 selectable role=RadioButton）。 */
@Composable
private fun WorldRadioRow(name: String, tag: String?, desc: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppRadio(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                if (tag != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 我的时区 sheet（图纸 §4.4·设置页与首启共用）：跟随设备（当前偏移）+ 8 常用区；已钉且不在表内 → 追加「当前 · {id}」行。
 * `onPick(null)` = 跟随设备。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorldTimezoneSheet(
    currentZoneId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AppSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.world_tz_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                stringResource(R.string.world_tz_sheet_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.width(0.dp))
            // 跟随设备。
            TimezoneRow(
                label = stringResource(R.string.world_tz_follow_device) + " · GMT" + gmtOffsetShort(ZoneId.systemDefault().id),
                selected = currentZoneId == null,
                onClick = { onPick(null) },
            )
            // 8 常用区。
            WORLD_TIMEZONES.forEach { opt ->
                TimezoneRow(
                    label = stringResource(opt.nameRes) + " · GMT" + gmtOffsetShort(opt.zoneId),
                    selected = currentZoneId == opt.zoneId,
                    onClick = { onPick(opt.zoneId) },
                )
            }
            // 已钉且不在表内 → 展示当前钉值。
            if (currentZoneId != null && WORLD_TIMEZONES.none { it.zoneId == currentZoneId }) {
                TimezoneRow(
                    label = stringResource(R.string.char_world_city_current) + " · " + currentZoneId,
                    selected = true,
                    onClick = { onPick(currentZoneId) },
                )
            }
        }
    }
}

@Composable
private fun TimezoneRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) {
            Text(
                stringResource(R.string.char_world_city_current),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
