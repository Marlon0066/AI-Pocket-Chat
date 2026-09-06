package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.EmotionTileAlpha
import com.situ.aichat.ui.moments.MomentsHubState
import com.situ.aichat.ui.pet.PetAnimationView
import com.situ.aichat.ui.pet.petStatusColor
import com.situ.aichat.ui.pet.petStatusKind
import com.situ.aichat.ui.pet.petStatusRes

/** 精灵排与状态点落值（照暖陶 `PetHubStrip` 逐字）。 */
private val SPRITE = 26.dp
private val SPRITE_OVERLAP = (-6).dp
private val SPRITE_RING = 2.dp
private val STATUS_DOT = 7.dp

/**
 * 琉璃「宠物」全宽条（图纸 2026-09-06 卷三 §4.5）。
 *
 * 三行结构与尾句三态**逐字照暖陶** `PetHubStrip`（同一份 [petStatusKind] 判据——头行报「门后有几只」、
 * 尾行报「谁需要照顾」，与世界卡信息条永不自相矛盾），只换皮：纸白卡 + 琉璃图标块。
 */
@Composable
fun LiuliPetHubStrip(state: MomentsHubState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val title = stringResource(R.string.moment_hub_pet)
    val subText = if (state.petCount > 0) {
        stringResource(R.string.pet_hub_strip_count, state.petCount)
    } else {
        stringResource(R.string.pet_hub_strip_none)
    }
    val tail = liuliPetStripTailText(state)
    LiuliHubCard(
        onClick = onClick,
        onClickLabel = title,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$title，$subText，$tail" },
    ) {
        LiuliStripHeader(
            title = title,
            subText = subText,
            leading = {
                // 底色 = 暖陶同族淡档 `shy@EmotionTileAlpha`（A-12「宠物 emotion.shy 族沿暖陶 PetStripTile」·R1 🟡-4 补回）。
                LiuliIconTile(AppFeatureIcons.Pet, colors.emotion.shy.copy(alpha = EmotionTileAlpha), colors.emotion.shyInk)
            },
        )
        if (state.petSprites.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(SPRITE_OVERLAP),
            ) {
                state.petSprites.forEach { pet ->
                    Box(Modifier.border(SPRITE_RING, colors.surface.raised, CircleShape)) {
                        PetAnimationView(pet = pet, size = SPRITE)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 空态无圆点（只一句邀约）；有宠物才上状态色点。
            val neediest = state.petGlance
            if (state.petCount > 0 && neediest != null) {
                Box(Modifier.size(STATUS_DOT).clip(CircleShape).background(petStatusColor(petStatusKind(neediest))))
            }
            Text(
                tail,
                style = AppTypography.snackbarBody,
                color = colors.text.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 尾行文案：空态邀约 / 都好着呢 / 「{宠名} {状态}」（照暖陶 `petStripTailText` 逐字）。 */
@Composable
internal fun liuliPetStripTailText(state: MomentsHubState): String {
    val neediest = state.petGlance
    if (state.petCount == 0 || neediest == null) return stringResource(R.string.moment_hub_pet_desc)
    if (state.petAllWell) return stringResource(R.string.pet_hub_strip_all_well)
    val kind = petStatusKind(neediest)
    // 「需要你」四态必有资源键；CONTENT/HAPPY 走不到这里（那两态 allWell 为真）。
    val statusText = petStatusRes(kind)?.let { stringResource(it) } ?: return stringResource(R.string.pet_hub_strip_all_well)
    return "${neediest.name} $statusText"
}
