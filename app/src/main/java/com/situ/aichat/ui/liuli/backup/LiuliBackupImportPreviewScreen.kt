package com.situ.aichat.ui.liuli.backup

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.backup.BackupPreview
import com.situ.aichat.data.backup.BackupProgress
import com.situ.aichat.data.backup.CharacterPreviewRow
import com.situ.aichat.data.backup.ImportResult
import com.situ.aichat.data.backup.ImportStrategy
import com.situ.aichat.ui.backup.strategyLabelRes
import com.situ.aichat.ui.components.AvatarColor
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliSpinner
import com.situ.aichat.ui.liuli.designsystem.liuliCardSurface
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliSaveBar
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmented
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember

/** 卡内距 / 块间缝 / 状态图标 / 头像尺寸（逐字照暖陶 12 / 8 / 16 / 44）。 */
private val CARD_PAD = 12.dp
private val CARD_GAP = 8.dp
private val STATUS_ICON = 16.dp
private val STATUS_GAP = 6.dp
private val AVATAR = 44.dp
/** 卡与卡的缝 / 结果区行距（逐字照暖陶 12 / 2）。 */
private val LIST_GAP = 12.dp
private val RESULT_GAP = 2.dp
/** 首字母兜底字号 = 头像直径 × 0.42（逐字照暖陶）。 */
private const val MONOGRAM_RATIO = 0.42f
/** 三种导入策略的固定顺序（序与文案 1:1 iOS Picker tag·逐字照暖陶）。 */
private val STRATEGIES = listOf(ImportStrategy.DUPLICATE, ImportStrategy.OVERWRITE, ImportStrategy.SKIP)

/**
 * 备份导入预览屏（琉璃·图纸 2026-09-06 卷五 §4.1 屏 30·**无 VM、无路由**——由 [LiuliBackupScreen] 内联替换）。
 * 收的还是那八个参数（A-7）。
 *
 * **a11y 契约密集，逐条保留**：`BackHandler(enabled = true)` 在 busy 时吞返回 + Toast（一记手势返回会销毁 VM、
 * 取消 `viewModelScope` → 静默中断导入，落在媒体重存段还会留媒体孤儿）· 三处 `liveRegion = Polite`
 * （错误 / 全跳过 / 结果区）· 头像 `clearAndSetSemantics` 压停 · **卡级绝不合并**（会吞掉三个分段钮的独立焦点）。
 * `strategyLabelRes` 借暖陶（§2.2-2 已提 internal）。
 */
