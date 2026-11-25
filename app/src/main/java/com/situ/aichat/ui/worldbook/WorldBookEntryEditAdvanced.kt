package com.situ.aichat.ui.worldbook

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppSlider
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface
import kotlin.math.roundToInt

private val LocalWorldBookGroupSurface = compositionLocalOf<@Composable () -> Modifier> {
    { Modifier.appCardSurface() }
}

/**
 * 条目编辑器·高级设置四组（WB7b·契约 §12.4 人话对照表）：进阶匹配 / 插入方式 / 概率与时效 /
 * 联动与分组（+其他）。全部默认收起；组内有非默认值时初始展开（导入的书一眼看出动过哪些）。
 * 存储保持 ST 原语义原值——位置归并档只改显示，用户不动就保留原值。
 */
@Composable
internal fun EntryAdvancedSections(
    entry: WorldBookEntryEntity,
    viewModel: WorldBookEntryEditViewModel,
    cardModifier: @Composable () -> Modifier = { Modifier.appCardSurface() },
) {
    CompositionLocalProvider(LocalWorldBookGroupSurface provides cardModifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MatchingGroup(entry, viewModel)
            InsertionGroup(entry, viewModel)
            TimingGroup(entry, viewModel)
            RecursionGroup(entry, viewModel)
            MiscGroup(entry, viewModel)
        }
    }
}

@Composable
private fun MatchingGroup(entry: WorldBookEntryEntity, viewModel: WorldBookEntryEditViewModel) {
    val hasNonDefault = viewModel.secondaryKeys(entry).isNotEmpty() || entry.selectiveLogic != 0 ||
        entry.caseSensitive != null || entry.matchWholeWords != null || entry.scanDepth != null
    GroupCard(R.string.wb_group_matching, R.string.wb_group_matching_sub, hasNonDefault) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KeywordChipsEditor(
                label = stringResource(R.string.wb_secondary_keywords_label),
                keys = viewModel.secondaryKeys(entry),
                onAdd = { viewModel.addKeys(it, secondary = true) },
                onRemove = { viewModel.removeKey(it, secondary = true) },
                inputValue = viewModel.pendingSecondaryKeyInput,
                onInputValueChange = { viewModel.pendingSecondaryKeyInput = it },
            )
            DropdownRow(
                label = stringResource(R.string.wb_logic_label),
                value = stringResource(logicLabelRes(entry.selectiveLogic)),
                options = LOGIC_ORDER.map { logic ->
                    stringResource(logicLabelRes(logic)) to { viewModel.update { it.copy(selectiveLogic = logic) } }
                },
            )
            TriStateRow(
                label = stringResource(R.string.wb_case_label),
                value = entry.caseSensitive,
                onPick = { picked -> viewModel.update { it.copy(caseSensitive = picked) } },
            )
            TriStateRow(
                label = stringResource(R.string.wb_whole_label),
                value = entry.matchWholeWords,
                onPick = { picked -> viewModel.update { it.copy(matchWholeWords = picked) } },
                warning = stringResource(R.string.wb_whole_warning),
            )
            DropdownRow(
                label = stringResource(R.string.wb_scan_depth_label),
                value = if (entry.scanDepth == null) {
                    stringResource(R.string.wb_follow_global)
                } else {
                    stringResource(R.string.wb_scan_depth_custom)
                },
                options = listOf(
                    stringResource(R.string.wb_follow_global) to { viewModel.update { it.copy(scanDepth = null) } },
                    stringResource(R.string.wb_scan_depth_custom) to {
                        viewModel.update { if (it.scanDepth == null) it.copy(scanDepth = 2) else it }
                    },
                ),
            )
            entry.scanDepth?.let { depth ->
                StepperRow(
                    label = "",
                    value = depth,
                    range = 1..50,
                    valueText = stringResource(R.string.wb_recent_n_messages, depth),
                    onChange = { v -> viewModel.update { it.copy(scanDepth = v) } },
                )
            }
        }
    }
}

