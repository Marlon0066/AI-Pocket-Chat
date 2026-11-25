package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.situ.aichat.ui.chat.ChatMessageDispatcher
import com.situ.aichat.ui.components.SettingsRangeStepper
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSliderRow
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 暖陶回复设置页（设置全面重构卷二）：
 * 发送等待时间 + 回复条数（范围钮）+ 创造力 + 说话方式（自然短句口吻、消息情绪动画）。
 * 语音条回复轮次已搬至独立「语音」页。
 */
@Composable
fun ReplyRuleSettingsScreen(
    onBack: () -> Unit,
    viewModel: ReplyRuleSettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val waitSeconds by viewModel.chatSendWaitSeconds.collectAsStateWithLifecycle()
    val chatModelIsThinking by viewModel.chatModelIsThinking.collectAsStateWithLifecycle()
    ReplyRuleSettingsContent(
        settings = s,
        waitSeconds = waitSeconds,
        chatModelIsThinking = chatModelIsThinking,
        onSetWaitSeconds = viewModel::setChatSendWaitSeconds,
        onSetSegmentRange = viewModel::setReplySegmentRange,
        onSetTemperature = viewModel::setLlmTemperature,
        onSetTextingTone = viewModel::setTextingToneEnabled,
        onSetEmotionAnimation = viewModel::setEmotionAnimationEnabled,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplyRuleSettingsContent(
    settings: AppSettings,
    waitSeconds: Float,
    chatModelIsThinking: Boolean,
    onSetWaitSeconds: (Float) -> Unit,
    onSetSegmentRange: (Int, Int) -> Unit,
    onSetTemperature: (Double) -> Unit,
    onSetTextingTone: (Boolean) -> Unit,
    onSetEmotionAnimation: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val seg = settings.sanitizedReplySegmentRange
    val scrollState = rememberScrollState()
    val lifted by remember { derivedStateOf { scrollState.value > 0 } }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.reply_rule_title),
                onBack = onBack,
                lifted = lifted,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .contentMaxWidth(),
        ) {
            Spacer(Modifier.height(8.dp))

            SettingsSection(
                title = stringResource(R.string.chat_send_wait_section_title),
                footer = stringResource(R.string.chat_send_wait_footer),
            ) {
                SettingsSliderRow(
                    label = stringResource(R.string.chat_send_wait_label),
                    valueLabel = stringResource(R.string.chat_send_wait_value_seconds, waitSeconds),
                    value = waitSeconds,
                    valueRange = 0.5f..5f,
                    steps = 8,
                    defaultValue = ChatMessageDispatcher.DEFAULT_WAIT_SECONDS,
                    startLabel = stringResource(R.string.settings_reply_wait_start),
                    endLabel = stringResource(R.string.settings_reply_wait_end),
                    onValueChange = { onSetWaitSeconds((it * 2).roundToInt() / 2f) },
                )
            }

            SettingsSection(
                title = stringResource(R.string.reply_rule_section_segments),
                footer = stringResource(R.string.reply_rule_segments_footer),
            ) {
                SettingsRangeStepper(
                    title = stringResource(R.string.reply_rule_current_range),
                    rangeSummary = stringResource(R.string.reply_rule_segments_range, seg.first, seg.last),
                    minValue = seg.first,
                    minRange = ReplyRuleRange.minStepperRange(seg.last, AppSettings.REPLY_SEGMENT_MIN_BOUND),
                    minValueText = stringResource(R.string.reply_rule_segments_value, seg.first),
                    onMinChange = { onSetSegmentRange(it, seg.last) },
                    maxValue = seg.last,
                    maxRange = ReplyRuleRange.maxStepperRange(seg.first, AppSettings.REPLY_SEGMENT_MAX_BOUND),
                    maxValueText = stringResource(R.string.reply_rule_segments_value, seg.last),
                    onMaxChange = { onSetSegmentRange(seg.first, it) },
                    minLabel = stringResource(R.string.reply_rule_min_segments),
                    maxLabel = stringResource(R.string.reply_rule_max_segments),
                )
            }

            SettingsSection(
                title = stringResource(R.string.reply_rule_section_creativity),
                footer = stringResource(R.string.model_temperature_footer),
            ) {
                SettingsSliderRow(
                    label = stringResource(R.string.model_temperature_label),
                    valueLabel = String.format(Locale.US, "%.1f", settings.sanitizedLlmTemperature),
                    value = settings.sanitizedLlmTemperature.toFloat(),
                    valueRange = 0f..2f,
                    steps = 19,
                    defaultValue = AppSettings.DEFAULT_LLM_TEMPERATURE.toFloat(),
                    startLabel = stringResource(R.string.settings_reply_temp_start),
                    endLabel = stringResource(R.string.settings_reply_temp_end),
                    onValueChange = { onSetTemperature((it * 10).roundToInt() / 10.0) },
                )
                if (chatModelIsThinking) {
                    Text(
                        text = stringResource(R.string.reply_rule_creativity_thinking_hint),
                        style = AppTheme.typography.settingsRowSubtitle,
                        color = AppTheme.colors.status.onWarning,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_group_chat_behavior)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_texting_tone_title),
                    subtitle = stringResource(R.string.settings_texting_tone_desc),
                    checked = settings.textingToneEnabled,
                    onCheckedChange = onSetTextingTone,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.chat_effect_emotion_title),
                    subtitle = stringResource(R.string.chat_effect_emotion_subtitle),
                    checked = settings.emotionAnimationEnabled,
                    onCheckedChange = onSetEmotionAnimation,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
