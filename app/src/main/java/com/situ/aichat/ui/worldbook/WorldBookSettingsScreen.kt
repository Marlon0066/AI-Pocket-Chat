package com.situ.aichat.ui.worldbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.ui.components.SettingsSliderRow
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.worldbook.WorldInfoInsertionStrategy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设定集触发设置（WB7c·契约 §12.7·书架右上进入）：回看范围 / 篇幅上限 / 设定联动 / 插入偏好 /
 * 高级匹配 / 语义灵敏度指路。写入即持久化，AssistantTurnEngine 每回合现读——改完即用（§12.11-3）。
 */
@HiltViewModel
class WorldBookSettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepo.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setScanDepth(depth: Int) = viewModelScope.launch { settingsRepo.setWorldInfoScanDepth(depth) }
    fun setBudgetChars(chars: Int) = viewModelScope.launch { settingsRepo.setWorldInfoBudgetChars(chars) }
    fun setRecursiveScan(enabled: Boolean) = viewModelScope.launch { settingsRepo.setWorldInfoRecursiveScan(enabled) }
    fun setMaxRecursionSteps(steps: Int) = viewModelScope.launch { settingsRepo.setWorldInfoMaxRecursionSteps(steps) }
    fun setInsertionStrategy(strategy: WorldInfoInsertionStrategy) =
        viewModelScope.launch { settingsRepo.setWorldInfoInsertionStrategy(strategy.name) }
    fun setCaseSensitive(enabled: Boolean) = viewModelScope.launch { settingsRepo.setWorldInfoCaseSensitive(enabled) }
    fun setMatchWholeWords(enabled: Boolean) = viewModelScope.launch { settingsRepo.setWorldInfoMatchWholeWords(enabled) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookSettingsScreen(
    onBack: () -> Unit,
    onOpenMemorySettings: () -> Unit,
    viewModel: WorldBookSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = AppTheme.colors

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.wb_settings_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(horizontal = AppSpacing.screenGutter, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = AppShapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepperRow(
                        label = stringResource(R.string.wb_scan_range),
                        value = settings.worldInfoScanDepth,
                        range = 1..10,
                        valueText = stringResource(R.string.wb_n_messages, settings.worldInfoScanDepth),
                        onChange = viewModel::setScanDepth,
                        hint = stringResource(R.string.wb_scan_range_sub),
                    )
                }
            }
            Surface(shape = AppShapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsSliderRow(
                        label = stringResource(R.string.wb_budget),
                        valueLabel = stringResource(R.string.wb_char_count, settings.worldInfoBudgetChars),
                        value = settings.worldInfoBudgetChars.toFloat(),
                        valueRange = 1_000f..20_000f,
                        steps = 18,
                        infoMessage = stringResource(R.string.wb_budget_sub),
                        onManualInput = { viewModel.setBudgetChars(it) },
                        onValueChange = { viewModel.setBudgetChars(it.toInt()) },
                    )
                }
            }
            Surface(shape = AppShapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsSwitchRow(
                        title = stringResource(R.string.wb_linkage),
                        subtitle = stringResource(R.string.wb_linkage_sub),
                        checked = settings.worldInfoRecursiveScan,
                        onCheckedChange = viewModel::setRecursiveScan,
                    )
                    if (settings.worldInfoRecursiveScan) {
                        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                            StepperRow(
                                label = stringResource(R.string.wb_linkage_depth),
                                value = settings.worldInfoMaxRecursionSteps,
                                range = 0..10,
                                valueText = if (settings.worldInfoMaxRecursionSteps == 0) {
                                    stringResource(R.string.wb_unlimited)
                                } else {
                                    stringResource(R.string.wb_layers_value, settings.worldInfoMaxRecursionSteps)
                                },
                                onChange = viewModel::setMaxRecursionSteps,
                            )
                        }
                    }
                }
            }
            Surface(shape = AppShapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    val current = runCatching { WorldInfoInsertionStrategy.valueOf(settings.worldInfoInsertionStrategy) }
                        .getOrDefault(WorldInfoInsertionStrategy.CHARACTER_FIRST)
                    DropdownRow(
                        label = stringResource(R.string.wb_insertion_pref),
                        value = stringResource(strategyLabelRes(current)),
                        options = listOf(
                            WorldInfoInsertionStrategy.CHARACTER_FIRST,
                            WorldInfoInsertionStrategy.GLOBAL_FIRST,
                            WorldInfoInsertionStrategy.EVENLY,
                        ).map { strategy ->
                            stringResource(strategyLabelRes(strategy)) to { viewModel.setInsertionStrategy(strategy) }
                        },
                    )
                }
            }
            Text(
                stringResource(R.string.wb_adv_matching_section),
                style = MaterialTheme.typography.titleSmall,
                color = colors.text.secondary,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
            Surface(shape = AppShapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsSwitchRow(
                        title = stringResource(R.string.wb_case_label),
                        checked = settings.worldInfoCaseSensitive,
                        onCheckedChange = viewModel::setCaseSensitive,
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.wb_whole_label),
                        subtitle = stringResource(R.string.wb_whole_warning),
                        checked = settings.worldInfoMatchWholeWords,
                        onCheckedChange = viewModel::setMatchWholeWords,
                    )
                }
            }
            Surface(
                shape = AppShapes.medium,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().clickableScale { onOpenMemorySettings() },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.wb_semantic_row),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.text.primary,
                        )
                        Text(
                            stringResource(R.string.wb_semantic_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.text.secondary,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.text.secondary)
                }
            }
        }
    }
}

/** 琉璃卷五复用（`ui/liuli` 树借同一份实现·改这里两张脸同时变）。 */
internal fun strategyLabelRes(strategy: WorldInfoInsertionStrategy): Int = when (strategy) {
    WorldInfoInsertionStrategy.CHARACTER_FIRST -> R.string.wb_strategy_char_first
    WorldInfoInsertionStrategy.GLOBAL_FIRST -> R.string.wb_strategy_global_first
    WorldInfoInsertionStrategy.EVENLY -> R.string.wb_strategy_evenly
}
