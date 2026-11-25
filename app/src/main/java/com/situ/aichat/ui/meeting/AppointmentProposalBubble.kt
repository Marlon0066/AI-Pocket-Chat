package com.situ.aichat.ui.meeting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.data.model.FutureMeetingChangeData
import com.situ.aichat.data.model.FutureMeetingProposalData
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme

/** 确认卡视觉态：proposed→待确认(三按钮)；confirmed/honored/missed→已约定回执；cancelled→婉拒回执。 */
private enum class ProposalCardState { PENDING, AGREED, DECLINED }

/**
 * 未来约定见面「确认卡」气泡（聊天流·1:1 iOS `FutureMeetingProposalCardView`，安卓 Fable-5 换装）。
 *
 * 陶土玫日历隐喻(过审 2026-06-25·mockup `future_meetup_ui_mockup`)：暖白 raised 卡 + 发丝边 + 陶土色图标/时间，
 * 邀约台词与暗示走楷体点缀(kaiQuote)。**态由真理源 [status] 驱动**(气泡实时观察 MeetingAppointment.status·
 * 同红包卡观察 Record)；[status]=null(约定已不存在)时退到消息快照 [FutureMeetingProposalData.responded] 兜底、收起按钮。
 *
 * **纯展示**：按钮回调由调用方注入(聊天接 ChatViewModel→MeetingProposalCoordinator)。
 */
@Composable
fun AppointmentProposalBubble(
    data: FutureMeetingProposalData,
    status: MeetingStatus?,
    characterName: String,
    onAccept: () -> Unit,
    onReschedule: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val cardState = remember(status, data.responded) { resolveCardState(status, data.responded) }
    val name = characterName.ifBlank { "TA" }

    Column(
        modifier = modifier
            .widthIn(max = 280.dp)
            .clip(AppShapes.medium)
            .background(colors.surface.raised)
            .border(1.dp, colors.surface.stroke, AppShapes.medium)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // 标题：「{角色}想和你约个时间」
        IconLine(
            icon = AppActionIcons.Calendar,
            text = "${name}想和你约个时间",
            textStyle = typography.label,
            textColor = colors.text.primary,
            iconColor = colors.accent.text,
            iconSize = 18,
        )
        // 时间（突出·陶土色）
        data.whenDisplay?.takeIf { it.isNotBlank() }?.let {
            IconLine(AppActionIcons.Clock, it, typography.label, colors.accent.text, colors.accent.text)
        }
        // 活动
        data.activity?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = typography.label.copy(fontWeight = typography.body.fontWeight), color = colors.text.primary)
        }
        // 地点
        data.location?.takeIf { it.isNotBlank() }?.let {
            IconLine(Icons.Outlined.Place, it, typography.secondary, colors.text.secondary, colors.accent.text, iconSize = 14)
        }
        // 邀约台词（楷体点缀）
        data.invitation?.takeIf { it.isNotBlank() }?.let {
            Text("「$it」", style = typography.kaiQuote, color = colors.text.secondary)
        }
        // 隐晦暗示（楷体·sparkle·不剧透）
        data.tensionHint?.takeIf { it.isNotBlank() }?.let {
            IconLine(
                icon = Icons.Outlined.Stars,
                text = it,
                textStyle = typography.kaiQuote.copy(fontSize = 12.sp),
                textColor = colors.text.secondary,
                iconColor = colors.accent.text.copy(alpha = 0.6f),
                iconSize = 13,
            )
        }

        // 操作区
        when (cardState) {
            ProposalCardState.PENDING -> Row(
                modifier = Modifier.padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppButton(
                    onClick = onAccept,
                    style = AppButtonStyle.Primary,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp),
                ) { Text("好呀") }
                AppButton(
                    onClick = onReschedule,
                    style = AppButtonStyle.Tonal,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                ) { Text("换个时间") }
                AppButton(onClick = onDecline, style = AppButtonStyle.Text) { Text("先不约") }
            }
            ProposalCardState.AGREED -> ReceiptLine(Icons.Outlined.CheckCircle, "已约定", colors.accent.text)
            ProposalCardState.DECLINED -> ReceiptLine(Icons.Outlined.HighlightOff, "先不约了", colors.text.secondary)
        }
    }
}

/**
 * 未来约定见面「变更确认卡」气泡（决策①·2026-06-25 过审 mockup `future_meeting_change_card_mockup`）。
 * 识别到 AI 想改期 / 取消**已确认**约定时插，用户点头才动真理源。与确认卡共用陶土玫视觉。
 *
 * **态由消息快照 [FutureMeetingChangeData.responded] 驱动**（目标约定全程 confirmed·status 不变无法区分 pending/kept）：
 * null=待确认（带按钮）；applied=已应用回执；kept=保留原约定回执。**纯展示**·按钮回调由调用方注入。
 * 安全默认：取消态把「保留」做主按钮、「取消约定」做弱化文字钮——误触不毁已定约定。
 */
