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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.situ.aichat.ui.settings.WorldSettingsUiState
import com.situ.aichat.ui.settings.WorldSettingsViewModel
import com.situ.aichat.ui.world.resident.ResidentCreateSheet

/**
 * 世界设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 18「两 sheet 换 `LiuliSheetShell`」）。与暖陶
 * `WorldSettingsScreen` 共用 [WorldSettingsViewModel]；五节全部**即时写库**（无「保存」）。
 *
 * 两枚弹层：时区选择走琉璃自己的 [LiuliWorldTimezoneSheet]（清单内容逐字照暖陶）；
 * 居民创建 [ResidentCreateSheet] **直接借用**（§9 ⑤ 明列「禁重写」的内容件之一）。
 */
@Composable
fun LiuliWorldSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorldSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiuliWorldSettingsContent(
        state = state,
        onSetVividness = viewModel::setVividness,
        onSetNotification = viewModel::setNotification,
        onSetRelationships = viewModel::setRelationships,
        onSetRomance = viewModel::setRomance,
        onPickTimezone = viewModel::setTimezone,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 世界设置页内容层（纯参数·可测）。两枚弹层的开合态住这一层。 */
@Composable
internal fun LiuliWorldSettingsContent(
    state: WorldSettingsUiState,
    onSetVividness: (String) -> Unit,
    onSetNotification: (String) -> Unit,
    onSetRelationships: (Boolean) -> Unit,
    onSetRomance: (Boolean) -> Unit,
    onPickTimezone: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.world_settings_title)
    var showTzSheet by remember { mutableStateOf(false) }
    var showResidentSheet by remember { mutableStateOf(false) }
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
                    liuliWorldSettingsGroups(
                        state = state,
                        callbacks = LiuliWorldSettingsCallbacks(
                            onSetVividness = onSetVividness,
                            onSetNotification = onSetNotification,
                            onOpenTimezoneSheet = { showTzSheet = true },
                            onSetRelationships = onSetRelationships,
                            onSetRomance = onSetRomance,
                            onOpenResidentSheet = { showResidentSheet = true },
                        ),
                    )
                }
            }
        }
    }

    if (showResidentSheet) {
        // 内容件借用（§9 ⑤「禁重写 ResidentCreateSheet 内容件」）。
        ResidentCreateSheet(onDismiss = { showResidentSheet = false })
    }
    if (showTzSheet) {
        LiuliWorldTimezoneSheet(
            currentZoneId = state.timezoneId,
            onPick = {
                onPickTimezone(it)
                showTzSheet = false
            },
            onDismiss = { showTzSheet = false },
        )
    }
}
