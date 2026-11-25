package com.situ.aichat.ui.liuli.story

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliPaintedLens
import com.situ.aichat.ui.story.CancelFinalePill
import com.situ.aichat.ui.story.ContinueHeader
import com.situ.aichat.ui.story.ContinueZoneMode
import com.situ.aichat.ui.story.DIRECTION_BODY_ALPHA
import com.situ.aichat.ui.story.DIRECTION_MAX_LINES
import com.situ.aichat.ui.story.DRAFT_MAX_LINES
import com.situ.aichat.ui.story.FinalePill
import com.situ.aichat.ui.story.FinaleStatusChip
import com.situ.aichat.ui.story.StoryContinueZoneState
import com.situ.aichat.ui.story.StoryFinaleProgress
import com.situ.aichat.ui.story.StoryReaderLayout
import com.situ.aichat.ui.story.rememberStoryBreatheScale
import com.situ.aichat.ui.story.storyContinueFlowLabelRes
import com.situ.aichat.ui.story.storyDraftTagRes

// 琉璃阅读器推进区（琉璃 2.0 卷六·三·下甲 §4.6）：草稿卡 / 走向卡 / 输入卡换画出来的玻璃，主胶囊换紫渐变实底；
// 两颗金胶囊 + 收尾状态 chip 两张脸共用原件；写作中（[locked]）全部入口不可点 + 0.45 淡（R2）。

/** 推进区：走向卡 → 草稿卡 → 输入卡 → 胶囊排（主胶囊 + 金胶囊槽）。 */
@Composable
internal fun LiuliStoryContinueZone(
    isDark: Boolean,
    breatheTrigger: Int,
    finaleProgress: StoryFinaleProgress?,
    zone: StoryContinueZoneState,
    draftBeats: String?,
    draftUserEdited: Boolean,
    locked: Boolean,
    onWriteClick: () -> Unit,
    onFlowClick: () -> Unit,
    onFinaleClick: () -> Unit,
    onCancelFinaleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    val breathe = rememberStoryBreatheScale(breatheTrigger)
    val byDirection = zone.mode == ContinueZoneMode.BY_DIRECTION
    val lockAlpha = if (locked) LOCKED_ALPHA else 1f
    Column(modifier.fillMaxWidth()) {
        ContinueHeader(isDark)
        // 走向卡在草稿卡之上（先看见「我要什么」再看见「AI 打算怎么写」·同暖陶）。
        if (byDirection && zone.directionText != null) {
            LiuliStoryDirectionCard(zone.directionText, isDark, enabled = !locked, onClick = onWriteClick, modifier = Modifier.scale(breathe.value).alpha(lockAlpha))
        }
        draftBeats?.takeIf { it.isNotBlank() }?.let {
            LiuliStoryDraftCard(it, draftUserEdited, isDark, enabled = !locked, onClick = onWriteClick, modifier = Modifier.alpha(lockAlpha))
        }
        // 输入卡：态 B 下整卡隐藏（走向卡顶替入口·同暖陶 D-2）。
        if (!byDirection) LiuliStoryInputCard(isDark, enabled = !locked, onClick = onWriteClick, modifier = Modifier.scale(breathe.value).alpha(lockAlpha))
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 主胶囊三模式都走紫渐变实底（草图 ⑤「主胶囊换琉璃紫渐变实底」）；点击恒 onFlowClick（禁新开生成路径·同暖陶）。
            LiuliButton(onClick = { haptics.light(); onFlowClick() }, style = LiuliButtonStyle.Prominent, enabled = !locked) {
                Icon(AppActionIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(storyContinueFlowLabelRes(zone.mode)))
            }
            if (finaleProgress == null) {
                FinalePill(isDark = isDark, onClick = { haptics.light(); onFinaleClick() }, enabled = !locked, modifier = Modifier.alpha(lockAlpha))
            } else {
                FinaleStatusChip(isDark = isDark, progress = finaleProgress)
                CancelFinalePill(isDark = isDark, onClick = { haptics.light(); onCancelFinaleClick() }, enabled = !locked, modifier = Modifier.alpha(lockAlpha))
            }
        }
    }
}

