package com.situ.aichat.ui.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.pet.PetItem
import com.situ.aichat.pet.PetItemCategory
import com.situ.aichat.pet.PetItemKind
import com.situ.aichat.pet.PetShopService
import com.situ.aichat.pet.metadata
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.grainSurface
import com.situ.aichat.ui.gift.GiftColors
import kotlinx.coroutines.launch

/**
 * 宠物商店（💰 钱路 UI 壳·零业务逻辑改动）：按物种过滤 → 零食/装扮分段 →
 * 2 列暖陶卡片网格（3 态：购买/已拥有/金币不足）→ 点卡弹确认底片 → [PetShopService.purchase] → snackbar。
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
    val colors = AppTheme.colors

    Scaffold(
        containerColor = colors.surface.base,
        topBar = {
            AppTopBar(title = "宠物商店", onBack = onClose, actions = { CoinPill(balance) })
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(colors.surface.base)
                .grainSurface(),
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
                contentPadding = PaddingValues(
                    start = AppSpacing.screenGutter,
                    end = AppSpacing.screenGutter,
                    top = 4.dp,
                    bottom = 32.dp,
                ),
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
                    if (outcome is PetShopService.PurchaseOutcome.Success) appHaptics.success() else appHaptics.error()
                    snackbarHostState.showSnackbar(petShopPurchaseMessage(outcome, item))
                }
            },
        )
    }
}

/** 购买结果文案（单源，暖陶与琉璃共用）。 */
internal fun petShopPurchaseMessage(outcome: PetShopService.PurchaseOutcome, item: PetItem): String = when (outcome) {
    is PetShopService.PurchaseOutcome.Success -> "已购入 ${item.name}"
    is PetShopService.PurchaseOutcome.InsufficientCoins -> "金币不足（需要 ${outcome.required} 当前 ${outcome.current}）"
    is PetShopService.PurchaseOutcome.AlreadyOwned -> "已经拥有了"
    is PetShopService.PurchaseOutcome.UnknownItem, PetShopService.PurchaseOutcome.PetNotFound -> "购买失败"
}

/** 购买按钮 3 态（单源，暖陶与琉璃共用）。 */
internal enum class PetBuyState(val label: String) {
    AVAILABLE("购买"),
    ALREADY_OWNED("已拥有"),
    INSUFFICIENT("金币不足"),
}

internal fun petBuyStateOf(item: PetItem, balance: Int, ownedEquippable: Boolean): PetBuyState = when {
    item.kind == PetItemKind.EQUIPPABLE && ownedEquippable -> PetBuyState.ALREADY_OWNED
    balance < item.price -> PetBuyState.INSUFFICIENT
    else -> PetBuyState.AVAILABLE
}

/** 效果说明（单源，暖陶与琉璃共用）。 */
internal fun petShopEffectDescription(item: PetItem): String = when (item.kind) {
    PetItemKind.CONSUMABLE -> {
        val parts = petBoostParts(item.statBoosts)
        if (parts.isEmpty()) "消耗品，使用一次即消耗" else "使用效果：${parts.joinToString(" · ")}"
    }
    PetItemKind.EQUIPPABLE -> "永久拥有，可在背包页反复佩戴 / 摘下"
}

@Composable
private fun PetShopItemCard(
    item: PetItem,
    balance: Int,
    ownedEquippable: Boolean,
    onTap: () -> Unit,
) {
    val colors = AppTheme.colors
    val state = petBuyStateOf(item, balance, ownedEquippable)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appCardSurface()
            .clickableScale(onClickLabel = item.name, onClick = onTap)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(colors.accent.container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(petItemIcon(item), contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    item.name,
                    style = AppTypography.label,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.text.primary,
                    maxLines = 1,
                )
                if (item.isSignature) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(13.dp))
                }
            }
            Text(
                item.subtitle,
                style = AppTypography.caption,
                color = colors.text.secondary,
                maxLines = 1,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppActionIcons.Coin, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(15.dp))
            Spacer(Modifier.size(3.dp))
            Text("${item.price}", style = AppTypography.amount, color = colors.text.primary)
            Spacer(Modifier.weight(1f))
            BuyChip(state)
        }
    }
}

@Composable
private fun BuyChip(state: PetBuyState) {
    val colors = AppTheme.colors
    val (bg, fg) = when (state) {
        PetBuyState.AVAILABLE -> colors.accent.primary to colors.accent.onPrimary
        PetBuyState.ALREADY_OWNED, PetBuyState.INSUFFICIENT ->
            colors.surface.sunken to colors.text.secondary
    }
    Box(
        modifier = Modifier
            .clip(AppShapes.full)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(state.label, style = AppTypography.caption, fontWeight = FontWeight.SemiBold, color = fg)
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
    val colors = AppTheme.colors
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(colors.accent.container),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(petItemIcon(item), contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(28.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.name, style = AppTypography.titleSmall, color = colors.text.primary)
                        if (item.isSignature) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(item.subtitle, style = AppTypography.secondary, color = colors.text.secondary)
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.medium)
                    .background(colors.surface.sunken)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AmountRow("价格", item.price)
                AmountRow("当前余额", balance)
                AmountRow("购买后余额", remaining)
            }

            Text(petShopEffectDescription(item), style = AppTypography.caption, color = colors.text.secondary)

            AppButton(
                onClick = onConfirm,
                enabled = canAfford && !purchasing,
                style = AppButtonStyle.Primary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (purchasing) {
                    AppLoadingRing(
                        modifier = Modifier.size(16.dp),
                        size = AppLoadingRingSize.Small,
                        color = colors.accent.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Text(if (purchasing) "购买中…" else "确认购买 · ${item.price} 金币")
            }
            AppButton(
                onClick = onDismiss,
                enabled = !purchasing,
                style = AppButtonStyle.Text,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, amount: Int) {
    val colors = AppTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AppTypography.secondary, color = colors.text.secondary)
        Spacer(Modifier.weight(1f))
        Icon(AppActionIcons.Coin, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(3.dp))
        Text("$amount", style = AppTypography.amount, color = colors.text.primary)
    }
}

/** 金币胶囊（顶栏右上）。 */
@Composable
private fun CoinPill(balance: Int) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(AppShapes.full)
            .background(colors.surface.raised)
            .border(0.5.dp, colors.surface.stroke, AppShapes.full)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppActionIcons.Coin, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(16.dp))
        Text("$balance", style = AppTypography.amount, color = colors.text.primary)
    }
}
