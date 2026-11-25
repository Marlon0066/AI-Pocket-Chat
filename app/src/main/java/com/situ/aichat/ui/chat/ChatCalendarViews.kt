package com.situ.aichat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.prompt.CalendarItemParser
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppIconButton
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

/**
 * 日历操作确认卡片（P5.3b，对齐 iOS CalendarConfirmCard）：AI 想增改删日历事件时在输入栏上方弹出。
 * Fable-5（契约 §3.6·D9 同口径）：删除=status.warning 琥珀（「不可撤销」是用户主动决策的警示·血红留给
 * status.error 真失败）；其余=陶土玫。raised 暖白纸卡 + 16dp + 发丝描边。
 */
@Composable
fun CalendarConfirmCard(
    characterName: String,
    action: CalendarAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AppTheme.colors
    val accent: Color = if (action.isDeleteAction) colors.status.onWarning else colors.accent.primary
    Surface(
        color = colors.surface.raised,
        shape = AppShapes.medium,
        border = BorderStroke(1.dp, colors.surface.stroke),
        shadowElevation = 1.dp,
        // 审计 Y4：确认卡（含不可撤销删除警示）弹出即 Polite 播报——读屏用户知道 AI 正等确认；按钮不 merge 保持各自可操作。
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp)) {
            // 标题行：图标 + 「{角色}想{动词}一个{类型}」
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppActionIcons.Calendar, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${characterName}想${action.actionVerb}一个${action.typeDisplayName}",
                    style = AppTypography.bodyEmphasis,
                    color = colors.text.primary,
                )
            }

            // 详情（标题 / 时间 / 备注 / 地点）
            Column(Modifier.padding(start = 28.dp, top = 8.dp)) {
                if (action.title.isNotEmpty()) {
                    Text(action.title, style = AppTypography.label, color = colors.text.primary)
                }
                val dateDesc = action.displayDateDescription()
                if (dateDesc.isNotEmpty()) {
                    Text(dateDesc, style = AppTypography.secondary, color = colors.text.secondary)
                }
                action.notes?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = AppTypography.secondary, color = colors.text.secondary, modifier = Modifier.padding(top = 2.dp))
                }
                action.location?.takeIf { it.isNotEmpty() }?.let {
                    Text("📍 $it", style = AppTypography.secondary, color = colors.text.secondary, modifier = Modifier.padding(top = 2.dp))
                }
                if (action.isDeleteAction) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(AppActionIcons.Warning, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("此操作不可撤销", style = AppTypography.secondary, color = accent)
                    }
                }
            }

            // 操作按钮
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                AppButton(onClick = onCancel, style = AppButtonStyle.Text) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                // 删除=琥珀实底警示档（[AppButtonStyle.Warning]·深浅双档暖白字·修原深色「白字 on 浅琥珀 ≈1.6:1」bug）；
                // 其余=陶土主钮。
                AppButton(
                    onClick = onConfirm,
                    style = if (action.isDeleteAction) AppButtonStyle.Warning else AppButtonStyle.Primary,
                ) {
                    Text(action.confirmButtonText)
                }
            }
        }
    }
}

/**
 * 日历 toast 的进出动画容器（P5.3b·浮消息区顶部）。抽成 [BoxScope] 收方（W13）：ChatScreen content 区改 Column 后，
 * 内联的 `AnimatedVisibility(Modifier.align(...))` 会因外层 ColumnScope 触发隐式接收者歧义；本函数只有 BoxScope
 * 一个接收者，解析干净。行为与内联版逐字一致（[text] null = 隐藏）。
 */
@Composable
fun BoxScope.ChatCalendarToast(text: String?, isDelete: Boolean, reduceMotion: Boolean, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = text != null,
        modifier = Modifier.align(Alignment.TopCenter),
        // 审计 Y5③：减弱动画 → 直显直隐（照 VoiceMessageBubble 转写区写法；else 分支=该重载默认值原样）。
        enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandIn(),
        exit = if (reduceMotion) ExitTransition.None else shrinkOut() + fadeOut(),
    ) {
        text?.let { CalendarToastBanner(text = it, isDelete = isDelete, onDismiss = onDismiss) }
    }
}

/**
 * 日历操作成功提示横幅（P5.3b，对齐 iOS CalendarActionToast）：操作成功后短暂浮现在消息区顶部，自动消失。
 */
@Composable
fun CalendarToastBanner(text: String, isDelete: Boolean, onDismiss: () -> Unit) {
    // Fable-5：成功=status.success 灰绿、删除=status.warning 琥珀（双档·28dp 胶囊），与网络横幅同语言。
    val colors = AppTheme.colors
    val container = if (isDelete) colors.status.warningContainer else colors.status.successContainer
    val content = if (isDelete) colors.status.onWarning else colors.status.onSuccess
    // 审计 Y4：4s 自动消失的 toast 弹出即播报（合并成一句）。
    Surface(color = container, shape = AppShapes.large, shadowElevation = 1.dp, modifier = Modifier.padding(8.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = AppTypography.secondary, color = content)
            Spacer(Modifier.width(4.dp))
            AppIconButton(
                icon = AppActionIcons.Close,
                contentDescription = stringResource(R.string.action_close),
                onClick = onDismiss,
                tint = content,
                iconSize = 16.dp,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * 日程卡片气泡（P5.3b，对齐 iOS CalendarItemInlineBadge）：把 AI 照抄的 `[#E1] …` 行渲染成日历卡片。
 * 一条消息可能含多张卡片（连续卡片行）+ 偶发普通文本；用 [CalendarItemParser] 拆分逐段渲染。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScheduleCardBubble(content: String, onLongClick: () -> Unit) {
    val segments = remember(content) { CalendarItemParser.parse(content) }
    Column(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .combinedClickable(onClick = {}, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.a11y_message_menu)), // Y2
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        segments.forEach { seg ->
            when (seg) {
                is CalendarItemParser.Segment.Text ->
                    Text(seg.text, style = AppTypography.body, color = AppTheme.colors.text.primary)
                is CalendarItemParser.Segment.Item ->
                    CalendarItemCard(seg.item)
            }
        }
    }
}

@Composable
private fun CalendarItemCard(item: CalendarItemParser.ParsedCalendarItem) {
    // 事件=陶土玫，提醒（安卓平台缺口，防御渲染）=经济金外的暖琥珀深档（与 warning 同族图标装饰）。
    val colors = AppTheme.colors
    val accent = if (item.type == CalendarItemParser.ItemType.EVENT) colors.accent.primary else colors.status.onWarning
    Surface(color = colors.surface.raised, shape = AppShapes.medium, border = BorderStroke(1.dp, colors.surface.stroke)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(AppActionIcons.Calendar, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(item.title, style = AppTypography.label, color = colors.text.primary)
                if (item.dateInfo.isNotEmpty()) {
                    Text(
                        item.dateInfo,
                        style = AppTypography.secondary,
                        color = colors.text.secondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
