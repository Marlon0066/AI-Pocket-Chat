package com.situ.aichat.ui.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppSlider
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 日记设置（M07 7.1.5）。自动生成(开关 + 时间) + 交换日记(笔友) + AI 互动(评论开关 + 可评论角色多选 +
 * 评论延迟 1~15min 滑块)。三组 SettingsSection 卡壳（质感对齐二期 A7·整屏 AppTheme token 化）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiarySettingsScreen(
    onBack: () -> Unit,
    viewModel: DiarySettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showTimePicker by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.diary_settings_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(vertical = 8.dp),
        ) {
            // 自动生成
            SettingsSection(
                title = stringResource(R.string.diary_settings_autogen_header),
                footer = stringResource(R.string.diary_settings_autogen_footer),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.diary_settings_autogen_label),
                    checked = state.autoGenerateEnabled,
                    onCheckedChange = { viewModel.setAutoGenerateEnabled(it) },
                )
                if (state.autoGenerateEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showTimePicker = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.diary_settings_autogen_time), modifier = Modifier.weight(1f), style = AppTheme.typography.body)
                        Text(state.autoGenerateTime, color = AppTheme.colors.accent.text)
                    }
                    // R3 评论区活化（O3 锁定·默认关）：自动日记直接发布=跳过草稿、发布即走角色评论。
                    SettingsSwitchRow(
                        title = stringResource(R.string.diary_settings_auto_publish_label),
                        checked = state.autoPublishEnabled,
                        onCheckedChange = { viewModel.setAutoPublishEnabled(it) },
                    )
                    Text(
                        stringResource(R.string.diary_settings_auto_publish_footer),
                        style = AppTheme.typography.secondary,
                        color = AppTheme.colors.text.secondary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                // 宠物日记自动生成（独立开关·不受用户日记开关门控）。
                SettingsSwitchRow(
                    title = stringResource(R.string.diary_settings_pet_autogen_label),
                    checked = state.petAutoGenerateEnabled,
                    onCheckedChange = { viewModel.setPetAutoGenerateEnabled(it) },
                )
            }

            // R4 交换日记：笔友选择（空=自动「当天聊得最多」·O1 锁定「兼有」）。
            SettingsSection(
                title = stringResource(R.string.diary_settings_exchange_header),
                footer = stringResource(R.string.diary_settings_exchange_footer),
            ) {
                ExchangePartnerRow(
                    partnerUuid = state.exchangePartnerUuid,
                    characters = state.characters,
                    onSelect = { viewModel.setExchangePartner(it) },
                )
            }

            // AI 互动
            SettingsSection(
                title = stringResource(R.string.diary_settings_interaction_header),
                footer = stringResource(R.string.diary_settings_comment_footer),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.diary_settings_comment_label),
                    checked = state.commentEnabled,
                    onCheckedChange = { viewModel.setCommentEnabled(it) },
                )
                if (state.commentEnabled) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.diary_settings_comment_delay), modifier = Modifier.weight(1f), style = AppTheme.typography.body)
                            Text(
                                stringResource(R.string.diary_settings_comment_delay_value, state.commentDelay),
                                color = AppTheme.colors.text.secondary,
                            )
                        }
                        AppSlider(
                            value = state.commentDelay.toFloat(),
                            onValueChange = { viewModel.setCommentDelay(it.toInt()) },
                            valueRange = 1f..15f,
                            steps = 13,
                        )
                    }
                    Text(
                        stringResource(R.string.diary_settings_comment_chars),
                        // 非分区头 titleSmall（§4.A7 字映射未覆盖）→ AppTheme label（14sp·详情标题·§11 D-A7）。
                        style = AppTheme.typography.label,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    )
                    if (state.characters.isEmpty()) {
                        Text(
                            stringResource(R.string.diary_settings_comment_chars_all),
                            // bodyMedium（§4.A7 字映射未覆盖）→ AppTheme secondary（同 onSurfaceVariant 语义·§11 D-A7）。
                            style = AppTheme.typography.secondary,
                            color = AppTheme.colors.text.secondary,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    } else {
                        state.characters.forEach { ch ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleCharacter(ch.uuid) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(ch.name, modifier = Modifier.weight(1f), style = AppTheme.typography.body)
                                if (ch.selected) {
                                    Icon(Icons.Filled.Check, contentDescription = null, tint = AppTheme.colors.accent.text)
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.diary_settings_chars_footer),
                            style = AppTheme.typography.secondary,
                            color = AppTheme.colors.text.secondary,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }

    if (showTimePicker) {
        val parsed = remember(state.autoGenerateTime) { parseHm(state.autoGenerateTime) }
        val pickerState = rememberTimePickerState(initialHour = parsed.first, initialMinute = parsed.second, is24Hour = true)
        AppDialog(
            onDismissRequest = { showTimePicker = false },
            title = null,
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = {
                viewModel.setAutoGenerateTime("%02d:%02d".format(pickerState.hour, pickerState.minute))
                showTimePicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showTimePicker = false },
            content = { TimePicker(state = pickerState) },
        )
    }
}

private fun parseHm(time: String): Pair<Int, Int> {
    val parts = time.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 21
    val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return h to m
}

/** 笔友选择行（R4）：点开下拉 = 「自动」+ 全部角色单选；当前值回显（uuid 失配回落「自动」显示）。 */
@Composable
private fun ExchangePartnerRow(
    partnerUuid: String,
    characters: List<DiaryCharacterChoice>,
    onSelect: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val autoLabel = stringResource(R.string.diary_settings_exchange_auto)
    val currentLabel = characters.firstOrNull { it.uuid == partnerUuid }?.name ?: autoLabel
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { menuExpanded = true }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.diary_settings_exchange_partner),
                modifier = Modifier.weight(1f),
                style = AppTheme.typography.body,
            )
            Text(currentLabel, color = AppTheme.colors.accent.text)
        }
        // R1 已裁（D-2 核准·2026-08-06）：笔友单选清单项保留 DropdownMenuItem——AppMenuItem 无 trailingIcon
        // 槽，勾选标记属站点内部结构；容器已是 AppMenu 玻璃小笺。勿「统一」改掉。
        AppMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(autoLabel) },
                onClick = { onSelect(""); menuExpanded = false },
                trailingIcon = {
                    if (partnerUuid.isEmpty() || characters.none { it.uuid == partnerUuid }) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = AppTheme.colors.accent.text)
                    }
                },
            )
            characters.forEach { ch ->
                DropdownMenuItem(
                    text = { Text(ch.name) },
                    onClick = { onSelect(ch.uuid); menuExpanded = false },
                    trailingIcon = {
                        if (ch.uuid == partnerUuid) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = AppTheme.colors.accent.text)
                        }
                    },
                )
            }
        }
    }
}
