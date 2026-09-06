package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.chat.ImmersiveMenuAction
import com.situ.aichat.ui.chat.cascadeProgress
import com.situ.aichat.ui.chat.immersiveMenuActionLabel
import com.situ.aichat.ui.chat.immersiveMenuOffset
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.liuli.page.liuliFootprint
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃菜单卡（自 [LiuliImmersiveMenuOverlay] 只搬不改地分出来·图纸 §2.1 行数预算）：一片 20dp 圆角玻璃，
 * 顶行五个表情回应 + 一道发丝 + 动作行。宽**恒 200dp**（契约 §5.7·不再 `IntrinsicSize`）。
 *
 * 卷帘生长、项级联、收起上浮、首项读屏焦点全部照抄暖陶 `ImmersiveMenuCard`；表情行算级联的第 0 项。
 * 玻璃走 `blurEnabled = false`——身后已经是那张一次性磨砂快照（A-1），再取一次 backdrop 既多余又冻不住。
 */
@Composable
internal fun LiuliImmersiveMenuCard(
    entries: List<ImmersiveMenuAction>,
    bubbleBounds: Rect,
    alignEnd: Boolean,
    appear: () -> Float,
    closeShift: () -> Float,
    enabled: Boolean,
    /** 底部被占高度（键盘 / 面板 + 导航栏）——见 [LiuliImmersiveMenuOverlay] 同名形参。 */
    bottomObstructionPx: () -> Int,
    onReact: (String) -> Unit,
    onAction: (ImmersiveMenuAction) -> Unit,
) {
    val density = LocalDensity.current
    val dark = LocalIsDarkTheme.current
    val onGlass = LiuliTheme.onGlass
    val marginPx = with(density) { LiuliChatGeometry.menuMargin.roundToPx() }
    val gapPx = with(density) { LiuliChatGeometry.menuBubbleGap.roundToPx() }
    val closeRisePx = with(density) { MenuCloseRise.toPx() }
    val itemRisePx = with(density) { MenuItemRise.toPx() }
    val firstItemFocus = remember { FocusRequester() }
    val cascadeCount = entries.size + 1
    LaunchedEffect(entries) {
        // 读屏焦点送第一项（Telegram show 后 ~420ms·给入场动画让路）。
        kotlinx.coroutines.delay(A11Y_FOCUS_DELAY_MS)
        runCatching { firstItemFocus.requestFocus() }
    }
    Layout(
        content = {
            Column(
                Modifier
                    .width(LiuliChatGeometry.menuWidth)
                    .graphicsLayer {
                        val close = closeShift()
                        alpha = appear() * (1f - close)
                        translationY = -closeRisePx * close
                    }
                    .drawWithContentClipReveal(appear)
                    .liuliGlass(LiuliShapes.overlay, dark = dark, blurEnabled = false)
                    .padding(MenuCardPadding),
            ) {
                LiuliMenuReactionRow(
                    enabled = enabled,
                    itemRisePx = itemRisePx,
                    cascadeCount = cascadeCount,
                    appear = appear,
                    onReact = onReact,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(MenuHairline)
                        .background(onGlass.primary.copy(alpha = HAIRLINE_ALPHA)),
                )
                entries.forEachIndexed { index, action ->
                    // 语义色冻结（契约 §3.3）：重新生成 = 品牌动作、删除 = error 功能深档、其余玻璃上主字色。
                    val tint = when (action) {
                        ImmersiveMenuAction.REGENERATE -> AppTheme.colors.accent.text
                        ImmersiveMenuAction.DELETE -> AppTheme.colors.status.onError
                        else -> onGlass.primary
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                // 表情行占了第 0 位，动作项从 1 起算。
                                val p = cascadeProgress(appear(), index + 1, cascadeCount, CASCADE_WAVE)
                                alpha = p
                                translationY = -itemRisePx * (1f - p)
                            }
                            .then(if (index == 0) Modifier.focusRequester(firstItemFocus).focusable() else Modifier)
                            .clickable(enabled = enabled, role = Role.Button) { onAction(action) }
                            .heightIn(min = MenuItemHeight)
                            .padding(horizontal = MenuItemPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(menuActionIcon(action), contentDescription = null, tint = tint, modifier = Modifier.size(MenuIconSize))
                        Text(
                            immersiveMenuActionLabel(action),
                            style = AppTypography.label,
                            color = tint,
                            modifier = Modifier.padding(start = MenuLabelGap),
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val offset = immersiveMenuOffset(
                bubble = bubbleBounds,
                menuW = placeable.width,
                menuH = placeable.height,
                screenW = constraints.maxWidth,
                // 键盘 / 面板开着长按（罕见）→ 菜单钳在它们与导航栏之上（E18）。
                screenH = (constraints.maxHeight - bottomObstructionPx()).coerceAtLeast(placeable.height),
                alignEnd = alignEnd,
                marginPx = marginPx,
                gapPx = gapPx,
            )
            placeable.place(offset.x, offset.y)
        }
    }
}

/** 顶行五个表情回应（触达 48 / 脚印 28·`liuliFootprint`；顺序锁 [LiuliMenuReactions]）。 */
@Composable
private fun LiuliMenuReactionRow(
    enabled: Boolean,
    itemRisePx: Float,
    cascadeCount: Int,
    appear: () -> Float,
    onReact: (String) -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val rowLabel = stringResource(R.string.liuli_reaction_row_a11y)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ReactionRowHeight)
            .graphicsLayer {
                val p = cascadeProgress(appear(), 0, cascadeCount, CASCADE_WAVE)
                alpha = p
                translationY = -itemRisePx * (1f - p)
            }
            .padding(horizontal = ReactionRowPadding),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiuliMenuReactions.forEach { emoji ->
            Box(
                Modifier
                    .liuliFootprint(ReactionFootprint)
                    .clickable(enabled = enabled, role = Role.Button) {
                        haptics.selection()
                        onReact(emoji)
                    }
                    .semantics { contentDescription = "$rowLabel $emoji" },
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, style = AppTypography.body.copy(fontSize = ReactionEmojiSize))
            }
        }
    }
}

private fun menuActionIcon(action: ImmersiveMenuAction) = when (action) {
    ImmersiveMenuAction.COPY -> Icons.Filled.ContentCopy
    ImmersiveMenuAction.SAVE_IMAGE -> Icons.Filled.FileDownload
    ImmersiveMenuAction.QUOTE -> Icons.Filled.FormatQuote
    ImmersiveMenuAction.REGENERATE -> Icons.Filled.Refresh
    ImmersiveMenuAction.DELETE -> Icons.Filled.Delete
}

/** 卷帘生长（Telegram backScaleY 的裁剪式等价·非 View 缩放）：内容自顶向下按进度显露。 */
private fun Modifier.drawWithContentClipReveal(progress: () -> Float): Modifier = drawWithContent {
    val reveal = progress().coerceIn(0f, 1f)
    clipRect(top = 0f, left = 0f, right = size.width, bottom = size.height * reveal) {
        this@drawWithContent.drawContent()
    }
}

// 落值（图纸 §3.2 菜单一节·孤值即打回）。
private val MenuCardPadding = 6.dp
private val MenuItemPadding = 12.dp
private val MenuIconSize = 17.dp
private val MenuLabelGap = 12.dp
private const val HAIRLINE_ALPHA = 0.12f
private val ReactionRowHeight = 44.dp
private val ReactionRowPadding = 4.dp
private val ReactionFootprint = 28.dp
private val ReactionEmojiSize = 20.sp
