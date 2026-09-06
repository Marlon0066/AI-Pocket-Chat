package com.situ.aichat.ui.liuli.character

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.profile.StructuredMemoryStats
import com.situ.aichat.ui.character.MemColor
import com.situ.aichat.ui.character.MemoryRawFormatter
import com.situ.aichat.ui.character.fmt
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.liuliTouchHeight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// 落值与格式 1:1 暖陶（那边是 private，改一侧要同步另一侧·§11 D-13）。
private val MEM_YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.getDefault())
private val MEM_MD: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d", Locale.getDefault())
/** 记忆原文折叠态预览行数（锁定常量）。 */
private const val PREVIEW_LINES = 4
private const val CHIP_BG_ALPHA = 0.06f
private const val CHIP_BORDER_ALPHA = 0.12f
private const val CHIP_TITLE_ALPHA = 0.8f
private const val BULLET_ALPHA = 0.4f
private const val DISABLED_ALPHA = 0.45f
private val CHIP_CORNER = 12.dp
private val STRIP_CORNER = 12.dp
private val BULLET = 6.dp

private data class MemoryChipData(val icon: ImageVector, val title: String, val value: String, val color: Color)
private class SmFieldSpec(val value: String, val icon: ImageVector, val titleRes: Int, val color: Color)

/**
 * 共同记忆卡（琉璃·搬暖陶 `SharedMemoryCard`）：5 项档案统计 chip + 10 字段结构化记忆 chip + 记忆原文；
 * 全空时一句提示。记忆整理遇阻 / 忙碌时卡头下插琥珀状态条 + 「立即整理」（护栏第二层 MG-U2）。
 *
 * chip 顺序、门槛、色、弹窗、原文折叠行数、编辑入口门槛与禁用条件一律逐字继承暖陶。
 */
@Composable
internal fun LiuliProfileMemoryCard(
    stats: StructuredMemoryStats.Result,
    memory: StructuredMemory,
    memorySummary: String,
    modifier: Modifier = Modifier,
    guardBlocked: Boolean = false,
    organizing: Boolean = false,
    onOrganizeNow: () -> Unit = {},
    onEditMemory: (() -> Unit)? = null,
    editInProgressBlocked: Boolean = false,
) {
    val colors = AppTheme.colors
    val chips = buildMemoryChips(stats, memory)
    val display = MemoryRawFormatter.lines(memorySummary)
    // 弹窗态用 rememberSaveable 抗 LazyColumn item 回收；chip 含 ImageVector 不可 save → 存标题反查。
    var detailChipTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var memoryExpanded by rememberSaveable { mutableStateOf(false) }
    val detailChip = chips.firstOrNull { it.title == detailChipTitle }

    LiuliGroup(modifier = modifier, header = stringResource(R.string.profile_memory_title)) {
        LiuliRowBase(
            divider = false,
            minHeight = 0.dp,
            verticalPadding = LiuliPageGeometry.groupPadH,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (guardBlocked || organizing) {
                    MemoryGuardStrip(organizing = organizing, onOrganizeNow = onOrganizeNow)
                }
                if (chips.isEmpty() && memorySummary.isEmpty()) {
                    Text(
                        stringResource(R.string.profile_memory_empty),
                        style = AppTypography.listPreview,
                        color = colors.text.tertiary,
                    )
                } else {
                    // 记忆碎片：两列网格（非 lazy，避免在外层滚动里嵌套 lazy）。
                    if (chips.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            chips.chunked(2).forEach { rowChips ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    rowChips.forEach { chip ->
                                        MemoryChipCard(chip, Modifier.weight(1f)) { detailChipTitle = chip.title }
                                    }
                                    if (rowChips.size == 1) Spacer(Modifier.weight(1f)) // 奇数补位，保持左对齐
                                }
                            }
                        }
                    }
                    if (display.isNotEmpty()) {
                        if (chips.isNotEmpty()) Hairline()
                        MemoryRawSection(
                            display = display,
                            expanded = memoryExpanded,
                            onToggleExpanded = { memoryExpanded = !memoryExpanded },
                            onEditMemory = onEditMemory,
                            editInProgressBlocked = editInProgressBlocked,
                        )
                    }
                }
            }
        }
    }

    detailChip?.let { chip ->
        LiuliDialog(
            onDismissRequest = { detailChipTitle = null },
            title = chip.title,
            body = chip.value,
            confirmText = stringResource(R.string.action_close),
            onConfirm = { detailChipTitle = null },
        )
    }
}

