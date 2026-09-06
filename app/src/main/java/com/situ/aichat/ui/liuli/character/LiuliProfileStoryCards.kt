package com.situ.aichat.ui.liuli.character

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.promise.PromiseInjectionRenderer
import com.situ.aichat.ui.character.PromiseCardState
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.promise.PromiseUiFormat
import java.time.ZoneId

// 落值 1:1 暖陶（那边全是内联字面量）。
private val PREVIEW_ROW_TOP = 13.dp
private val DOT = 9.dp
private val CHECK = 15.dp

/**
 * 「我们的约定」卡（琉璃·搬暖陶 `ProfilePromisesCard`）：计数徽章 + 进行中预览 3 条 +
 * 近 7 天了结微区 + 恒显「查看全部」页脚。卡体与预览行**不可点**（单一入口 = 页脚·D-5）；
 * 整卡是否渲染由屏侧 `PromiseCardState.hasAny` 门控（一条都没有则整卡不渲染·D-1）。
 */
@Composable
internal fun LiuliProfilePromisesCard(
    state: PromiseCardState,
    nowMillis: Long,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val mdPattern = stringResource(R.string.promise_date_pattern_md)
    LiuliGroup(modifier = modifier, header = stringResource(R.string.promise_title)) {
        LiuliRowBase(
            divider = false,
            minHeight = 0.dp,
            verticalPadding = LiuliPageGeometry.groupPadH,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (state.openCount > 0) {
                    Box(
                        Modifier
                            .align(Alignment.End)
                            .clip(LiuliShapes.pill)
                            .background(colors.surface.sunken)
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Text(
                            stringResource(R.string.promise_card_open_count, state.openCount),
                            style = AppTypography.caption,
                            color = colors.text.secondary,
                        )
                    }
                }
                state.openPreview.forEach { p ->
                    Spacer(Modifier.height(PREVIEW_ROW_TOP))
                    PromiseOpenPreviewRow(p, nowMillis, mdPattern)
                }
                // 进行中为 0 但仍有历史（了结微区 / 页脚照走）。
                if (state.openPreview.isEmpty() && state.hasAny) {
                    Text(
                        stringResource(R.string.promise_card_empty_open),
                        style = AppTypography.listPreview,
                        color = colors.text.tertiary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                state.latestResolved?.let { r ->
                    Spacer(Modifier.height(14.dp))
                    Hairline()
                    Spacer(Modifier.height(12.dp))
                    PromiseResolvedMicroRow(r, mdPattern)
                }
                Spacer(Modifier.height(8.dp))
                LiuliButton(onClick = onOpenAll, style = LiuliButtonStyle.Text) {
                    Text(stringResource(R.string.promise_card_view_all, state.totalCount))
                }
            }
        }
    }
}

/** 进行中预览一行：空心环圆点 + 内容（2 行截断）+ 元信息行「{M月d日} · 聊天中 / 见面时定下」+ 到期件。 */
@Composable
private fun PromiseOpenPreviewRow(p: PromiseEntity, nowMillis: Long, mdPattern: String) {
    val colors = AppTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(DOT).border(2.dp, colors.accent.primary, CircleShape))
        Column {
            Text(
                p.content,
                style = AppTypography.listPreview,
                color = colors.text.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${PromiseUiFormat.format(p.createdAtMillis, mdPattern)} · " +
                        stringResource(PromiseUiFormat.sourceLabelRes(p.sourceRaw, short = false)),
                    style = AppTypography.secondary,
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
                                .clip(LiuliShapes.pill)
                                .background(colors.surface.sunken)
                                .padding(horizontal = 9.dp, vertical = 2.dp),
                        ) {
                            Text(
                                stringResource(R.string.promise_due_upcoming, mdDue),
                                style = AppTypography.caption,
                                color = colors.text.secondary,
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.promise_due_overdue, mdDue),
                            style = AppTypography.caption.copy(fontWeight = FontWeight.W500),
                            color = colors.status.onWarning,
                        )
                    }
                }
            }
        }
    }
}

/** 了结微区一行：已兑现 = ✓绿标 + 内容；已取消 = 灰标 + 内容删除线。日期取 `resolvedAtMillis`。 */
@Composable
private fun PromiseResolvedMicroRow(p: PromiseEntity, mdPattern: String) {
    val colors = AppTheme.colors
    val md = PromiseUiFormat.format(p.resolvedAtMillis ?: 0L, mdPattern)
    val fulfilled = p.statusRaw == PromiseStatus.FULFILLED
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        if (fulfilled) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(CHECK),
                tint = colors.status.onSuccess,
            )
        }
        Column {
            Text(
                stringResource(
                    if (fulfilled) R.string.promise_status_fulfilled_dated else R.string.promise_status_cancelled_dated,
                    md,
                ),
                style = AppTypography.caption.copy(fontWeight = FontWeight.W600),
                color = if (fulfilled) colors.status.onSuccess else colors.text.secondary,
            )
            Text(
                p.content,
                style = AppTypography.secondary,
                color = colors.text.secondary,
                textDecoration = if (fulfilled) null else TextDecoration.LineThrough,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
