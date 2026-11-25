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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Restaurant
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.pet.PetInventoryService
import com.situ.aichat.pet.PetItem
import com.situ.aichat.pet.PetItemCategory
import com.situ.aichat.pet.PetItemKind
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.gift.GiftColors
import com.situ.aichat.ui.liuli.designsystem.LiuliMaterials
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmented
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.pet.PetInventoryViewModel
import com.situ.aichat.ui.pet.petBoostParts
import com.situ.aichat.ui.pet.petInventoryConsumeMessage
import com.situ.aichat.ui.pet.petInventoryEquipMessage
import com.situ.aichat.ui.pet.petItemIcon
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 宠物背包（琉璃 2.0 脸）。 */
@Composable
fun LiuliPetInventoryScreen(
    onClose: () -> Unit,
    onReaction: (String) -> Unit = {},
    viewModel: PetInventoryViewModel = hiltViewModel(),
) {
    val pet by viewModel.pet.collectAsStateWithLifecycle()
    val category by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val items = remember(pet, category) { viewModel.displayedItems(pet, category) }
    val foodCount = viewModel.foodCount(pet)
    val costumeCount = viewModel.costumeCount(pet)

    var cooldownIds by remember { mutableStateOf(emptySet<String>()) }
    var processingEquipId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val appHaptics = LocalAppHaptics.current
    val dark = LocalIsDarkTheme.current
    val listState = rememberLazyListState()
    val collapsed = rememberLargeTitleCollapsed(listState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        LiuliPage(
            title = "背包",
            onBack = onClose,
            collapsed = collapsed,
            actions = { LiuliCountPill(foodCount + costumeCount, dark) },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = LiuliPageGeometry.gutter),
                contentPadding = PaddingValues(
                    top = LiuliPageGeometry.navRow + 4.dp,
                    bottom = navBottom + LiuliPageGeometry.pageBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "title") { LiuliLargeTitle("背包", modifier = Modifier.padding(bottom = 8.dp)) }
                item(key = "segment") {
                    LiuliSegmented(
                        options = PetItemCategory.entries,
                        selected = category,
                        label = { cat ->
                            val count = if (cat == PetItemCategory.FOOD) foodCount else costumeCount
                            if (count > 0) "${cat.displayName} ×$count" else cat.displayName
                        },
                        onSelect = { viewModel.selectCategory(it) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }
                if (items.isEmpty()) {
                    item(key = "empty") { LiuliEmptyInventory(category) }
                } else {
                    items(items, key = { it.id }) { item ->
                        LiuliInventoryItemRow(
                            item = item,
                            quantity = viewModel.quantityOf(pet, item.id),
                            isEquipped = viewModel.isEquipped(pet, item.id),
                            isInCooldown = item.id in cooldownIds,
                            isProcessingEquip = processingEquipId == item.id,
                            dark = dark,
                            onPrimaryTap = {
                                when (item.kind) {
                                    PetItemKind.CONSUMABLE -> {
                                        if (item.id in cooldownIds) return@LiuliInventoryItemRow
                                        cooldownIds = cooldownIds + item.id
                                        scope.launch {
                                            val outcome = viewModel.useConsumable(item)
                                            val ok = outcome is PetInventoryService.ConsumeOutcome.Consumed || outcome is PetInventoryService.ConsumeOutcome.ConsumedOut
                                            if (ok) {
                                                appHaptics.success()
                                                onReaction(viewModel.consumeReaction(item.name))
                                            } else appHaptics.error()
                                            snackbarHostState.showSnackbar(petInventoryConsumeMessage(outcome, item))
                                            if (ok) delay(2000)
                                            cooldownIds = cooldownIds - item.id
                                        }
                                    }
                                    PetItemKind.EQUIPPABLE -> {
                                        if (processingEquipId != null) return@LiuliInventoryItemRow
                                        val wasEquipped = viewModel.isEquipped(pet, item.id)
                                        processingEquipId = item.id
                                        scope.launch {
                                            val outcome = viewModel.toggleEquip(item)
                                            processingEquipId = null
                                            if (outcome is PetInventoryService.EquipOutcome.Done) {
                                                appHaptics.success()
                                                onReaction(viewModel.equipReaction(item.name, wasEquipped))
                                            } else appHaptics.error()
                                            snackbarHostState.showSnackbar(petInventoryEquipMessage(outcome, item, wasEquipped))
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        AppSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun LiuliCountPill(total: Int, dark: Boolean) {
    Row(
        modifier = Modifier.liuliCardMaterial(LiuliShapes.pill, dark).padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Backpack, null, tint = AppTheme.colors.accent.text, modifier = Modifier.size(15.dp))
        Text("$total", style = AppTypography.amount, color = AppTheme.colors.text.primary)
    }
}

@Composable
private fun LiuliInventoryItemRow(
    item: PetItem,
    quantity: Int,
    isEquipped: Boolean,
    isInCooldown: Boolean,
    isProcessingEquip: Boolean,
    dark: Boolean,
    onPrimaryTap: () -> Unit,
) {
    val colors = AppTheme.colors
    val secondary = when (item.kind) {
        PetItemKind.CONSUMABLE -> petBoostParts(item.statBoosts).joinToString(" · ").ifEmpty { item.subtitle }
        PetItemKind.EQUIPPABLE -> item.subtitle
    }
    Row(
        Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(colors.accent.container), contentAlignment = Alignment.Center) {
            Icon(petItemIcon(item), null, tint = GiftColors.Gold, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, style = AppTypography.label, fontWeight = FontWeight.SemiBold, color = colors.text.primary, maxLines = 1)
                if (item.isSignature) Icon(Icons.Filled.AutoAwesome, null, tint = GiftColors.Gold, modifier = Modifier.size(13.dp))
            }
            Text(secondary, style = AppTypography.caption, color = colors.text.secondary, maxLines = 1)
        }
        if (item.kind == PetItemKind.CONSUMABLE) {
            Text("×$quantity", style = AppTypography.amount, color = colors.text.secondary)
            LiuliActionPill(if (isInCooldown) "使用中…" else "使用", !isInCooldown, true, dark, onPrimaryTap)
        } else {
            if (isEquipped) {
                Box(Modifier.clip(AppShapes.full).background(colors.accent.container).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text("已佩戴", style = AppTypography.caption, fontWeight = FontWeight.SemiBold, color = colors.accent.text)
                }
            }
            val label = if (isProcessingEquip) "处理中…" else if (isEquipped) "摘下" else "佩戴"
            LiuliActionPill(label, !isProcessingEquip, !isEquipped, dark, onPrimaryTap)
        }
    }
}

@Composable
private fun LiuliActionPill(label: String, enabled: Boolean, primary: Boolean, dark: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val bg = if (enabled && primary) colors.accent.primary else LiuliMaterials.chipFill(dark)
    val fg = if (enabled && primary) colors.accent.onPrimary else colors.text.secondary
    Box(
        Modifier
            .clip(AppShapes.full)
            .background(bg)
            .then(if (enabled) Modifier.clickableScale(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, style = AppTypography.caption, fontWeight = FontWeight.SemiBold, color = fg) }
}

@Composable
private fun LiuliEmptyInventory(category: PetItemCategory) {
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(68.dp).clip(CircleShape).background(colors.accent.container), contentAlignment = Alignment.Center) {
            Icon(if (category == PetItemCategory.FOOD) Icons.Filled.Restaurant else Icons.Filled.Checkroom, null, tint = colors.accent.text, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.size(2.dp))
        Text(
            if (category == PetItemCategory.FOOD) "还没买过零食呢\n去商店给宠物挑一份吧" else "还没有装扮\n去商店给宠物买一件吧",
            style = AppTypography.secondary,
            color = colors.text.secondary,
            textAlign = TextAlign.Center,
        )
    }
}
