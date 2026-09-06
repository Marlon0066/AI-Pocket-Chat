package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.chat.truncateScheduleSubtitle
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppPanelIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.liuli.page.liuliFootprint
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃聊天顶栏（图纸 2026-09-05 卷二A §4.2 · 契约 §5.1「顶栏 A · 三片分体」）：
 * 返回圆钮 40 / 名片胶囊 44（呼吸环 + 头像 + 名 + 副标）/ 通话圆钮 40（见面态换「结束见面」玻璃胶囊），
 * 片间 10dp、状态栏底 + 6dp 起、左右 12dp。三片各是**独立玻璃片**（导航层才上玻璃·契约 §1）。
 *
 * 副标回退链（卷二B 起完整）= **「此刻」内心一句**（[rememberLiuliInnerStateLine]）→ `scheduleStatus`
 * （截 8 字素簇·复用暖陶 [truncateScheduleSubtitle]）→ 心情行。「此刻」两个字**不显示**——对版稿里那是标注。
 * 玻璃上字色恒 `onGlass.*`，情绪色只上呼吸环。
 */
@Composable
internal fun LiuliChatTopBar(
    characterName: String,
    loading: Boolean,
    avatarPath: String?,
    /** 副标链首位：活人感内核【此刻】那一段的第一句（无内容 / 成长系统关 ⇒ null·A-6）。 */
    innerStateLine: String?,
    scheduleStatus: String?,
    moodEmoji: String,
    moodText: String,
    isInOfflineMode: Boolean,
    characterUuid: String?,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onEndMeeting: () -> Unit,
    canStartCall: Boolean,
    onStartCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val onGlass = LiuliTheme.onGlass
    val openProfileLabel = stringResource(R.string.chat_open_profile)
    // B2 照抄：会话/角色都未就绪前不显假名（占位圆恒定高度·无跳变）。
    val displayName = if (loading) "" else characterName.ifEmpty { "聊天" }
    // 心情圈取色与呼吸圈本体 2026-09-05 卷二C C6c 只搬不改抽到 LiuliMoodRing.kt（空会话引导共用同一枚）。
    val ringColor = liuliMoodRingColor(moodEmoji)

    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(start = LiuliChatGeometry.topBarSide, end = LiuliChatGeometry.topBarSide, top = LiuliChatGeometry.topBarInsetTop)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LiuliChatGeometry.topBarPieceGap),
    ) {
        // 触达框不占版（复核 R1 🔴-2）：Row 高恒 = 名片 44，§4.7 `chromeBottom` 算式才成立。
        LiuliCircleButton(onClick = onBack, contentDescription = stringResource(R.string.action_back), modifier = Modifier.liuliFootprint(40.dp), size = 40.dp) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(20.dp)) // 圆钮甲：钴蓝
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(LiuliChatGeometry.topBarHeight)
                .liuliGlass(LiuliShapes.pill, dark = dark)
                .then(
                    characterUuid?.let { uuid ->
                        Modifier.clickable(onClickLabel = openProfileLabel, role = Role.Button) { onOpenProfile(uuid) }
                    } ?: Modifier,
                )
                .padding(start = 2.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LiuliMoodRing(ringColor = ringColor, size = 40.dp, breathing = !rememberReduceMotion()) {
                if (loading) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(colors.surface.sunken))
                } else {
                    CharacterAvatar(displayName, avatarPath, 34.dp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    displayName,
                    style = AppTypography.nameTopBar,
                    color = onGlass.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                LiuliTopBarSubtitle(innerStateLine, scheduleStatus, moodEmoji, moodText, onGlass.secondary)
            }
        }
        if (isInOfflineMode) {
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .liuliGlass(LiuliShapes.pill, dark = dark)
                    .clickable(role = Role.Button, onClick = onEndMeeting)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 文案与暖陶同字面（ChatTopBar.kt:160·非资源·照抄）。
                Text("结束见面", style = AppTypography.label, color = onGlass.primary)
            }
        }
        if (!isInOfflineMode && canStartCall) {
            LiuliCircleButton(onClick = onStartCall, contentDescription = stringResource(R.string.voice_call_entry), modifier = Modifier.liuliFootprint(40.dp), size = 40.dp) {
                Icon(AppPanelIcons.Call, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(20.dp)) // 圆钮甲：钴蓝
            }
        }
    }
}

/**
 * 副标唯一活槽（图纸 §4.9）：「此刻」→ 日程 → 心情行；切换 Crossfade（reduceMotion 直切）。
 * Crossfade 的 target 取「此刻 ?: 日程」——两者都空时才落到心情行，心情变化不该单独触发一次交叉淡化。
 */
@Composable
private fun LiuliTopBarSubtitle(
    innerStateLine: String?,
    scheduleStatus: String?,
    moodEmoji: String,
    moodText: String,
    color: Color,
) {
    if (rememberReduceMotion()) {
        LiuliTopBarSubtitleContent(innerStateLine, scheduleStatus, moodEmoji, moodText, color)
    } else {
        Crossfade(
            targetState = innerStateLine ?: scheduleStatus,
            animationSpec = tween(AppMotion.SMOOTH_MS),
            label = "liuliTopBarSubtitle",
        ) { top ->
            // top = 链前两档合流的结果：等于「此刻」那句就原样显（内心行本就短），否则它是日程串、按 8 字素簇截。
            val fromInner = top != null && top == innerStateLine
            LiuliTopBarSubtitleContent(
                innerStateLine = if (fromInner) top else null,
                scheduleStatus = if (fromInner) null else top,
                moodEmoji = moodEmoji,
                moodText = moodText,
                color = color,
            )
        }
    }
}

@Composable
private fun LiuliTopBarSubtitleContent(
    innerStateLine: String?,
    scheduleStatus: String?,
    moodEmoji: String,
    moodText: String,
    color: Color,
) {
    val text = when {
        innerStateLine != null -> innerStateLine
        scheduleStatus != null -> truncateScheduleSubtitle(scheduleStatus)
        moodEmoji.isEmpty() && moodText.isEmpty() -> return
        else -> listOf(moodEmoji, moodText).filter { it.isNotEmpty() }.joinToString(" ")
    }
    Text(
        text = text,
        style = AppTypography.settingsRowValue,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

