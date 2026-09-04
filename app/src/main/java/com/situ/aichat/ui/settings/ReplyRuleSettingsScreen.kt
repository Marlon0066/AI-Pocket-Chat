package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSliderRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppStepper
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import kotlin.math.roundToInt

/**
 * 回复规则设置页（14.3a）。回复段数范围 + 语音条回复轮次范围，各 min/max 步进器互钳（1:1 iOS
 * `ReplySegmentRangeSettingsView` / `VoiceReplyRoundRangeSettingsView` 双 Stepper）。后端字段已建并被
 * ChatViewModel 消费，改值即时持久化生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyRuleSettingsScreen(
    onBack: () -> Unit,
    viewModel: ReplyRuleSettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val waitSeconds by viewModel.chatSendWaitSeconds.collectAsStateWithLifecycle()
    val seg = s.sanitizedReplySegmentRange
    val voice = s.sanitizedVoiceReplyRoundRange

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.reply_rule_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
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

            // C2（输入排契约 §3.2-11）：合并等待窗——发送后等待期内连发合并、停手才答（0.5–5s 步 0.5·
            // 说明文案沿 iOS DebounceSettingsView；自适应后台改值经 VM Flow 实时回显）。
            SettingsSection(
                title = stringResource(R.string.chat_send_wait_section_title),
                footer = stringResource(R.string.chat_send_wait_footer),
            ) {
                SettingsSliderRow(
                    label = stringResource(R.string.chat_send_wait_label),
                    valueLabel = stringResource(R.string.chat_send_wait_value_seconds, waitSeconds),
                    value = waitSeconds,
                    valueRange = 0.5f..5f,
                    steps = 8, // 0.5–5.0 共 10 档 → 8 个中间步（步进 0.5）
                    onValueChange = { viewModel.setChatSendWaitSeconds((it * 2).roundToInt() / 2f) },
                )
            }

            Spacer(Modifier.height(8.dp))

            // 回复条数（文字消息拆条）。
            SettingsSection(
                title = stringResource(R.string.reply_rule_section_segments),
                footer = stringResource(R.string.reply_rule_segments_footer),
            ) {
                CurrentRangeRow(stringResource(R.string.reply_rule_segments_range, seg.first, seg.last))
                StepperRow(
                    label = stringResource(R.string.reply_rule_min_segments),
                    valueText = stringResource(R.string.reply_rule_segments_value, seg.first),
                    value = seg.first,
                    range = ReplyRuleRange.minStepperRange(seg.last, AppSettings.REPLY_SEGMENT_MIN_BOUND),
                    onChange = { viewModel.setReplySegmentRange(it, seg.last) },
                )
                StepperRow(
                    label = stringResource(R.string.reply_rule_max_segments),
                    valueText = stringResource(R.string.reply_rule_segments_value, seg.last),
                    value = seg.last,
                    range = ReplyRuleRange.maxStepperRange(seg.first, AppSettings.REPLY_SEGMENT_MAX_BOUND),
                    onChange = { viewModel.setReplySegmentRange(seg.first, it) },
                )
            }

            Spacer(Modifier.height(8.dp))

            // 语音条回复轮次。
            SettingsSection(
                title = stringResource(R.string.reply_rule_section_voice),
                footer = stringResource(R.string.reply_rule_voice_footer),
            ) {
                CurrentRangeRow(stringResource(R.string.reply_rule_rounds_range, voice.first, voice.last))
                StepperRow(
                    label = stringResource(R.string.reply_rule_min_rounds),
                    valueText = stringResource(R.string.reply_rule_rounds_value, voice.first),
                    value = voice.first,
                    range = ReplyRuleRange.minStepperRange(voice.last, AppSettings.VOICE_REPLY_ROUND_MIN_BOUND),
                    onChange = { viewModel.setVoiceReplyRoundRange(it, voice.last) },
                )
                StepperRow(
                    label = stringResource(R.string.reply_rule_max_rounds),
                    valueText = stringResource(R.string.reply_rule_rounds_value, voice.last),
                    value = voice.last,
                    range = ReplyRuleRange.maxStepperRange(voice.first, AppSettings.VOICE_REPLY_ROUND_MAX_BOUND),
                    onChange = { viewModel.setVoiceReplyRoundRange(voice.first, it) },
                )
            }

            Spacer(Modifier.height(8.dp))

            // 回复创造力（温度）——2026-07-11 搬自记忆页（CREATIVITY_RELOCATION D-1）：滑块参数原样只搬不改；
            // D-3 聊天功能解析为思考模型时追加琥珀提示行（滑块不禁用，值仍保存，换回普通模型即生效）。
            val chatModelIsThinking by viewModel.chatModelIsThinking.collectAsStateWithLifecycle()
            SettingsSection(
                title = stringResource(R.string.reply_rule_section_creativity),
                footer = stringResource(R.string.model_temperature_footer),
            ) {
                SettingsSliderRow(
                    label = stringResource(R.string.model_temperature_label),
                    valueLabel = String.format(java.util.Locale.US, "%.1f", s.sanitizedLlmTemperature),
                    value = s.sanitizedLlmTemperature.toFloat(),
                    valueRange = 0f..2f,
                    steps = 19,
                    onValueChange = { viewModel.setLlmTemperature((it * 10).roundToInt() / 10.0) },
                )
                if (chatModelIsThinking) {
                    Text(
                        stringResource(R.string.reply_rule_creativity_thinking_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.status.onWarning,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CurrentRangeRow(rangeText: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.reply_rule_current_range), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            rangeText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 步进器行：标题 + 凹槽药丸步进器（方案 B·到边界禁用·功能逐字冻结·见 [AppStepper]）。 */
@Composable
private fun StepperRow(
    label: String,
    valueText: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        AppStepper(
            value = value,
            valueText = valueText,
            range = range,
            onValueChange = onChange,
            decreaseDescription = stringResource(R.string.reply_rule_decrease),
            increaseDescription = stringResource(R.string.reply_rule_increase),
        )
    }
}
