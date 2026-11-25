package com.situ.aichat.ui.gift

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppChoiceChip
import com.situ.aichat.ui.designsystem.AppMediaRow
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.GiftCategory
import com.situ.aichat.gift.GiftItem
import com.situ.aichat.ui.components.CharacterAvatar

/**
 * 礼物店（9.2d d-2，1:1 iOS `GiftShopView`）：选对象 → 选礼物 → 扣币 → 反应页。
 *
 * 单个 [LazyVerticalGrid]（2 列）承载内容：选对象提示/行 + 分类 Tab 作全宽 header item，46 件礼物卡 2 列网格
 * （避免嵌套滚动）。点卡 → 详情底片（送出 → spendAndCreateRecord → [onNavigateToReaction]）。
 *
 * @param onNavigateToReaction 送出成功后跳反应页（带 recordUuid），生成反应。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftShopScreen(
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

    val gridState = rememberLazyGridState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "礼物店",
                onBack = onClose,
                lifted = gridState.canScrollBackward,
                actions = { BalancePill(balance) },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenGutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        ) {
            if (!viewModel.isLocked) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (effective == null) {
                        CharacterSelectPrompt(onClick = { showPicker = true })
                    } else {
                        SelectedCharacterRow(character = effective, onClick = { showPicker = true })
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                CategoryTabs(selected = category, onSelect = viewModel::selectCategory)
            }
            items(items, key = { it.id }) { item ->
                GiftCard(item = item, balance = balance, onClick = { detailItem = item })
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
        GiftCharacterPickerSheet(
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

/** 余额胶囊（toolbar 右上，1:1 iOS balancePill）。 */
@Composable
private fun BalancePill(balance: Int) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .padding(end = 8.dp)
            .background(colors.surface.sunken, AppShapes.full)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppActionIcons.Coin, contentDescription = null, tint = GiftColors.Gold, modifier = Modifier.size(16.dp))
        Text(
            text = "$balance",
            style = AppTheme.typography.label,
            fontWeight = FontWeight.SemiBold,
            color = colors.text.primary,
        )
    }
}

/** 未选角色引导卡（1:1 iOS characterSelectPrompt·appCardSurface 承托·圆角 18→16 并轨）。 */
@Composable
private fun CharacterSelectPrompt(onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .appCardSurface()
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(colors.accent.container, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.People, contentDescription = null, tint = colors.accent.text)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("选择送礼对象", style = AppTheme.typography.label, color = colors.text.primary)
            Text("先选一个角色再挑礼物", style = AppTheme.typography.caption, color = colors.text.secondary)
        }
    }
}

/** 已选角色行（1:1 iOS selectedCharacterRow，点击换对象·appCardSurface 承托）。 */
@Composable
private fun SelectedCharacterRow(character: CharacterEntity, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .appCardSurface()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(name = character.name, avatarPath = character.avatarPath, size = 36.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text("送给 ${character.name}", style = AppTheme.typography.label, color = colors.text.primary)
            Text("点此更换对象", style = AppTheme.typography.caption, color = colors.text.secondary)
        }
    }
}

/** 分类 Tab（横滚 Chip：「全部」+ 7 品类，1:1 iOS categoryTabs）。 */
@Composable
private fun CategoryTabs(selected: GiftCategory?, onSelect: (GiftCategory?) -> Unit) {
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

/** 角色选择底片（1:1 iOS GiftCharacterPickerSheet，creationDate DESC）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GiftCharacterPickerSheet(
    characters: List<CharacterEntity>,
    selectedUuid: String?,
    onPick: (CharacterEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    AppSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                "选择送礼对象",
                style = AppTheme.typography.titleSmall,
                color = colors.text.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
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
