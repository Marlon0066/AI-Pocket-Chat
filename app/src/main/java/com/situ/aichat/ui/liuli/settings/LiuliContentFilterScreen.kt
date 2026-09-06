package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.content.ContentFilterRule
import com.situ.aichat.content.ContentFilterService
import com.situ.aichat.content.FilterMode
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette
import com.situ.aichat.ui.liuli.designsystem.LiuliSwitch
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.ContentFilterSettingsViewModel

/** 行内两枚动作圆钮的视觉直径（同步进钮 28）与它们之间的缝。 */
private val ACTION_BUTTON = LiuliPageGeometry.stepperButton
private val ACTION_ICON = LiuliPageGeometry.stepperIcon
private val ACTION_GAP = 8.dp

/**
 * 内容过滤设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 5·列表态）。与暖陶
 * `ContentFilterSettingsScreen` 共用 [ContentFilterSettingsViewModel]，并沿用它的
 * **同屏全屏编辑态**：`editingRule != null` 时整屏换成 [LiuliContentFilterEditScreen] 并 `return`
 * （F9「同文件子屏」·不进 `AIChatApp.kt`）。
 */
@Composable
fun LiuliContentFilterScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContentFilterSettingsViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var editingRule by remember { mutableStateOf<LiuliEditingRule?>(null) }

    val editing = editingRule
    if (editing != null) {
        LiuliContentFilterEditScreen(
            editing = editing,
            onCancel = { editingRule = null },
            onSave = { saved ->
                viewModel.upsertCustomRule(saved)
                editingRule = null
            },
            modifier = modifier,
        )
        return
    }

    LiuliContentFilterListContent(
        rules = rules,
        onToggle = viewModel::setRuleEnabled,
        onAdd = { editingRule = LiuliEditingRule.new() },
        onEdit = { editingRule = LiuliEditingRule.from(it) },
        onDelete = viewModel::deleteCustomRule,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 列表态内容层（纯参数·可测）。分组 / 条件 / 文案逐字继承暖陶 `ContentFilterListScreen`。 */
@Composable
internal fun LiuliContentFilterListContent(
    rules: List<ContentFilterRule>,
    onToggle: (String, Boolean) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ContentFilterRule) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.content_filter_title)
    val presets = rules.filter { it.isPreset }
    val customs = rules.filter { !it.isPreset }
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            // C4：自定义正则增改时键盘弹起可滚到键盘上方（逐字照暖陶）。
            modifier = Modifier.fillMaxSize().imePadding().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    LiuliGroup(
                        header = stringResource(R.string.content_filter_section_presets),
                        footer = stringResource(R.string.content_filter_presets_footer),
                    ) {
                        if (presets.isEmpty()) {
                            EmptyRulesRow(stringResource(R.string.content_filter_empty_presets))
                        } else {
                            presets.forEachIndexed { index, rule ->
                                LiuliToggleRow(
                                    title = rule.name,
                                    subtitle = ContentFilterService.presetDescription(rule.id),
                                    checked = rule.isEnabled,
                                    onCheckedChange = { onToggle(rule.id, it) },
                                    divider = index > 0,
                                )
                            }
                        }
                    }
                    LiuliGroup(
                        header = stringResource(R.string.content_filter_section_custom),
                        footer = stringResource(R.string.content_filter_custom_footer),
                    ) {
                        if (customs.isEmpty()) {
                            EmptyRulesRow(stringResource(R.string.content_filter_empty_custom))
                        } else {
                            customs.forEachIndexed { index, rule ->
                                CustomRuleRow(
                                    rule = rule,
                                    onToggle = { onToggle(rule.id, it) },
                                    onEdit = { onEdit(rule) },
                                    onDelete = { onDelete(rule.id) },
                                    divider = index > 0,
                                )
                            }
                        }
                        LiuliNavRow(
                            title = stringResource(R.string.content_filter_add_custom),
                            onClick = onAdd,
                            icon = Icons.Filled.Add,
                            tileColor = LiuliPalette.tileChat,
                            // 空态时它是组里唯一一行；有规则时它排在规则之后，仍要一道发丝分开。
                            divider = customs.isNotEmpty(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一条自定义规则：标题 + **等宽**副标（正则原文·A-4 ⑨）+ 尾随三件（开关 / 编辑 / 删除）。
 *
 * 整行**不可点**（三件各自可点·点行没有第四种含义），故开关这里是真开关不是纯视觉——
 * 「整行可点的行里控件必须纯视觉」那条只管整行有点击面的行（卷四 R1 ②）。
 */
@Composable
private fun CustomRuleRow(
    rule: ContentFilterRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    divider: Boolean,
) {
    val colors = AppTheme.colors
    val title = rule.name.ifEmpty { stringResource(R.string.content_filter_unnamed_rule) }
    val subtitle = if (rule.mode == FilterMode.REPLACE && rule.replacement.isNotEmpty()) {
        stringResource(R.string.content_filter_custom_subtitle_replace, rule.pattern, rule.replacement)
    } else {
        rule.pattern
    }
    LiuliRowBase(
        minHeight = LiuliPageGeometry.rowTwoLine,
        verticalPadding = LiuliPageGeometry.rowTwoLinePad,
        divider = divider,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.body, color = colors.text.primary, maxLines = 1)
            Text(
                subtitle,
                // 正则原文靠等宽才看得清（暖陶 :193–195 留过 TODO·kit 补齐后在此落地）。
                style = AppTypography.secondary.copy(fontFamily = FontFamily.Monospace),
                color = colors.text.secondary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(ACTION_GAP))
        LiuliSwitch(checked = rule.isEnabled, onCheckedChange = onToggle)
        Spacer(Modifier.width(ACTION_GAP))
        RowActionButton(
            icon = Icons.Filled.Edit,
            contentDescription = stringResource(R.string.content_filter_edit_rule),
            onClick = onEdit,
        )
        Spacer(Modifier.width(ACTION_GAP))
        RowActionButton(
            icon = Icons.Filled.Delete,
            contentDescription = stringResource(R.string.content_filter_delete_rule),
            tint = colors.status.onError,
            onClick = onDelete,
        )
    }
}

/** 行尾一枚 28 圆钮（版位恰 28·48 触达居中外溢·同 `LiuliStepperRow` 的做法）。 */
@Composable
private fun RowActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    Box(Modifier.size(ACTION_BUTTON), contentAlignment = Alignment.Center) {
        LiuliCircleButton(onClick = onClick, contentDescription = contentDescription, size = ACTION_BUTTON) {
            if (tint != null) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(ACTION_ICON))
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(ACTION_ICON))
            }
        }
    }
}

/** 空态行：一句「还没有规则」+ 一句引导（逐字照暖陶 `EmptyRulesRow`）。 */
@Composable
private fun EmptyRulesRow(title: String) {
    val colors = AppTheme.colors
    LiuliRowBase(divider = false, verticalPadding = LiuliPageGeometry.rowTwoLinePad, verticalAlignment = Alignment.Top) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = AppTypography.body, color = colors.text.secondary)
            Text(
                stringResource(R.string.content_filter_empty_hint),
                style = AppTypography.secondary,
                color = colors.text.tertiary,
            )
        }
    }
}
