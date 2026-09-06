package com.situ.aichat.ui.liuli.perflog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.situ.aichat.R
import com.situ.aichat.diagnostics.perf.PerfChecklist
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.page.LiuliDangerRow
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.LiuliValueRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.perflog.PerfCollectViewModel
import com.situ.aichat.ui.perflog.PerfToast
import com.situ.aichat.ui.perflog.humanBytes
import com.situ.aichat.ui.contextlog.LogShareActions
import kotlinx.coroutines.launch
import com.situ.aichat.ui.liuli.page.LiuliTextActionRow

/** 脚注行的锁图标尺寸与它到文字的缝（逐字照暖陶 18 / 8）。 */
private val LOCK_ICON = 18.dp
private val LOCK_GAP = 8.dp

/**
 * 性能采集页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 27「动作组 + 危险行」）。与暖陶 `PerfCollectScreen`
 * 共用 [PerfCollectViewModel]。
 *
 * 清单标签表 `checklistLabels` 在暖陶那边是**函数体内的局部 `val`**（不是顶层常量），提不了 internal
 * （图纸 §11 D-3），故这里自写同一张表——**用的是同一批资源键**（`perf_check_*`），零新增键。
 * 四枚动作全部 `enabled = !busy`（E22）；清空走危险行 + 确认弹窗。
 */
@Composable
fun LiuliPerfCollectScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PerfCollectViewModel = hiltViewModel(),
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toast by viewModel.toasts.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    // 与暖陶 `checklistLabels` 同值同键（D-3）。
    val checklistLabels = mapOf(
        PerfChecklist.ID_COLD_START to stringResource(R.string.perf_check_cold_start),
        PerfChecklist.ID_FOREGROUND to stringResource(R.string.perf_check_foreground),
        PerfChecklist.ID_SLIDER to stringResource(R.string.perf_check_slider),
        PerfChecklist.ID_WORLD to stringResource(R.string.perf_check_world),
        PerfChecklist.ID_CALL to stringResource(R.string.perf_check_call),
        PerfChecklist.ID_BACKUP to stringResource(R.string.perf_check_backup),
    )

    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.probeBackup(uri)
    }

    LaunchedEffect(toast) {
        val resId = when (toast) {
            PerfToast.EXPORT_EMPTY -> R.string.perf_export_empty
            PerfToast.EXPORT_FAILED -> R.string.perf_export_failed
            PerfToast.PROBE_DONE -> R.string.perf_probe_done
            PerfToast.FAKE_BACKUP_DONE -> R.string.perf_fake_backup_done
            PerfToast.FAKE_BACKUP_FAILED -> R.string.perf_fake_backup_failed
            null -> null
        }
        if (resId != null) {
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    val colors = AppTheme.colors
    val title = stringResource(R.string.perf_title)
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    LiuliGroup(
                        header = stringResource(R.string.perf_section_capture),
                        footer = stringResource(R.string.perf_switch_footer),
                    ) {
                        LiuliToggleRow(
                            title = stringResource(R.string.perf_switch_title),
                            checked = state.enabled,
                            onCheckedChange = viewModel::setEnabled,
                            divider = false,
                        )
                    }
                    LiuliGroup(
                        header = stringResource(R.string.perf_section_checklist),
                        footer = stringResource(R.string.perf_checklist_footer),
                    ) {
                        state.checklist.forEachIndexed { index, item ->
                            LiuliValueRow(
                                title = checklistLabels[item.label] ?: item.label,
                                value = "${item.collected}/${item.required}",
                                // 已采够走 status.onSuccess，未采够走三级文字色（暖陶 :133 同判据·复核 R1 A-4 补回）。
                                valueColor = if (item.done) colors.status.onSuccess else colors.text.tertiary,
                                divider = index > 0,
                            )
                        }
                    }
                    LiuliGroup(header = stringResource(R.string.perf_section_samples)) {
                        state.sampleCounts.entries.sortedBy { it.key }.forEachIndexed { index, (kind, count) ->
                            LiuliValueRow(title = kind, value = "$count", divider = index > 0)
                        }
                        LiuliValueRow(
                            title = stringResource(R.string.perf_usage),
                            value = humanBytes(state.dirBytes),
                            // 样本为空时它是组里第一行 → 不画顶发丝（首行铁律）。
                            divider = state.sampleCounts.isNotEmpty(),
                        )
                    }
                    // 动作组：四行**留在原位**，忙碌时整行淡出禁点（暖陶四钮 `enabled = !busy`·复核 R1 A-5）。
                    LiuliGroup {
                        LiuliTextActionRow(
                            title = stringResource(R.string.perf_action_export),
                            enabled = !state.busy,
                            onClick = {
                                scope.launch {
                                    val report = viewModel.buildReport { id -> checklistLabels[id] ?: id }
                                    if (report != null) {
                                        LogShareActions.exportWithFeedback(context, report.second, report.first)
                                    }
                                }
                            },
                            divider = false,
                        )
                        LiuliTextActionRow(
                            title = stringResource(R.string.perf_action_backup_probe),
                            enabled = !state.busy,
                            onClick = { pickBackup.launch(arrayOf("*/*")) },
                        )
                        LiuliTextActionRow(
                            title = stringResource(R.string.perf_action_fake_backup),
                            enabled = !state.busy,
                            onClick = { viewModel.buildAndProbeFakeBackup(PerfCollectViewModel.FAKE_BACKUP_TARGET_BYTES) },
                        )
                        LiuliDangerRow(
                            title = stringResource(R.string.perf_action_clear),
                            enabled = !state.busy,
                            onClick = { confirmClear = true },
                        )
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
                        Text(
                            stringResource(R.string.perf_privacy_footnote),
                            style = AppTypography.caption,
                            color = colors.text.tertiary,
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        LiuliDialog(
            onDismissRequest = { confirmClear = false },
            title = stringResource(R.string.perf_clear_confirm_title),
            body = stringResource(R.string.perf_clear_confirm_body),
            confirmText = stringResource(R.string.perf_action_clear),
            confirmDanger = true,
            onConfirm = { confirmClear = false; viewModel.clearAll() },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { confirmClear = false },
        )
    }
}
