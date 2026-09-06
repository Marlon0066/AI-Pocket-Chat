package com.situ.aichat.ui.diary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSliderRow
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppSettingsRow
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 日记写作规则（2026-09-05·图纸 §4.2·已过审）：上下两分区「我的日记」/「TA 的信」，各四项
 * （篇幅滑块 / 人称 / 文风 / 我再补几条）+ 一个「恢复默认」；第三分区是两个只读预览入口。
 *
 * 三个文本项**空 = 用默认文案**（框里播种的就是默认文案原文，改回默认即存空）；篇幅是数值，
 * 拖动中只更本地态、松手才落盘（图纸 J-8）。
 */
@Composable
fun DiaryPromptSettingsScreen(
    onBack: () -> Unit,
    onOpenPreviewMine: () -> Unit,
    onOpenPreviewExchange: () -> Unit,
    viewModel: DiaryPromptSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.diary_rules_title),
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
            RuleSection(
                title = stringResource(R.string.diary_rules_section_mine),
                footer = stringResource(R.string.diary_rules_footer_mine),
                extraHint = stringResource(R.string.diary_rules_extra_hint_mine),
                form = state.mine,
                section = DiaryRuleSection.MINE,
                viewModel = viewModel,
            )
            RuleSection(
                title = stringResource(R.string.diary_rules_section_exchange),
                footer = stringResource(R.string.diary_rules_footer_exchange),
                extraHint = stringResource(R.string.diary_rules_extra_hint_exchange),
                form = state.exchange,
                section = DiaryRuleSection.EXCHANGE,
                viewModel = viewModel,
            )
            SettingsSection(
                title = stringResource(R.string.diary_rules_preview_header),
                footer = stringResource(R.string.diary_rules_preview_footer),
            ) {
                AppSettingsRow(
                    title = stringResource(R.string.diary_rules_preview_mine),
                    showChevron = true,
                    onClick = onOpenPreviewMine,
                )
                AppSettingsRow(
                    title = stringResource(R.string.diary_rules_preview_exchange),
                    showChevron = true,
                    onClick = onOpenPreviewExchange,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 一个分区：篇幅滑块 + 人称 + 文风 + 补充规则 + 恢复默认（两分区结构完全相同）。 */
@Composable
private fun RuleSection(
    title: String,
    footer: String,
    extraHint: String,
    form: DiaryRuleForm,
    section: DiaryRuleSection,
    viewModel: DiaryPromptSettingsViewModel,
) {
    SettingsSection(title = title, footer = footer) {
        SettingsSliderRow(
            label = stringResource(R.string.diary_rules_length),
            valueLabel = stringResource(R.string.diary_rules_length_value, form.wordCount),
            value = form.wordCount.toFloat(),
            valueRange = 300f..2000f,
            // (2000-300)/100 = 17 个区间 ⇒ 中间停靠点 16 个（端点不计入 steps）。
            steps = 16,
            onManualInput = { viewModel.setWordCount(section, it) },
            onValueChangeFinished = { viewModel.commitWordCount(section) },
            onValueChange = { viewModel.onWordCountDrag(section, it.toInt()) },
        )
        FieldLabel(stringResource(R.string.diary_rules_person))
        AppTextField(
            value = form.narrativePerson,
            onValueChange = { viewModel.onNarrativePersonChange(section, it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
        )
        FieldLabel(stringResource(R.string.diary_rules_style))
        RuleTextArea(
            value = form.styleHint,
            onValueChange = { viewModel.onStyleHintChange(section, it) },
            minHeight = 74.dp,
        )
        FieldLabel(stringResource(R.string.diary_rules_extra))
        RuleTextArea(
            value = form.extraRules,
            onValueChange = { viewModel.onExtraRulesChange(section, it) },
            minHeight = 104.dp,
            placeholder = extraHint,
        )
        AppButton(
            onClick = { viewModel.resetSection(section) },
            style = AppButtonStyle.Text,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.diary_rules_reset))
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = AppTheme.typography.secondary,
        color = AppTheme.colors.text.secondary,
        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun RuleTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    minHeight: Dp,
    placeholder: String? = null,
) {
    AppTextArea(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = placeholder,
        minHeight = minHeight,
    )
}