@Composable
fun AppointmentChangeBubble(
    data: FutureMeetingChangeData,
    characterName: String,
    onApply: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val name = characterName.ifBlank { "TA" }

    Column(
        modifier = modifier
            .widthIn(max = 280.dp)
            .clip(AppShapes.medium)
            .background(colors.surface.raised)
            .border(1.dp, colors.surface.stroke, AppShapes.medium)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // 标题
        IconLine(
            icon = if (data.isCancel) Icons.Outlined.EventBusy else Icons.Outlined.EditCalendar,
            text = if (data.isCancel) "${name}想取消这次约定" else "${name}想把约定改个时间",
            textStyle = typography.label,
            textColor = colors.text.primary,
            iconColor = colors.accent.text,
            iconSize = 18,
        )
        // 时间：改期 = 原时间(删除线) → 新时间；取消 = 原时间
        if (data.isReschedule) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                data.oldWhenDisplay?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = typography.secondary, color = colors.text.tertiary, textDecoration = TextDecoration.LineThrough)
                    Text("→", style = typography.secondary, color = colors.text.tertiary)
                }
                data.newWhenDisplay?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = typography.label, color = colors.accent.text)
                }
            }
        } else {
            data.oldWhenDisplay?.takeIf { it.isNotBlank() }?.let {
                IconLine(AppActionIcons.Clock, it, typography.secondary, colors.text.secondary, colors.text.tertiary, iconSize = 14)
            }
        }
        // 活动 · 地点（给上下文）
        listOfNotNull(
            data.activity?.takeIf { it.isNotBlank() },
            data.location?.takeIf { it.isNotBlank() },
        ).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = typography.secondary, color = colors.text.secondary)
        }
        // AI 提出变更的原话（楷体）
        data.reason?.takeIf { it.isNotBlank() }?.let {
            Text("「$it」", style = typography.kaiQuote, color = colors.text.secondary)
        }

        when (data.responded) {
            null -> Row(
                modifier = Modifier.padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (data.isCancel) {
                    // 安全默认：保留=主按钮（醒目）；取消约定=弱化文字钮（误触不毁约定）。
                    AppButton(onClick = onKeep, style = AppButtonStyle.Primary, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)) { Text("保留约定") }
                    AppButton(onClick = onApply, style = AppButtonStyle.Text) { Text("取消约定") }
                } else {
                    AppButton(onClick = onApply, style = AppButtonStyle.Primary, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp)) { Text("好，改") }
                    AppButton(onClick = onKeep, style = AppButtonStyle.Tonal, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) { Text("还是原来的") }
                }
            }
            FutureMeetingChangeData.RESPONDED_APPLIED ->
                if (data.isCancel) {
                    ReceiptLine(Icons.Outlined.EventBusy, "约定已取消", colors.text.secondary)
                } else {
                    ReceiptLine(Icons.Outlined.CheckCircle, "已改期" + (data.newWhenDisplay?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), colors.accent.text)
                }
            else -> ReceiptLine(Icons.Outlined.CheckCircle, "仍按原约定", colors.text.secondary) // kept
        }
    }
}

/** 图标 + 单行文字（标题/时间/地点/暗示通用）。 */
@Composable
private fun IconLine(
    icon: ImageVector,
    text: String,
    textStyle: TextStyle,
    textColor: Color,
    iconColor: Color,
    iconSize: Int = 15,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(iconSize.dp))
        Text(text, style = textStyle, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 已响应回执行（图标 + 结果文字·无按钮）。 */
@Composable
private fun ReceiptLine(icon: ImageVector, text: String, color: Color) {
    Row(
        modifier = Modifier.padding(top = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = AppTheme.typography.label, color = color)
    }
}

private fun resolveCardState(status: MeetingStatus?, responded: String?): ProposalCardState = when (status) {
    MeetingStatus.PROPOSED -> ProposalCardState.PENDING
    MeetingStatus.CONFIRMED, MeetingStatus.HONORED, MeetingStatus.MISSED -> ProposalCardState.AGREED
    MeetingStatus.CANCELLED -> ProposalCardState.DECLINED
    // 约定已不存在（删角色/会话）：退到消息快照兜底、收起按钮（不再可操作）。
    null -> when (responded) {
        FutureMeetingProposalData.RESPONDED_DECLINED -> ProposalCardState.DECLINED
        else -> ProposalCardState.AGREED
    }
}
