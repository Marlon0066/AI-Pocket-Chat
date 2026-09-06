package com.situ.aichat.ui.liuli.moments

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.moments.MomentSettingsState
import com.situ.aichat.ui.moments.MomentSettingsViewModel
import kotlin.math.roundToInt

/**
 * 朋友圈设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 17）。与暖陶 `MomentSettingsScreen` 共用
 * [MomentSettingsViewModel]。三枚滑杆的值域 / 步数 / 「0 = 关」的换词逐字继承。
 */
@Composable
fun LiuliMomentSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MomentSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiuliMomentSettingsContent(
        state = state,
        onSetAutoPost = viewModel::setAutoPostFrequency,
        onSetAutoComment = viewModel::setAutoCommentFrequency,
        onSetCommentDelay = viewModel::setCommentDelay,
        onSetAutoLike = viewModel::setAutoLikeEnabled,
        onSetNewPostNotification = viewModel::setNewPostNotificationEnabled,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 朋友圈设置页内容层（纯参数·可测）。 */
@Composable
internal fun LiuliMomentSettingsContent(
    state: MomentSettingsState,
    onSetAutoPost: (Int) -> Unit,
    onSetAutoComment: (Int) -> Unit,
    onSetCommentDelay: (Int) -> Unit,
    onSetAutoLike: (Boolean) -> Unit,
    onSetNewPostNotification: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.moment_settings_title)
    val offLabel = stringResource(R.string.moment_settings_off)
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
                        header = stringResource(R.string.moment_settings_freq_header),
                        footer = stringResource(R.string.moment_settings_freq_footer),
                    ) {
                        LiuliSliderRow(
                            title = stringResource(R.string.moment_settings_auto_post),
                            valueLabel = if (state.autoPostFrequency == 0) {
                                offLabel
                            } else {
                                stringResource(R.string.moment_settings_posts_unit, state.autoPostFrequency)
                            },
                            value = state.autoPostFrequency.toFloat(),
                            valueRange = 0f..5f,
                            steps = 4,
                            divider = false,
                            onValueChange = { onSetAutoPost(it.roundToInt()) },
                        )
                        LiuliSliderRow(
                            title = stringResource(R.string.moment_settings_auto_comment),
                            valueLabel = if (state.autoCommentFrequency == 0) {
                                offLabel
                            } else {
                                stringResource(R.string.moment_settings_comments_unit, state.autoCommentFrequency)
                            },
                            value = state.autoCommentFrequency.toFloat(),
                            valueRange = 0f..3f,
                            steps = 2,
                            onValueChange = { onSetAutoComment(it.roundToInt()) },
                        )
                        LiuliSliderRow(
                            title = stringResource(R.string.moment_settings_comment_delay),
                            valueLabel = stringResource(R.string.moment_settings_delay_unit, state.commentDelay),
                            value = state.commentDelay.toFloat(),
                            valueRange = 1f..10f,
                            steps = 8,
                            onValueChange = { onSetCommentDelay(it.roundToInt()) },
                        )
                    }
                    LiuliGroup(header = stringResource(R.string.moment_settings_behavior_header)) {
                        LiuliToggleRow(
                            title = stringResource(R.string.moment_settings_auto_like),
                            checked = state.autoLikeEnabled,
                            onCheckedChange = onSetAutoLike,
                            divider = false,
                        )
                        // 13.7e：「X 发了新动态」系统通知开关（默认开·每角色每天 ≤ 1·多角色合并）。
                        LiuliToggleRow(
                            title = stringResource(R.string.moment_settings_new_post_notif),
                            subtitle = stringResource(R.string.moment_settings_new_post_notif_desc),
                            checked = state.newPostNotificationEnabled,
                            onCheckedChange = onSetNewPostNotification,
                        )
                    }
                }
            }
        }
    }
}
