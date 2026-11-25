package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.story.StoryEditableField
import com.situ.aichat.story.globalValueLabel
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
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
 * 故事全局设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 13；设置全面重构卷三 B-6 补齐「回到出厂默认」）。
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
        onResetDefaults = viewModel::resetToDefaults,
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
    onResetDefaults: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.story_global_settings_title)
    var showResetConfirm by remember { mutableStateOf(false) }
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
                            value = stringResource(globalValueLabel(settings.storyTasteProfile, hasFactoryDefault = false)),
                        )
                    }

                    LiuliGroup {
                        LiuliRowBase(
                            modifier = Modifier.clickable(role = Role.Button) { showResetConfirm = true },
                            divider = false,
                        ) {
                            Text(
                                stringResource(R.string.settings_story_reset_defaults),
                                style = AppTypography.body,
                                color = AppTheme.colors.status.onError,
                            )
                        }
                    }
                }
            }
        }

        if (showResetConfirm) {
            LiuliDialog(
                onDismissRequest = { showResetConfirm = false },
                title = stringResource(R.string.settings_story_reset_confirm_title),
                body = stringResource(R.string.settings_story_reset_confirm_body),
                confirmText = stringResource(R.string.settings_story_reset_defaults),
                confirmDanger = true,
                onConfirm = {
                    showResetConfirm = false
                    onResetDefaults()
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { showResetConfirm = false },
            )
        }
    }
}
