package com.situ.aichat.ui.liuli.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.chat.ChatInputPanelState
import com.situ.aichat.ui.chat.ChatPanelItem
import com.situ.aichat.ui.chat.ChatSheetsState
import com.situ.aichat.ui.chat.ChatViewModel
import com.situ.aichat.ui.chat.QuoteTextOnlyHintState
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppPanelIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.designsystem.liuliPressable
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃「+」变形面板（图纸 2026-09-05 卷二B §4.4 · 契约 §5.2 · A-5）：一片**导航层玻璃**，自「+」原地长出
 * （`scaleIn` 的变换原点钉在圆钮那一点），宽 = 输入区宽、底距导航栏 12、圆角 24。
 *
 * 与暖陶的分叉只有「住哪一层」：暖陶把面板画在内容层白卡里，琉璃画在 overlay 玻璃片上（分叉 1·§3.3）。
 * **机制零碰**：高度仍是 `ChatInputPanelState.regionPx(ime − navBar)`（键盘高度实时自适应硬指标·
 * PLUS_PANEL 契约），六入口构造与 `photoPicker` 自 [LiuliChatPanelRegion] **整段搬**（含三处引用拦截与
 * vision 显隐），逐字未改。内容层那一份退为透明占位——托盘、面板、列表底三者读同一个 `regionPx`。
 */
