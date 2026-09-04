package com.situ.aichat.ui.contextlog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.diagnostics.FailureRateAlert
import com.situ.aichat.diagnostics.LogListRow
import com.situ.aichat.diagnostics.LogCategory
import com.situ.aichat.diagnostics.LogTokenFormat
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTopBarIcons

/**
 * 上下文日志列表屏（批 D·D-3）。顶部分类 chip 横滚 + 卡片列表（状态/角色/来源徽标/模型/时间/消息数/token/失败红条）
 * + 两种空态 + 菜单（保留设置 / 清空全部）+ detail 关提示。Fable-5 设计语言（AppTheme token·暖纸卡·陶土选中 chip）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextLogListScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ContextLogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    Scaffold(
        containerColor = AppTheme.colors.surface.base,
        topBar = {
            AppTopBar(
                title = "上下文日志",
                onBack = onBack,
                lifted = listState.canScrollBackward,
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(AppTopBarIcons.More, contentDescription = "更多")
                    }
                    AppMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                        AppMenuItem(text = "保留设置", onClick = { menuOpen = false; onOpenSettings() })
                        AppMenuItem(text = "清空全部", onClick = { menuOpen = false; confirmClear = true }, danger = true)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            CategoryChips(
                selected = state.category,
                onSelect = viewModel::setCategory,
            )
            if (state.entries.isEmpty()) {
                // 复核 R1-🟡2：空筛选态也渲染告警条——健康是全局体检（VM 对全量列表算），
                // 不许因「当前筛选恰好无条目」而消失（如站在语音筛选下，故事的失败簇仍要亮）。
                if (state.alerts.isNotEmpty()) {
                    Box(Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp)) {
                        FailureAlertBanner(state.alerts) { viewModel.setCategory(LogCategory.FAILED) }
                    }
                }
                EmptyState(loaded = state.loaded, category = state.category, detailEnabled = state.detailEnabled)
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 失败率告警条（D-3 打磨·③）：体检结论先于流水账，列表最顶随滚动（非吸顶）；
                    // 无告警 = 整条不渲染（status 家族瞬态非常驻）；点击 = 切到「失败」筛选。
                    if (state.alerts.isNotEmpty()) {
                        item(key = "failure-alerts") {
                            FailureAlertBanner(state.alerts) { viewModel.setCategory(LogCategory.FAILED) }
                        }
                    }
                    // 缓存命中率汇总卡（四小件·2026-07-16·§4.2）：随列表滚动（非吸顶）；
                    // cacheSummary==null（当前筛选下无缓存数据）→ 整卡不渲染，无空态占位。
                    state.cacheSummary?.let { summary ->
                        item(key = "cache-summary") { CacheSummaryCard(summary) }
                    }
                    items(state.entries, key = { it.id }) { entry ->
                        LogListCard(entry = entry, onClick = { onOpenDetail(entry.id) })
                    }
                    if (!state.detailEnabled) {
                        item { Spacer(Modifier.height(2.dp)); DetailOffHint() }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AppDialog(
            onDismissRequest = { confirmClear = false },
            title = "清空全部日志？",
            body = "将永久删除所有上下文日志记录，无法恢复。",
            confirmText = "清空",
            onConfirm = { confirmClear = false; viewModel.clearAll() },
            confirmTone = AppDialogTone.Danger,
            dismissText = "取消",
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun CategoryChips(selected: LogCategory, onSelect: (LogCategory) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items(LogCategory.entries.toList()) { cat ->
            val on = cat == selected
            val brush = Brush.linearGradient(
                listOf(AppTheme.colors.accent.gradientStart, AppTheme.colors.accent.gradientEnd),
            )
            Box(
                modifier = Modifier
                    .clip(AppTheme.shapes.full)
                    .then(
                        if (on) Modifier.background(brush, AppTheme.shapes.full)
                        else Modifier.background(AppTheme.colors.surface.sunken, AppTheme.shapes.full),
                    )
                    .clickable { onSelect(cat) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Text(
                    cat.displayName,
                    style = AppTheme.typography.secondary,
                    color = if (on) AppTheme.colors.text.onAccent else AppTheme.colors.text.secondary,
                )
            }
        }
    }
}

/**
 * 失败率告警条（D-3 打磨·③·mockup §1）：status.warning 家族配色（警示语义专用·与经济金物理隔离），
 * 每来源一行「「故事生成」失败 3/5（60%）」，最多 [ContextLogViewModel.MAX_ALERT_LINES] 行；
 * 整条可点 → 切「失败」筛选（chip 本就存在，告警条只是引路人）。a11y：整条一个语义节点播全句。
 */
