package com.situ.aichat.ui.liuli.contextlog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.diagnostics.LogSendAdaptation
import com.situ.aichat.diagnostics.LogShareFormat
import com.situ.aichat.diagnostics.LogTokenFormat
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.contextlog.ContextLogReaderUiState
import com.situ.aichat.ui.contextlog.ContextLogReaderViewModel
import com.situ.aichat.ui.contextlog.LogNoticeTone
import com.situ.aichat.ui.contextlog.LogPillTone
import com.situ.aichat.ui.contextlog.LogShareActions
import com.situ.aichat.ui.contextlog.model.LogCardPos
import com.situ.aichat.ui.contextlog.model.LogReaderItem
import com.situ.aichat.ui.contextlog.model.LogReaderSearch
import com.situ.aichat.ui.contextlog.model.LogSentMessage
import com.situ.aichat.ui.contextlog.model.LogSentRole
import com.situ.aichat.ui.contextlog.model.countsBy
import com.situ.aichat.ui.contextlog.model.readerChipOptions
import com.situ.aichat.ui.contextlog.model.readerCopyAllText
import com.situ.aichat.ui.contextlog.model.readerNav
import com.situ.aichat.ui.contextlog.model.readerRows
import com.situ.aichat.ui.contextlog.shared.LogReaderControls
import com.situ.aichat.ui.contextlog.shared.LogReaderEffects
import com.situ.aichat.ui.contextlog.shared.LogReaderFixed
import com.situ.aichat.ui.contextlog.shared.LogReaderOverview
import com.situ.aichat.ui.contextlog.shared.LogReaderRowContent
import com.situ.aichat.ui.contextlog.shared.adaptedCount
import com.situ.aichat.ui.contextlog.shared.adaptedItems
import com.situ.aichat.ui.contextlog.shared.readerFilterLabel
import com.situ.aichat.ui.contextlog.shared.readerFixedItems
import com.situ.aichat.ui.contextlog.shared.readerFooterText
import com.situ.aichat.ui.contextlog.shared.readerHitCountText
import com.situ.aichat.ui.contextlog.shared.readerScrollTo
import com.situ.aichat.ui.contextlog.shared.readerTocShape
import com.situ.aichat.ui.contextlog.shared.rememberLogReaderControls
import com.situ.aichat.ui.contextlog.shared.sentCaptionText
import com.situ.aichat.ui.contextlog.shared.sentRoleLabel
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliMaterials
import com.situ.aichat.ui.liuli.designsystem.LiuliSearchSlot
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmentPosition
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.designsystem.liuliCardSegment
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 浮钮让位：两枚 40 圆钮 + 间 8（= 88dp·图纸五 §4.3 / §9）。 */
private val JumpStackLiuli = LiuliPageGeometry.backButton * 2 + AppSpacing.s

/** 上下文阅读器（四期·图纸五 §4·琉璃）：内容 / 顺序 / 文案同暖陶，只换外壳（大标题页·玻璃胶囊下挂搜索条·右下玻璃圆钮）。 */
@Composable
fun LiuliContextLogSentScreen(
    onBack: () -> Unit,
    onOpenLogSettings: () -> Unit,
    onOpenMap: (Long) -> Unit,
    viewModel: ContextLogReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LiuliContextLogSentContent(
        state, search, onBack, onOpenLogSettings, onOpenMap, onQueryChange = viewModel::setQuery,
        onCopyMessage = { m ->
            state.entry?.let { e ->
                scope.launch(Dispatchers.Default) { LogShareActions.copyOrExport(context, m.raw, LogShareFormat.exportFileName(e.source, e.timestampMillis)) }
            }
        },
        onCopyAll = {
            val v = state.view
            val e = state.entry
            if (v != null && e != null) {
                scope.launch(Dispatchers.Default) { LogShareActions.copyOrExport(context, readerCopyAllText(v, e), LogShareFormat.exportFileName(e.source, e.timestampMillis)) }
            }
        },
    )
}

