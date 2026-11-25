package com.situ.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppRadio
import com.situ.aichat.ui.designsystem.AppSearchField
import com.situ.aichat.ui.designsystem.AppSettingsRow
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppStepper
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface

/** 选项卡片 / 底部选单共用的选项条目。 */
data class SettingsOptionItem<T>(
    val value: T,
    val title: String,
    val subtitle: String? = null,
)

/** D-8 选项卡片（暖陶）：单选 + 标题 + 一句说明，整行 selectable。 */
@Composable
fun <T> SettingsOptionCards(
    options: List<SettingsOptionItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    Column(modifier = modifier.fillMaxWidth().selectableGroup().alpha(if (enabled) 1f else 0.48f)) {
        options.forEachIndexed { index, item ->
            if (index > 0) AppListDivider(startInset = 48.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = item.value == selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = {
                            haptics.selection()
                            onSelect(item.value)
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppRadio(selected = item.value == selected, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.title, style = AppTypography.settingsRowTitle, color = colors.text.primary)
                    if (!item.subtitle.isNullOrBlank()) {
                        Text(item.subtitle, style = AppTypography.settingsRowSubtitle, color = colors.text.secondary)
                    }
                }
            }
        }
    }
}

/** D-8 底部选单入口行 + 弹层（超过 8 项自动出现搜索框）。 */
@Composable
fun <T> SettingsPickerRow(
    title: String,
    valueLabel: String,
    options: List<SettingsOptionItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    sheetSubtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    AppSettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        value = valueLabel,
        showChevron = true,
        enabled = enabled,
        onClick = { open = true },
        modifier = modifier.fillMaxWidth(),
    )
    if (open) {
        SettingsPickerSheet(
            title = title,
            subtitle = sheetSubtitle ?: subtitle,
            options = options,
            selected = selected,
            onSelect = {
                open = false
                onSelect(it)
            },
            onDismiss = { open = false },
        )
    }
}

/** 底部选单弹层（暖陶）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingsPickerSheet(
    title: String,
    options: List<SettingsOptionItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    val colors = AppTheme.colors
    var query by remember { mutableStateOf("") }
    val filtered = remember(options, query) {
        val q = query.trim()
        if (q.isEmpty()) options else options.filter {
            it.title.contains(q, ignoreCase = true) || (it.subtitle?.contains(q, ignoreCase = true) == true)
        }
    }
    AppSheet(onDismissRequest = onDismiss, title = title) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = AppTypography.settingsRowSubtitle,
                    color = colors.text.secondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (options.size > 8) {
                AppSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.settings_search_hint),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                itemsIndexed(filtered) { index, item ->
                    if (index > 0) AppListDivider(startInset = 20.dp)
                    val isSelected = item.value == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item.value) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                item.title,
                                style = AppTypography.settingsRowTitle,
                                color = if (isSelected) colors.accent.text else colors.text.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!item.subtitle.isNullOrBlank()) {
                                Text(item.subtitle, style = AppTypography.settingsRowSubtitle, color = colors.text.secondary)
                            }
                        }
                        if (isSelected) {
                            Icon(AppActionIcons.Check, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** D-8 范围步进器（暖陶）：标题 + 右侧范围摘要 + 「最少 / 最多」双步进器。 */
@Composable
fun SettingsRangeStepper(
    title: String,
    rangeSummary: String,
    minValue: Int,
    minRange: IntRange,
    minValueText: String,
    onMinChange: (Int) -> Unit,
    maxValue: Int,
    maxRange: IntRange,
    maxValueText: String,
    onMaxChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minLabel: String = stringResource(R.string.settings_range_min),
    maxLabel: String = stringResource(R.string.settings_range_max),
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.48f).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AppTypography.settingsRowTitle, color = colors.text.primary)
            Text(rangeSummary, style = AppTypography.settingsRowTitle, color = colors.accent.text)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(minLabel, style = AppTypography.settingsRowSubtitle, color = colors.text.secondary)
            AppStepper(value = minValue, valueText = minValueText, range = minRange, onValueChange = { if (enabled) onMinChange(it) })
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(maxLabel, style = AppTypography.settingsRowSubtitle, color = colors.text.secondary)
            AppStepper(value = maxValue, valueText = maxValueText, range = maxRange, onValueChange = { if (enabled) onMaxChange(it) })
        }
    }
}

/** D-8 页顶总开关卡（暖陶）：大砖 + 醒目标题 + 一句说明 + 开关，整卡可切换。 */
@Composable
fun SettingsMasterCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val onText = stringResource(R.string.a11y_switch_on)
    val offText = stringResource(R.string.a11y_switch_off)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .appCardSurface(raised = true)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { next ->
                    if (next) haptics.light() else haptics.soft()
                    onCheckedChange(next)
                },
            )
            .semantics { stateDescription = if (checked) onText else offText }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) colors.accent.container else colors.surface.sunken),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (checked) colors.accent.text else colors.text.tertiary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AppTypography.label, color = colors.text.primary)
            Text(subtitle, style = AppTypography.settingsRowSubtitle, color = colors.text.secondary)
        }
        AppSwitch(checked = checked, onCheckedChange = null)
    }
}

/** D-8 页顶说明信息条（暖陶）：柔和信息底 + ⓘ 图标 + 说明句。 */
@Composable
fun SettingsInfoNote(text: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(AppShapes.medium)
            .background(colors.status.infoContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.status.onInfo, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Text(text, style = AppTypography.settingsRowSubtitle, color = colors.status.onInfo)
    }
}
