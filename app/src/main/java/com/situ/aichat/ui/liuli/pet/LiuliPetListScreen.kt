package com.situ.aichat.ui.liuli.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.pet.growthStage
import com.situ.aichat.pet.species
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppProgressBar
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.EmotionTileAlpha
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberGridCollapsed
import com.situ.aichat.ui.pet.PetAnimationView
import com.situ.aichat.ui.pet.PetCardItem
import com.situ.aichat.ui.pet.PetListViewModel
import com.situ.aichat.ui.pet.StatusDot
import com.situ.aichat.ui.pet.petStatusColor
import com.situ.aichat.ui.pet.petStatusKind
import com.situ.aichat.ui.pet.petStatusRes
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 宠物总览页（琉璃 2.0 脸）。 */
@Composable
fun LiuliPetListScreen(
    onOpenPet: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: PetListViewModel = hiltViewModel(),
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val dark = LocalIsDarkTheme.current
    val gridState = rememberLazyGridState()
    val collapsed = rememberGridCollapsed(gridState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val title = stringResource(R.string.pet_list_title)

    LiuliPage(title = title, onBack = onBack, collapsed = collapsed) {
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(horizontal = LiuliPageGeometry.gutter)) {
                Column(Modifier.padding(top = LiuliPageGeometry.navRow + 4.dp)) {
                    LiuliLargeTitle(title)
                }
                Text(
                    stringResource(R.string.pet_list_empty),
                    style = AppTypography.secondary,
                    color = AppTheme.colors.text.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        } else {
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
                item(span = { GridItemSpan(2) }, key = "large_title") {
                    LiuliLargeTitle(title, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(cards, key = { it.characterUuid }) { card ->
                    LiuliPetCard(card, dark) { onOpenPet(card.characterUuid) }
                }
            }
        }
    }
}

@Composable
private fun LiuliPetCard(card: PetCardItem, dark: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 152.dp)
            .liuliCardMaterial(LiuliShapes.group, dark)
            .clickableScale(onClickLabel = card.characterName, onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        when (card) {
            is PetCardItem.Adopted -> {
                PetAnimationView(pet = card.pet, size = 80.dp)
                Text(card.pet.name, style = AppTypography.label, color = colors.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.pet_list_with_char, card.characterName), style = AppTypography.caption, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val kind = petStatusKind(card.pet)
                val statusText = petStatusRes(kind)?.let { stringResource(it) }
                    ?: stringResource(R.string.pet_list_content_status, card.pet.species.displayName, card.pet.growthStage.displayName)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    StatusDot(petStatusColor(kind))
                    Text(statusText, style = AppTypography.caption, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            is PetCardItem.CanAdopt -> {
                LiuliPetIconTile(accented = true)
                Text(stringResource(R.string.pet_list_adopt_cta), style = AppTypography.label, color = colors.accent.text)
                Text(card.characterName, style = AppTypography.caption, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            is PetCardItem.Locked -> {
                LiuliPetIconTile(accented = false)
                Text(card.characterName, style = AppTypography.caption, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AppProgressBar(card.progress.overallPercent, Modifier.fillMaxWidth())
                Text(stringResource(R.string.pet_list_progress, (card.progress.overallPercent * 100).toInt()), style = AppTypography.captionNumeric, color = colors.text.secondary)
            }
        }
    }
}

@Composable
private fun LiuliPetIconTile(accented: Boolean) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (accented) colors.emotion.shy.copy(alpha = EmotionTileAlpha) else colors.surface.sunken),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppFeatureIcons.Pet, contentDescription = null, tint = if (accented) colors.emotion.shyInk else colors.text.tertiary, modifier = Modifier.size(22.dp))
    }
}
