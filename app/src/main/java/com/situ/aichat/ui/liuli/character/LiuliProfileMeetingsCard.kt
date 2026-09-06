package com.situ.aichat.ui.liuli.character

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.offline.OfflineMeetingSession
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.liuliTouchHeight
import com.situ.aichat.ui.offline.MeetingSkyHeroCard
import com.situ.aichat.ui.offline.MeetingSkyMiniThumb

/** 卡头「全部 N 次 ›」的雪佛龙 / 胶卷右缘露白 / 两块之间的缝（1:1 暖陶）。 */
private val CHEVRON = 16.dp
private val FILM_END_PEEK = 24.dp
private val FILM_GAP = 8.dp
private val HERO_TO_FILM = 10.dp

/**
 * 见面回忆节「那晚的天色」（琉璃·搬暖陶 `OfflineMeetingMemorySection`）。
 *
 * 最新一场 = 全宽窗景卡；更早 = 横向小天窗胶卷（右缘露半张）；「查看全部」唯一入口 = 卡头
 * 「全部 N 次 ›」（≥1 场即显）。**始终渲染**，空态两行文案。
 *
 * 两枚天色 Canvas 件（[MeetingSkyHeroCard] / [MeetingSkyMiniThumb]）按图纸 F6 **永久借用**，原样嵌在组内。
 */
@Composable
internal fun LiuliProfileMeetingsCard(
    sessions: List<OfflineMeetingSession>,
    onOpenAll: () -> Unit,
    onRetryFallback: (String) -> Unit,
    modifier: Modifier = Modifier,
    retryingSessionIds: Set<String> = emptySet(),
) {
    val colors = AppTheme.colors
    LiuliGroup(modifier = modifier, header = stringResource(R.string.profile_meeting_title)) {
        LiuliRowBase(
            divider = false,
            minHeight = 0.dp,
            verticalPadding = LiuliPageGeometry.groupPadH,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (sessions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .clip(LiuliShapes.pill)
                            .liuliTouchHeight()
                            .clickable(role = Role.Button, onClick = onOpenAll),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.profile_meeting_all_count, sessions.size),
                            style = AppTypography.secondary,
                            color = colors.accent.text,
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.accent.text,
                            modifier = Modifier.size(CHEVRON),
                        )
                    }
                }
                if (sessions.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = LiuliPageGeometry.groupPadH),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.profile_meeting_empty_1),
                            style = AppTypography.listPreview,
                            color = colors.text.secondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.profile_meeting_empty_2),
                            style = AppTypography.secondary,
                            color = colors.text.tertiary,
                        )
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                    // extractor 已按日期倒序输出；此处显式钉死 newest-first（防上游变化静默倒挂·同暖陶）。
                    val sorted = remember(sessions) { sessions.sortedByDescending { it.startMillis } }
                    val hero = sorted.first()
                    MeetingSkyHeroCard(
                        session = hero,
                        sequenceNumber = sorted.size,
                        onRetryFallback = onRetryFallback,
                        onClick = onOpenAll,
                        isRetrying = hero.id in retryingSessionIds,
                    )
                    if (sorted.size > 1) {
                        Spacer(Modifier.height(HERO_TO_FILM))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(FILM_GAP),
                            contentPadding = PaddingValues(end = FILM_END_PEEK),
                        ) {
                            items(sorted.drop(1), key = { it.id }) { s ->
                                MeetingSkyMiniThumb(session = s, onClick = onOpenAll)
                            }
                        }
                    }
                }
            }
        }
    }
}
