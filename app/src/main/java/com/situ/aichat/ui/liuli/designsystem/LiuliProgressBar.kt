package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry

/** 不定态转圈直径与说明字号（图纸 2026-09-06 卷五 A-4 ③）。 */
private val SPINNER = 16.dp
private val LABEL_SIZE = 13.sp
/** 转圈 ↔ 说明字的缝。 */
private val LABEL_GAP = 10.dp

/**
 * 琉璃进度条（图纸 2026-09-06 卷五 A-4 ③·暖陶 `AppProgressBar` 的对应件）。
 *
 * **禁 M3 `LinearProgressIndicator` / `CircularProgressIndicator`**（§9 ⑤）：
 * - [progress] 非空 = 定量 → 轨 [LiuliPageGeometry.progressTrack] 高（与 `LiuliSlider` 的轨同源落值）
 *   圆角 [LiuliPageGeometry.progressCorner]，底 `surface.sunken`、填充 `accent.primary`；挂
 *   [progressSemantics] 报真值（0f..1f）。
 * - [progress] 为空 = 不定 → [LiuliSpinner] 16 + [label]（13 `text.secondary`）。定量态**不渲染** [label]
 *   ——它是「说不出还剩多少时才需要的一句话」，有百分比就不再多这一行（A-4 ③ 逐字）。
 */
@Composable
fun LiuliProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val colors = AppTheme.colors
    if (progress == null) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LABEL_GAP),
        ) {
            LiuliSpinner(size = SPINNER)
            if (label != null) {
                Text(
                    label,
                    style = AppTypography.secondary.copy(fontSize = LABEL_SIZE),
                    color = colors.text.secondary,
                )
            }
        }
        return
    }
    val clamped = progress.coerceIn(0f, 1f)
    val track = colors.surface.sunken
    val fill = colors.accent.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(LiuliPageGeometry.progressTrack)
            .progressSemantics(clamped)
            .clip(RoundedCornerShape(LiuliPageGeometry.progressCorner))
            .drawBehind {
                val radius = CornerRadius(LiuliPageGeometry.progressCorner.toPx(), LiuliPageGeometry.progressCorner.toPx())
                drawRoundRect(color = track, size = size, cornerRadius = radius)
                if (clamped > 0f) {
                    drawRoundRect(color = fill, size = Size(size.width * clamped, size.height), cornerRadius = radius)
                }
            },
    )
}
