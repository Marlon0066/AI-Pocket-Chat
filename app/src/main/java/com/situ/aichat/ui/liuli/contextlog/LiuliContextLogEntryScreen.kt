package com.situ.aichat.ui.liuli.contextlog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.LogEntryEntity
import com.situ.aichat.diagnostics.LogShareFormat
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.contextlog.ContextLogEntryUiState
import com.situ.aichat.ui.contextlog.ContextLogEntryViewModel
import com.situ.aichat.ui.contextlog.LogPillTone
import com.situ.aichat.ui.contextlog.LogShareActions
import com.situ.aichat.ui.contextlog.model.LogFormat
import com.situ.aichat.ui.contextlog.shared.LogMapLegend
import com.situ.aichat.ui.contextlog.shared.LogMiniMap
import com.situ.aichat.ui.contextlog.shared.LogToolCallContent
import com.situ.aichat.ui.contextlog.shared.breakNavText
import com.situ.aichat.ui.contextlog.shared.entryCaptionText
import com.situ.aichat.ui.contextlog.shared.failureCopy
import com.situ.aichat.ui.contextlog.shared.kidTailText
import com.situ.aichat.ui.contextlog.shared.sentSubtitleText
import com.situ.aichat.ui.contextlog.shared.timeTokensText
import com.situ.aichat.ui.contextlog.shared.tokensExtraText
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.logMapColors
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliGroupHeader
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliStatCard
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 一轮详情 / 调用详情（四期·图纸四 §4.3·琉璃）：与暖陶共用 [ContextLogEntryViewModel]，内容 / 顺序 / 文案同暖陶。 */
@Composable
fun LiuliContextLogEntryScreen(
    onBack: () -> Unit,
    onOpenEntry: (Long, Boolean) -> Unit,
    onOpenMap: (Long) -> Unit,
    onOpenSent: (Long) -> Unit,
    onOpenReply: (Long) -> Unit,
    viewModel: ContextLogEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LiuliContextLogEntryContent(
        state, onBack, onOpenEntry, onOpenMap, onOpenSent, onOpenReply,
        onCopyAll = { e ->
            scope.launch(Dispatchers.Default) {
                LogShareActions.copyOrExport(context, LogShareFormat.entryText(e), LogShareFormat.exportFileName(e.source, e.timestampMillis))
            }
        },
        onExportReplay = { e ->
            scope.launch(Dispatchers.Default) {
                viewModel.replayExport(e)?.let { (text, fileName) -> LogShareActions.exportWithFeedback(context, text, fileName) }
            }
        },
    )
}

/** 琉璃日志条目各页共用的页壳：大标题页 + 懒列表（页底 + 导航栏留白）。 */
@Composable
internal fun LiuliLogPage(title: String, onBack: () -> Unit, listState: LazyListState = rememberLazyListState(), content: LazyListScope.() -> Unit) {
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(title = title, onBack = onBack, collapsed = rememberLargeTitleCollapsed(listState)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            content()
        }
    }
}

@Composable
internal fun LiuliContextLogEntryContent(
    state: ContextLogEntryUiState,
    onBack: () -> Unit,
    onOpenEntry: (Long, Boolean) -> Unit,
    onOpenMap: (Long) -> Unit,
    onOpenSent: (Long) -> Unit,
    onOpenReply: (Long) -> Unit,
    onCopyAll: (LogEntryEntity) -> Unit,
    onExportReplay: (LogEntryEntity) -> Unit,
) {
    LiuliLogPage(stringResource(if (state.isTurn) R.string.clog_turn_title else R.string.clog_entry_title), onBack) {
        if (!state.loaded) return@LiuliLogPage
        val e = state.entry
        if (e == null) {
            item(key = "missing") { LiuliLogEmpty(stringResource(R.string.clog_entry_missing)) }
            return@LiuliLogPage
        }
        item(key = "caption") { LiuliLogCaption(entryCaptionText(e, state.conversationTitle, state.quote)) }
        item(key = "info") { InfoGroup(e, state.providerLabel) }
        if (e.isSuccess) item(key = "tokens") { TokenBlock(e, state.cacheRatePercent) }
        state.failure?.let { f ->
            item(key = "failure") {
                val copy = failureCopy(f.kind)
                LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
                    LiuliLogFreeRow(first = true) {
                        Column {
                            Text(stringResource(copy.name), style = AppTypography.label, color = AppTheme.colors.status.onError)
                            Text(stringResource(copy.short), style = AppTypography.secondary, color = AppTheme.colors.text.secondary)
                        }
                    }
                    LiuliNavRow(stringResource(R.string.clog_open_failure), onClick = { onOpenEntry(e.id, true) })
                }
            }
        }
        item(key = "minimap") { MiniMapGroup(state) { onOpenMap(e.id) } }
        item(key = "links") {
            LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
                LiuliNavRow(stringResource(R.string.clog_sent_title), onClick = { onOpenSent(e.id) }, subtitle = sentSubtitleText(state.adaptation), divider = false)
                if (!e.responseContent.isNullOrEmpty()) LiuliNavRow(stringResource(R.string.clog_reply_full), onClick = { onOpenReply(e.id) })
            }
        }
        state.toolInfo?.let { info ->
            item(key = "tools") {
                LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter), header = "工具调用") {
                    LiuliLogFreeRow(first = true) { Column { LogToolCallContent(info) } }
                }
            }
        }
        if (state.kids.isNotEmpty()) {
            item(key = "kids") {
                LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter), header = stringResource(R.string.clog_turn_kids_title)) {
                    state.kids.forEachIndexed { i, kid ->
                        LiuliRowBase(onClick = { onOpenEntry(kid.id, kid.failed) }, onClickLabel = kid.source, divider = i > 0) {
                            Text(kid.source, style = AppTypography.secondary, color = AppTheme.colors.text.primary, modifier = Modifier.weight(1f))
                            Text(
                                timeTokensText(kid.timeMillis, kid.tokens, kid.tokensEstimated) + (if (kid.failed) kidTailText() else "") + " ›",
                                style = AppTypography.secondary,
                                color = if (kid.failed) AppTheme.colors.status.onError else AppTheme.colors.text.primary,
                            )
                        }
                    }
                }
            }
        }
        item(key = "buttons") { ActionButtons(state, e, onCopyAll, onExportReplay) }
    }
}

