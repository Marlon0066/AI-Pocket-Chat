package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.data.model.RedPacketStatus
import com.situ.aichat.ui.chat.rememberBubbleMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes

/**
 * 琉璃红包卡（图纸 2026-09-05 卷二C §4.5 · A-9）：pending = **哑光红**（`packetRedTop → packetRedBottom`
 * 160° + 顶沿迎光 + 红影）· accepted / rejected / expired = 纸白卡；封印 26（muted 灰）；非 pending 时
 * 副文案下沉成状态胶囊。
 *
 * **只换渲染皮**（图纸「钱路声明」）：状态三态语义、`primaryText` / `secondaryText` 取值、整卡点击与
 * 无障碍句拼法与暖陶 `RedPacketCardBubble`（F9）逐字同；`ui/redpacket` 整目录零改，金额只显示不参与任何账。
 */
@Composable
internal fun LiuliRedPacketCard(
    data: RedPacketData,
    isFromUser: Boolean,
    status: RedPacketStatus,
    festivalName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val pending = status == RedPacketStatus.PENDING
    val accepted = status == RedPacketStatus.ACCEPTED
    val muted = !pending && !accepted
    val primary = liuliRedPacketPrimaryText(data, festivalName)
    val secondary = liuliRedPacketSecondaryText(status, isFromUser)
    // cd 逐字照抄暖陶 F9（`RedPacketCardBubble.kt:76`）。
    val cardDescription = "红包，$primary，$secondary"

    // 复核 R1 🟡-2：点击挂在两种卡壳各自的圆角裁切**之内**（ripple 不出圆角）；语义仍在外层压成一个 Button 停。
    Box(
        modifier = modifier.clearAndSetSemantics {
            role = Role.Button
            contentDescription = cardDescription
            onClick { onClick(); true }
        },
    ) {
        val header: @Composable () -> Unit = {
            LiuliCardHeader(
                icon = Icons.Filled.Redeem,
                title = primary,
                subtitle = if (pending) {
                    {
                        Text(
                            secondary,
                            style = AppTypography.secondary,
                            color = LiuliPalette.packetText.copy(alpha = PACKET_SUB_ALPHA),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier.padding(end = SEAL_CLEARANCE),
                titleColor = if (pending) LiuliPalette.packetText else colors.text.primary,
                iconBlockColor = if (pending) Palette.White.copy(alpha = PACKET_ICON_BLOCK_ALPHA) else colors.accent.container,
                iconColor = if (pending) LiuliPalette.packetGold else colors.accent.onContainer,
            )
        }
        if (pending) {
            Column(
                modifier = Modifier
                    .width(liuliCardWidth(LiuliChatGeometry.cardWidth, rememberBubbleMaxWidth()))
                    // §3.2 红包一节：影 = `packetRedBottom@0.28` 4 / 14（复核 R1 🟡-4 补上·昼夜同·自带底色的面）。
                    .shadow(
                        elevation = PACKET_SHADOW,
                        shape = LiuliShapes.medium,
                        clip = false,
                        ambientColor = LiuliPalette.packetRedBottom.copy(alpha = PACKET_SHADOW_ALPHA),
                        spotColor = LiuliPalette.packetRedBottom.copy(alpha = PACKET_SHADOW_ALPHA),
                    )
                    .clip(LiuliShapes.medium)
                    .drawWithCache {
                        // 160°（CSS `linear-gradient(160deg,…)`）= 自竖直向右偏 20°：终点横移 = 高 × tan20°。
                        val brush = Brush.linearGradient(
                            colors = listOf(LiuliPalette.packetRedTop, LiuliPalette.packetRedBottom),
                            start = Offset.Zero,
                            end = Offset(size.height * TAN_20_DEG, size.height),
                        )
                        onDrawBehind {
                            drawRect(brush)
                            // 顶沿迎光 1px 硬线（形状之外已被 clip 裁掉）。
                            drawRect(Palette.White.copy(alpha = PACKET_SPECULAR_ALPHA), size = size.copy(height = 1f))
                        }
                    }
                    .clickable { onClick() },
            ) {
                header()
            }
        } else {
            LiuliCard(width = LiuliChatGeometry.cardWidth, onClick = onClick) {
                header()
                LiuliRedPacketStatusPill(text = secondary)
            }
        }
        LiuliFuSeal(muted = muted, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/**
 * 状态胶囊（§4.5）：非 pending 时副文案下沉到卡底。
 *
 * **图纸未覆盖**（§11 D-2）：§3.2 只给了哑光红卡上的胶囊配色（`Black@0.18` 底 + `packetGold` 字），
 * 而 A-9 把 accepted / muted 都判成**纸白卡**——那套金字压浅灰底不达对比。此处按 F9 里同为「退场态」的
 * rejected / expired 用色（`surface.sunken` 底 + `text.secondary` 字）落地，留复核裁决。
 */
@Composable
internal fun LiuliRedPacketStatusPill(text: String) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .padding(start = PILL_SIDE, end = PILL_SIDE, bottom = PILL_BOTTOM)
            .clip(LiuliShapes.pill)
            .background(colors.surface.sunken)
            .padding(horizontal = PILL_PAD_SIDE, vertical = PILL_PAD_VERTICAL),
    ) {
        Text(text, style = AppTypography.caption, color = colors.text.secondary, maxLines = 1)
    }
}

/** 烫金封印 26（§3.2·`sealGoldStart→End` 135° · 「福」`sealInk`；[muted] = 灰退场·语义照 F9）。 */
@Composable
private fun LiuliFuSeal(muted: Boolean, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Box(
        modifier = modifier
            .padding(top = SEAL_TOP, end = SEAL_END)
            .size(SEAL_SIZE)
            .clip(CircleShape)
            .then(
                if (muted) {
                    Modifier.background(colors.text.tertiary.copy(alpha = SEAL_MUTED_ALPHA))
                } else {
                    Modifier.background(
                        Brush.linearGradient(listOf(colors.economy.sealGoldStart, colors.economy.sealGoldEnd)),
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "福",
            style = AppTypography.caption.copy(fontSize = SEAL_FONT, fontWeight = SEAL_WEIGHT),
            color = if (muted) colors.text.tertiary else LiuliPalette.sealInk,
        )
    }
}

/**
 * 主文案（**重打**暖陶 `RedPacketCardBubble.primaryText` 同值·那侧是 private·两侧注释互指·卷二B `M:SS` 同判例）：
 * 祝福非空 → 祝福；否则节日名 +「红包」；否则「恭喜发财」。
 */
internal fun liuliRedPacketPrimaryText(data: RedPacketData, festivalName: String?): String {
    val trimmed = data.blessingText.trim()
    if (trimmed.isNotEmpty()) return trimmed
    if (!festivalName.isNullOrEmpty()) return festivalName + "红包"
    return "恭喜发财"
}

/** 副文案（**重打**暖陶 `RedPacketCardBubble.secondaryText` 同值·同上互指）：状态 × [isFromUser] 驱动。 */
internal fun liuliRedPacketSecondaryText(status: RedPacketStatus, isFromUser: Boolean): String = when (status) {
    RedPacketStatus.PENDING -> if (isFromUser) "等待对方查收" else "点击拆开 🧧"
    RedPacketStatus.ACCEPTED -> if (isFromUser) "对方已领取" else "已领取"
    RedPacketStatus.REJECTED -> if (isFromUser) "对方拒收了" else "已退回"
    RedPacketStatus.EXPIRED -> "24 小时未拆,已退回"
}

/** 落值（§3.2 红包一节 + 对版稿 `.card.red`·孤值即打回）。 */
private const val PACKET_SUB_ALPHA = 0.8f
private const val PACKET_ICON_BLOCK_ALPHA = 0.14f
private const val PACKET_SPECULAR_ALPHA = 0.28f
private const val TAN_20_DEG = 0.36397f
private val PACKET_SHADOW = 4.dp
private const val PACKET_SHADOW_ALPHA = 0.28f
private val SEAL_SIZE = 26.dp
private val SEAL_TOP = 12.dp
private val SEAL_END = 14.dp
private val SEAL_CLEARANCE = 30.dp
private const val SEAL_MUTED_ALPHA = 0.15f
private val SEAL_FONT = 12.sp
private val SEAL_WEIGHT = FontWeight(640)
private val PILL_SIDE = 14.dp
private val PILL_BOTTOM = 12.dp
private val PILL_PAD_SIDE = 8.dp
private val PILL_PAD_VERTICAL = 2.dp
