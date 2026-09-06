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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.LiuliStepperRow
import com.situ.aichat.ui.liuli.page.LiuliValueRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.ReplyRuleRange
import com.situ.aichat.ui.settings.ReplyRuleSettingsViewModel
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 回复规则设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 4·A-4 ① 步进器首用）。与暖陶
 * `ReplyRuleSettingsScreen` 共用 [ReplyRuleSettingsViewModel]。
 *
 * 四枚步进器的互钳范围走暖陶 [ReplyRuleRange]（internal 纯函数·直接 import，不复制）：最少值上界 =
 * 最多值 − 1、最多值下界 = 最少值 + 1，各端再受 `AppSettings` 的 bounds 限制。
 *
 * 「当前范围」那一行在暖陶是 `CurrentRangeRow`（标题 + 右值·不可点），琉璃对应 [LiuliValueRow]（`onClick = null`）。
 */
@Composable
fun LiuliReplyRuleScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReplyRuleSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val waitSeconds by viewModel.chatSendWaitSeconds.collectAsStateWithLifecycle()
    val chatModelIsThinking by viewModel.chatModelIsThinking.collectAsStateWithLifecycle()
    LiuliReplyRuleContent(
        settings = settings,
        waitSeconds = waitSeconds,
        chatModelIsThinking = chatModelIsThinking,
        onSetWaitSeconds = viewModel::setChatSendWaitSeconds,
        onSetSegmentRange = viewModel::setReplySegmentRange,
        onSetVoiceRoundRange = viewModel::setVoiceReplyRoundRange,
        onSetTemperature = viewModel::setLlmTemperature,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 回复规则页内容层（纯参数·可测）。 */
@Composable
internal fun LiuliReplyRuleContent(
    settings: AppSettings,
    waitSeconds: Float,
    chatModelIsThinking: Boolean,
    onSetWaitSeconds: (Float) -> Unit,
    onSetSegmentRange: (Int, Int) -> Unit,
    onSetVoiceRoundRange: (Int, Int) -> Unit,
    onSetTemperature: (Double) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.reply_rule_title)
    val seg = settings.sanitizedReplySegmentRange
    val voice = settings.sanitizedVoiceReplyRoundRange
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
                    // 合并等待窗（输入排契约 §3.2-11）：发送后等待期内连发合并、停手才答。
                    LiuliGroup(
                        header = stringResource(R.string.chat_send_wait_section_title),
                        footer = stringResource(R.string.chat_send_wait_footer),
                    ) {
                        LiuliSliderRow(
                            title = stringResource(R.string.chat_send_wait_label),
                            valueLabel = stringResource(R.string.chat_send_wait_value_seconds, waitSeconds),
                            value = waitSeconds,
                            valueRange = 0.5f..5f,
                            steps = 8, // 0.5–5.0 共 10 档 → 8 个中间步（步进 0.5）
                            divider = false,
                            onValueChange = { onSetWaitSeconds((it * 2).roundToInt() / 2f) },
                        )
                    }

                    // 回复条数（文字消息拆条）。
                    LiuliGroup(
                        header = stringResource(R.string.reply_rule_section_segments),
                        footer = stringResource(R.string.reply_rule_segments_footer),
                    ) {
                        LiuliValueRow(
                            title = stringResource(R.string.reply_rule_current_range),
                            value = stringResource(R.string.reply_rule_segments_range, seg.first, seg.last),
                            divider = false,
                        )
                        LiuliStepperRow(
                            title = stringResource(R.string.reply_rule_min_segments),
                            value = seg.first,
                            range = ReplyRuleRange.minStepperRange(seg.last, AppSettings.REPLY_SEGMENT_MIN_BOUND),
                            valueText = stringResource(R.string.reply_rule_segments_value, seg.first),
                            onChange = { onSetSegmentRange(it, seg.last) },
                        )
                        LiuliStepperRow(
                            title = stringResource(R.string.reply_rule_max_segments),
                            value = seg.last,
                            range = ReplyRuleRange.maxStepperRange(seg.first, AppSettings.REPLY_SEGMENT_MAX_BOUND),
                            valueText = stringResource(R.string.reply_rule_segments_value, seg.last),
                            onChange = { onSetSegmentRange(seg.first, it) },
                        )
                    }

                    // 语音条回复轮次。
                    LiuliGroup(
                        header = stringResource(R.string.reply_rule_section_voice),
                        footer = stringResource(R.string.reply_rule_voice_footer),
                    ) {
                        LiuliValueRow(
                            title = stringResource(R.string.reply_rule_current_range),
                            value = stringResource(R.string.reply_rule_rounds_range, voice.first, voice.last),
                            divider = false,
                        )
                        LiuliStepperRow(
                            title = stringResource(R.string.reply_rule_min_rounds),
                            value = voice.first,
                            range = ReplyRuleRange.minStepperRange(voice.last, AppSettings.VOICE_REPLY_ROUND_MIN_BOUND),
                            valueText = stringResource(R.string.reply_rule_rounds_value, voice.first),
                            onChange = { onSetVoiceRoundRange(it, voice.last) },
                        )
                        LiuliStepperRow(
                            title = stringResource(R.string.reply_rule_max_rounds),
                            value = voice.last,
                            range = ReplyRuleRange.maxStepperRange(voice.first, AppSettings.VOICE_REPLY_ROUND_MAX_BOUND),
                            valueText = stringResource(R.string.reply_rule_rounds_value, voice.last),
                            onChange = { onSetVoiceRoundRange(voice.first, it) },
                        )
                    }

                    // 回复创造力（温度）：D-3 聊天功能解析为思考模型时追加琥珀提示行——滑杆**不禁用**，
                    // 值仍保存，换回普通模型即生效（逐字照暖陶 :150）。
                    LiuliGroup(
                        header = stringResource(R.string.reply_rule_section_creativity),
                        footer = stringResource(R.string.model_temperature_footer),
                    ) {
                        LiuliSliderRow(
                            title = stringResource(R.string.model_temperature_label),
                            valueLabel = String.format(Locale.US, "%.1f", settings.sanitizedLlmTemperature),
                            value = settings.sanitizedLlmTemperature.toFloat(),
                            valueRange = 0f..2f,
                            steps = 19,
                            divider = false,
                            onValueChange = { onSetTemperature((it * 10).roundToInt() / 10.0) },
                        )
                        if (chatModelIsThinking) {
                            LiuliRowBase(verticalPadding = LiuliPageGeometry.rowTwoLinePad, verticalAlignment = Alignment.Top) {
                                Text(
                                    stringResource(R.string.reply_rule_creativity_thinking_hint),
                                    style = AppTypography.secondary,
                                    color = AppTheme.colors.status.onWarning,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
