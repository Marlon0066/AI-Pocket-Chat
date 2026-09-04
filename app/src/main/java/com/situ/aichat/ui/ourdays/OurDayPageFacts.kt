package com.situ.aichat.ui.ourdays

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppNavIcons
import com.situ.aichat.ui.designsystem.AppPanelIcons
import com.situ.aichat.ui.designsystem.AppProfileIcons
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

/**
 * 日页「这一天」事实层 + 页脚（卷三图纸 §4.6-3 / -4·自 [OurDayPageScreen] 只搬不改拆出控行数）。
 * 图标 / 家族色表 = W-14（自家 + Material 现成件·不新绘九枚）；tile = 家族色 30% 叠 raised·tint = 家族深色。
 */
@Composable
internal fun OurDayFactsSection(
    facts: List<FactItem>,
    characterUuid: String,
    dayKey: String,
    onOpenMeetings: (String) -> Unit,
    onOpenPromises: (String) -> Unit,
    onOpenMoments: (String, String) -> Unit,
    onOpenDiary: (String) -> Unit,
    onOpenSchedule: (String, String) -> Unit,
) {
    val colors = AppTheme.colors
    Text(
        stringResource(R.string.our_days_section_facts),
        style = AppTypography.caption.copy(fontSize = 12.sp, letterSpacing = 1.5.sp), color = colors.text.tertiary,
        modifier = Modifier.padding(top = 16.dp, start = 2.dp, bottom = 8.dp),
    )
    facts.forEachIndexed { index, item ->
        if (index > 0) AppListDivider(startInset = 0.dp)
        val (icon, tile, tint) = factIcon(item.kind)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(tile), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = tint)
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = AppTypography.label.copy(fontWeight = FontWeight.Medium), color = colors.text.primary)
                if (item.detail.isNotEmpty()) Text(item.detail, style = AppTypography.caption.copy(fontSize = 12.sp, lineHeight = 18.sp), color = colors.text.secondary)
            }
            item.link?.let { link ->
                val (label, action) = when (link) {
                    FactLink.MEETINGS -> stringResource(R.string.our_days_link_meeting) to { onOpenMeetings(characterUuid) }
                    FactLink.PROMISES -> stringResource(R.string.our_days_link_promises) to { onOpenPromises(characterUuid) }
                    FactLink.MOMENTS -> stringResource(R.string.our_days_link_moments) to { onOpenMoments(characterUuid, dayKey) }
                    is FactLink.DIARY -> stringResource(R.string.our_days_link_diary) to { onOpenDiary(link.uuid) }
                    is FactLink.SCHEDULE -> stringResource(R.string.our_days_link_schedule) to { onOpenSchedule(characterUuid, dayKey) }
                }
                Text(label, style = AppTypography.caption.copy(fontSize = 12.sp), color = colors.accent.text, modifier = Modifier.clickable(onClickLabel = label) { action() }.padding(top = 3.dp))
            }
        }
    }
}

private data class FactIcon(val icon: ImageVector, val tile: Color, val tint: Color)

/** 图标 / 家族色表（§4.6·W-14）：tile = 家族色 30% 叠 raised·tint = 家族深色。 */
@Composable
private fun factIcon(kind: FactKind): FactIcon {
    val c = AppTheme.colors
    return when (kind) {
        FactKind.CHAT -> FactIcon(AppNavIcons.Chat, c.accent.container, c.accent.text)
        FactKind.CALL -> FactIcon(AppPanelIcons.Call, c.accent.container, c.accent.text)
        FactKind.MEETING -> FactIcon(AppPanelIcons.Meet, c.ourDays.dotMeeting.copy(alpha = 0.30f), c.ourDays.dotMeeting)
        FactKind.PROMISE -> FactIcon(Icons.Filled.Handshake, c.ourDays.dotRelation.copy(alpha = 0.30f), c.ourDays.dotRelation)
        FactKind.MILESTONE -> FactIcon(Icons.Filled.AutoAwesome, c.ourDays.dotRelation.copy(alpha = 0.30f), c.ourDays.dotRelation)
        FactKind.GIFT -> FactIcon(AppPanelIcons.Gift, c.ourDays.dotLife.copy(alpha = 0.30f), c.ourDays.dotLife)
        FactKind.RED_PACKET -> FactIcon(AppPanelIcons.RedPacket, c.ourDays.dotLife.copy(alpha = 0.30f), c.ourDays.dotLife)
        FactKind.MOMENTS -> FactIcon(AppProfileIcons.Moments, c.ourDays.dotLife.copy(alpha = 0.30f), c.ourDays.dotLife)
        FactKind.EXCHANGE_DIARY -> FactIcon(AppFeatureIcons.Diary, c.ourDays.dotLife.copy(alpha = 0.30f), c.ourDays.dotLife)
        FactKind.SCHEDULE -> FactIcon(Icons.Filled.Schedule, c.surface.sunken, c.text.tertiary)
    }
}

/** 页脚（§4.6-4）：REMEMBERS 绿药丸 + 提示（有见面用见面提示）；HIDDEN 灰药丸 + 不进记忆提示；NONE 不渲染。 */
@Composable
internal fun OurDayFooterRow(footer: FooterKind, hasMeeting: Boolean) {
    if (footer == FooterKind.NONE) return
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
        if (footer == FooterKind.REMEMBERS) {
            Text(
                stringResource(R.string.our_days_footer_remembers), style = AppTypography.caption, color = colors.status.onSuccess,
                modifier = Modifier.clip(AppShapes.full).background(colors.status.successContainer).padding(horizontal = 8.dp, vertical = 1.dp),
            )
            Text(
                stringResource(if (hasMeeting) R.string.our_days_footer_meeting_hint else R.string.our_days_footer_remembers_hint),
                style = AppTypography.caption.copy(fontSize = 11.5.sp), color = colors.text.tertiary,
            )
        } else {
            Text(
                stringResource(R.string.our_days_only_you), style = AppTypography.caption, color = colors.text.secondary,
                modifier = Modifier.clip(AppShapes.full).background(colors.surface.sunken).padding(horizontal = 8.dp, vertical = 1.dp),
            )
            Text(stringResource(R.string.our_days_footer_hidden_hint), style = AppTypography.caption.copy(fontSize = 11.5.sp), color = colors.text.tertiary)
        }
    }
}
