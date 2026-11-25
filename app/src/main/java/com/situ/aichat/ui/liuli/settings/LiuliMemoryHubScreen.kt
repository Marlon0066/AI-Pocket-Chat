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
import com.situ.aichat.ui.settings.MemorySettingsViewModel

/**
 * 琉璃 2.0 记忆设置页（设置全面重构卷二）：
 * 聚焦日常记忆参数（含见面记忆）；提取与注入提示词模板已迁至「高级 → 记忆提示词」独立页（[LiuliMemoryPromptsScreen]）。
 */
@Composable
fun LiuliMemoryHubScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    memoryViewModel: MemorySettingsViewModel = hiltViewModel(),
) {
    val settings by memoryViewModel.state.collectAsStateWithLifecycle()
    LiuliMemoryHubContent(
        settings = settings,
        memoryCallbacks = LiuliMemoryCallbacks(
            onSetShortTermLength = memoryViewModel::setShortTermMemoryLength,
            onSetAutoSummarizeInterval = memoryViewModel::setAutoSummarizeInterval,
            onSetCooldownMinutes = memoryViewModel::setMemorySummaryCooldownMinutes,
            onSetSummaryMaxLength = memoryViewModel::setMemorySummaryMaxLength,
            onSetProgressive = memoryViewModel::setProgressiveCompressionEnabled,
            onSetStructuredInterval = memoryViewModel::setStructuredMemoryInterval,
            onSetVectorThreshold = memoryViewModel::setVectorSearchThreshold,
            onSetMeetingMemoryInjectCount = memoryViewModel::setMeetingMemoryInjectCount,
            onSetMeetingMemoryMaxLength = memoryViewModel::setMeetingMemoryMaxLength,
        ),
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun LiuliMemoryHubContent(
    settings: AppSettings,
    memoryCallbacks: LiuliMemoryCallbacks,
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
                }
            }
        }
    }
}
