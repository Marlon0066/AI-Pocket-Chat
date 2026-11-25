package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.MemoryPromptsSettingsViewModel

/** 琉璃 2.0「记忆提示词」独立页（设置全面重构卷二，挂在「高级 → 记忆提示词」下）。 */
@Composable
fun LiuliMemoryPromptsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    promptsViewModel: MemoryPromptsSettingsViewModel = hiltViewModel(),
) {
    val extraction by promptsViewModel.extraction.collectAsStateWithLifecycle()
    val injection by promptsViewModel.injection.collectAsStateWithLifecycle()
    LiuliMemoryPromptsContent(
        extraction = extraction,
        injection = injection,
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

@Composable
internal fun LiuliMemoryPromptsContent(
    extraction: String,
    injection: String,
    promptCallbacks: LiuliMemoryPromptCallbacks,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.settings_adv_memory_prompts_title)
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
                    liuliMemoryPromptGroups(extraction, injection, promptCallbacks)
                }
            }
        }
    }
}
