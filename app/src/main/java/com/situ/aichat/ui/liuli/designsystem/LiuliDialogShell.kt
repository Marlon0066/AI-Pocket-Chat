package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.situ.aichat.data.model.GlassTier
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 弹窗 scrim = 18% 黑（§3.2·与 [LiuliSheetShell] 同值；平台 Dialog 的 dim 默认 ~60%，须显式压下来）。 */
private const val DIALOG_SCRIM_ALPHA = 0.18f

/** 正文最大高（超出自滚·照抄暖陶 `AppDialog` 的 400dp 帽，防长文顶破屏）。 */
private val DIALOG_BODY_MAX_HEIGHT = 400.dp

/**
 * 琉璃确认弹窗「玻璃小卡」（图纸 2026-09-05 卷二C §4.11 · 落值 §3.2）。
 *
 * 底座 = 平台 [Dialog]（`usePlatformDefaultWidth = false` 拿全宽度确定性），皮 = 一片
 * [GlassTier].CLEAR 清透玻璃 + [LiuliShapes].overlay 20dp 圆角；左右各 26dp 让位。弹窗与弹层一样住
 * 独立 window，[liuliGlass] 拿不到 `LocalBackdrop` 会退纯染色——**壳底垫一层 `surface.raised` 纸面**
 * （R2 P-1·用户选①），清透档 60% 白覆在纸面上 = 比纸面再亮一档的磨砂小卡，身后内容不再透字。
 *
 * 空白区点击关闭：平台 Dialog 的 `dismissOnClickOutside` 只认内容之外，而全宽内容把整屏都占了，
 * 所以外层 [Box] 自己挂一次无涟漪 `clickable`（卡本体再挂一次空 `clickable` 吃掉冒泡）。
 */
@Composable
fun LiuliDialogShell(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    paneTitleText: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 平台 dim 压到 18%（§3.2）：Compose 的 Dialog 不暴露 scrim 参数，只能取宿主 window 调。
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(DIALOG_SCRIM_ALPHA) }
        val outside = remember { MutableInteractionSource() }
        val inside = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = outside, indication = null, onClick = onDismissRequest),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp)
                    .let { if (paneTitleText != null) it.semantics { paneTitle = paneTitleText } else it }
                    .liuliGlass(LiuliShapes.overlay, dark = dark, tier = GlassTier.CLEAR)
                    // 独立 window 无 backdrop → 玻璃层里面铺纸面（R2 P-1·用户 2026-09-05 选①·与弹层壳同口径；
                    // 铺在层外面会让软影透过半透明层在卡中央留亮方块）。
                    .background(AppTheme.colors.surface.raised)
                    // 卡面吃掉冒泡，别让「点卡里空白」也关掉弹窗。
                    .clickable(interactionSource = inside, indication = null, onClick = {})
                    .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
                content = content,
            )
        }
    }
}

/**
 * 琉璃确认弹窗（签名对齐暖陶 `AppDialog` 便于逐字换接·§2.1 D-5）。
 *
 * 结构自上而下：标题（`titleSmall`）→ 10dp → 正文（[body] 纯文字自带滚动与高度帽 / [content] 自定义槽
 * 不包滚动）→ 20dp → 钮行（右对齐 `spacedBy(8.dp, End)`）。[confirmDanger] = true 时确认钮走
 * `LiuliButtonStyle.Text` + danger 红字（不可撤销动作），否则 Prominent 药丸；[onDismiss] 为 null 时
 * 取消钮回调走 [onDismissRequest]。[confirmText] 与 [dismissText] 皆 null → 整排不渲染。
 */
@Composable
fun LiuliDialog(
    onDismissRequest: () -> Unit,
    title: String?,
    modifier: Modifier = Modifier,
    body: String? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmDanger: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    LiuliDialogShell(onDismissRequest = onDismissRequest, modifier = modifier, paneTitleText = title) {
        val onGlass = LiuliTheme.onGlass
        if (title != null) Text(title, style = AppTypography.titleSmall, color = onGlass.primary)
        val hasContentArea = body != null || content != null
        if (title != null && hasContentArea) Spacer(Modifier.height(10.dp))
        if (body != null) {
            Column(Modifier.heightIn(max = DIALOG_BODY_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                Text(body, style = AppTypography.dialogBody, color = onGlass.secondary)
            }
        }
        content?.invoke(this)
        if (confirmText != null || dismissText != null) {
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dismissText != null) {
                    LiuliButton(
                        onClick = onDismiss ?: onDismissRequest,
                        style = LiuliButtonStyle.Text,
                    ) { Text(dismissText) }
                }
                if (confirmText != null) {
                    LiuliButton(
                        onClick = { onConfirm?.invoke() },
                        style = if (confirmDanger) LiuliButtonStyle.Text else LiuliButtonStyle.Prominent,
                        enabled = confirmEnabled,
                        danger = confirmDanger,
                    ) { Text(confirmText) }
                }
            }
        }
    }
}
