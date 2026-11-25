package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.LocalIndication
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTileTone
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.designsystem.liuliPressable
import com.situ.aichat.ui.liuli.designsystem.liuliToneFill
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 图标块内的图标尺寸（§3.2「卡片」：IconTile 40 / 12）。 */
private val TILE_ICON = 22.dp

/**
 * 琉璃卡的通用外壳（§3.2「卡片」）：半透明卡片（卷三·`liuliCardMaterial(medium)` = 20 圆角 + 形状外柔影 + 半透明底 +
 * 顶沿高光 + 1dp 白边），内距 [contentPadding]（默认 16·身份卡 20），整卡可点。`clickable` 排在 `liuliCardMaterial`
 * （内含 `clip`）**之后**，否则 ripple 是矩形、从四个圆角漏出来（PITFALLS §1d·卷二C R1 🟡-2）。
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
            .liuliPressable(interactionSource = interaction, enabled = true)
            .liuliCardMaterial(LiuliShapes.medium, LocalIsDarkTheme.current)
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
        Icon(AppActionIcons.ChevronRight, null, Modifier.size(LiuliHomeGeometry.chevron), colors.text.tertiary)
    }
}

/** 图标块（A-12·卷三 §4.10）：40 见方、圆角 12，底 = 六色渐变 [LiuliTileTone]（与设置砖 / 加号面板共用 [liuliToneFill]）+ 白图标 22。 */
@Composable
fun LiuliIconTile(icon: ImageVector, tone: LiuliTileTone, modifier: Modifier = Modifier) {
    Box(
        modifier.size(LiuliHomeGeometry.tile).liuliToneFill(tone, RoundedCornerShape(LiuliHomeGeometry.tileCorner)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.White, modifier = Modifier.size(TILE_ICON))
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
            .liuliPressable(interactionSource = interaction, enabled = true)
            .then(if (surface) Modifier.liuliCardMaterial(LiuliShapes.medium, LocalIsDarkTheme.current) else Modifier)
            .clickable(interaction, LocalIndication.current, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(LiuliHomeGeometry.cardPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}
