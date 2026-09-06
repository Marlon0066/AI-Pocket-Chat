package com.situ.aichat.ui.gift

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush as GraphicsBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.GiftCategory
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.gift.GiftItem
import com.situ.aichat.gift.GiftSendService
import com.situ.aichat.ui.designsystem.AppChoiceChip
import com.situ.aichat.ui.components.AnimatedCoinText
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 聊天内送礼底片（9.2d d-3，1:1 iOS `InChatGiftSheetView`）。
 *
 * 聊天加号弹出，锁定当前角色：角色头条 + 分类 Tab（全部 + 7 品类）+ 2 列网格（全部/手作 tab 首卡为「创建我的 DIY」入口）。
 * 点目录礼物 → 确认对话框 → [onSendGift]（[GiftSendService.sendInChat] 礼物以 .giftCard 进聊天流 + 触发 AI 回复）；
 * 点 DIY 入口 → [DIYGiftCreationSheet] → [onSendDiy]。成功关闭，余额不足/失败显示提示。
 *
 * 有意小简化（非像素仿 iOS）：头条不显关系副标题（装饰性，currentRelationship 为 suspend 查询，避免为副标题加 VM 管线）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InChatGiftSheet(
    characterName: String,
    avatarPath: String?,
    balance: Int,
    onSendGift: suspend (GiftItem) -> GiftSendService.InChatSendOutcome,
    onSendDiy: suspend (title: String, content: String, imageUri: Uri?, cost: Int) -> GiftSendService.InChatSendOutcome,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 审计 B2（拍板 2026-07-02）：分类选中 + DIY 创作器开合跨重建存活（enum/Boolean 天然可存）；
    // confirmingItem 确认框留瞬态（转屏丢确认框 → 重点一下即可，按目录 id 重解析不成比例）。
    var category by rememberSaveable { mutableStateOf<GiftCategory?>(null) }
    var confirmingItem by remember { mutableStateOf<GiftItem?>(null) }
    var showDiyCreator by rememberSaveable { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val items = remember(category) { category?.let { GiftCatalog.items(it) } ?: GiftCatalog.allItems }
    val showDiyEntry = category == null || category == GiftCategory.HANDMADE

    fun performSend(item: GiftItem) {
        if (isSending) return
        scope.launch {
            isSending = true
            errorText = null
            when (val outcome = onSendGift(item)) {
                is GiftSendService.InChatSendOutcome.Success -> {
                    // 成功后保持 sheet ~500ms 让余额滚动可见再关；isSending 不复位防误触
                    // （=iOS InChatGiftSheetView.swift:455-462 Task.sleep(500ms)+不 reset）。
                    delay(500)
                    onDismiss()
                }
                is GiftSendService.InChatSendOutcome.InsufficientCoins -> {
                    errorText = "余额不足，还差 ${outcome.need - outcome.have} 金币"
                    isSending = false
                }
                GiftSendService.InChatSendOutcome.SpendFailed -> {
                    errorText = "送礼失败，请稍后重试"
                    isSending = false
                }
            }
        }
    }

    AppSheet(onDismissRequest = { if (!isSending) onDismiss() }, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("送礼物给 $characterName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                BalancePillSmall(balance)
            }
            errorText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = GiftColors.Unafford, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.screenGutter),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CharacterAvatar(name = characterName, avatarPath = avatarPath, size = 36.dp)
                        Text("送给 $characterName", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GiftCategoryTabs(selected = category, onSelect = { category = it })
                }
                if (showDiyEntry) {
                    item(key = "diy_entry") { DiyEntryCard(enabled = !isSending, onClick = { if (!isSending) showDiyCreator = true }) }
                }
                items(items, key = { it.id }) { item ->
                    GiftCard(item = item, balance = balance, onClick = { if (!isSending) confirmingItem = item })
                }
            }
        }
    }

    confirmingItem?.let { item ->
        AppDialog(
            onDismissRequest = { confirmingItem = null },
            title = "送出这份 ${item.name}？",
            body = "将从余额扣 ${item.price} 金币",
            confirmText = "确认送出",
            onConfirm = {
                confirmingItem = null
                performSend(item)
            },
            dismissText = "取消",
            onDismiss = { confirmingItem = null },
        )
    }

    if (showDiyCreator) {
        DIYGiftCreationSheet(
            onSend = onSendDiy,
            onSuccess = {
                showDiyCreator = false
                onDismiss()
            },
            onDismiss = { showDiyCreator = false },
        )
    }
}

@Composable
private fun BalancePillSmall(balance: Int) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MonetizationOn, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(16.dp))
            // 数字滚动 + 千分位（P1-12·=iOS numericText(value:)+smooth(0.35)+.number 分组）。
            AnimatedCoinText(balance, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/** 分类 Tab（与礼物店共用样式，1:1 iOS categoryTabs）。 */
@Composable
private fun GiftCategoryTabs(selected: GiftCategory?, onSelect: (GiftCategory?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        item {
            AppChoiceChip(selected = selected == null, onClick = { onSelect(null) }, label = "全部")
        }
        items(GiftCategory.entries) { cat ->
            val icon: ImageVector = GiftSymbolMapping.materialIcon(cat.iconSymbol)
            AppChoiceChip(
                selected = selected == cat,
                onClick = { onSelect(cat) },
                label = cat.displayName,
                leading = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/** DIY 入口卡（1:1 iOS diyEntryCard：暖金渐变 + 金边 + paintbrush + 「创建我的 DIY」/「手作·你自己做」/「2–20 金币」）。 */
@Composable
private fun DiyEntryCard(enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(0.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .border(1.2.dp, GiftColors.Gold.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .background(
                    GraphicsBrush.linearGradient(listOf(Color(0xFFFFF4E0), Color(0xFFFDE4C0))),
                    RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Brush, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(38.dp))
                Text("创建我的 DIY", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color(0xFF8A5A1F))
            }
        }
        Text("手作 · 你自己做", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text("亲手做一份独特的", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MonetizationOn, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(14.dp))
            Text("2–20", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("金币", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
