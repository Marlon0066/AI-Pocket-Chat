package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Notifications
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
import com.situ.aichat.BuildConfig
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsMasterCard
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSliderRow
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppSettingsRow
import com.situ.aichat.ui.designsystem.AppTopBar
import kotlin.math.roundToInt

/**
 * 暖陶「成长与关系」设置页（设置全面重构卷三）：
 * 顶卡总控角色成长 + 从属「自动推进关系」开关 + 4 枚分析与记忆滑杆（总开关关闭时整组可见置灰 + 明确原因）
 * + 前往「通知」管理成长提醒的快捷链接 + debug 观测台。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrowthSettingsScreen(
    onBack: () -> Unit,
    onOpenObservatory: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    viewModel: GrowthSettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val rounds = stringResource(R.string.mem_unit_rounds)
    val off = stringResource(R.string.mem_value_off)
    val entries = stringResource(R.string.growth_unit_entries)
    val days = stringResource(R.string.growth_unit_days)
    val enabled = s.growthSystemEnabled
    val disabledReason = if (!enabled) stringResource(R.string.settings_growth_disabled_reason) else null

    val scrollState = rememberScrollState()
    val lifted by remember { derivedStateOf { scrollState.value > 0 } }
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.growth_settings_title),
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
                title = stringResource(R.string.settings_growth_master_title),
                subtitle = stringResource(R.string.settings_growth_master_sub),
                icon = Icons.Filled.Insights,
                checked = enabled,
                onCheckedChange = viewModel::setGrowthSystemEnabled,
            )

            SettingsSection(title = stringResource(R.string.sys_relationship_title)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.sys_relationship_title),
                    subtitle = stringResource(R.string.sys_relationship_subtitle),
                    checked = enabled && s.relationshipAutoAdvanceEnabled,
                    enabled = enabled,
                    disabledReason = disabledReason,
                    onCheckedChange = viewModel::setRelationshipAutoAdvanceEnabled,
                )
            }

            SettingsSection(
                title = stringResource(R.string.growth_section_analysis_title),
                footer = stringResource(R.string.growth_section_analysis_footer),
            ) {
                SettingsSliderRow(
                    label = stringResource(R.string.growth_analysis_interval),
                    valueLabel = if (s.growthAnalysisInterval == 0) off else "${s.growthAnalysisInterval} $rounds",
                    value = s.growthAnalysisInterval.toFloat(),
                    valueRange = 0f..100f,
                    steps = 19,
                    enabled = enabled,
                    onManualInput = { viewModel.setGrowthAnalysisInterval(it) },
                    onValueChange = { viewModel.setGrowthAnalysisInterval((it / 5f).roundToInt() * 5) },
                )
            }

            SettingsSection(title = stringResource(R.string.growth_section_memory_title)) {
                SettingsSliderRow(
                    label = stringResource(R.string.growth_mood_history_max),
                    valueLabel = "${s.moodHistoryMaxCount} $entries",
                    value = s.moodHistoryMaxCount.toFloat(),
                    valueRange = 50f..500f,
                    steps = 8,
                    enabled = enabled,
                    onManualInput = { viewModel.setMoodHistoryMaxCount(it) },
                    onValueChange = { viewModel.setMoodHistoryMaxCount((it / 50f).roundToInt() * 50) },
                )
                SettingsSliderRow(
                    label = stringResource(R.string.growth_log_max),
                    valueLabel = "${s.growthLogMaxCount} $entries",
                    value = s.growthLogMaxCount.toFloat(),
                    valueRange = 20f..300f,
                    steps = 27,
                    enabled = enabled,
                    onManualInput = { viewModel.setGrowthLogMaxCount(it) },
                    onValueChange = { viewModel.setGrowthLogMaxCount((it / 10f).roundToInt() * 10) },
                )
                SettingsSliderRow(
                    label = stringResource(R.string.growth_interest_cooldown),
                    valueLabel = "${s.interestCooldownDays} $days",
                    value = s.interestCooldownDays.toFloat(),
                    valueRange = 1f..60f,
                    steps = 58,
                    enabled = enabled,
                    onManualInput = { viewModel.setInterestCooldownDays(it) },
                    onValueChange = { viewModel.setInterestCooldownDays(it.roundToInt()) },
                )
            }

            SettingsSection(title = stringResource(R.string.notif_settings_title)) {
                AppSettingsRow(
                    title = stringResource(R.string.settings_growth_notif_link),
                    subtitle = stringResource(R.string.settings_growth_notif_link_sub),
                    icon = Icons.Filled.Notifications,
                    showChevron = true,
                    onClick = onOpenNotifications,
                )
            }

            if (BuildConfig.DEBUG) {
                SettingsSection(title = "开发者") {
                    SettingsRow(
                        icon = Icons.Filled.Insights,
                        title = "内核观测台（仅 debug）",
                        onClick = onOpenObservatory,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
