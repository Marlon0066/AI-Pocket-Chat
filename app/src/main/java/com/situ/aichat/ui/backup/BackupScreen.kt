package com.situ.aichat.ui.backup

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.data.backup.BackupByteSource
import com.situ.aichat.data.backup.ImportResult
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.work.AutoBackupFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    autoPickFolder: Boolean = false,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    val exportOk by viewModel.exportOk.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val strategies by viewModel.strategies.collectAsStateWithLifecycle()
    val autoBackupConfig by viewModel.autoBackupConfig.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val readFailed by viewModel.readFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 是否在导出里包含聊天图片/语音等媒体（默认开=对齐 iOS includeMedia 默认 ON；关可大幅减小文件体积）。
    var includeMedia by remember { mutableStateOf(true) }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            // 提供 SAF 目标的 OutputStream，由 VM/Service 流式写入 zip（不在内存里建整包，13.6c）。
            // includeMedia 在 SAF 返回时取值（与 iOS 导出启动时捕获 let media 同语义）。
            // P1-7 原子导出：失败/取消时删目标文档（CreateDocument 选名当刻即建 0 字节文档，必须能清掉）。
            viewModel.export(
                includeMedia = includeMedia,
                openStream = {
                    withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull() }
                },
                deleteTarget = { DocumentsContract.deleteDocument(context.contentResolver, uri) },
            )
        }
    }
    // 两路导入共用（P1-8）：手选文件 + 「最近备份」行。卷 A：只把「怎么打开这个 Uri」交给 VM——**不再整包读进
    // 内存**（zip / 旧 .json 都由 startImport 内部两遍流式识别与处理）。
    val importFromUri: (Uri) -> Unit = { uri ->
        viewModel.startImport(BackupByteSource.fromUri(context.contentResolver, uri))
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri)
    }

    // 自动备份目录里最近几份可直接导入的备份（P1-8，安卓超越——iOS 备份纯手动无此入口）。
    // 仅 keyed on treeUri：屏幕停留期间 worker 恰好写新份不即时上屏（重进屏即新），不为此加 ON_RESUME 复杂度。
    val recentBackups by produceState(initialValue = emptyList<AutoBackupFolder.BackupFileEntry>(), autoBackupConfig.treeUri) {
        val treeUri = autoBackupConfig.treeUri
        value = if (treeUri.isBlank()) {
            emptyList()
        } else {
            runCatching { AutoBackupFolder.listBackupFiles(context, Uri.parse(treeUri)) }.getOrDefault(emptyList())
        }
    }

    // 进入冲突预览段 → 整屏换成预览屏（自带 TopAppBar；返回/系统返回 = 取消预览）。launcher 已在上方声明，
    // 提前 return 不影响其 ActivityResult 注册。
    preview?.let { pv ->
        BackupImportPreviewScreen(
            preview = pv,
            strategies = strategies,
            importResult = importResult,
            busy = busy,
            progress = progress,
            onSetStrategy = viewModel::setStrategy,
            onConfirm = viewModel::confirmImport,
            onDismiss = viewModel::dismissPreview,
        )
        return
    }

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.backup_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.backup_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppButton(
                onClick = {
                    viewModel.clearEvents()
                    createDoc.launch(exportFileName())
                },
                style = AppButtonStyle.Primary,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_export)) }
            AppButton(
                onClick = {
                    viewModel.clearEvents()
                    openDoc.launch(arrayOf("*/*"))
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                style = AppButtonStyle.Tonal,
            ) { Text(stringResource(R.string.backup_import)) }

            // 「最近备份」直选导入（P1-8）：自动备份目录的最近几份，点一份 → 走既有 预览→策略→导入 链路，零新解析。
            if (recentBackups.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(
                        stringResource(R.string.backup_recent_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    recentBackups.forEach { entry ->
                        val timeText = remember(entry.timestampMillis) {
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(entry.timestampMillis))
                        }
                        AppButton(
                            // startImport 自带 clearEvents（busy 守卫内）；这里直接触发即可。
                            onClick = { importFromUri(entry.uri) },
                            style = AppButtonStyle.Text,
                            enabled = !busy,
                        ) {
                            Text(
                                if (entry.includesMedia) {
                                    stringResource(R.string.backup_recent_entry_media, timeText)
                                } else {
                                    timeText
                                },
                            )
                        }
                    }
                }
            }

            // 导出「包含媒体」开关 → 无标题卡壳单行卡（§4.A11·操作页 16 gutter 由外 Column 提供·
            // 与全宽按钮同宽故不叠 horizontal 20·卡内 16·§11 D-A11）。
            Column(Modifier.fillMaxWidth().appCardSurface().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.backup_export_include_media), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.backup_export_include_media_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppSwitch(checked = includeMedia, onCheckedChange = { includeMedia = it }, enabled = !busy)
                }
            }

            if (busy) {
                // P1-7：有确定性进度时显进度条+阶段文案（安卓超越——iOS 全程不确定转圈）；间隙段退转圈兜底。
                val p = progress
                if (p != null) {
                    BackupProgressRow(p)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppLoadingRing(size = AppLoadingRingSize.Small)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.backup_busy))
                    }
                }
            }

            val ir = importResult
            val statusText: String? = when {
                readFailed -> stringResource(R.string.backup_read_failed)
                exportOk == true -> stringResource(R.string.backup_export_done)
                exportOk == false -> stringResource(R.string.backup_export_failed)
                // 卷 A：媒体失败数如实并句报出（同一资源 id·0 时不出现），别让「导入完成」盖住悄悄少掉的图/语音。
                ir is ImportResult.Success -> stringResource(R.string.backup_import_done, ir.characters, ir.messages) +
                    if (ir.mediaFailed > 0) "\n" + stringResource(R.string.backup_result_media_failed, ir.mediaFailed) else ""
                ir is ImportResult.Error -> ir.message
                else -> null
            }
            statusText?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            // 定时自动备份（13.6c；安卓超越 iOS）。autoPickFolder=失败通知深链进来 → 进页自动开目录选择器（P0-19）。
            AutoBackupSection(
                config = autoBackupConfig,
                onSetEnabled = viewModel::setAutoBackupEnabled,
                onSetFolder = viewModel::setAutoBackupFolder,
                autoPickFolder = autoPickFolder,
            )
        }
    }
}

// .zip 容器（manifest.json + media/）：用户可在电脑上直接解压检视/挑拣（超越 iOS 的 Base64 内嵌单文件，13.6）。
private fun exportFileName(): String =
    "AIChat_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.zip"
