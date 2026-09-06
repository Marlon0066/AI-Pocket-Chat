package com.situ.aichat.ui.liuli.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.diary.DiaryPromptSettingsViewModel
import com.situ.aichat.ui.diary.DiaryRuleForm
import com.situ.aichat.ui.diary.DiaryRuleSection
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliInputRow
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed

/** 两枚文本域的最小高（逐字照暖陶 74 / 104）与组内块间缝。 */
private val STYLE_MIN_HEIGHT = 74.dp
private val EXTRA_MIN_HEIGHT = 104.dp
private val BLOCK_GAP = 12.dp

/** 一个分区的写口（两分区结构完全相同·section 由调用方钉死）。 */
@Immutable
internal data class LiuliDiaryRuleCallbacks(
    val onWordCountDrag: (DiaryRuleSection, Int) -> Unit,
    val onCommitWordCount: (DiaryRuleSection) -> Unit,
    val onSetWordCount: (DiaryRuleSection, Int) -> Unit,
    val onNarrativePersonChange: (DiaryRuleSection, String) -> Unit,
    val onStyleHintChange: (DiaryRuleSection, String) -> Unit,
    val onExtraRulesChange: (DiaryRuleSection, String) -> Unit,
    val onResetSection: (DiaryRuleSection) -> Unit,
)

/**
 * 日记写作规则页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 15·**T2 逐字即写页 → 无保存栏**·A-9）。
 * 与暖陶 `DiaryPromptSettingsScreen` 共用 [DiaryPromptSettingsViewModel]。
 *
 * **prompt 耦合**：三个文本项**空 = 用默认文案**——换脸只把空态渲染成 placeholder 灰字，
 * 不引入任何别的语义（勘察表点名的那一条）。篇幅滑杆的 `steps = 16` 与「拖动 → 松手 commit」时序逐字继承。
 */
@Composable
fun LiuliDiaryPromptSettingsScreen(
    onBack: () -> Unit,
    onOpenPreviewMine: () -> Unit,
    onOpenPreviewExchange: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiaryPromptSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiuliDiaryPromptSettingsContent(
        mine = state.mine,
        exchange = state.exchange,
        callbacks = LiuliDiaryRuleCallbacks(
            onWordCountDrag = viewModel::onWordCountDrag,
            onCommitWordCount = viewModel::commitWordCount,
            onSetWordCount = viewModel::setWordCount,
            onNarrativePersonChange = viewModel::onNarrativePersonChange,
            onStyleHintChange = viewModel::onStyleHintChange,
            onExtraRulesChange = viewModel::onExtraRulesChange,
            onResetSection = viewModel::resetSection,
        ),
        onOpenPreviewMine = onOpenPreviewMine,
        onOpenPreviewExchange = onOpenPreviewExchange,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 写作规则页内容层（纯参数·可测）。 */
@Composable
internal fun LiuliDiaryPromptSettingsContent(
    mine: DiaryRuleForm,
    exchange: DiaryRuleForm,
    callbacks: LiuliDiaryRuleCallbacks,
    onOpenPreviewMine: () -> Unit,
    onOpenPreviewExchange: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.diary_rules_title)
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
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
                    ruleGroup(
                        title = stringResource(R.string.diary_rules_section_mine),
                        footer = stringResource(R.string.diary_rules_footer_mine),
                        extraHint = stringResource(R.string.diary_rules_extra_hint_mine),
                        form = mine,
                        section = DiaryRuleSection.MINE,
                        callbacks = callbacks,
                    )
                    ruleGroup(
                        title = stringResource(R.string.diary_rules_section_exchange),
                        footer = stringResource(R.string.diary_rules_footer_exchange),
                        extraHint = stringResource(R.string.diary_rules_extra_hint_exchange),
                        form = exchange,
                        section = DiaryRuleSection.EXCHANGE,
                        callbacks = callbacks,
                    )
                    LiuliGroup(
                        header = stringResource(R.string.diary_rules_preview_header),
                        footer = stringResource(R.string.diary_rules_preview_footer),
                    ) {
                        LiuliNavRow(
                            title = stringResource(R.string.diary_rules_preview_mine),
                            onClick = onOpenPreviewMine,
                            divider = false,
                        )
                        LiuliNavRow(
                            title = stringResource(R.string.diary_rules_preview_exchange),
                            onClick = onOpenPreviewExchange,
                        )
                    }
                }
            }
        }
    }
}

/** 一个分区：篇幅滑杆 + 人称 + 文风 + 补充规则 + 恢复默认（两分区结构完全相同·逐字照暖陶 `RuleSection`）。 */
@Composable
private fun ColumnScope.ruleGroup(
    title: String,
    footer: String,
    extraHint: String,
    form: DiaryRuleForm,
    section: DiaryRuleSection,
    callbacks: LiuliDiaryRuleCallbacks,
) {
    LiuliGroup(header = title, footer = footer) {
        LiuliSliderRow(
            title = stringResource(R.string.diary_rules_length),
            valueLabel = stringResource(R.string.diary_rules_length_value, form.wordCount),
            value = form.wordCount.toFloat(),
            valueRange = 300f..2000f,
            // (2000-300)/100 = 17 个区间 ⇒ 中间停靠点 16 个（端点不计入 steps）。
            steps = 16,
            divider = false,
            onManualInput = { callbacks.onSetWordCount(section, it) },
            onValueChangeFinished = { callbacks.onCommitWordCount(section) },
            onValueChange = { callbacks.onWordCountDrag(section, it.toInt()) },
        )
        LiuliInputRow(
            label = stringResource(R.string.diary_rules_person),
            value = form.narrativePerson,
            onValueChange = { callbacks.onNarrativePersonChange(section, it) },
        )
        RuleTextBlock(
            label = stringResource(R.string.diary_rules_style),
            value = form.styleHint,
            onValueChange = { callbacks.onStyleHintChange(section, it) },
            minHeight = STYLE_MIN_HEIGHT,
        )
        RuleTextBlock(
            label = stringResource(R.string.diary_rules_extra),
            value = form.extraRules,
            onValueChange = { callbacks.onExtraRulesChange(section, it) },
            minHeight = EXTRA_MIN_HEIGHT,
            placeholder = extraHint,
        )
        LiuliRowBase(verticalPadding = LiuliPageGeometry.rowTwoLinePad) {
            LiuliButton(onClick = { callbacks.onResetSection(section) }, style = LiuliButtonStyle.Text, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(stringResource(R.string.diary_rules_reset))
            }
        }
    }
}

/** 一块多行规则：标签 + 整块文本域（A-4 ⑤「标签作组标题」在这里退化成块内小标——一组里有四块，标题不能都归组）。 */
@Composable
private fun RuleTextBlock(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    minHeight: Dp,
    placeholder: String? = null,
) {
    LiuliRowBase(verticalPadding = LiuliPageGeometry.rowTwoLinePad, verticalAlignment = Alignment.Top) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
            Text(label, style = AppTypography.secondary, color = AppTheme.colors.text.secondary)
            LiuliField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                singleLine = false,
                minHeight = minHeight,
            )
        }
    }
}
