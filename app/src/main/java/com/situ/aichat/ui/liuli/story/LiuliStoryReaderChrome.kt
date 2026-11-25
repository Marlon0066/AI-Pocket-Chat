package com.situ.aichat.ui.liuli.story

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliMaterials
import com.situ.aichat.ui.liuli.designsystem.LiuliPopupSurface
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmented
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliSlider
import com.situ.aichat.ui.liuli.designsystem.LiuliSwitch
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.liuli.page.LiuliCompactTopBar
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.story.MenuActionRow
import com.situ.aichat.ui.story.MenuHairline
import com.situ.aichat.ui.story.STORY_READER_MENU_WIDTH
import com.situ.aichat.ui.story.StoryReaderLayout
import com.situ.aichat.ui.story.StoryReaderNav
import com.situ.aichat.ui.story.StoryReaderProgress
import com.situ.aichat.ui.story.storyReaderCapsuleTitle
import com.situ.aichat.ui.story.storyReaderChapterSuffix
import com.situ.aichat.ui.story.storyReaderFontSizeLabels
import com.situ.aichat.ui.story.storyReaderProgressLabel
import com.situ.aichat.ui.theme.LocalIsDarkTheme

// 琉璃阅读器外框（琉璃 2.0 卷六·三·下甲 §4.3）：顶部玻璃胶囊（R3）+ ⋮ 玻璃菜单 + 底部可拖进度坞。

// 坞：左右内缩 12 / 离导航栏 12 / 高 44（= 暖陶岛高）/ 件缝 12（亦为坞与进度卡缝）/ 列表底留白 68（+ 导航栏）；
// 进度片左右 16 · 字与滑杆 2（12sp 字 + 2 + 20 滑杆 = 38 ≤ 44）· 进出 180ms + 6dp（= LiuliCompactTopBar）· 菜单锚「钮高 + 8」。
internal val READER_DOCK_INSET_H = LiuliPageGeometry.floatingBarInset
internal val READER_DOCK_BOTTOM = LiuliPageGeometry.floatingBarInset
internal val READER_DOCK_HEIGHT = StoryReaderLayout.islandHeight
internal val READER_DOCK_GAP = LiuliPageGeometry.floatingBarGap
internal val READER_DOCK_RESERVE = READER_DOCK_BOTTOM + READER_DOCK_HEIGHT + READER_DOCK_GAP
private val DOCK_PILL_PAD_H = LiuliPageGeometry.groupPadH
private val DOCK_LABEL_GAP = 2.dp
private const val DOCK_MOTION_MS = 180
private val DOCK_SLIDE = 6.dp
private val MENU_ANCHOR_GAP = 8.dp

/** 顶部玻璃胶囊「‹ · 书名 · 第 N 章 · ⋮」（R3·现成 [LiuliCompactTopBar] 接线）。 */
@Composable
internal fun BoxScope.LiuliStoryReaderCapsule(
    visible: Boolean,
    storyTitle: String?,
    chapter: StoryChapterEntity?,
    readingAnimationsEnabled: Boolean,
    fontSizeIndex: Int,
    onBack: () -> Unit,
    onOpenBookHub: (() -> Unit)?,
    onToggleAnimations: (Boolean) -> Unit,
    onSetFontSizeIndex: (Int) -> Unit,
    onMenuExpandedChange: (Boolean) -> Unit,
) {
    LiuliCompactTopBar(
        title = storyReaderCapsuleTitle(storyTitle),
        visible = visible,
        leading = { LiuliPageCircleAction(onClick = onBack, contentDescription = stringResource(R.string.action_back), icon = AppTopBarIcons.Back) },
        trailing = {
            LiuliStoryReaderMenu(readingAnimationsEnabled, fontSizeIndex, onOpenBookHub, onToggleAnimations, onSetFontSizeIndex, onMenuExpandedChange)
        },
        titleSuffix = storyReaderChapterSuffix(chapter),
    )
}

