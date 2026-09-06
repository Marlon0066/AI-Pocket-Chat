package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.chat.VoiceDraftState
import com.situ.aichat.ui.chat.VoiceTranscriptFailure
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliSpinner
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.LiuliGlassSpec
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.liuli.page.liuliFootprint
import com.situ.aichat.ui.liuli.page.liuliTouchHeight
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 草稿条落值（图纸 2026-09-05 卷二C §3.2「C6 锁定项」+ 对版稿 B 甲 `.draft`·孤值即打回）。 */
// 56 = 两行（题 + 识别文字）的草稿条；录音条单行仍 44（`inputPieceSize`）——「同壳不同高」
// （R2 裁 D-C6-3·图纸 §4.12 已改写）。
private val BAR_HEIGHT = 56.dp
private val DISC = 44.dp
private val DISC_ICON = 20.dp
private const val DISC_ALPHA = 0.55f
private val WAVE_WIDTH = 44.dp
private val WAVE_HEIGHT = 22.dp
private const val WAVE_BARS = 8
private const val WAVE_ALPHA = 0.7f
private val BAR_PADDING = 6.dp
private val PIECE_GAP = 8.dp

/**
 * 琉璃版「录好待发」草稿条（图纸 2026-09-05 卷二C §4.12 · A-17 · 对版稿 B 甲 · 照抄源 F28
 * `ui/chat/VoiceInputComposer.kt:302-394`）。
 *
 * 顶替 `LiuliInputBar` 里的输入胶囊（发送球留在胶囊**外**、位置不动）。五态与暖陶逐字同：
 * 识别中（转圈 + 「识别中…」）/ 就绪（「点按试听」）/ 三种失败（琥珀字 + 非 UNAVAILABLE 才给
 * 「重新识别」——引擎粘滞失败重试必败，不给假希望）/ 试听中（▶ 换 ■）。
 *
 * **唯一有意的可见变化（§2.3「唯八」→「唯九」·A-17）**：就绪态第二行在「点按试听」后缀上识别出的
 * 文字（单行省略）——发之前看得见自己说了什么。
 *
 * 两枚 44 圆片是「玻璃上的圆片」而不是各自一片玻璃（`surface.raised` 55% + 0.5 发丝·对版稿
 * `.draft .ic`）：整条已经是一片玻璃，再套 `liuliGlass` 会叠两层片（同 `LiuliReplyBar` 的判例）。
 * 触达一律 48 由 [liuliFootprint] / [liuliTouchHeight] 外溢撑起，版位不长。
 */
@Composable
internal fun LiuliDraftBar(
    draft: VoiceDraftState,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onRetryTranscription: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val dark = LocalIsDarkTheme.current
    val hairline = if (dark) LiuliGlassSpec.hairlineDark else LiuliGlassSpec.hairlineLight

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .liuliGlass(LiuliShapes.pill, dark = dark)
            .padding(horizontal = BAR_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PIECE_GAP),
    ) {
        LiuliDraftDisc(
            contentDescription = stringResource(R.string.action_cancel),
            background = colors.surface.raised.copy(alpha = DISC_ALPHA),
            border = hairline,
            tint = onGlass.primary,
            icon = Icons.Filled.Close,
            onClick = onCancel,
        )
        LiuliDraftDisc(
            contentDescription = stringResource(
                if (isPlaying) R.string.a11y_stop_playback else R.string.a11y_voice_play,
            ),
            background = colors.accent.container,
            border = Color.Transparent,
            tint = colors.accent.onContainer,
            icon = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            onClick = onPlay,
        )
        LiuliVoiceWaveform(
            isPlaying = false,
            progress = 0f,
            playedColor = colors.accent.text.copy(alpha = WAVE_ALPHA),
            unplayedColor = colors.accent.text.copy(alpha = WAVE_ALPHA),
            barCount = WAVE_BARS,
            appearPlay = false,
            onAppearPlayed = {},
            modifier = Modifier.width(WAVE_WIDTH).height(WAVE_HEIGHT),
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.voice_draft_title),
                style = AppTypography.label,
                color = onGlass.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LiuliDraftSecondLine(draft = draft, onRetryTranscription = onRetryTranscription)
        }
        Text(
            liuliFormatVoiceDuration((draft.durationSec * 1000).toLong()),
            style = AppTypography.snackbarBody.copy(fontFeatureSettings = "tnum"),
            color = onGlass.secondary,
            maxLines = 1,
        )
    }
}

/** 第二行三态（照抄 F28 的分支序：识别中 → 失败 → 就绪）。 */
@Composable
private fun LiuliDraftSecondLine(draft: VoiceDraftState, onRetryTranscription: () -> Unit) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val subStyle = AppTypography.settingsRowValue
    when {
        draft.isTranscriptPending -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LiuliSpinner(color = colors.accent.text)
            Text(stringResource(R.string.voice_draft_processing), style = subStyle, color = onGlass.secondary)
        }
        draft.transcriptFailure != null -> {
            val failure = draft.transcriptFailure
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        when (failure) {
                            VoiceTranscriptFailure.UNAVAILABLE -> R.string.voice_draft_failed_unavailable
                            VoiceTranscriptFailure.EMPTY -> R.string.voice_draft_failed_empty
                            VoiceTranscriptFailure.TIMEOUT -> R.string.voice_draft_failed_timeout
                        },
                    ),
                    style = subStyle,
                    color = colors.status.onError,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 让失败文案先缩（省略号），把「重新识别」的完整宽度留住——不给 weight 的话，
                    // 12 字的错误文案会把钮挤成一个字宽、竖着排出胶囊（装机实证）。
                    modifier = Modifier.weight(1f, fill = false),
                )
                // UNAVAILABLE = 引擎模型加载失败（进程内粘滞），重试必败 → 不给钮（照抄 F28）。
                if (failure != VoiceTranscriptFailure.UNAVAILABLE) {
                    Box(
                        modifier = Modifier
                            .liuliTouchHeight()
                            .clickable(role = Role.Button) { onRetryTranscription() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.voice_draft_retry),
                            style = subStyle,
                            color = colors.accent.text,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
        else -> Text(
            // A-17 唯一新增的可见变化：就绪态后缀识别文字（空则只留「点按试听」）。
            text = stringResource(R.string.voice_draft_ready) +
                if (draft.transcript.isNotBlank()) " · ${draft.transcript}" else "",
            style = subStyle,
            color = onGlass.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 44 圆片（玻璃条上的圆片·非独立玻璃）：视觉 44 · 触达 48 外溢不占版。 */
@Composable
private fun LiuliDraftDisc(
    contentDescription: String,
    background: Color,
    border: Color,
    tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .liuliFootprint(DISC)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(DISC)
                .clip(CircleShape)
                .background(background)
                .border(LiuliGlassSpec.hairlineWidth, border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(DISC_ICON))
        }
    }
}
