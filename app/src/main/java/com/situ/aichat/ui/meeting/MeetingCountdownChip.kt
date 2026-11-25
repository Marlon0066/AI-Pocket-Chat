package com.situ.aichat.ui.meeting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.meeting.MeetingDisplayFormatter
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import java.time.ZoneId

/**
 * 等待期「倒数小条」（Phase 9·过审 mockup `future_meetup_ui_mockup`）：聊天页顶 / 资料页展示「下一个已确认未来约定」的
 * 倒计时人话 + 活动/地点；点开浮起菜单可改期 / 取消（查看 = 小条本身即信息）。陶土玫 raised 浮丸 + 发丝边。
 *
 * **纯展示**：倒计时文本复用 [MeetingDisplayFormatter.countdownText]（今天 HH:mm / 明天 / N天后 / 绝对日期）；
 * 改期 / 取消回调由调用方注入（聊天接 ChatViewModel→Coordinator·改期复用 8c 改期 sheet）。
 *
 * [onReschedule] / [onCancel] 均为空 → **信息型**（无 ⋯ 菜单、整条不可点）：资料页（Phase 12·角色级）只读展示用，
 * 管理动作留聊天页。任一非空 → 显 ⋯ 菜单（仅列出非空的项）。
 */
@Composable
fun MeetingCountdownChip(
    appt: MeetingAppointmentEntity,
    characterName: String,
    onReschedule: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val zone = remember { ZoneId.systemDefault() }
    val name = characterName.ifBlank { "TA" }
    val countdown = remember(appt.scheduledAt, appt.timeGranularity) {
        MeetingDisplayFormatter.countdownText(
            appt.scheduledAt, MeetingTimeGranularity.fromRaw(appt.timeGranularity), System.currentTimeMillis(), zone,
        )
    }
    val detail = remember(appt.activity, appt.location) {
        listOfNotNull(
            appt.activity.takeIf { it.isNotBlank() },
            appt.location.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }
    val interactive = onReschedule != null || onCancel != null
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(AppShapes.full)
            .background(colors.surface.raised)
            .border(0.5.dp, colors.surface.stroke, AppShapes.full)
            .then(if (interactive) Modifier.clickable { menuOpen = true } else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(AppActionIcons.Calendar, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(16.dp))
        Text(
            text = "${countdown}和${name}见面" + if (detail.isNotBlank()) " · $detail" else "",
            style = typography.label,
            color = colors.accent.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (interactive) {
            Icon(Icons.Outlined.MoreHoriz, contentDescription = "约定操作", tint = colors.text.tertiary, modifier = Modifier.size(16.dp))
            AppMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                onReschedule?.let { cb ->
                    AppMenuItem(text = "改期", onClick = { menuOpen = false; cb() })
                }
                onCancel?.let { cb ->
                    AppMenuItem(
                        text = "取消约定", // 审计 T2：与消息菜单删除项同口径（经桥同值）
                        danger = true,
                        onClick = { menuOpen = false; cb() },
                    )
                }
            }
        }
    }
}

/**
 * 到点「出发赴约」按钮（Phase 10·10d·过审 mockup `meetup_arrival_button_morph`）：等待期 [MeetingCountdownChip]
 * 在约定到点（仍在宽限窗口内）就地变身——**深档深陶渐变填充 + 暖白字**的醒目 CTA（accent.deepStart→deepEnd /
 * onDeep，对齐设计语言「到点变身按钮」），点击进线下见面沉浸赴约。是否显示由 ChatViewModel.arrivalAppointment 驱动。
 */
@Composable
fun MeetingArrivalButton(
    onArrive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    Row(
        modifier = modifier
            .clip(AppShapes.full)
            .background(Brush.horizontalGradient(listOf(colors.accent.deepStart, colors.accent.deepEnd)))
            .clickable(role = Role.Button, onClick = onArrive)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, contentDescription = null, tint = colors.accent.onDeep, modifier = Modifier.size(16.dp))
        Text(text = "到点啦，去赴约", style = typography.label, color = colors.accent.onDeep)
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = colors.accent.onDeep, modifier = Modifier.size(15.dp))
    }
}
