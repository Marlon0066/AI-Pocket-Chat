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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.MemoryPromptsSettingsViewModel
import com.situ.aichat.ui.settings.MemorySettingsViewModel

/**
 * 记忆 hub（琉璃·图纸 2026-09-06 卷五 §4.1 屏 1）。与暖陶 [com.situ.aichat.ui.settings.MemoryHubScreen]
 * 同一副大脑：**两节各自订阅各自的 VM**（参数段 [MemorySettingsViewModel] / 提示词段
 * [MemoryPromptsSettingsViewModel]），一页上下堆叠，挂原路由 `memorySettings`。
 */
@Composable
fun LiuliMemoryHubScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    memoryViewModel: MemorySettingsViewModel = hiltViewModel(),
    promptsViewModel: MemoryPromptsSettingsViewModel = hiltViewModel(),
) {
    val settings by memoryViewModel.state.collectAsStateWithLifecycle()
    val extraction by promptsViewModel.extraction.collectAsStateWithLifecycle()
    val injection by promptsViewModel.injection.collectAsStateWithLifecycle()

    LiuliMemoryHubContent(
        settings = settings,
        extraction = extraction,
        injection = injection,
        memoryCallbacks = LiuliMemoryCallbacks(
            onSetShortTermLength = memoryViewModel::setShortTermMemoryLength,
            onSetAutoSummarizeInterval = memoryViewModel::setAutoSummarizeInterval,
            onSetCooldownMinutes = memoryViewModel::setMemorySummaryCooldownMinutes,
            onSetSummaryMaxLength = memoryViewModel::setMemorySummaryMaxLength,
            onSetProgressive = memoryViewModel::setProgressiveCompressionEnabled,
            onSetStructuredInterval = memoryViewModel::setStructuredMemoryInterval,
            onSetVectorThreshold = memoryViewModel::setVectorSearchThreshold,
        ),
        promptCallbacks = LiuliMemoryPromptCallbacks(
            onExtractionChange = promptsViewModel::onExtractionChange,
            onInjectionChange = promptsViewModel::onInjectionChange,
            onResetExtraction = promptsViewModel::resetExtraction,
            onResetInjection = promptsViewModel::resetInjection,
        ),
        onBack = onBack,
        modifier = modifier,
    )
}

/** 记忆 hub 内容层（纯参数·可测）。两段的节序逐字继承暖陶：先参数四组，后提示词三组。 */
@Composable
internal fun LiuliMemoryHubContent(
    settings: AppSettings,
    extraction: String,
    injection: String,
    memoryCallbacks: LiuliMemoryCallbacks,
    promptCallbacks: LiuliMemoryPromptCallbacks,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.mem_settings_title)
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    liuliMemoryParamGroups(settings, memoryCallbacks)
                    liuliMemoryPromptGroups(extraction, injection, promptCallbacks)
                }
            }
        }
    }
}