@Composable
private fun InsertionGroup(entry: WorldBookEntryEntity, viewModel: WorldBookEntryEditViewModel) {
    val hasNonDefault = entry.position != 0 || entry.insertionOrder != 100 || entry.depth != 4 || entry.role != 0
    GroupCard(R.string.wb_group_insertion, R.string.wb_group_insertion_sub, hasNonDefault) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val positionOptions = buildList {
                add(stringResource(R.string.wb_pos_before) to { viewModel.update { it.copy(position = 0) } })
                add(stringResource(R.string.wb_pos_after) to { viewModel.update { it.copy(position = 1) } })
                add(stringResource(R.string.wb_pos_end) to { viewModel.update { it.copy(position = 2) } })
                add(stringResource(R.string.wb_pos_at_depth) to { viewModel.update { it.copy(position = 4) } })
                // Outlet(7) 只在导入件已是 7 时显示，不给新选（契约 §2.2/§12.4）
                if (entry.position == 7) add(stringResource(R.string.wb_pos_outlet) to {})
            }
            DropdownRow(
                label = stringResource(R.string.wb_position_label),
                value = stringResource(positionLabelRes(entry.position)),
                options = positionOptions,
            )
            if (entry.position == 4) {
                StepperRow(
                    label = stringResource(R.string.wb_depth_label),
                    value = entry.depth,
                    range = 0..200,
                    valueText = stringResource(R.string.wb_n_messages, entry.depth),
                    onChange = { v -> viewModel.update { it.copy(depth = v) } },
                )
                DropdownRow(
                    label = stringResource(R.string.wb_role_label),
                    value = stringResource(roleLabelRes(entry.role)),
                    options = listOf(0, 1, 2).map { role ->
                        stringResource(roleLabelRes(role)) to { viewModel.update { it.copy(role = role) } }
                    },
                )
            }
            StepperRow(
                label = stringResource(R.string.wb_order_label),
                value = entry.insertionOrder,
                range = 0..9999,
                valueText = entry.insertionOrder.toString(),
                onChange = { v -> viewModel.update { it.copy(insertionOrder = v) } },
                hint = stringResource(R.string.wb_order_hint),
            )
        }
    }
}

@Composable
private fun TimingGroup(entry: WorldBookEntryEntity, viewModel: WorldBookEntryEditViewModel) {
    val hasNonDefault = entry.probability != 100 || entry.sticky != null || entry.cooldown != null || entry.delay != null
    GroupCard(R.string.wb_group_timing, R.string.wb_group_timing_sub, hasNonDefault) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val colors = AppTheme.colors
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.wb_probability_label),
                    style = AppTypography.body,
                    color = colors.text.primary,
                )
                AppSlider(
                    value = entry.probability.toFloat(),
                    onValueChange = { v -> viewModel.update { it.copy(probability = v.roundToInt().coerceIn(0, 100)) } },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                )
                Text("${entry.probability}%", style = AppTypography.secondary, color = colors.text.secondary)
            }
            NullableCountStepper(R.string.wb_sticky_label, entry.sticky) { v -> viewModel.update { it.copy(sticky = v) } }
            NullableCountStepper(R.string.wb_cooldown_label, entry.cooldown) { v -> viewModel.update { it.copy(cooldown = v) } }
            NullableCountStepper(R.string.wb_delay_label, entry.delay) { v -> viewModel.update { it.copy(delay = v) } }
        }
    }
}

@Composable
private fun RecursionGroup(entry: WorldBookEntryEntity, viewModel: WorldBookEntryEditViewModel) {
    val hasNonDefault = entry.excludeRecursion || entry.preventRecursion || entry.delayUntilRecursion != 0 ||
        entry.groupName.isNotBlank() || entry.groupWeight != 100 || entry.groupOverride || entry.useGroupScoring != null
    GroupCard(R.string.wb_group_recursion, R.string.wb_group_recursion_sub, hasNonDefault) {
        SettingsSwitchRow(
            title = stringResource(R.string.wb_exclude_recursion),
            checked = entry.excludeRecursion,
            onCheckedChange = { v -> viewModel.update { it.copy(excludeRecursion = v) } },
        )
        SettingsSwitchRow(
            title = stringResource(R.string.wb_prevent_recursion),
            checked = entry.preventRecursion,
            onCheckedChange = { v -> viewModel.update { it.copy(preventRecursion = v) } },
        )
        Column(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StepperRow(
                label = stringResource(R.string.wb_delay_recursion_label),
                value = entry.delayUntilRecursion,
                range = 0..10,
                valueText = if (entry.delayUntilRecursion == 0) {
                    stringResource(R.string.wb_off_zero)
                } else {
                    stringResource(R.string.wb_delay_recursion_value, entry.delayUntilRecursion)
                },
                onChange = { v -> viewModel.update { it.copy(delayUntilRecursion = v) } },
            )
            AppTextField(
                value = entry.groupName,
                onValueChange = { v -> viewModel.update { it.copy(groupName = v) } },
                label = stringResource(R.string.wb_group_name_label),
                placeholder = stringResource(R.string.wb_group_name_placeholder),
            )
            StepperRow(
                label = stringResource(R.string.wb_group_weight_label),
                value = entry.groupWeight,
                range = 1..1000,
                valueText = entry.groupWeight.toString(),
                onChange = { v -> viewModel.update { it.copy(groupWeight = v) } },
            )
        }
        SettingsSwitchRow(
            title = stringResource(R.string.wb_group_override),
            checked = entry.groupOverride,
            onCheckedChange = { v -> viewModel.update { it.copy(groupOverride = v) } },
        )
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            TriStateRow(
                label = stringResource(R.string.wb_group_scoring),
                value = entry.useGroupScoring,
                onPick = { picked -> viewModel.update { it.copy(useGroupScoring = picked) } },
            )
        }
    }
}

