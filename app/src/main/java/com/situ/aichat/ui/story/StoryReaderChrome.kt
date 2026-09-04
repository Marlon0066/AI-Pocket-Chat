package com.situ.aichat.ui.story

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.storyCleanTitle
import com.situ.aichat.story.storyNumberToChinese
import com.situ.aichat.story.unlockRemainingMinutes
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface

// 故事阅读器的非正文视觉构件组（从 StoryReaderScreen 抽出·纯搬 composable）。
// 4 件均 private→internal 供主屏跨文件调用：章节封面随滚动入场，底部翻页胶囊 + 生成中 / 锁态遮罩浮于其上。

// ── 章节封面 ──

@Composable
internal fun ChapterCover(
    chapter: StoryChapterEntity?,
    isDark: Boolean,
    containerHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val ornament = StoryReaderLayout.ornamentColor(isDark)
    val titleColor = StoryReaderLayout.textColor(isDark)
    val secondary = StoryReaderLayout.secondaryTextColor(isDark)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = containerHeight * StoryReaderLayout.coverHeightRatio),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // P1-20：封面装饰线/◆ 对 TalkBack 压停（iOS 未隐藏此处=安卓超越）。
        Row(
            modifier = Modifier.clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(28.dp).height(0.5.dp).background(ornament))
            Text("✦", color = ornament, fontSize = 11.sp)
            Box(Modifier.width(28.dp).height(0.5.dp).background(ornament))
        }
        Spacer(Modifier.height(12.dp))
        chapter?.let {
            Text(
                stringResource(R.string.story_reader_cover_chapter, storyNumberToChinese(it.chapterNumber)),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Serif,
                letterSpacing = StoryReaderLayout.chapterNumberKerning,
                color = secondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("◆", color = ornament, fontSize = 8.sp, modifier = Modifier.clearAndSetSemantics {})
        Spacer(Modifier.height(8.dp))
        // P1-20：封面章节标题进「按标题导航」（heading；顶栏胶囊标题不加，避免同章双 heading 跳点）。
        Text(
            storyCleanTitle(chapter?.title ?: stringResource(R.string.story_reader_untitled)),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif,
            color = titleColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        chapter?.teaser?.takeIf { it.isNotEmpty() }?.let { teaser ->
            Spacer(Modifier.height(12.dp))
            Text(
                "“$teaser”",
                fontSize = 16.sp,
                fontFamily = FontFamily.Serif,
                color = secondary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.story_reader_start_reading), fontSize = 12.sp, fontFamily = FontFamily.Serif, color = secondary)
    }
}

// ── 底部胶囊 ──

@Composable
internal fun BottomCapsule(
    hasPrev: Boolean,
    hasNext: Boolean,
    showContinueArc: Boolean,
    progressPercent: Int,
    remainingMinutes: Int,
    visible: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onContinueArc: () -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    // 显隐 0.25s 淡入淡出（隐藏即移出组合＝iOS allowsHitTesting(false) 不吃点击）；
    // 统一由 chromeVisible 驱动（点击唤出 / 滚动隐入沉浸）；入场附 8dp 轻上浮（reduceMotion 只留淡入），
    // 与顶栏浮岛「飘落」呼应（过审 mockup story_reader_chrome_restyle_options·方案 A）。
    val reduceMotion = rememberReduceMotion()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduceMotion) fadeIn(tween(250)) else fadeIn(tween(250)) + slideInVertically(tween(300, easing = EaseOut)) { it / 3 },
        exit = fadeOut(tween(250)),
    ) {
        // 胶囊底色/文字随当前背景明暗自适应（深背景→深底浅字），effect 轴慢档渐变与背景同步。
        val capsuleContent = StoryReaderLayout.textColor(isDark)
        val capsuleScrim = StoryReaderLayout.islandScrimColor(isDark)
        val capsuleBorder = StoryReaderLayout.chromeBorderColor(isDark)
        // 底部毛玻璃进度胶囊（照 mockup rd-cap·混合保留导航）：‹ 上一章 · 「62% · 还剩 3 分钟」· 下一章 ›；
        // 末章完结故事时右侧换「开启续篇」。发丝迎光边让胶囊在纸面渐变上成形。
        // 「陶土液面」：胶囊本身即进度条——暖陶淡色从左灌到当前进度（随滚动 400ms 缓推·色随深浅换肤·
        // 复用菜单暖陶单源 menuAccentColor·左缘圆角由胶囊裁切天然获得）。
        val fillColor = StoryReaderLayout.menuAccentColor(isDark).copy(alpha = if (isDark) 0.20f else 0.15f)
        val fillFraction by animateFloatAsState(
            progressPercent.coerceIn(0, 100) / 100f,
            tween(400, easing = EaseOut),
            label = "capsuleFillFraction",
        )
        Surface(
            modifier = Modifier.padding(bottom = 16.dp),
            shape = RoundedCornerShape(50),
            color = capsuleScrim,
            border = BorderStroke(0.75.dp, capsuleBorder),
        ) {
            // 与顶栏三件浮岛同一 44dp 尺度（旧 IconButton 默认 48dp 把胶囊撑到 ~56dp=头轻脚重·2026-07-04 真机反馈）。
            Row(
                modifier = Modifier
                    .drawBehind { drawRect(fillColor, size = Size(size.width * fillFraction, size.height)) }
                    .height(StoryReaderLayout.islandHeight)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(onClick = onPrev, enabled = hasPrev, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = stringResource(R.string.story_reader_prev),
                        tint = capsuleContent.copy(alpha = if (hasPrev) 1f else 0.3f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    storyReaderProgressLabel(progressPercent, remainingMinutes),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = capsuleContent.copy(alpha = 0.78f),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                if (showContinueArc) {
                    // TODO(图纸未覆盖): 阅读器 chrome 的胶囊按钮，字色 `capsuleContent` 跟着阅读主题走
                    //  （§0.5 明文「不动故事阅读器的自研 chrome」）；§4.13 只允许「删 colors」或「改 danger」，
                    //  这里两者都不对——它既不是装饰也不是危险语义，是承重的主题色 → 停手登记（施工日志 D-15）。
                    TextButton(
                        onClick = onContinueArc,
                        colors = ButtonDefaults.textButtonColors(contentColor = capsuleContent),
                    ) { Text(stringResource(R.string.story_reader_continue_arc), fontWeight = FontWeight.Bold) }
                } else {
                    IconButton(onClick = onNext, enabled = hasNext, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = stringResource(R.string.story_reader_next),
                            tint = capsuleContent.copy(alpha = if (hasNext) 1f else 0.3f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 进度胶囊文案：仍有剩余分钟 → 「62% · 还剩 3 分钟」；读到末尾 → 只「100%」。 */
@Composable
private fun storyReaderProgressLabel(percent: Int, remainingMinutes: Int): String =
    if (remainingMinutes > 0) stringResource(R.string.story_reader_progress_time, percent, remainingMinutes)
    else stringResource(R.string.story_reader_progress_pct, percent)

// ── 生成中遮罩 ──

@Composable
internal fun GenerationOverlay(gen: StoryGenerationTaskManager.GenerationProgress, onGoToChat: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        // M3 浮层 Surface（tonal 6 档 + surface@0.96）→ Column(appCardSurface raised·卡内 16·§4.S1)；外层 scrim 零改。
        Column(
            modifier = Modifier.appCardSurface(raised = true).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // P1-20：遮罩出现时 Polite 播报一次（只挂稳定标题——章号生成期不变；phase 高频切换绝不挂防刷屏）。
            Text(
                stringResource(R.string.story_reader_generating, gen.chapterNumber),
                style = AppTheme.typography.listName,
                color = AppTheme.colors.text.primary,
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            )
            StoryPhaseBar(
                genPhase = gen.genPhase,
                progress = gen.progress,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(gen.phase, style = AppTheme.typography.secondary, color = AppTheme.colors.text.secondary)
            // a11y 措辞=iOS label「去聊天，生成完成后通知我」（Helpers.swift:168，覆盖可见短文案）。
            val goChatA11y = stringResource(R.string.story_reader_go_chat_a11y)
            AppButton(
                onClick = onGoToChat,
                style = AppButtonStyle.Text,
                modifier = Modifier.semantics { contentDescription = goChatA11y },
            ) { Text(stringResource(R.string.story_reader_go_chat)) }
        }
    }
}

// ── 锁态遮罩 ──

@Composable
internal fun LockedOverlay(chapter: StoryChapterEntity, now: Long) {
    // Surface(colorScheme.background) → Box(surface.base)·色字上 AppTheme token（§4.S2）；布局零改。
    Box(modifier = Modifier.fillMaxSize().background(AppTheme.colors.surface.base)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("🔒", fontSize = 56.sp, modifier = Modifier.clearAndSetSemantics {})
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.story_reader_chapter_n, chapter.chapterNumber), style = AppTheme.typography.titleMedium)
            Text(storyCleanTitle(chapter.title), style = AppTheme.typography.listName, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            chapter.unlockAt?.let { unlockAt ->
                val mins = unlockRemainingMinutes(unlockAt, now)
                val remaining = if (mins >= 60) {
                    stringResource(R.string.story_remaining_hm, mins / 60, mins % 60)
                } else {
                    stringResource(R.string.story_remaining_m, mins)
                }
                Text(stringResource(R.string.story_unlock_in, remaining), color = AppTheme.colors.accent.text)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.story_reader_locked_hint),
                style = AppTheme.typography.listPreview,
                color = AppTheme.colors.text.secondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
