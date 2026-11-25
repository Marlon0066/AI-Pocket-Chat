package com.situ.aichat.ui.liuli.pet

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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.gift.GiftColors
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliMaterials
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmented
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberGridCollapsed
import com.situ.aichat.ui.pet.PetBuyState
import com.situ.aichat.ui.pet.PetShopViewModel
import com.situ.aichat.ui.pet.petBuyStateOf
import com.situ.aichat.ui.pet.petItemIcon
import com.situ.aichat.ui.pet.petShopEffectDescription
import com.situ.aichat.ui.pet.petShopPurchaseMessage
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.launch

/** 宠物商店（琉璃 2.0 脸 · 💰只换界面壳，扣币/记账/库存逻辑全走 [PetShopViewModel]）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiuliPetShopScreen(
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
    val dark = LocalIsDarkTheme.current
    val gridState = rememberLazyGridState()
    val collapsed = rememberGridCollapsed(gridState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        LiuliPage(
            title = "宠物商店",
            onBack = onClose,
            collapsed = collapsed,
            actions = { LiuliPetCoinPill(balance, dark) },
        ) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = LiuliPageGeometry.gutter),
                contentPadding = PaddingValues(
                    top = LiuliPageGeometry.navRow + 4.dp,
                    bottom = navBottom + LiuliPageGeometry.pageBottom,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(2) }, key = "title") {
                    LiuliLargeTitle("宠物商店", modifier = Modifier.padding(bottom = 8.dp))
                }
                item(span = { GridItemSpan(2) }, key = "segment") {
                    LiuliSegmented(
                        options = PetItemCategory.entries,
                        selected = category,
                        label = { it.displayName },
                        onSelect = { viewModel.selectCategory(it) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }
                items(items, key = { it.id }) { item ->
                    val owned = item.kind == PetItemKind.EQUIPPABLE && (inventory?.has(item.id) == true)
                    LiuliPetShopCard(item, balance, owned, dark) { confirmingItem = item }
                }
            }
        }
        AppSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    confirmingItem?.let { item ->
        LiuliPetShopBuySheet(
            item = item,
            balance = balance,
            purchasing = purchasing,
            dark = dark,
            onDismiss = { if (!purchasing) confirmingItem = null },
            onConfirm = {
                if (purchasing) return@LiuliPetShopBuySheet
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

@Composable
private fun LiuliPetCoinPill(balance: Int, dark: Boolean) {
    Row(
        modifier = Modifier
            .liuliCardMaterial(LiuliShapes.pill, dark)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppActionIcons.Coin, null, tint = GiftColors.Gold, modifier = Modifier.size(15.dp))
        Text("$balance", style = AppTypography.amount, color = AppTheme.colors.text.primary)
    }
}

@Composable
private fun LiuliPetShopCard(item: PetItem, balance: Int, owned: Boolean, dark: Boolean, onTap: () -> Unit) {
    val colors = AppTheme.colors
    val state = petBuyStateOf(item, balance, owned)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liuliCardMaterial(LiuliShapes.group, dark)
            .clickableScale(onClickLabel = item.name, onClick = onTap)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(colors.accent.container),
            contentAlignment = Alignment.Center,
        ) { Icon(petItemIcon(item), null, tint = GiftColors.Gold, modifier = Modifier.size(28.dp)) }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, style = AppTypography.label, fontWeight = FontWeight.SemiBold, color = colors.text.primary, maxLines = 1)
                if (item.isSignature) Icon(Icons.Filled.AutoAwesome, null, tint = GiftColors.Gold, modifier = Modifier.size(13.dp))
            }
            Text(item.subtitle, style = AppTypography.caption, color = colors.text.secondary, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppActionIcons.Coin, null, tint = GiftColors.Gold, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(3.dp))
            Text("${item.price}", style = AppTypography.amount, color = colors.text.primary)
            Spacer(Modifier.weight(1f))
            val bg = if (state == PetBuyState.AVAILABLE) colors.accent.primary else LiuliMaterials.chipFill(dark)
            val fg = if (state == PetBuyState.AVAILABLE) colors.accent.onPrimary else colors.text.secondary
            Box(Modifier.clip(AppShapes.full).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(state.label, style = AppTypography.caption, fontWeight = FontWeight.SemiBold, color = fg)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiuliPetShopBuySheet(
    item: PetItem,
    balance: Int,
    purchasing: Boolean,
    dark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = AppTheme.colors
    val canAfford = balance >= item.price
    val remaining = (balance - item.price).coerceAtLeast(0)
    LiuliSheetShell(onDismissRequest = onDismiss, title = item.name, subtitle = item.subtitle) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LiuliAmountRow("价格", item.price)
                LiuliAmountRow("当前余额", balance)
                LiuliAmountRow("购买后余额", remaining)
            }
            Text(petShopEffectDescription(item), style = AppTypography.caption, color = colors.text.secondary)
            LiuliButton(
                onClick = onConfirm,
                style = LiuliButtonStyle.Prominent,
                enabled = canAfford && !purchasing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (purchasing) {
                    AppLoadingRing(Modifier.size(16.dp), AppLoadingRingSize.Small, color = colors.accent.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp))
                }
                Text(if (purchasing) "购买中…" else "确认购买 · ${item.price} 金币")
            }
            LiuliButton(onClick = onDismiss, style = LiuliButtonStyle.Text, enabled = !purchasing, modifier = Modifier.fillMaxWidth()) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun LiuliAmountRow(label: String, amount: Int) {
    val colors = AppTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AppTypography.secondary, color = colors.text.secondary)
        Spacer(Modifier.weight(1f))
        Icon(AppActionIcons.Coin, null, tint = GiftColors.Gold, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(3.dp))
        Text("$amount", style = AppTypography.amount, color = colors.text.primary)
    }
}
