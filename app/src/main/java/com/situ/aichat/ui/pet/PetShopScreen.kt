package com.situ.aichat.ui.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.pet.PetItem
import com.situ.aichat.pet.PetItemCategory
import com.situ.aichat.pet.PetItemKind
import com.situ.aichat.pet.PetShopService
import com.situ.aichat.pet.metadata
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.gift.GiftColors
import kotlinx.coroutines.launch

/**
 * 宠物商店（1:1 iOS `PetShopView` 行为/状态，Material 3 原生重写非像素仿）：按物种过滤 → 零食/装扮分段 →
 * 2 列卡片网格（3 态：购买/已拥有/金币不足）→ 点卡弹确认底片 → [PetShopService.purchase] → snackbar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetShopScreen(
    onClose: () -> Unit,
    viewModel: PetShopViewModel = hiltViewModel(),
) {
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val pet by viewModel.pet.collectAsStateWithLifecycle()
    val category by viewModel.selectedCategory.collectAsStateWithLifecycle()

    val items = remember(pet, category) { viewModel.displayedItems(pet, category) }
    val inventory = pet?.metadata?.petInventory

    var confirmingItem by remember { mutableStateOf<PetItem?>(null) }
    var purchasing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val appHaptics = LocalAppHaptics.current

    Scaffold(
        topBar = {
            // 顶栏正下方是恒定不动的分段控件（列表在它之下）→ 内容永不滚到栏下，恒静止（图纸 §11 D-2）。
            AppTopBar(title = "宠物商店", onBack = onClose, actions = { CoinPill(balance) })
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AppSegmentedControl(
                options = PetItemCategory.entries,
                selected = category,
                onSelect = { viewModel.selectCategory(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenGutter, vertical = 8.dp),
                label = { it.displayName },
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = AppSpacing.screenGutter, end = AppSpacing.screenGutter, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    val owned = item.kind == PetItemKind.EQUIPPABLE && (inventory?.has(item.id) == true)
                    PetShopItemCard(item = item, balance = balance, ownedEquippable = owned) { confirmingItem = item }
                }
            }
        }
    }

    confirmingItem?.let { item ->
        PetShopBuyConfirmSheet(
            item = item,
            balance = balance,
            purchasing = purchasing,
            onDismiss = { if (!purchasing) confirmingItem = null },
            onConfirm = {
                if (purchasing) return@PetShopBuyConfirmSheet
                purchasing = true
                scope.launch {
                    val outcome = viewModel.purchase(item)
                    purchasing = false
                    confirmingItem = null
                    // P1-14：购买结果触觉（=iOS PetShopView.swift :204 成功 .success / :211/:218/:225 其余一切 .error，
                    // sensoryFeedback :118-119）。必须在 showSnackbar 前——它是挂起函数会等 snackbar 消失才返回。
                    if (outcome is PetShopService.PurchaseOutcome.Success) appHaptics.success() else appHaptics.error()
                    snackbarHostState.showSnackbar(purchaseMessage(outcome, item))
                }
            },
        )
    }
}

/** 购买结果文案（1:1 iOS toast 文字）。 */
private fun purchaseMessage(outcome: PetShopService.PurchaseOutcome, item: PetItem): String = when (outcome) {
    is PetShopService.PurchaseOutcome.Success -> "已购入 ${item.name}"
    is PetShopService.PurchaseOutcome.InsufficientCoins -> "金币不足（需要 ${outcome.required} 当前 ${outcome.current}）"
    is PetShopService.PurchaseOutcome.AlreadyOwned -> "已经拥有了"
    is PetShopService.PurchaseOutcome.UnknownItem, PetShopService.PurchaseOutcome.PetNotFound -> "购买失败"
}

/** 购买按钮 3 态（1:1 iOS BuyState）。 */
private enum class BuyState(val label: String) {
    AVAILABLE("购买"),
    ALREADY_OWNED("已拥有"),
    INSUFFICIENT("金币不足"),
}

private fun buyStateOf(item: PetItem, balance: Int, ownedEquippable: Boolean): BuyState = when {
    item.kind == PetItemKind.EQUIPPABLE && ownedEquippable -> BuyState.ALREADY_OWNED
    balance < item.price -> BuyState.INSUFFICIENT
    else -> BuyState.AVAILABLE
}

@Composable
private fun PetShopItemCard(
    item: PetItem,
    balance: Int,
    ownedEquippable: Boolean,
    onTap: () -> Unit,
) {
    val state = buyStateOf(item, balance, ownedEquippable)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
    ) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(petItemIcon(item), contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(32.dp))
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    if (item.isSignature) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(13.dp))
                    }
                }
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MonetizationOn, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(15.dp))
                Spacer(Modifier.size(3.dp))
                Text("${item.price}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                BuyChip(state)
            }
        }
    }
}

@Composable
private fun BuyChip(state: BuyState) {
    val (bg, fg) = when (state) {
        BuyState.AVAILABLE -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        BuyState.ALREADY_OWNED, BuyState.INSUFFICIENT ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(
            state.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PetShopBuyConfirmSheet(
    item: PetItem,
    balance: Int,
    purchasing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val canAfford = balance >= item.price
    val remaining = (balance - item.price).coerceAtLeast(0)
    AppSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 头部
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(56.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(petItemIcon(item), contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(28.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (item.isSignature) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 金额面板
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountRow("价格", item.price)
                    AmountRow("当前余额", balance)
                    AmountRow("购买后余额", remaining)
                }
            }

            // 效果说明
            Text(
                effectDescription(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 确认 / 取消
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (canAfford) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = canAfford && !purchasing, onClick = onConfirm),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (purchasing) {
                        // TODO(图纸未覆盖): 这枚转圈在**实心陶土主钮里面**，靠 onPrimary 反色才看得见；
                        //  AppLoadingRing 恒 accent 色 = 陶土画在陶土上 → 停手登记（D-13）。
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(
                        if (purchasing) "购买中…" else "确认购买 · ${item.price} 金币",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (canAfford) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "取消",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !purchasing, onClick = onDismiss)
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AmountRow(label: String, amount: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.MonetizationOn, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(3.dp))
        Text("$amount", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** 效果说明（1:1 iOS effectDescription）。 */
private fun effectDescription(item: PetItem): String = when (item.kind) {
    PetItemKind.CONSUMABLE -> {
        val parts = petBoostParts(item.statBoosts)
        if (parts.isEmpty()) "消耗品，使用一次即消耗" else "使用效果：${parts.joinToString(" · ")}"
    }
    PetItemKind.EQUIPPABLE -> "永久拥有，可在背包页反复佩戴 / 摘下"
}

/** 金币胶囊（toolbar 右上，1:1 iOS balancePill）。 */
@Composable
private fun CoinPill(balance: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.MonetizationOn, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(16.dp))
            Text("$balance", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}
