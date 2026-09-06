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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import com.situ.aichat.data.model.GlassTier
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.page.liuliFootprint
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃底部弹层壳（图纸 2026-09-05 卷二C §4.11 · A-14 · 落值 §3.2「C6 锁定项」）。
 *
 * 机制层照借 M3 [ModalBottomSheet]（拖拽关闭 / 返回键 / IME 让位 / 独立 window / 无障碍），只拆它的脸：
 * `containerColor = Transparent` + `dragHandle = null` + [LiuliShapes].sheet 顶角 38 + 18% 黑 scrim，
 * 玻璃画在内容最外层。这是 §9 ⑤ 对 material3 放行的两处机制用法之一——[ModalBottomSheet] /
 * [rememberModalBottomSheetState] 只许出现在本文件。
 *
 * 玻璃恒取 [GlassTier].TINTED 着色档（不跟随用户档位）：弹层在独立 window 里拿不到 `LocalBackdrop`，
 * [liuliGlass] 本就会退成纯染色；**壳底再垫一层 `surface.raised` 纸面**（R2 P-1·用户选①）——只靠 88% 平染
 * 身后的聊天文字会不模糊地透上来（装机 c6_07 / c6_19），纸面 + 着色 = 近乎不透明的磨砂纸，最接近对版稿
 * A 甲「字压得住」的那张脸。**不要试图给它传 backdrop**。
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
        shape = LiuliShapes.sheet,
        containerColor = Color.Transparent,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = SHEET_SCRIM_ALPHA),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .liuliGlass(LiuliShapes.sheet, dark = dark, tier = GlassTier.TINTED)
                // 独立 window 拿不到 backdrop、玻璃退成 88% 平染会把身后聊天文字不模糊地透上来
                // （R2 P-1·用户 2026-09-05 选①）：在玻璃层**里面**铺一层纸面（排在 liuliGlass 之后 = 被它的
                // 形状裁切、盖在染色之上、在迎光 / 发丝之下）。纸面若铺在层外面，层仍是半透明的，
                // 玻璃自己的软影会从层里透出来、在卡中央留一块「没影子的亮方块」（装机 r2_03 / p1_04 实证）。
                .background(AppTheme.colors.surface.raised),
        ) {
            // 把手：36×4 圆角条，玻璃上主文字色 18%，上下各 12dp 净距（§3.2）。
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(LiuliShapes.pill)
                    .background(LiuliTheme.onGlass.primary.copy(alpha = HANDLE_ALPHA)),
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

/** 把手色 = 玻璃上主文字 18%。 */
private const val HANDLE_ALPHA = 0.18f

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
            Icon(Icons.Default.Close, contentDescription = label, tint = onGlass.secondary, modifier = Modifier.size(CLOSE_ICON))
        }
    }
}
