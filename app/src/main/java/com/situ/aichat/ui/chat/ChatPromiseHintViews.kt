package com.situ.aichat.ui.chat

import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Handshake
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 约定记账「当场提示」胶囊 · 暖陶脸（图纸 2026-09-06 约定工具调用化 §4.1·mockup
 * `fable5_artifacts/mockups/promise_live_hint_mockup.html`）。消息区顶部一条 4 秒自动消失的轻提示；
 * 「记下了」那条带「不是约定」一键撤（D-2 点了直接生效不二次确认）。
 *
 * 本件不持业务状态、不调仓库——态与计时全在 [ChatPromiseToolHandler]，点击只回调 VM。
 * 琉璃脸另有自己的 `LiuliPromiseHint`（双脸规则），两脸共用的只有 [PromiseHint] 类型与 [promiseHintText]。
 */
@Composable
internal fun BoxScope.ChatPromiseHint(
    hint: PromiseHint?,
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
        modifier = Modifier.align(Alignment.TopCenter),
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
        shown?.let { PromiseHintCapsule(it, onOpenLedger, onUndo, onDismiss) }
    }
}

@Composable
private fun PromiseHintCapsule(
    hint: PromiseHint,
    onOpenLedger: () -> Unit,
    onUndo: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val haptics = LocalAppHaptics.current
    val res = LocalContext.current.resources
    val container = when (hint.kind) {
        PromiseHint.Kind.RECORDED, PromiseHint.Kind.MERGED -> colors.accent.container
        PromiseHint.Kind.FULFILLED -> colors.status.successContainer
        PromiseHint.Kind.CANCELLED, PromiseHint.Kind.UNDONE -> colors.surface.sunken
    }
    val fg = when (hint.kind) {
        PromiseHint.Kind.RECORDED, PromiseHint.Kind.MERGED -> colors.accent.text
        PromiseHint.Kind.FULFILLED -> colors.status.onSuccess
        PromiseHint.Kind.CANCELLED, PromiseHint.Kind.UNDONE -> colors.text.secondary
    }
    val undoUuid = hint.undoUuid.takeIf { hint.kind == PromiseHint.Kind.RECORDED }
    val showClose = hint.kind != PromiseHint.Kind.UNDONE && undoUuid == null
    // 右侧有件（动作词 / ×）时内距收窄给它让位；UNDONE 无右件 → 左右对称。
    val endPad = if (undoUuid != null || showClose) 4.dp else 12.dp

    // 触达 48 / 视觉 36：外层只管点击与外溢（requiredHeight 超约束子项自动居中），视觉在内层（PITFALLS 1d）。
    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .height(36.dp)
            .requiredHeight(48.dp)
            .clickable(role = Role.Button, onClickLabel = res.getString(R.string.chat_promise_hint_open_ledger)) { onOpenLedger() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                // 影排在 clip 之前 = 软影画在形状外不被裁（PITFALLS 1d）；深色主题不上影。
                .then(if (dark) Modifier else Modifier.shadow(1.dp, AppShapes.large, clip = false))
                .clip(AppShapes.large)
                .background(container)
                .padding(start = 12.dp, end = endPad)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(promiseHintIcon(hint.kind), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Box(Modifier.size(8.dp))
            Text(
                text = promiseHintText(hint, res),
                style = AppTypography.secondary,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (undoUuid != null) {
                val actionBg = if (dark) colors.text.primary.copy(alpha = 0.08f) else colors.surface.raised.copy(alpha = 0.35f)
                // 触达 / 视觉分两层（复核 R1 🟡-1）：外层只管 32→48 外溢与点击；视觉胶囊（clip + 底色）在内层 32 高。
                // clip/background 若排在 requiredHeight 之后会画成 48 高、再被 Row 的 clip 裁成通栏色块（PITFALLS 1d）。
                Box(
                    modifier = Modifier
                        .testTag(TAG_UNDO)
                        .padding(start = 8.dp)
                        .height(32.dp)
                        .requiredHeight(48.dp)
                        .clickable(role = Role.Button) { haptics.light(); onUndo(undoUuid) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(AppShapes.full)
                            .background(actionBg)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(res.getString(R.string.chat_promise_hint_not_promise), style = AppTypography.snackbarAction, color = fg)
                    }
                }
            } else if (showClose) {
                Box(
                    modifier = Modifier
                        .testTag(TAG_CLOSE)
                        .padding(start = 4.dp)
                        .size(32.dp)
                        .requiredSize(48.dp)
                        .clickable(role = Role.Button, onClickLabel = res.getString(R.string.action_close)) { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** 测试锚（两个动作面在合并语义树里读不出自己·量触达与点击都靠它）。 */
internal const val TAG_UNDO = "promiseHintUndo"
internal const val TAG_CLOSE = "promiseHintClose"

/** 五态图形（两脸共用口径·图纸 §4.1 表）。 */
internal fun promiseHintIcon(kind: PromiseHint.Kind): ImageVector = when (kind) {
    PromiseHint.Kind.RECORDED, PromiseHint.Kind.MERGED -> Icons.Filled.Handshake
    PromiseHint.Kind.FULFILLED -> Icons.Filled.CheckCircle
    PromiseHint.Kind.CANCELLED -> Icons.Filled.Cancel
    PromiseHint.Kind.UNDONE -> Icons.AutoMirrored.Filled.Undo
}

/** 合并提示的连接符（图纸 §4.4·两语同·不进资源）。 */
internal const val PROMISE_HINT_JOINER = " · "

/**
 * 提示文案组装（纯函数·两张脸共用·图纸 §4.1 表）：前缀常规字重、约定内容加粗；MERGED 为单段计数文案。
 */
internal fun promiseHintText(hint: PromiseHint, res: Resources): AnnotatedString {
    if (hint.kind == PromiseHint.Kind.MERGED) {
        val parts = buildList {
            if (hint.recorded > 0) add(res.getString(R.string.chat_promise_hint_count_recorded, hint.recorded))
            if (hint.fulfilled > 0) add(res.getString(R.string.chat_promise_hint_count_fulfilled, hint.fulfilled))
            if (hint.cancelled > 0) add(res.getString(R.string.chat_promise_hint_count_cancelled, hint.cancelled))
        }
        return AnnotatedString(parts.joinToString(PROMISE_HINT_JOINER))
    }
    if (hint.kind == PromiseHint.Kind.UNDONE) return AnnotatedString(res.getString(R.string.chat_promise_hint_undone))
    val template = when (hint.kind) {
        PromiseHint.Kind.RECORDED -> R.string.chat_promise_hint_recorded
        PromiseHint.Kind.FULFILLED -> R.string.chat_promise_hint_fulfilled
        else -> R.string.chat_promise_hint_cancelled
    }
    // 模板形如「记下了 · %1$s」：内容段加粗，其余常规（拆点 = 占位符在整句里的位置）。
    val full = res.getString(template, hint.content)
    val prefixEnd = (full.length - hint.content.length).coerceAtLeast(0)
    return buildAnnotatedString {
        append(full.substring(0, prefixEnd))
        withStyle(SpanStyle(fontWeight = FontWeight(520))) { append(full.substring(prefixEnd)) }
    }
}
