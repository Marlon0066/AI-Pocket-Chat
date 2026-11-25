package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTypography

/**
 * 暖陶「记忆提示词」独立页（设置全面重构卷二）：
 * 从日常「记忆」页拆出，收进「高级 → 记忆提示词」，只给需要微调提示词模板的用户使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryPromptsSettingsScreen(onBack: () -> Unit) {
    val scrollState = rememberScrollState()
    val lifted by remember { derivedStateOf { scrollState.value > 0 } }
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_adv_memory_prompts_title),
                onBack = onBack,
                lifted = lifted,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            MemoryPromptsSections()
        }
    }
}

/** 记忆提示词内容段：提取提示词 + 注入提示词 + 恢复默认 + 宏说明（九个宏字面量与解析格式耦合，零碰）。 */
@Composable
fun MemoryPromptsSections(viewModel: MemoryPromptsSettingsViewModel = hiltViewModel()) {
    val extraction by viewModel.extraction.collectAsStateWithLifecycle()
    val injection by viewModel.injection.collectAsStateWithLifecycle()

    Column {
        SettingsSection(
            title = stringResource(R.string.mem_prompts_extraction_title),
            footer = stringResource(R.string.mem_prompts_extraction_macros),
        ) {
            CaptionText(stringResource(R.string.mem_prompts_extraction_desc))
            PromptEditor(
                value = extraction,
                onValueChange = viewModel::onExtractionChange,
                minHeight = 260.dp,
            )
            ResetButton(onClick = viewModel::resetExtraction)
        }

        SettingsSection(
            title = stringResource(R.string.mem_prompts_injection_title),
            footer = stringResource(R.string.mem_prompts_injection_macros),
        ) {
            CaptionText(stringResource(R.string.mem_prompts_injection_desc))
            PromptEditor(
                value = injection,
                onValueChange = viewModel::onInjectionChange,
                minHeight = 140.dp,
            )
            ResetButton(onClick = viewModel::resetInjection)
        }

        SettingsSection(title = stringResource(R.string.mem_prompts_macros_title)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MacroRow("{{聊天记录}}", stringResource(R.string.mem_prompts_macro_chatlog))
                MacroRow("{{已有记忆}}", stringResource(R.string.mem_prompts_macro_existing))
                MacroRow("{{当前时间}}", stringResource(R.string.mem_prompts_macro_now))
                MacroRow("{{最大字数}}", stringResource(R.string.mem_prompts_macro_maxchars))
                MacroRow("{{当前字数}}", stringResource(R.string.mem_prompts_macro_curchars))
                MacroRow("{{压缩策略}}", stringResource(R.string.mem_prompts_macro_compress))
                MacroRow("{{记忆内容}}", stringResource(R.string.mem_prompts_macro_content))
                MacroRow("{{char}}", stringResource(R.string.mem_prompts_macro_char))
                MacroRow("{{user}}", stringResource(R.string.mem_prompts_macro_user))
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CaptionText(text: String) {
    Text(
        text = text,
        style = AppTypography.settingsRowSubtitle,
        color = AppTheme.colors.text.secondary,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun PromptEditor(value: String, onValueChange: (String) -> Unit, minHeight: Dp) {
    AppTextArea(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        minHeight = minHeight,
        highlightMacros = true,
    )
}

@Composable
private fun ResetButton(onClick: () -> Unit) {
    AppButton(onClick = onClick, style = AppButtonStyle.Text, modifier = Modifier.padding(horizontal = 8.dp)) {
        Icon(AppActionIcons.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.mem_prompts_reset_default))
    }
}

@Composable
private fun MacroRow(macro: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = macro,
            style = AppTypography.settingsRowSubtitle,
            color = AppTheme.colors.accent.text,
        )
        Text(
            text = description,
            style = AppTypography.settingsRowSubtitle,
            color = AppTheme.colors.text.secondary,
        )
    }
}
