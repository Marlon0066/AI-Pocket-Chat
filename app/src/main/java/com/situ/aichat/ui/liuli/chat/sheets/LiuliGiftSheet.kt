package com.situ.aichat.ui.liuli.chat.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.data.model.GiftCategory
import com.situ.aichat.gift.GiftItem
import com.situ.aichat.gift.GiftSendService
import com.situ.aichat.ui.components.AnimatedCoinText
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.gift.GiftImage
import com.situ.aichat.ui.gift.GiftSymbolMapping
import com.situ.aichat.ui.liuli.designsystem.LiuliChip
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 网格格几何（对版稿 A 甲）：格底 `surface.raised` 45% · 18 圆角 · 礼物图 140。 */
private val CELL_SHAPE = RoundedCornerShape(18.dp)
private const val CELL_ALPHA = 0.45f
private val CELL_IMAGE = 140.dp

/** 成功后保持 sheet 可见让余额滚动看得见的时长（照抄 F23 = iOS 500ms）。 */
private const val SUCCESS_LINGER_MS = 500L

/**
 * 琉璃版聊天内送礼底片（图纸 2026-09-05 卷二C C6b · A-16 · 照抄源 F23
 * `ui/gift/InChatGiftSheet.kt:70-242`）。
 *
 * **只换渲染皮·钱路零碰**（§6「C6 钱路」）：`category` / `showDiyCreator` 的 saveable 存活、
 * `showDiyEntry` 的显隐条件（全部 tab 或手作 tab）、`performSend` 的三分支（成功 delay 500ms 再关且
 * `isSending` 不复位防误触 / 余额不足 / 送礼失败）、确认框两行文案，全部逐字照抄。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiuliGiftSheet(
    characterName: String,
    avatarPath: String?,
    balance: Int,
    onSendGift: suspend (GiftItem) -> GiftSendService.InChatSendOutcome,
    onSendDiy: suspend (title: String, content: String, imageUri: android.net.Uri?, cost: Int) -> GiftSendService.InChatSendOutcome,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                    delay(SUCCESS_LINGER_MS)
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

    LiuliSheetShell(onDismissRequest = { if (!isSending) onDismiss() }, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("送礼物给 $characterName", style = AppTypography.titleSmall, color = onGlass.primary, modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(LiuliShapes.pill)
                        .background(colors.surface.raised.copy(alpha = CELL_ALPHA))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.MonetizationOn, contentDescription = null, tint = colors.economy.gold, modifier = Modifier.size(16.dp))
                    AnimatedCoinText(balance, style = AppTypography.amount.copy(color = onGlass.primary))
                }
            }
            errorText?.let {
                Text(it, style = AppTypography.snackbarBody, color = colors.status.onError, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CharacterAvatar(name = characterName, avatarPath = avatarPath, size = 36.dp)
                        Text("送给 $characterName", style = AppTypography.label, color = onGlass.primary)
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        item {
                            LiuliChip(selected = category == null, onClick = { category = null }, label = "全部")
                        }
                        items(GiftCategory.entries) { cat ->
                            LiuliChip(
                                selected = category == cat,
                                onClick = { category = cat },
                                label = cat.displayName,
                                leading = {
                                    Icon(GiftSymbolMapping.materialIcon(cat.iconSymbol), contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                            )
                        }
                    }
                }
                if (showDiyEntry) {
                    item(key = "diy_entry") {
                        LiuliDiyEntryCell(enabled = !isSending, onClick = { if (!isSending) showDiyCreator = true })
                    }
                }
                items(items, key = { it.id }) { item ->
                    LiuliGiftCell(item = item, balance = balance, onClick = { if (!isSending) confirmingItem = item })
                }
            }
        }
    }

    confirmingItem?.let { item ->
        LiuliDialog(
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
        LiuliDiyGiftCreationSheet(
            onSend = onSendDiy,
            onSuccess = {
                showDiyCreator = false
                onDismiss()
            },
            onDismiss = { showDiyCreator = false },
        )
    }
}

/** 目录礼物格：140 图 + 名 + 副标 + 价格行（余额不足走 `status.onError`·照抄 F23 的可负担语义）。 */
@Composable
private fun LiuliGiftCell(item: GiftItem, balance: Int, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val canAfford = balance >= item.price
    Column(
        modifier = Modifier
            .clip(CELL_SHAPE)
            .background(colors.surface.raised.copy(alpha = CELL_ALPHA))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            GiftImage(item = item, size = CELL_IMAGE, cornerRadius = 12.dp, showsShadow = false)
        }
        Text(item.name, style = AppTypography.label, color = onGlass.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle, style = AppTypography.snackbarBody, color = onGlass.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                Icons.Filled.MonetizationOn,
                contentDescription = null,
                tint = if (canAfford) colors.economy.gold else colors.status.onError,
                modifier = Modifier.size(14.dp),
            )
            Text(
                "${item.price} 金币",
                style = AppTypography.amount,
                color = if (canAfford) onGlass.primary else colors.status.onError,
            )
        }
    }
}

/** DIY 入口格（照抄 F23 四行文案：创建我的 DIY / 手作 · 你自己做 / 亲手做一份独特的 / 2–20 金币）。 */
@Composable
private fun LiuliDiyEntryCell(enabled: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    Column(
        modifier = Modifier
            .clip(CELL_SHAPE)
            .background(colors.surface.raised.copy(alpha = CELL_ALPHA))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 与礼物格同构：满宽 + 140 高 + 居中（写成 `size(140)` 会把方块钉在左侧，与右列不对齐）。
        Box(
            modifier = Modifier.fillMaxWidth().height(CELL_IMAGE),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Brush, contentDescription = null, tint = colors.economy.gold, modifier = Modifier.size(38.dp))
                Text("创建我的 DIY", style = AppTypography.label, color = onGlass.primary)
            }
        }
        Text("手作 · 你自己做", style = AppTypography.label, color = onGlass.primary, maxLines = 1)
        Text("亲手做一份独特的", style = AppTypography.snackbarBody, color = onGlass.secondary, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Filled.MonetizationOn, contentDescription = null, tint = colors.economy.gold, modifier = Modifier.size(14.dp))
            Text("2–20 金币", style = AppTypography.amount, color = onGlass.primary)
        }
    }
}
