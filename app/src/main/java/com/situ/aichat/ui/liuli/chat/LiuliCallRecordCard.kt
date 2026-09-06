package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.outlined.Info
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
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.voicecall.voiceCallDurationText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 琉璃通话记录卡（图纸 2026-09-05 卷二C §4.9）：236 纸白小行 = 圆 30 图标块 +「语音通话」+ 副
 * 「{时长} · {起始时间}」；点卡展开逐轮转写（发丝线下），超 [DEFAULT_VISIBLE_COUNT] 条给「查看全部」，
 * 卡底可长出琥珀「语音设置」尾巴。
 *
 * 折叠 / 展开 / 全部 / 尾巴门控**机制逐条照抄**暖陶 `CallRecordCardBubble`（F13）：`expanded` / `showAll`
 * 两态随 [data] 重置、chevron 旋 90、折叠态整卡 cd、尾巴只在调用方判定「有过失声且仍没修好」时给；
 * `ui/voicecall` 整目录零改（只 import 纯函数 [voiceCallDurationText]）。
 */
@Composable
internal fun LiuliCallRecordCard(
    data: CallRecordData,
    characterName: String,
    characterAvatarPath: String?,
    userName: String,
    userAvatarPath: String?,
    modifier: Modifier = Modifier,
    showVoiceSetupHint: Boolean = false,
    onOpenVoiceSetup: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    var expanded by remember(data) { mutableStateOf(false) }
    var showAll by remember(data) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (expanded) CHEVRON_OPEN else 0f, label = "liuliCallRecordChevron")
    val collapsedCd = stringResource(R.string.a11y_call_record_bubble, voiceCallDurationText(data.duration.toLong()))
    val startTime = remember(data.startTime) { liuliFormatCallStartTime(data.startTime) }

    LiuliCard(
        width = LiuliChatGeometry.cardWidth,
        // 展开态有意不盖 cd——保留合并转写可读（照抄 F13 的 iOS 超集口径）。
        modifier = modifier.semantics { if (!expanded) contentDescription = collapsedCd },
        // 复核 R1 🟡-2：点击挂在卡壳的圆角裁切之内（ripple 不出圆角）。
        onClick = {
            expanded = !expanded
            if (!expanded) showAll = false
        },
        onClickLabel = stringResource(
            if (expanded) R.string.a11y_call_record_collapse else R.string.a11y_call_record_expand,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CARD_SIDE, vertical = ROW_VERTICAL),
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(ICON_CIRCLE)
                    .clip(CircleShape)
                    .background(colors.accent.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Phone,
                    contentDescription = null,
                    tint = colors.accent.onContainer,
                    modifier = Modifier.size(ICON_SIZE),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.voice_call_record_title),
                    style = AppTypography.label,
                    color = colors.text.primary,
                )
                Text(
                    "${voiceCallDurationText(data.duration.toLong())} · $startTime",
                    // 11.5sp（§4.9）= `settingsRowValue` 这一枚字阶（设计语言 §2 梯子含 11.5）。
                    style = AppTypography.settingsRowValue,
                    color = colors.text.secondary,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.text.tertiary,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        if (expanded) {
            LiuliCardHairline()
            LiuliCallTranscript(
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
                style = AppTypography.secondary,
                color = colors.accent.text,
                modifier = Modifier.padding(start = CARD_SIDE, end = CARD_SIDE, bottom = SECTION_BOTTOM),
            )
        }
        if (showVoiceSetupHint) {
            LiuliCardHairline()
            LiuliVoiceSetupTail(onClick = { onOpenVoiceSetup?.invoke() })
        }
    }
}

