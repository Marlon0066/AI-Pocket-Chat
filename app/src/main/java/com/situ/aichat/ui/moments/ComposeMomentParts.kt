package com.situ.aichat.ui.moments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.moments.MentionAvailability
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

// 发布页两脸共用件（朋友圈发布页·乙 §4.7 / §4.9）：工具图标钮、字数环、提醒签、选人列表主体。只用 AppTheme token。

/** 字数环三态（§4.7）。 */
internal enum class CharRingTone { NORMAL, WARN, OVER }

/** 超上限 → OVER；离上限 ≤ 50 → WARN；否则 NORMAL。 */
internal fun charRingTone(count: Int, max: Int = ComposeMomentViewModel.MAX_CHARS): CharRingTone = when {
    count > max -> CharRingTone.OVER
    max - count <= CHAR_WARN_LEFT -> CharRingTone.WARN
    else -> CharRingTone.NORMAL
}

/** 环旁数字：NORMAL 不显示；WARN = 还剩几字；OVER = 负的超出数。 */
internal fun charRingLabel(count: Int, max: Int = ComposeMomentViewModel.MAX_CHARS): String? = when (charRingTone(count, max)) {
    CharRingTone.NORMAL -> null
    CharRingTone.WARN -> "${max - count}"
    CharRingTone.OVER -> "-${count - max}"
}

/** 还剩这么多字起提示（锁定）。 */
private const val CHAR_WARN_LEFT = 50

/**
 * 工具栏图标钮（L-6：同日记 `DiaryNavIcon` 规格——40dp 圆·24dp 图标·`accent.text`·禁用 ×0.4·48 触达；
 * 那一件是 private，发布页另立同规格一件，日记零改动）。
 */
