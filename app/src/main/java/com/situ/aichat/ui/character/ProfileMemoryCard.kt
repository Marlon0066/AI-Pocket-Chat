package com.situ.aichat.ui.character

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.profile.StructuredMemoryStats
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// 资料页成长卡 · 共同记忆卡族（从 ProfileGrowthCards.kt 按卡族纯搬拆出，未改一像素）。
// 公用件 fmt() / MemColor 留在 ProfileGrowthCards.kt（同包 internal 可见）。

private val memYmd = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.getDefault())
private val memMd = DateTimeFormatter.ofPattern("M/d", Locale.getDefault())

// 记忆原文折叠态预览行数（锁定常量·图纸 §4.3/§9）。
private const val PREVIEW_LINES = 4

private data class MemoryChipData(val icon: ImageVector, val title: String, val value: String, val color: Color)
private class SmFieldSpec(val value: String, val icon: ImageVector, val titleRes: Int, val color: Color)

// ── 共同记忆卡（5 项档案统计 chip + 10 字段结构化记忆 chip + 记忆原文；全空时提示）──────────────

/**
 * [guardBlocked]/[organizing]/[onOrganizeNow]（记忆护栏第二层 MG-U2·契约 FABLE5_MEMORY_GUARD_UI_PROPOSAL §4/§5）：
 * 该角色记忆整理遇阻时在卡头下方插入琥珀状态条 + 「立即整理」；三参带默认值，不传时渲染与旧版逐像素一致。
 * organizing 单独成立也显示（整理进行中遇阻旗标可能已被成功路径清除，忙态条要撑到跑完）。
 */
