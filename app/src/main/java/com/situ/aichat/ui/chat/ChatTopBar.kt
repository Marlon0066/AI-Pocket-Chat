package com.situ.aichat.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppIconButton
import com.situ.aichat.ui.designsystem.AppPanelIcons
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.OnGlass
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.GlassBackdrop

// Fable-5 聊天顶栏自绘件（契约 §3.1·从 ChatScreen 抽出·纯搬 composable）。
// ChatTopBar(头像+双行信息块=单一档案入口·右缘结束见面/编辑·壁纸态悬浮玻璃丸) private→internal 供主屏调；
// TopBarActionButton/TopBarSubtitle/TopBarSubtitleContent 留 private(仅本文件内调)。

/**
 * Fable-5 顶栏（D2）：36dp 头像引领的左对齐双行信息块（名 16sp/520 + 副标题 13sp/420），整块=单一
 * 档案入口（role=Button + onClickLabel）；右缘=通话圆钮（电话图标·仅见面外），线下见面中改显「结束见面」
 * 幽灵胶囊（accent.text 描边）。**沉浸决议（2026-06-13 用户拍板）**：顶栏=surface.base 与消息区同色无缝（白色只留给
 * 内容纸张·气泡/卡片），0 阴影 0 分隔线。不附加状态栏 inset（外层 Scaffold 已供给·E3）。
 */
@Composable
internal fun ChatTopBar(
    characterName: String,
    loading: Boolean,
    avatarPath: String?,
    scheduleStatus: String?,
    moodEmoji: String,
    moodText: String,
    moodColorName: String,
    isInOfflineMode: Boolean,
    characterUuid: String?,
    wallpaperFrosted: ImageBitmap?,
    wallpaperDark: Boolean,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onEndMeeting: () -> Unit,
    canStartCall: Boolean,
    onStartCall: () -> Unit,
) {
    val colors = AppTheme.colors
    val glass = wallpaperFrosted != null
    // chunk3 玻璃顶栏（契约 §4）：有壁纸→透明栏 + 悬浮玻璃丸（返回圆/信息丸/通话圆），内容色按**顶部那块壁纸**亮度
    // 自适应（要素⑤）；无壁纸→原 surface.base 实心栏、原 token 色，逐像素现状（§3.4）。
    // 审计 T1：玻璃上内容色换 OnGlass 单源（值逐位同）。
    val onContent = if (glass) { if (wallpaperDark) OnGlass.PrimaryOnDark else OnGlass.PrimaryOnLight } else colors.text.primary
    val onContentDim = if (glass) { if (wallpaperDark) OnGlass.SecondaryOnDarkTopBar else OnGlass.SecondaryOnLightTopBar } else colors.text.secondary

    // 壁纸全屏沉浸重构②：旧靠骨架垫付让位状态栏；现 NavHost 去 consume → 顶栏自管 statusBarsPadding（内容落状态栏下、
    // 状态栏区显壁纸/容器色）。
    Surface(modifier = Modifier.statusBarsPadding(), color = if (glass) Color.Transparent else colors.surface.base) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = if (glass) 10.dp else 4.dp, vertical = if (glass) 6.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBarActionButton(
                wallpaperFrosted, wallpaperDark, onBack,
                Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onContentDim,
            )
            if (glass) Spacer(Modifier.width(8.dp))
            val openProfileLabel = stringResource(R.string.chat_open_profile)
            val profileModifier = Modifier
                .weight(1f)
                .clip(if (glass) AppShapes.full else AppShapes.small)
                .then(
                    characterUuid?.let { uuid ->
                        Modifier.clickable(onClickLabel = openProfileLabel, role = Role.Button) { onOpenProfile(uuid) }
                    } ?: Modifier,
                )
            // B2：加载中不显假「聊天」+占位字母圈——头像位显中性占位圈、标题留空，就绪即填真名/真头像（36dp 占位高度恒定·无跳变）。
            val displayName = if (loading) "" else characterName.ifEmpty { "聊天" }
            val profileContent: @Composable () -> Unit = {
                Row(
                    modifier = Modifier.padding(horizontal = if (glass) 8.dp else 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (loading) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(colors.surface.sunken))
                    } else {
                        CharacterAvatar(displayName, avatarPath, 36.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            displayName,
                            style = AppTypography.nameTopBar,
                            color = onContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                        TopBarSubtitle(scheduleStatus, moodEmoji, moodText, moodColorName, if (glass) onContentDim else null)
                    }
                }
            }
            if (glass) {
                GlassBackdrop(blurred = wallpaperFrosted, dark = wallpaperDark, shape = AppShapes.full, modifier = profileModifier) { profileContent() }
            } else {
                Box(profileModifier) { profileContent() }
            }
            if (isInOfflineMode) {
                if (glass) Spacer(Modifier.width(8.dp))
                val endLabel: @Composable () -> Unit = {
                    // §4.8：Row 高度锁 44dp（与返回圆钮同高·故事阅读器 ST10-3 islandHeight 教训）·文本垂直居中·水平内边距 14dp。
                    Row(
                        modifier = Modifier.height(44.dp).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "结束见面",
                            style = AppTypography.label,
                            color = if (glass) onContent else colors.accent.text,
                        )
                    }
                }
                if (glass) {
                    GlassBackdrop(
                        blurred = wallpaperFrosted,
                        dark = wallpaperDark,
                        shape = AppShapes.full,
                        modifier = Modifier.clip(AppShapes.full).clickable(role = Role.Button, onClick = onEndMeeting),
                    ) { endLabel() }
                } else {
                    Surface(
                        onClick = onEndMeeting,
                        shape = AppShapes.full,
                        color = Color.Transparent,
                        border = BorderStroke(1.5.dp, colors.accent.text),
                    ) { endLabel() }
                }
            }
            // 右缘=通话圆钮（电话图标·与返回键同款）：仅见面外、且会话/角色就绪才出；线下见面时此处只留上面的「结束见面」。
            if (!isInOfflineMode && canStartCall) {
                if (glass) Spacer(Modifier.width(8.dp))
                TopBarActionButton(
                    wallpaperFrosted, wallpaperDark, onStartCall,
                    AppPanelIcons.Call, stringResource(R.string.voice_call_entry), onContentDim,
                )
            }
        }
    }
}

