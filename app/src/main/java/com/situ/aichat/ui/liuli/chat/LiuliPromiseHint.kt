package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.chat.PromiseHint
import com.situ.aichat.ui.chat.TAG_CLOSE
import com.situ.aichat.ui.chat.TAG_UNDO
import com.situ.aichat.ui.chat.promiseHintIcon
import com.situ.aichat.ui.chat.promiseHintText
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliCloseDot
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.liuli.page.liuliTouchHeight
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 落值（图纸 2026-09-06 §4.2·孤值即打回）。 */
private val HINT_HEIGHT = 38.dp // = LiuliBanners.TOAST_HEIGHT 同族
private val HINT_GAP = 8.dp
private val HINT_DOT = 20.dp
private val HINT_GLYPH = 12.dp
private val UNDO_H_PAD = 10.dp
private val WEIGHT_520 = FontWeight(520)

/**
 * 约定记账「当场提示」玻璃胶囊 · 琉璃脸（图纸 2026-09-06 约定工具调用化 §4.2·风格范式
 * [LiuliCalendarToast]）。与暖陶版共用的只有 [PromiseHint] 类型、[promiseHintText] / [promiseHintIcon]
 * 纯函数与 VM 三个 API——**绝不 import 暖陶 composable**（双脸规则）。
 *
 * 触达 / 视觉分两层：外层只挂 [liuliTouchHeight] + clickable，视觉在内层 Row（PITFALLS 1d·
 * 视觉链绝不排在 touchHeight 之后）。
 */
@Composable
internal fun BoxScope.LiuliPromiseHint(
    hint: PromiseHint?,
    topPadding: Dp,
    reduceMotion: Boolean,
    onOpenLedger: () -> Unit,
    onUndo: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 退场期间 hint 已变 null，内容取「最后一个非空」，否则收起动画里文字会先消失。
    var shown by remember { mutableStateOf(hint) }
    if (hint != null) shown = hint
    AnimatedVisibility(
        visible = hint != null,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = topPadding),
        enter = if (reduceMotion) {
            EnterTransition.None
        } else {
            fadeIn(AppMotion.effectMediumSpring()) + expandIn(AppMotion.gentleSpring(IntSize.VisibilityThreshold))
        },
        exit = if (reduceMotion) {
            ExitTransition.None
        } else {
            shrinkOut(AppMotion.gentleSpring(IntSize.VisibilityThreshold)) + fadeOut(AppMotion.effectMediumSpring())
        },
    ) {
        shown?.let { LiuliPromiseHintCapsule(it, onOpenLedger, onUndo, onDismiss) }
    }
}

@Composable
private fun LiuliPromiseHintCapsule(
    hint: PromiseHint,
    onOpenLedger: () -> Unit,
    onUndo: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val dark = LocalIsDarkTheme.current
    val haptics = LocalAppHaptics.current
    val res = LocalContext.current.resources
    val dot = when (hint.kind) {
        PromiseHint.Kind.RECORDED, PromiseHint.Kind.MERGED -> colors.accent.primary
        PromiseHint.Kind.FULFILLED -> colors.status.onSuccess
        PromiseHint.Kind.CANCELLED, PromiseHint.Kind.UNDONE -> onGlass.secondary
    }
    val undoUuid = hint.undoUuid.takeIf { hint.kind == PromiseHint.Kind.RECORDED }
    val showClose = hint.kind != PromiseHint.Kind.UNDONE && undoUuid == null
    val endPad = if (undoUuid != null || showClose) 6.dp else 14.dp

    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .liuliTouchHeight()
            .clickable(role = Role.Button, onClickLabel = res.getString(R.string.chat_promise_hint_open_ledger)) { onOpenLedger() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(HINT_HEIGHT)
                .liuliGlass(LiuliShapes.pill, dark = dark)
                .padding(start = 12.dp, end = endPad)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HINT_GAP),
        ) {
            Box(Modifier.size(HINT_DOT).clip(CircleShape).background(dot), contentAlignment = Alignment.Center) {
                Icon(
                    promiseHintIcon(hint.kind),
                    contentDescription = null,
                    tint = colors.text.onAccent,
                    modifier = Modifier.size(HINT_GLYPH),
                )
            }
            Text(
                text = promiseHintText(hint, res),
                style = AppTypography.snackbarBody.copy(fontWeight = WEIGHT_520),
                color = onGlass.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (undoUuid != null) {
                Box(
                    modifier = Modifier
                        .testTag(TAG_UNDO)
                        .liuliTouchHeight()
                        .clickable(role = Role.Button) { haptics.light(); onUndo(undoUuid) }
                        .padding(horizontal = UNDO_H_PAD),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        res.getString(R.string.chat_promise_hint_not_promise),
                        style = AppTypography.snackbarAction,
                        color = colors.accent.text,
                    )
                }
            } else if (showClose) {
                LiuliCloseDot(onDismiss, modifier = Modifier.testTag(TAG_CLOSE))
            }
        }
    }
}
