package com.situ.aichat.ui.gift

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import com.situ.aichat.ui.designsystem.AppActionIcons
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
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 聊天内送礼卡片气泡（9.2d d-3 行为 1:1 iOS `GiftCardBubbleView`，用户送 / 角色主动送共用）。
 *
 * Fable-5 换装（契约 §3.2·D11）：糖果色双渐变 → **raised 纸壳 + sunken 图区 + economy.gold 贵金属点缀**
 * （金币图标/分割线/金额）；「手作」红 pill → 白底金描边；方向区分只靠左右对齐 + 头像 + a11y 文案，
 * 不再用第二套视觉编码。140 图 + 名（2 行）+ 金分割线 + 价格行；卡宽 168dp（140 图 + 14×2 卡内 padding）。
 *
 * **纯展示组件**：DIY 图片与点击行为由调用方注入（聊天渲染 d-4 提供 [diyImage]/[onDiyClick]；DIY 创建预览不传，
 * 显示兜底图标）。仅用户 DIY（diy_user_ 前缀）且有 [onDiyClick] 时可点开详情；预置礼物不响应。
 *
 * @param isFromUser 仅参与 a11y 方向文案（送出/收到）；视觉同壳（D11）。
 * @param diyImage 用户 DIY 上传图（按 record 懒加载；**永不进 LLM**，仅本地气泡渲染）。
 */
@Composable
fun GiftCardBubble(
    data: GiftCardData,
    isFromUser: Boolean,
    modifier: Modifier = Modifier,
    diyImage: Bitmap? = null,
    onDiyClick: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val isUserDIY = data.giftItemId.startsWith(GiftCatalog.userDIYIdPrefix)
    val fallbackSymbol = GiftCatalog.find(data.giftItemId)?.fallbackSymbol ?: "gift.fill"
    val shape = AppShapes.medium
    val clickEnabled = isUserDIY && onDiyClick != null

    // 无障碍（14.7e）：卡内图标/图片装饰 + 名字/心意/金币散成多个节点、方向(送出/收到)与手作只靠视觉。
    // clearAndSetSemantics 压成一个停、1:1 iOS accessibilityLabel；可点 DIY 卡补 Button role + onClick（替 iOS isButton+hint）。
    val direction = if (isFromUser) "送出礼物" else "收到礼物"
    val handmadeSuffix = if (data.isHandmade) "，手作" else ""
    val cardDescription = "$direction ${data.giftName}$handmadeSuffix，心意 ${data.cost} 金币" +
        if (clickEnabled) "，点击查看" else ""

    Box(
        modifier = modifier
            .width(168.dp)
            .clip(shape)
            .background(colors.surface.raised)
            .border(1.dp, colors.surface.stroke, shape)
            .then(if (clickEnabled) Modifier.clickable { onDiyClick.invoke() } else Modifier)
            .clearAndSetSemantics {
                contentDescription = cardDescription
                if (clickEnabled) {
                    role = Role.Button
                    onClick { onDiyClick.invoke(); true }
                }
            },
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 礼物图（sunken 图区·卡内图区 12dp）：DIY 有上传图 → 直接渲染本地图；否则通用 GiftImage（DIY 无图走兜底图标）
            if (isUserDIY && diyImage != null) {
                Image(
                    bitmap = diyImage.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(140.dp).clip(AppShapes.small).background(colors.surface.sunken),
                    contentScale = ContentScale.Crop,
                )
            } else {
                GiftImage(
                    giftItemId = data.giftItemId,
                    fallbackSymbol = fallbackSymbol,
                    size = 140.dp,
                    cornerRadius = 12.dp,
                    showsShadow = false,
                    backgroundColor = colors.surface.sunken,
                )
            }

            Text(
                text = data.giftName,
                style = typography.label,
                color = colors.text.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(140.dp),
            )

            // 金色发丝分割线（economy.gold·限宽 140）
            Box(modifier = Modifier.width(140.dp).height(0.5.dp).background(colors.economy.gold.copy(alpha = 0.35f)))

            // 价格行：礼物图标 + 心意 + 金币数（金额 tnum·economy.gold）+ 金币（限宽 140）
            Row(
                modifier = Modifier.width(140.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppActionIcons.Gift, contentDescription = null, tint = colors.economy.gold, modifier = Modifier.size(14.dp))
                Text("心意", style = typography.caption, color = colors.text.secondary)
                Text("${data.cost}", style = typography.amount, color = colors.economy.gold)
                Text("金币", style = typography.caption, color = colors.text.secondary)
            }
        }

        // 手作 pill（右上角·白底金描边去大红·仅手作时显示）
        if (data.isHandmade) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .clip(AppShapes.full)
                    .background(colors.surface.raised)
                    .border(1.dp, colors.economy.gold, AppShapes.full)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text("手作", style = typography.caption, color = colors.economy.gold)
            }
        }
    }
}
