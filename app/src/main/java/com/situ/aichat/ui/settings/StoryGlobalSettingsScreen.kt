package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.story.StoryEditableField
import com.situ.aichat.story.globalValueLabel
import com.situ.aichat.ui.components.SettingsSliderRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * App 设置「故事创作」子屏（故事二期卷四·提案 §10.1 = 全局创作偏好的**唯一的家**；
 * 设置全面重构卷三 B-6 补齐「回到出厂默认」确认弹窗）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryGlobalSettingsScreen(
    onBack: () -> Unit,
    onOpenField: (String) -> Unit,
    viewModel: StoryGlobalSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isThinking by viewModel.storyModelIsThinking.collectAsStateWithLifecycle()
    var showResetConfirm by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.story_global_settings_title),
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
                .contentMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            SettingsGroupCard(stringResource(R.string.settings_group_story)) {
                SettingsSliderRow(
                    label = stringResource(R.string.story_temp_label),
                    valueLabel = String.format(Locale.US, "%.1f", settings.sanitizedStoryCreationTemperature),
                    value = settings.sanitizedStoryCreationTemperature.toFloat(),
                    valueRange = 0f..2f,
                    steps = 19,
                    onValueChange = { viewModel.setTemperature((it * 10).roundToInt() / 10.0) },
                )
                // 思考模型不吃温度：只提示，滑条不禁用（换回普通模型即生效）。
                if (isThinking) {
                    Text(
                        stringResource(R.string.story_temp_thinking_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.status.onWarning,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
                SettingsRow(
                    Icons.Filled.FilterAlt,
                    stringResource(R.string.story_global_row_banned),
                    value = stringResource(globalValueLabel(settings.storyBannedExpressions, hasFactoryDefault = true)),
                    onClick = { onOpenField(StoryEditableField.GLOBAL_BANNED_KEY) },
                )
                SettingsRow(
                    Icons.Filled.GraphicEq,
                    stringResource(R.string.story_global_beats_title),
                    value = stringResource(globalValueLabel(settings.storySceneBeats, hasFactoryDefault = true)),
                    onClick = { onOpenField(StoryEditableField.GLOBAL_SCENE_BEATS_KEY) },
                )
                SettingsRow(
                    Icons.Filled.Insights,
                    stringResource(R.string.story_global_taste_title),
                    value = stringResource(globalValueLabel(settings.storyTasteProfile, hasFactoryDefault = false)),
                    onClick = { onOpenField(StoryEditableField.GLOBAL_TASTE_PROFILE_KEY) },
                )
            }
            Text(
                stringResource(R.string.story_global_settings_footer),
                style = AppTheme.typography.secondary,
                color = AppTheme.colors.text.secondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
            AppButton(
                onClick = { showResetConfirm = true },
                style = AppButtonStyle.Tonal,
                danger = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.settings_story_reset_defaults))
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showResetConfirm) {
        AppDialog(
            onDismissRequest = { showResetConfirm = false },
            title = stringResource(R.string.settings_story_reset_confirm_title),
            body = stringResource(R.string.settings_story_reset_confirm_body),
            confirmText = stringResource(R.string.settings_story_reset_defaults),
            confirmTone = AppDialogTone.Danger,
            onConfirm = {
                showResetConfirm = false
                viewModel.resetToDefaults()
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showResetConfirm = false },
        )
    }
}
