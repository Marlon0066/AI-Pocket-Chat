package com.situ.aichat.ui.ourdays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.ui.character.CardSectionHeader
import com.situ.aichat.ui.character.ProfileCard
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

/**
 * 资料页「我们的日子」轻卡（卷三图纸 §4.10·提案 D-2·帧 2）：`ProfileCard` 同款浅卡；标题 + chevron；副标三段（空态改空态句·卡仍渲染）；
 * 14 天热度条（见面日暖金·今天陶土描边·10dp 高 3dp 圆角 5dp 间）+ 首尾日期小字。点击 ⇒ 进日历页并预选该角色。
 */
@Composable
fun ProfileOurDaysCard(onOpen: () -> Unit, modifier: Modifier = Modifier, viewModel: OurDaysEntryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val mdPattern = stringResource(R.string.our_days_fmt_md)
    val cellShape = RoundedCornerShape(3.dp)
    ProfileCard(modifier = modifier, onClick = onOpen, onClickLabel = stringResource(R.string.our_days_title)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            CardSectionHeader(AppFeatureIcons.Days, colors.accent.text, stringResource(R.string.our_days_title))
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.text.tertiary)
        }
        Text(
            if (state.hasAny) {
                stringResource(R.string.our_days_card_stats, state.daysTogether, state.chatDaysThisMonth, state.meetingDays)
            } else {
                stringResource(R.string.our_days_empty_hint)
            },
            style = AppTypography.secondary.copy(fontSize = 12.sp),
            color = colors.text.secondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            state.bar.forEach { cell ->
                val fill = when {
                    cell.dots.contains(DotFamily.MEETING) -> colors.ourDays.dotMeeting
                    cell.heatLevel == 0 -> colors.surface.sunken
                    else -> heatColor(cell.heatLevel)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(cellShape)
                        .background(fill)
                        .then(if (cell.isToday) Modifier.border(1.5.dp, colors.accent.primary, cellShape) else Modifier),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(OurDaysFormat.date(state.rangeStart, mdPattern), style = AppTypography.caption.copy(fontSize = 10.sp), color = colors.text.tertiary)
            Text(stringResource(R.string.our_days_today), style = AppTypography.caption.copy(fontSize = 10.sp), color = colors.text.tertiary)
        }
    }
}
