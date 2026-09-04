package com.situ.aichat.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.content.ContentFilterRule
import com.situ.aichat.content.ContentFilterService
import com.situ.aichat.content.FilterMode
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppFormBar
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppSettingsRow
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTopBar
import java.util.UUID

/**
 * 内容过滤设置页（14.3c·1:1 iOS `ContentFilterSettingsView`）：预设过滤规则（开关）+ 自定义正则规则（增删改 +
 * 实时测试）。规则变更经 [ContentFilterSettingsViewModel] 即时持久化，被 ChatViewModel/BusyReplyService 消费。
 * 规则编辑用同屏全屏编辑态（[editingRule]）替代 iOS 的 modal sheet（安卓地道；复杂表单 + 多行测试框更稳）。
 */
@Composable
fun ContentFilterSettingsScreen(
    onBack: () -> Unit,
    viewModel: ContentFilterSettingsViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var editingRule by remember { mutableStateOf<EditingRule?>(null) }

    val editing = editingRule
    if (editing != null) {
        RuleEditScreen(
            editing = editing,
            onCancel = { editingRule = null },
            onSave = { saved ->
                viewModel.upsertCustomRule(saved)
                editingRule = null
            },
        )
        return
    }

    ContentFilterListScreen(
        rules = rules,
        onBack = onBack,
        onToggle = viewModel::setRuleEnabled,
        onAdd = { editingRule = EditingRule.new() },
        onEdit = { editingRule = EditingRule.from(it) },
        onDelete = viewModel::deleteCustomRule,
    )
}

