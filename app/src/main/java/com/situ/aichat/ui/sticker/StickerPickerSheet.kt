package com.situ.aichat.ui.sticker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.sticker.DisabledBuiltInStickerStore
import com.situ.aichat.sticker.StickerInfo
import com.situ.aichat.sticker.StickerRecentStore
import com.situ.aichat.sticker.StickerService
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.sticker.toStickerInfo
import com.situ.aichat.ui.chat.StickerImage
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 表情包选择器（1:1 iOS `StickerPickerView`）：底部弹窗 + 三段（最近使用/全部表情/我的表情）+ 4 列网格
 * （60dp 图 + 名）+ 长按预览（180dp）+ 底部「管理表情包」。recent/disabled 在打开时读 SharedPreferences
 * （K7：produceState + IO 线程，每次弹出重读拿最新；就绪前不渲染内容区——毫秒级、入场动画掩蔽），
 * custom 由聊天页传入。选中即回调发送并关闭。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StickerPickerSheet(
    customStickers: List<CustomStickerEntity>,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 打开时读隐藏集合 + 最近使用——K7（2026-07-12）：原 `remember { 读盘 }` 在组合期的主线程同步读，
    // 该 prefs 首读会掉帧；改 produceState + IO。null=尚未就绪 → 只渲染骨架不渲染内容区（绝不闪错误内容）。
    val prefsSnapshot by produceState<StickerPickerPrefs?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            StickerPickerPrefs(
                disabled = DisabledBuiltInStickerStore.disabledIds(context),
                recentIds = StickerRecentStore.recentStickerIds(context),
            )
        }
    }
    val customAsc = remember(customStickers) { customStickers.sortedBy { it.createdAt } }

    var tab by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<StickerInfo?>(null) }

    val stickers = remember(tab, customAsc, prefsSnapshot) {
        val prefs = prefsSnapshot ?: return@remember emptyList()
        when (tab) {
            0 -> {
                val byId = StickerService.allStickers(customAsc, prefs.disabled).associateBy { it.id }
                prefs.recentIds.mapNotNull { byId[it] }
            }
            1 -> StickerService.allStickers(customAsc, prefs.disabled)
            else -> customAsc.map { it.toStickerInfo() }
        }
    }

    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            val stickerTabs = listOf("最近使用", "全部表情", "我的表情")
            AppSegmentedControl(
                options = stickerTabs.indices.toList(),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.screenGutter),
                label = { stickerTabs[it] },
            )

            if (prefsSnapshot == null) {
                // prefs 就绪前的骨架占位（高度对齐网格区，防内容到达时面板高度跳变）。
                Spacer(Modifier.height(320.dp))
            } else if (stickers.isEmpty()) {
                // sticker-2：空态 = 图标 + 文案 +（「我的表情」tab）添加按钮，1:1 iOS StickerPickerView.emptyStateView。
                Column(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        if (tab == 0) Icons.Filled.Schedule else Icons.Filled.Mood, // clock / face.dashed
                        contentDescription = null,
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (tab == 0) "还没有使用过表情包" else "还没有自定义表情包",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (tab == 2) { // 我的表情 tab → 引导添加（1:1 iOS tab==2 .bordered「添加表情包」）
                        Spacer(Modifier.height(12.dp))
                        AppButton(onClick = onManage, style = AppButtonStyle.Tonal) { Text("添加表情包") }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().height(320.dp).padding(horizontal = AppSpacing.screenGutter),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(stickers, key = { it.id }) { info ->
                        StickerGridCell(
                            info = info,
                            customStickers = customAsc,
                            onTap = { onSelect(info.id); onDismiss() },
                            onLongPress = { preview = info },
                        )
                    }
                }
            }

            AppButton(onClick = onManage, style = AppButtonStyle.Text, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  管理表情包")
            }
        }
    }

    preview?.let { info ->
        Dialog(onDismissRequest = { preview = null }) {
            Surface(
                shape = androidx.compose.material3.MaterialTheme.shapes.large,
                color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .padding(24.dp)
                        .combinedClickable(onClick = { preview = null }, onLongClick = {}),
                ) {
                    StickerImage(info.id, customAsc, 180.dp)
                    Text(info.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Text(
                        info.semanticDescription,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 打开面板时一次性读的两份 prefs 快照（K7·IO 线程读，null=尚未就绪）。 */
private data class StickerPickerPrefs(val disabled: Set<String>, val recentIds: List<String>)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerGridCell(
    info: StickerInfo,
    customStickers: List<CustomStickerEntity>,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.combinedClickable(onClick = onTap, onLongClick = onLongPress).padding(2.dp),
    ) {
        Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
            StickerImage(info.id, customStickers, 60.dp)
        }
        Text(
            info.name,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
