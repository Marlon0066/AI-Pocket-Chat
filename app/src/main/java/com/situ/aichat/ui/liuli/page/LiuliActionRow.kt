package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton

/** 钮内图标 24 · 字 12/500 下 6 · 排上 16 下 8（契约 §6.5「动作排」· A-9）。 */
private val ACTION_ICON = 24.dp
private val LABEL_SIZE = 12.sp
private val LABEL_TOP = 6.dp
private val ROW_TOP = 16.dp
private val ROW_BOTTOM = 8.dp

/** 动作排一格：一枚玻璃圆钮 + 一行字。[contentDescription] 给读屏（= 标题本身）。 */
@Immutable
data class LiuliActionItem(
    val icon: ImageVector,
    val label: String,
    val contentDescription: String,
    val onClick: () -> Unit,
)

/**
 * 详情页动作排（契约 §6.5「动作排」· A-9）：n 枚 56 玻璃圆钮（Button 档·图标 24 `accent.text`）+ 字。
 *
 * 版位每格 68（钮 56 居中）· 缝 16 —— 4 格恰好 4×68 + 3×16 = 320 = 360 窄屏的可用宽，不换行（E19）。
 * 只调既有回调，卡内原链接不删（双入口·零新逻辑）。
 */
@Composable
fun LiuliActionRow(items: List<LiuliActionItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().padding(top = ROW_TOP, bottom = ROW_BOTTOM),
        horizontalArrangement = Arrangement.spacedBy(LiuliPageGeometry.actionGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
    ) {
        items.forEach { item ->
            Column(
                modifier = Modifier.width(LiuliPageGeometry.actionSlot),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LiuliCircleButton(
                    onClick = item.onClick,
                    contentDescription = item.contentDescription,
                    size = LiuliPageGeometry.action,
                ) {
                    Icon(item.icon, contentDescription = null, modifier = Modifier.size(ACTION_ICON))
                }
                Text(
                    item.label,
                    style = AppTypography.caption.copy(fontSize = LABEL_SIZE, fontWeight = FontWeight.W500),
                    color = AppTheme.colors.text.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = LABEL_TOP),
                )
            }
        }
    }
}
