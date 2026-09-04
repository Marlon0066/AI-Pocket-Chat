package com.situ.aichat.ui.promise

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.promise.PromiseInjectionRenderer
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import java.time.ZoneId

/**
 * 「我们的约定」账本子页（记忆改造三期·D-3·图纸 §4.2）：TopAppBar + 两节平铺 LazyColumn（进行中全量 +
 * 已了结全部历史）+ 空态。每行分组圆角、可点（≥48dp 触达）→ 详情 sheet（U4 接入）。排序 / 到期判据取
 * [PromiseInjectionRenderer] 单源（VM 侧），UI 不复制第二份（D-7）。
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

    val listState = rememberLazyListState()
    Scaffold(
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
            modifier = Modifier.fillMaxSize().padding(padding),
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
                        OpenRowBody(p, nowMillis, mdPattern, onClick = { viewModel.select(p.uuid) })
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
                        ResolvedRowBody(p, mdPattern, onClick = { viewModel.select(p.uuid) })
                    }
                }
                item(key = "resolved_hint") { LedgerHint(stringResource(R.string.promise_hint_resolved)) }
            }
        }

        // 详情 sheet + 二次确认框挂 Screen 层（照钱包 sheet 先例）：detail 从 Flow 派生 → 背景对账改状态时实时跟变（E1）。
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

/** 行动效（D-5·§4.2）：淡入淡出走效果轴恒 ζ1.0、位移走空间轴 gentle 档；reduceMotion 降级瞬时落位（裸 Modifier）。 */
private fun LazyItemScope.ledgerRowModifier(reduceMotion: Boolean): Modifier =
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
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = topPadding, bottom = 8.dp),
    )
}

@Composable
private fun LedgerHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
    )
}

/** 分组圆角行容器：首行顶角 16dp / 末行底角 16dp / 中间 0；非首行内嵌分割线。[modifier]=行动效（U4 注入）。 */
@Composable
private fun GroupedRow(first: Boolean, last: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(
            topStart = if (first) 16.dp else 0.dp,
            topEnd = if (first) 16.dp else 0.dp,
            bottomStart = if (last) 16.dp else 0.dp,
            bottomEnd = if (last) 16.dp else 0.dp,
        ),
        modifier = modifier.padding(horizontal = 16.dp),
    ) {
        Column {
            if (!first) AppListDivider(startInset = 0.dp)
            content()
        }
    }
}

@Composable
private fun OpenRowBody(p: PromiseEntity, nowMillis: Long, mdPattern: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(9.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(p.content, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            PromiseMetaRow(p, nowMillis, mdPattern)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 进行中行元信息（同资料页卡 §4.1）：「{M月d日} · 聊天中/见面时定下」+ 到期件（数据判据单源·非视觉耦合）。 */
@Composable
private fun PromiseMetaRow(p: PromiseEntity, nowMillis: Long, mdPattern: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${PromiseUiFormat.format(p.createdAtMillis, mdPattern)} · " +
                stringResource(PromiseUiFormat.sourceLabelRes(p.sourceRaw, short = false)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        p.dueAtMillis?.let { due ->
            val mdDue = PromiseUiFormat.format(due, mdPattern)
            if (PromiseInjectionRenderer.isDueUpcoming(due, nowMillis, ZoneId.systemDefault())) {
                Surface(shape = AppShapes.full, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text(
                        stringResource(R.string.promise_due_upcoming, mdDue),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
                    )
                }
            } else {
                Text(
                    stringResource(R.string.promise_due_overdue, mdDue),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = AppTheme.colors.status.onWarning,
                )
            }
        }
    }
}

@Composable
private fun ResolvedRowBody(p: PromiseEntity, mdPattern: String, onClick: () -> Unit) {
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
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (fulfilled) AppTheme.colors.status.onSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                p.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (fulfilled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = if (fulfilled) null else TextDecoration.LineThrough,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text(
            PromiseUiFormat.format(p.resolvedAtMillis ?: 0L, mdPattern),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PromiseLedgerEmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 96.dp, start = 40.dp, end = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Handshake,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.promise_empty_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.promise_empty_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}
