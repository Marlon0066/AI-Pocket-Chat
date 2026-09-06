package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardSurface
import com.situ.aichat.R
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppNavIcons
import com.situ.aichat.ui.designsystem.AppProfileIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

/** 身份卡落值（§3.2「我页身份卡」）：内距 20 · 头像 68 · 顶沿微光 70 · 数字 22/700。 */
private val HERO_PAD = 20.dp
private val HERO_AVATAR = 68.dp
private val HERO_GLOW_HEIGHT = 70.dp
private const val HERO_GLOW_ALPHA = 0.12f
private val STAT_VALUE_SIZE = 22.sp
private val HAIRLINE = 0.5.dp
private val CHEVRON_SMALL = 12.dp
private val GIFT_ICON = 21.dp
private const val HERO_ICON_RATIO = 0.46f // 空态头像里人形线稿占圆的比例（照暖陶 `HeroAvatar`）

/**
 * 「我」页身份卡（图纸 2026-09-06 卷三 A-11 / §3.2 · 契约 §6 D 甲）。
 *
 * 纸白 `liuliCardSurface(medium)` + **顶沿 70dp 微光**（`accent.primary@12% → 透明`·画在卡内、发丝之内）；
 * 暖陶那张的 `grainSurface` 颗粒与陶土染底都不用（A-11）。三列统计的数字 22/700 `accent.text` tnum，
 * 列间 0.5 竖发丝。
 */
@Composable
fun LiuliHeroCard(
    name: String?,
    avatarPath: String?,
    bio: String?,
    charactersCount: Int,
    companionDays: Int?,
    memoriesCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    LiuliHubCard(
        onClick = onClick,
        onClickLabel = stringResource(R.string.profile_edit_label),
        modifier = modifier,
        contentPadding = HERO_PAD,
        // 微光走卡壳的 decor 槽 = 卡面之上、发丝之内；挂在 modifier 上会画到纸面底下并溢出卡外（R1 🔴-2）。
        decor = Modifier.drawBehind {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(colors.accent.primary.copy(alpha = HERO_GLOW_ALPHA), Color.Transparent),
                    startY = 0f,
                    endY = HERO_GLOW_HEIGHT.toPx(),
                ),
                size = Size(size.width, HERO_GLOW_HEIGHT.toPx()),
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiuliHeroAvatar(name = name, avatarPath = avatarPath, size = HERO_AVATAR)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name ?: stringResource(R.string.profile_header_empty),
                    style = AppTypography.titleSmall.copy(fontSize = 20.sp, fontWeight = FontWeight.W700),
                    color = colors.text.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bio != null) {
                    Text(
                        bio, style = AppTypography.secondary, color = colors.text.secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.Top).padding(start = 8.dp)) {
                Text(
                    stringResource(R.string.profile_edit_label),
                    style = AppTypography.secondary,
                    color = colors.accent.text,
                )
                Icon(AppProfileIcons.ChevronRight, null, Modifier.size(CHEVRON_SMALL), colors.accent.text)
            }
        }
        if (charactersCount > 0 && companionDays != null) {
            Box(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp)
                    .height(HAIRLINE).background(colors.surface.stroke),
            )
            Row(Modifier.height(IntrinsicSize.Min)) {
                LiuliCompanionStat(Modifier.weight(1f), companionDays, stringResource(R.string.profile_stat_days_unit), stringResource(R.string.profile_stat_days_label))
                LiuliStatDivider()
                LiuliCompanionStat(Modifier.weight(1f), charactersCount, stringResource(R.string.profile_stat_friends_unit), stringResource(R.string.profile_stat_friends_label))
                LiuliStatDivider()
                LiuliCompanionStat(Modifier.weight(1f), memoriesCount, stringResource(R.string.profile_stat_memories_unit), stringResource(R.string.profile_stat_memories_label))
            }
        }
    }
}

/** 身份卡头像（机制照暖陶 `HeroAvatar`）：有照片 = 照片；空态 = `accent.gradient` 圆底 + 昵称首字，无昵称退人形线稿。
 *  **不走 `CharacterAvatar` 的空名分支**——它对空名画一个「·」（R1 🟡-5）。 */
@Composable
private fun LiuliHeroAvatar(name: String?, avatarPath: String?, size: Dp) {
    val colors = AppTheme.colors
    if (!avatarPath.isNullOrEmpty()) {
        CharacterAvatar(name = name ?: stringResource(R.string.moment_author_me), avatarPath = avatarPath, size = size)
        return
    }
    val gradient = Brush.linearGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd))
    Box(Modifier.size(size).clip(CircleShape).background(gradient), contentAlignment = Alignment.Center) {
        val monogram = name?.trim()?.firstOrNull()?.toString()
        if (monogram != null) {
            Text(monogram, style = AppTypography.titleSmall.copy(fontSize = 20.sp, fontWeight = FontWeight.W700), color = colors.text.onAccent)
        } else {
            Icon(AppNavIcons.Profile, contentDescription = null, tint = colors.text.onAccent, modifier = Modifier.size(size * HERO_ICON_RATIO))
        }
    }
}

