package com.situ.aichat.ui.sticker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.sticker.BuiltInStickerCatalog
import com.situ.aichat.sticker.StickerInfo
import com.situ.aichat.sticker.StickerService
import com.situ.aichat.ui.chat.StickerImage
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppSettingsRow
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.designsystem.CardSegment
import com.situ.aichat.ui.designsystem.appCardSegmentSurface
import com.situ.aichat.ui.designsystem.appCardSurface

/**
 * 表情包管理（1:1 iOS `StickerManagementView` + `HiddenBuiltInStickersView`）：我的表情包（删除）+
 * 预置表情包（隐藏/恢复）+ 导入入口 + 「角色发送表情包」总开关（Android 把该设置并入此页）。
 *
 * Android 取舍：① 删除/隐藏用行尾动作按钮替 iOS 左滑（native 习惯，铁律#1 允许达同效）；
 * ② 已隐藏内置改为**页内可展开区**而非独立页——同一 VM 单一 [StickerManagementViewModel.disabledIds]
 * 流，隐藏/恢复后立即一致（避免独立页两个 VM 实例不同步，替 iOS 的 NotificationCenter 广播）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerManagementScreen(
    onBack: () -> Unit,
    onImport: () -> Unit,
    viewModel: StickerManagementViewModel = hiltViewModel(),
) {
    val customStickers by viewModel.customStickers.collectAsStateWithLifecycle()
    val disabled by viewModel.disabledIds.collectAsStateWithLifecycle()
    val enabled by viewModel.stickersEnabled.collectAsStateWithLifecycle()
    val enabledBuiltIns = remember(disabled) { BuiltInStickerCatalog.enabled(disabled) }
    val hiddenStickers = remember(disabled) { BuiltInStickerCatalog.all.filter { it.id in disabled } }
    val atLimit = customStickers.size >= StickerService.CUSTOM_STICKER_LIMIT
    var showHidden by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = "表情包管理",
                onBack = onBack,
                lifted = listState.canScrollBackward,
                actions = {
                    IconButton(onClick = onImport, enabled = !atLimit) { Icon(AppTopBarIcons.Add, contentDescription = stringResource(R.string.a11y_add_sticker_pack)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).contentMaxWidth()) {
            // 总开关：无标题卡壳单行（透明底 SettingsSwitchRow·§4.B2）。
            item {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp).appCardSurface().padding(vertical = 6.dp)) {
                    SettingsSwitchRow(
                        title = "角色发送表情包",
                        subtitle = "关闭后角色不再发表情包，历史表情仍正常显示",
                        checked = enabled,
                        onCheckedChange = { viewModel.setStickersEnabled(it) },
                    )
                }
            }

            // 我的表情包 分段卡（header=Top·空态=Bottom·行末=Bottom 余 Middle·§4.B2 照钱包先例）。
            item {
                Box(Modifier.stickerSegment(CardSegment.Top)) {
                    StickerSectionHeader("我的表情包", "${customStickers.size}/${StickerService.CUSTOM_STICKER_LIMIT}")
                }
            }
            if (customStickers.isEmpty()) {
                item { Box(Modifier.stickerSegment(CardSegment.Bottom)) { StickerEmptyHint("还没有自定义表情包") } }
            } else {
                itemsIndexed(customStickers, key = { _, it -> it.stickerUuid }) { index, sticker ->
                    val seg = if (index == customStickers.lastIndex) CardSegment.Bottom else CardSegment.Middle
                    Box(Modifier.stickerSegment(seg)) {
                        CustomStickerRow(sticker, customStickers, onDelete = { viewModel.deleteCustom(sticker) })
                    }
                }
            }

            // 预置表情包 分段卡：header=Top·启用行/空态/折叠行/展开行同属一段序列·当前可见末项=Bottom（§4.B2）。
            val presetHasHidden = hiddenStickers.isNotEmpty()
            item {
                Box(Modifier.stickerSegment(CardSegment.Top)) {
                    StickerSectionHeader("预置表情包", "${enabledBuiltIns.size}/${BuiltInStickerCatalog.all.size}")
                }
            }
            if (enabledBuiltIns.isEmpty()) {
                // 全隐藏空态：折叠行在其后 → 段中间（presetHasHidden 必真）。
                item { Box(Modifier.stickerSegment(CardSegment.Middle)) { StickerEmptyHint("已全部隐藏") } }
            } else {
                itemsIndexed(enabledBuiltIns, key = { _, it -> "on_${it.id}" }) { index, sticker ->
                    val seg = if (!presetHasHidden && index == enabledBuiltIns.lastIndex) CardSegment.Bottom else CardSegment.Middle
                    Box(Modifier.stickerSegment(seg)) {
                        BuiltInStickerRow(sticker, trailingLabel = "隐藏", onAction = { viewModel.hideBuiltIn(sticker.id) })
                    }
                }
            }

            if (presetHasHidden) {
                item {
                    val seg = if (!showHidden) CardSegment.Bottom else CardSegment.Middle
                    Box(Modifier.stickerSegment(seg)) {
                        AppSettingsRow(
                            title = "已隐藏的内置表情",
                            value = "${hiddenStickers.size}",
                            trailing = {
                                Icon(
                                    if (showHidden) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = AppTheme.colors.text.tertiary,
                                )
                            },
                            // trailing 是纯装饰（展开箭头），点击照旧由整行承担 —— 这也是本组件
                            // 「trailing 在场时行自己不吃点击」的设计意图：交互权交给调用方。
                            modifier = Modifier.fillMaxWidth().clickable { showHidden = !showHidden },
                        )
                    }
                }
                if (showHidden) {
                    item {
                        Box(Modifier.stickerSegment(CardSegment.Middle)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                AppButton(onClick = { viewModel.restoreAllBuiltIn() }, style = AppButtonStyle.Text) { Text("全部恢复") }
                            }
                        }
                    }
                    itemsIndexed(hiddenStickers, key = { _, it -> "off_${it.id}" }) { index, sticker ->
                        val seg = if (index == hiddenStickers.lastIndex) CardSegment.Bottom else CardSegment.Middle
                        Box(Modifier.stickerSegment(seg)) {
                            BuiltInStickerRow(sticker, trailingLabel = "取消隐藏", onAction = { viewModel.enableBuiltIn(sticker.id) })
                        }
                    }
                }
            }

            item {
                Text(
                    "隐藏不想要的预置表情后，它不再出现在选择器和 AI 提示词中，但历史消息仍能正常显示，随时可以恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StickerSectionHeader(title: String, trailing: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StickerEmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 分段卡承托：gutter 20 + 段起（Top）留 16 顶间隔与上一张卡分离，Middle/Bottom 无间隔续接成一张连续卡（§4.B2）。 */
