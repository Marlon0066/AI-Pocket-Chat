package com.situ.aichat.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.LocalAppHaptics

/**
 * Fable-5 底部弹层「托盘」（M3 清零卷一·总契约 §2.2·2026-07-17 草图过审）。
 *
 * 契约「行为重组件包壳不重写」：手势 / 弹簧 / scrim / 无障碍全交 M3 [ModalBottomSheet]，本件只锁四件皮——
 * 顶角 [AppShapes.sheet]（28dp）、底色 `surface.raised`、自研短把手 [AppSheetHandle]、内容纸感内衬
 * （浅色呼吸白 [breathingRaisedFill] + [grainSurface]；深色纯 raised + 顶缘月光沿）。
 * scrim / tonalElevation / windowInsets 等一律**不传**，保 M3 默认（现状不变）。
 *
 * [sheetState] 必须透传——站点各有 `skipPartiallyExpanded` 等既有配置。
 * [title] 题头槽仅供**今后新弹层**：全库 32 站收编一律不传（站点自带题头零碰·契约 §2.2「收编口径」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    title: String? = null,
    onClose: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = AppShapes.sheet,
        containerColor = colors.surface.raised,
        dragHandle = { AppSheetHandle() },
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (!colors.isDark) {
                // 浅色纸感：呼吸白纵向渐变（顶端 = raised，与容器同色 → 把手区无接缝）。
                Box(Modifier.matchParentSize().background(breathingRaisedFill()))
            }
            Box(Modifier.matchParentSize().grainSurface())
            if (colors.isDark) {
                // 深色 raised 的「月光沿」：顶缘 1dp 内高光（[AppElevation.MOONLINE_ALPHA]）。
                val moonline = colors.text.primary.copy(alpha = AppElevation.MOONLINE_ALPHA)
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind {
                            val w = 1.dp.toPx()
                            drawLine(
                                color = moonline,
                                start = Offset(0f, w / 2f),
                                end = Offset(size.width, w / 2f),
                                strokeWidth = w,
                            )
                        },
                )
            }
            Column {
                if (title != null) AppSheetTitleRow(title = title, onClose = onClose)
                content()
            }
        }
    }
}

/**
 * 自研短把手（36×4dp·上下各 12dp 净距·[AppShapes.full] 圆角）——替 M3 默认宽把手。
 * 色 = 深色 `surface.stroke` / 浅色 `text.primary` 12%（与菜单发丝同构的浅深二元）。
 */
@Composable
fun AppSheetHandle() {
    val colors = AppTheme.colors
    val handleColor = if (colors.isDark) colors.surface.stroke else colors.text.primary.copy(alpha = 0.12f)
    Box(
        Modifier
            .padding(top = 12.dp, bottom = 12.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(AppShapes.full)
            .background(handleColor),
    )
}

/** 题头行：左标题（[AppTypography.titleSmall]）+ 右关闭圆点（26dp·6% 底·✕ 16dp·触达 48dp）。 */
@Composable
private fun AppSheetTitleRow(title: String, onClose: (() -> Unit)?) {
    val colors = AppTheme.colors
    Row(
        // 屏 gutter 恒 20（设计语言 §2.5 军规）：弹层横跨全宽，题头的标题与关闭钮即贴屏缘元素。
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.screenGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AppTypography.titleSmall, color = colors.text.primary)
        Spacer(Modifier.weight(1f))
        if (onClose != null) {
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(26.dp)
                    .clip(AppShapes.full)
                    .background(colors.text.primary.copy(alpha = 0.06f))
                    .closeDotClickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = colors.text.secondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 关闭圆点的点击接线（品牌 ripple + `haptics.light()`·与 [AppDialogGhostButton] 同约定）。 */
@Composable
private fun Modifier.closeDotClickable(onClick: () -> Unit): Modifier {
    val haptics = LocalAppHaptics.current
    return clickable(role = Role.Button, onClick = { haptics.light(); onClick() })
}