@Composable
internal fun LiuliContextLogSentContent(
    state: ContextLogReaderUiState,
    search: LogReaderSearch?,
    onBack: () -> Unit,
    onOpenLogSettings: () -> Unit,
    onOpenMap: (Long) -> Unit,
    onQueryChange: (String) -> Unit,
    onCopyMessage: (LogSentMessage) -> Unit,
    onCopyAll: () -> Unit,
) {
    val listState = rememberLazyListState()
    val c = rememberLogReaderControls()
    val view = state.view
    val active = c.activeSearch(search)
    val nav = remember(active, view, c.filter) { readerNav(active, view, c.filter) }
    val current = nav.getOrNull(c.current)
    val snap = c.snapshot(search)
    val rows = remember(view, snap) { view?.let { readerRows(it, snap) }.orEmpty() }
    val fixed = readerFixedItems(state, c, rows.isEmpty())
    LogReaderEffects(c, view, search, rows, fixed.size + (if (!c.searching) 1 else 0), listState, onQueryChange)
    val focus = remember { FocusRequester() }
    LaunchedEffect(c.searching) { if (c.searching) focus.requestFocus() }
    val collapsedByScroll = rememberLargeTitleCollapsed(listState)
    val jumpsPossible = !c.searching && view != null
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.clog_sent_title)
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(
        title = title,
        onBack = if (c.searching) ({ c.exitSearch() }) else onBack,
        collapsed = c.searching || collapsedByScroll,
        actions = {
            if (c.searching) {
                LiuliPageCircleAction({ c.current = (c.current - 1).mod(nav.size) }, stringResource(R.string.clog_reader_prev_hit), AppActionIcons.ChevronUp, enabled = nav.isNotEmpty())
                LiuliPageCircleAction({ c.current = (c.current + 1).mod(nav.size) }, stringResource(R.string.clog_reader_next_hit), AppActionIcons.ChevronDown, enabled = nav.isNotEmpty())
            } else if (view != null) {
                LiuliPageCircleAction({ c.enterSearch() }, stringResource(R.string.clog_reader_search), AppActionIcons.Search)
                LiuliPageCircleAction(onCopyAll, stringResource(R.string.clog_reader_copy_all), AppActionIcons.Copy)
            }
        },
        subBar = if (c.searching) ({
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                LiuliSearchSlot(
                    c.query, { c.query = it }, stringResource(R.string.clog_reader_search_hint), stringResource(R.string.clog_reader_search_clear),
                    Modifier.weight(1f).focusRequester(focus),
                )
                readerHitCountText(c.query, nav, c.current, search?.capped == true)?.let {
                    Spacer(Modifier.width(AppSpacing.s))
                    Text(it, style = AppTypography.captionNumeric, color = LiuliTheme.onGlass.secondary)
                }
            }
        }) else null,
        fab = if (jumpsPossible && (listState.canScrollForward || listState.canScrollBackward)) ({
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                JumpButton(Icons.Outlined.VerticalAlignTop, stringResource(R.string.clog_reader_jump_top)) { scope.launch { listState.readerScrollTo(0, reduceMotion) } }
                JumpButton(Icons.Outlined.VerticalAlignBottom, stringResource(R.string.clog_reader_jump_bottom)) {
                    scope.launch { listState.readerScrollTo((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), reduceMotion) }
                }
            }
        }) else null,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(
                top = if (c.searching) LiuliPageGeometry.compactBar + LiuliPageGeometry.subBar else LiuliPageGeometry.navRow,
                bottom = LiuliPageGeometry.pageBottom + navBar + if (jumpsPossible) LiuliPageGeometry.fabBottom + JumpStackLiuli else 0.dp,
            ),
        ) {
            if (!c.searching) item(key = "large-title") { LiuliLargeTitle(title) }
            if (!state.loaded) return@LazyColumn
            val e = state.entry
            if (e == null) {
                item(key = "missing") { LiuliLogEmpty(stringResource(R.string.clog_entry_missing)) }
                return@LazyColumn
            }
            fixed.forEach { f -> item(key = f.name.lowercase()) { FixedItem(f, state, search, c, onOpenLogSettings, onOpenMap) } }
            if (view != null) {
                items(rows, key = { it.key }) { item ->
                    RowShell(item) {
                        LogReaderRowContent(item, view, nav, current, c, onCopyMessage) { m ->
                            LiuliLogPill(sentRoleLabel(m, e.characterName), if (m.role == LogSentRole.USER) LogPillTone.USER else LogPillTone.NEUTRAL)
                        }
                    }
                }
                if (!c.searching) {
                    item(key = "footer") {
                        Text(
                            readerFooterText(view), style = AppTypography.caption, color = AppTheme.colors.text.secondary,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.m),
                        )
                    }
                }
            }
        }
    }
}

/** 列表前的固定项（按 readerFixedItems 顺序·间距由各零件自带）。 */
@Composable
private fun FixedItem(
    f: LogReaderFixed, state: ContextLogReaderUiState, search: LogReaderSearch?, c: LogReaderControls,
    onOpenLogSettings: () -> Unit, onOpenMap: (Long) -> Unit,
) {
    val e = state.entry ?: return
    val view = state.view
    val onInfo = AppTheme.colors.status.onInfo
    when (f) {
        LogReaderFixed.CAPTION -> LiuliLogCaption(sentCaptionText(e, state.providerLabel))
        LogReaderFixed.OVERVIEW -> if (view != null) {
            LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
                LiuliLogFreeRow(first = true) { Column { LogReaderOverview(view, e) { onOpenMap(e.id) } } }
            }
        }
        LogReaderFixed.CHIPS -> if (view != null) {
            val counts = c.activeSearch(search)?.countsBy(view) ?: view.counts
            LiuliLogChipRow(
                readerChipOptions(counts, c.filter), { it == c.filter }, { readerFilterLabel(it, e) + " " + (counts[it] ?: 0) }, { c.filter = it },
                Modifier.padding(bottom = AppSpacing.m),
            )
        }
        LogReaderFixed.ONLY_HITS -> LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
            LiuliToggleRow(
                stringResource(R.string.clog_reader_only_hits), c.onlyHits, { c.onlyHits = it },
                subtitle = stringResource(R.string.clog_reader_only_hits_note), divider = false,
            )
        }
        LogReaderFixed.ADAPTATION -> AdaptationNotice(state.adaptation)
        LogReaderFixed.FALLBACK -> LiuliLogNotice(LogNoticeTone.INFO) {
            Text(stringResource(R.string.clog_reader_fallback), style = AppTypography.secondary, color = onInfo)
        }
        LogReaderFixed.DETAIL_OFF -> Column {
            LiuliLogNotice(LogNoticeTone.INFO) {
                Text(stringResource(R.string.clog_sent_detail_off), style = AppTypography.secondary, color = onInfo)
                // 条数 = 发出的消息条数（messageCount 与形状同一份消息列表算出）；token = 形状 token 之和（= 地图总量）。
                state.shapeTokens?.let { tk ->
                    Text(stringResource(R.string.clog_sent_stats, e.messageCount, LogTokenFormat.compact(tk)), style = AppTypography.secondary, color = onInfo)
                }
            }
            LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
                LiuliNavRow(stringResource(R.string.clog_open_log_settings), onClick = onOpenLogSettings, divider = false)
            }
        }
        LogReaderFixed.SEARCH_EMPTY -> Text(
            stringResource(R.string.clog_reader_search_empty, c.query.trim()), style = AppTypography.secondary, color = AppTheme.colors.text.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.xl).padding(horizontal = LiuliPageGeometry.gutter),
        )
    }
}

