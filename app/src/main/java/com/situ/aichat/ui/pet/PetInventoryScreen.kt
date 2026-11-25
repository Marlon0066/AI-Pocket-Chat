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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Restaurant
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
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.grainSurface
import com.situ.aichat.ui.gift.GiftColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 宠物背包（暖陶外衣）：零食/装扮分段（带数量）→ 已拥有物品列表 → 使用消耗品（2s 冷却）/ 佩戴-摘下装扮 → snackbar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetInventoryScreen(
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
    val colors = AppTheme.colors

    Scaffold(
        containerColor = colors.surface.base,
        topBar = {
            AppTopBar(title = "背包", onBack = onClose, actions = { CountPill(foodCount + costumeCount) })
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
                label = { cat ->
                    val count = if (cat == PetItemCategory.FOOD) foodCount else costumeCount
                    if (count > 0) "${cat.displayName} ×$count" else cat.displayName
                },
            )

            if (items.isEmpty()) {
                EmptyInventory(category)
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = AppSpacing.screenGutter,
                        end = AppSpacing.screenGutter,
                        top = 4.dp,
                        bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        PetInventoryItemRow(
                            item = item,
                            quantity = viewModel.quantityOf(pet, item.id),
                            isEquipped = viewModel.isEquipped(pet, item.id),
                            isInCooldown = item.id in cooldownIds,
                            isProcessingEquip = processingEquipId == item.id,
                            onPrimaryTap = {
                                when (item.kind) {
                                    PetItemKind.CONSUMABLE -> {
                                        if (item.id in cooldownIds) return@PetInventoryItemRow
                                        cooldownIds = cooldownIds + item.id
                                        scope.launch {
                                            val outcome = viewModel.useConsumable(item)
                                            val ok = outcome is PetInventoryService.ConsumeOutcome.Consumed ||
                                                outcome is PetInventoryService.ConsumeOutcome.ConsumedOut
                                            if (ok) {
                                                appHaptics.success()
                                                onReaction(viewModel.consumeReaction(item.name))
                                            } else {
                                                appHaptics.error()
                                            }
                                            snackbarHostState.showSnackbar(petInventoryConsumeMessage(outcome, item))
                                            if (ok) delay(2000)
                                            cooldownIds = cooldownIds - item.id
                                        }
                                    }
                                    PetItemKind.EQUIPPABLE -> {
                                        if (processingEquipId != null) return@PetInventoryItemRow
                                        val wasEquipped = viewModel.isEquipped(pet, item.id)
                                        processingEquipId = item.id
                                        scope.launch {
                                            val outcome = viewModel.toggleEquip(item)
                                            processingEquipId = null
                                            if (outcome is PetInventoryService.EquipOutcome.Done) {
                                                appHaptics.success()
                                                onReaction(viewModel.equipReaction(item.name, wasEquipped))
                                            } else {
                                                appHaptics.error()
                                            }
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
    }
}

internal fun petInventoryConsumeMessage(outcome: PetInventoryService.ConsumeOutcome, item: PetItem): String = when (outcome) {
    is PetInventoryService.ConsumeOutcome.Consumed -> "用了「${item.name}」 · 还剩 ${outcome.remaining} 份"
    PetInventoryService.ConsumeOutcome.ConsumedOut -> "用了「${item.name}」 · 这袋吃完啦"
    else -> "使用失败"
}

internal fun petInventoryEquipMessage(outcome: PetInventoryService.EquipOutcome, item: PetItem, wasEquipped: Boolean): String =
    when {
        outcome is PetInventoryService.EquipOutcome.Done && wasEquipped -> "摘下了「${item.name}」"
        outcome is PetInventoryService.EquipOutcome.Done -> "戴上了「${item.name}」"
        else -> "操作失败"
    }

@Composable
private fun PetInventoryItemRow(
    item: PetItem,
    quantity: Int,
    isEquipped: Boolean,
    isInCooldown: Boolean,
    isProcessingEquip: Boolean,
    onPrimaryTap: () -> Unit,
) {
    val colors = AppTheme.colors
    val secondary = when (item.kind) {
        PetItemKind.CONSUMABLE -> petBoostParts(item.statBoosts).joinToString(" · ").ifEmpty { item.subtitle }
        PetItemKind.EQUIPPABLE -> item.subtitle
    }
    Row(
        Modifier
            .fillMaxWidth()
            .appCardSurface()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(colors.accent.container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(petItemIcon(item), contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, style = AppTypography.label, fontWeight = FontWeight.SemiBold, color = colors.text.primary, maxLines = 1)
                if (item.isSignature) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(13.dp))
                }
            }
            Text(secondary, style = AppTypography.caption, color = colors.text.secondary, maxLines = 1)
        }
        if (item.kind == PetItemKind.CONSUMABLE) {
            Text("×$quantity", style = AppTypography.amount, color = colors.text.secondary)
            ActionChip(label = if (isInCooldown) "使用中…" else "使用", enabled = !isInCooldown, primary = true, onClick = onPrimaryTap)
        } else {
            if (isEquipped) EquippedBadge()
            val label = if (isProcessingEquip) "处理中…" else if (isEquipped) "摘下" else "佩戴"
            ActionChip(label = label, enabled = !isProcessingEquip, primary = !isEquipped, onClick = onPrimaryTap)
        }
    }
}

@Composable
private fun ActionChip(label: String, enabled: Boolean, primary: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val bg = when {
        !enabled -> colors.surface.sunken
        primary -> colors.accent.primary
        else -> colors.surface.sunken
    }
    val fg = if (enabled && primary) colors.accent.onPrimary else colors.text.secondary
    Box(
        modifier = Modifier
            .clip(AppShapes.full)
            .background(bg)
            .then(if (enabled) Modifier.clickableScale(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, style = AppTypography.caption, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
private fun EquippedBadge() {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .clip(AppShapes.full)
            .background(colors.accent.container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text("已佩戴", style = AppTypography.caption, fontWeight = FontWeight.SemiBold, color = colors.accent.text)
    }
}

@Composable
private fun EmptyInventory(category: PetItemCategory) {
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(colors.accent.container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (category == PetItemCategory.FOOD) Icons.Filled.Restaurant else Icons.Filled.Checkroom,
                contentDescription = null,
                tint = colors.accent.text,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.size(14.dp))
        Text(
            if (category == PetItemCategory.FOOD) "还没买过零食呢\n去商店给宠物挑一份吧" else "还没有装扮\n去商店给宠物买一件吧",
            style = AppTypography.secondary,
            color = colors.text.secondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CountPill(total: Int) {
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
        Icon(Icons.Filled.Backpack, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(15.dp))
        Text("$total", style = AppTypography.amount, color = colors.text.primary)
    }
}
