package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.SettingsOptionItem
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliSearchSlot
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.LiuliSwitch
import com.situ.aichat.ui.liuli.designsystem.LiuliTileTone
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** D-8 底部选单入口行 + 玻璃弹层（琉璃，须在 LiuliPage 内容层内调用）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> LiuliPickerRow(
    title: String,
    valueLabel: String,
    options: List<SettingsOptionItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    sheetSubtitle: String? = null,
    icon: ImageVector? = null,
    tileColor: LiuliTileTone? = null,
    divider: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    LiuliNavRow(
        title = title,
        subtitle = subtitle,
        value = valueLabel,
        icon = icon,
        tileColor = tileColor,
        divider = divider,
        onClick = { open = true },
        modifier = modifier,
    )
    if (open) {
        LiuliPickerSheet(
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

/** D-8 底部选单玻璃弹层（琉璃·超 8 项自动带搜索）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> LiuliPickerSheet(
    title: String,
    options: List<SettingsOptionItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(options, query) {
        val q = query.trim()
        if (q.isEmpty()) options else options.filter {
            it.title.contains(q, ignoreCase = true) || (it.subtitle?.contains(q, ignoreCase = true) == true)
        }
    }
    LiuliSheetShell(onDismissRequest = onDismiss, title = title, subtitle = subtitle) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            if (options.size > 8) {
                LiuliSearchSlot(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.settings_search_hint),
                    clearContentDescription = stringResource(R.string.chat_list_search_clear),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            LiuliGroup {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    itemsIndexed(filtered) { index, item ->
                        LiuliRadioRow(
                            title = item.title,
                            subtitle = item.subtitle,
                            selected = item.value == selected,
                            onSelect = { onSelect(item.value) },
                            divider = index > 0,
                            notifyWhenSelected = true,
                        )
                    }
                }
            }
        }
    }
}

/** D-8 范围步进器（琉璃）：摘要行 + 最少 / 最多双步进器。 */
@Composable
fun LiuliRangeStepper(
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
    divider: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f)) {
        LiuliValueRow(title = title, value = rangeSummary, divider = divider, enabled = enabled)
        LiuliStepperRow(title = minLabel, value = minValue, range = minRange, valueText = minValueText, onChange = onMinChange, enabled = enabled, divider = true)
        LiuliStepperRow(title = maxLabel, value = maxValue, range = maxRange, valueText = maxValueText, onChange = onMaxChange, enabled = enabled, divider = true)
    }
}

/** D-8 页顶总开关卡（琉璃玻璃卡）：彩色砖 + 标题 + 一句说明 + 开关。 */
@Composable
fun LiuliMasterCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tileTone: LiuliTileTone,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val haptics = LocalAppHaptics.current
    val onText = stringResource(R.string.settings_state_on)
    val offText = stringResource(R.string.settings_state_off)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = LiuliPageGeometry.groupGap)
            .liuliCardMaterial(LiuliShapes.group, dark)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { next ->
                    if (next) haptics.light() else haptics.soft()
                    onCheckedChange(next)
                },
            )
            .semantics { stateDescription = if (checked) onText else offText }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiuliGroupIconTile(icon, tileTone, modifier = Modifier.alpha(if (checked) 1f else 0.45f))
        Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AppTypography.body.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.W500), color = colors.text.primary)
            Text(subtitle, style = AppTypography.secondary.copy(fontSize = 13.sp), color = colors.text.secondary)
        }
        Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        LiuliSwitch(checked = checked, onCheckedChange = null)
    }
}

/** D-8 页顶信息说明条（琉璃）。 */
@Composable
fun LiuliInfoNote(text: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(LiuliShapes.group)
            .background(colors.status.infoContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.status.onInfo, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Text(text, style = AppTypography.secondary.copy(fontSize = 13.sp, lineHeight = 19.sp), color = colors.status.onInfo)
    }
}