@Composable
private fun LiuliStoryInputCard(isDark: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .liuliPaintedLens(RoundedCornerShape(16.dp), isDark)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(AppActionIcons.Edit, null, tint = AppTheme.colors.accent.text, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.story_continue_director_hint), color = StoryReaderLayout.secondaryTextColor(isDark), fontSize = 15.sp)
    }
}

/** 草稿卡：卡形 / 内距 / 字号 / 行数 = 暖陶；标题换紫、tag 换小胶囊（草图 ⑤ `.dcard .t`）。 */
@Composable
internal fun LiuliStoryDraftCard(
    draftBeats: String,
    draftUserEdited: Boolean,
    isDark: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().padding(bottom = 10.dp).liuliPaintedLens(shape, isDark)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.story_continue_draft_title), color = AppTheme.colors.accent.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Spacer(Modifier.width(6.dp))
            LiuliReaderTagChip(stringResource(storyDraftTagRes(draftUserEdited)))
            Spacer(Modifier.weight(1f))
            // 小笔图标只是「可改」的暗示，不是独立按钮（整卡 clickable 已接导演台·同暖陶）。
            Icon(AppActionIcons.Edit, contentDescription = null, tint = StoryReaderLayout.secondaryTextColor(isDark), modifier = Modifier.size(14.dp))
        }
        Text(
            draftBeats,
            color = StoryReaderLayout.secondaryTextColor(isDark),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = DRAFT_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 走向卡：同草稿卡链 + 1dp 紫边 @0.40（= 暖陶走向卡边）；正文 serif @0.80。 */
@Composable
internal fun LiuliStoryDirectionCard(
    directionText: String,
    isDark: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().padding(bottom = 10.dp).liuliPaintedLens(shape, isDark)
            .border(1.dp, AppTheme.colors.accent.primary.copy(alpha = 0.40f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.story_continue_direction_title), color = StoryReaderLayout.textColor(isDark), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Spacer(Modifier.width(6.dp))
            LiuliReaderTagChip(stringResource(R.string.story_continue_direction_tag))
            Spacer(Modifier.weight(1f))
            Icon(AppActionIcons.Edit, contentDescription = null, tint = StoryReaderLayout.secondaryTextColor(isDark), modifier = Modifier.size(14.dp))
        }
        Text(
            directionText,
            color = StoryReaderLayout.textColor(isDark).copy(alpha = DIRECTION_BODY_ALPHA),
            fontSize = 13.5.sp,
            lineHeight = 21.sp,
            fontFamily = FontFamily.Serif,
            maxLines = DIRECTION_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 卡头小胶囊 tag（草图 `em`：1 / 6 内距·10sp = 暖陶 tag 字号）。 */
@Composable
private fun LiuliReaderTagChip(text: String) {
    Text(
        text,
        color = AppTheme.colors.accent.onContainer,
        fontSize = 10.sp,
        modifier = Modifier.clip(LiuliShapes.pill).background(AppTheme.colors.accent.container).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 建议完结卡底三枚钮（放进 `StoryEndingSuggestCard` 的 actions 槽）：主 / 次 / 静 = 暖陶 Primary / Tonal / Text。 */
@Composable
internal fun LiuliStoryEndingSuggestActions(
    enabled: Boolean,
    onGracefulFinale: () -> Unit,
    onFinish: () -> Unit,
    onKeepWriting: () -> Unit,
) {
    LiuliButton(
        onClick = onGracefulFinale,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        style = LiuliButtonStyle.Prominent,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) { Text(stringResource(R.string.story_ending_suggest_graceful), textAlign = TextAlign.Center) }
    LiuliButton(
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        style = LiuliButtonStyle.Glass,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) { Text(stringResource(R.string.story_ending_suggest_finish), textAlign = TextAlign.Center) }
    LiuliButton(
        onClick = onKeepWriting,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        style = LiuliButtonStyle.Text,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) { Text(stringResource(R.string.story_ending_suggest_continue), textAlign = TextAlign.Center) }
}