/** 记忆原文区：标题行（+ 编辑入口）+ 折叠 / 展开正文 + 展开钮。 */
@Composable
private fun MemoryRawSection(
    display: List<String>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEditMemory: (() -> Unit)?,
    editInProgressBlocked: Boolean,
) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.profile_memory_raw_title),
                style = AppTypography.secondary.copy(fontWeight = FontWeight.W500),
                color = colors.text.secondary,
            )
            // 编辑入口：空记忆无入口（与记忆原文区同门槛）；整理进行中暗且禁点。
            if (onEditMemory != null) {
                LiuliButton(
                    onClick = onEditMemory,
                    style = LiuliButtonStyle.Text,
                    enabled = !editInProgressBlocked,
                    modifier = Modifier.alpha(if (editInProgressBlocked) DISABLED_ALPHA else 1f),
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(13.dp))
                    Text(stringResource(R.string.profile_memory_edit_action))
                }
            }
        }
        // 折叠态出前 PREVIEW_LINES 行·展开态整段（随外层滚·无嵌套滚动）；
        // 整行恰为【…】的行当小标题（显示层美化非分节），其余走圆点正文行。
        val shown = if (expanded) display else display.take(PREVIEW_LINES)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.forEach { line ->
                if (MemoryRawFormatter.isSectionTitle(line)) MemoryRawSubhead(line) else MemoryLine(line)
            }
        }
        if (display.size > PREVIEW_LINES) {
            LiuliButton(onClick = onToggleExpanded, style = LiuliButtonStyle.Text) {
                Text(stringResource(if (expanded) R.string.profile_memory_collapse else R.string.profile_memory_expand))
            }
        }
    }
}

/**
 * 记忆整理遇阻状态条（MG-U2·锁定规格 = 契约 FABLE5_MEMORY_GUARD_UI_PROPOSAL §4）：
 * 琥珀淡底块 + 圆形叹号 + 双行文案 + 描边胶囊钮。色 = `status.warningContainer` / `status.onWarning`
 * （与经济金物理隔离）；不用红色不说「失败 / 丢失」。忙态换文案 + 45% 置灰禁点。
 */
@Composable
private fun MemoryGuardStrip(organizing: Boolean, onOrganizeNow: () -> Unit) {
    val colors = AppTheme.colors
    val status = colors.status
    Row(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(STRIP_CORNER))
            .background(status.warningContainer)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = status.onWarning,
            modifier = Modifier.padding(top = 2.dp).size(17.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (organizing) R.string.profile_memory_guard_busy_title else R.string.profile_memory_guard_title),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.W600,
                color = status.onWarning,
            )
            Spacer(Modifier.size(3.dp))
            Text(
                stringResource(if (organizing) R.string.profile_memory_guard_busy_body else R.string.profile_memory_guard_body),
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = colors.text.secondary,
            )
        }
        Text(
            stringResource(if (organizing) R.string.profile_memory_guard_busy_action else R.string.profile_memory_guard_action),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.W600,
            color = status.onWarning,
            maxLines = 1,
            modifier = Modifier
                .padding(top = 1.dp)
                .alpha(if (organizing) DISABLED_ALPHA else 1f)
                .liuliTouchHeight()
                .border(1.dp, status.onWarning, CircleShape)
                .clip(CircleShape)
                .clickable(enabled = !organizing, role = Role.Button, onClick = onOrganizeNow)
                .padding(horizontal = 13.dp, vertical = 5.dp),
        )
    }
}

