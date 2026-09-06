package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardSurface
import com.situ.aichat.ui.liuli.designsystem.liuliPressable

/** 图标块内的图标尺寸（§3.2「卡片」：IconTile 40 / 12）。 */
private val TILE_ICON = 22.dp

/**
 * 琉璃纸白卡的通用外壳（§3.2「卡片」）：`liuliCardSurface(medium)` = 20 圆角纸面 + 0.5 发丝、**无软影**，
 * 内距 [contentPadding]（默认 16·身份卡 20），整卡可点。`clickable` 排在 `liuliCardSurface`（内含 `clip`）
 * **之后**，否则 ripple 是矩形、从四个圆角漏出来（PITFALLS §1d·卷二C R1 🟡-2）。
 *
 * [decor] 排在卡面**之后、内容之前**：要「画在卡内、发丝之内」的装饰（身份卡顶沿微光）挂这里——挂在 [modifier]
 * 上会画到卡面**底下**（被纸面盖住）且溢出到卡外（R1 🔴-2）。
 */
@Composable
internal fun LiuliHubCard(
    onClick: () -> Unit,
    onClickLabel: String,
    modifier: Modifier = Modifier,
    contentPadding: Dp = LiuliHomeGeometry.cardPad,
    decor: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .liuliPressable(interactionSource = interaction, enabled = true, brighten = false)
            .liuliCardSurface(LiuliShapes.medium)
            .then(decor)
            .clickable(interaction, LocalIndication.current, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(contentPadding),
        content = content,
    )
}

/** 三条 strip 共用的头行：标题 + 副标 + 尾件槽 + chevron。 */
@Composable
internal fun LiuliStripHeader(
    title: String,
    subText: String,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(title, style = AppTypography.titleSmall, color = colors.text.primary)
        Spacer(Modifier.width(8.dp))
        Text(subText, style = AppTypography.snackbarBody, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        trailing()
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(LiuliHomeGeometry.chevron), colors.text.tertiary)
    }
}

/** 图标块（A-12）：40 见方、圆角 12，底色按功能族取（色族沿用暖陶·契约 §3.1 #3）。 */
@Composable
fun LiuliIconTile(icon: ImageVector, tint: Color, ink: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(LiuliHomeGeometry.tile).clip(RoundedCornerShape(LiuliHomeGeometry.tileCorner)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(TILE_ICON))
    }
}

/**
 * 横排版卡壳（图标块 + 两行字 + chevron 这类入口行）。[surface] = false 时只做点击面 + 内距，
 * 卡面留给外层（礼物条那样「一张卡装两半」的场合）。
 */
@Composable
internal fun LiuliHubRow(
    onClick: () -> Unit,
    onClickLabel: String,
    modifier: Modifier = Modifier,
    surface: Boolean = true,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .liuliPressable(interactionSource = interaction, enabled = true, brighten = false)
            .then(if (surface) Modifier.liuliCardSurface(LiuliShapes.medium) else Modifier)
            .clickable(interaction, LocalIndication.current, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(LiuliHomeGeometry.cardPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}
