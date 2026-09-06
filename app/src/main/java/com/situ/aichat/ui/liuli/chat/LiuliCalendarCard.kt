package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardSurface
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃日历确认卡（图纸 2026-09-05 卷二B §4.3 · 契约 §5.2「日历卡 = 纸白版」）：替换暖陶
 * `CalendarConfirmCard`。琉璃里**只有导航层是玻璃**（契约 §3.1 #2），所以这张卡是实心纸面
 * （[liuliCardSurface] = raised + 0.5 发丝）+ 一道玻璃软影，不上玻璃。
 *
 * 文案拼法、字段取用与删除警示与暖陶逐字同（图纸 F7）：确认词恒取 [CalendarAction.confirmButtonText]
 * ——它随动作类型变（添加 / 修改 / 删除），写死一个词对另两种就不对（图纸 A-4）。
 */
@Composable
internal fun LiuliCalendarCard(
    characterName: String,
    action: CalendarAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val shadow = if (dark) {
        // 夜里纸卡与底之间已有明度差，再加影只会糊成一团（对版稿夜档同样不画）。
        Modifier
    } else {
        Modifier.shadow(
            elevation = CARD_SHADOW_ELEVATION,
            shape = LiuliShapes.medium,
            clip = false,
            ambientColor = Palette.InkCool.copy(alpha = CARD_SHADOW_ALPHA),
            spotColor = Palette.InkCool.copy(alpha = CARD_SHADOW_ALPHA),
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(shadow)
            .liuliCardSurface(LiuliShapes.medium)
            .padding(horizontal = CARD_HORIZONTAL_PADDING, vertical = CARD_VERTICAL_PADDING)
            // 弹出即 Polite 播报（读屏用户知道 AI 正等确认）；按钮不 merge 保持各自可操作。
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = "${characterName}想${action.actionVerb}一个${action.typeDisplayName}",
            style = AppTypography.bodyEmphasis,
            color = colors.text.primary,
        )
        if (action.title.isNotEmpty()) {
            Text(
                action.title,
                style = AppTypography.secondary,
                color = colors.text.secondary,
                modifier = Modifier.padding(top = ROW_GAP),
            )
        }
        val dateDesc = action.displayDateDescription()
        if (dateDesc.isNotEmpty()) {
            Text(
                dateDesc,
                style = AppTypography.secondary,
                color = colors.text.secondary,
                modifier = Modifier.padding(top = ROW_GAP),
            )
        }
        action.notes?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = AppTypography.secondary, color = colors.text.secondary, modifier = Modifier.padding(top = ROW_GAP))
        }
        action.location?.takeIf { it.isNotEmpty() }?.let {
            Text("📍 $it", style = AppTypography.secondary, color = colors.text.secondary, modifier = Modifier.padding(top = ROW_GAP))
        }
        if (action.isDeleteAction) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = ROW_GAP),
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = colors.status.onWarning,
                    modifier = Modifier.size(WARNING_ICON_SIZE),
                )
                Spacer(Modifier.width(WARNING_ICON_GAP))
                Text("此操作不可撤销", style = AppTypography.secondary, color = colors.status.onWarning)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = BUTTON_ROW_TOP),
            horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP),
        ) {
            // 破坏性动作绝不渲染成主 CTA（PITFALLS §1d）：删除走 Glass + danger 红字，其余才是 Prominent。
            LiuliButton(
                onClick = onConfirm,
                style = if (action.isDeleteAction) LiuliButtonStyle.Glass else LiuliButtonStyle.Prominent,
                danger = action.isDeleteAction,
                modifier = Modifier.weight(1f),
            ) {
                Text(action.confirmButtonText)
            }
            LiuliButton(
                onClick = onCancel,
                style = LiuliButtonStyle.Glass,
                modifier = Modifier.weight(1f),
            ) {
                Text("取消")
            }
        }
    }
}

/** 落值（图纸 §3.2 日历卡一节·孤值即打回）。 */
private val CARD_HORIZONTAL_PADDING = 14.dp
private val CARD_VERTICAL_PADDING = 12.dp
private val CARD_SHADOW_ELEVATION = 8.dp
private const val CARD_SHADOW_ALPHA = 0.12f
private val ROW_GAP = 8.dp
private val WARNING_ICON_SIZE = 14.dp
private val WARNING_ICON_GAP = 4.dp
private val BUTTON_ROW_TOP = 12.dp
private val BUTTON_GAP = 8.dp
