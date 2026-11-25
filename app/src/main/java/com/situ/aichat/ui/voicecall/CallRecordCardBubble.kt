package com.situ.aichat.ui.voicecall

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.model.CallRecordData
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Default collapsed/expanded transcript line count before "查看全部" (= iOS `defaultVisibleCount`). */
private const val DEFAULT_VISIBLE_COUNT = 10

// 模板只含数字/字面量，用 Locale.ROOT 恒输出 ASCII 数字（避免非拉丁数字系统区域的乱码；通话记录时间戳）。
private val startTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.ROOT)

/**
 * 通话记录卡片气泡（行为 1:1 iOS `CallRecordBubbleView`：折叠/展开/查看全部/语义不变）。
 *
 * Fable-5 换装（契约 §2 卡片统一口径·≤3 常驻彩故绿色退场）：raised 纸壳 + 16dp + 发丝描边；phone 图标
 * 走陶土玫功能深档 accent.text（与线下卡同语言）；时长 pill=sunken 底 11sp tnum；时间戳 11sp tnum
 * text.secondary。居中显示（通话是双方共同事件，不归某一方）。默认折叠显「查看通话记录」；展开默认显
 * [DEFAULT_VISIBLE_COUNT] 条，超出给「查看全部 N 条对话」。每行：mini 头像 + 文字，用户行加 sunken 底以区分角色。
 *
 * **纯展示**：transcript / 时长 / 起始时间均来自解析好的 [data]；头像/名字由调用方注入（用户与角色两套）。
 */
@Composable
fun CallRecordCardBubble(
    data: CallRecordData,
    characterName: String,
    characterAvatarPath: String?,
    userName: String,
    userAvatarPath: String?,
    modifier: Modifier = Modifier,
    // VU3 §4.3：本通有过失声（data.hadTtsFailure）且当前仍没修好时，卡底长出可点琥珀尾巴深链语音设置。
    // 两参默认关 = 既有调用零改（逐像素同旧·B4）。
    showVoiceSetupHint: Boolean = false,
    onOpenVoiceSetup: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    var expanded by remember(data) { mutableStateOf(false) }
    var showAll by remember(data) { mutableStateOf(false) }
    val shape = AppShapes.medium
    val chevronRotation by animateFloatAsState(if (expanded) 90f else 0f, label = "callRecordChevron")
    val collapsedCd = stringResource(R.string.a11y_call_record_bubble, voiceCallDurationText(data.duration.toLong()))

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(colors.surface.raised)
                .border(1.dp, colors.surface.stroke, shape)
                .clickable(
                    onClickLabel = stringResource(
                        if (expanded) R.string.a11y_call_record_collapse else R.string.a11y_call_record_expand,
                    ),
                ) {
                    expanded = !expanded
                    if (!expanded) showAll = false
                }
                // P1-17 扩围（勘察发现缺口）：折叠态整卡 cd=iOS CallRecordBubbleView.swift:74-75
                // combine+「语音通话记录，时长%@」逐字；展开态有意不盖 cd——iOS combine 连展开转写一并
                // 替换丢失，安卓保留合并转写可读=信息超集（铁律#1 不降级）。
                .semantics { if (!expanded) contentDescription = collapsedCd }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 标题行：phone + 语音通话 + 时长 pill + chevron
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Filled.Phone,
                    contentDescription = null,
                    tint = colors.accent.text,
                    modifier = Modifier.padding(end = 2.dp),
                )
                Text(
                    stringResource(R.string.voice_call_record_title),
                    style = typography.label,
                    color = colors.text.primary,
                    modifier = Modifier.weight(1f),
                )
                DurationPill(seconds = data.duration)
                Icon(
                    imageVector = AppActionIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.text.tertiary,
                    modifier = Modifier.rotate(chevronRotation),
                )
            }
            // 副标题：起始时间（时间戳=11sp tnum·功能小字走 secondary）
            Text(
                formatStartTime(data.startTime),
                style = typography.captionNumeric,
                color = colors.text.secondary,
            )

            if (expanded) {
                TranscriptSection(
                    data = data,
                    showAll = showAll,
                    onShowAll = { showAll = true },
                    characterName = characterName,
                    characterAvatarPath = characterAvatarPath,
                    userName = userName,
                    userAvatarPath = userAvatarPath,
                )
            } else {
                Text(
                    stringResource(R.string.voice_call_record_view_transcript),
                    style = typography.secondary,
                    color = colors.accent.text,
                )
            }

            // VU3 琥珀尾巴（§4.3·卡内 Column 最末·两态皆在最后子项之后）：自愈显示由调用方裁（仍没修好才传 true）。
            if (showVoiceSetupHint) {
                VoiceSetupTailRow(onClick = { onOpenVoiceSetup?.invoke() })
            }
        }
    }
}

/**
 * VU3 戏外工程提示尾巴（§4.3）：卡底 1px 分线 + 一行可点琥珀提示深链语音设置。整行自己 clickable（内层优先
 * 消费·同 [TranscriptSection]「查看全部」先例），独立于卡的折叠点击、不并入折叠 cd。全 token 深浅双模。
 */
@Composable
private fun VoiceSetupTailRow(onClick: () -> Unit) {
    val colors = AppTheme.colors
    val label = stringResource(R.string.call_record_tts_failure_hint)
    HorizontalDivider(thickness = 1.dp, color = colors.surface.stroke)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = colors.status.onWarning,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            style = AppTheme.typography.caption,
            color = colors.status.onWarning,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = AppActionIcons.ChevronRight,
            contentDescription = null,
            tint = colors.status.onWarning.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun DurationPill(seconds: Int) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .clip(AppShapes.full)
            .background(colors.surface.sunken)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            formatDuration(seconds),
            style = AppTheme.typography.captionNumeric,
            color = colors.text.secondary,
        )
    }
}

@Composable
private fun TranscriptSection(
    data: CallRecordData,
    showAll: Boolean,
    onShowAll: () -> Unit,
    characterName: String,
    characterAvatarPath: String?,
    userName: String,
    userAvatarPath: String?,
) {
    val colors = AppTheme.colors
    val all = data.transcript
    val visible = if (showAll || all.size <= DEFAULT_VISIBLE_COUNT) all else all.take(DEFAULT_VISIBLE_COUNT)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        visible.forEach { entry ->
            val isUser = entry.role == "user"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.small)
                    .then(if (isUser) Modifier.background(colors.surface.sunken) else Modifier)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                CharacterAvatar(
                    name = if (isUser) userName else characterName,
                    avatarPath = if (isUser) userAvatarPath else characterAvatarPath,
                    size = 24.dp,
                )
                Text(
                    entry.text,
                    style = AppTheme.typography.body,
                    color = colors.text.primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (!showAll && all.size > DEFAULT_VISIBLE_COUNT) {
            Text(
                stringResource(R.string.voice_call_record_view_all, all.size),
                style = AppTheme.typography.secondary,
                color = colors.accent.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowAll() }
                    .padding(top = 4.dp),
            )
        }
    }
}

/** `mm:ss` of `max(seconds, 0)` (= iOS `formattedDuration`). */
private fun formatDuration(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

/** ISO-8601 → "M月d日 HH:mm" (= iOS `formattedStartTime` via `DateFormatters.dateMDHM`); raw on failure. */
private fun formatStartTime(iso: String): String =
    runCatching {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).format(startTimeFormatter)
    }.getOrDefault(iso)
