package com.situ.aichat.ui.promise

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.promise.PromiseInjectionRenderer
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.grainSurface
import java.time.ZoneId

/**
 * 「我们的约定」账本子页（暖陶外衣）：AppTopBar + 两节平铺 LazyColumn（进行中全量 + 已了结全部历史）+ 空态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromiseLedgerScreen(
    onBack: () -> Unit,
    viewModel: PromiseLedgerViewModel = hiltViewModel(),
) {
    val open by viewModel.openPromises.collectAsStateWithLifecycle()
    val resolved by viewModel.resolvedPromises.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val mdPattern = stringResource(R.string.promise_date_pattern_md)
    val nowMillis = remember { System.currentTimeMillis() }
    val reduceMotion = rememberReduceMotion()
    val colors = AppTheme.colors

    val listState = rememberLazyListState()
    Scaffold(
        containerColor = colors.surface.base,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.promise_title),
                onBack = onBack,
                lifted = listState.canScrollBackward,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).background(colors.surface.base).grainSurface(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            if (open.isEmpty() && resolved.isEmpty()) {
                item(key = "empty") { PromiseLedgerEmptyState() }
            }

            if (open.isNotEmpty()) {
                item(key = "open_header") {
                    LedgerSectionHeader(stringResource(R.string.promise_section_open, open.size), topPadding = 18.dp)
                }
                itemsIndexed(open, key = { _, p -> p.uuid }) { index, p ->
                    GroupedRow(first = index == 0, last = index == open.lastIndex, modifier = ledgerRowModifier(reduceMotion)) {
                        PromiseOpenRowBody(p, nowMillis, mdPattern, onClick = { viewModel.select(p.uuid) })
                    }
                }
                item(key = "open_hint") { LedgerHint(stringResource(R.string.promise_hint_open)) }
            }

            if (resolved.isNotEmpty()) {
                item(key = "resolved_header") {
                    LedgerSectionHeader(stringResource(R.string.promise_section_resolved), topPadding = 26.dp)
                }
                itemsIndexed(resolved, key = { _, p -> p.uuid }) { index, p ->
                    GroupedRow(first = index == 0, last = index == resolved.lastIndex, modifier = ledgerRowModifier(reduceMotion)) {
                        PromiseResolvedRowBody(p, mdPattern, onClick = { viewModel.select(p.uuid) })
                    }
                }
                item(key = "resolved_hint") { LedgerHint(stringResource(R.string.promise_hint_resolved)) }
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
}

internal fun LazyItemScope.ledgerRowModifier(reduceMotion: Boolean): Modifier =
    if (reduceMotion) {
        Modifier
    } else {
        Modifier.animateItem(
            fadeInSpec = AppMotion.effectMediumSpring(),
            placementSpec = AppMotion.gentleSpring(IntOffset.VisibilityThreshold),
            fadeOutSpec = AppMotion.effectMediumSpring(),
        )
    }

@Composable
private fun LedgerSectionHeader(text: String, topPadding: Dp) {
    Text(
        text,
        style = AppTypography.caption,
        fontWeight = FontWeight.SemiBold,
        color = AppTheme.colors.text.secondary,
        modifier = Modifier.padding(start = AppSpacing.screenGutter, end = AppSpacing.screenGutter, top = topPadding, bottom = 8.dp),
    )
}

@Composable
private fun LedgerHint(text: String) {
    Text(
        text,
        style = AppTypography.caption,
        color = AppTheme.colors.text.tertiary,
        modifier = Modifier.padding(start = AppSpacing.screenGutter, end = AppSpacing.screenGutter, top = 8.dp),
    )
}

@Composable
private fun GroupedRow(first: Boolean, last: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(
        topStart = if (first) 16.dp else 0.dp,
        topEnd = if (first) 16.dp else 0.dp,
        bottomStart = if (last) 16.dp else 0.dp,
        bottomEnd = if (last) 16.dp else 0.dp,
    )
    Column(
        modifier = modifier
            .padding(horizontal = AppSpacing.screenGutter)
            .clip(shape)
            .background(colors.surface.raised)
            .border(0.5.dp, colors.surface.stroke, shape),
    ) {
        if (!first) AppListDivider(startInset = 0.dp)
        content()
    }
}

@Composable
internal fun PromiseOpenRowBody(p: PromiseEntity, nowMillis: Long, mdPattern: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(9.dp).border(2.dp, colors.accent.primary, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(p.content, style = AppTypography.label, color = colors.text.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            PromiseMetaRow(p, nowMillis, mdPattern)
        }
        Icon(AppActionIcons.ChevronRight, null, Modifier.size(18.dp), tint = colors.text.tertiary)
    }
}

@Composable
private fun PromiseMetaRow(p: PromiseEntity, nowMillis: Long, mdPattern: String) {
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${PromiseUiFormat.format(p.createdAtMillis, mdPattern)} · " +
                stringResource(PromiseUiFormat.sourceLabelRes(p.sourceRaw, short = false)),
            style = AppTypography.caption,
            color = colors.text.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        p.dueAtMillis?.let { due ->
            val mdDue = PromiseUiFormat.format(due, mdPattern)
            if (PromiseInjectionRenderer.isDueUpcoming(due, nowMillis, ZoneId.systemDefault())) {
                Box(
                    Modifier
                        .clip(AppShapes.full)
                        .background(colors.surface.sunken)
                        .padding(horizontal = 9.dp, vertical = 2.dp),
                ) {
                    Text(stringResource(R.string.promise_due_upcoming, mdDue), style = AppTypography.caption, color = colors.text.secondary)
                }
            } else {
                Text(
                    stringResource(R.string.promise_due_overdue, mdDue),
                    style = AppTypography.caption,
                    fontWeight = FontWeight.Medium,
                    color = colors.status.onWarning,
                )
            }
        }
    }
}

@Composable
internal fun PromiseResolvedRowBody(p: PromiseEntity, mdPattern: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val fulfilled = p.statusRaw == PromiseStatus.FULFILLED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (fulfilled) R.string.promise_status_fulfilled else R.string.promise_status_cancelled),
                style = AppTypography.caption,
                fontWeight = FontWeight.SemiBold,
                color = if (fulfilled) colors.status.onSuccess else colors.text.secondary,
            )
            Text(
                p.content,
                style = AppTypography.label,
                color = if (fulfilled) colors.text.primary else colors.text.secondary,
                textDecoration = if (fulfilled) null else TextDecoration.LineThrough,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text(
            PromiseUiFormat.format(p.resolvedAtMillis ?: 0L, mdPattern),
            style = AppTypography.caption,
            color = colors.text.tertiary,
        )
        Icon(AppActionIcons.ChevronRight, null, Modifier.size(18.dp), tint = colors.text.tertiary)
    }
}

@Composable
internal fun PromiseLedgerEmptyState() {
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 96.dp, start = 40.dp, end = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(68.dp).clip(CircleShape).background(colors.accent.container), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Handshake, null, Modifier.size(34.dp), tint = colors.accent.text)
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.promise_empty_title), style = AppTypography.label, fontWeight = FontWeight.SemiBold, color = colors.text.primary)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.promise_empty_sub), style = AppTypography.secondary, color = colors.text.secondary, textAlign = TextAlign.Center)
    }
}
