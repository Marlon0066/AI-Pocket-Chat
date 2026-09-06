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
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Insights
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
import com.situ.aichat.story.StoryEditableField
import com.situ.aichat.story.globalValueLabel
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.StoryGlobalSettingsViewModel
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 故事全局设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 13）。与暖陶 `StoryGlobalSettingsScreen` 共用
 * [StoryGlobalSettingsViewModel]。
 *
 * 三行值标全走 [globalValueLabel]（**三态取值单源**·REDLINES §1：口味画像没有出厂默认，故第三行传
 * `hasFactoryDefault = false`——这一处传错，屏上写的就与真注入的不是一回事）。思考模型时只加一行琥珀提示，
 * **滑杆不禁用**（换回普通模型即生效·逐字照暖陶 :80）。
 */
@Composable
fun LiuliStoryGlobalSettingsScreen(
    onBack: () -> Unit,
    onOpenField: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StoryGlobalSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isThinking by viewModel.storyModelIsThinking.collectAsStateWithLifecycle()
    LiuliStoryGlobalSettingsContent(
        settings = settings,
        isThinking = isThinking,
        onSetTemperature = viewModel::setTemperature,
        onOpenField = onOpenField,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 故事全局设置页内容层（纯参数·可测）。 */
@Composable
internal fun LiuliStoryGlobalSettingsContent(
    settings: AppSettings,
    isThinking: Boolean,
    onSetTemperature: (Double) -> Unit,
    onOpenField: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.story_global_settings_title)
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
                        header = stringResource(R.string.settings_group_story),
                        // 组尾说明条：讲清「全局 vs 单本书覆盖」的分工（暖陶是组外一段文字·琉璃归组脚注）。
                        footer = stringResource(R.string.story_global_settings_footer),
                    ) {
                        LiuliSliderRow(
                            title = stringResource(R.string.story_temp_label),
                            valueLabel = String.format(Locale.US, "%.1f", settings.sanitizedStoryCreationTemperature),
                            value = settings.sanitizedStoryCreationTemperature.toFloat(),
                            valueRange = 0f..2f,
                            steps = 19,
                            divider = false,
                            onValueChange = { onSetTemperature((it * 10).roundToInt() / 10.0) },
                        )
                        // 思考模型不吃温度：只提示，滑条不禁用。
                        if (isThinking) {
                            LiuliRowBase(
                                divider = false,
                                verticalPadding = LiuliPageGeometry.rowTwoLinePad,
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    stringResource(R.string.story_temp_thinking_hint),
                                    style = AppTypography.secondary,
                                    color = AppTheme.colors.status.onWarning,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        LiuliNavRow(
                            title = stringResource(R.string.story_global_row_banned),
                            onClick = { onOpenField(StoryEditableField.GLOBAL_BANNED_KEY) },
                            icon = Icons.Filled.FilterAlt,
                            tileColor = LiuliPalette.tileStory,
                            value = stringResource(globalValueLabel(settings.storyBannedExpressions, hasFactoryDefault = true)),
                        )
                        LiuliNavRow(
                            title = stringResource(R.string.story_global_beats_title),
                            onClick = { onOpenField(StoryEditableField.GLOBAL_SCENE_BEATS_KEY) },
                            icon = Icons.Filled.GraphicEq,
                            tileColor = LiuliPalette.tileStory,
                            value = stringResource(globalValueLabel(settings.storySceneBeats, hasFactoryDefault = true)),
                        )
                        LiuliNavRow(
                            title = stringResource(R.string.story_global_taste_title),
                            onClick = { onOpenField(StoryEditableField.GLOBAL_TASTE_PROFILE_KEY) },
                            icon = Icons.Filled.Insights,
                            tileColor = LiuliPalette.tileStory,
                            // 口味画像没有出厂默认 → 值标只有「已设置 / 未设置」两种说法。
                            value = stringResource(globalValueLabel(settings.storyTasteProfile, hasFactoryDefault = false)),
                        )
                    }
                }
            }
        }
    }
}