@Composable
private fun RowShell(item: LogReaderItem, content: @Composable () -> Unit) =
    LiuliReaderRowShell(item.pos, item.tocPos, item.divider, item.gapTop, item.gapBottom, item.tocGapBottom, content)

/** 消息卡的行壳（§4.4·琉璃·回复全文页共用）：分段卡（页边 20·行内距 16）+ 0.5dp 分隔线 + 上下留白 + 目录区底（节末留白在灰底里·复核 R1）。 */
@Composable
internal fun LiuliReaderRowShell(
    pos: LogCardPos, tocPos: LogCardPos?, divider: Boolean, gapTop: Boolean, gapBottom: Boolean, tocGapBottom: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val position = when (pos) {
        LogCardPos.SINGLE -> LiuliSegmentPosition.Single
        LogCardPos.TOP -> LiuliSegmentPosition.Top
        LogCardPos.MIDDLE -> LiuliSegmentPosition.Middle
        LogCardPos.BOTTOM -> LiuliSegmentPosition.Bottom
    }
    // fillMaxWidth：内容比卡窄的行（短正文 / 「展开」钮）不许把这一段卡缩窄（装机实测破版·复核 R1 裁决 T-3 核准、作者已补进 §4.4）。
    Column(Modifier.padding(horizontal = LiuliPageGeometry.gutter).fillMaxWidth().liuliCardSegment(position, dark).padding(horizontal = LiuliPageGeometry.groupPadH)) {
        if (divider) Box(Modifier.fillMaxWidth().height(0.5.dp).background(LiuliMaterials.divider(dark)))
        if (gapTop) Spacer(Modifier.height(AppSpacing.xs))
        if (tocPos != null) {
            Column(Modifier.fillMaxWidth().clip(readerTocShape(tocPos)).background(AppTheme.colors.surface.sunken)) {
                content()
                if (tocGapBottom) Spacer(Modifier.height(AppSpacing.s))
            }
        } else {
            content()
        }
        if (gapBottom) Spacer(Modifier.height(AppSpacing.s))
    }
}

/**
 * 回顶 / 到底玻璃圆钮（视觉 40 · 图标 20）。外套一枚 40 盒让 48 触达框居中外溢——版位恰 40，两枚叠起来 = [JumpStackLiuli] 88
 * （同 LiuliPageCircleAction 手法·§11 D-5）。
 */
@Composable
private fun JumpButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(LiuliPageGeometry.backButton), contentAlignment = Alignment.Center) {
        LiuliCircleButton(onClick = onClick, contentDescription = label, size = LiuliPageGeometry.backButton) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(LiuliPageGeometry.chromeIcon))
        }
    }
}

@Composable
private fun AdaptationNotice(a: LogSendAdaptation?) {
    LiuliLogNotice(LogNoticeTone.INFO) {
        val fg = AppTheme.colors.status.onInfo
        when {
            a == null -> Text(stringResource(R.string.clog_sent_none), style = AppTypography.secondary, color = fg)
            a.asIs -> Text(stringResource(R.string.clog_sent_asis), style = AppTypography.secondary, color = fg)
            // 非白名单服务商、但这一条本来就没有要改的（计数全 0·复核 R1）：不写「改写了 0 处」，也不说「这家能直接读懂」
            adaptedCount(a) == 0 -> Text(stringResource(R.string.clog_sent_sub_asis), style = AppTypography.secondary, color = fg)
            else -> {
                Text(stringResource(R.string.clog_sent_adapted_intro, adaptedCount(a)), style = AppTypography.secondary, color = fg)
                adaptedItems(a).forEachIndexed { i, (res, n) ->
                    Text("${i + 1}. " + stringResource(res, n), style = AppTypography.secondary, color = fg, modifier = Modifier.padding(start = AppSpacing.l))
                }
            }
        }
    }
}