/** 一列陪伴统计：数字 22/700 `accent.text` tnum + 单位 12/500 + 标签 12。 */
@Composable
private fun LiuliCompanionStat(modifier: Modifier, value: Int, unit: String, label: String) {
    val colors = AppTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$value",
                style = AppTypography.titleSmall.copy(fontSize = STAT_VALUE_SIZE, fontWeight = FontWeight.W700, fontFeatureSettings = "tnum"),
                color = colors.accent.text,
            )
            Text(
                unit,
                style = AppTypography.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.W500),
                color = colors.text.secondary,
                modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
            )
        }
        Text(
            label,
            style = AppTypography.caption.copy(fontSize = 12.sp),
            color = colors.text.secondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** 三列之间的 0.5 竖发丝（上下各留 6）。 */
@Composable
private fun LiuliStatDivider() {
    Box(Modifier.padding(vertical = 6.dp).fillMaxHeight().width(HAIRLINE).background(AppTheme.colors.surface.stroke))
}

/**
 * 资产格（§4.6）：图标块 + 标题 + 大数字 22/700 tnum + 单位 + 提示。
 * 0 值传 [emptyText] 时数字行换一句温和引导（照暖陶 `StatTile` 逐字·拍板 ④）。
 */
@Composable
fun LiuliStatTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tileTint: Color,
    tileInk: Color,
    title: String,
    value: Int,
    unit: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String? = null,
    valueColor: Color = AppTheme.colors.accent.text,
) {
    val colors = AppTheme.colors
    LiuliHubCard(onClick = onClick, onClickLabel = title, modifier = modifier) {
        LiuliIconTile(icon, tileTint, tileInk)
        Spacer(Modifier.height(8.dp))
        Text(title, style = AppTypography.label, color = colors.text.primary)
        if (emptyText != null) {
            Text(emptyText, style = AppTypography.secondary, color = colors.accent.text, modifier = Modifier.padding(vertical = 3.dp))
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value.toString(),
                    style = AppTypography.titleSmall.copy(fontSize = STAT_VALUE_SIZE, fontWeight = FontWeight.W700, fontFeatureSettings = "tnum"),
                    color = valueColor,
                )
                Text(unit, style = AppTypography.caption, color = colors.text.secondary, modifier = Modifier.padding(start = 3.dp, bottom = 3.dp))
            }
        }
        Text(hint, style = AppTypography.caption, color = colors.text.secondary)
    }
}

/** 礼物一条：左店右盒各自可点，中缝 0.5 竖发丝（上下 12）。 */
@Composable
fun LiuliGiftRow(shopSub: String, giftBoxSub: String, onOpenShop: () -> Unit, onOpenBox: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .liuliCardSurface(LiuliShapes.medium)
            .height(IntrinsicSize.Min),
    ) {
        LiuliGiftHalf(Modifier.weight(1f), AppProfileIcons.Shop, stringResource(R.string.profile_box_shop_title), shopSub, onOpenShop)
        Box(
            Modifier.padding(vertical = 12.dp).fillMaxHeight().width(HAIRLINE)
                .background(AppTheme.colors.surface.stroke),
        )
        LiuliGiftHalf(Modifier.weight(1f), AppProfileIcons.GiftBox, stringResource(R.string.profile_box_giftbox_title), giftBoxSub, onOpenBox)
    }
}

@Composable
private fun LiuliGiftHalf(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    LiuliHubRow(onClick = onClick, onClickLabel = title, modifier = modifier, surface = false) {
        Icon(icon, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(GIFT_ICON))
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.label, color = colors.text.primary)
            Text(sub, style = AppTypography.captionNumeric, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(AppProfileIcons.ChevronRight, null, Modifier.size(CHEVRON_SMALL), colors.text.tertiary)
    }
}

/** 货币关闭时「我的动态」的全宽卡（照暖陶 `MomentsWideCard` 逐字换皮）。 */
@Composable
fun LiuliMomentsWideCard(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val title = stringResource(R.string.moment_user_moments_title)
    val detail = if (count == 0) {
        stringResource(R.string.profile_box_moments_empty)
    } else {
        "$count ${stringResource(R.string.profile_box_moments_unit)} · ${stringResource(R.string.profile_box_moments_hint)}"
    }
    LiuliHubRow(onClick = onClick, onClickLabel = title, modifier = modifier) {
        LiuliIconTile(AppProfileIcons.Moments, colors.accent.container, colors.accent.onContainer)
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.label, color = colors.text.primary)
            Text(detail, style = AppTypography.captionNumeric, color = colors.text.secondary)
        }
        Icon(AppProfileIcons.ChevronRight, null, Modifier.size(CHEVRON_SMALL), colors.text.tertiary)
    }
}

/** 设置条（§4.6）：齿轮图标块走 `surface.sunken` + `text.secondary`。 */
@Composable
fun LiuliSettingsEntryBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    LiuliHubRow(onClick = onClick, onClickLabel = stringResource(R.string.settings_screen_title), modifier = modifier) {
        LiuliIconTile(AppProfileIcons.Tune, colors.surface.sunken, colors.text.secondary)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_screen_title), style = AppTypography.label, color = colors.text.primary)
            Text(
                stringResource(R.string.profile_settings_entry_preview),
                style = AppTypography.caption, color = colors.text.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(AppProfileIcons.ChevronRight, null, Modifier.size(CHEVRON_SMALL), colors.text.tertiary)
    }
}

