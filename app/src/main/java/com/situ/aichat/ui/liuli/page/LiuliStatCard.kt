package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes

/** 卡内上下内距 12 / 左右 0（契约 §6.5「内距 12/0」）与列内值→标签的 3。 */
private val CARD_PAD_V = 12.dp
private val LABEL_TOP = 3.dp
/** 列间竖发丝高（比内容略矮，两端留呼吸）。 */
private val DIVIDER_HEIGHT = 28.dp
/** 卡外：上 4 下 16（契约 §6.5）。 */
private val CARD_TOP = 4.dp
private val CARD_BOTTOM = 16.dp

/**
 * 统计卡（Q-S6 甲·用户 2026-09-06 拍板「统计要单独一个框，不做纯文字」·契约 §6.5）。
 *
 * `surface.raised` 16 圆角 + 0.5 发丝 · 等分列（3–5 列）· 列间 0.5 竖发丝 ·
 * 值 18/700 `accent.text` tnum · 标签 12 `text.tertiary` 上 3。
 *
 * [items] = `标签 to 值`，**值不带单位**（与暖陶 `StatsBar` 同字·A-10）；空表不画。
 * 整卡合成一句读屏文案（「相识 128，消息 3412，…」），免得读屏逐格念。
 */
@Composable
fun LiuliStatCard(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val colors = AppTheme.colors
    val spoken = items.joinToString("，") { (label, value) -> "$label $value" }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = CARD_TOP, bottom = CARD_BOTTOM)
            .clip(LiuliShapes.group)
            .background(colors.surface.raised)
            .border(0.5.dp, colors.surface.stroke, LiuliShapes.group)
            .padding(vertical = CARD_PAD_V)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(Modifier.width(0.5.dp).height(DIVIDER_HEIGHT).background(colors.surface.stroke))
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    value,
                    style = AppTypography.captionNumeric.copy(
                        fontSize = LiuliPageGeometry.statValue,
                        fontWeight = FontWeight.W700,
                    ),
                    color = colors.accent.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    label,
                    style = AppTypography.caption.copy(fontSize = LiuliPageGeometry.statLabel),
                    color = colors.text.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = LABEL_TOP),
                )
            }
        }
    }
}
