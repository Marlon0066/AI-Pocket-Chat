package com.situ.aichat.ui.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.EmotionTileAlpha
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.grainSurface

/**
 * 宠物总览枢纽：2 列卡片网格，每个角色一张——已领养（80dp 精灵 + 名 + 状态行）/ 可领养 / 陪伴进度。
 * 入口 = 动态页「宠物」条（图纸 2026-09-06-宠物总览页复活 D-1）；点卡片 → [onOpenPet]
 * （详情页按是否有宠物显示详情或领养进度）。
 *
 * **本屏无数量上限**（[PetListViewModel] 取全量角色 × 全量宠物）——家内站位只摆前 3 只，
 * 第 4 只起靠这一屏才够得着（图纸 §0.2）。
 *
 * 外衣 = 自研设计语言（脱 M3）：卡走 [appCardSurface]，色走 [AppTheme]，字号走 [AppTypography]，
 * 确定进度走条形件 [AppProgressBar]（M3 契约 §0.5「不做陶环确定进度」），状态走圆点 + 纯文字（无 emoji·
 * 圈子枢纽契约 §2.2）。间距三值 gutter 20 / 卡间 12 / 卡内 16 为间距军规锁值。
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
                title = stringResource(R.string.pet_list_title),
                onBack = onBack,
                lifted = gridState.canScrollBackward,
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(AppTheme.colors.surface.base)
                .grainSurface(),
        ) {
            if (cards.isEmpty()) {
                Text(
                    stringResource(R.string.pet_list_empty),
                    style = AppTypography.secondary,
                    color = AppTheme.colors.text.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = AppSpacing.screenGutter),
                )
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    // 屏 gutter 恒 20（设计语言 §2.5 军规）
                    contentPadding = PaddingValues(horizontal = AppSpacing.screenGutter, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(cards, key = { it.characterUuid }) { card ->
                        PetCard(card) { onOpenPet(card.characterUuid) }
                    }
                }
            }
        }
    }
}

/** 三态等高卡（图纸 §4.2·min 150dp）。 */
@Composable
private fun PetCard(card: PetCardItem, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .appCardSurface()
            .clickableScale(onClickLabel = card.characterName) { onClick() }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        when (card) {
            is PetCardItem.Adopted -> {
                PetAnimationView(pet = card.pet, size = 80.dp)
                Text(
                    card.pet.name,
                    style = AppTypography.label,
                    color = colors.text.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.pet_list_with_char, card.characterName),
                    style = AppTypography.caption,
                    color = colors.text.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PetStatusRow(card)
            }
            is PetCardItem.CanAdopt -> {
                PetIconTile(accented = true)
                Text(
                    stringResource(R.string.pet_list_adopt_cta),
                    style = AppTypography.label,
                    color = colors.accent.text,
                )
                Text(
                    card.characterName,
                    style = AppTypography.caption,
                    color = colors.text.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            is PetCardItem.Locked -> {
                PetIconTile(accented = false)
                Text(
                    card.characterName,
                    style = AppTypography.caption,
                    color = colors.text.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 确定进度 = 条形件（M3 契约 §0.5：确定进度归条形件·不做陶环确定进度）
                AppProgressBar(card.progress.overallPercent, Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.pet_list_progress, (card.progress.overallPercent * 100).toInt()),
                    style = AppTypography.captionNumeric,
                    color = colors.text.secondary,
                )
            }
        }
    }
}

/** 已领养卡的状态行：7dp 状态色圆点 + 纯文字（无 emoji）。CONTENT 态拼「{物种} · {成长期}」。 */
@Composable
private fun PetStatusRow(card: PetCardItem.Adopted) {
    val kind = petStatusKind(card.pet)
    val text = petStatusRes(kind)?.let { stringResource(it) }
        ?: stringResource(
            R.string.pet_list_content_status,
            card.pet.species.displayName,
            card.pet.growthStage.displayName,
        )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        StatusDot(petStatusColor(kind))
        Text(
            text,
            style = AppTypography.caption,
            color = AppTheme.colors.text.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 7dp 状态圆点（图纸 §4.3）。 */
@Composable
internal fun StatusDot(color: Color) {
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
}

/** 40dp 宠物图标块：可领养 = 暖玫家族色底 + 深档图标；未解锁 = sunken 底 + 三级字色图标。 */
@Composable
private fun PetIconTile(accented: Boolean) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (accented) colors.emotion.shy.copy(alpha = EmotionTileAlpha) else colors.surface.sunken,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppFeatureIcons.Pet,
            contentDescription = null,
            tint = if (accented) colors.emotion.shyInk else colors.text.tertiary,
            modifier = Modifier.size(22.dp),
        )
    }
}