/** 逐轮转写（F13 机制照抄：默认 10 条 + 「查看全部 N 条对话」；用户行加 sunken 底区分方向）。 */
@Composable
private fun LiuliCallTranscript(
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
    Column(
        modifier = Modifier.padding(start = CARD_SIDE, end = CARD_SIDE, top = SECTION_TOP, bottom = SECTION_BOTTOM),
        verticalArrangement = Arrangement.spacedBy(TRANSCRIPT_GAP),
    ) {
        visible.forEach { entry ->
            val isUser = entry.role == "user"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(LiuliShapes.small)
                    .then(if (isUser) Modifier.background(colors.surface.sunken) else Modifier)
                    .padding(horizontal = TRANSCRIPT_PAD_SIDE, vertical = TRANSCRIPT_PAD_VERTICAL),
                horizontalArrangement = Arrangement.spacedBy(TRANSCRIPT_GAP),
                verticalAlignment = Alignment.Top,
            ) {
                CharacterAvatar(
                    name = if (isUser) userName else characterName,
                    avatarPath = if (isUser) userAvatarPath else characterAvatarPath,
                    size = TRANSCRIPT_AVATAR,
                )
                Text(
                    entry.text,
                    style = AppTypography.secondary,
                    color = colors.text.primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (!showAll && all.size > DEFAULT_VISIBLE_COUNT) {
            Text(
                stringResource(R.string.voice_call_record_view_all, all.size),
                style = AppTypography.secondary,
                color = colors.accent.text,
                modifier = Modifier.fillMaxWidth().clickable { onShowAll() }.padding(top = SHOW_ALL_TOP),
            )
        }
    }
}

/** 琥珀「语音设置」尾巴（F13 照抄：整行自己 clickable、48dp 触达、不并入折叠 cd）。 */
@Composable
private fun LiuliVoiceSetupTail(onClick: () -> Unit) {
    val colors = AppTheme.colors
    val label = stringResource(R.string.call_record_tts_failure_hint)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LiuliChatGeometry.touchTarget)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .padding(horizontal = CARD_SIDE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TAIL_GAP),
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = colors.status.onWarning,
            modifier = Modifier.size(TAIL_ICON),
        )
        Text(label, style = AppTypography.caption, color = colors.status.onWarning, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.status.onWarning.copy(alpha = TAIL_CHEVRON_ALPHA),
            modifier = Modifier.size(TAIL_CHEVRON),
        )
    }
}

/**
 * ISO-8601 →「M月d日 HH:mm」（**重打**暖陶 `CallRecordCardBubble.formatStartTime` 同值·那侧是 private·
 * 两侧注释互指）；解析失败原样返回。模板只含数字/字面量，用 `Locale.ROOT` 恒输出 ASCII 数字。
 */
internal fun liuliFormatCallStartTime(iso: String): String =
    runCatching {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).format(LIULI_CALL_START_FORMATTER)
    }.getOrDefault(iso)

private val LIULI_CALL_START_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.ROOT)

/** 折叠 / 展开的转写默认条数（照抄暖陶 `DEFAULT_VISIBLE_COUNT`）。 */
private const val DEFAULT_VISIBLE_COUNT = 10

/** 落值（§4.9 + 对版稿 `.callrow`·孤值即打回）。 */
private const val CHEVRON_OPEN = 90f
private val CARD_SIDE = 14.dp
private val ROW_VERTICAL = 12.dp
private val ROW_GAP = 10.dp
private val ICON_CIRCLE = 30.dp
private val ICON_SIZE = 15.dp
private val SECTION_TOP = 8.dp
private val SECTION_BOTTOM = 12.dp
private val TRANSCRIPT_GAP = 6.dp
private val TRANSCRIPT_PAD_SIDE = 6.dp
private val TRANSCRIPT_PAD_VERTICAL = 4.dp
private val TRANSCRIPT_AVATAR = 24.dp
private val SHOW_ALL_TOP = 4.dp
private val TAIL_GAP = 7.dp
private val TAIL_ICON = 13.dp
private val TAIL_CHEVRON = 14.dp
private const val TAIL_CHEVRON_ALPHA = 0.7f
