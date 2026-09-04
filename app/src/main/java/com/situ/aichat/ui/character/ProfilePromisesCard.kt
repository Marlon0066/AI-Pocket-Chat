package com.situ.aichat.ui.character

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.promise.PromiseInjectionRenderer
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.promise.PromiseUiFormat
import java.time.ZoneId

/**
 * 资料页「我们的约定」卡状态（记忆改造三期·D-1/D-2·图纸 §3.2）：由 VM 计算、UI 文件持有
 * （先例 [ScheduleCardState]）。排序 / 7 天窗单源 = [PromiseInjectionRenderer]，UI 不复制第二份（D-7）。
 */
internal data class PromiseCardState(
    val openPreview: List<PromiseEntity>, // sortedOpen 前 3（D-2）
    val openCount: Int,                   // open 全量数（徽章）
    val latestResolved: PromiseEntity?,   // 近 7 天窗最新一条（窗常量 = PromiseInjectionRenderer.RESOLVED_WINDOW_MS）
    val totalCount: Int,                  // open + resolved 全部（「查看全部 N 条」的 N）
) {
    val hasAny: Boolean get() = totalCount > 0

    companion object {
        val EMPTY = PromiseCardState(emptyList(), 0, null, 0)

        fun compute(open: List<PromiseEntity>, resolved: List<PromiseEntity>, nowMillis: Long): PromiseCardState {
            val sorted = PromiseInjectionRenderer.sortedOpen(open)
            val latest = resolved
                .filter { (it.resolvedAtMillis ?: Long.MIN_VALUE) >= nowMillis - PromiseInjectionRenderer.RESOLVED_WINDOW_MS }
                .maxByOrNull { it.resolvedAtMillis ?: Long.MIN_VALUE }
            return PromiseCardState(sorted.take(3), open.size, latest, open.size + resolved.size)
        }
    }
}

/**
 * 资料页「我们的约定」卡（记忆改造三期·D-1/D-2·图纸 §4.1）：Handshake 头 + 进行中预览 3 条 +
 * 近 7 天了结微区 + 恒显「查看全部」页脚。卡体与预览行**不可点**（单一入口=页脚·D-5）。整卡是否渲染由
 * Screen 侧 [PromiseCardState.hasAny] 门控（一条都没有则整卡不渲染·D-1）。
 */
@Composable
internal fun ProfilePromisesCard(
    state: PromiseCardState,
    nowMillis: Long,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mdPattern = stringResource(R.string.promise_date_pattern_md)
    ProfileCard(modifier) {
        // 头行：Handshake 图标标题（heading 语义由 CardSectionHeader 自带）+ 计数徽章（openCount>0 才显）。
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            CardSectionHeader(Icons.Filled.Handshake, MaterialTheme.colorScheme.primary, stringResource(R.string.promise_title))
            Spacer(Modifier.weight(1f))
            if (state.openCount > 0) {
                Surface(shape = AppShapes.full, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text(
                        stringResource(R.string.promise_card_open_count, state.openCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }

        // 进行中预览（每条行顶距 13dp）。
        state.openPreview.forEach { p ->
            Spacer(Modifier.height(13.dp))
            PromiseOpenPreviewRow(p, nowMillis, mdPattern)
        }

        // 进行中为 0 但仍有历史（了结微区 / 页脚照走）。
        if (state.openPreview.isEmpty() && state.hasAny) {
            Text(
                stringResource(R.string.promise_card_empty_open),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        // 最近了结微区（近 7 天窗最新 1 条）。
        state.latestResolved?.let { r ->
            Spacer(Modifier.height(14.dp))
            AppListDivider(startInset = 0.dp)
            Spacer(Modifier.height(12.dp))
            PromiseResolvedMicroRow(r, mdPattern)
        }

        // 页脚（恒显·单一入口=查看全部）。
        Spacer(Modifier.height(8.dp))
        AppButton(onClick = onOpenAll, style = AppButtonStyle.Text, contentPadding = PaddingValues(vertical = 4.dp)) {
            Text(stringResource(R.string.promise_card_view_all, state.totalCount), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 进行中预览一行：陶土空心环圆点 + 内容(2 行截断) + 元信息行「{M月d日} · 聊天中/见面时定下」+ 到期件。 */
@Composable
private fun PromiseOpenPreviewRow(p: PromiseEntity, nowMillis: Long, mdPattern: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(9.dp)
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
        )
        Column {
            Text(
                p.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
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
    }
}

/** 了结微区一行：已兑现=✓绿标+内容；已取消=灰标+内容删除线。日期取 resolvedAtMillis。 */
@Composable
private fun PromiseResolvedMicroRow(p: PromiseEntity, mdPattern: String) {
    val md = PromiseUiFormat.format(p.resolvedAtMillis ?: 0L, mdPattern)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        if (p.statusRaw == PromiseStatus.FULFILLED) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(15.dp),
                tint = AppTheme.colors.status.onSuccess,
            )
            Column {
                Text(
                    stringResource(R.string.promise_status_fulfilled_dated, md),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.status.onSuccess,
                )
                Text(
                    p.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        } else {
            Column {
                Text(
                    stringResource(R.string.promise_status_cancelled_dated, md),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    p.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.LineThrough,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
