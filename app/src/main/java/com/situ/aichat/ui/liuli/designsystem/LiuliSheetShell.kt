package com.situ.aichat.ui.liuli.designsystem

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.page.liuliFootprint
import com.situ.aichat.ui.liuli.glass.liuliWindowGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃底部弹层壳（图纸 2026-09-05 卷二C §4.11 · A-14 · 落值 §3.2「C6 锁定项」）。
 *
 * 机制层照借 M3 [ModalBottomSheet]（拖拽关闭 / 返回键 / IME 让位 / 独立 window / 无障碍），只拆它的脸：
 * `containerColor = Transparent` + `dragHandle = null` + [LiuliShapes].sheetFloating（顶 38 / 底 32）+ 18% 黑 scrim，
 * 玻璃画在内容最外层、四周（左右下）各留 8dp 悬浮。这是 §9 ⑤ 对 material3 放行的两处机制用法之一——
 * [ModalBottomSheet] / [rememberModalBottomSheetState] 只许出现在本文件。
 *
 * 玻璃（琉璃 2.0 卷二 §4.7-1）：[liuliWindowGlass]——通透 / 标准档取宿主的跨窗口取景源走 Haze 真玻璃
 * （大面板加厚配方），毛玻璃档 / 安卓 13 以下 / 不在宿主里走卷一「只着色兜底」（不透明·观感 ≈ 原纸垫底）。
 *
 * [content] 自带 `imePadding` / `verticalScroll` / `navigationBarsPadding` 与左右 20 内距——各弹层照抄
 * 暖陶原修饰链，壳不代劳（壳一旦统一加内距，网格 / 满宽钮那几站就得再减回去）。
 * [onClose] 默认走 [onDismissRequest]；[title] 为 null 时整条题头行不画。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiuliSheetShell(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    title: String? = null,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = LiuliShapes.sheetFloating,
        containerColor = Color.Transparent,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = SHEET_SCRIM_ALPHA),
    ) {
        Column(
            Modifier
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .fillMaxWidth()
                .liuliWindowGlass(LiuliShapes.sheetFloating, dark),
        ) {
            // 把手：36×4 圆角条，色 [LiuliMaterials.handle]（卷二 §4.7-1），上下各 12dp 净距（§3.2）。
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(LiuliShapes.pill)
                    .background(LiuliMaterials.handle(dark)),
            )
            if (title != null) {
                LiuliSheetTitleRow(title = title, subtitle = subtitle, onClose = onClose ?: onDismissRequest)
            }
            content()
        }
    }
}

/** scrim = 18% 黑（§3.2·比 M3 默认淡——玻璃自己已经把身后压了一层）。 */
private const val SHEET_SCRIM_ALPHA = 0.18f

/** 关闭圆的底色 = 玻璃上主文字 8%。 */
private const val CLOSE_DOT_ALPHA = 0.08f

/** 关闭圆的视觉直径与 ✕ 尺寸（§3.2·触达 48 由 [liuliFootprint] 外溢撑起，不占版位）。 */
private val CLOSE_DOT = 26.dp
private val CLOSE_ICON = 14.dp

/** 题头行（§3.2）：左标题 `titleSmall` + 可选副标 `snackbarBody`，右关闭圆。 */
@Composable
private fun LiuliSheetTitleRow(title: String, subtitle: String?, onClose: () -> Unit) {
    val onGlass = LiuliTheme.onGlass
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.titleSmall, color = onGlass.primary)
            if (subtitle != null) {
                Text(subtitle, style = AppTypography.snackbarBody, color = onGlass.secondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        LiuliCloseDot(onClose)
    }
}

/**
 * 玻璃题头 / 玻璃胶囊上的关闭圆（26dp 视觉 · 触达 48 外溢不占版 · cd = `action_close`）。
 * 内层再套一层 26dp 的圆底，否则底色会铺满 48dp 的触达框。
 */
@Composable
internal fun LiuliCloseDot(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val onGlass = LiuliTheme.onGlass
    val haptics = LocalAppHaptics.current
    val label = stringResource(R.string.action_close)
    Box(
        modifier = modifier
            .liuliFootprint(CLOSE_DOT)
            .clickable(role = Role.Button, onClickLabel = label) { haptics.light(); onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(CLOSE_DOT)
                .clip(CircleShape)
                .background(onGlass.primary.copy(alpha = CLOSE_DOT_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppActionIcons.Close, contentDescription = label, tint = onGlass.secondary, modifier = Modifier.size(CLOSE_ICON))
        }
    }
}