@Composable
private fun FailureAlertBanner(alerts: List<FailureRateAlert>, onShowFailed: () -> Unit) {
    val title = stringResource(R.string.contextlog_alert_title)
    val lines = alerts.map {
        stringResource(R.string.contextlog_alert_line, it.source, it.failures, it.total, it.percent)
    }
    val action = stringResource(R.string.contextlog_alert_action)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppTheme.shapes.medium)
            .background(AppTheme.colors.status.warningContainer)
            .border(1.dp, AppTheme.colors.status.onWarning.copy(alpha = 0.14f), AppTheme.shapes.medium)
            .clickable(onClickLabel = action, onClick = onShowFailed)
            .padding(horizontal = 13.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title：${lines.joinToString("；")}。$action"
            },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = AppTheme.colors.status.onWarning,
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AppTheme.typography.secondary.copy(fontWeight = FontWeight.SemiBold),
                color = AppTheme.colors.status.onWarning,
            )
            Spacer(Modifier.height(3.dp))
            lines.forEach { line ->
                Text(
                    line,
                    style = AppTheme.typography.caption,
                    color = AppTheme.colors.status.onWarning.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            "$action ›",
            style = AppTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
            color = AppTheme.colors.status.onWarning,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/**
 * 缓存命中率汇总卡（四小件·2026-07-16·§4.2）：左标签 + 右数值（tnum 等宽数字）+ 次行条目数。
 * 卡壳照日志屏既有纹路（raised 底 + 1dp stroke + medium 圆角）；静态信息卡——无动效、无触觉、不可点。
 * a11y：整卡一个语义节点，读屏播两资源拼的全句（不逐字念散装数字）。
 */
@Composable
private fun CacheSummaryCard(summary: CacheSummary) {
    val label = stringResource(R.string.contextlog_cache_rate_label)
    val countText = stringResource(R.string.contextlog_cache_rate_count, summary.entryCount)
    val rateText = stringResource(R.string.contextlog_cache_rate_inline, summary.ratePercent)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppTheme.shapes.medium)
            .background(AppTheme.colors.surface.raised)
            .border(1.dp, AppTheme.colors.surface.stroke, AppTheme.shapes.medium)
            .padding(14.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label：$rateText，$countText" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = AppTheme.typography.caption,
            color = AppTheme.colors.text.secondary,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${summary.ratePercent}%",
                style = AppTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                color = AppTheme.colors.text.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(countText, style = AppTheme.typography.caption, color = AppTheme.colors.text.tertiary)
        }
    }
}

@Composable
private fun LogListCard(entry: LogListRow, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppTheme.shapes.medium)
            .background(AppTheme.colors.surface.raised)
            .border(1.dp, AppTheme.colors.surface.stroke, AppTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (!entry.isSuccess) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(AppTheme.colors.status.onError))
            }
            Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
                StatusGlyph(entry.isSuccess)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            primaryName(entry),
                            style = AppTheme.typography.bodyEmphasis,
                            color = if (entry.characterName.isBlank()) AppTheme.colors.accent.text else AppTheme.colors.text.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (showSourceBadgeResolved(entry)) {
                            Spacer(Modifier.width(7.dp))
                            SourceBadge(entry.source)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${entry.modelName} · ${formatLogTime(entry.timestampMillis)}",
                        style = AppTheme.typography.caption,
                        color = AppTheme.colors.text.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    if (entry.isSuccess) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dur = formatDuration(entry.durationMillis)
                            Text(
                                buildString {
                                    append("发送 ${entry.messageCount} 条")
                                    if (dur != null) append(" · 回复 $dur")
                                },
                                style = AppTheme.typography.caption,
                                color = AppTheme.colors.text.secondary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                LogTokenFormat.withEstimatePrefix(
                                    entry.promptTokens + entry.completionTokens, entry.isTokenEstimated,
                                ) + " tk",
                                style = AppTheme.typography.captionNumeric,
                                color = AppTheme.colors.economy.gold,
                            )
                        }
                    } else {
                        Text(
                            entry.errorMessage ?: "调用失败",
                            style = AppTheme.typography.caption,
                            color = AppTheme.colors.status.onError,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SourceBadge(source: String) {
    Box(
        Modifier
            .background(AppTheme.colors.surface.sunken, AppTheme.shapes.full)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(source, style = AppTheme.typography.caption, color = AppTheme.colors.accent.text)
    }
}

@Composable
private fun EmptyState(loaded: Boolean, category: LogCategory, detailEnabled: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loaded) {
            Text(
                if (category == LogCategory.ALL) "还没有任何调用日志" else "「${category.displayName}」分类下暂无日志",
                style = AppTheme.typography.body,
                color = AppTheme.colors.text.tertiary,
            )
            if (!detailEnabled) {
                Spacer(Modifier.height(16.dp))
                DetailOffHint()
            }
        }
    }
}

@Composable
private fun DetailOffHint() {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(AppTheme.shapes.medium)
            .background(AppTheme.colors.surface.sunken)
            .padding(13.dp),
    ) {
        Text(
            "详细记录已关闭——卡片只显元数据与分段统计，上下文 / 回复正文不留存。可在「保留设置」中开启。",
            style = AppTheme.typography.caption,
            color = AppTheme.colors.text.secondary,
        )
    }
}
