package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardSurface
import com.situ.aichat.ui.liuli.designsystem.liuliPressable
import com.situ.aichat.ui.liuli.page.liuliTouchHeight

/** 引导落值（图纸 §3.2「C6 锁定项」+ §4.13·孤值即打回）。 */
// 心情圈 = 顶栏同一枚 `LiuliMoodRing`（环 2 / scale 1→1.5·只搬不改），图纸曾写的 2.5 / 1.45 是对版稿
// 示意值，R2 裁 D-C6-4 以「顶栏同参」为准、图纸 §3.2 / §4.13 已改写。
private val RING = 96.dp
private val AVATAR = 86.dp
private val GAP_RING_NAME = 10.dp
private val GAP_NAME_PERSONA = 4.dp
private val GAP_PERSONA_HINT = 10.dp
private val GAP_HINT_PILLS = 8.dp
private val PILL_GAP = 8.dp
private val PILL_PADDING_H = 14.dp
private val PILL_PADDING_V = 8.dp

/** 三句开场白与提示语照抄暖陶原字面（F29·§9 ①「锁定文本」，对版稿的「试着说一句」只是示意）。 */
private const val STARTER_HINT = "试试这样开场："
private val STARTERS = listOf("早上好呀～", "在忙什么呢？", "给我讲个故事吧")

/**
 * 琉璃版空会话引导（图纸 2026-09-05 卷二C §4.13 · A-18 · 对版稿 C 甲 · 照抄源 F29
 * `ui/chat/ChatScreenParts.kt:81-150`）。
 *
 * 它住**内容层**：纸面不上玻璃（契约 §3.1 #2）。与暖陶的分别只在长相——呼吸头像换成与顶栏
 * **同一枚**心情圈（[LiuliMoodRing]·取色走 [liuliMoodRingColor] 同一处），三句开场白换成纸白胶囊；
 * 文案、三句原字、点击即发的行为一字不动。
 *
 * 触达 48 由 [liuliTouchHeight] 上下外溢撑起（胶囊版位仍是「字高 + 8/14 内距」·§3.2）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiuliEmptyHint(
    characterName: String,
    avatarPath: String?,
    persona: String,
    moodEmoji: String,
    onStarter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()
    val displayName = characterName.ifEmpty { "?" }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LiuliMoodRing(ringColor = liuliMoodRingColor(moodEmoji), size = RING, breathing = !reduceMotion) {
            CharacterAvatar(displayName, avatarPath, AVATAR)
        }
        Spacer(Modifier.height(GAP_RING_NAME))
        Text(displayName, style = AppTypography.titleSmall, color = colors.text.primary)
        if (persona.isNotBlank()) {
            Spacer(Modifier.height(GAP_NAME_PERSONA))
            Text(
                persona,
                style = AppTypography.kaiQuote,
                color = colors.text.secondary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(GAP_PERSONA_HINT))
        Text(STARTER_HINT, style = AppTypography.snackbarBody, color = colors.text.tertiary)
        Spacer(Modifier.height(GAP_HINT_PILLS))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PILL_GAP, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(PILL_GAP),
        ) {
            STARTERS.forEach { starter -> LiuliStarterPill(text = starter, onClick = { onStarter(starter) }) }
        }
    }
}

/** 开场白胶囊**视觉体**的测试标签（R2 🟡-3 回归锁：视觉高 = 字高 + 8/14 内距，绝不被 48 触达框撑高）。 */
internal const val LIULI_STARTER_PILL_TAG = "liuli_starter_pill"

/**
 * 一枚纸白开场白胶囊（`liuliCardSurface(pill)` + 昼 1dp 接触影 + 按压缩 0.96·触达 48 不占版）。
 *
 * 触达与视觉**分两层**（同 `LiuliCardButton` / `LiuliChip` 的写法）：外层只挂 [liuliTouchHeight] + `clickable`
 * （点击面 48），视觉体在内层 Box——把 `liuliCardSurface` 排在 `liuliTouchHeight` 之后会让胶囊本体被量成 48 高
 * （R2 🟡-3·装机 c6_13 实证胶囊粗了一圈）。
 */
@Composable
private fun LiuliStarterPill(text: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .liuliTouchHeight()
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = { haptics.light(); onClick() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .liuliPressable(interactionSource = interaction, enabled = true, brighten = false)
                .liuliCardContactShadow(LiuliShapes.pill)
                .liuliCardSurface(LiuliShapes.pill)
                .testTag(LIULI_STARTER_PILL_TAG),
        ) {
            Text(
                text,
                style = AppTypography.secondary,
                color = colors.accent.text,
                modifier = Modifier.padding(horizontal = PILL_PADDING_H, vertical = PILL_PADDING_V),
            )
        }
    }
}
