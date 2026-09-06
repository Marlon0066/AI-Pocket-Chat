package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSliderRow
import com.situ.aichat.ui.components.SettingsSwitchRow
import kotlin.math.roundToInt

/**
 * 记忆参数段（P12.1）。短期记忆 / 长期记忆(滚动摘要) / 结构化记忆 / **向量检索阈值（安卓特有 ONNX 向量层）**。
 * 创造力（温度）已于 2026-07-11 搬家至回复规则页（FABLE5_CHAT_CREATIVITY_RELOCATION_PROPOSAL D-1）。
 * SETTINGS_REORG D3 起为 [MemoryHubScreen] 上半段：壳（Scaffold / 滚动 / 标题）在 hub，此处只出内容。
 */
@Composable
fun MemorySettingsSections(viewModel: MemorySettingsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val rounds = stringResource(R.string.mem_unit_rounds)
    val chars = stringResource(R.string.mem_unit_chars)
    val off = stringResource(R.string.mem_value_off)
    val minutes = stringResource(R.string.mem_unit_minutes)
    val unlimited = stringResource(R.string.mem_value_unlimited)

    Column {
        SettingsSection(
            title = stringResource(R.string.mem_section_short_title),
            footer = stringResource(R.string.mem_section_short_footer),
        ) {
            SettingsSliderRow(
                label = stringResource(R.string.mem_short_rounds),
                valueLabel = "${s.shortTermMemoryLength} $rounds",
                value = s.shortTermMemoryLength.toFloat(),
                valueRange = 1f..100f,
                steps = 98,
                onManualInput = { viewModel.setShortTermMemoryLength(it) }, // settings-slider-manualinput
                onValueChange = { viewModel.setShortTermMemoryLength(it.roundToInt()) },
            )
        }

        SettingsSection(
            title = stringResource(R.string.mem_section_long_title),
            footer = stringResource(R.string.mem_section_long_footer),
        ) {
            // 攒够多少轮再总结（提案 D-1/D-3·图纸 §4.1）：滑杆上限跟着短期窗口走 = 拦住「设出安全区」的常见误设；
            // 手填仍可超（设置行惯例·setter 不加上限钳），越界靠下方琥珀副标提示，用户的值绝不被静默改写。
            val triggerUpper = maxOf(s.shortTermMemoryLength, s.autoSummarizeInterval, 1)
            val preview = MemoryTriggerPreview.from(
                window = s.shortTermMemoryLength,
                interval = s.autoSummarizeInterval,
                cooldownMinutes = s.memorySummaryCooldownMinutes,
            )
            SettingsSliderRow(
                label = stringResource(R.string.mem_long_trigger),
                valueLabel = if (s.autoSummarizeInterval == 0) off else "${s.autoSummarizeInterval} $rounds",
                value = s.autoSummarizeInterval.toFloat(),
                valueRange = 0f..triggerUpper.toFloat(),
                steps = (triggerUpper - 1).coerceAtLeast(0),
                infoMessage = stringResource(R.string.mem_long_trigger_info), // settings-slider-infobutton
                onManualInput = { viewModel.setAutoSummarizeInterval(it) }, // settings-slider-manualinput
                subtitle = when (preview) {
                    MemoryTriggerPreview.Off -> stringResource(R.string.mem_long_live_off)
                    is MemoryTriggerPreview.OverWindow -> stringResource(R.string.mem_long_over_window, preview.window)
                    is MemoryTriggerPreview.Normal -> if (preview.cooldownMinutes > 0) {
                        stringResource(R.string.mem_long_live_example, preview.firstRound, preview.interval, preview.cooldownMinutes)
                    } else {
                        stringResource(R.string.mem_long_live_example_nowait, preview.firstRound, preview.interval)
                    }
                },
                subtitleIsWarning = preview is MemoryTriggerPreview.OverWindow,
                onValueChange = { viewModel.setAutoSummarizeInterval(it.roundToInt()) },
            )
            SettingsSliderRow(
                label = stringResource(R.string.mem_long_cooldown),
                valueLabel = if (s.memorySummaryCooldownMinutes == 0) unlimited else "${s.memorySummaryCooldownMinutes} $minutes",
                value = s.memorySummaryCooldownMinutes.toFloat(),
                valueRange = 0f..180f,
                steps = 35,
                onManualInput = { viewModel.setMemorySummaryCooldownMinutes(it) }, // settings-slider-manualinput（手填不做 5 吸附）
                onValueChange = { viewModel.setMemorySummaryCooldownMinutes((it / 5f).roundToInt() * 5) },
            )
            SettingsSliderRow(
                label = stringResource(R.string.mem_long_max),
                valueLabel = "${s.memorySummaryMaxLength} $chars",
                value = s.memorySummaryMaxLength.toFloat(),
                valueRange = 200f..5000f,
                steps = 47,
                onManualInput = { viewModel.setMemorySummaryMaxLength(it) }, // settings-slider-manualinput（手填不做 100 吸附）
                onValueChange = { viewModel.setMemorySummaryMaxLength((it / 100f).roundToInt() * 100) },
            )
            // 智能渐进压缩（2026-06-20）：用户自定义提取 prompt 时开关让位 → 置灰 + 显示关（存储值不变，清空自定义后恢复）。
            val canUseProgressive = s.memoryExtractionPrompt.isEmpty()
            SettingsSwitchRow(
                title = stringResource(R.string.mem_progressive_title),
                subtitle = stringResource(
                    if (canUseProgressive) R.string.mem_progressive_subtitle else R.string.mem_progressive_disabled_hint,
                ),
                checked = s.progressiveCompressionEnabled && canUseProgressive,
                enabled = canUseProgressive,
                onCheckedChange = { viewModel.setProgressiveCompressionEnabled(it) },
            )
        }

        SettingsSection(
            title = stringResource(R.string.mem_section_structured_title),
            footer = stringResource(R.string.mem_section_structured_footer),
        ) {
            SettingsSliderRow(
                label = stringResource(R.string.mem_structured_trigger),
                valueLabel = if (s.structuredMemoryInterval == 0) off else "${s.structuredMemoryInterval} $rounds",
                value = s.structuredMemoryInterval.toFloat(),
                valueRange = 0f..100f,
                steps = 99,
                onManualInput = { viewModel.setStructuredMemoryInterval(it) }, // settings-slider-manualinput
                onValueChange = { viewModel.setStructuredMemoryInterval(it.roundToInt()) },
            )
        }

        SettingsSection(
            title = stringResource(R.string.mem_section_vector_title),
            footer = stringResource(R.string.mem_section_vector_footer),
        ) {
            SettingsSliderRow(
                label = stringResource(R.string.mem_vector_threshold),
                valueLabel = if (s.vectorSearchThreshold == 0) off else "${s.vectorSearchThreshold}%",
                value = s.vectorSearchThreshold.toFloat(),
                valueRange = 0f..100f,
                steps = 19,
                // settings-slider-manualinput：向量阈值是百分比，手填仍钳 0~100（>100% 无意义，有意微偏 iOS）。
                onManualInput = { viewModel.setVectorSearchThreshold(it) },
                onValueChange = { viewModel.setVectorSearchThreshold((it / 5f).roundToInt() * 5) },
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}
