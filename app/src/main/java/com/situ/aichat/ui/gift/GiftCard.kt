package com.situ.aichat.ui.gift

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import com.situ.aichat.ui.designsystem.AppActionIcons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.gift.GiftItem
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface

/**
 * 通用礼物卡（9.2d d-1，1:1 iOS `GiftShopView.giftCard` / `InChatGiftSheetView.giftCard`，礼物店与聊天送礼 sheet 共用）。
 *
 * 布局：140 图（圆角 14、无阴影）+ 名（titleSmall 半粗 1 行）+ 副标题（bodySmall 次要 1 行）+ 价格行
 * （$圈icon + 价格 bold + "金币"）。可负担金 [GiftColors.Gold] / 余额不足暗红 [GiftColors.Unafford]。
 *
 * 卡背景用 Material 3 实色 `surfaceContainer`（**禁毛玻璃**——46 张卡叠 backdrop 会卡死，iOS 注释明确），地道写法
 * 非像素仿 iOS 的 `cardBackground()`。
 *
 * @param balance 当前用户余额，用于判定可负担/不足配色（钱相关语义色 1:1）。
 */
@Composable
fun GiftCard(
    item: GiftItem,
    balance: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val canAfford = balance >= item.price
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    Column(
        modifier = modifier
            .appCardSurface()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            GiftImage(item = item, size = 140.dp, cornerRadius = 14.dp, showsShadow = false)
        }
        Text(
            text = item.name,
            style = typography.label,
            color = colors.text.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.subtitle,
            style = typography.caption,
            color = colors.text.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppActionIcons.Coin,
                contentDescription = null,
                tint = if (canAfford) GiftColors.Gold else GiftColors.Unafford,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = " ${item.price}",
                style = typography.label,
                fontWeight = FontWeight.Bold,
                color = if (canAfford) colors.text.primary else GiftColors.Unafford,
            )
            Text(
                text = " 金币",
                style = typography.caption,
                color = colors.text.secondary,
            )
        }
    }
}
