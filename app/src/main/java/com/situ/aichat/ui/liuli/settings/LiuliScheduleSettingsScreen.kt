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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.designsystem.LiuliTileTone
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliMasterCard
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliSegmentRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.SystemTogglesViewModel

/** 琉璃 2.0「日程与社交」独立页（设置全面重构卷三）。 */
@Composable
fun LiuliScheduleSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SystemTogglesViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LiuliScheduleSettingsContent(
        settings = s,
        onSetScheduleEnabled = viewModel::setScheduleSystemEnabled,
        onSetCrossCharacterLevel = viewModel::setCrossCharacterLevel,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun LiuliScheduleSettingsContent(
    settings: AppSettings,
    onSetScheduleEnabled: (Boolean) -> Unit,
    onSetCrossCharacterLevel: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.settings_hub_schedule)
    val enabled = settings.scheduleSystemEnabled
    val disabledReason = if (!enabled) stringResource(R.string.settings_schedule_disabled_reason) else null
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
                    LiuliMasterCard(
                        title = stringResource(R.string.sys_schedule_title),
                        subtitle = stringResource(R.string.settings_schedule_master_sub),
                        icon = Icons.Filled.Schedule,
                        tileTone = LiuliTileTone.Mint,
                        checked = enabled,
                        onCheckedChange = onSetScheduleEnabled,
                    )

                    LiuliGroup(header = stringResource(R.string.sys_cross_character_title)) {
                        LiuliToggleRow(
                            title = stringResource(R.string.sys_cross_character_title),
                            subtitle = stringResource(R.string.sys_cross_character_subtitle),
                            checked = enabled && settings.crossCharacterLevel > 0,
                            enabled = enabled,
                            disabledReason = disabledReason,
                            divider = false,
                            onCheckedChange = { on -> onSetCrossCharacterLevel(if (on) 1 else 0) },
                        )
                        if (enabled && settings.crossCharacterLevel > 0) {
                            LiuliSegmentRow(
                                title = null,
                                options = listOf(1, 2, 3),
                                selected = settings.crossCharacterLevel.coerceIn(1, 3),
                                label = { level ->
                                    stringResource(
                                        when (level) {
                                            1 -> R.string.cross_level_occasional
                                            2 -> R.string.cross_level_often
                                            else -> R.string.cross_level_frequent
                                        },
                                    )
                                },
                                onSelect = onSetCrossCharacterLevel,
                            )
                        }
                    }
                }
            }
        }
    }
}
