package com.situ.aichat.ui.pet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.pet.growthStage
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface

/**
 * 宠物列表枢纽（1:1 iOS `PetListView`）：2 列卡片网格，每个角色一张——已领养（迷你动画+名）/ 去领养 /
 * 进度环。从圈子枢纽「宠物」入口进入；点卡片 → [onOpenPet]（详情页按是否有宠物显示详情或领养进度）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetListScreen(
    onOpenPet: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: PetListViewModel = hiltViewModel(),
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = "宠物",
                onBack = onBack,
                lifted = gridState.canScrollBackward,
            )
        },
    ) { padding ->
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("先创建角色，培养关系后就能一起领养宠物啦", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cards, key = { it.characterUuid }) { card -> PetCard(card) { onOpenPet(card.characterUuid) } }
            }
        }
    }
}

@Composable
private fun PetCard(card: PetCardItem, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .appCardSurface()
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (card) {
            is PetCardItem.Adopted -> {
                PetAnimationView(pet = card.pet, size = 80.dp)
                Text(card.pet.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                Text(card.characterName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text("🐾 ${card.pet.growthStage.displayName}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is PetCardItem.CanAdopt -> {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                Text("去领养", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text(card.characterName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            is PetCardItem.Locked -> {
                Box(contentAlignment = Alignment.Center) {
                    // TODO(图纸未覆盖): 这是**确定进度**的 56dp 环（宠物成长百分比），不是不确定态转圈；
                    //  §0.5 明文「不做陶环确定进度（确定进度归条形件）」，而条形件在这里换不了长相 → 停手登记（D-13）。
                    CircularProgressIndicator(
                        progress = { card.progress.overallPercent },
                        modifier = Modifier.size(56.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text("🔒", fontSize = 14.sp)
                }
                Text("${(card.progress.overallPercent * 100).toInt()}%", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(card.characterName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
