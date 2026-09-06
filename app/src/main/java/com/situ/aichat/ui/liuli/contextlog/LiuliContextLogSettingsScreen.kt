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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.diagnostics.perf.PerfSettingsSites
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.diagnostics.perf.rememberSettingsWriteRecorder
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.page.LiuliDangerRow
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliSliderRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.LiuliValueRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.contextlog.ContextLogViewModel
import kotlin.math.roundToInt
import com.situ.aichat.ui.liuli.page.LiuliTextActionRow

/**
 * 上下文日志「日志设置」页的全部文案（琉璃·A-6「硬编码中文屏逐字复制」）。
 * 暖陶 `ContextLogSettingsScreen.kt` **一枚资源键都没有**，本卷零新增键，故这里逐字复制一份。
 * **改任一侧必须同步另一侧**（登记契约 §10.3 F 备注）。
 */
private object ContextLogText {
    const val TITLE = "日志设置"
    const val CAPACITY_HEADER = "容量"
    const val RETENTION_LABEL = "保留条数"
    const val RETENTION_UNIT = "条"
    const val PRIVACY_HEADER = "隐私"
    const val PRIVACY_FOOTER =
        "默认仅存元数据与分段统计；开启后才记录完整上下文与回复正文（仅本地，绝不含 API 密钥）。"
    const val DETAIL_ROW = "记录完整详细内容"
    const val ACTION_PURGE = "清除既有日志全文（保留元数据）"
    const val ACTION_CLEAR = "清空全部日志"
    const val FOOTNOTE = "绝不记录 API 密钥 · 容量自动轮转 · 日志不进备份导出 · 纯本地"
    const val PURGE_TITLE = "清除日志全文？"
    const val PURGE_BODY = "删除所有已存的上下文与回复正文，保留每条记录的元数据与分段统计。"
    const val PURGE_CONFIRM = "清除"
    const val CLEAR_TITLE = "清空全部日志？"
    const val CLEAR_BODY = "永久删除所有上下文日志记录，无法恢复。"
    const val CLEAR_CONFIRM = "清空"
    const val CANCEL = "取消"
}

/** 脚注行的锁图标尺寸与它到文字的缝（逐字照暖陶 18 / 8）。 */
private val LOCK_ICON = 18.dp
private val LOCK_GAP = 8.dp

/**
 * 上下文日志设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 20）。与暖陶 `ContextLogSettingsScreen` 共用
 * [ContextLogViewModel]。
 *
 * 机制锁（F8·逐字搬）：**保留条数「拖动只更本地态、松手才写」**——`setRetentionCount` 会立即真删日志，
 * 每档即写会让「500 → 10 → 300」在经过 10 那一档时把库裁到 10 条且拖回不恢复（暖陶 `:132–144` 记着的真 bug）；
 * 数字标签跟本地态走，否则拖动中数字会冻住。埋点 `rememberSettingsWriteRecorder` 一并搬。
 */
@Composable
fun LiuliContextLogSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContextLogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiuliContextLogSettingsContent(
        retentionCount = state.retentionCount,
        detailEnabled = state.detailEnabled,
        onCommitRetention = viewModel::setRetentionCount,
        onSetDetailEnabled = viewModel::setDetailEnabled,
        onPurgeFullText = viewModel::purgeFullText,
        onClearAll = viewModel::clearAll,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 日志设置页内容层（纯参数·可测）。两枚危险动作各自带一道确认。 */
@Composable
internal fun LiuliContextLogSettingsContent(
    retentionCount: Int,
    detailEnabled: Boolean,
    onCommitRetention: (Int) -> Unit,
    onSetDetailEnabled: (Boolean) -> Unit,
    onPurgeFullText: () -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = AppTheme.colors
    var confirmPurge by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = ContextLogText.TITLE,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(ContextLogText.TITLE) }
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    LiuliGroup(header = ContextLogText.CAPACITY_HEADER) {
                        LiuliRetentionSliderRow(savedCount = retentionCount, onCommit = onCommitRetention)
                    }
                    LiuliGroup(header = ContextLogText.PRIVACY_HEADER, footer = ContextLogText.PRIVACY_FOOTER) {
                        LiuliToggleRow(
                            title = ContextLogText.DETAIL_ROW,
                            checked = detailEnabled,
                            onCheckedChange = onSetDetailEnabled,
                            divider = false,
                        )
                    }
                    LiuliGroup {
                        // 「清除全文」保留元数据 = 可恢复不了但不毁记录 → 走普通值行；「清空全部」不可恢复 → 危险行。
                        LiuliTextActionRow(
                            title = ContextLogText.ACTION_PURGE,
                            onClick = { confirmPurge = true },
                            divider = false,
                        )
                        LiuliDangerRow(title = ContextLogText.ACTION_CLEAR, onClick = { confirmClear = true })
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.groupPadH),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LOCK_GAP),
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = colors.text.tertiary,
                            modifier = Modifier.width(LOCK_ICON),
                        )
                        Text(ContextLogText.FOOTNOTE, style = AppTypography.caption, color = colors.text.tertiary)
                    }
                }
            }
        }
    }

    if (confirmPurge) {
        LiuliDialog(
            onDismissRequest = { confirmPurge = false },
            title = ContextLogText.PURGE_TITLE,
            body = ContextLogText.PURGE_BODY,
            confirmText = ContextLogText.PURGE_CONFIRM,
            onConfirm = { confirmPurge = false; onPurgeFullText() },
            dismissText = ContextLogText.CANCEL,
            onDismiss = { confirmPurge = false },
        )
    }
    if (confirmClear) {
        LiuliDialog(
            onDismissRequest = { confirmClear = false },
            title = ContextLogText.CLEAR_TITLE,
            body = ContextLogText.CLEAR_BODY,
            confirmText = ContextLogText.CLEAR_CONFIRM,
            confirmDanger = true,
            onConfirm = { confirmClear = false; onClearAll() },
            dismissText = ContextLogText.CANCEL,
            onDismiss = { confirmClear = false },
        )
    }
}

/**
 * 保留条数滑杆（琉璃版·**时序逐字照暖陶 `RetentionSliderRow`**）：拖动只更本地态、松手才 [onCommit]。
 * 抽成独立可组合件同样为了可测——整屏在 `LazyColumn` 里，Robolectric 下滚动容器会吞掉滑杆的横向拖动。
 */
@Composable
internal fun LiuliRetentionSliderRow(savedCount: Int, onCommit: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: savedCount
    // 尺 4（性能采集卷 0）：数一趟拖动里滑杆变了多少次 = 未改的调用点会做多少次 DataStore 全量重写。
    val writeRecorder = rememberSettingsWriteRecorder(
        PerfSettingsSites.SCREEN_CONTEXT_LOG,
        PerfSettingsSites.KEY_LOG_RETENTION,
    )
    LiuliSliderRow(
        title = ContextLogText.RETENTION_LABEL,
        valueLabel = "$shown ${ContextLogText.RETENTION_UNIT}",
        value = shown.toFloat(),
        valueRange = 10f..500f,
        steps = 48,
        divider = false,
        onManualInput = onCommit, // 可手填超限（= iOS）·仍即时生效
        onValueChangeFinished = {
            dragging?.let(onCommit)
            dragging = null
            writeRecorder.onGestureEnd()
        },
        onValueChange = {
            dragging = (it / 10f).roundToInt() * 10
            writeRecorder.onTick()
        },
    )
}
