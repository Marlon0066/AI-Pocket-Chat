package com.situ.aichat.ui.redpacket

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.data.model.RedPacketStatus
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme

/** 哑光暗红上的暖白字（红底两档固定不随主题·#F5EFEA 降纯白光晕·对两 stop 6.6–8.9:1）。 */
private val OnPacketRed = Color(0xFFF5EFEA)

/**
 * 聊天流红包卡片气泡（行为 1:1 iOS `RedPacketBubbleView`：4 状态文案/点击/语义不变）。
 *
 * Fable-5 换装（契约 §3.2·D12 去赌场化）：**pending=哑光暗红 `#A8323D→#8A2230` + 福徽径向金 +
 * 外缘烫金细描边（单色 #D4B96A）**；accepted=红纸褪去（raised 纸壳 + 金福徽 + 金描边「已领取」pill）；
 * rejected/expired=sunken 灰退场（福徽降灰）。原「角色发的 pending 才有金描边」的可拆暗示改由副标题
 * 「点击拆开 🧧」+ a11y 文案承载（D11 不叠第二套视觉编码），烫金描边归还给红包本体。
 *
 * **纯展示**：[status] 与 [onClick] 由调用方注入（聊天渲染观察 Record 状态机；点击打开 [RedPacketDetailDialog]）。
 *
 * @param isFromUser true=用户发 / false=角色主动发（参与副文案与 a11y）。
 * @param festivalName 节日名（调用方经 FestivalCalendar 解析；无祝福时用作主文案「{节日}红包」）。
 */
@Composable
fun RedPacketCardBubble(
    data: RedPacketData,
    isFromUser: Boolean,
    status: RedPacketStatus,
    festivalName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val shape = AppShapes.medium
    val pending = status == RedPacketStatus.PENDING
    val accepted = status == RedPacketStatus.ACCEPTED
    val muted = !pending && !accepted

    // 无障碍（14.7e）：红包是涉钱可点面，但此前 cd=0、「福」徽章读成噪音、主/副文案散成两节点、无 button 语义。
    // clearAndSetSemantics 压成一个停、按状态拼读（1:1 iOS accessibilityDescription）+ Button role + onClick（替被清掉的 clickable 语义）。
    val cardDescription = "红包，${primaryText(data, festivalName)}，${secondaryText(status, isFromUser)}"

    val backgroundModifier = when {
        pending -> Modifier.background(
            Brush.linearGradient(listOf(colors.economy.redPacketStart, colors.economy.redPacketEnd)),
        )
        accepted -> Modifier.background(colors.surface.raised)
        else -> Modifier.background(colors.surface.sunken)
    }
    val strokeColor = if (pending) colors.economy.redPacketStroke else colors.surface.stroke
    val textPrimary = when {
        pending -> OnPacketRed
        accepted -> colors.text.primary
        else -> colors.text.secondary
    }
    val textSecondary = if (pending) OnPacketRed.copy(alpha = 0.8f) else colors.text.secondary

    Box(
        modifier = modifier
            .width(220.dp)
            .clip(shape)
            .then(backgroundModifier)
            .border(1.dp, strokeColor, shape)
            .clickable { onClick() }
            .padding(14.dp)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = cardDescription
                onClick { onClick(); true }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FuBadge(muted = muted)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = primaryText(data, festivalName),
                    style = typography.label,
                    color = textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = secondaryText(status, isFromUser),
                    style = typography.secondary,
                    color = textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 「福」字徽章：径向金 `sealGoldStart→sealGoldEnd` + 暗红「福」（D12 小面积留渐变）；[muted]=灰退场态。 */
@Composable
private fun FuBadge(muted: Boolean) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(
                if (muted) {
                    Modifier.background(colors.text.tertiary.copy(alpha = 0.15f))
                } else {
                    Modifier.background(
                        Brush.radialGradient(listOf(colors.economy.sealGoldStart, colors.economy.sealGoldEnd)),
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "福",
            style = AppTheme.typography.titleMedium,
            color = if (muted) colors.text.tertiary else colors.economy.redPacketSeal,
        )
    }
}

/** 主文案：祝福非空 → 祝福；否则节日名+「红包」；否则「恭喜发财」（1:1 iOS primaryText）。 */
private fun primaryText(data: RedPacketData, festivalName: String?): String {
    val trimmed = data.blessingText.trim()
    if (trimmed.isNotEmpty()) return trimmed
    if (!festivalName.isNullOrEmpty()) return festivalName + "红包"
    return "恭喜发财"
}

/** 副文案：状态 + isFromUser 驱动（1:1 iOS secondaryText）。 */
private fun secondaryText(status: RedPacketStatus, isFromUser: Boolean): String = when (status) {
    RedPacketStatus.PENDING -> if (isFromUser) "等待对方查收" else "点击拆开 🧧"
    RedPacketStatus.ACCEPTED -> if (isFromUser) "对方已领取" else "已领取"
    RedPacketStatus.REJECTED -> if (isFromUser) "对方拒收了" else "已退回"
    RedPacketStatus.EXPIRED -> "24 小时未拆,已退回"
}
