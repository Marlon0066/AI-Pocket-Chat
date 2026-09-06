package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.settings.MemoryTriggerPreview
import kotlin.math.roundToInt

/**
 * 记忆参数四组（琉璃·图纸 2026-09-06 卷五 A-3 / §4.1 屏 1）。节序 / 条件 / 值域 / 步数 / 吸附 / 文案 /
 * 回调逐字继承暖陶 `MemorySettingsSections`（`ui/settings/MemorySettingsScreen.kt`）——**这里只换长相**。
 *
 * 「攒够多少轮再总结」那一行的三件（滑杆上限跟着短期窗口走 / ⓘ 说明 / 越界琥珀副标）来自 2026-09-05
 * 已 SHIP 的记忆设置页小批（D-1–D-6），一件都不许在换脸时掉：上限算式、`MemoryTriggerPreview` 三态、
 * `subtitleIsWarning` 分别落在 `valueRange` / `example` / `exampleIsWarning` 上。
 */

/** 记忆参数段的写口（一屏七枚 setter·与暖陶 `MemorySettingsViewModel` 一一对应）。 */
@Immutable
data class LiuliMemoryCallbacks(
    val onSetShortTermLength: (Int) -> Unit,
    val onSetAutoSummarizeInterval: (Int) -> Unit,
    val onSetCooldownMinutes: (Int) -> Unit,
    val onSetSummaryMaxLength: (Int) -> Unit,
    val onSetProgressive: (Boolean) -> Unit,
    val onSetStructuredInterval: (Int) -> Unit,
    val onSetVectorThreshold: (Int) -> Unit,
)

