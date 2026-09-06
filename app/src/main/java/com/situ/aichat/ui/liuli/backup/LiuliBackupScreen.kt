package com.situ.aichat.ui.liuli.backup

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.situ.aichat.work.AutoBackupFolder
import com.situ.aichat.data.backup.BackupByteSource
import com.situ.aichat.data.backup.ImportResult
import com.situ.aichat.ui.backup.BackupViewModel
import com.situ.aichat.ui.backup.exportFileName
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliSpinner
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.LiuliValueRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** 组内块间缝 / 转圈与字的缝（逐字照暖陶 16 / 8）。 */
private val BLOCK_GAP = 16.dp
private val INLINE_GAP = 8.dp

/**
 * 备份与恢复页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 29·A-4 ③ 进度条）。与暖陶 `BackupScreen` 共用
 * [BackupViewModel]，并沿用它的**整屏替换**：`preview != null` 时整屏换成
 * [LiuliBackupImportPreviewScreen] 并 `return`（F9·预览屏无路由·不进 `AIChatApp.kt`）。
 *
 * **数据安全路径零碰**：导出文件名格式（借暖陶 [exportFileName]·§2.2-2 已提 internal）· 流式写入
 * （不在内存里建整包）· `deleteTarget` 原子清理（CreateDocument 选名当刻就建了 0 字节文档，失败必须清掉）·
 * 两路导入都只把「怎么打开这个 Uri」交给 VM。
 */
@Composable
fun LiuliBackupScreen(
    onBack: () -> Unit,
    autoPickFolder: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = AppTheme.colors
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    val exportOk by viewModel.exportOk.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val strategies by viewModel.strategies.collectAsStateWithLifecycle()
    val autoBackupConfig by viewModel.autoBackupConfig.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val readFailed by viewModel.readFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 是否在导出里包含媒体（默认开 = 对齐 iOS includeMedia 默认 ON）。
    var includeMedia by remember { mutableStateOf(true) }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            // 流式写入 zip（不在内存里建整包）；失败 / 取消时删目标文档（原子导出）。
            viewModel.export(
                includeMedia = includeMedia,
                openStream = {
                    withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
                    }
                },
                deleteTarget = { DocumentsContract.deleteDocument(context.contentResolver, uri) },
            )
        }
    }
    // 两路导入共用：只把「怎么打开这个 Uri」交给 VM（zip / 旧 .json 由 startImport 内部两遍流式识别）。
    val importFromUri: (Uri) -> Unit = { uri ->
        viewModel.startImport(BackupByteSource.fromUri(context.contentResolver, uri))
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri)
    }

    // 自动备份目录里最近几份可直接导入的备份（仅 keyed on treeUri·逐字照暖陶 :99）。
    val recentBackups by produceState(
        initialValue = emptyList<AutoBackupFolder.BackupFileEntry>(),
        autoBackupConfig.treeUri,
    ) {
        val treeUri = autoBackupConfig.treeUri
        value = if (treeUri.isBlank()) {
            emptyList()
        } else {
            runCatching { AutoBackupFolder.listBackupFiles(context, Uri.parse(treeUri)) }.getOrDefault(emptyList())
        }
    }

    // 进入冲突预览段 → 整屏换成预览屏（launcher 已在上方声明，提前 return 不影响其 ActivityResult 注册）。
    preview?.let { pv ->
        LiuliBackupImportPreviewScreen(
            preview = pv,
            strategies = strategies,
            importResult = importResult,
            busy = busy,
            progress = progress,
            onSetStrategy = viewModel::setStrategy,
            onConfirm = viewModel::confirmImport,
            onDismiss = viewModel::dismissPreview,
            modifier = modifier,
        )
        return
    }

    val title = stringResource(R.string.backup_title)
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val ir = importResult
    val statusText: String? = when {
        readFailed -> stringResource(R.string.backup_read_failed)
        exportOk == true -> stringResource(R.string.backup_export_done)
        exportOk == false -> stringResource(R.string.backup_export_failed)
        // 卷 A：媒体失败数如实并句报出（同一资源 id·0 时不出现）。
        ir is ImportResult.Success -> stringResource(R.string.backup_import_done, ir.characters, ir.messages) +
            if (ir.mediaFailed > 0) "\n" + stringResource(R.string.backup_result_media_failed, ir.mediaFailed) else ""
        ir is ImportResult.Error -> ir.message
        else -> null
    }

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
                    LiuliGroup(footer = stringResource(R.string.backup_desc)) {
                        LiuliRowBase(divider = false, verticalPadding = LiuliPageGeometry.rowTwoLinePad) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(INLINE_GAP)) {
                                LiuliButton(
                                    onClick = {
                                        viewModel.clearEvents()
                                        createDoc.launch(exportFileName())
                                    },
                                    style = LiuliButtonStyle.Prominent,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.backup_export)) }
                                LiuliButton(
                                    onClick = {
                                        viewModel.clearEvents()
                                        openDoc.launch(arrayOf("*/*"))
                                    },
                                    style = LiuliButtonStyle.Glass,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.backup_import)) }
                            }
                        }
                        LiuliToggleRow(
                            title = stringResource(R.string.backup_export_include_media),
                            subtitle = stringResource(R.string.backup_export_include_media_desc),
                            checked = includeMedia,
                            enabled = !busy,
                            onCheckedChange = { includeMedia = it },
                        )
                    }

                    // 「最近备份」直选导入（P1-8）：点一份 → 走既有 预览 → 策略 → 导入 链路，零新解析。
                    if (recentBackups.isNotEmpty()) {
                        LiuliGroup(header = stringResource(R.string.backup_recent_title)) {
                            recentBackups.forEachIndexed { index, entry ->
                                val timeText = remember(entry.timestampMillis) {
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                        .format(Date(entry.timestampMillis))
                                }
                                LiuliValueRow(
                                    title = if (entry.includesMedia) {
                                        stringResource(R.string.backup_recent_entry_media, timeText)
                                    } else {
                                        timeText
                                    },
                                    value = "",
                                    // startImport 自带 clearEvents（busy 守卫内）。
                                    onClick = if (busy) null else ({ importFromUri(entry.uri) }),
                                    divider = index > 0,
                                )
                            }
                        }
                    }

                    if (busy || statusText != null) {
                        LiuliGroup {
                            if (busy) {
                                LiuliRowBase(
                                    divider = false,
                                    verticalPadding = LiuliPageGeometry.rowTwoLinePad,
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    // P1-7：有确定性进度时显进度条 + 阶段文案；间隙段退转圈兜底。
                                    val p = progress
                                    if (p != null) {
                                        LiuliBackupProgressRow(p, Modifier.fillMaxWidth())
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            LiuliSpinner()
                                            Text(
                                                stringResource(R.string.backup_busy),
                                                style = AppTypography.listPreview,
                                                color = colors.text.secondary,
                                                modifier = Modifier.padding(start = INLINE_GAP),
                                            )
                                        }
                                    }
                                }
                            }
                            statusText?.let {
                                LiuliRowBase(
                                    divider = busy,
                                    verticalPadding = LiuliPageGeometry.rowTwoLinePad,
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        it,
                                        style = AppTypography.listPreview,
                                        color = colors.accent.text,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }

                    // 定时自动备份（13.6c）；autoPickFolder = 失败通知深链进来 → 进页自动开目录选择器（P0-19）。
                    liuliAutoBackupGroup(
                        config = autoBackupConfig,
                        onSetEnabled = viewModel::setAutoBackupEnabled,
                        onSetFolder = viewModel::setAutoBackupFolder,
                        autoPickFolder = autoPickFolder,
                    )
                }
            }
        }
    }
}
