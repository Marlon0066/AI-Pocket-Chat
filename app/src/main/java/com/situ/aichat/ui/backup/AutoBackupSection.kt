package com.situ.aichat.ui.backup

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.data.model.AutoBackupConfig
import com.situ.aichat.notification.NotificationPermission
import com.situ.aichat.work.BackgroundReliability
import java.text.DateFormat
import java.util.Date

private val WarningAmber = Color(0xFFF5A623)

/**
 * 「定时自动备份」设置区（13.6c；安卓超越 iOS）。开关 + SAF 持久目录选择 + 上次备份时间 + HyperOS 后台可靠性卡。
 * 启用时若无 POST_NOTIFICATIONS 权限则请求（完成通知用）、若无目录则顺手打开选择器。可靠性卡仅在「已启用且未给
 * 电池白名单」时出现，复用 [BackgroundReliability]（回前台刷新电池状态）。
 */
@Composable
fun AutoBackupSection(
    config: AutoBackupConfig,
    onSetEnabled: (Boolean) -> Unit,
    onSetFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoPickFolder: Boolean = false,
) {
    val context = LocalContext.current

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
            // 选完目录后再请求通知权限（完成通知用）——与选目录串行，避免同帧并发两个 launcher 竞态。
            if (!NotificationPermission.isGranted(context)) notifPermLauncher.launch(NotificationPermission.PERMISSION)
        }
    }

    // P15·P0-19：自动备份失败/目录丢失通知深链进来 → 进页一次性自动打开目录选择器重选（每次进本屏只触发一次）。
    LaunchedEffect(Unit) {
        if (autoPickFolder) pickFolder.launch(null)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppListDivider(startInset = 0.dp)
        Text(stringResource(R.string.auto_backup_section_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.auto_backup_section_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.auto_backup_enable), modifier = Modifier.weight(1f))
            AppSwitch(
                checked = config.enabled,
                onCheckedChange = { on ->
                    if (on) {
                        // 无目录 → 先开选择器（选完回调里再请求通知权限，串行不竞态）；已有目录 → 直接请求通知权限。
                        if (config.treeUri.isBlank()) {
                            pickFolder.launch(null)
                        } else if (!NotificationPermission.isGranted(context)) {
                            notifPermLauncher.launch(NotificationPermission.PERMISSION)
                        }
                    }
                    onSetEnabled(on)
                },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.auto_backup_folder), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (config.treeUri.isBlank()) stringResource(R.string.auto_backup_folder_none) else folderLabel(config.treeUri),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppButton(onClick = { pickFolder.launch(null) }, style = AppButtonStyle.Text) {
                Text(stringResource(if (config.treeUri.isBlank()) R.string.auto_backup_pick_folder else R.string.auto_backup_change_folder))
            }
        }

        if (config.enabled && config.treeUri.isBlank()) {
            Text(
                stringResource(R.string.auto_backup_need_folder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(
            stringResource(
                R.string.auto_backup_last,
                if (config.lastBackupAt == 0L) stringResource(R.string.auto_backup_never) else formatTime(config.lastBackupAt),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (config.enabled && !batteryExempt) {
            // M3 描边卡 → appCardSurface（内层 Column 参数上提·内 padding 12→16·§4.A11）；内部行零改。
            Column(
                Modifier.fillMaxWidth().appCardSurface().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.auto_backup_reliability_title), style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    stringResource(R.string.auto_backup_reliability_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(onClick = { BackgroundReliability.requestIgnoreBatteryOptimizations(context) }, style = AppButtonStyle.Primary) {
                        Text(stringResource(R.string.auto_backup_allow_background))
                    }
                    if (BackgroundReliability.isXiaomi) {
                        AppButton(onClick = { BackgroundReliability.openAutoStartSettings(context) }, style = AppButtonStyle.Tonal) {
                            Text(stringResource(R.string.auto_backup_autostart))
                        }
                    }
                }
            }
        }
    }
}

/** SAF tree URI → 友好路径（`primary:Download/AIChat` → `Download/AIChat`）；解析失败回退原串；琉璃卷五复用（`ui/liuli` 树借同一份实现·改这里两张脸同时变）。 */
internal fun folderLabel(uriString: String): String = runCatching {
    val docId = DocumentsContract.getTreeDocumentId(Uri.parse(uriString))
    docId.substringAfter(':').ifBlank { docId }
}.getOrDefault(uriString)

/** 琉璃卷五复用（`ui/liuli` 树借同一份实现·改这里两张脸同时变）。 */
internal fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
