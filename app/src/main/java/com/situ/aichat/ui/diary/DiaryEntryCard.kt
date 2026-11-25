package com.situ.aichat.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.model.DiaryVisibility
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 方向 C（书信画报融合派）日记卡片：按角色分三种差异化原型——
 * 1) **TA 的来信**（[authorName] != null）：信笺纸底 + 顶部双线抬头 + 楷体作者头像栏 + 楷体正文 + 右下角作者单字圆印；
 * 2) **宠物日记**（[entry].isPetDiary）：柔和卡面 + 右上角极淡爪印水印 + 画报日期头 + 迷你邮票；
 * 3) **我的日常日记**：杂志感日期天头（大号等宽日号 + 今天/昨天/周几 · HH:mm）+ 右上角迷你心情邮票 + 自适应配图
 *    （1 张微斜拍立得 / 2-3+ 张规整拼图）+ 减噪页脚。
 * 时间线与日历选中日共用；琉璃脸通过 [surface] / [letterSurface] 材质外给。
 */
@Composable
fun DiaryEntryCard(
    entry: DiaryEntryEntity,
    commentCount: Int,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
    reactionCount: Int = 0,
    /** R3：草稿一键发布（非 null 且 entry.isDraft 时页脚出「发布」快捷钮）。 */
    onPublish: (() -> Unit)? = null,
    /** R4：交换日记信笺形态——作者角色名（非 null = TA 写的信：信笺抬头 + 楷体摘录 + 圆印）。 */
    authorName: String? = null,
    /** U3：孤儿信（作者角色已删·§6.3 O2）——true 时头后缀「· 故友的信」淡标。 */
    isOrphan: Boolean = false,
    /** 卡面材质（琉璃 2.0 卷六·一「材质外给」：默认 = 暖陶承托，琉璃传半透明卡）。 */
    surface: Modifier = Modifier.appCardSurface(cornerRadius = 18.dp),
    /** 信笺卡专用材质（非 null 时用于 TA 的来信，如琉璃磨砂信纸）。 */
    letterSurface: Modifier? = null,
    /** 点击面（排在材质裁切之后 → ripple 不漏圆角）。暖陶把点击放在外层 [modifier] 里，不传本形参。 */
    interaction: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val images = entry.imagePaths
    val cardCd = diaryEntryA11yDescription(entry, commentCount, reactionCount, authorName, isOrphan)
    val isLetter = authorName != null
    val resolvedSurface = if (isLetter && letterSurface != null) letterSurface else surface
    val stationeryWash = if (isLetter && letterSurface == null) {
        colors.accent.container.copy(alpha = if (colors.isDark) 0.08f else 0.14f)
    } else {
        Color.Transparent
    }

    Box(
        modifier = modifier
            .semantics { contentDescription = cardCd }
            .fillMaxWidth()
            .then(resolvedSurface)
            .background(stationeryWash)
            .then(interaction),
    ) {
        if (entry.isPetDiary && !isLetter) {
            DiaryPawWatermark(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 14.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            if (isLetter) {
                DiaryLetterTopRule()
                LetterCardHeader(
                    authorName = authorName,
                    isOrphan = isOrphan,
                    timestamp = entry.timestamp,
                )
            } else {
                EditorialCardHeader(
                    timestamp = entry.timestamp,
                    moodEmoji = entry.moodEmoji,
                    isPetDiary = entry.isPetDiary,
                )
            }

            Text(
                text = entry.content.ifEmpty { stringResource(R.string.diary_no_content) },
                style = if (isLetter) AppTheme.typography.kaiQuote else AppTheme.typography.listPreview,
                color = colors.text.primary,
                maxLines = if (preview) 4 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )

            if (images.isNotEmpty()) {
                DiaryCardPhotoBlock(images)
            }

            DiaryCardFooter(
                entry = entry,
                commentCount = commentCount,
                reactionCount = reactionCount,
                onPublish = onPublish,
                authorName = authorName,
            )
        }
    }
}

/** 杂志感日期天头（我的日常日记 & 宠物日记共用）：左侧大号等宽日号 + 星期与时刻，右侧迷你心情邮票。 */
@Composable
private fun EditorialCardHeader(
    timestamp: Long,
    moodEmoji: String?,
    isPetDiary: Boolean,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                formatDiaryDate(timestamp, stringResource(R.string.diary_fmt_day_number)),
                style = AppTheme.typography.titleLarge.copy(
                    fontSize = 26.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
                color = colors.text.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        rememberDiaryDayLabel(timestamp),
                        style = AppTheme.typography.secondary,
                        color = colors.text.secondary,
                    )
                    if (isPetDiary) {
                        Text(
                            stringResource(R.string.diary_pet_diary),
                            style = AppTheme.typography.caption,
                            color = colors.accent.text,
                        )
                    }
                }
                Text(
                    formatDiaryDate(timestamp, "HH:mm"),
                    style = AppTheme.typography.captionNumeric,
                    color = colors.text.tertiary,
                )
            }
        }
        moodEmoji?.takeIf { it.isNotEmpty() }?.let { emoji ->
            DiaryMiniMoodStamp(emoji = emoji, timestamp = timestamp)
        }
    }
}

