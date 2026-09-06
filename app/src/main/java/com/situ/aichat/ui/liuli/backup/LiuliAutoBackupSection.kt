package com.situ.aichat.ui.liuli.backup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.situ.aichat.R
import com.situ.aichat.data.model.AutoBackupConfig
import com.situ.aichat.notification.NotificationPermission
import com.situ.aichat.ui.backup.folderLabel
import com.situ.aichat.ui.backup.formatTime
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.LiuliValueRow
import com.situ.aichat.work.BackgroundReliability

/** 卡内块间缝 / 警告图标尺寸（逐字照暖陶 8 / 18）。 */
private val CARD_GAP = 8.dp
private val WARN_ICON = 18.dp

/**
 * 定时自动备份段（琉璃·图纸 2026-09-06 卷五 §4.1 屏 29 的下半段·暖陶 `AutoBackupSection` 的对应件）。
 *
 * **数据安全路径逐字搬**：SAF 目录选好后立刻 `takePersistableUriPermission`（跨重启存活·后台 worker 要靠它写）；
 * 选目录与请求通知权限**串行**（同帧并发两个 launcher 会竞态）；`autoPickFolder` 深链进来时进页自动开一次
 * 目录选择器（P0-19）。`folderLabel` / `formatTime` 借暖陶（§2.2-2 已提 internal·实现零改）。
 */
@Composable
internal fun ColumnScope.liuliAutoBackupGroup(
    config: AutoBackupConfig,
    onSetEnabled: (Boolean) -> Unit,
    onSetFolder: (String) -> Unit,
    autoPickFolder: Boolean = false,
) {
    val context = LocalContext.current
    val colors = AppTheme.colors

    var batteryExempt by remember { mutableStateOf(BackgroundReliability.isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = BackgroundReliability.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // 持久化读写授权（跨重启存活，供后台 worker 写入）。
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            onSetFolder(uri.toString())
            // 选完目录后再请求通知权限——与选目录串行，避免同帧并发两个 launcher 竞态。
            if (!NotificationPermission.isGranted(context)) notifPermLauncher.launch(NotificationPermission.PERMISSION)
        }
    }

    // P0-19：自动备份失败 / 目录丢失通知深链进来 → 进页一次性自动打开目录选择器重选。
    LaunchedEffect(Unit) {
        if (autoPickFolder) pickFolder.launch(null)
    }

    LiuliGroup(
        header = stringResource(R.string.auto_backup_section_title),
        footer = stringResource(
            R.string.auto_backup_last,
            if (config.lastBackupAt == 0L) {
                stringResource(R.string.auto_backup_never)
            } else {
                formatTime(config.lastBackupAt)
            },
        ),
    ) {
        LiuliRowBase(divider = false, verticalPadding = LiuliPageGeometry.rowTwoLinePad, verticalAlignment = Alignment.Top) {
            Text(
                stringResource(R.string.auto_backup_section_desc),
                style = AppTypography.secondary,
                color = colors.text.secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LiuliToggleRow(
            title = stringResource(R.string.auto_backup_enable),
            checked = config.enabled,
            onCheckedChange = { on ->
                // 无目录 → 先开选择器（选完回调里再请求通知权限，串行不竞态）；已有目录 → 直接请求通知权限。
                if (on) {
                    if (config.treeUri.isBlank()) {
                        pickFolder.launch(null)
                    } else if (!NotificationPermission.isGranted(context)) {
                        notifPermLauncher.launch(NotificationPermission.PERMISSION)
                    }
                }
                onSetEnabled(on)
            },
        )
        LiuliValueRow(
            title = stringResource(R.string.auto_backup_folder),
            subtitle = if (config.treeUri.isBlank()) {
                stringResource(R.string.auto_backup_folder_none)
            } else {
                folderLabel(config.treeUri)
            },
            value = stringResource(
                if (config.treeUri.isBlank()) R.string.auto_backup_pick_folder else R.string.auto_backup_change_folder,
            ),
            // 右值是动作不是状态（暖陶为文字钮）——按 accent.text 上色，别让它冒充只读值。
            valueColor = colors.accent.text,
            onClick = { pickFolder.launch(null) },
        )
        if (config.enabled && config.treeUri.isBlank()) {
            LiuliRowBase(verticalPadding = LiuliPageGeometry.rowTwoLinePad, verticalAlignment = Alignment.Top) {
                Text(
                    stringResource(R.string.auto_backup_need_folder),
                    style = AppTypography.secondary,
                    color = colors.status.onError,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (config.enabled && !batteryExempt) {
            LiuliRowBase(verticalPadding = LiuliPageGeometry.groupPadH, verticalAlignment = Alignment.Top) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            // F7：原文件私有的 WarningAmber → 语义色 status.onWarning。
                            tint = colors.status.onWarning,
                            modifier = Modifier.size(WARN_ICON),
                        )
                        Spacer(Modifier.width(CARD_GAP))
                        Text(
                            stringResource(R.string.auto_backup_reliability_title),
                            style = AppTypography.bodyEmphasis,
                            color = colors.text.primary,
                        )
                    }
                    Text(
                        stringResource(R.string.auto_backup_reliability_desc),
                        style = AppTypography.secondary,
                        color = colors.text.secondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                        LiuliButton(
                            onClick = { BackgroundReliability.requestIgnoreBatteryOptimizations(context) },
                            style = LiuliButtonStyle.Prominent,
                        ) { Text(stringResource(R.string.auto_backup_allow_background)) }
                        if (BackgroundReliability.isXiaomi) {
                            LiuliButton(
                                onClick = { BackgroundReliability.openAutoStartSettings(context) },
                                style = LiuliButtonStyle.Glass,
                            ) { Text(stringResource(R.string.auto_backup_autostart)) }
                        }
                    }
                }
            }
        }
    }
}
