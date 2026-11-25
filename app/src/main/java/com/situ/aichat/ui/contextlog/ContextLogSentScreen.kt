package com.situ.aichat.ui.contextlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
import com.situ.aichat.ui.designsystem.AppActionIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.diagnostics.LogSendAdaptation
import com.situ.aichat.diagnostics.LogShareFormat
import com.situ.aichat.diagnostics.LogTokenFormat
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.contextlog.model.LogCardPos
import com.situ.aichat.ui.contextlog.model.LogReaderHit
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
import com.situ.aichat.ui.designsystem.AppChoiceChip
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppSearchField
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTopBarAction
import com.situ.aichat.ui.designsystem.CardSegment
import com.situ.aichat.ui.designsystem.appCardSegmentSurface
import com.situ.aichat.ui.designsystem.appCardSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 浮钮让位：锁定 128dp（图纸五 §9「暖陶底距 AppSpacing.xxl + 128dp」）= 右下边距 16 + 两枚 48 + 间 8 + 末项与钮的间隙 8
 * （原 §4.3 算式漏了最后一个 AppSpacing.s·复核 R1 裁决 T-2 取 128、作者已订正图纸）。
 */
private val JumpClearanceWarm = 128.dp

/**
 * 上下文阅读器（四期·图纸五 §4·暖陶）：只看这个模型实际收到的内容——说明行 → 概览（细条 / 断点）→ 筛选芯片 → 改写说明 /
 * 兜底说明 → 消息卡（系统提示拆目录·时间胶囊·App 附加框·每条可复制）→ 页脚；右上搜索 / 复制全部，右下回顶 / 到底。
 */
