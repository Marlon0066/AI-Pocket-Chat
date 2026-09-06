package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.designsystem.AppProfileIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign

/**
 * 分组内的「看 / 走」行族（契约 §6.5「行基线」/「值行」/「危险行」）：
 * [LiuliNavRow]（砖 + 标题 + 副 + 值 + badge + chevron）· [LiuliValueRow]（无 chevron 的值行）·
 * [LiuliDangerRow]（红字危险行）· [LiuliStatusDotRow]（色点状态行·向量模型状态用）。
 *
 * 全部落在 [LiuliRowBase] 上（行高 / 内距 / 按压 / 顶发丝单源）。
 */

/** 标题 16/400 · 行高 1.3（契约 §6.5）。 */
private val TITLE_SIZE = 16.sp
private val TITLE_LINE = 21.sp
/** 副标 13 · 上 2。 */
private val SUB_SIZE = 13.sp
private val SUB_TOP = 2.dp
/** 右值 15 · 值 ↔ chevron 8 · chevron 12。 */
private val VALUE_SIZE = 15.sp
private val VALUE_GAP = 8.dp
private val CHEVRON = 12.dp
/** 两行行的上下内距（单源 [LiuliPageGeometry.rowTwoLinePad]）。 */
private val TWO_LINE_PAD = LiuliPageGeometry.rowTwoLinePad
/** badge 药丸底透明度（同暖陶 `AppSettingsRow` 的 12%）。 */
private const val BADGE_BG_ALPHA = 0.12f
/** 状态点直径（同暖陶 `EmbedderStatusRow` 的 10dp）。 */
private val STATUS_DOT = 10.dp

/** 行内标题 + 可选副标（两者共用一列·标题在上）。 */
@Composable
private fun RowScope.RowTitle(title: String, subtitle: String?, color: Color) {
    Column(Modifier.weight(1f)) {
        Text(
            title,
            style = AppTypography.body.copy(fontSize = TITLE_SIZE, lineHeight = TITLE_LINE, fontWeight = FontWeight.W400),
            color = color,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = AppTypography.secondary.copy(fontSize = SUB_SIZE),
                color = AppTheme.colors.text.secondary,
                modifier = Modifier.padding(top = SUB_TOP),
            )
        }
    }
}

/**
 * 行尾值（[warning] = 「未配置」这类要警示的值·走 `status.onWarning`）。`internal` 供同包的 [LiuliMenuRow]
 * 复用——下拉行的右值必须与值行逐字同长相（卷五 A-4 ②）。
 */
@Composable
internal fun LiuliRowValue(
    value: String,
    warning: Boolean,
    modifier: Modifier = Modifier,
    /** 覆盖色（性能采集清单「已采够」走 `status.onSuccess`·卷五复核 R1 A-4）；null = 常态 / 警示二选一。 */
    color: Color? = null,
    /** 长值行数上限（下拉行的「服务商 模型名」给 2·值行默认 1）。 */
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    val colors = AppTheme.colors
    Text(
        value,
        style = AppTypography.secondary.copy(fontSize = VALUE_SIZE),
        color = color ?: if (warning) colors.status.onWarning else colors.text.secondary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier,
    )
}

/** 行禁用态透明度（与 [LiuliSwitch] / [LiuliButton] 同值）。 */
private const val ROW_DISABLED_ALPHA = 0.38f

/**
 * 文字动作行（卷五复核 R1 补·性能采集 / 日志设置的「导出 / 清除…」这类**按钮语义**的行）：
 * 标题 16 `accent.text` 左对齐、无砖无 chevron 无右值（值行拿空串冒充会多一个空文本节点、也没有可点的暗示）；
 * [enabled] = false 整行淡到 38% 且不吃点击（忙碌时行**留在原位**·别整行消失让组跳高）。
 * 危险动作用 [LiuliDangerRow]。
 */
@Composable
fun LiuliTextActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    divider: Boolean = true,
) {
    LiuliRowBase(
        modifier = modifier.alpha(if (enabled) 1f else ROW_DISABLED_ALPHA),
        onClick = onClick,
        enabled = enabled,
        divider = divider,
    ) {
        RowTitle(title, subtitle = null, color = AppTheme.colors.accent.text)
    }
}

/**
 * 导航行：可选图标砖 + 标题（+ 副标）+ 可选行尾值 / badge + chevron。
 *
 * [divider] 组内第一行传 false；发丝起点随有无砖自动取 56 / 16。
 */
