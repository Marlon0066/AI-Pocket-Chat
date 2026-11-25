package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.components.SettingsRangeStepper
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSliderRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTopBar
import kotlin.math.roundToInt

/**
 * 暖陶「语音」统一页（设置全面重构卷二）：
 * 语音服务入口（→ ttsConfig）+ 每隔几轮发语音范围钮 + 通话打断灵敏度滑杆。
 */
@Composable
fun VoiceSettingsScreen(
    onBack: () -> Unit,
    onOpenTtsConfig: () -> Unit,
    viewModel: VoiceSettingsViewModel = hiltViewModel(),
) {
    val providerName by viewModel.ttsProviderName.collectAsStateWithLifecycle()
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val storedSlider by viewModel.sliderPosition.collectAsStateWithLifecycle()
    VoiceSettingsContent(
        ttsProviderName = providerName,
        settings = settings,
        storedSlider = storedSlider,
        onOpenTtsConfig = onOpenTtsConfig,
        onSetVoiceRoundRange = viewModel::setVoiceReplyRoundRange,
        onSetSliderPosition = viewModel::setSliderPosition,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceSettingsContent(
    ttsProviderName: String,
    settings: AppSettings,
    storedSlider: Float,
    onOpenTtsConfig: () -> Unit,
    onSetVoiceRoundRange: (Int, Int) -> Unit,
    onSetSliderPosition: (Float) -> Unit,
    onBack: () -> Unit,
) {
    val voice = settings.sanitizedVoiceReplyRoundRange
    var localSlider by remember(storedSlider) { mutableFloatStateOf(storedSlider) }
    val scrollState = rememberScrollState()
    val lifted by remember { derivedStateOf { scrollState.value > 0 } }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_hub_voice),
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
                .contentMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            SettingsGroupCard(stringResource(R.string.settings_voice_service_row)) {
                SettingsRow(
                    icon = Icons.Filled.GraphicEq,
                    title = stringResource(R.string.settings_voice_service_row),
                    subtitle = stringResource(R.string.settings_voice_service_sub),
                    value = ttsProviderName,
                    onClick = onOpenTtsConfig,
                )
            }

            SettingsSection(
                title = stringResource(R.string.reply_rule_section_voice),
                footer = stringResource(R.string.reply_rule_voice_footer),
            ) {
                SettingsRangeStepper(
                    title = stringResource(R.string.reply_rule_current_range),
                    rangeSummary = stringResource(R.string.reply_rule_rounds_range, voice.first, voice.last),
                    minValue = voice.first,
                    minRange = ReplyRuleRange.minStepperRange(voice.last, AppSettings.VOICE_REPLY_ROUND_MIN_BOUND),
                    minValueText = stringResource(R.string.reply_rule_rounds_value, voice.first),
                    onMinChange = { onSetVoiceRoundRange(it, voice.last) },
                    maxValue = voice.last,
                    maxRange = ReplyRuleRange.maxStepperRange(voice.first, AppSettings.VOICE_REPLY_ROUND_MAX_BOUND),
                    maxValueText = stringResource(R.string.reply_rule_rounds_value, voice.last),
                    onMaxChange = { onSetVoiceRoundRange(voice.first, it) },
                    minLabel = stringResource(R.string.reply_rule_min_rounds),
                    maxLabel = stringResource(R.string.reply_rule_max_rounds),
                )
            }

            SettingsSection(
                title = stringResource(R.string.voice_call_settings_title),
                footer = stringResource(R.string.voice_call_settings_footer),
            ) {
                val defaultSlider = VoiceCallSensitivity.sliderFromThreshold(
                    AppSettings.DEFAULT_VOICE_CALL_INTERRUPT_THRESHOLD,
                )
                val pct = (
                    (localSlider - VoiceCallSensitivity.SLIDER_MIN) /
                        (VoiceCallSensitivity.SLIDER_MAX - VoiceCallSensitivity.SLIDER_MIN) * 100f
                    ).roundToInt().coerceIn(0, 100)
                SettingsSliderRow(
                    label = stringResource(R.string.voice_call_settings_sensitivity_label),
                    valueLabel = "$pct%",
                    value = localSlider,
                    valueRange = VoiceCallSensitivity.SLIDER_MIN..VoiceCallSensitivity.SLIDER_MAX,
                    steps = VoiceCallSensitivity.SLIDER_STEPS,
                    defaultValue = defaultSlider,
                    startLabel = stringResource(R.string.settings_voice_interrupt_start),
                    endLabel = stringResource(R.string.settings_voice_interrupt_end),
                    onValueChange = { localSlider = it },
                    onValueChangeFinished = { onSetSliderPosition(localSlider) },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
