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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Restaurant
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.pet.PetInventoryService
import com.situ.aichat.pet.PetItem
import com.situ.aichat.pet.PetItemCategory
import com.situ.aichat.pet.PetItemKind
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.gift.GiftColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 宠物背包（1:1 iOS `PetInventorySheet` 行为/状态）：零食/装扮分段（带数量）→ 已拥有物品列表 → 使用消耗品
 * （2s 冷却）/ 佩戴-摘下装扮 → snackbar。空态引导去商店。Material 3 原生重写。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetInventoryScreen(
    onClose: () -> Unit,
    onReaction: (String) -> Unit = {}, // pet-ui-2：成功用消耗品/换装后回传字面反应文案给详情页头顶气泡
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

    Scaffold(
        topBar = {
            // 顶栏正下方是恒定不动的分段控件（列表在它之下）→ 内容永不滚到栏下，恒静止（图纸 §11 D-2）。
            AppTopBar(title = "背包", onBack = onClose, actions = { CountPill(foodCount + costumeCount) })
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                            // P1-14：消耗结果触觉（=iOS PetInventorySheet.swift :249 成功 .success /
                                            // :274 catch .error，sensoryFeedback :127-128）；置于 showSnackbar 前（挂起）。
                                            if (ok) {
                                                appHaptics.success()
                                                onReaction(viewModel.consumeReaction(item.name)) // pet-ui-2
                                            } else {
                                                appHaptics.error()
                                            }
                                            snackbarHostState.showSnackbar(consumeMessage(outcome, item))
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
                                            // P1-14：换装结果触觉——佩戴与摘下成功同 .success（=iOS :301/:292）、失败 .error（:310）；
                                            // 换装落定（精灵 overlay）不补强化=iOS 无（账本减项作废维持）。
                                            if (outcome is PetInventoryService.EquipOutcome.Done) {
                                                appHaptics.success()
                                                onReaction(viewModel.equipReaction(item.name, wasEquipped)) // pet-ui-2
                                            } else {
                                                appHaptics.error()
                                            }
                                            snackbarHostState.showSnackbar(equipMessage(outcome, item, wasEquipped))
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

private fun consumeMessage(outcome: PetInventoryService.ConsumeOutcome, item: PetItem): String = when (outcome) {
    is PetInventoryService.ConsumeOutcome.Consumed -> "用了「${item.name}」 · 还剩 ${outcome.remaining} 份"
    PetInventoryService.ConsumeOutcome.ConsumedOut -> "用了「${item.name}」 · 这袋吃完啦"
    else -> "使用失败"
}

private fun equipMessage(outcome: PetInventoryService.EquipOutcome, item: PetItem, wasEquipped: Boolean): String =
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
    val secondary = when (item.kind) {
        PetItemKind.CONSUMABLE -> petBoostParts(item.statBoosts).joinToString(" · ").ifEmpty { item.subtitle }
        PetItemKind.EQUIPPABLE -> item.subtitle
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(46.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(petItemIcon(item), contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (item.isSignature) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(13.dp))
                    }
                }
                Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            // 尾部操作
            if (item.kind == PetItemKind.CONSUMABLE) {
                Text("×$quantity", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                ActionChip(label = if (isInCooldown) "使用中…" else "使用", enabled = !isInCooldown, primary = true, onClick = onPrimaryTap)
            } else {
                if (isEquipped) {
                    EquippedBadge()
                    Spacer(Modifier.size(8.dp))
                }
                val label = if (isProcessingEquip) "处理中…" else if (isEquipped) "摘下" else "佩戴"
                ActionChip(label = label, enabled = !isProcessingEquip, primary = !isEquipped, onClick = onPrimaryTap)
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, enabled: Boolean, primary: Boolean, onClick: () -> Unit) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant // 摘下用灰
    }
    val fg = if (enabled && primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun EquippedBadge() {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            "已佩戴",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun EmptyInventory(category: PetItemCategory) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (category == PetItemCategory.FOOD) Icons.Filled.Restaurant else Icons.Filled.Checkroom,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            if (category == PetItemCategory.FOOD) "还没买过零食呢\n去商店给宠物挑一份吧" else "还没有装扮\n去商店给宠物买一件吧",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CountPill(total: Int) {
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
            Icon(Icons.Filled.Backpack, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
            Text("$total", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}
