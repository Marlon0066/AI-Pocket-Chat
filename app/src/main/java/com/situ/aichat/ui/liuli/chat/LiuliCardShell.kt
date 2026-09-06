package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.chat.rememberBubbleMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardSurface
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃卡片族的壳（图纸 2026-09-05 卷二C §2.1 / §3.2 / A-2）：礼物 / 红包 / 日程 / 通话记录 / 约见面 /
 * 改期 / 线下邀约 / 结束卡共用的**纸面容器 + 头 + 体 + 脚钮行 + 金额**（钮本体在 `LiuliCardButtons.kt`·复核 R1 只搬）。
 *
 * 琉璃里只有导航层是玻璃（契约 §3.1 #2），所以卡一律是内容层纸面——[liuliCardSurface]（raised + 0.5 发丝）
 * + 昼 1dp 接触影（与 AI 泡同档 · 夜无影 · A-2），**绝不上玻璃**。
 */

/** 卡宽收口（A-3·T1-3）：恒宽与气泡最大宽取小——360dp 窄屏上 280 已超 76% 可用宽，不让位就出血。 */
internal fun liuliCardWidth(preferred: Dp, bubbleMax: Dp): Dp = minOf(preferred, bubbleMax)

/** 昼 1dp 接触影（A-2·夜档不画：夜里纸卡与底已有明度差，再加影只会糊成一团）。 */
@Composable
internal fun Modifier.liuliCardContactShadow(shape: Shape = LiuliShapes.medium): Modifier =
    if (LocalIsDarkTheme.current) this else this.shadow(CARD_CONTACT_SHADOW, shape, clip = false)

/**
 * 纸白卡容器：恒宽（自动对 [rememberBubbleMaxWidth] 取小）+ 接触影 + 纸面。
 * [onClick] 非空时整卡可点——点击挂在圆角裁切**之内**（ripple 不出圆角·复核 R1 🟡-2），语义由调用方按需
 * `clearAndSetSemantics`（礼物 / 红包）或直接并进同一节点（通话记录）。
 */
@Composable
internal fun LiuliCard(
    width: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .width(liuliCardWidth(width, rememberBubbleMaxWidth()))
            .liuliCardContactShadow()
            .liuliCardSurface(LiuliShapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier),
        content = content,
    )
}

/**
 * 卡头（对版稿 `.card .hd`）：34dp 圆角图标块 + 标题（可缀标签）+ 副标槽。
 *
 * 色与标题样式全部可覆盖——同一个头要同时服务纸白卡、哑光红卡与恒暗卡三套底（§3.2 三节落值）。
 */
@Composable
internal fun LiuliCardHeader(
    icon: ImageVector,
    title: String,
    subtitle: (@Composable () -> Unit)?,
    tag: String? = null,
    modifier: Modifier = Modifier,
    titleColor: Color = AppTheme.colors.text.primary,
    titleStyle: TextStyle = AppTypography.label,
    iconBlockColor: Color = AppTheme.colors.accent.container,
    iconColor: Color = AppTheme.colors.accent.onContainer,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = CARD_SIDE, end = CARD_SIDE, top = HEADER_TOP, bottom = HEADER_BOTTOM),
        horizontalArrangement = Arrangement.spacedBy(HEADER_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(LiuliChatGeometry.cardIconBlock)
                .clip(RoundedCornerShape(LiuliChatGeometry.cardIconCorner))
                .background(iconBlockColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(HEADER_ICON))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = titleStyle, color = titleColor)
                if (tag != null) LiuliCardTag(tag)
            }
            subtitle?.invoke()
        }
    }
}

/** 「日程」这类小标签（对版稿 `.card .tag`·字重 500 归梯 520·设计语言 §2 梯子只有 420/520/640）。 */
@Composable
private fun LiuliCardTag(text: String) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .padding(start = TAG_LEAD)
            .clip(RoundedCornerShape(TAG_CORNER))
            .background(colors.accent.container)
            .padding(horizontal = TAG_SIDE),
    ) {
        Text(text, style = AppTypography.caption.copy(fontWeight = TAG_WEIGHT), color = colors.accent.onContainer)
    }
}

/** 卡体（对版稿 `.card .bd{padding:0 14 10}`）。 */
@Composable
internal fun LiuliCardBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().padding(start = CARD_SIDE, end = CARD_SIDE, bottom = BODY_BOTTOM),
        content = content,
    )
}

/** 卡脚钮行（对版稿 `.card .ft{gap:8; padding:0 12 12}`）。 */
@Composable
internal fun LiuliCardButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = FOOT_SIDE, end = FOOT_SIDE, bottom = FOOT_BOTTOM),
        horizontalArrangement = Arrangement.spacedBy(FOOT_GAP),
        content = content,
    )
}

/** 金额（对版稿 `.card .gold`）：`amount`（14 / 640 / tnum）× `economy.gold`。 */
@Composable
internal fun LiuliGoldAmount(text: String) {
    Text(text, style = AppTypography.amount, color = AppTheme.colors.economy.gold)
}

/** 一道 0.5dp 发丝分线（卡内分节用·M3 `HorizontalDivider` 是禁品·§9 ⑤）。 */
@Composable
internal fun LiuliCardHairline(modifier: Modifier = Modifier, color: Color = AppTheme.colors.surface.stroke) {
    Box(modifier.fillMaxWidth().height(LIULI_CARD_HAIRLINE).background(color))
}

/** 落值（§3.2 卡一节 + 对版稿 `.card`·孤值即打回）。 */
private val CARD_CONTACT_SHADOW = 1.dp
private val CARD_SIDE = 14.dp
private val HEADER_TOP = 12.dp
private val HEADER_BOTTOM = 8.dp
private val HEADER_GAP = 10.dp
private val HEADER_ICON = 18.dp
private val BODY_BOTTOM = 10.dp
private val FOOT_SIDE = 12.dp
private val FOOT_BOTTOM = 12.dp
private val FOOT_GAP = 8.dp
private val TAG_LEAD = 6.dp
private val TAG_SIDE = 6.dp
private val TAG_CORNER = 6.dp
private val TAG_WEIGHT = FontWeight(520)
internal val LIULI_CARD_HAIRLINE = 0.5.dp