@Composable
internal fun ColumnScope.liuliMemoryParamGroups(s: AppSettings, callbacks: LiuliMemoryCallbacks) {
    val rounds = stringResource(R.string.mem_unit_rounds)
    val chars = stringResource(R.string.mem_unit_chars)
    val off = stringResource(R.string.mem_value_off)
    val minutes = stringResource(R.string.mem_unit_minutes)
    val unlimited = stringResource(R.string.mem_value_unlimited)

    LiuliGroup(
        header = stringResource(R.string.mem_section_short_title),
        footer = stringResource(R.string.mem_section_short_footer),
    ) {
        LiuliSliderRow(
            title = stringResource(R.string.mem_short_rounds),
            valueLabel = "${s.shortTermMemoryLength} $rounds",
            value = s.shortTermMemoryLength.toFloat(),
            valueRange = 1f..100f,
            steps = 98,
            divider = false,
            onManualInput = callbacks.onSetShortTermLength,
            onValueChange = { callbacks.onSetShortTermLength(it.roundToInt()) },
        )
    }

    LiuliGroup(
        header = stringResource(R.string.mem_section_long_title),
        footer = stringResource(R.string.mem_section_long_footer),
    ) {
        // 滑杆上限跟着短期窗口走 = 拦住「设出安全区」的常见误设；手填仍可超（setter 不加上限钳），
        // 越界靠下方琥珀那一行提示，用户的值绝不被静默改写（逐字照暖陶 :55–58）。
        val triggerUpper = maxOf(s.shortTermMemoryLength, s.autoSummarizeInterval, 1)
        val preview = MemoryTriggerPreview.from(
            window = s.shortTermMemoryLength,
            interval = s.autoSummarizeInterval,
            cooldownMinutes = s.memorySummaryCooldownMinutes,
        )
        LiuliSliderRow(
            title = stringResource(R.string.mem_long_trigger),
            valueLabel = if (s.autoSummarizeInterval == 0) off else "${s.autoSummarizeInterval} $rounds",
            value = s.autoSummarizeInterval.toFloat(),
            valueRange = 0f..triggerUpper.toFloat(),
            steps = (triggerUpper - 1).coerceAtLeast(0),
            divider = false,
            info = stringResource(R.string.mem_long_trigger_info),
            onManualInput = callbacks.onSetAutoSummarizeInterval,
            example = when (preview) {
                MemoryTriggerPreview.Off -> stringResource(R.string.mem_long_live_off)
                is MemoryTriggerPreview.OverWindow -> stringResource(R.string.mem_long_over_window, preview.window)
                is MemoryTriggerPreview.Normal -> if (preview.cooldownMinutes > 0) {
                    stringResource(R.string.mem_long_live_example, preview.firstRound, preview.interval, preview.cooldownMinutes)
                } else {
                    stringResource(R.string.mem_long_live_example_nowait, preview.firstRound, preview.interval)
                }
            },
            exampleIsWarning = preview is MemoryTriggerPreview.OverWindow,
            onValueChange = { callbacks.onSetAutoSummarizeInterval(it.roundToInt()) },
        )
        LiuliSliderRow(
            title = stringResource(R.string.mem_long_cooldown),
            valueLabel = if (s.memorySummaryCooldownMinutes == 0) unlimited else "${s.memorySummaryCooldownMinutes} $minutes",
            value = s.memorySummaryCooldownMinutes.toFloat(),
            valueRange = 0f..180f,
            steps = 35,
            onManualInput = callbacks.onSetCooldownMinutes, // 手填不做 5 吸附（同暖陶）
            onValueChange = { callbacks.onSetCooldownMinutes((it / 5f).roundToInt() * 5) },
        )
        LiuliSliderRow(
            title = stringResource(R.string.mem_long_max),
            valueLabel = "${s.memorySummaryMaxLength} $chars",
            value = s.memorySummaryMaxLength.toFloat(),
            valueRange = 200f..5000f,
            steps = 47,
            onManualInput = callbacks.onSetSummaryMaxLength, // 手填不做 100 吸附（同暖陶）
            onValueChange = { callbacks.onSetSummaryMaxLength((it / 100f).roundToInt() * 100) },
        )
        // 智能渐进压缩：用户自定义提取 prompt 时开关让位 → 置灰 + 显示关（存储值不变，清空自定义后恢复）。
        val canUseProgressive = s.memoryExtractionPrompt.isEmpty()
        LiuliToggleRow(
            title = stringResource(R.string.mem_progressive_title),
            subtitle = stringResource(
                if (canUseProgressive) R.string.mem_progressive_subtitle else R.string.mem_progressive_disabled_hint,
            ),
            checked = s.progressiveCompressionEnabled && canUseProgressive,
            enabled = canUseProgressive,
            onCheckedChange = callbacks.onSetProgressive,
        )
    }

    LiuliGroup(
        header = stringResource(R.string.mem_section_structured_title),
        footer = stringResource(R.string.mem_section_structured_footer),
    ) {
        LiuliSliderRow(
            title = stringResource(R.string.mem_structured_trigger),
            valueLabel = if (s.structuredMemoryInterval == 0) off else "${s.structuredMemoryInterval} $rounds",
            value = s.structuredMemoryInterval.toFloat(),
            valueRange = 0f..100f,
            steps = 99,
            divider = false,
            onManualInput = callbacks.onSetStructuredInterval,
            onValueChange = { callbacks.onSetStructuredInterval(it.roundToInt()) },
        )
    }

    LiuliGroup(
        header = stringResource(R.string.mem_section_vector_title),
        footer = stringResource(R.string.mem_section_vector_footer),
    ) {
        LiuliSliderRow(
            title = stringResource(R.string.mem_vector_threshold),
            valueLabel = if (s.vectorSearchThreshold == 0) off else "${s.vectorSearchThreshold}%",
            value = s.vectorSearchThreshold.toFloat(),
            valueRange = 0f..100f,
            steps = 19,
            divider = false,
            // 向量阈值是百分比，手填仍钳 0~100（>100% 无意义·有意微偏 iOS·同暖陶注释）。
            onManualInput = callbacks.onSetVectorThreshold,
            onValueChange = { callbacks.onSetVectorThreshold((it / 5f).roundToInt() * 5) },
        )
    }
}
