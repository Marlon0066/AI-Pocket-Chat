package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.contacts.ContactsViewModel
import com.situ.aichat.ui.contacts.RecentEvent
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.util.StreakManager

/** 行内小件落值（§3.2「列表行」）：关系小标 11 · 火苗 14 + 3 + 数字 13 tnum · 待重生成红点 11。 */
private val PILL_H_PAD = 8.dp
private val PILL_V_PAD = 1.dp
private val FALLBACK_DOT = 11.dp
private val FALLBACK_DOT_RING = 1.5.dp
private val STREAK_ICON = 14.dp
private val STREAK_GAP = 3.dp
private const val MYSTERY_ALPHA = 0.6f

/**
 * 琉璃联系人一行（图纸 2026-09-06 卷三 §4.3 B · 契约 §6 B 甲）。
 *
 * **语义逐字照抄暖陶 F5**：整行 `combinedClickable`（点 = 进会话 / 分享落地、长按 = 动作面板 + medium 触觉）
 * + `semantics(mergeDescendants)` 的一句 cd 与三个 customActions；头像那块 `clearAndSetSemantics{}` 必须排在
 * `clickable` **之前**（清空节点要最后生效）。皮走 [LiuliListRow]。右侧火苗 = **连续聊天天数**
 * （`StreakManager`），不是亲密度——对版稿写「亲密度」是文案笔误（A-8）；亲密度环 / 数值化仍 parked。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiuliContactRow(
    row: ContactsViewModel.Row,
    nowMillis: Long,
    hasFallback: Boolean,
    onOpen: () -> Unit,
    onOpenProfile: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val character = row.character
    val streak = StreakManager.getStreakCount(character)
    val occupation = character.occupation.trim()
    val recentEventText = recentEventText(row.recentEvent, nowMillis)
    val a11yLabel = buildString {
        append(character.name)
        row.relationshipDisplay?.let { append("，").append(it) }
        (recentEventText ?: occupation.takeIf { it.isNotEmpty() })?.let { append("，").append(it) }
        if (streak > 0) append("，连续 ").append(streak).append(" 天")
        if (hasFallback) append("，").append(stringResource(R.string.a11y_contact_fallback_pending))
    }
    val actionProfile = stringResource(R.string.a11y_contact_open_profile)
    val actionEdit = stringResource(R.string.action_edit)
    val actionDelete = stringResource(R.string.action_delete)

    LiuliListRow(
        modifier = modifier
            .combinedClickable(
                onClickLabel = stringResource(R.string.a11y_contact_open_chat),
                onClick = { onOpen() },
                onLongClick = { haptics.medium(); onLongPress() },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = a11yLabel
                customActions = listOf(
                    CustomAccessibilityAction(actionProfile) { onOpenProfile(); true },
                    CustomAccessibilityAction(actionEdit) { onEdit(); true },
                    CustomAccessibilityAction(actionDelete) { onDelete(); true },
                )
            },
        avatar = {
            Box(Modifier.clearAndSetSemantics {}.clickable(onClick = onOpenProfile), Alignment.Center) {
                CharacterAvatar(character.name, character.avatarPath, LiuliHomeGeometry.rowAvatar)
                if (hasFallback) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(FALLBACK_DOT)
                            .clip(CircleShape)
                            .background(colors.surface.base)
                            .padding(FALLBACK_DOT_RING)
                            .clip(CircleShape)
                            .background(colors.status.onError),
                    )
                }
            }
        },
        primary = {
            Text(
                character.name, style = AppTypography.listName, color = colors.text.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            LiuliRelationshipPill(row.relationshipDisplay)
        },
        secondary = {
            // 三级降级（§4.3 B / 暖陶 F5）：14 天内纪事 → 职业 → 神秘占位（只有占位淡 60%）。
            val (text, alpha) = when {
                recentEventText != null -> recentEventText to 1f
                occupation.isNotEmpty() -> occupation to 1f
                else -> stringResource(R.string.contacts_occupation_mystery) to MYSTERY_ALPHA
            }
            Text(
                text, style = AppTypography.listPreview, color = colors.text.secondary.copy(alpha = alpha),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            if (streak > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(STREAK_ICON))
                    Spacer(Modifier.width(STREAK_GAP))
                    Text("$streak", style = AppTypography.secondary.copy(fontSize = 13.sp, fontFeatureSettings = "tnum"), color = colors.text.secondary)
                }
            }
        },
    )
}

/**
 * 关系小标（§3.2）：11sp `secondary`，0.5 发丝描边 + 8/1 内距；
 * 「初识」（无里程碑）走 `accent.container` 浅钴蓝实底、无描边。
 */
@Composable
fun LiuliRelationshipPill(display: String?, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val isFallback = display == null
    Text(
        text = display ?: stringResource(R.string.contacts_relationship_initial),
        style = AppTypography.caption, // 11sp（§3.2「关系小标 11」）
        color = if (isFallback) colors.accent.onContainer else colors.text.secondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(CircleShape)
            .background(if (isFallback) colors.accent.container else colors.surface.base)
            .then(if (isFallback) Modifier else Modifier.border(0.5.dp, colors.surface.stroke, CircleShape))
            .padding(horizontal = PILL_H_PAD, vertical = PILL_V_PAD),
    )
}

/** 最近纪事文案（照暖陶 `ContactRow` 逐字·VM 已选出窗内事件，此处只格式化）。 */
@Composable
private fun recentEventText(event: RecentEvent?, nowMillis: Long): String? = when (event) {
    null -> null
    is RecentEvent.Milestone ->
        stringResource(R.string.contacts_recent_event_milestone, DateFormatters.relativeDay(event.atMillis, nowMillis), event.name)
    is RecentEvent.Meeting -> {
        val day = DateFormatters.relativeDay(event.atMillis, nowMillis)
        when {
            event.activity.isNotEmpty() -> stringResource(R.string.contacts_recent_event_meeting, day, event.activity)
            event.location.isNotEmpty() -> stringResource(R.string.contacts_recent_event_meeting_location, day, event.location)
            else -> stringResource(R.string.contacts_recent_event_meeting_plain, day)
        }
    }
}
