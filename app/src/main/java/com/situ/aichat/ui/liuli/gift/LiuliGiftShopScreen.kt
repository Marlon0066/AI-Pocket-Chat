package com.situ.aichat.ui.liuli.gift

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.GiftCategory
import com.situ.aichat.gift.GiftItem
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppMediaRow
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.gift.GiftColors
import com.situ.aichat.ui.gift.GiftDetailSheet
import com.situ.aichat.ui.gift.GiftImage
import com.situ.aichat.ui.gift.GiftShopViewModel
import com.situ.aichat.ui.gift.GiftSymbolMapping
import com.situ.aichat.ui.liuli.designsystem.LiuliChip
import com.situ.aichat.ui.liuli.designsystem.LiuliMaterials
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberGridCollapsed
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 礼物店（琉璃 2.0 脸 · 💰只换外壳，扣币与目录逻辑全走既有 [GiftShopViewModel] & [GiftDetailSheet]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiuliGiftShopScreen(
    onClose: () -> Unit,
    onNavigateToReaction: (String) -> Unit,
    viewModel: GiftShopViewModel = hiltViewModel(),
) {
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val locked by viewModel.lockedCharacter.collectAsStateWithLifecycle()
    val picked by viewModel.pickedCharacter.collectAsStateWithLifecycle()
    val category by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()

    val effective = locked ?: picked
    val items = remember(category) { viewModel.itemsFor(category) }

    var detailItem by remember { mutableStateOf<GiftItem?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    val dark = LocalIsDarkTheme.current
    val gridState = rememberLazyGridState()
    val collapsed = rememberGridCollapsed(gridState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = "礼物店",
        onBack = onClose,
        collapsed = collapsed,
        actions = { LiuliBalancePill(balance, dark) },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(horizontal = LiuliPageGeometry.gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                top = LiuliPageGeometry.navRow,
                bottom = navBottom + LiuliPageGeometry.pageBottom,
            ),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LiuliLargeTitle("礼物店")
            }
            if (!viewModel.isLocked) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LiuliRecipientCard(effective, dark, onClick = { showPicker = true })
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    item {
                        LiuliChip(selected = category == null, onClick = { viewModel.selectCategory(null) }, label = "全部")
                    }
                    items(GiftCategory.entries) { cat ->
                        val icon: ImageVector = GiftSymbolMapping.materialIcon(cat.iconSymbol)
                        LiuliChip(
                            selected = category == cat,
                            onClick = { viewModel.selectCategory(cat) },
                            label = cat.displayName,
                            leading = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            items(items, key = { it.id }) { item ->
                LiuliGiftShopCard(item = item, balance = balance, dark = dark, onClick = { detailItem = item })
            }
        }
    }

    detailItem?.let { item ->
        GiftDetailSheet(
            item = item,
            balance = balance,
            character = effective,
            onDismiss = { detailItem = null },
            onSpend = viewModel::spend,
            onSent = { recordUuid ->
                detailItem = null
                onNavigateToReaction(recordUuid)
            },
        )
    }

    if (showPicker) {
        LiuliGiftRecipientSheet(
            characters = characters,
            selectedUuid = picked?.uuid,
            onPick = {
                viewModel.pickCharacter(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun LiuliBalancePill(balance: Int, dark: Boolean) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .background(LiuliMaterials.chipFill(dark), AppShapes.full)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppActionIcons.Coin, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(15.dp))
        Text("$balance", style = AppTheme.typography.label, fontWeight = FontWeight.SemiBold, color = colors.text.primary)
    }
}

@Composable
private fun LiuliRecipientCard(character: CharacterEntity?, dark: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liuliCardMaterial(LiuliShapes.group, dark)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (character == null) {
            Box(
                modifier = Modifier.size(40.dp).background(colors.accent.container, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.People, contentDescription = null, tint = colors.accent.text)
            }
            Column(Modifier.weight(1f)) {
                Text("选择送礼对象", style = typography.label, color = colors.text.primary)
                Text("先选一个角色再挑礼物", style = typography.caption, color = colors.text.secondary)
            }
        } else {
            CharacterAvatar(name = character.name, avatarPath = character.avatarPath, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text("送给 ${character.name}", style = typography.label, color = colors.text.primary)
                Text("点此更换对象", style = typography.caption, color = colors.text.secondary)
            }
        }
        Icon(AppActionIcons.ChevronRight, contentDescription = null, tint = colors.text.tertiary)
    }
}

@Composable
private fun LiuliGiftShopCard(item: GiftItem, balance: Int, dark: Boolean, onClick: () -> Unit) {
    val canAfford = balance >= item.price
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liuliCardMaterial(LiuliShapes.group, dark)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            GiftImage(item = item, size = 140.dp, cornerRadius = 14.dp, showsShadow = false)
        }
        Text(item.name, style = typography.label, color = colors.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle, style = typography.caption, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppActionIcons.Coin,
                contentDescription = null,
                tint = if (canAfford) GiftColors.Gold else GiftColors.Unafford,
                modifier = Modifier.size(14.dp),
            )
            Text(
                " ${item.price}",
                style = typography.label,
                fontWeight = FontWeight.Bold,
                color = if (canAfford) colors.text.primary else GiftColors.Unafford,
            )
            Text(" 金币", style = typography.caption, color = colors.text.secondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiuliGiftRecipientSheet(
    characters: List<CharacterEntity>,
    selectedUuid: String?,
    onPick: (CharacterEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    LiuliSheetShell(onDismissRequest = onDismiss, title = "选择送礼对象") {
        Column(modifier = Modifier.navigationBarsPadding()) {
            if (characters.isEmpty()) {
                Text(
                    "先去「联系人」创建一个角色，才能送礼。",
                    style = AppTheme.typography.body,
                    color = colors.text.secondary,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    characters.forEach { c ->
                        AppMediaRow(
                            title = c.name,
                            subtitle = c.occupation.takeIf { it.isNotEmpty() },
                            leading = { CharacterAvatar(name = c.name, avatarPath = c.avatarPath, size = 44.dp) },
                            trailing = {
                                if (c.uuid == selectedUuid) {
                                    Icon(AppActionIcons.Check, contentDescription = stringResource(R.string.a11y_gift_selected), tint = colors.accent.text)
                                }
                            },
                            onClick = { onPick(c) },
                        )
                    }
                }
            }
        }
    }
}