@Composable
private fun Modifier.stickerSegment(segment: CardSegment): Modifier = this
    .fillMaxWidth()
    .padding(start = 20.dp, end = 20.dp, top = if (segment == CardSegment.Top) 16.dp else 0.dp)
    .appCardSegmentSurface(segment)

@Composable
private fun GifChip() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Text(
            "GIF",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun CustomStickerRow(
    sticker: CustomStickerEntity,
    customStickers: List<CustomStickerEntity>,
    onDelete: () -> Unit,
) {
    // TODO(图纸未覆盖): leading 是 44dp 表情缩略图，不是 AppSettingsRow 的 30dp 陶土瓦片（§4.8 点名的
    //  「自定义 leading 尺寸」）；尾槽还是「GIF 徽标 + 文字钮」。停手登记（施工日志 D-12）。
    ListItem(
        leadingContent = { StickerThumb { StickerImage(sticker.stickerUuid, customStickers, 44.dp) } },
        headlineContent = { Text(sticker.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (sticker.semanticDescription.isNotEmpty()) {
                Text(sticker.semanticDescription, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (sticker.isAnimated) GifChip()
                AppButton(onClick = onDelete, style = AppButtonStyle.Text) { Text("删除") }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun BuiltInStickerRow(sticker: StickerInfo, trailingLabel: String, onAction: () -> Unit) {
    // TODO(图纸未覆盖): 同上一行，44dp 缩略图 leading + 文字钮尾槽 → 停手登记（施工日志 D-12）。
    ListItem(
        leadingContent = { StickerThumb { StickerImage(sticker.id, emptyList(), 44.dp) } },
        headlineContent = { Text(sticker.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(sticker.semanticDescription, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (sticker.isAnimated) GifChip()
                AppButton(onClick = onAction, style = AppButtonStyle.Text) { Text(trailingLabel) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** 44dp 固定缩略图槽（贴纸图自适应，加载/失败不撑破布局）。 */
@Composable
private fun StickerThumb(content: @Composable () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { content() }
}
