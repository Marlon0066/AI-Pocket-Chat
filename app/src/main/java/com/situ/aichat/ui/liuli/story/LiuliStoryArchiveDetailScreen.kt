package com.situ.aichat.ui.liuli.story

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberScrollCollapsed
import com.situ.aichat.ui.story.StoryArchiveContent
import com.situ.aichat.ui.story.StoryArchiveDetailViewModel
import com.situ.aichat.ui.story.StoryArchiveUiState
import com.situ.aichat.ui.story.rememberStoryArchiveActions
import com.situ.aichat.ui.story.storyArchiveContinueLabel
import com.situ.aichat.ui.theme.LocalIsDarkTheme

// 底部三钮（= 暖陶：横 22 · 底 28 · 分享钮 50 · 钮缝 11）；两枚玻璃钮视觉高 40 = LiuliButton。
private val ARCHIVE_ACTIONS_PAD_H = 22.dp
private val ARCHIVE_ACTIONS_BOTTOM = 28.dp
private val ARCHIVE_SHARE_HEIGHT = 50.dp
private val ARCHIVE_ACTION_GAP = 11.dp
/** 底部动作区占高 = 28 + 50 + 11 + 40 = 129。 */
private val ARCHIVE_ACTIONS_RESERVE = ARCHIVE_ACTIONS_BOTTOM + ARCHIVE_SHARE_HEIGHT + ARCHIVE_ACTION_GAP + 40.dp
/** 屏底渐进带 = 动作区 + 尾巴 12 = 141。 */
private val ARCHIVE_BOTTOM_EDGE = ARCHIVE_ACTIONS_RESERVE + LiuliPageGeometry.edgeTail
/** 内容横距（= 暖陶 24）。 */
private val ARCHIVE_CONTENT_H = 24.dp
/** 摘句卡内距：横 8（+ 摘句自带 6 = 设计稿 `.card` 横 14）· 竖 12。 */
private val QUOTE_CARD_PAD_H = 8.dp
private val QUOTE_CARD_PAD_V = 12.dp

/**
 * 琉璃结局档案（琉璃 2.0 卷六·三·上 §4.4·设计稿 S7）：与暖陶 [com.situ.aichat.ui.story.StoryArchiveDetailScreen] 共用同一个 VM、
 * 分享 / 导出 / 继续写动作件与档案内容件；左上玻璃 ✕、滚过收成胶囊（标题 = 书名）、摘句进半透明卡，
 * 底部一枚主色「生成分享长图」+ 一行两枚玻璃钮（导出 / 继续写）+ 屏底渐进带。
 */
@Composable
internal fun LiuliStoryArchiveDetailScreen(
    onBack: () -> Unit,
    viewModel: StoryArchiveDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val actions = rememberStoryArchiveActions(viewModel, state, onBack)

    LiuliStoryArchiveDetailPage(state, onClose = onBack, onShare = actions.share, onExport = actions.export, onContinueWriting = viewModel::continueWriting)

    // 「继续写」失败提示（断网 / 无 key 等）——绝不让异常穿透闪退（同暖陶）。
    error?.let { LiuliStoryAlert(stringResource(R.string.story_alert_error_title), it, viewModel::dismissError) }
}

/** 无 VM 的结局档案页（测试直接驱动它）。 */
@Composable
internal fun LiuliStoryArchiveDetailPage(
    state: StoryArchiveUiState?,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onContinueWriting: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val dark = LocalIsDarkTheme.current
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(
        title = state?.story?.title.orEmpty(),
        onBack = onClose,
        collapsed = rememberScrollCollapsed(scrollState) && state != null,
        leading = { LiuliPageCircleAction(onClick = onClose, contentDescription = stringResource(R.string.action_close), icon = AppActionIcons.Close) },
        bottomBar = if (state != null) {
            { LiuliStoryArchiveActions(onShare, onExport, onContinueWriting) }
        } else {
            null
        },
        bottomEdge = if (state != null) ARCHIVE_BOTTOM_EDGE else null,
    ) {
        state?.let { s ->
            StoryArchiveContent(
                s.story,
                s.digest,
                Modifier.fillMaxSize(),
                scrollState = scrollState,
                contentPadding = PaddingValues(
                    start = ARCHIVE_CONTENT_H,
                    end = ARCHIVE_CONTENT_H,
                    top = LiuliPageGeometry.navRow + LiuliPageGeometry.titleGap,
                    bottom = ARCHIVE_ACTIONS_RESERVE + LiuliPageGeometry.pageBottom + navBarBottom,
                ),
                quoteSurface = Modifier.fillMaxWidth().liuliStoryCard(dark).padding(horizontal = QUOTE_CARD_PAD_H, vertical = QUOTE_CARD_PAD_V),
            )
        }
    }
}

/** 底部动作：主色分享长图 + 一行两枚玻璃钮（窄屏英文会折两行·登记 E37）。 */
@Composable
private fun LiuliStoryArchiveActions(onShare: () -> Unit, onExport: () -> Unit, onContinueWriting: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = ARCHIVE_ACTIONS_PAD_H, end = ARCHIVE_ACTIONS_PAD_H, bottom = ARCHIVE_ACTIONS_BOTTOM),
        verticalArrangement = Arrangement.spacedBy(ARCHIVE_ACTION_GAP),
    ) {
        LiuliButton(onShare, style = LiuliButtonStyle.Prominent, modifier = Modifier.fillMaxWidth().height(ARCHIVE_SHARE_HEIGHT)) {
            Text(stringResource(R.string.story_archive_share))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ARCHIVE_ACTION_GAP)) {
            LiuliButton(onExport, style = LiuliButtonStyle.Glass, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.story_archive_export_txt)) }
            LiuliButton(onContinueWriting, style = LiuliButtonStyle.Glass, modifier = Modifier.weight(1f)) { Text(storyArchiveContinueLabel()) }
        }
    }
}
