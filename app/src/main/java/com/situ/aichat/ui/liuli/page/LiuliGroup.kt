package com.situ.aichat.ui.liuli.page

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes

/**
 * 二级屏「iOS 内嵌圆角分组」三件套（契约 §6.5 · 用户 Q-S2 甲）：组壳 [LiuliGroup]、行基线 [LiuliRowBase]、
 * 图标砖 [LiuliGroupIconTile]。
 *
 * 组 = `surface.raised` 纸白 + 0.5 发丝描边 + 16 圆角，**无软影**（琉璃只有导航层是玻璃、内容层是纸）。
 * 组内行间发丝从 56 起（有砖）/ 16 起（无砖），**首行不画**。
 *
 * 发丝为什么是**行自己画在顶边**而不是组壳按序插：组里常有 `AnimatedVisibility` 包着的高级门行——
 * 它隐着时是 0 高节点，若由组壳在行与行之间插发丝就会留一条孤线（PITFALLS §1d「幽灵缝」的同族）。
 * 行自己画 = 行隐则线隐。代价是**首行要显式传 `divider = false`**（组内第一行不画），由
 * `LiuliGroupRowsTest` 钉住。
 */
@Composable
fun LiuliGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    Column(modifier.fillMaxWidth().padding(bottom = LiuliPageGeometry.groupGap)) {
        if (header != null) LiuliGroupHeader(header)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(LiuliShapes.group)
                .background(colors.surface.raised)
                .border(0.5.dp, colors.surface.stroke, LiuliShapes.group),
            content = content,
        )
        if (footer != null) LiuliGroupFooter(footer)
    }
}

/** 组标题：13/500 `text.tertiary` 字距 .06em · 左 16 · 下 8；挂 `heading()`（同暖陶 `SettingsGroupCard`）。 */
@Composable
fun LiuliGroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = AppTypography.secondary.copy(
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
            letterSpacing = 0.06.em,
        ),
        color = AppTheme.colors.text.tertiary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = LiuliPageGeometry.groupPadH, bottom = LiuliPageGeometry.groupHeaderBottom)
            .semantics { heading() },
    )
}

/** 组脚注：13/400 `text.tertiary` · 左 16 · 上 6 · 行高 1.4。 */
@Composable
fun LiuliGroupFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = AppTypography.secondary.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W400),
        color = AppTheme.colors.text.tertiary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = LiuliPageGeometry.groupPadH, top = LiuliPageGeometry.groupFooterTop),
    )
}

/**
 * 行基线（契约 §6.5「行基线」）：最小高 52（两行 64）· 左右内距 16 · 按压时行底染 `surface.sunken` 80ms。
 *
 * 按压反馈**不用** `liuliPressable`：那一枚是「缩 0.96 + 叠亮」的钮语汇，整行缩放不是 iOS 列表的长相；
 * 契约写的是「行底 surface.sunken 80ms」，故本件自画（§11 D-2）。
 *
 * [divider] = 是否画顶发丝（组内第一行传 false）；[dividerInset] 有砖 56 / 无砖 16。
 */
@Composable
fun LiuliRowBase(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** 整行点击面挂在**外层**（开关 / 单选行的 `toggleable` / `selectable`）时把它的 interaction 传进来，行底按压染色才跟得上。 */
    interactionSource: MutableInteractionSource? = null,
    /** 读屏念的动作名（如「聊天」）；null = 默认「点击」。 */
    onClickLabel: String? = null,
    enabled: Boolean = true,
    minHeight: Dp = LiuliPageGeometry.rowMin,
    divider: Boolean = true,
    dividerInset: Dp = LiuliPageGeometry.dividerInsetPlain,
    role: Role = Role.Button,
    verticalPadding: Dp = 0.dp,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = AppTheme.colors
    val own = remember { MutableInteractionSource() }
    val interaction = interactionSource ?: own
    val pressed by interaction.collectIsPressedAsState()
    val press by animateColorAsState(
        targetValue = if (pressed && enabled && (onClick != null || interactionSource != null)) colors.surface.sunken else Color.Transparent,
        animationSpec = tween(ROW_PRESS_MS),
        label = "liuliRowPress",
    )
    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(press)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            enabled = enabled,
                            interactionSource = interaction,
                            indication = null,
                            role = role,
                            onClickLabel = onClickLabel,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .heightIn(min = minHeight)
                .padding(horizontal = LiuliPageGeometry.groupPadH, vertical = verticalPadding),
            verticalAlignment = verticalAlignment,
            content = content,
        )
        if (divider) {
            // 0.5dp 无指针的装饰条，压在行顶边（Compose 命中测试只认带 pointerInput 的节点，点击照常落到行上）。
            Box(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = dividerInset)) {
                // 发丝自成一个布局节点（内缩由**外层** Box 承担）：语义坐标取的是节点本身的位置，
                // 内缩若与 testTag 挂在同一条链上，量到的仍是内缩之前的满宽框（实测 left = 0）。
                Box(
                    Modifier
                        .testTag(LIULI_ROW_DIVIDER_TAG)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(colors.surface.stroke),
                )
            }
        }
    }
}

/** 行按压染色时长（契约 §6.5「行基线」）。 */
private const val ROW_PRESS_MS = 80

/**
 * 顶发丝的测试标记：发丝是纯装饰、无语义节点，不打标就没法在 Robolectric 里**量**它的起点
 * （只能回读构造参数 = 自证式断言·PITFALLS §1e）。生产期零影响（`testTag` 不改布局不吃指针）。
 */
const val LIULI_ROW_DIVIDER_TAG = "liuliRowDivider"

/** 图标砖：28×28 圆角 7 实色 + 白图标 16（契约 §6.5·色取 `LiuliPalette` 十砖色）。 */
@Composable
fun LiuliGroupIconTile(icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(LiuliPageGeometry.tile)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(LiuliPageGeometry.tileCorner))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.White, modifier = Modifier.size(TILE_ICON))
    }
}

/** 砖内图标尺寸（契约 §6.5「白图标 16」）。 */
private val TILE_ICON = 16.dp
