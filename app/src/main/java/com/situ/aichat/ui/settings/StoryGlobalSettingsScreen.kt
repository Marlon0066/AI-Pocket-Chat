package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * App 设置「故事创作」子屏（故事二期卷四·提案 §10.1 = 全局创作偏好的**唯一的家**·mockup 屏 6 上半）。
 *
 * 四行：创作温度滑条 / 全局文字忌口 / 全局场面节拍 / 全局口味画像
 * （段序实验开关随 2026-08-03「B 序固化」整链拆除）。后三行点开进统一编辑页的全局哨兵变体（`storyFieldEditor/-/<哨兵键>`——storyId 段是占位符，
 * 全局分支根本不读它）。**存储与语义逐字节沿用书页「全局（暂驻）」组**，本屏只是换了个家。
 *
 * 长相复用同包的 [SettingsGroupCard] / [SettingsRow]（卷四把它们由 private 提到 internal，实现一字未动）。
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
                    // 口味画像没有出厂默认 → 值标只有「已设置 / 未设置」两种说法。
                    value = stringResource(globalValueLabel(settings.storyTasteProfile, hasFactoryDefault = false)),
                    onClick = { onOpenField(StoryEditableField.GLOBAL_TASTE_PROFILE_KEY) },
                )
            }
            // 组尾说明条（mockup 屏6 notestrip·R1 补齐）：讲清「全局 vs 单本书覆盖」的分工，组脚注惯例同书页 SettingsGroup.footer。
            Text(
                stringResource(R.string.story_global_settings_footer),
                style = AppTheme.typography.secondary,
                color = AppTheme.colors.text.secondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}