/** 一枚记忆碎片：色相 6% 淡底 + 12% 发丝 + 图标标题 + 值（两行省略）；整块可点开详情弹窗。 */
@Composable
private fun MemoryChipCard(chip: MemoryChipData, modifier: Modifier, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(CHIP_CORNER)
    Column(
        modifier
            .clip(shape)
            .background(chip.color.copy(alpha = CHIP_BG_ALPHA))
            .border(0.5.dp, chip.color.copy(alpha = CHIP_BORDER_ALPHA), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(chip.icon, contentDescription = null, tint = chip.color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                chip.title,
                style = AppTypography.secondary.copy(fontWeight = FontWeight.W500),
                color = chip.color.copy(alpha = CHIP_TITLE_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            chip.value,
            style = AppTypography.listPreview.copy(fontWeight = FontWeight.W500),
            color = AppTheme.colors.text.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 记忆原文小标题（整行恰为【…】的行·无圆点·字重强调）。 */
@Composable
private fun MemoryRawSubhead(line: String) {
    Text(
        line,
        style = AppTypography.secondary.copy(fontWeight = FontWeight.W600),
        color = AppTheme.colors.text.secondary,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** 记忆原文正文行（圆点 + 文字）。 */
@Composable
private fun MemoryLine(line: String) {
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Spacer(
            Modifier
                .padding(top = 6.dp)
                .size(BULLET)
                .clip(CircleShape)
                .background(colors.accent.primary.copy(alpha = BULLET_ALPHA)),
        )
        Spacer(Modifier.width(8.dp))
        Text(line, style = AppTypography.listPreview, color = colors.text.secondary)
    }
}

/** 组装记忆碎片：5 项统计在前（各有数据 / 过门槛才加）+ 10 字段结构化记忆在后（非空才加）·顺序 1:1 暖陶。 */
@Composable
private fun buildMemoryChips(stats: StructuredMemoryStats.Result, sm: StructuredMemory): List<MemoryChipData> {
    val chips = mutableListOf<MemoryChipData>()
    val neutral = AppTheme.colors.text.secondary

    stats.firstMeetDate?.let {
        chips += MemoryChipData(Icons.Filled.CalendarMonth, stringResource(R.string.profile_memory_first_meet), fmt(it, MEM_YMD), MemColor.Blue)
    }
    stats.busiestDay?.let {
        chips += MemoryChipData(
            Icons.AutoMirrored.Filled.Message,
            stringResource(R.string.profile_memory_busiest),
            stringResource(R.string.profile_memory_busiest_value, fmt(it.dateMillis, MEM_MD), it.count),
            MemColor.Green,
        )
    }
    stats.latestNightChat?.let {
        val zdt = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
        chips += MemoryChipData(
            Icons.Filled.Bedtime,
            stringResource(R.string.profile_memory_night),
            stringResource(R.string.profile_memory_night_value, zdt.hour, zdt.minute),
            MemColor.Indigo,
        )
    }
    if (stats.longestConversation > 5) {
        chips += MemoryChipData(
            Icons.AutoMirrored.Filled.Chat,
            stringResource(R.string.profile_memory_longest_conv),
            stringResource(R.string.profile_memory_longest_conv_value, stats.longestConversation),
            MemColor.Cyan,
        )
    }
    if (stats.longestStreak > 1) {
        chips += MemoryChipData(
            Icons.Filled.LocalFireDepartment,
            stringResource(R.string.profile_memory_streak),
            stringResource(R.string.profile_memory_streak_value, stats.longestStreak),
            MemColor.Orange,
        )
    }

    val smFields = listOf(
        SmFieldSpec(sm.nicknameFromChar, Icons.Filled.Favorite, R.string.profile_memory_nickname_from, MemColor.Pink),
        SmFieldSpec(sm.nicknameToChar, Icons.Filled.WavingHand, R.string.profile_memory_nickname_to, MemColor.Purple),
        SmFieldSpec(sm.insideJoke, Icons.Filled.Mood, R.string.profile_memory_inside_joke, MemColor.Yellow),
        SmFieldSpec(sm.deepestChat, Icons.Filled.Forum, R.string.profile_memory_deepest, MemColor.Teal),
        SmFieldSpec(sm.impressionOfUser, Icons.Filled.Visibility, R.string.profile_memory_impression, MemColor.Mint),
        SmFieldSpec(sm.sharedLikes, Icons.Filled.MusicNote, R.string.profile_memory_shared_likes, MemColor.Red),
        SmFieldSpec(sm.learnedPhrase, Icons.Filled.Bolt, R.string.profile_memory_learned, MemColor.Brown),
        SmFieldSpec(sm.importantPromise, Icons.Filled.Handshake, R.string.profile_memory_promise, MemColor.Blue),
        SmFieldSpec(sm.firstConflict, Icons.Filled.Bolt, R.string.profile_memory_conflict, neutral),
        SmFieldSpec(sm.comfortStyle, Icons.Filled.AutoAwesome, R.string.profile_memory_comfort, MemColor.Green),
    )
    for (f in smFields) {
        if (f.value.isNotEmpty()) chips += MemoryChipData(f.icon, stringResource(f.titleRes), f.value, f.color)
    }
    return chips
}
