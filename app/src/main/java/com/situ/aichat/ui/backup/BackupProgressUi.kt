package com.situ.aichat.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.backup.BackupProgress
import com.situ.aichat.data.backup.overallFraction
import com.situ.aichat.ui.designsystem.AppProgressBar

/**
 * 备份导出/导入的确定性进度行（P1-7，BackupScreen 与 BackupImportPreviewScreen 共用）：
 * 进度条 + 阶段文案。整体 0..1 由 [overallFraction] 映射。
 */
@Composable
internal fun BackupProgressRow(progress: BackupProgress, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppProgressBar(progress = overallFraction(progress), modifier = Modifier.fillMaxWidth())
        Text(
            text = progressLabel(progress),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun progressLabel(p: BackupProgress): String = when (p.stage) {
    BackupProgress.Stage.COLLECT -> stringResource(R.string.backup_progress_collect, p.done, p.total)
    BackupProgress.Stage.WRITE_MEDIA -> stringResource(R.string.backup_progress_media, p.done, p.total)
    BackupProgress.Stage.COPY -> stringResource(R.string.backup_progress_copy)
    BackupProgress.Stage.RESTORE_MEDIA -> stringResource(R.string.backup_progress_restore_media, p.done, p.total)
    BackupProgress.Stage.WRITE_DB -> stringResource(R.string.backup_progress_restore_db, p.done, p.total)
}
