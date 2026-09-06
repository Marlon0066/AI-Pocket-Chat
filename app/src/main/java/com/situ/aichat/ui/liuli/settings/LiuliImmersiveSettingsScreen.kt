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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.ImmersiveSettingsViewModel

/** 页标题（暖陶 `ImmersiveSettingsScreen.kt:58` 的硬编码同值·A-6）。 */
private const val PAGE_TITLE = "线下见面"

/**
 * 线下见面设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 3）。与暖陶 `ImmersiveSettingsScreen` 共用
 * [ImmersiveSettingsViewModel]；节序 / 门控 / raw 值 / 文案逐字继承（文案见 `LiuliImmersiveSections`）。
 *
 * `imePadding()` 照暖陶留着——本页有四个会拉起键盘的输入格（三个 custom 编辑器 + 背景色）。
 */
@Composable
fun LiuliImmersiveSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImmersiveSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    LiuliImmersiveSettingsContent(
        settings = settings,
        callbacks = LiuliImmersiveCallbacks(
            onSetCharacterCanInitiate = viewModel::setCharacterCanInitiate,
            onSetImmersiveInput = viewModel::setImmersiveInputEnabled,
            onSetNarrativeDetail = viewModel::setNarrativeDetail,
            onSetCustomStyle = viewModel::setCustomStyle,
            onSetCustomDirective = viewModel::setCustomDirective,
            onSetCustomEmotion = viewModel::setCustomEmotion,
            onSetMeetingMemoryInjectCount = viewModel::setMeetingMemoryInjectCount,
            onSetMeetingMemoryMaxLength = viewModel::setMeetingMemoryMaxLength,
            onSetAfterglowEnabled = viewModel::setOfflineAfterglowEnabled,
            onSetBackgroundStyle = viewModel::setBackgroundStyle,
            onSetParticleStyle = viewModel::setParticleStyle,
            onSetBackgroundColor = viewModel::setBackgroundColor,
        ),
        onBack = onBack,
        modifier = modifier,
    )
}

/** 线下见面页内容层（纯参数·可测）。 */
@Composable
internal fun LiuliImmersiveSettingsContent(
    settings: AppSettings,
    callbacks: LiuliImmersiveCallbacks,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(
        title = PAGE_TITLE,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(PAGE_TITLE) }
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    liuliImmersiveGroups(settings, callbacks)
                }
            }
        }
    }
}