// MARK: - 列表

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentFilterListScreen(
    rules: List<ContentFilterRule>,
    onBack: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ContentFilterRule) -> Unit,
    onDelete: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.content_filter_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding() // C4：自定义正则增改时键盘弹起可滚到键盘上方
                .verticalScroll(scrollState)
                .contentMaxWidth(),
        ) {
            Spacer(Modifier.height(8.dp))

            val presets = rules.filter { it.isPreset }
            SettingsSection(
                title = stringResource(R.string.content_filter_section_presets),
                footer = stringResource(R.string.content_filter_presets_footer),
            ) {
                if (presets.isEmpty()) {
                    EmptyRulesRow(stringResource(R.string.content_filter_empty_presets))
                } else {
                    presets.forEach { rule ->
                        SettingsSwitchRow(
                            title = rule.name,
                            subtitle = ContentFilterService.presetDescription(rule.id),
                            checked = rule.isEnabled,
                            onCheckedChange = { onToggle(rule.id, it) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            val customs = rules.filter { !it.isPreset }
            SettingsSection(
                title = stringResource(R.string.content_filter_section_custom),
                footer = stringResource(R.string.content_filter_custom_footer),
            ) {
                if (customs.isEmpty()) {
                    EmptyRulesRow(stringResource(R.string.content_filter_empty_custom))
                } else {
                    customs.forEach { rule ->
                        CustomRuleRow(
                            rule = rule,
                            onToggle = { onToggle(rule.id, it) },
                            onEdit = { onEdit(rule) },
                            onDelete = { onDelete(rule.id) },
                        )
                    }
                }
                AppSettingsRow(
                    title = stringResource(R.string.content_filter_add_custom),
                    icon = Icons.Filled.Add,
                    onClick = onAdd,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRuleRow(
    rule: ContentFilterRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = rule.name.ifEmpty { stringResource(R.string.content_filter_unnamed_rule) }
    val subtitle = if (rule.mode == FilterMode.REPLACE && rule.replacement.isNotEmpty()) {
        stringResource(R.string.content_filter_custom_subtitle_replace, rule.pattern, rule.replacement)
    } else {
        rule.pattern
    }
    // TODO(图纸未覆盖): 本行的副标题是**等宽字体**（正则原文，靠 Monospace 才看得清），AppSettingsRow 的副
    //  走 settingsRowSubtitle 单一字阶、无字体槽；尾槽还是「开关 + 编辑 + 删除」三件套。按 §4.8「结构不符
    //  的登记不硬套、也不给组件加参数」停手（施工日志 D-12）。
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSwitch(checked = rule.isEnabled, onCheckedChange = onToggle)
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.content_filter_edit_rule),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.content_filter_delete_rule),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmptyRulesRow(title: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.content_filter_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - 规则编辑

/** 编辑态（自定义规则草稿）。预设规则不走编辑屏（仅开关），故这里恒为自定义。 */
private data class EditingRule(
    val id: String,
    val isNew: Boolean,
    val isEnabled: Boolean,
    val name: String,
    val pattern: String,
    val mode: FilterMode,
    val replacement: String,
) {
    fun toRule() = ContentFilterRule(
        id = id,
        name = name,
        pattern = pattern,
        isEnabled = isEnabled,
        isPreset = false,
        mode = mode,
        replacement = replacement,
    )

    companion object {
        fun new() = EditingRule(
            id = UUID.randomUUID().toString(),
            isNew = true,
            isEnabled = true, // 新规则默认启用（对齐 iOS isAddingRule sheet 初值）
            name = "",
            pattern = "",
            mode = FilterMode.REMOVE,
            replacement = "",
        )

        fun from(rule: ContentFilterRule) = EditingRule(
            id = rule.id,
            isNew = false,
            isEnabled = rule.isEnabled,
            name = rule.name,
            pattern = rule.pattern,
            mode = rule.mode,
            replacement = rule.replacement,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditScreen(
    editing: EditingRule,
    onCancel: () -> Unit,
    onSave: (ContentFilterRule) -> Unit,
) {
    var name by remember { mutableStateOf(editing.name) }
    var pattern by remember { mutableStateOf(editing.pattern) }
    var mode by remember { mutableStateOf(editing.mode) }
    var replacement by remember { mutableStateOf(editing.replacement) }
    var testInput by remember { mutableStateOf("") }
    // 测试结果：未跑=null；跑过则 result=testFilter 输出（null=正则非法 / ""=完全过滤 / 文字=结果）。
    var testRan by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    // pattern 空 → 视为有效（不显红字）；否则编译校验（1:1 iOS validatePattern）。
    val patternValid = pattern.isEmpty() || ContentFilterService.isValidRegex(pattern)
    val canSave = pattern.isNotEmpty() && patternValid

    val editorScrollState = rememberScrollState()

    BackHandler(onBack = onCancel)

    Scaffold(
        topBar = {
            AppFormBar(
                title = stringResource(
                    if (editing.isNew) R.string.content_filter_add_title else R.string.content_filter_edit_title,
                ),
                lifted = editorScrollState.value > 0,
                onCancel = onCancel,
                cancelText = stringResource(R.string.content_filter_action_cancel),
                trailing = {
                    AppButton(
                        onClick = {
                            onSave(
                                editing.copy(
                                    name = name,
                                    pattern = pattern,
                                    mode = mode,
                                    replacement = replacement,
                                ).toRule(),
                            )
                        },
                        enabled = canSave,
                    ) {
                        Text(stringResource(R.string.content_filter_action_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding() // C4：规则编辑屏（名/正则/替换多输入）键盘弹起可滚到键盘上方
                .verticalScroll(editorScrollState)
                .contentMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // 基本信息
            SectionLabel(stringResource(R.string.content_filter_section_basic))
            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.content_filter_field_name),
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = stringResource(R.string.content_filter_field_pattern),
                isError = !patternValid,
                supportingText = if (!patternValid) stringResource(R.string.content_filter_invalid_pattern) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )

            // 过滤方式
            SectionLabel(stringResource(R.string.content_filter_section_mode))
            AppSegmentedControl(
                options = listOf(FilterMode.REMOVE, FilterMode.REPLACE),
                selected = mode,
                onSelect = { mode = it },
                modifier = Modifier.fillMaxWidth(),
                label = { stringResource(if (it == FilterMode.REMOVE) R.string.content_filter_mode_remove else R.string.content_filter_mode_replace) },
            )
            if (mode == FilterMode.REPLACE) {
                AppTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = stringResource(R.string.content_filter_field_replacement),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                stringResource(
                    if (mode == FilterMode.REMOVE) R.string.content_filter_mode_remove_footer
                    else R.string.content_filter_mode_replace_footer,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 测试
            SectionLabel(stringResource(R.string.content_filter_section_test))
            Text(
                stringResource(R.string.content_filter_test_input_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppTextArea(
                value = testInput,
                onValueChange = { testInput = it },
                minHeight = 96.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                onClick = {
                    testResult = ContentFilterService.testFilter(testInput, pattern, mode, replacement)
                    testRan = true
                },
                style = AppButtonStyle.Primary,
                enabled = pattern.isNotEmpty() && patternValid && testInput.isNotEmpty(),
            ) {
                Text(stringResource(R.string.content_filter_test_run))
            }
            if (testRan) {
                AppListDivider(startInset = 0.dp)
                Text(
                    stringResource(R.string.content_filter_test_result_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val result = testResult
                val shown = when {
                    result == null -> stringResource(R.string.content_filter_test_invalid)
                    result.isEmpty() -> stringResource(R.string.content_filter_test_fully_filtered)
                    else -> result
                }
                Text(
                    shown,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
