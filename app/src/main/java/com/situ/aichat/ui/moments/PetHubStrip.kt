package com.situ.aichat.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.EmotionTileAlpha
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.pet.PetAnimationView
import com.situ.aichat.ui.pet.StatusDot
import com.situ.aichat.ui.pet.petStatusColor
import com.situ.aichat.ui.pet.petStatusKind
import com.situ.aichat.ui.pet.petStatusRes

/**
 * 动态页「宠物」全宽条（图纸 2026-09-06-宠物总览页复活 §4.1·**D-1 位置 = 日记/故事两卡之下**）。
 *
 * 外衣逐字照 [OurDaysStrip][com.situ.aichat.ui.ourdays.OurDaysStrip] / `CircleStrip`
 * （appCardSurface + clickableScale + h16 v12），是本页第三条 strip，非新部件。
 *
 * 三行：头行「图标 + 宠物 + 总数 + ›」／精灵排（≤5·无宠不出）／尾行状态。
 * **D-2 分工**：头行报「门后有几只」，尾行报「谁需要照顾」——世界卡信息条只在宠物出状况时顶一句，
 * 二者读同一 `petRepo.observeAll()` 流、同一套 [petStatusKind] 映射，永不自相矛盾（图纸 §3.3）。
 *
 * 本条是 `momentsPet` 路由自 W11 撤卡后的**唯一入口**（图纸 §0）。
 */
@Composable
internal fun PetHubStrip(state: MomentsHubState, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val title = stringResource(R.string.moment_hub_pet)
    val subText = if (state.petCount > 0) {
        stringResource(R.string.pet_hub_strip_count, state.petCount)
    } else {
        stringResource(R.string.pet_hub_strip_none)
    }
    val tail = petStripTailText(state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appCardSurface()
            .clickableScale(onClickLabel = title) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title，$subText，$tail" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PetStripTile()
            Spacer(Modifier.width(10.dp))
            Text(title, style = AppTypography.listName, color = colors.text.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                subText,
                style = AppTypography.secondary.copy(fontSize = 12.sp),
                color = colors.text.secondary,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.text.tertiary,
            )
        }

        if (state.petSprites.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy((-6).dp),
            ) {
                state.petSprites.forEach { pet ->
                    Box(Modifier.border(2.dp, colors.surface.raised, CircleShape)) {
                        PetAnimationView(pet = pet, size = 26.dp)
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
                StatusDot(petStatusColor(petStatusKind(neediest)))
            }
            Text(
                tail,
                style = AppTypography.secondary.copy(fontSize = 12.5.sp),
                color = colors.text.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 尾行文案：空态邀约 / 都好着呢 / 「{宠名} {状态}」。 */
@Composable
private fun petStripTailText(state: MomentsHubState): String {
    val neediest = state.petGlance
    if (state.petCount == 0 || neediest == null) return stringResource(R.string.moment_hub_pet_desc)
    if (state.petAllWell) return stringResource(R.string.pet_hub_strip_all_well)
    val kind = petStatusKind(neediest)
    // 「需要你」四态必有资源键；CONTENT/HAPPY 走不到这里（那两态 allWell 为真）。
    val statusText = petStatusRes(kind)?.let { stringResource(it) } ?: return stringResource(R.string.pet_hub_strip_all_well)
    return "${neediest.name} $statusText"
}

/** 头行 28dp 暖玫图标块（总览页卡用 40dp 版·同族配色）。 */
@Composable
private fun PetStripTile() {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.emotion.shy.copy(alpha = EmotionTileAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppFeatureIcons.Pet,
            contentDescription = null,
            tint = colors.emotion.shyInk,
            modifier = Modifier.size(17.dp),
        )
    }
}
