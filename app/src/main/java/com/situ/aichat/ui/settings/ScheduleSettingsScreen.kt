package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
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
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.components.SettingsMasterCard
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 暖陶「日程与社交」独立页（设置全面重构卷三）：
 * 顶卡总控日程系统，下方从属「角色之间来往」开关与频率三档；关闭日程时从属项置灰带锁说明。
 */
@Composable
fun ScheduleSettingsScreen(
    onBack: () -> Unit,
    viewModel: SystemTogglesViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    ScheduleSettingsContent(
        settings = s,
        onSetScheduleEnabled = viewModel::setScheduleSystemEnabled,
        onSetCrossCharacterLevel = viewModel::setCrossCharacterLevel,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleSettingsContent(
    settings: AppSettings,
    onSetScheduleEnabled: (Boolean) -> Unit,
    onSetCrossCharacterLevel: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val lifted by remember { derivedStateOf { scrollState.value > 0 } }
    val enabled = settings.scheduleSystemEnabled
    val disabledReason = if (!enabled) stringResource(R.string.settings_schedule_disabled_reason) else null

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_hub_schedule),
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
                .contentMaxWidth(),
        ) {
            SettingsMasterCard(
                title = stringResource(R.string.sys_schedule_title),
                subtitle = stringResource(R.string.settings_schedule_master_sub),
                icon = Icons.Filled.Schedule,
                checked = enabled,
                onCheckedChange = onSetScheduleEnabled,
            )

            SettingsSection(title = stringResource(R.string.sys_cross_character_title)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.sys_cross_character_title),
                    subtitle = stringResource(R.string.sys_cross_character_subtitle),
                    checked = enabled && settings.crossCharacterLevel > 0,
                    enabled = enabled,
                    disabledReason = disabledReason,
                    onCheckedChange = { on -> onSetCrossCharacterLevel(if (on) 1 else 0) },
                )
                if (enabled && settings.crossCharacterLevel > 0) {
                    AppSegmentedControl(
                        options = listOf(1, 2, 3),
                        selected = settings.crossCharacterLevel.coerceIn(1, 3),
                        onSelect = onSetCrossCharacterLevel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        label = { level ->
                            stringResource(
                                when (level) {
                                    1 -> R.string.cross_level_occasional
                                    2 -> R.string.cross_level_often
                                    else -> R.string.cross_level_frequent
                                },
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