/** ⋮ 玻璃菜单：书页 / 阅读动画 / 字号（项目同暖陶·开关与字号拨完不关菜单 = 活预览）。 */
@Composable
private fun LiuliStoryReaderMenu(
    readingAnimationsEnabled: Boolean,
    fontSizeIndex: Int,
    onOpenBookHub: (() -> Unit)?,
    onToggleAnimations: (Boolean) -> Unit,
    onSetFontSizeIndex: (Int) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val setExpanded: (Boolean) -> Unit = { expanded = it; onExpandedChange(it) }
    val dark = LocalIsDarkTheme.current
    val onGlass = LiuliTheme.onGlass
    val divider = LiuliMaterials.divider(dark)
    Box {
        LiuliPageCircleAction(onClick = { setExpanded(true) }, contentDescription = stringResource(R.string.story_reader_menu), icon = Icons.Filled.MoreVert)
        LiuliPopupSurface(
            expanded = expanded,
            onDismiss = { setExpanded(false) },
            offset = DpOffset(0.dp, LiuliPageGeometry.backButton + MENU_ANCHOR_GAP),
            width = STORY_READER_MENU_WIDTH,
        ) {
            if (onOpenBookHub != null) {
                MenuActionRow(Icons.AutoMirrored.Filled.MenuBook, stringResource(R.string.story_reader_menu_book_hub), onGlass.primary, AppTheme.colors.accent.text) {
                    setExpanded(false); onOpenBookHub()
                }
                MenuHairline(divider)
            }
            LiuliStoryReaderToggleRow(
                title = stringResource(R.string.story_reader_menu_animations),
                hint = stringResource(R.string.story_reader_menu_animations_hint),
                checked = readingAnimationsEnabled,
                onToggle = onToggleAnimations,
            )
            MenuHairline(divider)
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.story_reader_menu_font_size), fontSize = 11.sp, color = onGlass.secondary)
                Spacer(Modifier.height(6.dp))
                LiuliSegmented(
                    options = storyReaderFontSizeLabels.indices.toList(),
                    selected = fontSizeIndex,
                    label = { stringResource(storyReaderFontSizeLabels[it]) },
                    onSelect = onSetFontSizeIndex,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 开关行（双行式·整行点击切换·开关只作视觉·语义同暖陶 `MenuToggleRow`）。 */
@Composable
private fun LiuliStoryReaderToggleRow(title: String, hint: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val onGlass = LiuliTheme.onGlass
    val switchState = stringResource(if (checked) R.string.a11y_switch_on else R.string.a11y_switch_off)
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!checked) }
            .semantics { role = Role.Switch; stateDescription = switchState }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = onGlass.primary)
            Text(hint, fontSize = 11.sp, color = onGlass.secondary)
        }
        LiuliSwitch(checked = checked, onCheckedChange = null)
    }
}

/**
 * 底部玻璃进度坞「‹ · 进度条 · ›」：进度条可拖（拖动中字显「f%」不显分钟·松手回真实进度）；
 * 完结书末章右侧 = 「开启续篇」。进度两量在坞里读（滚动只重组坞）。
 */
@Composable
internal fun LiuliStoryReaderDock(
    visible: Boolean,
    nav: StoryReaderNav,
    progress: StoryReaderProgress,
    seekEnabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onContinueArc: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val slidePx = with(LocalDensity.current) { DOCK_SLIDE.roundToPx() }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduceMotion) fadeIn(tween(0)) else fadeIn(tween(DOCK_MOTION_MS)) + slideInVertically(tween(DOCK_MOTION_MS)) { slidePx },
        exit = if (reduceMotion) fadeOut(tween(0)) else fadeOut(tween(DOCK_MOTION_MS)) + slideOutVertically(tween(DOCK_MOTION_MS)) { slidePx },
    ) {
        val dark = LocalIsDarkTheme.current
        var seekFraction by remember { mutableStateOf<Float?>(null) }
        val percent by progress.percent // 读点下沉到坞里：滚动只重组坞
        val minutes by progress.remainingMinutes
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(start = READER_DOCK_INSET_H, end = READER_DOCK_INSET_H, bottom = READER_DOCK_BOTTOM)
                .height(READER_DOCK_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(READER_DOCK_GAP),
        ) {
            DockCircle(AppActionIcons.ChevronLeft, R.string.story_reader_prev, enabled = nav.hasPrev, onClick = onPrev)
            Column(
                Modifier.weight(1f).fillMaxHeight().liuliGlass(LiuliShapes.pill, dark).padding(horizontal = DOCK_PILL_PAD_H),
                verticalArrangement = Arrangement.Center,
            ) {
                val seeking = seekFraction
                Text(
                    storyReaderProgressLabel(seeking?.let { (it * 100).toInt() } ?: percent, if (seeking != null) 0 else minutes),
                    style = AppTypography.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.W500),
                    color = LiuliTheme.onGlass.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(DOCK_LABEL_GAP))
                LiuliSlider(
                    value = seeking ?: (percent / 100f),
                    onValueChange = { f -> seekFraction = f; onSeek(f) },
                    onValueChangeFinished = { seekFraction = null; onSeekEnd() },
                    enabled = seekEnabled,
                )
            }
            if (nav.showContinueArc) {
                LiuliButton(onClick = onContinueArc, style = LiuliButtonStyle.Glass) {
                    Text(stringResource(R.string.story_reader_continue_arc), fontWeight = FontWeight.Bold)
                }
            } else {
                DockCircle(AppActionIcons.ChevronRight, R.string.story_reader_next, enabled = nav.hasNext, onClick = onNext)
            }
        }
    }
}

/** 坞两端圆钮：外套 44 盒让 48 触达外溢不占版（同 [LiuliPageCircleAction] 法）。 */
@Composable
private fun DockCircle(icon: ImageVector, @StringRes cdRes: Int, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(READER_DOCK_HEIGHT), contentAlignment = Alignment.Center) {
        LiuliCircleButton(onClick, stringResource(cdRes), size = READER_DOCK_HEIGHT, enabled = enabled) {
            Icon(icon, null, Modifier.size(LiuliPageGeometry.chromeIcon))
        }
    }
}