@Composable
fun ContextLogSentScreen(
    onBack: () -> Unit,
    onOpenLogSettings: () -> Unit,
    onOpenMap: (Long) -> Unit,
    viewModel: ContextLogReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ContextLogSentContent(
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
internal fun ContextLogSentContent(
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
    LogReaderEffects(c, view, search, rows, fixed.size, listState, onQueryChange)
    val focus = remember { FocusRequester() }
    LaunchedEffect(c.searching) { if (c.searching) focus.requestFocus() }
    val jumpsPossible = !c.searching && view != null
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    Scaffold(
        containerColor = AppTheme.colors.surface.base,
        topBar = {
            if (c.searching) {
                SearchTopBar(c, nav, search, focus)
            } else {
                AppTopBar(
                    title = stringResource(R.string.clog_sent_title), onBack = onBack, lifted = listState.canScrollBackward,
                    actions = {
                        if (view != null) {
                            AppTopBarAction(AppActionIcons.Search, stringResource(R.string.clog_reader_search), { c.enterSearch() })
                            AppTopBarAction(AppActionIcons.Copy, stringResource(R.string.clog_reader_copy_all), onCopyAll)
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AppSpacing.screenGutter, end = AppSpacing.screenGutter, top = AppSpacing.s,
                    bottom = AppSpacing.xxl + if (jumpsPossible) JumpClearanceWarm else 0.dp,
                ),
            ) {
                if (!state.loaded) return@LazyColumn
                val e = state.entry
                if (e == null) {
                    item(key = "missing") { MissingText() }
                    return@LazyColumn
                }
                fixed.forEach { f ->
                    item(key = f.name.lowercase()) {
                        Box(Modifier.padding(bottom = AppSpacing.cardGapInGroup)) { FixedItem(f, state, search, c, onOpenLogSettings, onOpenMap) }
                    }
                }
                if (view != null) {
                    items(rows, key = { it.key }) { item ->
                        RowShell(item) {
                            LogReaderRowContent(item, view, nav, current, c, onCopyMessage) { m ->
                                LogPill(sentRoleLabel(m, e.characterName), if (m.role == LogSentRole.USER) LogPillTone.USER else LogPillTone.NEUTRAL)
                            }
                        }
                    }
                    if (!c.searching) {
                        item(key = "footer") {
                            Text(
                                readerFooterText(view), style = AppTheme.typography.caption, color = AppTheme.colors.text.secondary,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.m),
                            )
                        }
                    }
                }
            }
            if (jumpsPossible && (listState.canScrollForward || listState.canScrollBackward)) {
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(end = AppSpacing.l, bottom = AppSpacing.l),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s),
                ) {
                    JumpButton(Icons.Outlined.VerticalAlignTop, stringResource(R.string.clog_reader_jump_top)) {
                        scope.launch { listState.readerScrollTo(0, reduceMotion) }
                    }
                    JumpButton(Icons.Outlined.VerticalAlignBottom, stringResource(R.string.clog_reader_jump_bottom)) {
                        scope.launch { listState.readerScrollTo((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), reduceMotion) }
                    }
                }
            }
        }
    }
}

/** 搜索态顶栏：返回（= 退出搜索）· 页名 · 上一个 / 下一个；下面一行搜索框 + 计数。 */
@Composable
private fun SearchTopBar(c: LogReaderControls, nav: List<LogReaderHit>, search: LogReaderSearch?, focus: FocusRequester) {
    Column(Modifier.background(AppTheme.colors.surface.base)) {
        AppTopBar(
            title = stringResource(R.string.clog_sent_title), onBack = { c.exitSearch() }, lifted = false,
            actions = {
                AppTopBarAction(AppActionIcons.ChevronUp, stringResource(R.string.clog_reader_prev_hit), { c.current = (c.current - 1).mod(nav.size) }, enabled = nav.isNotEmpty())
                AppTopBarAction(AppActionIcons.ChevronDown, stringResource(R.string.clog_reader_next_hit), { c.current = (c.current + 1).mod(nav.size) }, enabled = nav.isNotEmpty())
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(start = AppSpacing.screenGutter, end = AppSpacing.screenGutter, bottom = AppSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppSearchField(
                c.query, { c.query = it }, stringResource(R.string.clog_reader_search_hint),
                Modifier.weight(1f).focusRequester(focus), stringResource(R.string.clog_reader_search_clear),
            )
            readerHitCountText(c.query, nav, c.current, search?.capped == true)?.let {
                Spacer(Modifier.width(AppSpacing.s))
                Text(it, style = AppTheme.typography.captionNumeric, color = AppTheme.colors.text.secondary)
            }
        }
    }
}

/** 列表前的固定项（按 [readerFixedItems] 顺序）。 */
@Composable
private fun FixedItem(
    f: LogReaderFixed, state: ContextLogReaderUiState, search: LogReaderSearch?, c: LogReaderControls,
    onOpenLogSettings: () -> Unit, onOpenMap: (Long) -> Unit,
) {
    val e = state.entry ?: return
    val view = state.view
    when (f) {
        LogReaderFixed.CAPTION -> Text(sentCaptionText(e, state.providerLabel), style = AppTheme.typography.caption, color = AppTheme.colors.text.secondary)
        LogReaderFixed.OVERVIEW -> if (view != null) LogCard { LogReaderOverview(view, e) { onOpenMap(e.id) } }
        LogReaderFixed.CHIPS -> if (view != null) {
            val counts = c.activeSearch(search)?.countsBy(view) ?: view.counts
            LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                items(readerChipOptions(counts, c.filter), key = { it.name }) { option ->
                    AppChoiceChip(selected = c.filter == option, onClick = { c.filter = option }, label = readerFilterLabel(option, e) + " " + (counts[option] ?: 0))
                }
            }
        }
        LogReaderFixed.ONLY_HITS -> LogCard(contentPadding = PaddingValues(0.dp)) {
            SettingsSwitchRow(stringResource(R.string.clog_reader_only_hits), c.onlyHits, { c.onlyHits = it }, subtitle = stringResource(R.string.clog_reader_only_hits_note))
        }
        LogReaderFixed.ADAPTATION -> AdaptationNotice(state.adaptation)
        LogReaderFixed.FALLBACK -> LogNotice(LogNoticeTone.INFO) {
            Text(stringResource(R.string.clog_reader_fallback), style = AppTheme.typography.secondary, color = AppTheme.colors.status.onInfo)
        }
        LogReaderFixed.DETAIL_OFF -> LogNotice(LogNoticeTone.INFO) {
            Text(stringResource(R.string.clog_sent_detail_off), style = AppTheme.typography.secondary, color = AppTheme.colors.status.onInfo)
            // 形状的条数 = 发出的消息条数（messageCount 与形状同一份消息列表算出）；token = 形状 token 之和（= 地图总量）。
            state.shapeTokens?.let { tk ->
                Text(
                    stringResource(R.string.clog_sent_stats, e.messageCount, LogTokenFormat.compact(tk)),
                    style = AppTheme.typography.secondary, color = AppTheme.colors.status.onInfo,
                )
            }
            LogNavRow(stringResource(R.string.clog_open_log_settings), onClick = onOpenLogSettings)
        }
        LogReaderFixed.SEARCH_EMPTY -> Text(
            stringResource(R.string.clog_reader_search_empty, c.query.trim()), style = AppTheme.typography.secondary,
            color = AppTheme.colors.text.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.xl),
        )
    }
}

@Composable
private fun RowShell(item: LogReaderItem, content: @Composable () -> Unit) =
    ReaderRowShell(item.pos, item.tocPos, item.divider, item.gapTop, item.gapBottom, item.tocGapBottom, content)

/** 消息卡的行壳（§4.4·暖陶·回复全文页共用）：逐行分段卡拼成一张 + 行顶分隔线 / 上下留白 + 目录区底（节末留白在灰底里·复核 R1）。 */
@Composable
internal fun ReaderRowShell(
    pos: LogCardPos, tocPos: LogCardPos?, divider: Boolean, gapTop: Boolean, gapBottom: Boolean, tocGapBottom: Boolean = false,
    content: @Composable () -> Unit,
) {
    val surface = when (pos) {
        LogCardPos.SINGLE -> Modifier.appCardSurface()
        LogCardPos.TOP -> Modifier.appCardSegmentSurface(CardSegment.Top)
        LogCardPos.MIDDLE -> Modifier.appCardSegmentSurface(CardSegment.Middle)
        LogCardPos.BOTTOM -> Modifier.appCardSegmentSurface(CardSegment.Bottom)
    }
    Column(Modifier.fillMaxWidth().then(surface).padding(horizontal = AppSpacing.cardInset)) {
        if (divider) AppListDivider(startInset = 0.dp)
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

/** 回顶 / 到底浮钮：48dp 浮起圆卡 + 20dp 图标。 */
@Composable
private fun JumpButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).appCardSurface(raised = true, cornerRadius = 24.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = AppTheme.colors.text.primary)
    }
}

@Composable
private fun AdaptationNotice(a: LogSendAdaptation?) {
    LogNotice(LogNoticeTone.INFO) {
        val fg = AppTheme.colors.status.onInfo
        when {
            a == null -> Text(stringResource(R.string.clog_sent_none), style = AppTheme.typography.secondary, color = fg)
            a.asIs -> Text(stringResource(R.string.clog_sent_asis), style = AppTheme.typography.secondary, color = fg)
            // 非白名单服务商、但这一条本来就没有要改的（计数全 0·复核 R1）：不写「改写了 0 处」，也不说「这家能直接读懂」
            adaptedCount(a) == 0 -> Text(stringResource(R.string.clog_sent_sub_asis), style = AppTheme.typography.secondary, color = fg)
            else -> {
                Text(stringResource(R.string.clog_sent_adapted_intro, adaptedCount(a)), style = AppTheme.typography.secondary, color = fg)
                adaptedItems(a).forEachIndexed { i, (res, n) ->
                    Text("${i + 1}. " + stringResource(res, n), style = AppTheme.typography.secondary, color = fg, modifier = Modifier.padding(start = AppSpacing.l))
                }
            }
        }
    }
}
