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
import com.situ.aichat.ui.chat.ChatMessageDispatcher
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRangeStepper
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.ReplyRuleRange
import com.situ.aichat.ui.settings.ReplyRuleSettingsViewModel
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 琉璃 2.0 回复设置页（设置全面重构卷二）：
 * 与暖陶同步——合并等待时间 + 回复条数范围钮 + 创造力 + 说话方式（短句口吻 / 情绪动画）。
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
        onSetTemperature = viewModel::setLlmTemperature,
        onSetTextingTone = viewModel::setTextingToneEnabled,
        onSetEmotionAnimation = viewModel::setEmotionAnimationEnabled,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun LiuliReplyRuleContent(
    settings: AppSettings,
    waitSeconds: Float,
    chatModelIsThinking: Boolean,
    onSetWaitSeconds: (Float) -> Unit,
    onSetSegmentRange: (Int, Int) -> Unit,
    onSetTemperature: (Double) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSetTextingTone: (Boolean) -> Unit = {},
    onSetEmotionAnimation: (Boolean) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.reply_rule_title)
    val seg = settings.sanitizedReplySegmentRange
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
                    LiuliGroup(
                        header = stringResource(R.string.chat_send_wait_section_title),
                        footer = stringResource(R.string.chat_send_wait_footer),
                    ) {
                        LiuliSliderRow(
                            title = stringResource(R.string.chat_send_wait_label),
                            valueLabel = stringResource(R.string.chat_send_wait_value_seconds, waitSeconds),
                            value = waitSeconds,
                            valueRange = 0.5f..5f,
                            steps = 8,
                            defaultValue = ChatMessageDispatcher.DEFAULT_WAIT_SECONDS,
                            startLabel = stringResource(R.string.settings_reply_wait_start),
                            endLabel = stringResource(R.string.settings_reply_wait_end),
                            divider = false,
                            onValueChange = { onSetWaitSeconds((it * 2).roundToInt() / 2f) },
                        )
                    }

                    LiuliGroup(
                        header = stringResource(R.string.reply_rule_section_segments),
                        footer = stringResource(R.string.reply_rule_segments_footer),
                    ) {
                        LiuliRangeStepper(
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
                            divider = false,
                        )
                    }

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
                            defaultValue = AppSettings.DEFAULT_LLM_TEMPERATURE.toFloat(),
                            startLabel = stringResource(R.string.settings_reply_temp_start),
                            endLabel = stringResource(R.string.settings_reply_temp_end),
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

                    LiuliGroup(header = stringResource(R.string.settings_group_chat_behavior)) {
                        LiuliToggleRow(
                            title = stringResource(R.string.settings_texting_tone_title),
                            subtitle = stringResource(R.string.settings_texting_tone_desc),
                            checked = settings.textingToneEnabled,
                            onCheckedChange = onSetTextingTone,
                            divider = false,
                        )
                        LiuliToggleRow(
                            title = stringResource(R.string.chat_effect_emotion_title),
                            subtitle = stringResource(R.string.chat_effect_emotion_subtitle),
                            checked = settings.emotionAnimationEnabled,
                            onCheckedChange = onSetEmotionAnimation,
                        )
                    }
                }
            }
        }
    }
}
