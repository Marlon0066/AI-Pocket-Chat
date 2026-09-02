package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.BuildConfig
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSliderRow
import com.situ.aichat.ui.components.contentMaxWidth
import kotlin.math.roundToInt

/**
 * 成长设置页（P12.1d）。性格分析频率 / 成长记录数量 / 兴趣遗忘周期 = 对齐 iOS `GrowthRelationshipSettingsView`
 * 的「分析设置 / 记忆与兴趣」段（成长与关系总开关在 12.1b、记忆提取频率在 12.1a；情绪记录数量随 moodHistory 复活后接入本屏）。
 * 滑杆受「角色成长」总开关门控（1:1 iOS：关闭时不展示参数）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrowthSettingsScreen(
    onBack: () -> Unit,
    onOpenObservatory: () -> Unit = {},
    viewModel: GrowthSettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val rounds = stringResource(R.string.mem_unit_rounds)
    val off = stringResource(R.string.mem_value_off)
    val entries = stringResource(R.string.growth_unit_entries)
    val days = stringResource(R.string.growth_unit_days)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.growth_settings_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .contentMaxWidth(),
        ) {
            if (!s.growthSystemEnabled) {
                // 1:1 iOS：成长关闭时参数不可调，给出引导提示。
                Text(
                    stringResource(R.string.growth_disabled_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
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
                        onManualInput = { viewModel.setGrowthAnalysisInterval(it) }, // settings-slider-manualinput
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
                        onManualInput = { viewModel.setMoodHistoryMaxCount(it) }, // settings-slider-manualinput
                        onValueChange = { viewModel.setMoodHistoryMaxCount((it / 50f).roundToInt() * 50) },
                    )
                    SettingsSliderRow(
                        label = stringResource(R.string.growth_log_max),
                        valueLabel = "${s.growthLogMaxCount} $entries",
                        value = s.growthLogMaxCount.toFloat(),
                        valueRange = 20f..300f,
                        steps = 27,
                        onManualInput = { viewModel.setGrowthLogMaxCount(it) }, // settings-slider-manualinput（手填不做 10 吸附）
                        onValueChange = { viewModel.setGrowthLogMaxCount((it / 10f).roundToInt() * 10) },
                    )
                    SettingsSliderRow(
                        label = stringResource(R.string.growth_interest_cooldown),
                        valueLabel = "${s.interestCooldownDays} $days",
                        value = s.interestCooldownDays.toFloat(),
                        valueRange = 1f..60f,
                        steps = 58,
                        onManualInput = { viewModel.setInterestCooldownDays(it) }, // settings-slider-manualinput
                        onValueChange = { viewModel.setInterestCooldownDays(it.roundToInt()) },
                    )
                }
            }

            // 活人感内核卷零 chunk5：开发者调试页入口。BuildConfig.DEBUG 门控（屏本体另有一道守卫）
            // ⇒ release 构建整块短路，用户侧零变化。
            if (BuildConfig.DEBUG) {
                SettingsSection(title = "开发者") {
                    SettingsRow(
                        icon = Icons.Filled.Insights,
                        title = "内核观测台（仅 debug）",
                        onClick = onOpenObservatory,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
