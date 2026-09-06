package com.situ.aichat.ui.liuli.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.gift.GiftImage
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes

/**
 * 琉璃礼物卡（图纸 2026-09-05 卷二C §4.4 · A-2 / A-3）：236 纸白卡 = 头（礼物图标块 + 「送你一份礼物 /
 * 收到一份礼物」+ 副「{名} · {金额} 金币」金）+ 体（礼物图 208 · 12 圆角 · sunken 底）+ 右上「手作」金边标。
 *
 * **只换渲染皮**（图纸「钱路声明」）：`GiftCardData` 字段取用、DIY 判据（`GiftCatalog.userDIYIdPrefix`）、
 * 点击动作（仅 DIY 且有 [onDiyClick]）、无障碍句拼法与暖陶 `GiftCardBubble` 逐字同（F9）；`ui/gift` 整目录零改。
 */
@Composable
internal fun LiuliGiftCard(
    data: GiftCardData,
    isFromUser: Boolean,
    diyImage: Bitmap?,
    onDiyClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val isUserDIY = data.giftItemId.startsWith(GiftCatalog.userDIYIdPrefix)
    val fallbackSymbol = GiftCatalog.find(data.giftItemId)?.fallbackSymbol ?: GIFT_FALLBACK_SYMBOL
    val clickEnabled = isUserDIY && onDiyClick != null

    // cd 逐字照抄暖陶 F9（`GiftCardBubble.kt:80-84`）：方向 / 手作 / 心意 / 点击查看四段拼法一字不改。
    val direction = if (isFromUser) "送出礼物" else "收到礼物"
    val handmadeSuffix = if (data.isHandmade) "，手作" else ""
    val cardDescription = "$direction ${data.giftName}$handmadeSuffix，心意 ${data.cost} 金币" +
        if (clickEnabled) "，点击查看" else ""

    // 复核 R1 🟡-2：点击挂在卡壳的圆角裁切之内（ripple 不出圆角）；语义仍在外层压成一个停。
    Box(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = cardDescription
            if (clickEnabled) {
                role = Role.Button
                onClick { onDiyClick.invoke(); true }
            }
        },
    ) {
        LiuliCard(width = LiuliChatGeometry.cardWidth, onClick = if (clickEnabled) onDiyClick else null) {
            LiuliCardHeader(
                icon = Icons.Filled.CardGiftcard,
                title = if (isFromUser) "送你一份礼物" else "收到一份礼物",
                subtitle = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${data.giftName} · ",
                            style = AppTypography.secondary,
                            color = colors.text.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        LiuliGoldAmount("${data.cost} 金币")
                    }
                },
            )
            LiuliCardBody {
                // 图区居中（复核 R1 作者修订·可反悔）：`GiftImage` 只收正方形，208 撑满卡宽会让整卡 330dp 高
                // （装机 02），改回暖陶同档 140 方图、在 236 卡内居中。
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (isUserDIY && diyImage != null) {
                        Image(
                            bitmap = diyImage.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(GIFT_IMAGE)
                                .clip(RoundedCornerShape(GIFT_IMAGE_CORNER))
                                .background(colors.surface.sunken),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        GiftImage(
                            giftItemId = data.giftItemId,
                            fallbackSymbol = fallbackSymbol,
                            size = GIFT_IMAGE,
                            cornerRadius = GIFT_IMAGE_CORNER,
                            showsShadow = false,
                            backgroundColor = colors.surface.sunken,
                        )
                    }
                }
            }
        }
        if (data.isHandmade) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = HANDMADE_INSET, end = HANDMADE_INSET)
                    .clip(LiuliShapes.pill)
                    .background(colors.surface.raised)
                    .border(HANDMADE_BORDER, colors.economy.gold, LiuliShapes.pill)
                    .padding(horizontal = HANDMADE_SIDE, vertical = HANDMADE_VERTICAL),
            ) {
                Text("手作", style = AppTypography.caption, color = colors.economy.gold)
            }
        }
    }
}

/** 终极兜底图标名（照抄暖陶 `GiftCardBubble` 的 `"gift.fill"`）。 */
private const val GIFT_FALLBACK_SYMBOL = "gift.fill"

/** 落值（图区 140 方图居中 = 复核 R1 作者修订，原 §2.1 的 208 撑满卡宽已撤·「手作」标照抄暖陶几何）。 */
private val GIFT_IMAGE = 140.dp
private val GIFT_IMAGE_CORNER = 12.dp
private val HANDMADE_INSET = 8.dp
private val HANDMADE_BORDER = 1.dp
private val HANDMADE_SIDE = 8.dp
private val HANDMADE_VERTICAL = 3.dp
