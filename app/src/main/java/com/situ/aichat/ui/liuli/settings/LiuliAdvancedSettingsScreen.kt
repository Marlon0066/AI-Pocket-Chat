package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.liuli.designsystem.LiuliTileTone
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliInfoNote
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliStatusDotRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.SettingsOverviewViewModel
import com.situ.aichat.ui.settings.dotColorFor

/** 琉璃 2.0「高级」页（设置全面重构卷一 D-6）。 */
@Composable
fun LiuliAdvancedSettingsScreen(
    onBack: () -> Unit,
    onOpenPromptModules: () -> Unit,
    onOpenMemoryPrompts: () -> Unit,
    onOpenContentFilter: () -> Unit,
    onOpenContextLog: () -> Unit,
    onOpenPerfCollect: () -> Unit,
    modifier: Modifier = Modifier,
    overviewViewModel: SettingsOverviewViewModel = hiltViewModel(),
) {
    val embedderLoadState by overviewViewModel.embedderLoadState.collectAsStateWithLifecycle()
    LiuliAdvancedSettingsContent(
        embedderLoadState = embedderLoadState,
        onBack = onBack,
        onOpenPromptModules = onOpenPromptModules,
        onOpenMemoryPrompts = onOpenMemoryPrompts,
        onOpenContentFilter = onOpenContentFilter,
        onOpenContextLog = onOpenContextLog,
        onOpenPerfCollect = onOpenPerfCollect,
        modifier = modifier,
    )
}

@Composable
internal fun LiuliAdvancedSettingsContent(
    embedderLoadState: TextEmbedder.LoadState,
    onBack: () -> Unit,
    onOpenPromptModules: () -> Unit,
    onOpenMemoryPrompts: () -> Unit,
    onOpenContentFilter: () -> Unit,
    onOpenContextLog: () -> Unit,
    onOpenPerfCollect: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.settings_hub_advanced)
    val bottomInset = LiuliPageGeometry.pageBottom +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusWord = stringResource(
        when (embedderLoadState) {
            TextEmbedder.LoadState.LOADED -> R.string.embedder_status_ready
            TextEmbedder.LoadState.NOT_ATTEMPTED -> R.string.embedder_status_idle
            TextEmbedder.LoadState.FAILED -> R.string.embedder_status_unavailable
        },
    )
    val hint = stringResource(
        when (embedderLoadState) {
            TextEmbedder.LoadState.LOADED -> R.string.embedder_status_hint_ready
            TextEmbedder.LoadState.NOT_ATTEMPTED -> R.string.embedder_status_hint_idle
            TextEmbedder.LoadState.FAILED -> R.string.embedder_status_hint_unavailable
        },
    )
    val dot = dotColorFor(embedderLoadState, AppTheme.colors)

    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            item(key = "body") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    LiuliInfoNote(
                        text = stringResource(R.string.settings_adv_info),
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    LiuliGroup(header = stringResource(R.string.settings_adv_group_prompts)) {
                        LiuliNavRow(
                            title = stringResource(R.string.pm_title),
                            subtitle = stringResource(R.string.settings_adv_prompt_modules_sub),
                            onClick = onOpenPromptModules,
                            icon = Icons.Filled.Extension,
                            tileColor = LiuliTileTone.Lilac,
                            divider = false,
                        )
                        LiuliNavRow(
                            title = stringResource(R.string.settings_adv_memory_prompts_title),
                            subtitle = stringResource(R.string.settings_adv_memory_prompts_sub),
                            onClick = onOpenMemoryPrompts,
                            icon = Icons.Filled.EditNote,
                            tileColor = LiuliTileTone.Lilac,
                        )
                    }

                    LiuliGroup(header = stringResource(R.string.settings_adv_group_reply)) {
                        LiuliNavRow(
                            title = stringResource(R.string.content_filter_title),
                            subtitle = stringResource(R.string.settings_adv_content_filter_sub),
                            onClick = onOpenContentFilter,
                            icon = Icons.Filled.FilterAlt,
                            tileColor = LiuliTileTone.Sky,
                            divider = false,
                        )
                    }

                    LiuliGroup(header = stringResource(R.string.settings_adv_group_diagnostics)) {
                        LiuliNavRow(
                            title = stringResource(R.string.settings_context_log_title),
                            subtitle = stringResource(R.string.settings_adv_context_log_sub),
                            onClick = onOpenContextLog,
                            icon = Icons.AutoMirrored.Filled.ReceiptLong,
                            tileColor = LiuliTileTone.Mint,
                            divider = false,
                        )
                        LiuliNavRow(
                            title = stringResource(R.string.perf_title),
                            subtitle = stringResource(R.string.settings_adv_perf_sub),
                            onClick = onOpenPerfCollect,
                            icon = Icons.Filled.Speed,
                            tileColor = LiuliTileTone.Mint,
                        )
                        LiuliStatusDotRow(
                            title = stringResource(R.string.embedder_status_title),
                            status = statusWord,
                            dotColor = dot,
                            subtitle = hint,
                        )
                    }
                }
            }
        }
    }
}
