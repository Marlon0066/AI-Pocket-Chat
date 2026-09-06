package com.situ.aichat.ui.backup

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.produceState
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.data.backup.BackupPreview
import com.situ.aichat.data.backup.BackupProgress
import com.situ.aichat.data.backup.CharacterPreviewRow
import com.situ.aichat.data.backup.ImportResult
import com.situ.aichat.data.backup.ImportStrategy
import com.situ.aichat.ui.components.AvatarColor
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.grainSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// iOS 用硬编码 .yellow/.green 区分「冲突警告 / 将新导入」——这里同义取暖黄 + 系统绿（非主题色，刻意醒目）。
private val ConflictAmber = Color(0xFFF5A623)
private val NewGreen = Color(0xFF34C759)

/**
 * 导入冲突预览屏（1:1 iOS `CharacterBackupImportPreviewView`）：逐角色显示头像/名/消息数 + 冲突黄标&三策略 Picker
 * 或无冲突绿标「将导入为新角色」；底部确认导入 → 结果区分策略计数。冲突默认「创建副本」。**总是显示**（含 0 冲突，
 * 用户拍板）：给一次「将恢复 N 角色」的确认与防误覆盖机会。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupImportPreviewScreen(
    preview: BackupPreview,
    strategies: Map<String, ImportStrategy>,
    importResult: ImportResult?,
    busy: Boolean,
    progress: BackupProgress? = null,
    onSetStrategy: (String, ImportStrategy) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // E1#2：导入进行中（busy）反向**启用** BackHandler 吞掉系统返回 + Toast 提示，防一记手势返回弹出 "backup"
    // 路由销毁 VM、取消 viewModelScope → 静默中断导入（落在媒体重存段还会留媒体孤儿）。非 busy 时正常 onDismiss。
    val context = LocalContext.current
    val importingHint = stringResource(R.string.backup_import_in_progress)
    BackHandler(enabled = true) {
        if (busy) {
            Toast.makeText(context, importingHint, Toast.LENGTH_SHORT).show()
        } else {
            onDismiss()
        }
    }

    val success = importResult as? ImportResult.Success
    val error = importResult as? ImportResult.Error
    val done = success != null

    val conflicts = preview.characters.filter { it.hasConflict }
    val hasNonConflict = preview.characters.any { !it.hasConflict }
    // 全跳过 = 所有冲突角色都选跳过 且 没有任何无冲突角色（=没东西可导）——1:1 iOS allSkipped。
    val allSkipped = !hasNonConflict && conflicts.isNotEmpty() &&
        conflicts.all { (strategies[it.uuid] ?: ImportStrategy.DUPLICATE) == ImportStrategy.SKIP }
    val canConfirm = !busy && !done && preview.characters.isNotEmpty() && !allSkipped

    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.backup_preview_title),
                onBack = onDismiss,
                // 导入进行中禁止退出：钮灰掉但仍在原位（图纸 §4.6）。
                backEnabled = !busy,
                lifted = listState.canScrollBackward,
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (done) {
                        ImportResultSummary(success)
                        AppButton(onClick = onDismiss, style = AppButtonStyle.Primary, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.backup_preview_done))
                        }
                    } else {
                        if (error != null) {
                            // P1-19：错误动态出现 → Polite 自动播报（同一错误连发文本不变不重播=RedeemCode 既有接受行为）。
                            Text(
                                error.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        // P1-7：导入确定性进度（媒体重存→逐角色写库）；无进度间隙仍有按钮内转圈兜底。
                        progress?.let { BackupProgressRow(it) }
                        AppButton(onClick = onConfirm, style = AppButtonStyle.Primary, enabled = canConfirm, modifier = Modifier.fillMaxWidth()) {
                            if (busy) {
                                AppLoadingRing(size = AppLoadingRingSize.Small)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.backup_preview_importing))
                            } else {
                                Text(stringResource(R.string.backup_preview_confirm))
                            }
                        }
                        if (allSkipped) {
                            // P1-19：动态出现的「全跳过」解释了确认钮为何禁用 → Polite 播报。
                            Text(
                                stringResource(R.string.backup_preview_all_skipped),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .contentMaxWidth(),
            // 屏 gutter 恒 20（设计语言 §2.5 军规）
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AppSpacing.screenGutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (preview.hasGlobalData) {
                item {
                    Text(
                        stringResource(R.string.backup_preview_global_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(preview.characters, key = { it.uuid }) { row ->
                CharacterPreviewRowView(
                    row = row,
                    strategy = strategies[row.uuid] ?: ImportStrategy.DUPLICATE,
                    enabled = !busy && !done,
                    onSetStrategy = onSetStrategy,
                )
            }
        }
    }
}

@Composable
private fun CharacterPreviewRowView(
    row: CharacterPreviewRow,
    strategy: ImportStrategy,
    enabled: Boolean,
    onSetStrategy: (String, ImportStrategy) -> Unit,
) {
    // P1-19 a11y（iOS 此屏零 a11y 修饰 = 安卓超越）：头行/冲突行/新建行各自合并为一停；图标保 cd=null
    // （状态进文本 = iOS Label 语义，CharacterBackupImportPreviewView.swift:106-131）；Card 级绝不合并
    // （会吞掉三个分段钮的独立焦点）；SegmentedButtonRow 零改动（M3 1.3 自带 selectableGroup+RadioButton 角色）。
    Box(Modifier.appCardSurface(raised = true, cornerRadius = 16.dp).grainSurface()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreviewAvatar(name = row.name, bytes = row.avatarBytes, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.backup_preview_msg_count, row.messageCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (row.hasConflict) {
                // 「处理方式：X」挂在合并后的冲突行上（=iOS Picker label「处理方式」的组上下文，:115）；
                // 选中切换的播报由分段钮自身发出，这里只随重组更新、未聚焦不重播 → 无双报。
                val strategyState = stringResource(
                    R.string.a11y_backup_strategy_state,
                    stringResource(strategyLabelRes(strategy)),
                )
                Row(
                    Modifier.semantics(mergeDescendants = true) { stateDescription = strategyState },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = ConflictAmber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.backup_preview_conflict, row.existingName ?: row.name),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                AppSegmentedControl(
                    options = listOf(ImportStrategy.DUPLICATE, ImportStrategy.OVERWRITE, ImportStrategy.SKIP),
                    selected = strategy,
                    onSelect = { onSetStrategy(row.uuid, it) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    label = { stringResource(strategyLabelRes(it)) },
                )
            } else {
                Row(
                    Modifier.semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = NewGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.backup_preview_new), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * 策略 → 标签资源（P1-19 抽出供分段钮与 stateDescription 共用，免双份映射；可测）。
 * 序与文案 1:1 iOS Picker tag（CharacterBackupImportPreviewView.swift:116-118：创建副本/覆盖已有/跳过）。
 */
