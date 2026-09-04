package com.situ.aichat.ui.moments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.designsystem.AppSlider
import com.situ.aichat.ui.designsystem.AppTopBar
import kotlin.math.roundToInt

/**
 * 朋友圈设置页（M06 7.2.8，对齐 iOS `MomentSettingsView`）：每日自动发帖(0~5) / 自动评论上限(0~3) /
 * 评论延迟(1~10min) 滑块 + 自动点赞开关。改动即持久化（VM→DataStore），下次发帖/互动读取生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentSettingsScreen(
    onBack: () -> Unit,
    viewModel: MomentSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offLabel = stringResource(R.string.moment_settings_off)

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.moment_settings_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(vertical = 8.dp),
        ) {
            // AI 互动频率：说明文字改传 footer（V-e·文案不变·位置从组头下方移至卡下脚注位）。
            SettingsSection(
                title = stringResource(R.string.moment_settings_freq_header),
                footer = stringResource(R.string.moment_settings_freq_footer),
            ) {
                SliderRow(
                    label = stringResource(R.string.moment_settings_auto_post),
                    valueLabel = if (state.autoPostFrequency == 0) offLabel else stringResource(R.string.moment_settings_posts_unit, state.autoPostFrequency),
                    value = state.autoPostFrequency,
                    range = 0f..5f,
                    steps = 4,
                    onChange = viewModel::setAutoPostFrequency,
                )
                SliderRow(
                    label = stringResource(R.string.moment_settings_auto_comment),
                    valueLabel = if (state.autoCommentFrequency == 0) offLabel else stringResource(R.string.moment_settings_comments_unit, state.autoCommentFrequency),
                    value = state.autoCommentFrequency,
                    range = 0f..3f,
                    steps = 2,
                    onChange = viewModel::setAutoCommentFrequency,
                )
                SliderRow(
                    label = stringResource(R.string.moment_settings_comment_delay),
                    valueLabel = stringResource(R.string.moment_settings_delay_unit, state.commentDelay),
                    value = state.commentDelay,
                    range = 1f..10f,
                    steps = 8,
                    onChange = viewModel::setCommentDelay,
                )
            }

            SettingsSection(title = stringResource(R.string.moment_settings_behavior_header)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.moment_settings_auto_like),
                    checked = state.autoLikeEnabled,
                    onCheckedChange = viewModel::setAutoLikeEnabled,
                )
                // 13.7e：「X 发了新动态」系统通知开关（默认开，仅后台周期发的帖推、每角色每天≤1、多角色合并）。
                SettingsSwitchRow(
                    title = stringResource(R.string.moment_settings_new_post_notif),
                    subtitle = stringResource(R.string.moment_settings_new_post_notif_desc),
                    checked = state.newPostNotificationEnabled,
                    onCheckedChange = viewModel::setNewPostNotificationEnabled,
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Int) -> Unit,
) {
    // 卡内行水平 16（§4.0-3·私有行对齐军规）；行内布局零改。
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppSlider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range,
            steps = steps,
        )
    }
}
