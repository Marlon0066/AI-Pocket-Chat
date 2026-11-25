package com.situ.aichat.ui.diary

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import com.situ.aichat.ui.designsystem.AppActionIcons
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.DiaryReactionEntity
import com.situ.aichat.data.model.DiaryVisibility
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.util.ContentImageStore
import com.situ.aichat.util.DateFormatters
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 三种日记视图模式（时间线 / 列表 / 日历）。标签走资源（zh/en）。 */
enum class DiaryViewMode(val labelRes: Int) {
    TIMELINE(R.string.diary_mode_timeline),
    LIST(R.string.diary_mode_list),
    CALENDAR(R.string.diary_mode_calendar),
}

/**
 * 日记作者筛选（U4·契约 §6.2/§6.3③）：全部 / 我的（[DiaryEntryEntity.authorCharacterUuid] 为空）/
 * TA 的信（交换日记·authorCharacterUuid 非空·含 U3 孤儿信）。三视图模式共用（F2·作用于底层条目）。
 * [matches] 为纯谓词（T1 可测）——与 U3「TA 的信」判定同一数据轴。
 */
enum class DiaryEntryFilter(val labelRes: Int) {
    ALL(R.string.diary_filter_all),
    MINE(R.string.diary_filter_mine),
    THEIRS(R.string.diary_filter_theirs);

    fun matches(entry: DiaryEntryEntity): Boolean = when (this) {
        ALL -> true
        MINE -> entry.authorCharacterUuid == null
        THEIRS -> entry.authorCharacterUuid != null
    }
}

/** 格式化 epoch 毫秒为给定 pattern（pattern 来自资源，locale 相关）。 */
internal fun formatDiaryDate(millis: Long, pattern: String, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).withZone(zone).format(Instant.ofEpochMilli(millis))

/** 交换日记作者显示：活名 / 已删角色的快照名（[isOrphan]）。null=非交换日记（用户自己的）。 */
internal data class DiaryAuthorDisplay(val name: String, val isOrphan: Boolean)

/**
 * 纯决策（U3 故人来信·§6.3 O1/O3b·internal 便于 T1）：活角色→活名（isOrphan=false）；
 * [authorCharacterUuid] 非空但活角色查不到（已删）→ 快照名 [authorNameSnapshot]，快照空/空白（v25 迁移前的老信）
 * → 通用兜底名 [orphanFallbackName]（O3b），两者皆 isOrphan=true。返回 null = 非交换日记（[authorCharacterUuid] 为空）。
 */
internal fun resolveDiaryAuthor(
    authorCharacterUuid: String?,
    authorNameSnapshot: String?,
    liveName: String?,
    orphanFallbackName: String,
): DiaryAuthorDisplay? {
    if (authorCharacterUuid == null) return null
    liveName?.let { return DiaryAuthorDisplay(it, isOrphan = false) }
    val name = authorNameSnapshot?.takeIf { it.isNotBlank() } ?: orphanFallbackName
    return DiaryAuthorDisplay(name, isOrphan = true)
}

/** [resolveDiaryAuthor] 的 @Composable 薄壳：兜底名从资源取（O3b·「一位旧友」）。 */
@Composable
internal fun diaryAuthorDisplay(
    authorCharacterUuid: String?,
    authorNameSnapshot: String?,
    liveName: String?,
): DiaryAuthorDisplay? = resolveDiaryAuthor(
    authorCharacterUuid = authorCharacterUuid,
    authorNameSnapshot = authorNameSnapshot,
    liveName = liveName,
    orphanFallbackName = stringResource(R.string.diary_exchange_orphan_fallback_name),
)

/** 交换日记作者显示（活名 / 快照名 / 兜底名）的「按条目取」便捷版（两张脸三处共用）。 */
@Composable
internal fun diaryAuthorDisplayOf(entry: DiaryEntryEntity, charactersByUuid: Map<String, CharacterEntity>): DiaryAuthorDisplay? =
    diaryAuthorDisplay(entry.authorCharacterUuid, entry.authorNameSnapshot, entry.authorCharacterUuid?.let { charactersByUuid[it]?.name })

/**
 * ③ U2：正文按段落切分（详情页段落呼吸感）。以换行分段（兼容单 `\n` 与空行 `\n\n`）·trim 去空白段。
 * 纯逻辑·T1 可测。无换行=单段；全空白优雅回退单元素（调用方已 ifEmpty 兜底占位，不会真空）。
 */
internal fun splitDiaryParagraphs(content: String): List<String> =
    content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(content.trim()) }

/** 可见性图标（仅自己可见=锁，AI 好友可见=人）。仅用 Material core 图标。 */
internal fun diaryVisibilityIcon(visibilityRaw: String): ImageVector =
    if (DiaryVisibility.fromRaw(visibilityRaw) == DiaryVisibility.PRIVATE) AppActionIcons.Lock else AppActionIcons.Person

/** 票据虚线（月分节裁切边 / 详情评论分隔·全案唯一「手账」装饰隐喻·契约 §1 手法2）。纯装饰无语义。 */
@Composable
internal fun DiaryDashedDivider(modifier: Modifier = Modifier) {
    val color = AppTheme.colors.text.tertiary.copy(alpha = 0.45f)
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
        )
    }
}