@Composable
internal fun BoxScope.LiuliPlusPanel(
    viewModel: ChatViewModel,
    sheets: ChatSheetsState,
    inputPanel: ChatInputPanelState,
    replyTarget: MessageEntity?,
    quoteHint: QuoteTextOnlyHintState,
    isOfflineMode: Boolean,
    chatModelHasVision: Boolean,
    reduceMotion: Boolean,
    /** 面板 / 键盘当前高度（布局 lambda 里取值·组合期绝不读 ime——审计 P4）。 */
    regionPx: () -> Int,
) {
    val dark = LocalIsDarkTheme.current
    // 发图入口（拍板③「选完即发」）：系统 Photo Picker 多选，选完直接逐张成消息发出（照抄）。
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_CHAT_IMAGES_PER_PICK),
    ) { uris -> if (uris.isNotEmpty()) viewModel.sendImages(uris) }

    val items = buildList {
        // 见面期隐藏 送礼 / 红包 / 见面 / 约见面（2026-06-21 用户拍板·照抄）。
        if (!isOfflineMode) {
            add(ChatPanelItem(AppPanelIcons.Gift, "送礼") { inputPanel.dismiss(reduceMotion); sheets.showGiftSheet = true })
            add(ChatPanelItem(AppPanelIcons.RedPacket, "红包") { inputPanel.dismiss(reduceMotion); sheets.showRedPacketSheet = true })
        }
        add(
            ChatPanelItem(AppPanelIcons.Sticker, "表情") {
                inputPanel.dismiss(reduceMotion)
                // 引用一期 E·拦截③：带引用时不开表情面板，只弹提示。
                if (replyTarget != null) {
                    quoteHint.trigger()
                    return@ChatPanelItem
                }
                sheets.showPicker = true
            },
        )
        // 「照片」按**聊天对话模型的视觉能力**显隐（2026-08-29 用户拍板·照抄）。
        if (chatModelHasVision) {
            add(
                ChatPanelItem(AppPanelIcons.Photo, "照片") {
                    inputPanel.dismiss(reduceMotion)
                    // 引用一期 E·拦截②：带引用时不拉起系统选图器，只弹提示。
                    if (replyTarget != null) {
                        quoteHint.trigger()
                        return@ChatPanelItem
                    }
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
        }
        if (!isOfflineMode) {
            add(ChatPanelItem(AppPanelIcons.Meet, "见面") { inputPanel.dismiss(reduceMotion); sheets.showManualMeetingSheet = true })
            add(ChatPanelItem(AppPanelIcons.FutureMeet, "约见面") { inputPanel.dismiss(reduceMotion); sheets.showFutureMeetingSheet = true })
        }
    }

    AnimatedVisibility(
        visible = inputPanel.panelOpen,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(
                start = LiuliChatGeometry.inputSide,
                end = LiuliChatGeometry.inputSide,
                bottom = LiuliChatGeometry.panelBottom,
            )
            // 高度只在布局相位算（键盘动画帧只重排、不重组）；与内容层占位同帧同数。
            // 几何（复核 R1 🟡-3 改定·图纸 §3.2 公式勘误）：托盘自带 inputBottom 离屏底，三片行底 = 面板区顶 +
            // inputBottom；面板顶要落在三片行底 − panelTop、面板底落在导航栏顶 + panelBottom ⇒
            // h = regionPx − panelBottom + inputBottom − panelTop（panelBottom == inputBottom ⇒ = regionPx − 6dp）。
            // 旧式 regionPx − (panelTop + panelBottom) 会让面板顶离三片行 18dp 而非 6dp（装机实测 18.7dp）。
            .layout { measurable, constraints ->
                val inset = (LiuliChatGeometry.panelTop + LiuliChatGeometry.panelBottom - LiuliChatGeometry.inputBottom).roundToPx()
                val h = (regionPx() - inset).coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(placeable.width, h) { placeable.place(0, 0) }
            },
        enter = if (reduceMotion) {
            EnterTransition.None
        } else {
            fadeIn(tween(PANEL_FADE_IN_MS)) +
                scaleIn(AppMotion.gentleSpring(), initialScale = PANEL_MORPH_SCALE, transformOrigin = PlusOrigin)
        },
        exit = if (reduceMotion) {
            ExitTransition.None
        } else {
            fadeOut(tween(PANEL_FADE_OUT_MS)) +
                scaleOut(AppMotion.gentleSpring(), targetScale = PANEL_MORPH_SCALE, transformOrigin = PlusOrigin)
        },
        label = "liuliPlusPanel",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .liuliGlass(RoundedCornerShape(LiuliChatGeometry.panelCorner), dark = dark)
                .padding(top = PANEL_CONTENT_TOP, start = PANEL_CONTENT_SIDE, end = PANEL_CONTENT_SIDE),
            verticalArrangement = Arrangement.spacedBy(PANEL_ROW_GAP),
        ) {
            items.chunked(PANEL_COLUMNS).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    rowItems.forEach { LiuliPanelTile(it, Modifier.weight(1f)) }
                    // 不足一排的格留在左侧，尾列等宽占位补齐（绝不拉伸 / 居中·照抄暖陶排布）。
                    repeat(PANEL_COLUMNS - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 面板一格：44dp 圆角方底 + 22dp 图标 + 标签。禁用态只降透明**不改结构**（REDLINES §7）。 */
@Composable
private fun LiuliPanelTile(item: ChatPanelItem, modifier: Modifier = Modifier) {
    val haptics = LocalAppHaptics.current
    val onGlass = LiuliTheme.onGlass
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .liuliPressable(interactionSource = interaction, enabled = item.enabled, brighten = false)
            .clickable(
                enabled = item.enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = {
                    haptics.selection()
                    item.onClick()
                },
            )
            .alpha(if (item.enabled) 1f else TILE_DISABLED_ALPHA),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(LiuliChatGeometry.panelTileSize)
                .clip(RoundedCornerShape(LiuliChatGeometry.panelTileCorner))
                .background(onGlass.primary.copy(alpha = TILE_FILL_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            // 标签即无障碍名（整格的语义由 clickable 的 Role.Button + 标签文字合成·照抄暖陶口径）。
            Icon(item.icon, null, Modifier.size(TILE_ICON_SIZE), tint = AppTheme.colors.accent.text)
        }
        Spacer(Modifier.height(TILE_LABEL_GAP))
        Text(item.label, style = AppTypography.caption, color = onGlass.secondary, textAlign = TextAlign.Center)
    }
}

// 落值（图纸 §3.2 面板一节 / §4.10 表·孤值即打回）。
private const val PANEL_COLUMNS = 3
private val PANEL_CONTENT_TOP = 16.dp
private val PANEL_CONTENT_SIDE = 12.dp
private val PANEL_ROW_GAP = 14.dp
private val TILE_ICON_SIZE = 22.dp
private val TILE_LABEL_GAP = 8.dp
private const val TILE_FILL_ALPHA = 0.06f
private const val TILE_DISABLED_ALPHA = 0.38f
private const val PANEL_FADE_IN_MS = 120
private const val PANEL_FADE_OUT_MS = 160
private const val PANEL_MORPH_SCALE = 0.3f

/** 变形原点 = 「+」圆钮那一点（左 6% / 顶缘）——面板从它身上长出来，而不是从中心炸开。 */
private val PlusOrigin = TransformOrigin(0.06f, 0f)

/** 一次最多选几张（照抄暖陶 `ChatBottomBar` 的同名 private 常量·两侧同值）。 */
private const val MAX_CHAT_IMAGES_PER_PICK = 9
