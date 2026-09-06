package com.situ.aichat.ui.perflog

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.diagnostics.perf.PerfChecklist
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.contextlog.LogShareActions
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import kotlinx.coroutines.launch

/**
 * 性能采集页（图纸 §4.1）。用户 2026-07-30 拍板「照上下文日志同款就行」，故骨架逐条复刻
 * [com.situ.aichat.ui.contextlog.ContextLogSettingsScreen]：Scaffold + 门楣（[com.situ.aichat.ui.designsystem.AppTopBar]）+ 分节 + 动作区 + 隐私脚注。
 * **不引入任何新视觉语言**：无图表、无自绘、无新动效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerfCollectScreen(
    onBack: () -> Unit,
    viewModel: PerfCollectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toast by viewModel.toasts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    // 备份体检要用户自己挑文件——只读、只在用户显式点选后才碰那个文件。
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

    val checklistLabels = mapOf(
        PerfChecklist.ID_COLD_START to stringResource(R.string.perf_check_cold_start),
        PerfChecklist.ID_FOREGROUND to stringResource(R.string.perf_check_foreground),
        PerfChecklist.ID_SLIDER to stringResource(R.string.perf_check_slider),
        PerfChecklist.ID_WORLD to stringResource(R.string.perf_check_world),
        PerfChecklist.ID_CALL to stringResource(R.string.perf_check_call),
        PerfChecklist.ID_BACKUP to stringResource(R.string.perf_check_backup),
    )

    val scrollState = rememberScrollState()
    Scaffold(
        containerColor = AppTheme.colors.surface.base,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.perf_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState)) {
            SettingsSection(
                title = stringResource(R.string.perf_section_capture),
                footer = stringResource(R.string.perf_switch_footer),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.perf_switch_title),
                    checked = state.enabled,
                    onCheckedChange = { viewModel.setEnabled(it) },
                )
            }

            SettingsSection(
                title = stringResource(R.string.perf_section_checklist),
                footer = stringResource(R.string.perf_checklist_footer),
            ) {
                state.checklist.forEach { item ->
                    InfoRow(
                        label = checklistLabels[item.label] ?: item.label,
                        value = "${item.collected}/${item.required}",
                        // 已完成走 status.onSuccess（功能深档文字），未完成走三级文字色。
                        valueColor = if (item.done) AppTheme.colors.status.onSuccess else AppTheme.colors.text.tertiary,
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.perf_section_samples)) {
                state.sampleCounts.entries.sortedBy { it.key }.forEach { (kind, count) ->
                    InfoRow(label = kind, value = "$count")
                }
                InfoRow(label = stringResource(R.string.perf_usage), value = humanBytes(state.dirBytes))
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Spacer(Modifier.height(7.dp))
                ActionButton(stringResource(R.string.perf_action_export), enabled = !state.busy) {
                    scope.launch {
                        val report = viewModel.buildReport { id -> checklistLabels[id] ?: id }
                        if (report != null) LogShareActions.exportWithFeedback(context, report.second, report.first)
                    }
                }
                ActionButton(stringResource(R.string.perf_action_backup_probe), enabled = !state.busy) {
                    pickBackup.launch(arrayOf("*/*"))
                }
                ActionButton(stringResource(R.string.perf_action_fake_backup), enabled = !state.busy) {
                    viewModel.buildAndProbeFakeBackup(PerfCollectViewModel.FAKE_BACKUP_TARGET_BYTES)
                }
                ActionButton(stringResource(R.string.perf_action_clear), destructive = true, enabled = !state.busy) {
                    confirmClear = true
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = AppTheme.colors.text.tertiary,
                    modifier = Modifier.width(18.dp),
                )
                Text(
                    stringResource(R.string.perf_privacy_footnote),
                    style = AppTheme.typography.caption,
                    color = AppTheme.colors.text.tertiary,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        AppDialog(
            onDismissRequest = { confirmClear = false },
            title = stringResource(R.string.perf_clear_confirm_title),
            body = stringResource(R.string.perf_clear_confirm_body),
            confirmText = stringResource(R.string.perf_action_clear),
            onConfirm = { confirmClear = false; viewModel.clearAll() },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { confirmClear = false },
        )
    }
}

/** 只读信息行（镜像设置页 SettingsRow 的形态：标题在左、数值在右；不可点、无箭头）。 */
@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = AppTheme.colors.text.tertiary) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor, maxLines = 1)
    }
}

/** 动作钮（逐字复刻 ContextLogSettingsScreen 的同名私有件，另加 busy 期间禁用）。 */
@Composable
private fun ActionButton(
    label: String,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (destructive) AppTheme.colors.status.errorContainer else AppTheme.colors.surface.sunken
    val fg = if (destructive) AppTheme.colors.status.onError else AppTheme.colors.text.primary
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (enabled) bg else bg.copy(alpha = 0.4f), AppTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = AppTheme.typography.label,
            color = if (enabled) fg else fg.copy(alpha = 0.4f),
            textAlign = TextAlign.Center,
        )
    }
}

/** 人类可读的字节数（采集页「占用空间」）。琉璃卷五复用（`ui/liuli` 树借同一份实现·改这里两张脸同时变）。 */
internal fun humanBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
    else -> "$bytes B"
}
