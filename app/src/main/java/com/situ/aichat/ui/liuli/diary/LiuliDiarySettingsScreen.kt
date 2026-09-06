package com.situ.aichat.ui.liuli.diary

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.diary.DiarySettingsState
import com.situ.aichat.ui.diary.DiarySettingsViewModel
import com.situ.aichat.ui.diary.parseHm
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed

/**
 * 日记设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 14·A-4 ⑥ 时间选择器唯一豁免点）。与暖陶
 * `DiarySettingsScreen` 共用 [DiarySettingsViewModel]。
 *
 * **持久化格式零碰**：写回恒 `"%02d:%02d".format(hour, minute)`，读回走暖陶 [parseHm]
 * （§2.2-2 已提 internal·缺省 21:00 + `coerceIn`）——这一对是存串的两端，改一侧就读不回来。
 */
@Composable
fun LiuliDiarySettingsScreen(
    onBack: () -> Unit,
    onOpenWritingRules: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiarySettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiuliDiarySettingsContent(
        state = state,
        callbacksFor = { openPicker ->
            LiuliDiarySettingsCallbacks(
                onSetAutoGenerate = viewModel::setAutoGenerateEnabled,
                onOpenTimePicker = openPicker,
                onSetAutoPublish = viewModel::setAutoPublishEnabled,
                onSetPetAutoGenerate = viewModel::setPetAutoGenerateEnabled,
                onSelectExchangePartner = viewModel::setExchangePartner,
                onSetCommentEnabled = viewModel::setCommentEnabled,
                onSetCommentDelay = viewModel::setCommentDelay,
                onToggleCharacter = viewModel::toggleCharacter,
                onOpenWritingRules = onOpenWritingRules,
            )
        },
        onCommitTime = viewModel::setAutoGenerateTime,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * 日记设置页内容层（纯参数·可测）。时间选择弹窗的开合态住这里，故回调表由 [callbacksFor] 现造
 * ——「点那一行 → 开弹窗」这条线不该穿到 VM 层去。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiuliDiarySettingsContent(
    state: DiarySettingsState,
    callbacksFor: (openPicker: () -> Unit) -> LiuliDiarySettingsCallbacks,
    onCommitTime: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.diary_settings_title)
    var showTimePicker by remember { mutableStateOf(false) }
    var partnerMenuOpen by remember { mutableStateOf(false) }
    val callbacks = callbacksFor { showTimePicker = true }
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
                    liuliDiarySettingsGroups(
                        state = state,
                        partnerMenuOpen = partnerMenuOpen,
                        onPartnerMenuOpenChange = { partnerMenuOpen = it },
                        callbacks = callbacks,
                    )
                }
            }
        }
    }

    if (showTimePicker) {
        val parsed = remember(state.autoGenerateTime) { parseHm(state.autoGenerateTime) }
        val pickerState = rememberTimePickerState(initialHour = parsed.first, initialMinute = parsed.second, is24Hour = true)
        LiuliDialog(
            onDismissRequest = { showTimePicker = false },
            title = null,
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = {
                // 持久化格式零碰：两位补零的 "HH:mm"，读回走 parseHm。
                onCommitTime("%02d:%02d".format(pickerState.hour, pickerState.minute))
                showTimePicker = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showTimePicker = false },
            // A-4 ⑥：**本卷唯一的 M3 豁免**——时间轮暂借 M3 `TimePicker`，自绘时间轮挂账卷六对版稿。
            content = { TimePicker(state = pickerState) },
        )
    }
}
