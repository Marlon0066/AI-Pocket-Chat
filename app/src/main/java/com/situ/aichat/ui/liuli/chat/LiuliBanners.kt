package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.meeting.MeetingDisplayFormatter
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliCloseDot
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.designsystem.LiuliPopupMenu
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.liuli.page.liuliTouchHeight
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import java.time.ZoneId
import kotlinx.coroutines.delay

/** 横幅族落值（图纸 §3.2「C6 锁定项」+ §4.14·孤值即打回）。 */
private val BANNER_HEIGHT = 36.dp
private val TOAST_HEIGHT = 38.dp
private val STATUS_DOT = 8.dp
private val TOAST_DOT = 20.dp
private val BANNER_ICON = 16.dp
private val ARRIVAL_ICON = 18.dp
private val ARRIVAL_ARROW = 15.dp
private val TOAST_CHECK = 12.dp
private val BANNER_GAP = 8.dp
/** 横幅正文字重（§3.2「字 snackbarBody W520」）与倒数段字重（W640）。 */
private val BANNER_WEIGHT = FontWeight(520)
private val COUNTDOWN_WEIGHT = FontWeight(640)
/** 倒数条弹出菜单与条底的间距（菜单落在条下方·R2 🟡-2）。 */
private val MENU_GAP = 6.dp

/** 网络恢复条自动消的等待（照抄 F30 的 2000ms·由调用方的 `onRecoveredShown` 落地）。 */
private const val RECOVERED_LINGER_MS = 2000L

/**
 * 玻璃胶囊横幅底座（图纸 2026-09-05 卷二C §4.14 · A-19 · 对版稿 D 甲）：36 高 pill，玻璃档位**跟随用户**
 * （它属导航层·契约 §3.1 #2）。左 [leading] → 文 → 右 [trailing]。
 */
@Composable
private fun LiuliBanner(
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit,
    text: @Composable RowScope.() -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val dark = LocalIsDarkTheme.current
    Row(
        modifier = modifier
            .height(BANNER_HEIGHT)
            .liuliGlass(LiuliShapes.pill, dark = dark)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BANNER_GAP),
    ) {
        leading()
        text()
        trailing?.invoke(this)
    }
}

/**
 * 网络状态横幅（照抄源 F30 `ui/chat/ChatScreenParts.kt:182-218`）：离线常驻（离线是状态不是错误），
 * 恢复 2s 自动消。文案 / `liveRegion = Polite` / 2s 时序一字不动，只换成玻璃胶囊 + 一枚状态点。
 */
@Composable
internal fun LiuliNetworkBanner(connected: Boolean, recovered: Boolean, onRecoveredShown: () -> Unit) {
    val offline = !connected
    if (recovered && connected) {
        LaunchedEffect(Unit) {
            delay(RECOVERED_LINGER_MS)
            onRecoveredShown()
        }
    }
    val colors = AppTheme.colors
    val dot = if (offline) colors.status.onWarning else colors.status.onSuccess
    LiuliBanner(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        leading = {
            Box(Modifier.size(STATUS_DOT).clip(CircleShape).background(dot))
            Icon(
                if (offline) Icons.Filled.WifiOff else Icons.Filled.Wifi,
                contentDescription = null,
                tint = dot,
                modifier = Modifier.size(BANNER_ICON),
            )
        },
        text = {
            Text(
                stringResource(if (offline) R.string.chat_network_disconnected else R.string.chat_network_recovered),
                style = AppTypography.snackbarBody.copy(fontWeight = BANNER_WEIGHT),
                color = LiuliTheme.onGlass.primary,
            )
        },
    )
}

/**
 * 约定倒数条（照抄源 F30 `ui/meeting/MeetingCountdownChip.kt:49-108`）：倒数文本走
 * [MeetingDisplayFormatter].countdownText，拼法「{倒数}和{名}见面 · {活动} · {地点}」单行省略
 * （Q-C2 拍板：显示明细）。`···` 打开 [LiuliPopupMenu]（改期 / 取消约定 danger）。
 */
