package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.liuli.page.liuliFootprint
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃引用条（图纸 2026-09-05 卷二B §4.2 · 契约 §5.2）：18dp 圆角玻璃条 + 左缘 2.5dp 钴蓝竖条 +
 * 「引用 {名}」与摘要合成的**一行** + 右侧 ✕。替换暖陶 `ReplyPreview`。栈式间距逐项自带
 * （调用方给 `padding(bottom = stackGap)`）——`spacedBy` 会给 0 高的提示条留幽灵缝（卷二A R1 🟡-3）。
 */
@Composable
internal fun LiuliReplyBar(
    senderLabel: String,
    content: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    val cancelLabel = stringResource(R.string.a11y_cancel_quote)
    LiuliQuoteShell(modifier, verticalPadding = BAR_VERTICAL_PADDING, accentBarHeight = null) {
        Text(
            text = buildAnnotatedString {
                withStyle(AppTypography.label.toSpanStyle()) { append("引用 $senderLabel") }
                append(" ")
                withStyle(AppTypography.secondary.toSpanStyle()) { append(content.take(QUOTE_PREVIEW_CHARS)) }
            },
            color = LiuliTheme.onGlass.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 裸图标钮（不用 LiuliCircleButton——那是一片玻璃圆，压在玻璃条上会叠两层片）：脚印 24 不占版、
        // 触达 48 居中外溢（图纸 §9 ⑤：圆钮 / 徽章 / 箭头一律 liuliFootprint）。
        Box(
            Modifier
                .liuliFootprint(CLEAR_FOOTPRINT)
                .clickable(role = Role.Button, onClickLabel = cancelLabel) { haptics.light(); onClear() }
                .semantics { contentDescription = cancelLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, null, Modifier.size(CLEAR_ICON_SIZE), tint = LiuliTheme.onGlass.secondary)
        }
    }
}

/**
 * 「引用时只能发文字」提示条（图纸 §4.2）：同 [LiuliReplyBar] 的玻璃语言、矮一档（32dp）。进出照抄暖陶
 * `QuoteTextOnlyHint`（220ms 淡入 + 半程上滑 / 反向·RM 直显直隐）；3000ms 自动消与「replyTarget 变 null
 * 即消」仍由借来的 `QuoteTextOnlyHintState` 管（机制零碰）。
 */
@Composable
internal fun LiuliQuoteHint(visible: Boolean, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val accent = AppTheme.colors.accent.text
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(HINT_MOTION_MS)) + slideInVertically { it / 2 },
        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(HINT_MOTION_MS)) + slideOutVertically { it / 2 },
    ) {
        LiuliQuoteShell(Modifier.height(HINT_HEIGHT), verticalPadding = 0.dp, accentBarHeight = HINT_ACCENT_BAR_HEIGHT) {
            Icon(Icons.Filled.FormatQuote, null, Modifier.size(HINT_ICON_SIZE), tint = accent)
            Spacer(Modifier.width(ACCENT_BAR_GAP))
            Text(
                stringResource(R.string.chat_quote_text_only_hint),
                style = AppTypography.secondary,
                color = LiuliTheme.onGlass.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 两件共用的壳：玻璃条 + 左缘竖条（[accentBarHeight] 为 null = 随内容全高）。 */
@Composable
private fun LiuliQuoteShell(
    modifier: Modifier,
    verticalPadding: Dp,
    accentBarHeight: Dp?,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .liuliGlass(LiuliShapes.bubble, dark = LocalIsDarkTheme.current)
            .padding(horizontal = BAR_HORIZONTAL_PADDING, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(ACCENT_BAR_WIDTH)
                .then(if (accentBarHeight == null) Modifier.fillMaxHeight() else Modifier.height(accentBarHeight))
                .clip(RoundedCornerShape(percent = 50))
                .background(AppTheme.colors.accent.text),
        )
        Spacer(Modifier.width(ACCENT_BAR_GAP))
        content()
    }
}

// 落值（图纸 §3.2 引用条 / 提示条两节·孤值即打回）。
private val BAR_HORIZONTAL_PADDING = 12.dp
private val BAR_VERTICAL_PADDING = 8.dp
private val ACCENT_BAR_WIDTH = 2.5.dp
private val ACCENT_BAR_GAP = 8.dp
private const val QUOTE_PREVIEW_CHARS = 40
private val CLEAR_FOOTPRINT = 24.dp
private val CLEAR_ICON_SIZE = 18.dp
private val HINT_HEIGHT = 32.dp
private val HINT_ACCENT_BAR_HEIGHT = 18.dp
private val HINT_ICON_SIZE = 15.dp
private const val HINT_MOTION_MS = 220
