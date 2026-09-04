package com.situ.aichat.ui.contextlog

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.diagnostics.perf.PerfSettingsSites
import com.situ.aichat.diagnostics.perf.rememberSettingsWriteRecorder
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSliderRow
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import kotlin.math.roundToInt

/** 日志保留设置屏（批 D·D-3）：保留条数滑块（10–500·可手填超限）+ detail 开关 + 清全文 + 清空全部 + 隐私脚注。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextLogSettingsScreen(
    onBack: () -> Unit,
    viewModel: ContextLogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmPurge by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    Scaffold(
        containerColor = AppTheme.colors.surface.base,
        topBar = {
            AppTopBar(
                title = "日志设置",
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState),
        ) {
            SettingsSection(title = "容量") {
                RetentionSliderRow(
                    savedCount = state.retentionCount,
                    onCommit = { viewModel.setRetentionCount(it) },
                )
            }

            SettingsSection(
                title = "隐私",
                footer = "默认仅存元数据与分段统计；开启后才记录完整上下文与回复正文（仅本地，绝不含 API 密钥）。",
            ) {
                SettingsSwitchRow(
                    title = "记录完整详细内容",
                    checked = state.detailEnabled,
                    onCheckedChange = { viewModel.setDetailEnabled(it) },
                )
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                ActionButton("清除既有日志全文（保留元数据）") { confirmPurge = true }
                ActionButton("清空全部日志", destructive = true) { confirmClear = true }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = AppTheme.colors.text.tertiary, modifier = Modifier.width(18.dp))
                Text(
                    "绝不记录 API 密钥 · 容量自动轮转 · 日志不进备份导出 · 纯本地",
                    style = AppTheme.typography.caption,
                    color = AppTheme.colors.text.tertiary,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmPurge) {
        ConfirmDialog(
            title = "清除日志全文？",
            body = "删除所有已存的上下文与回复正文，保留每条记录的元数据与分段统计。",
            confirmLabel = "清除",
            onConfirm = { confirmPurge = false; viewModel.purgeFullText() },
            onDismiss = { confirmPurge = false },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "清空全部日志？",
            body = "永久删除所有上下文日志记录，无法恢复。",
            confirmLabel = "清空",
            destructive = true,
            onConfirm = { confirmClear = false; viewModel.clearAll() },
            onDismiss = { confirmClear = false },
        )
    }
}

/**
 * 保留条数滑杆（**拖动只更本地态、松手才写入**）。
 *
 * 修的是真 bug：原先每跨一档就 [onCommit]，而 `setRetentionCount` 会**立即真删日志**——从 500 拖到 10 再拖回
 * 300，经过 10 那一档时库已被裁到 10 条，拖回不恢复。数字标签必须跟本地态走，否则拖动中数字会冻住。
 *
 * 抽成独立可组合件是为了让行为**可测**：整屏包在 `verticalScroll` 里，而 Robolectric 环境下滚动容器会吞掉
 * 滑杆的横向拖动手势（已逐层实证：bare / SettingsSection 都能拖动，一加 verticalScroll 就收不到），
 * 直接测整屏只能得到「零次写入」的假绿。
 *
 * @param savedCount 已保存值（没在拖时显示它）。
 * @param onCommit 松手提交（手填也走这里，即时生效 = iOS）。
 */
@Composable
internal fun RetentionSliderRow(savedCount: Int, onCommit: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: savedCount
    // 尺 4（性能采集卷 0）：数一趟拖动里滑杆变了多少次 = 未改的调用点会做多少次 DataStore 全量重写。
    val writeRecorder = rememberSettingsWriteRecorder(
        PerfSettingsSites.SCREEN_CONTEXT_LOG,
        PerfSettingsSites.KEY_LOG_RETENTION,
    )
    SettingsSliderRow(
        label = "保留条数",
        valueLabel = "$shown 条",
        value = shown.toFloat(),
        valueRange = 10f..500f,
        steps = 48,
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

@Composable
private fun ActionButton(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val bg = if (destructive) AppTheme.colors.status.errorContainer else AppTheme.colors.surface.sunken
    val fg = if (destructive) AppTheme.colors.status.onError else AppTheme.colors.text.primary
    Box(
        Modifier
            .fillMaxWidth()
            .background(bg, AppTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AppTheme.typography.label, color = fg, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = title,
        body = body,
        confirmText = confirmLabel,
        onConfirm = onConfirm,
        confirmTone = if (destructive) AppDialogTone.Danger else AppDialogTone.Primary,
        dismissText = "取消",
        onDismiss = onDismiss,
    )
}