@Composable
private fun InfoGroup(e: LogEntryEntity, providerLabel: String?) {
    LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
        LiuliLogFreeRow(first = true) {
            if (e.isSuccess) LiuliLogPill(stringResource(R.string.clog_status_ok), LogPillTone.SUCCESS)
            else LiuliLogPill(stringResource(R.string.clog_status_failed), LogPillTone.ERROR)
            Spacer(Modifier.weight(1f))
            Text(LogFormat.fullTime(e.timestampMillis, ZoneId.systemDefault()), style = AppTypography.captionNumeric, color = AppTheme.colors.text.secondary)
        }
        LiuliLogKvRow(stringResource(R.string.clog_kv_source), e.source)
        LiuliLogKvRow(stringResource(R.string.clog_kv_model), e.modelName)
        LiuliLogKvRow(stringResource(R.string.clog_kv_provider), providerLabel ?: stringResource(R.string.clog_old_value))
        e.durationMillis?.let { LiuliLogKvRow(stringResource(R.string.clog_kv_duration), stringResource(R.string.clog_turn_duration, LogFormat.seconds(it))) }
        LiuliLogKvRow(stringResource(R.string.clog_kv_messages), stringResource(R.string.clog_messages_count, e.messageCount))
    }
}

/** Token：组标题 + 三格统计卡（值不带单位·零件自带样式）+ 附行。 */
@Composable
private fun TokenBlock(e: LogEntryEntity, cacheRatePercent: Int?) {
    Column(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
        LiuliGroupHeader(stringResource(R.string.clog_tokens_title))
        LiuliStatCard(
            listOf(
                stringResource(R.string.clog_tokens_input) to LogFormat.grouped(e.promptTokens.toLong()),
                stringResource(R.string.clog_tokens_output) to LogFormat.grouped(e.completionTokens.toLong()),
                stringResource(R.string.clog_tokens_cache) to (cacheRatePercent?.let { "$it%" } ?: stringResource(R.string.clog_rate_none)),
            ),
        )
    }
    LiuliLogCaption(tokensExtraText(e))
    if (e.isTokenEstimated) LiuliLogCaption(stringResource(R.string.clog_tokens_estimated))
}

@Composable
private fun MiniMapGroup(state: ContextLogEntryUiState, onOpenMap: () -> Unit) {
    val mini = state.miniMap
    if (mini == null) {
        LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
            LiuliLogFreeRow(first = true) { Text(stringResource(R.string.clog_map_old), style = AppTypography.secondary, color = AppTheme.colors.text.secondary) }
        }
        return
    }
    val colors = logMapColors()
    LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter), header = stringResource(R.string.clog_map_title)) {
        LiuliLogFreeRow(first = true) {
            Column {
                LogMiniMap(mini, colors)
                Spacer(Modifier.size(AppSpacing.s))
                LogMapLegend(colors)
            }
        }
        state.comparison?.cacheBreak?.let { cut ->
            LiuliNavRow(breakNavText(cut.kind, cut.moduleName), onClick = onOpenMap, subtitle = stringResource(R.string.clog_break_nav_sub))
        }
    }
}

@Composable
private fun ActionButtons(state: ContextLogEntryUiState, e: LogEntryEntity, onCopyAll: (LogEntryEntity) -> Unit, onExportReplay: (LogEntryEntity) -> Unit) {
    Column(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            LiuliButton(onClick = { onCopyAll(e) }, style = LiuliButtonStyle.Glass, modifier = Modifier.weight(1f)) {
                Icon(AppActionIcons.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(AppSpacing.s))
                Text(stringResource(R.string.contextlog_copy_all))
            }
            LiuliButton(onClick = { onExportReplay(e) }, style = LiuliButtonStyle.Prominent, enabled = state.canExportReplay, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.IosShare, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(AppSpacing.s))
                Text(stringResource(R.string.clog_export_replay), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            stringResource(
                when {
                    state.canExportReplay -> R.string.clog_export_note
                    !state.detailEnabled -> R.string.clog_export_needs_detail
                    else -> R.string.clog_export_none
                },
            ),
            style = AppTypography.caption,
            color = AppTheme.colors.text.secondary,
            modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.s),
        )
    }
}
