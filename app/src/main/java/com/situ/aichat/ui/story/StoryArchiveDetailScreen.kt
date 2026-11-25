package com.situ.aichat.ui.story

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.story.StoryArchiveDigest
import com.situ.aichat.story.StoryEndingType
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppTheme
import java.time.Instant
import java.time.ZoneId

/**
 * 结局档案卡（ST8·契约 §5 / D4·照 mockup 屏八）：完结故事的「作品档案」全屏页。
 *
 * 大封面（复用 [StoryCover]）+ 书名 + 题材/结局徽章 + 足迹行 + 起讫日期 + 楷体摘句；底部两钮：生成分享长图 / 导出全文 txt。
 * 纯展示层——不改故事状态、不碰金额；分享/导出=用户显式点击才发生（隐私口径 §14）。
 */
@Composable
fun StoryArchiveDetailScreen(
    onBack: () -> Unit,
    viewModel: StoryArchiveDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val c = AppTheme.colors
    val actions = rememberStoryArchiveActions(viewModel, state, onBack)

    Box(Modifier.fillMaxSize().background(c.surface.base)) {
        state?.let { s ->
            StoryArchiveContent(story = s.story, digest = s.digest, modifier = Modifier.fillMaxSize())

            // 底部固定两钮（浮于内容·mockup .arc-btns）。
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 22.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                AppButton(
                    onClick = actions.share,
                    style = AppButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text(stringResource(R.string.story_archive_share)) }

                OutlineActionButton(
                    text = stringResource(R.string.story_archive_export_txt),
                    onClick = actions.export,
                )

                // 存量已完结书的救济（ST11 §4.5）：判定链改动不回溯，被旧规则（AI 说完结就完结）
                // 误关进档案的书从这儿请回在读区。虚线 = 「这本还没真的结束」的视觉暗示。
                ContinueWritingButton(onClick = viewModel::continueWriting)
            }
        }

        // 关闭 ✕（右上·浮层·mockup .arc-close）。
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 20.dp)
                .size(32.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(c.surface.sunken)
                .clickableScale(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppActionIcons.Close, contentDescription = stringResource(R.string.action_close), tint = c.text.secondary, modifier = Modifier.size(16.dp))
        }
    }

    // 「继续写」失败提示（断网/无 key 等）——照阅读器同款惯例，绝不让异常穿透闪退。
    error?.let { msg ->
        AppDialog(
            onDismissRequest = viewModel::dismissError,
            title = stringResource(R.string.story_alert_error_title),
            body = msg,
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = viewModel::dismissError,
        )
    }
}

@Composable
internal fun StoryArchiveContent(
    story: StoryEntity,
    digest: StoryArchiveDigest,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(start = 24.dp, end = 24.dp, top = 64.dp, bottom = 140.dp),
    quoteSurface: Modifier = Modifier,
) {
    val c = AppTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    val startDate = remember(digest.startMillis) { Instant.ofEpochMilli(digest.startMillis).atZone(zone).toLocalDate() }
    val endDate = remember(digest.endMillis) { Instant.ofEpochMilli(digest.endMillis).atZone(zone).toLocalDate() }
    val startStr = stringResource(R.string.story_archive_date_md, startDate.monthValue, startDate.dayOfMonth)
    val endStr = stringResource(R.string.story_archive_date_md, endDate.monthValue, endDate.dayOfMonth)

    Column(
        modifier
            .verticalScroll(scrollState)
            .padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StoryCover(
            coverColorScheme = story.coverColorScheme,
            title = story.title,
            storyId = story.id,
            titleSizeSp = 15f,
            modifier = Modifier.size(width = 158.dp, height = 211.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(story.title, style = AppTheme.typography.titleMedium.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold), color = c.text.primary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        ArchiveChips(story = story, endingType = digest.endingType)
        Spacer(Modifier.height(22.dp))
        Text(
            stringResource(R.string.story_archive_footprint, digest.chapterCount, digest.choiceCount, digest.dayCount),
            style = AppTheme.typography.secondary, color = c.text.secondary,
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.story_archive_dates, startStr, endStr), style = AppTheme.typography.caption, color = c.text.tertiary)
        if (digest.quote.isNotBlank()) {
            Spacer(Modifier.height(26.dp))
            Text(
                stringResource(R.string.story_archive_quote, digest.quote),
                style = AppTheme.typography.kaiQuote.copy(fontSize = 14.5.sp, lineHeight = 27.sp),
                color = c.text.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.then(quoteSurface).padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun ArchiveChips(story: StoryEntity, endingType: String?) {
    val c = AppTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // 题材 · 文风（原始中文·非本地化键）。
        Chip(text = "${story.genre} · ${story.writingStyle}", fg = c.text.secondary, bg = c.surface.sunken)
        // 结局类型徽章（贵金属金 economy.gold·与 warning 琥珀物理隔离）。金@0.1 填充上金字实测仅 4.02:1<4.5，
        // 故改「金边 + 金字 on base」——金字直接落 base 达 4.55:1 过 WCAG，同保「贵金属」识别（mockup 填充→描边微调）。
        val endingRes = when (endingType) {
            StoryEndingType.CUSTOM -> R.string.story_archive_ending_custom
            StoryEndingType.AI -> R.string.story_archive_ending_ai
            StoryEndingType.OPEN -> R.string.story_archive_ending_open
            else -> R.string.story_archive_ending_natural
        }
        Chip(text = stringResource(endingRes), fg = c.economy.gold, border = c.economy.gold.copy(alpha = 0.5f), bold = true)
    }
}

@Composable
private fun Chip(text: String, fg: Color, bg: Color? = null, border: Color? = null, bold: Boolean = false) {
    val pill = RoundedCornerShape(99.dp)
    var box = Modifier.clip(pill)
    if (bg != null) box = box.background(bg)
    if (border != null) box = box.border(1.dp, border, pill)
    Text(
        text,
        style = AppTheme.typography.caption.copy(fontSize = 11.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium),
        color = fg,
        modifier = box.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** 描边行动钮（mockup .btn-s·design system 无 Outline 档·照过审 mockup 自绘 pill）。 */
@Composable
private fun OutlineActionButton(text: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(99.dp))
            .border(1.5.dp, c.accent.text.copy(alpha = 0.35f), RoundedCornerShape(99.dp))
            .clickableScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AppTheme.typography.label, color = c.accent.text, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 「✎ 继续写这个故事」（ST11 §4.5）：虚线陶土钮——虚线画法照 [StoryShelfSections] 的「开新故事」入场卡先例
 * （drawBehind + dashPathEffect 10/8），与实线的分享/导出两钮区分开：这不是对档案的操作，是把书请回连载。
 */
@Composable
private fun ContinueWritingButton(onClick: () -> Unit) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(c.accent.primary.copy(alpha = 0.04f))
            .drawBehind {
                val r = 14.dp.toPx()
                drawRoundRect(
                    color = c.accent.text.copy(alpha = 0.45f),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    cornerRadius = CornerRadius(r, r),
                )
            }
            .clickableScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            storyArchiveContinueLabel(),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.accent.onContainer,
        )
    }
}