internal fun strategyLabelRes(strategy: ImportStrategy): Int = when (strategy) {
    ImportStrategy.DUPLICATE -> R.string.backup_strategy_duplicate
    ImportStrategy.OVERWRITE -> R.string.backup_strategy_overwrite
    ImportStrategy.SKIP -> R.string.backup_strategy_skip
}

/**
 * 结果区（导入完成）：分策略计数（只显示 >0 的），1:1 iOS resultSection。
 * P1-19：整块合并 + Polite——bottomBar 切到 done 分支时自动播一句「导入完成，新导入 N…」（RedeemCodeScreen 14.7e
 * 同款模式）；「完成」按钮在合并外保持独立焦点。liveRegion 绝不挂 bottomBar 容器/确认按钮（忙态文本切换会误播）。
 */
@Composable
private fun ImportResultSummary(result: ImportResult.Success) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.backup_preview_result_title), style = MaterialTheme.typography.labelLarge)
        if (result.imported > 0) Text(stringResource(R.string.backup_result_imported, result.imported), style = MaterialTheme.typography.bodyMedium)
        if (result.duplicated > 0) Text(stringResource(R.string.backup_result_duplicated, result.duplicated), style = MaterialTheme.typography.bodyMedium)
        if (result.overwritten > 0) Text(stringResource(R.string.backup_result_overwritten, result.overwritten), style = MaterialTheme.typography.bodyMedium)
        if (result.skipped > 0) Text(stringResource(R.string.backup_result_skipped, result.skipped), style = MaterialTheme.typography.bodyMedium)
        // 卷 A：媒体逐条恢复，坏了几条就说几条——「导入完成」却悄悄少图少语音属于隐瞒数据丢失。0 时不出现。
        if (result.mediaFailed > 0) {
            Text(
                stringResource(R.string.backup_result_media_failed, result.mediaFailed),
                style = AppTheme.typography.secondary, // 图纸 §4 锁定（复核 R1 代修：勿换回 M3 token）
                color = AppTheme.colors.status.onWarning,
            )
        }
    }
}

/** 预览头像：直接解码 zip 内字节（预览段未重存媒体）；无字节 → 名字首字母单字 + 渐变（复用 [AvatarColor]）。 */
@Composable
private fun PreviewAvatar(name: String, bytes: ByteArray?, size: Dp) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, bytes) {
        value = bytes?.let {
            withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(AvatarColor.brush(name))
            // P1-19：整体装饰压停（名字由行文本承载）——cd=null 盖不住首字母兜底 Text 的泄漏停，须容器级清空；
            // 本 Box 不可点 → 无 clickable 链序坑（惯例参照 ContactsScreen:271）。
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            bytes == null -> Text(
                text = name.take(1).uppercase().ifEmpty { "·" },
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
            // else: 有字节但还在解码 → 只显示渐变背景（不闪首字母）。
        }
    }
}
