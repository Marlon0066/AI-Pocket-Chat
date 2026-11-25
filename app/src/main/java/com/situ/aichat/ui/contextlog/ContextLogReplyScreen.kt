package com.situ.aichat.ui.contextlog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Scaffold
import com.situ.aichat.ui.designsystem.AppActionIcons
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
import com.situ.aichat.ui.contextlog.model.LogCardPos
import com.situ.aichat.ui.contextlog.model.LogFormat
import com.situ.aichat.ui.contextlog.model.LogSentMessage
import com.situ.aichat.ui.contextlog.model.LogSentRole
import com.situ.aichat.ui.contextlog.shared.LogReaderHeaderRow
import com.situ.aichat.ui.contextlog.shared.readerCharacterName
import com.situ.aichat.ui.contextlog.shared.sentCaptionText
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTopBarAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 回复全文（四期·图纸五 §4.8·暖陶·取代原「全文」页）：与阅读器同一副外壳——说明行 → 一张消息卡（徽标 / 字数 / 复制 + 分块正文）→
 * （思考用量提示）→ 字数脚注。只记了思考用量，没记思考原文（日志表不存）。
 */
@Composable
fun ContextLogReplyScreen(onBack: () -> Unit, viewModel: ContextLogEntryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ContextLogReplyContent(state, onBack) { t ->
        state.entry?.let { e ->
            scope.launch(Dispatchers.Default) { LogShareActions.copyOrExport(context, t, LogShareFormat.exportFileName(e.source, e.timestampMillis)) }
        }
    }
}

@Composable
internal fun ContextLogReplyContent(state: ContextLogEntryUiState, onBack: () -> Unit, onCopy: (String) -> Unit) {
    val listState = rememberLazyListState()
    val text = state.entry?.responseContent.orEmpty()
    val blocks = remember(text) { splitLogTextBlocks(text) }
    Scaffold(
        containerColor = AppTheme.colors.surface.base,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.clog_reply_full), onBack = onBack, lifted = listState.canScrollBackward,
                actions = { if (text.isNotEmpty()) AppTopBarAction(AppActionIcons.Copy, stringResource(R.string.clog_reader_copy_all), { onCopy(text) }) },
            )
        },
    ) { padding ->
        SelectionContainer(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = AppSpacing.screenGutter, end = AppSpacing.screenGutter, top = AppSpacing.s, bottom = AppSpacing.xxl),
            ) {
                if (!state.loaded) return@LazyColumn
                val e = state.entry
                if (e == null) {
                    item(key = "missing") { MissingText() }
                    return@LazyColumn
                }
                item(key = "caption") {
                    Text(
                        sentCaptionText(e, state.providerLabel), style = AppTheme.typography.caption, color = AppTheme.colors.text.secondary,
                        modifier = Modifier.padding(bottom = AppSpacing.cardGapInGroup),
                    )
                }
                if (text.isEmpty()) {
                    item(key = "empty") { Text(stringResource(R.string.clog_reply_empty), style = AppTheme.typography.secondary, color = AppTheme.colors.text.secondary) }
                } else {
                    item(key = "header") {
                        ReaderRowShell(LogCardPos.TOP, null, divider = false, gapTop = false, gapBottom = false) {
                            LogReaderHeaderRow(LogSentMessage(1, LogSentRole.ASSISTANT, text.length, emptyList()), { LogPill(readerCharacterName(e), LogPillTone.NEUTRAL) }) { onCopy(text) }
                        }
                    }
                    itemsIndexed(blocks) { i, block ->
                        val last = i == blocks.lastIndex
                        ReaderRowShell(if (last) LogCardPos.BOTTOM else LogCardPos.MIDDLE, null, divider = false, gapTop = false, gapBottom = last) {
                            Text(block, style = AppTheme.typography.secondary, color = AppTheme.colors.text.primary)
                        }
                    }
                }
                if (e.reasoningTokens > 0) {
                    item(key = "reasoning") {
                        Box(Modifier.padding(top = AppSpacing.cardGapInGroup)) {
                            LogNotice(LogNoticeTone.INFO) {
                                Text(
                                    stringResource(R.string.clog_reply_reasoning, LogFormat.grouped(e.reasoningTokens.toLong())),
                                    style = AppTheme.typography.secondary, color = AppTheme.colors.status.onInfo,
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
                            style = AppTheme.typography.caption, color = AppTheme.colors.text.secondary, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