@Composable
internal fun LiuliCountdownChip(
    appt: MeetingAppointmentEntity,
    characterName: String,
    onReschedule: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val zone = remember { ZoneId.systemDefault() }
    val name = characterName.ifBlank { "TA" }
    val countdown = remember(appt.scheduledAt, appt.timeGranularity) {
        MeetingDisplayFormatter.countdownText(
            appt.scheduledAt,
            MeetingTimeGranularity.fromRaw(appt.timeGranularity),
            System.currentTimeMillis(),
            zone,
        )
    }
    val detail = remember(appt.activity, appt.location) {
        listOfNotNull(
            appt.activity.takeIf { it.isNotBlank() },
            appt.location.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }
    var menuOpen by remember { mutableStateOf(false) }

    // 整条可点（照抄 F30 暖陶原件·R2 核准 D-C6-5：`···` 只是「这条可点」的提示 + 读屏标签）；
    // 版位恒 36，触达由外层 liuliTouchHeight 上下外溢到 48（REDLINES「a11y 48dp」）。
    // 菜单锚在**这整条**（Popup 的锚 = 调用它的那个父布局）并下移「条高 + 间距」，落在条**下方**
    // 向左展开——锚在 `···` 图标上会让 160 宽的菜单盖住条本身（R2 🟡-2·装机 c6_12 实证）。
    Box(
        modifier = modifier
            .liuliTouchHeight()
            .clickable(role = Role.Button) { menuOpen = true },
        contentAlignment = Alignment.Center,
    ) {
        LiuliBanner(
            leading = {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(BANNER_ICON))
            },
            text = {
                // 倒数段 W640、其余 W520（§3.2「倒数条 …… 倒数段 W640」·整句仍是一个 Text 节点，读屏一次读完）。
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = COUNTDOWN_WEIGHT)) { append(countdown) }
                        append("和${name}见面")
                        if (detail.isNotBlank()) append(" · $detail")
                    },
                    style = AppTypography.snackbarBody.copy(fontWeight = BANNER_WEIGHT),
                    color = onGlass.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            },
            trailing = {
                Icon(
                    Icons.Outlined.MoreHoriz,
                    contentDescription = "约定操作",
                    tint = onGlass.secondary,
                    modifier = Modifier.size(BANNER_ICON),
                )
            },
        )
        LiuliPopupMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            offset = DpOffset(0.dp, BANNER_HEIGHT + MENU_GAP),
            items = listOf(
                LiuliMenuEntry("改期", onClick = onReschedule),
                LiuliMenuEntry("取消约定", danger = true, onClick = onCancel),
            ),
        )
    }
}

/** 到点「出发赴约」钮（照抄源 F30 `:116-135`）：琉璃直接用 Prominent 药丸（40 高 · 触达 48 自带）。 */
@Composable
internal fun LiuliArrivalButton(onArrive: () -> Unit, modifier: Modifier = Modifier) {
    LiuliButton(onClick = onArrive, modifier = modifier, style = LiuliButtonStyle.Prominent) {
        Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, contentDescription = null, modifier = Modifier.size(ARRIVAL_ICON))
        Text("到点啦，去赴约")
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(ARRIVAL_ARROW))
    }
}

/**
 * 日历操作提示 toast（照抄源 F31 `ui/chat/ChatCalendarViews.kt:132-168`）：`text != null` 即显、
 * 进出动画与 `liveRegion = Polite` 一字不动；4s 自动消仍由 VM 定时（本件不碰）。
 */
@Composable
internal fun BoxScope.LiuliCalendarToast(
    text: String?,
    isDelete: Boolean,
    reduceMotion: Boolean,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    AnimatedVisibility(
        visible = text != null,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandIn(),
        exit = if (reduceMotion) ExitTransition.None else shrinkOut() + fadeOut(),
    ) {
        text?.let { shown ->
            val dot = if (isDelete) colors.status.onWarning else colors.status.onSuccess
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .height(TOAST_HEIGHT)
                    .liuliGlass(LiuliShapes.pill, dark = dark)
                    .padding(start = 12.dp, end = 6.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BANNER_GAP),
            ) {
                Box(Modifier.size(TOAST_DOT).clip(CircleShape).background(dot), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = colors.text.onAccent,
                        modifier = Modifier.size(TOAST_CHECK),
                    )
                }
                Text(shown, style = AppTypography.secondary, color = LiuliTheme.onGlass.primary)
                LiuliCloseDot(onDismiss)
            }
        }
    }
}