/** 顶栏圆形操作钮：有壁纸=悬浮玻璃圆（44dp 视觉 + 48dp 触达 + Role.Button），无=原 M3 IconButton。 */
@Composable
private fun TopBarActionButton(
    wallpaperFrosted: ImageBitmap?,
    wallpaperDark: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
) {
    if (wallpaperFrosted != null) {
        GlassBackdrop(
            blurred = wallpaperFrosted,
            dark = wallpaperDark,
            shape = CircleShape,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
        ) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.align(Alignment.Center))
        }
    } else {
        AppIconButton(
            icon = icon,
            contentDescription = contentDescription,
            onClick = onClick,
            tint = tint,
            iconSize = 22.dp,
        )
    }
}

/**
 * 顶栏副标题=唯一活槽（日程态优先·P0-17「补」非「替」口径不动；无则回退心情行）。态切换走 Crossfade
 * 柔性过渡（效果轴·reduceMotion 直切）。
 */
@Composable
private fun TopBarSubtitle(
    scheduleStatus: String?,
    moodEmoji: String,
    moodText: String,
    moodColorName: String,
    textColor: Color? = null,
) {
    if (rememberReduceMotion()) {
        TopBarSubtitleContent(scheduleStatus, moodEmoji, moodText, moodColorName, textColor)
    } else {
        Crossfade(
            targetState = scheduleStatus,
            animationSpec = tween(AppMotion.SMOOTH_MS),
            label = "topBarSubtitle",
        ) { sched ->
            TopBarSubtitleContent(sched, moodEmoji, moodText, moodColorName, textColor)
        }
    }
}

@Composable
private fun TopBarSubtitleContent(
    scheduleStatus: String?,
    moodEmoji: String,
    moodText: String,
    moodColorName: String,
    textColor: Color? = null,
) {
    if (scheduleStatus != null) {
        // P0-17：此刻日程状态优先（截断 8 字素簇·复用 ChatList ticker）。textColor 非空=玻璃上自适应色。
        Text(
            text = truncateScheduleSubtitle(scheduleStatus),
            style = AppTypography.secondary,
            color = textColor ?: AppTheme.colors.text.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        MoodSubtitle(moodEmoji, moodText, moodColorName, textColor)
    }
}
