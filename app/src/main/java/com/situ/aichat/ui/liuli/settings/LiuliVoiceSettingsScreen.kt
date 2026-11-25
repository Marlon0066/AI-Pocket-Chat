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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRangeStepper
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.ReplyRuleRange
import com.situ.aichat.ui.settings.VoiceCallSensitivity
import com.situ.aichat.ui.settings.VoiceSettingsViewModel
import kotlin.math.roundToInt

/** 琉璃 2.0「语音」统一页（设置全面重构卷二）。 */
@Composable
fun LiuliVoiceSettingsScreen(
    onBack: () -> Unit,
    onOpenTtsConfig: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VoiceSettingsViewModel = hiltViewModel(),
) {
    val providerName by viewModel.ttsProviderName.collectAsStateWithLifecycle()
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val storedSlider by viewModel.sliderPosition.collectAsStateWithLifecycle()
    LiuliVoiceSettingsContent(
        ttsProviderName = providerName,
        settings = settings,
        storedSlider = storedSlider,
        onOpenTtsConfig = onOpenTtsConfig,
        onSetVoiceRoundRange = viewModel::setVoiceReplyRoundRange,
        onSetSliderPosition = viewModel::setSliderPosition,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun LiuliVoiceSettingsContent(
    ttsProviderName: String,
    settings: AppSettings,
    storedSlider: Float,
    onOpenTtsConfig: () -> Unit,
    onSetVoiceRoundRange: (Int, Int) -> Unit,
    onSetSliderPosition: (Float) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.settings_hub_voice)
    val voice = settings.sanitizedVoiceReplyRoundRange
    var localSlider by remember(storedSlider) { mutableFloatStateOf(storedSlider) }
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
                    LiuliGroup(header = stringResource(R.string.settings_voice_service_row)) {
                        LiuliNavRow(
                            title = stringResource(R.string.settings_voice_service_row),
                            subtitle = stringResource(R.string.settings_voice_service_sub),
                            value = ttsProviderName,
                            onClick = onOpenTtsConfig,
                            icon = Icons.Filled.GraphicEq,
                            tileColor = LiuliTileTone.Sky,
                            divider = false,
                        )
                    }

                    LiuliGroup(
                        header = stringResource(R.string.reply_rule_section_voice),
                        footer = stringResource(R.string.reply_rule_voice_footer),
                    ) {
                        LiuliRangeStepper(
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
                            divider = false,
                        )
                    }

                    LiuliGroup(
                        header = stringResource(R.string.voice_call_settings_title),
                        footer = stringResource(R.string.voice_call_settings_footer),
                    ) {
                        val defaultSlider = VoiceCallSensitivity.sliderFromThreshold(
                            AppSettings.DEFAULT_VOICE_CALL_INTERRUPT_THRESHOLD,
                        )
                        val pct = (
                            (localSlider - VoiceCallSensitivity.SLIDER_MIN) /
                                (VoiceCallSensitivity.SLIDER_MAX - VoiceCallSensitivity.SLIDER_MIN) * 100f
                            ).roundToInt().coerceIn(0, 100)
                        LiuliSliderRow(
                            title = stringResource(R.string.voice_call_settings_sensitivity_label),
                            valueLabel = "$pct%",
                            value = localSlider,
                            valueRange = VoiceCallSensitivity.SLIDER_MIN..VoiceCallSensitivity.SLIDER_MAX,
                            steps = VoiceCallSensitivity.SLIDER_STEPS,
                            defaultValue = defaultSlider,
                            startLabel = stringResource(R.string.settings_voice_interrupt_start),
                            endLabel = stringResource(R.string.settings_voice_interrupt_end),
                            divider = false,
                            onValueChange = { localSlider = it },
                            onValueChangeFinished = { onSetSliderPosition(localSlider) },
                        )
                    }
                }
            }
        }
    }
}
