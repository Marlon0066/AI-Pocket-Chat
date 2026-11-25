package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.glass.liuliWindowGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import androidx.compose.ui.unit.Dp

/**
 * 菜单落值（§3.2）：宽 160 · 行 40 · 出场缩放起点 0.9。
 *
 * 行**不做** 48 触达外溢（R2 🔴-1）：菜单行是上下紧贴的兄弟，各自外溢 4dp 就互相压住——后一行的点击面
 * 盖进前一行底边 3.5dp，Compose 命中测试后声明者优先，点「改期」底边会触发「取消约定」（不可撤销）。
 * 160×40 的整行本身已是大目标，触达 = 版位。
 */
private val MENU_WIDTH = 160.dp
private val MENU_ROW_HEIGHT = 40.dp
private const val MENU_ENTER_SCALE = 0.9f
/** 行左右内距（原来写在 `Text` 上·抽出来给勾也用同一枚）。 */
private val MENU_ROW_PAD_H = 14.dp
/** 选中勾的尺寸（与行尾 chevron 同档 · 卷五 A-4 ②）与它左侧的缝。 */
private val MENU_CHECK = 14.dp
private val MENU_CHECK_GAP = 8.dp

/**
 * 一条菜单项（[danger] = 不可撤销动作·走 `status.onError` 红字）。
 *
 * [selected]（卷五 A-4 ②·**加法零回归**：默认 false = 与增补前逐字节同渲染）= 这一项是当前值 → 行尾打一枚
 * 勾。给「下拉行」这类**选值**菜单用；动作菜单（倒数条的改期 / 取消）一律不传。
 */
@Immutable
data class LiuliMenuEntry(
    val text: String,
    val danger: Boolean = false,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 琉璃玻璃弹出层底座（琉璃 2.0 卷六·三·下甲·自 [LiuliPopupMenu] 只搬不改抽出）：`Popup` + [liuliWindowGlass] +
 * 0.9 → 1 出场缩放；内容随调用方（[LiuliPopupMenu] 放文字行；阅读器菜单放开关与字号条）。
 * 位置 / 宽 / 抢焦点语义同 [LiuliPopupMenu]。
 */
@Composable
fun LiuliPopupSurface(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    alignment: Alignment = Alignment.TopEnd,
    width: Dp = MENU_WIDTH,
    focusable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val dark = LocalIsDarkTheme.current
    val reduceMotion = rememberReduceMotion()
    val scale = remember { Animatable(if (reduceMotion) 1f else MENU_ENTER_SCALE) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, if (reduceMotion) snap() else AppMotion.gentleSpring())
    }

    Popup(
        // 锚到父级**尾端**向左展开：`···` 贴在横幅右侧，用 TopStart 时 160dp 宽的卡会伸出屏右缘被裁
        // （装机实证·`Popup` 不像 M3 `DropdownMenu` 会自动回弹）。
        alignment = alignment,
        offset = with(LocalDensity.current) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = focusable),
    ) {
        Column(
            modifier = modifier
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .width(width)
                .liuliWindowGlass(LiuliShapes.menu, dark),
            content = content,
        )
    }
}

/**
 * 琉璃弹出菜单（图纸 2026-09-05 卷二C §4.11 · 落值 §3.2 · A-15）。
 *
 * 底座 = `androidx.compose.ui.window.Popup`（**不是** material3·§9 ⑤ 明说它不算 M3），皮 = [liuliWindowGlass] +
 * [LiuliShapes].menu 18 圆角（琉璃 2.0 卷二 §4.7-3）：菜单住独立 window——通透 / 标准档 = 跨窗口真玻璃（Haze 取宿主
 * 内容·大面板加厚配方），毛玻璃档 / 安卓 13 以下 / 不在宿主里 = 不透明兜底。**禁 M3 `DropdownMenu`**。
 *
 * 出场 = 0.9 → 1 缩放（[AppMotion].gentleSpring·[rememberReduceMotion] 时直显）。行高恒 40 = 触达
 * （紧贴的兄弟行不外溢·见 [MENU_ROW_HEIGHT]）；行间无分隔线（卷二 §4.7-3）。点击先 `haptics.light()`
 * 再回调、再关菜单。
 *
 * 位置：`Popup(alignment = TopEnd, offset)`——菜单**右上角**对齐锚点的右上角、向左展开；要让它落在
 * 锚点**下方**，调用方传 `offset.y = 锚点高 + 间距`（倒数条就是这么用的），别让菜单盖在锚点身上。
 */
@Composable
fun LiuliPopupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<LiuliMenuEntry>,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    /** 锚点对齐（卷五复核 R1 补·默认 TopEnd = 增补前行为）：靠屏左的锚（「加载预设」芯片）要 TopStart 往右展开。 */
    alignment: Alignment = Alignment.TopEnd,
    /** 菜单宽（默认 160 = §3.2）：条目是「服务商 模型名」这类长串时给宽些（可输入下拉 / 功能分配）。 */
    width: Dp = MENU_WIDTH,
    /**
     * 是否抢焦点（默认 true = 增补前行为）。挂在**输入框**下面的候选菜单必须 false：抢了焦点键盘就落到菜单上、
     * 正在打字的框失焦立刻收起（卷五复核 R1 D1）。
     */
    focusable: Boolean = true,
) {
    if (!expanded) return
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val haptics = LocalAppHaptics.current
    LiuliPopupSurface(expanded, onDismiss, modifier, offset, alignment, width, focusable) {
        items.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MENU_ROW_HEIGHT)
                    .clickable(role = Role.Button) { haptics.light(); entry.onClick(); onDismiss() }
                    .padding(horizontal = MENU_ROW_PAD_H),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.text,
                    style = AppTypography.label,
                    color = if (entry.danger) colors.status.onError else onGlass.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (entry.selected) {
                    Spacer(Modifier.width(MENU_CHECK_GAP))
                    Icon(
                        AppActionIcons.Check,
                        contentDescription = null,
                        tint = colors.accent.text,
                        modifier = Modifier.size(MENU_CHECK),
                    )
                }
            }
        }
    }
}