@Composable
fun LiuliNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tileColor: Color? = null,
    subtitle: String? = null,
    value: String? = null,
    valueWarning: Boolean = false,
    badge: String? = null,
    divider: Boolean = true,
) {
    val colors = AppTheme.colors
    LiuliRowBase(
        modifier = modifier,
        onClick = onClick,
        minHeight = if (subtitle != null) LiuliPageGeometry.rowTwoLine else LiuliPageGeometry.rowMin,
        verticalPadding = if (subtitle != null) TWO_LINE_PAD else 0.dp,
        divider = divider,
        dividerInset = if (icon != null) LiuliPageGeometry.dividerInsetTile else LiuliPageGeometry.dividerInsetPlain,
    ) {
        if (icon != null && tileColor != null) {
            LiuliGroupIconTile(icon, tileColor)
            Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        }
        RowTitle(title, subtitle, colors.text.primary)
        if (value != null) {
            Spacer(Modifier.width(LiuliPageGeometry.tileGap))
            LiuliRowValue(value, valueWarning)
        }
        if (badge != null) {
            Spacer(Modifier.width(VALUE_GAP))
            Text(
                badge,
                style = AppTypography.secondary.copy(fontSize = VALUE_SIZE),
                color = colors.accent.text,
                maxLines = 1,
                modifier = Modifier
                    .background(colors.accent.primary.copy(alpha = BADGE_BG_ALPHA), LiuliShapes.pill)
                    .padding(horizontal = VALUE_GAP, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(VALUE_GAP))
        Icon(
            AppProfileIcons.ChevronRight,
            contentDescription = null,
            tint = colors.text.tertiary,
            modifier = Modifier.size(CHEVRON),
        )
    }
}

/** 值行（契约 §6.5）：标题 + 行尾值，**无 chevron**（点开的是单选面板 / 菜单，不是下一层页）；[onClick] = null 即只读。 */
@Composable
fun LiuliValueRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    tileColor: Color? = null,
    subtitle: String? = null,
    valueWarning: Boolean = false,
    divider: Boolean = true,
    /** 右值覆盖色（卷五复核 R1 A-4·null = 常态）。 */
    valueColor: Color? = null,
    /** 禁用（卷五复核 R1 A-5·默认 true = 增补前行为）：整行淡到 38%、不吃点击，位置不变。 */
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    LiuliRowBase(
        modifier = modifier.alpha(if (enabled) 1f else ROW_DISABLED_ALPHA),
        onClick = onClick,
        enabled = enabled,
        minHeight = if (subtitle != null) LiuliPageGeometry.rowTwoLine else LiuliPageGeometry.rowMin,
        verticalPadding = if (subtitle != null) TWO_LINE_PAD else 0.dp,
        divider = divider,
        dividerInset = if (icon != null) LiuliPageGeometry.dividerInsetTile else LiuliPageGeometry.dividerInsetPlain,
    ) {
        if (icon != null && tileColor != null) {
            LiuliGroupIconTile(icon, tileColor)
            Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        }
        RowTitle(title, subtitle, colors.text.primary)
        Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        LiuliRowValue(value, valueWarning, color = valueColor)
    }
}

/** 危险行（契约 §6.5）：16/400 `status.error` 左对齐、无砖无 chevron；确认由调用方接 `LiuliDialog(confirmDanger)`。 */
@Composable
fun LiuliDangerRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    /** 禁用（卷五复核 R1 A-5·默认 true = 增补前行为）：淡到 38%、不吃点击，行留在原位。 */
    enabled: Boolean = true,
) {
    LiuliRowBase(
        modifier = modifier.alpha(if (enabled) 1f else ROW_DISABLED_ALPHA),
        onClick = onClick,
        enabled = enabled,
        divider = divider,
    ) {
        RowTitle(title, subtitle = null, color = AppTheme.colors.status.onError)
    }
}

/**
 * 色点状态行（暖陶 `EmbedderStatusRow` 的琉璃版）：砖位放一枚 10dp 色点 + 标题（+ 副标）+ 行尾状态词，
 * **不可点、无箭头**（色点纯装饰·语义由状态词与副标承载）。
 */
@Composable
fun LiuliStatusDotRow(
    title: String,
    status: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    divider: Boolean = true,
) {
    val colors = AppTheme.colors
    LiuliRowBase(
        modifier = modifier,
        minHeight = if (subtitle != null) LiuliPageGeometry.rowTwoLine else LiuliPageGeometry.rowMin,
        verticalPadding = if (subtitle != null) TWO_LINE_PAD else 0.dp,
        divider = divider,
        dividerInset = LiuliPageGeometry.dividerInsetTile,
    ) {
        // 点占砖位（28 宽）中央，让它与上下行的砖左缘对齐。
        Box(Modifier.size(LiuliPageGeometry.tile), contentAlignment = Alignment.Center) {
            Box(Modifier.size(STATUS_DOT).background(dotColor, CircleShape))
        }
        Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        RowTitle(title, subtitle, colors.text.primary)
        Spacer(Modifier.width(LiuliPageGeometry.tileGap))
        LiuliRowValue(status, warning = false)
    }
}
