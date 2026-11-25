package com.situ.aichat.ui.liuli.promise

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.liuliCardSegment
import com.situ.aichat.ui.liuli.designsystem.liuliSegmentPosition
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliSectionHeader
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.promise.PromiseLedgerDetailSheet
import com.situ.aichat.ui.promise.PromiseLedgerEmptyState
import com.situ.aichat.ui.promise.PromiseLedgerViewModel
import com.situ.aichat.ui.promise.PromiseOpenRowBody
import com.situ.aichat.ui.promise.PromiseResolvedRowBody
import com.situ.aichat.ui.promise.ledgerRowModifier
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 「我们的约定」账本页（琉璃 2.0 脸）。 */
@Composable
fun LiuliPromiseLedgerScreen(
    onBack: () -> Unit,
    viewModel: PromiseLedgerViewModel = hiltViewModel(),
) {
    val open by viewModel.openPromises.collectAsStateWithLifecycle()
    val resolved by viewModel.resolvedPromises.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val mdPattern = stringResource(R.string.promise_date_pattern_md)
    val nowMillis = remember { System.currentTimeMillis() }
    val reduceMotion = rememberReduceMotion()
    val dark = LocalIsDarkTheme.current
    val listState = rememberLazyListState()
    val collapsed = rememberLargeTitleCollapsed(listState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val title = stringResource(R.string.promise_title)

    LiuliPage(title = title, onBack = onBack, collapsed = collapsed) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = LiuliPageGeometry.navRow + 4.dp,
                bottom = navBottom + LiuliPageGeometry.pageBottom,
            ),
        ) {
            item(key = "title") {
                LiuliLargeTitle(title, modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter, vertical = 4.dp))
            }

            if (open.isEmpty() && resolved.isEmpty()) {
                item(key = "empty") { PromiseLedgerEmptyState() }
            }

            if (open.isNotEmpty()) {
                item(key = "open_header") {
                    LiuliSectionHeader(stringResource(R.string.promise_section_open, open.size))
                }
                itemsIndexed(open, key = { _, p -> p.uuid }) { index, p ->
                    val pos = liuliSegmentPosition(index, open.size)
                    Column(
                        modifier = ledgerRowModifier(reduceMotion)
                            .padding(horizontal = LiuliPageGeometry.gutter)
                            .liuliCardSegment(pos, dark),
                    ) {
                        if (index > 0) AppListDivider(startInset = 16.dp)
                        PromiseOpenRowBody(p, nowMillis, mdPattern, onClick = { viewModel.select(p.uuid) })
                    }
                }
                item(key = "open_hint") {
                    Text(
                        stringResource(R.string.promise_hint_open),
                        style = AppTypography.caption,
                        color = AppTheme.colors.text.tertiary,
                        modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter + 12.dp, vertical = 8.dp),
                    )
                }
            }

            if (resolved.isNotEmpty()) {
                item(key = "resolved_header") {
                    LiuliSectionHeader(stringResource(R.string.promise_section_resolved))
                }
                itemsIndexed(resolved, key = { _, p -> p.uuid }) { index, p ->
                    val pos = liuliSegmentPosition(index, resolved.size)
                    Column(
                        modifier = ledgerRowModifier(reduceMotion)
                            .padding(horizontal = LiuliPageGeometry.gutter)
                            .liuliCardSegment(pos, dark),
                    ) {
                        if (index > 0) AppListDivider(startInset = 16.dp)
                        PromiseResolvedRowBody(p, mdPattern, onClick = { viewModel.select(p.uuid) })
                    }
                }
                item(key = "resolved_hint") {
                    Text(
                        stringResource(R.string.promise_hint_resolved),
                        style = AppTypography.caption,
                        color = AppTheme.colors.text.tertiary,
                        modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter + 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    detail?.let { d ->
        PromiseLedgerDetailSheet(
            detail = d,
            nowMillis = nowMillis,
            onDismiss = viewModel::dismissDetail,
            onConfirm = { status -> viewModel.markResolved(d.uuid, status) },
        )
    }
}