@Composable
private fun MiscGroup(entry: WorldBookEntryEntity, viewModel: WorldBookEntryEditViewModel) {
    GroupCard(R.string.wb_group_misc, R.string.wb_group_misc_sub, initiallyExpanded = entry.ignoreBudget) {
        SettingsSwitchRow(
            title = stringResource(R.string.wb_ignore_budget),
            subtitle = stringResource(R.string.wb_ignore_budget_sub),
            checked = entry.ignoreBudget,
            onCheckedChange = { v -> viewModel.update { it.copy(ignoreBudget = v) } },
        )
    }
}

// MARK: - 组容器与行件

/** 可折叠组卡：标题 + 一句内容摘要 + 折叠箭头；导入件有非默认值的组初始展开。 */
@Composable
private fun GroupCard(
    @StringRes titleRes: Int,
    @StringRes subtitleRes: Int,
    initiallyExpanded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val surface = LocalWorldBookGroupSurface.current
    Column(modifier = Modifier.fillMaxWidth().then(surface())) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableScale { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(titleRes), style = AppTypography.titleSmall, color = colors.text.primary)
                Text(stringResource(subtitleRes), style = AppTypography.caption, color = colors.text.secondary)
            }
            Icon(
                if (expanded) AppActionIcons.ChevronUp else AppActionIcons.ChevronDown,
                contentDescription = null,
                tint = colors.text.secondary,
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(bottom = 12.dp)) { content() }
        }
    }
}

/** 「N 条 / 关」可空计数步进器（sticky / cooldown / delay：0 存 null）。 */
@Composable
private fun NullableCountStepper(@StringRes labelRes: Int, value: Int?, onChange: (Int?) -> Unit) {
    val shown = value ?: 0
    StepperRow(
        label = stringResource(labelRes),
        value = shown,
        range = 0..999,
        valueText = if (shown == 0) stringResource(R.string.wb_off_zero) else stringResource(R.string.wb_n_messages, shown),
        onChange = { v -> onChange(v.takeIf { it > 0 }) },
    )
}

/** selectiveLogic 的 UI 顺序（常用在前；存储值仍为 ST 原值 0/3/2/1）。 */
private val LOGIC_ORDER = listOf(0, 3, 2, 1)

@StringRes
private fun logicLabelRes(logic: Int): Int = when (logic) {
    3 -> R.string.wb_logic_and_all
    2 -> R.string.wb_logic_not_any
    1 -> R.string.wb_logic_not_all
    else -> R.string.wb_logic_and_any
}

/** position 原值 → 显示档（2/3 归并「靠近对话末尾」·5/6 归并「角色设定之后」·契约 §2.2）。 */
@StringRes
private fun positionLabelRes(position: Int): Int = when (position) {
    0 -> R.string.wb_pos_before
    2, 3 -> R.string.wb_pos_end
    4 -> R.string.wb_pos_at_depth
    7 -> R.string.wb_pos_outlet
    else -> R.string.wb_pos_after
}

@StringRes
private fun roleLabelRes(role: Int): Int = when (role) {
    1 -> R.string.wb_role_user
    2 -> R.string.wb_role_assistant
    else -> R.string.wb_role_system
}
