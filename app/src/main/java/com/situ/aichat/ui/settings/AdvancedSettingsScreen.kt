package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.ui.components.SettingsInfoNote
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 暖陶「高级」页（设置全面重构卷一 D-6：取代原主页底部高级功能开关）。
 * 收拢：提示词模块 / 记忆提示词 / 内容过滤 / 上下文日志 / 性能采集 / 深层记忆状态。
 */
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    onOpenPromptModules: () -> Unit,
    onOpenMemoryPrompts: () -> Unit,
    onOpenContentFilter: () -> Unit,
    onOpenContextLog: () -> Unit,
    onOpenPerfCollect: () -> Unit,
    overviewViewModel: SettingsOverviewViewModel = hiltViewModel(),
) {
    val embedderLoadState by overviewViewModel.embedderLoadState.collectAsStateWithLifecycle()
    AdvancedSettingsContent(
        embedderLoadState = embedderLoadState,
        onBack = onBack,
        onOpenPromptModules = onOpenPromptModules,
        onOpenMemoryPrompts = onOpenMemoryPrompts,
        onOpenContentFilter = onOpenContentFilter,
        onOpenContextLog = onOpenContextLog,
        onOpenPerfCollect = onOpenPerfCollect,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedSettingsContent(
    embedderLoadState: TextEmbedder.LoadState,
    onBack: () -> Unit,
    onOpenPromptModules: () -> Unit,
    onOpenMemoryPrompts: () -> Unit,
    onOpenContentFilter: () -> Unit,
    onOpenContextLog: () -> Unit,
    onOpenPerfCollect: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val lifted by remember { derivedStateOf { scrollState.value > 0 } }
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_hub_advanced),
                onBack = onBack,
                lifted = lifted,
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SettingsInfoNote(
                text = stringResource(R.string.settings_adv_info),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            SettingsGroupCard(stringResource(R.string.settings_adv_group_prompts)) {
                SettingsRow(
                    icon = Icons.Filled.Extension,
                    title = stringResource(R.string.pm_title),
                    subtitle = stringResource(R.string.settings_adv_prompt_modules_sub),
                    onClick = onOpenPromptModules,
                )
                SettingsRow(
                    icon = Icons.Filled.EditNote,
                    title = stringResource(R.string.settings_adv_memory_prompts_title),
                    subtitle = stringResource(R.string.settings_adv_memory_prompts_sub),
                    onClick = onOpenMemoryPrompts,
                )
            }

            SettingsGroupCard(stringResource(R.string.settings_adv_group_reply)) {
                SettingsRow(
                    icon = Icons.Filled.FilterAlt,
                    title = stringResource(R.string.content_filter_title),
                    subtitle = stringResource(R.string.settings_adv_content_filter_sub),
                    onClick = onOpenContentFilter,
                )
            }

            SettingsGroupCard(stringResource(R.string.settings_adv_group_diagnostics)) {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    title = stringResource(R.string.settings_context_log_title),
                    subtitle = stringResource(R.string.settings_adv_context_log_sub),
                    onClick = onOpenContextLog,
                )
                SettingsRow(
                    icon = Icons.Filled.Speed,
                    title = stringResource(R.string.perf_title),
                    subtitle = stringResource(R.string.settings_adv_perf_sub),
                    onClick = onOpenPerfCollect,
                )
                EmbedderStatusRow(embedderLoadState)
            }
        }
    }
}