/** TA 的来信卡头：圆角字母徽章 + 楷体作者名（孤儿附淡标）+ 右侧日期时刻。 */
@Composable
private fun LetterCardHeader(
    authorName: String,
    isOrphan: Boolean,
    timestamp: Long,
) {
    val colors = AppTheme.colors
    val initial = authorName.trim().lastOrNull()?.toString().orEmpty()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(colors.accent.container),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = AppTheme.typography.kaiQuote.copy(fontSize = 13.sp, lineHeight = 14.sp),
                color = colors.accent.onContainer,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                stringResource(R.string.diary_exchange_card_author, authorName),
                style = AppTheme.typography.kaiQuote,
                color = colors.accent.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isOrphan) {
                Text("·", style = AppTheme.typography.caption, color = colors.text.secondary)
                Text(
                    stringResource(R.string.diary_exchange_orphan_label),
                    style = AppTheme.typography.caption,
                    color = colors.text.secondary,
                    maxLines = 1,
                )
            }
        }
        Text(
            "${rememberDiaryDayLabel(timestamp)} · ${formatDiaryDate(timestamp, "HH:mm")}",
            style = AppTheme.typography.captionNumeric,
            color = colors.text.tertiary,
        )
    }
}

/** 自适应配图块：1 张 → 微斜拍立得；2 张 → 规整双图；3+ 张 → 规整三图（超 3 张在第 3 张右下叠 +N）。 */
@Composable
private fun DiaryCardPhotoBlock(images: List<String>) {
    when (images.size) {
        1 -> DiaryPolaroidPreview(path = images[0], imageHeight = 148.dp)
        2 -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            images.forEach { path ->
                DiaryThumbnail(
                    path = path,
                    modifier = Modifier.weight(1f).height(112.dp),
                    corner = 10.dp,
                )
            }
        }
        else -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val extra = images.size - 3
            images.take(3).forEachIndexed { index, path ->
                Box(modifier = Modifier.weight(1f).height(94.dp)) {
                    DiaryThumbnail(
                        path = path,
                        modifier = Modifier.fillMaxWidth().height(94.dp),
                        corner = 10.dp,
                    )
                    if (index == 2 && extra > 0) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.42f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "+$extra",
                                style = AppTheme.typography.label,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 减噪页脚（时刻已上移天头，避免底部堆砌）：草稿 / 发布 / 暖心 / 评论 / AI / 可见性 + 来信圆印。 */
@Composable
private fun DiaryCardFooter(
    entry: DiaryEntryEntity,
    commentCount: Int,
    reactionCount: Int,
    onPublish: (() -> Unit)?,
    authorName: String?,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (entry.isDraft) DraftBadge()
        if (entry.isDraft && onPublish != null) {
            Text(
                stringResource(R.string.diary_compose_publish),
                style = AppTheme.typography.caption,
                color = colors.accent.onContainer,
                modifier = Modifier
                    .clip(AppTheme.shapes.full)
                    .background(colors.accent.container)
                    .clickable(onClickLabel = stringResource(R.string.diary_compose_publish)) { onPublish() }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        if (reactionCount > 0) {
            Text(
                "♥ $reactionCount",
                style = AppTheme.typography.captionNumeric,
                color = colors.accent.text,
            )
        }
        if (commentCount > 0) {
            Text(
                stringResource(R.string.diary_comments_header, commentCount),
                style = AppTheme.typography.caption,
                color = colors.text.secondary,
            )
        }
        if (entry.isAutoGenerated && authorName == null) {
            Text(
                stringResource(R.string.diary_ai_label),
                style = AppTheme.typography.caption,
                color = colors.text.tertiary,
            )
        }
        Spacer(Modifier.weight(1f))
        if (authorName != null) {
            DiaryAuthorChopSeal(name = authorName, size = 22.dp)
        } else {
            Icon(
                diaryVisibilityIcon(entry.visibilityRaw),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = colors.text.tertiary,
            )
        }
    }
}

/** 今天 / 昨天 / 周几（日记天头 + 交换日记复用）。 */
@Composable
internal fun rememberDiaryDayLabel(timestamp: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
        today -> stringResource(R.string.diary_today)
        today.minusDays(1) -> stringResource(R.string.diary_yesterday)
        else -> formatDiaryDate(timestamp, stringResource(R.string.diary_fmt_weekday), zone)
    }
}

/**
 * P1-16：日记卡/紧凑行整卡 TalkBack 文案（原 DiaryUiComponents 迁入·文案与合并逻辑不变）。日期锚句 +
 * 草稿/宠物/AI/心情 + 正文全文 + 图片数 + 评论数 + 可见性，null/空字段跳过，全角逗号分隔；消费方
 * combinedClickable 已 mergeDescendants（卡内绝不再加），本 cd 在同节点上替换合并杂音。
 */
@Composable
internal fun diaryEntryA11yDescription(
    entry: DiaryEntryEntity,
    commentCount: Int,
    reactionCount: Int = 0,
    authorName: String? = null,
    isOrphan: Boolean = false,
): String {
    val date = formatDiaryDate(entry.timestamp, stringResource(R.string.diary_fmt_ymd))
    return buildList {
        add(stringResource(R.string.a11y_diary_entry_date, date))
        authorName?.let { add(stringResource(R.string.diary_exchange_card_author, it)) }
        if (isOrphan) add(stringResource(R.string.diary_exchange_orphan_label))
        if (entry.isDraft) add(stringResource(R.string.diary_draft))
        if (entry.isPetDiary) add(stringResource(R.string.diary_pet_diary))
        if (entry.isAutoGenerated) add(stringResource(R.string.diary_ai_generated))
        entry.moodEmoji?.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.a11y_diary_mood, it)) }
        entry.moodText?.takeIf { it.isNotEmpty() }?.let { add(it) }
        add(entry.content.ifEmpty { stringResource(R.string.diary_no_content) })
        entry.imagePaths.size.takeIf { it > 0 }?.let { add(stringResource(R.string.a11y_diary_images, it)) }
        if (reactionCount > 0) add(stringResource(R.string.a11y_diary_reactions, reactionCount))
        if (commentCount > 0) add(stringResource(R.string.a11y_diary_comments, commentCount))
        add(
            stringResource(
                if (DiaryVisibility.fromRaw(entry.visibilityRaw) == DiaryVisibility.PRIVATE) {
                    R.string.diary_visibility_private
                } else {
                    R.string.diary_visibility_open
                },
            ),
        )
    }.joinToString("，")
}