// MARK: - 图片缩略图（从内部存储路径异步解码，仿 CharacterAvatar 的解码模式）

@Composable
fun DiaryThumbnail(path: String, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    BoxWithConstraints(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(corner))
            .background(AppTheme.colors.surface.sunken),
    ) {
        // 按实际缩略图像素降采样解码（远小于存储 1024px），命中 LruCache 后列表滚动零重解。
        val targetPx = constraints.maxWidth.coerceIn(1, 2048)
        var bitmap by remember(path, targetPx) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(path, targetPx) { bitmap = ContentImageStore.load(path, targetPx) }
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// MARK: - 草稿徽章

@Composable
internal fun DraftBadge() {
    val colors = AppTheme.colors
    Text(
        text = stringResource(R.string.diary_draft),
        style = AppTheme.typography.caption,
        color = colors.accent.onContainer,
        modifier = Modifier
            .clip(AppTheme.shapes.full)
            .background(colors.accent.container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// MARK: - 紧凑行（列表视图）

@Composable
fun DiaryEntryRowCompact(entry: DiaryEntryEntity, commentCount: Int, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val rowCd = diaryEntryA11yDescription(entry, commentCount)
    Row(
        modifier = modifier.semantics { contentDescription = rowCd }.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(diaryMoodTint(entry.moodEmoji) ?: colors.surface.sunken),
            contentAlignment = Alignment.Center,
        ) {
            Text(entry.moodEmoji ?: "📖", style = AppTheme.typography.body)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.content.ifEmpty { stringResource(R.string.diary_no_content) },
                style = AppTheme.typography.listPreview,
                color = colors.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    formatDiaryDate(entry.timestamp, stringResource(R.string.diary_fmt_compact)),
                    style = AppTheme.typography.captionNumeric,
                    color = colors.text.secondary,
                )
                if (entry.isDraft) DraftBadge()
                if (commentCount > 0) {
                    Text(
                        stringResource(R.string.diary_comments_header, commentCount),
                        style = AppTheme.typography.caption,
                        color = colors.text.secondary,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            diaryVisibilityIcon(entry.visibilityRaw),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = colors.text.secondary,
        )
    }
}

// MARK: - 角色点赞行（R3·详情页互动区）

/** 「♥ + 叠放小头像（≤5）+ N 位觉得暖」柔和胶囊条。心形/头像装饰，语义靠计数文案。 */
@Composable
internal fun DiaryReactionRow(
    reactions: List<DiaryReactionEntity>,
    charactersByUuid: Map<String, CharacterEntity>,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.sunken)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "♥",
            style = AppTheme.typography.secondary,
            color = colors.accent.text,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
            reactions.take(5).forEach { r ->
                val character = charactersByUuid[r.characterUuid]
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, colors.surface.sunken, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CharacterAvatar(name = character?.name ?: "?", avatarPath = character?.avatarPath, size = 22.dp)
                }
            }
        }
        Text(
            stringResource(R.string.diary_reactions_row, reactions.size),
            style = AppTheme.typography.caption,
            color = colors.text.secondary,
        )
    }
}

// MARK: - 评论行

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiaryCommentRow(
    authorName: String,
    authorAvatarPath: String?,
    content: String,
    timestampMillis: Long,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    // P0-14：长按评论 → 删除菜单 → 确认（镜像 MomentCommentRow 手势/弹窗结构）。onClick 空操作。
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { menuExpanded = true }),
            verticalAlignment = Alignment.Top,
        ) {
            CharacterAvatar(name = authorName, avatarPath = authorAvatarPath, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(authorName, style = AppTheme.typography.label, color = colors.text.primary)
                Text(content, style = AppTheme.typography.listPreview, color = colors.text.primary)
                Text(
                    diaryCommentRelativeTime(timestampMillis),
                    style = AppTheme.typography.caption,
                    color = colors.text.secondary,
                )
            }
        }
        AppMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }) {
            AppMenuItem(
                text = stringResource(R.string.diary_comment_delete),
                onClick = { menuExpanded = false; showDeleteConfirm = true },
                danger = true,
            )
        }
    }
    if (showDeleteConfirm) {
        AppDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.diary_comment_delete_title),
            body = stringResource(R.string.diary_comment_delete_message),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { showDeleteConfirm = false; onDelete() },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/** 评论相对时间（diary-2：刚刚 / X分钟前 / 昨天…·原 :306–315 逐字搬）。 */
@Composable
internal fun diaryCommentRelativeTime(timestampMillis: Long): String =
    // diary-2：评论时间用相对时间（刚刚/X分钟前/昨天…）。
    DateFormatters.relativeTimeString(
        timestampMillis,
        System.currentTimeMillis(),
        DateFormatters.RelativeTimeStrings(
            justNow = stringResource(R.string.relative_time_just_now),
            minutesAgo = stringResource(R.string.relative_time_minutes_ago),
            hoursAgo = stringResource(R.string.relative_time_hours_ago),
            yesterday = stringResource(R.string.relative_time_yesterday),
        ),
    )
