package com.situ.aichat.ui.liuli.contextlog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.diagnostics.LogShareFormat
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.contextlog.ContextLogEntryUiState
import com.situ.aichat.ui.contextlog.ContextLogEntryViewModel
import com.situ.aichat.ui.contextlog.LEGACY_CLIP_MARKER
import com.situ.aichat.ui.contextlog.LogNoticeTone
import com.situ.aichat.ui.contextlog.LogPillTone
import com.situ.aichat.ui.contextlog.LogShareActions
import com.situ.aichat.ui.contextlog.model.LogCardPos
import com.situ.aichat.ui.contextlog.model.LogFormat
import com.situ.aichat.ui.contextlog.model.LogSentMessage
import com.situ.aichat.ui.contextlog.model.LogSentRole
import com.situ.aichat.ui.contextlog.shared.LogReaderHeaderRow
import com.situ.aichat.ui.contextlog.shared.readerCharacterName
import com.situ.aichat.ui.contextlog.shared.sentCaptionText
import com.situ.aichat.ui.contextlog.splitLogTextBlocks
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/** 回复全文（四期·图纸五 §4.8·琉璃）：内容 / 顺序 / 文案同暖陶 `ContextLogReplyScreen`，外壳 = 大标题页 + 分段卡。 */
@Composable
fun LiuliContextLogReplyScreen(onBack: () -> Unit, viewModel: ContextLogEntryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LiuliContextLogReplyContent(state, onBack) { t ->
        state.entry?.let { e ->
            scope.launch(Dispatchers.Default) { LogShareActions.copyOrExport(context, t, LogShareFormat.exportFileName(e.source, e.timestampMillis)) }
        }
    }
}

@Composable
internal fun LiuliContextLogReplyContent(state: ContextLogEntryUiState, onBack: () -> Unit, onCopy: (String) -> Unit) {
    val listState = rememberLazyListState()
    val text = state.entry?.responseContent.orEmpty()
    val blocks = remember(text) { splitLogTextBlocks(text) }
    val title = stringResource(R.string.clog_reply_full)
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        actions = { if (text.isNotEmpty()) LiuliPageCircleAction({ onCopy(text) }, stringResource(R.string.clog_reader_copy_all), AppActionIcons.Copy) },
    ) {
        SelectionContainer(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().contentMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
            ) {
                item(key = "large-title") { LiuliLargeTitle(title) }
                if (!state.loaded) return@LazyColumn
                val e = state.entry
                if (e == null) {
                    item(key = "missing") { LiuliLogEmpty(stringResource(R.string.clog_entry_missing)) }
                    return@LazyColumn
                }
                item(key = "caption") { LiuliLogCaption(sentCaptionText(e, state.providerLabel)) }
                if (text.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.clog_reply_empty), style = AppTypography.secondary, color = AppTheme.colors.text.secondary,
                            modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter),
                        )
                    }
                } else {
                    item(key = "header") {
                        LiuliReaderRowShell(LogCardPos.TOP, null, divider = false, gapTop = false, gapBottom = false) {
                            LogReaderHeaderRow(LogSentMessage(1, LogSentRole.ASSISTANT, text.length, emptyList()), { LiuliLogPill(readerCharacterName(e), LogPillTone.NEUTRAL) }) { onCopy(text) }
                        }
                    }
                    itemsIndexed(blocks) { i, block ->
                        val last = i == blocks.lastIndex
                        LiuliReaderRowShell(if (last) LogCardPos.BOTTOM else LogCardPos.MIDDLE, null, divider = false, gapTop = false, gapBottom = last) {
                            Text(block, style = AppTypography.secondary, color = AppTheme.colors.text.primary)
                        }
                    }
                }
                if (e.reasoningTokens > 0) {
                    item(key = "reasoning") {
                        Box(Modifier.padding(top = AppSpacing.m)) {
                            LiuliLogNotice(LogNoticeTone.INFO) {
                                Text(
                                    stringResource(R.string.clog_reply_reasoning, LogFormat.grouped(e.reasoningTokens.toLong())),
                                    style = AppTypography.secondary, color = AppTheme.colors.status.onInfo,
                                )
                            }
                        }
                    }
                }
                if (text.isNotEmpty()) {
                    item(key = "footer") {
                        val chars = remember(text) { String.format(Locale.ROOT, "%,d", text.length) }
                        val clipped = remember(text) { text.contains(LEGACY_CLIP_MARKER) }
                        Text(
                            stringResource(if (clipped) R.string.contextlog_viewer_chars_truncated else R.string.contextlog_viewer_chars, chars),
                            style = AppTypography.caption, color = AppTheme.colors.text.secondary, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
