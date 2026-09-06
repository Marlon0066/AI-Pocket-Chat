package com.situ.aichat.ui.liuli.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.backup.BackupProgress
import com.situ.aichat.data.backup.overallFraction
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliProgressBar

/** 进度条与阶段文案的缝（逐字照暖陶 `BackupProgressRow` 的 4）。 */
private val ROW_GAP = 4.dp

/**
 * 备份导出 / 导入的确定性进度行（琉璃·A-4 ③ 进度条首用）。整体 0..1 由暖陶 [overallFraction] 映射
 * （纯函数·两张脸同一条算式），五个阶段的文案与占位参数逐字照暖陶 `progressLabel`。
 */
@Composable
internal fun LiuliBackupProgressRow(progress: BackupProgress, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        LiuliProgressBar(progress = overallFraction(progress), modifier = Modifier.fillMaxWidth())
        Text(
            liuliBackupProgressLabel(progress),
            style = AppTypography.secondary,
            color = AppTheme.colors.text.secondary,
        )
    }
}

/** 五阶段文案（逐字照暖陶 `progressLabel`·同五枚资源键与占位参数）。 */
@Composable
private fun liuliBackupProgressLabel(p: BackupProgress): String = when (p.stage) {
    BackupProgress.Stage.COLLECT -> stringResource(R.string.backup_progress_collect, p.done, p.total)
    BackupProgress.Stage.WRITE_MEDIA -> stringResource(R.string.backup_progress_media, p.done, p.total)
    BackupProgress.Stage.COPY -> stringResource(R.string.backup_progress_copy)
    BackupProgress.Stage.RESTORE_MEDIA -> stringResource(R.string.backup_progress_restore_media, p.done, p.total)
    BackupProgress.Stage.WRITE_DB -> stringResource(R.string.backup_progress_restore_db, p.done, p.total)
}