@Composable
internal fun SharedMemoryCard(
    stats: StructuredMemoryStats.Result,
    memory: StructuredMemory,
    memorySummary: String,
    modifier: Modifier = Modifier,
    guardBlocked: Boolean = false,
    organizing: Boolean = false,
    onOrganizeNow: () -> Unit = {},
    /** 记忆手动编辑入口（图纸 2026-09-01 件③·D-1）：null（默认）= 不渲染入口，卡面与旧版逐像素一致。 */
    onEditMemory: (() -> Unit)? = null,
    /** 整理进行中 → 编辑入口暗且禁点（防编辑与自动整理同时开工）。 */
    editInProgressBlocked: Boolean = false,
) {
    val chips = buildMemoryChips(stats, memory)
    // 记忆原文（V-1·图纸 §4.3/§4.4）：整段不拆·拆行 / 去行首项目符号 / 滤空·不再调用 MemorySummarySections.parse。
    val display = MemoryRawFormatter.lines(memorySummary)
    // 弹窗态用 rememberSaveable 抗 LazyColumn item 回收（P1-31；iOS 把这俩 @State 提升在屏级故天然存活）。
    // chip 本体含 ImageVector 不可 save → 存标题、从当前 chips 反查（标题在 5 统计+10 字段内唯一）。
    var detailChipTitle by rememberSaveable { mutableStateOf<String?>(null) }
    // 记忆原文就地展开态（V-1·替代旧「查看全部」弹窗·抗 item 回收）。
    var memoryExpanded by rememberSaveable { mutableStateOf(false) }
    val detailChip = chips.firstOrNull { it.title == detailChipTitle }

    ProfileCard(modifier) {
        CardSectionHeader(Icons.Filled.AutoAwesome, MaterialTheme.colorScheme.primary, stringResource(R.string.profile_memory_title))
        Spacer(Modifier.size(12.dp))

        if (guardBlocked || organizing) {
            MemoryGuardStrip(organizing = organizing, onOrganizeNow = onOrganizeNow)
            Spacer(Modifier.size(12.dp))
        }

        if (chips.isEmpty() && memorySummary.isEmpty()) {
            Text(
                stringResource(R.string.profile_memory_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            return@ProfileCard
        }

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

        // 记忆原文区（V-1·就地展开·整段不拆·图纸 §4.3）。
        if (display.isNotEmpty()) {
            if (chips.isNotEmpty()) {
                Spacer(Modifier.size(12.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(Modifier.size(12.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.profile_memory_raw_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 编辑入口（D-1）：空记忆无入口（与记忆原文区同门槛）；整理进行中暗且禁点。
                if (onEditMemory != null && display.isNotEmpty()) {
                    AppButton(
                        onClick = onEditMemory,
                        style = AppButtonStyle.Text,
                        enabled = !editInProgressBlocked,
                        contentPadding = PaddingValues(vertical = 4.dp),
                        modifier = Modifier.alpha(if (editInProgressBlocked) 0.45f else 1f),
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(
                            stringResource(R.string.profile_memory_edit_action),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.size(6.dp))
            // 折叠态出前 PREVIEW_LINES 行·展开态整段（随外层滚·无嵌套滚动·E3）；
            // 整行恰为【…】的行当小标题（D-D·显示层美化非分节），其余走圆点正文行。
            val shown = if (memoryExpanded) display else display.take(PREVIEW_LINES)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shown.forEach { line ->
                    if (MemoryRawFormatter.isSectionTitle(line)) MemoryRawSubhead(line) else MemoryLine(line)
                }
            }
            // 就地展开/收起（复用「查看全部」按钮样式）——超过预览行数才出（E2 只一条标题时不出）。
            if (display.size > PREVIEW_LINES) {
                AppButton(onClick = { memoryExpanded = !memoryExpanded }, style = AppButtonStyle.Text, contentPadding = PaddingValues(vertical = 4.dp)) {
                    Text(
                        stringResource(if (memoryExpanded) R.string.profile_memory_collapse else R.string.profile_memory_expand),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }

    detailChip?.let { chip ->
        AppDialog(
            onDismissRequest = { detailChipTitle = null },
            title = chip.title,
            body = chip.value,
            confirmText = stringResource(R.string.action_close),
            onConfirm = { detailChipTitle = null },
        )
    }
}

/**
 * 记忆整理遇阻状态条（MG-U2·锁定规格=契约 §4/mockup v1.1）：r12 琥珀淡底块，圆形叹号 + 双行文案 + 描边胶囊按钮。
 * 色 token = status.warningContainer（底）/ status.onWarning（字/边/图标·与经济金物理隔离）；不用红色不说「失败/丢失」。
 * 忙态：文案换「正在整理…」、按钮「整理中」45% 置灰禁点。
 */
@Composable
private fun MemoryGuardStrip(organizing: Boolean, onOrganizeNow: () -> Unit) {
    val status = AppTheme.colors.status
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
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
                fontWeight = FontWeight.SemiBold,
                color = status.onWarning,
            )
            Spacer(Modifier.size(3.dp))
            Text(
                stringResource(if (organizing) R.string.profile_memory_guard_busy_body else R.string.profile_memory_guard_body),
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(if (organizing) R.string.profile_memory_guard_busy_action else R.string.profile_memory_guard_action),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = status.onWarning,
            maxLines = 1,
            modifier = Modifier
                .padding(top = 1.dp)
                .alpha(if (organizing) 0.45f else 1f)
                .border(1.dp, status.onWarning, CircleShape)
                .clip(CircleShape)
                .clickable(enabled = !organizing, role = Role.Button, onClick = onOrganizeNow)
                .padding(horizontal = 13.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun MemoryChipCard(chip: MemoryChipData, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(chip.color.copy(alpha = 0.06f))
            .border(0.5.dp, chip.color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(chip.icon, contentDescription = null, tint = chip.color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                chip.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = chip.color.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            chip.value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 记忆原文小标题（V-1·D-D·图纸 §4.3）：整行恰为【…】的行——无圆点·字重强调（SemiBold）·复用卡内既有 onSurfaceVariant。
 * 属显示层美化非分节（无结构依赖·优雅退化：模型漏写标题则全部走圆点正文行·不崩）。
 */
@Composable
private fun MemoryRawSubhead(line: String) {
    Text(
        line,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun MemoryLine(line: String) {
    Row(verticalAlignment = Alignment.Top) {
        Spacer(
            Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 组装记忆碎片：5 项统计在前（各有数据/过门槛才加）+ 10 字段结构化记忆在后（非空才加）。1:1 iOS buildMemoryChips 顺序。 */
@Composable
private fun buildMemoryChips(stats: StructuredMemoryStats.Result, sm: StructuredMemory): List<MemoryChipData> {
    val chips = mutableListOf<MemoryChipData>()
    val cs = MaterialTheme.colorScheme

    stats.firstMeetDate?.let {
        chips += MemoryChipData(Icons.Filled.CalendarMonth, stringResource(R.string.profile_memory_first_meet), fmt(it, memYmd), MemColor.Blue)
    }
    stats.busiestDay?.let {
        chips += MemoryChipData(
            Icons.AutoMirrored.Filled.Message,
            stringResource(R.string.profile_memory_busiest),
            stringResource(R.string.profile_memory_busiest_value, fmt(it.dateMillis, memMd), it.count),
            MemColor.Green,
        )
    }
    stats.latestNightChat?.let {
        val zdt = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
        chips += MemoryChipData(Icons.Filled.Bedtime, stringResource(R.string.profile_memory_night), stringResource(R.string.profile_memory_night_value, zdt.hour, zdt.minute), MemColor.Indigo)
    }
    if (stats.longestConversation > 5) {
        chips += MemoryChipData(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.profile_memory_longest_conv), stringResource(R.string.profile_memory_longest_conv_value, stats.longestConversation), MemColor.Cyan)
    }
    if (stats.longestStreak > 1) {
        chips += MemoryChipData(Icons.Filled.LocalFireDepartment, stringResource(R.string.profile_memory_streak), stringResource(R.string.profile_memory_streak_value, stats.longestStreak), MemColor.Orange)
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
        SmFieldSpec(sm.firstConflict, Icons.Filled.Bolt, R.string.profile_memory_conflict, cs.onSurfaceVariant),
        SmFieldSpec(sm.comfortStyle, Icons.Filled.AutoAwesome, R.string.profile_memory_comfort, MemColor.Green),
    )
    for (f in smFields) {
        if (f.value.isNotEmpty()) chips += MemoryChipData(f.icon, stringResource(f.titleRes), f.value, f.color)
    }
    return chips
}