@Composable
internal fun LiuliBackupImportPreviewScreen(
    preview: BackupPreview,
    strategies: Map<String, ImportStrategy>,
    importResult: ImportResult?,
    busy: Boolean,
    progress: BackupProgress? = null,
    onSetStrategy: (String, ImportStrategy) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    val importingHint = stringResource(R.string.backup_import_in_progress)
    // E1#2：导入进行中反向**启用** BackHandler 吞掉系统返回 + Toast 提示。
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
    // 全跳过 = 所有冲突角色都选跳过 且 没有任何无冲突角色（= 没东西可导·1:1 iOS allSkipped）。
    val allSkipped = !hasNonConflict && conflicts.isNotEmpty() &&
        conflicts.all { (strategies[it.uuid] ?: ImportStrategy.DUPLICATE) == ImportStrategy.SKIP }
    val canConfirm = !busy && !done && preview.characters.isNotEmpty() && !allSkipped

    val title = stringResource(R.string.backup_preview_title)
    // 底栏会随内容长高（结果摘要 / 错误 / 进度·复核 R1 🔴 A-2）：列表底内距按栏实高留（实高已含导航栏）。
    var barHeight by remember { mutableStateOf(LiuliPageGeometry.saveBar) }
    val bottomInset = LiuliPageGeometry.pageBottom + barHeight + LiuliPageGeometry.saveBarGap

    LiuliPage(
        title = title,
        // 导入进行中禁止退出：返回钮淡出且不吃点击（= 暖陶 `backEnabled = !busy`）；系统返回由 BackHandler 吞并 toast。
        onBack = onDismiss,
        backEnabled = !busy,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
        bottomBar = {
            LiuliSaveBar(onHeightChanged = { barHeight = it }) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                    if (done) {
                        LiuliImportResultSummary(success)
                        LiuliButton(
                            onClick = onDismiss,
                            style = LiuliButtonStyle.Prominent,
                            modifier = Modifier.fillMaxWidth().height(LiuliPageGeometry.saveBarButton),
                        ) { Text(stringResource(R.string.backup_preview_done)) }
                    } else {
                        if (error != null) {
                            // P1-19：错误动态出现 → Polite 自动播报。
                            Text(
                                error.message,
                                style = AppTypography.listPreview,
                                color = colors.status.onError,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        progress?.let { LiuliBackupProgressRow(it) }
                        LiuliButton(
                            onClick = onConfirm,
                            style = LiuliButtonStyle.Prominent,
                            enabled = canConfirm,
                            modifier = Modifier.fillMaxWidth().height(LiuliPageGeometry.saveBarButton),
                        ) {
                            if (busy) {
                                LiuliSpinner(color = colors.accent.onPrimary)
                                Text(stringResource(R.string.backup_preview_importing))
                            } else {
                                Text(stringResource(R.string.backup_preview_confirm))
                            }
                        }
                        if (allSkipped) {
                            // P1-19：动态出现的「全跳过」解释了确认钮为何禁用 → Polite 播报。
                            Text(
                                stringResource(R.string.backup_preview_all_skipped),
                                style = AppTypography.secondary,
                                color = colors.text.secondary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }
            }
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            contentPadding = PaddingValues(
                top = LiuliPageGeometry.navRow,
                bottom = bottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(LIST_GAP),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            if (preview.hasGlobalData) {
                item(key = "global-note") {
                    Text(
                        stringResource(R.string.backup_preview_global_note),
                        style = AppTypography.secondary,
                        color = colors.text.secondary,
                        modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter),
                    )
                }
            }
            items(preview.characters, key = { it.uuid }) { row ->
                LiuliCharacterPreviewRow(
                    row = row,
                    strategy = strategies[row.uuid] ?: ImportStrategy.DUPLICATE,
                    enabled = !busy && !done,
                    onSetStrategy = onSetStrategy,
                    modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter),
                )
            }
        }
    }
}

/** 一张角色预览卡（**卡级绝不合并**：三个分段钮要各自可聚焦）。 */
@Composable
private fun LiuliCharacterPreviewRow(
    row: CharacterPreviewRow,
    strategy: ImportStrategy,
    enabled: Boolean,
    onSetStrategy: (String, ImportStrategy) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(
        modifier.fillMaxWidth().liuliCardSurface().padding(CARD_PAD),
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            LiuliPreviewAvatar(name = row.name, bytes = row.avatarBytes, size = AVATAR)
            Spacer(Modifier.width(LiuliPageGeometry.tileGap))
            Column(Modifier.weight(1f)) {
                Text(row.name, style = AppTypography.bodyEmphasis, color = colors.text.primary)
                Text(
                    stringResource(R.string.backup_preview_msg_count, row.messageCount),
                    style = AppTypography.secondary,
                    color = colors.text.secondary,
                )
            }
        }
        if (row.hasConflict) {
            // 「处理方式：X」挂在合并后的冲突行上（= iOS Picker label「处理方式」的组上下文）。
            val strategyState = stringResource(
                R.string.a11y_backup_strategy_state,
                stringResource(strategyLabelRes(strategy)),
            )
            Row(
                Modifier.semantics(mergeDescendants = true) { stateDescription = strategyState },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    // F7：原文件私有的 ConflictAmber → 语义色 status.onWarning。
                    tint = colors.status.onWarning,
                    modifier = Modifier.size(STATUS_ICON),
                )
                Spacer(Modifier.width(STATUS_GAP))
                Text(
                    stringResource(R.string.backup_preview_conflict, row.existingName ?: row.name),
                    style = AppTypography.secondary,
                    color = colors.text.primary,
                )
            }
            // 直接放分段控件：分段**行**自带 16/12 内距，套进已有 12 内距的卡里会比上面的行多缩 16（复核 R1 C-2）。
            LiuliSegmented(
                options = STRATEGIES,
                selected = strategy,
                label = { stringResource(strategyLabelRes(it)) },
                onSelect = { onSetStrategy(row.uuid, it) },
                enabled = enabled,
                modifier = Modifier.padding(top = CARD_GAP),
            )
        } else {
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    // F7：原文件私有的 NewGreen → 语义色 status.onSuccess。
                    tint = colors.status.onSuccess,
                    modifier = Modifier.size(STATUS_ICON),
                )
                Spacer(Modifier.width(STATUS_GAP))
                Text(
                    stringResource(R.string.backup_preview_new),
                    style = AppTypography.secondary,
                    color = colors.text.primary,
                )
            }
        }
    }
}

/**
 * 结果区（导入完成）：分策略计数（只显示 > 0 的）。整块合并 + Polite——切到 done 分支时自动播一句；
 * 「完成」按钮在合并外保持独立焦点。**liveRegion 绝不挂 bottomBar 容器 / 确认按钮**（忙态文本切换会误播）。
 */
@Composable
private fun LiuliImportResultSummary(result: ImportResult.Success) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(RESULT_GAP),
    ) {
        Text(stringResource(R.string.backup_preview_result_title), style = AppTypography.label, color = colors.text.primary)
        if (result.imported > 0) {
            Text(stringResource(R.string.backup_result_imported, result.imported), style = AppTypography.listPreview, color = colors.text.secondary)
        }
        if (result.duplicated > 0) {
            Text(stringResource(R.string.backup_result_duplicated, result.duplicated), style = AppTypography.listPreview, color = colors.text.secondary)
        }
        if (result.overwritten > 0) {
            Text(stringResource(R.string.backup_result_overwritten, result.overwritten), style = AppTypography.listPreview, color = colors.text.secondary)
        }
        if (result.skipped > 0) {
            Text(stringResource(R.string.backup_result_skipped, result.skipped), style = AppTypography.listPreview, color = colors.text.secondary)
        }
        // 卷 A：媒体逐条恢复，坏了几条就说几条——0 时不出现。
        if (result.mediaFailed > 0) {
            Text(
                stringResource(R.string.backup_result_media_failed, result.mediaFailed),
                style = AppTypography.secondary,
                color = colors.status.onWarning,
            )
        }
    }
}

/** 预览头像：直接解码 zip 内字节（预览段未重存媒体）；无字节 → 名字首字母 + 渐变（复用 [AvatarColor]）。 */
@Composable
private fun LiuliPreviewAvatar(name: String, bytes: ByteArray?, size: Dp) {
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
            // 整体装饰压停（名字由行文本承载）——cd = null 盖不住首字母兜底 Text 的泄漏停，须容器级清空。
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
                fontSize = (size.value * MONOGRAM_RATIO).sp,
            )
            // else：有字节但还在解码 → 只显示渐变背景（不闪首字母）。
        }
    }
}