@Composable
internal fun ComposeMomentToolIcon(icon: ImageVector, contentDescription: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = contentDescription, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) colors.accent.text else colors.accent.text.copy(alpha = 0.4f),
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 字数环：左数字（WARN / OVER 才有）+ 右 20dp 环（底圈 + 自 12 点顺时针进度弧；超了整圈变红）。 */
@Composable
internal fun ComposeMomentCharRing(count: Int, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val max = ComposeMomentViewModel.MAX_CHARS
    val tone = charRingTone(count, max)
    val label = charRingLabel(count, max)
    val description = if (tone == CharRingTone.OVER) {
        stringResource(R.string.moment_compose_chars_over, count - max)
    } else {
        stringResource(R.string.moment_compose_chars_left, max - count)
    }
    val track = colors.text.primary.copy(alpha = 0.10f)
    val arc = if (tone == CharRingTone.OVER) colors.status.onError else colors.accent.text
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (label != null) {
            Text(
                label,
                style = AppTypography.captionNumeric,
                color = if (tone == CharRingTone.OVER) colors.status.onError else colors.text.secondary,
            )
        }
        Canvas(Modifier.size(20.dp)) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            drawCircle(track, radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
            val sweep = 360f * (count.toFloat() / max).coerceAtMost(1f)
            if (sweep > 0f) {
                drawArc(arc, startAngle = -90f, sweepAngle = sweep, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

/**
 * 提醒签（§4.9）：高 32 胶囊 `accent.container`；前 3 位头像叠放（24dp·后一位左移 8dp·各套 1.5dp 同底色圈）→ 8 → 「提醒 … 看」
 * → 4 → 小叉（视觉 24dp·48 触达以视觉为中心外溢）；签身点 = 改提醒，小叉 = 全清。
 */
@Composable
internal fun ComposeMentionChip(mentioned: List<CharacterEntity>, onOpen: () -> Unit, onClear: () -> Unit) {
    val colors = AppTheme.colors
    val names = mentioned.joinToString(stringResource(R.string.moment_mention_separator)) { it.name }
    val editLabel = stringResource(R.string.moment_compose_mention_chip_edit)
    val clearLabel = stringResource(R.string.moment_compose_mention_chip_clear)
    val ring = colors.accent.container
    Row(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(colors.accent.container)
            .clickable(onClickLabel = editLabel, role = Role.Button, onClick = onOpen)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            mentioned.take(MENTION_AVATARS).forEachIndexed { i, c ->
                Box(
                    Modifier
                        .padding(start = (AVATAR - AVATAR_OVERLAP) * i)
                        .drawBehind { drawCircle(ring, radius = size.minDimension / 2f + AVATAR_RING.toPx()) },
                ) {
                    CharacterAvatar(name = c.name, avatarPath = c.avatarPath, size = AVATAR)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.moment_compose_mention_chip, names),
            style = AppTypography.secondary,
            color = colors.accent.onContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(24.dp)
                .requiredSize(48.dp)
                .clickable(onClickLabel = clearLabel, role = Role.Button, onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(24.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    AppActionIcons.Close,
                    contentDescription = clearLabel,
                    tint = colors.accent.onContainer.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private const val MENTION_AVATARS = 3
private val AVATAR = 24.dp
private val AVATAR_OVERLAP = 8.dp
private val AVATAR_RING = 1.5.dp

/** 选人弹层「完成」钮文案：没选 =「完成」，选了 n 位 =「完成 n」。 */
@Composable
internal fun composeMentionDoneText(selected: List<String>): String =
    if (selected.isEmpty()) stringResource(R.string.moment_compose_mention_done)
    else stringResource(R.string.moment_compose_mention_done_count, selected.size)

/**
 * 选人列表主体（§4.9·两脸弹层里调）：标题行 + 副标题 + 角色列表（在睡 / 见面中出一行状态·点行即时切换·勾选圆）。
 * 顺序 = [characters] 原顺序；行间 0.5dp 分隔线左缩 72（= 20 + 40 + 12）。
 */
@Composable
internal fun ComposeMentionPickerBody(
    characters: List<CharacterEntity>,
    selected: List<String>,
    availability: Map<String, MentionAvailability>,
    dividerColor: Color,
    done: @Composable () -> Unit,
    onToggle: (String) -> Unit,
) {
    val colors = AppTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.moment_compose_mention_title),
                style = AppTypography.titleSmall,
                color = colors.text.primary,
                modifier = Modifier.weight(1f),
            )
            done()
        }
        Text(
            stringResource(R.string.moment_compose_mention_subtitle),
            style = AppTypography.secondary,
            color = colors.text.secondary,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
        )
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            characters.forEachIndexed { i, c ->
                if (i > 0) Box(Modifier.padding(start = 72.dp).fillMaxWidth().height(0.5.dp).background(dividerColor))
                MentionPickerRow(c, checked = c.uuid in selected, availability = availability[c.uuid], onToggle = { onToggle(c.uuid) })
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

@Composable
private fun MentionPickerRow(character: CharacterEntity, checked: Boolean, availability: MentionAvailability?, onToggle: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CharacterAvatar(name = character.name, avatarPath = character.avatarPath, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(
                character.name,
                style = AppTypography.bodyEmphasis,
                color = colors.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val note = when (availability) {
                MentionAvailability.SLEEPING -> Icons.Outlined.Bedtime to R.string.moment_compose_mention_sleeping
                MentionAvailability.IN_MEETING -> Icons.Outlined.Place to R.string.moment_compose_mention_meeting
                MentionAvailability.AVAILABLE, null -> null
            }
            if (note != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(note.first, contentDescription = null, tint = colors.text.secondary, modifier = Modifier.size(12.dp))
                    Text(stringResource(note.second), style = AppTypography.caption, color = colors.text.secondary)
                }
            }
        }
        if (checked) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(colors.accent.text), contentAlignment = Alignment.Center) {
                Icon(AppActionIcons.Check, contentDescription = null, tint = colors.surface.base, modifier = Modifier.size(14.dp))
            }
        } else {
            Box(Modifier.size(22.dp).border(1.5.dp, colors.text.tertiary, CircleShape))
        }
    }
}
