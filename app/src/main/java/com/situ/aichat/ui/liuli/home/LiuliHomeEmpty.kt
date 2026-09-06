package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle

/** 空态几何（图纸 2026-09-06 卷三 A-13）：72 圆 + 图标 32。 */
private val EMPTY_DISC = 72.dp
private val EMPTY_ICON = 32.dp

/**
 * 琉璃主页空态（A-13）：纸面居中——72 的 `accent.container` 圆 + 32 图标 → 标题 → 副 → 主钮。
 * 文案全部由调用方传既有资源串（本卷零新增资源键·§9 ①）。
 */
@Composable
fun LiuliHomeEmpty(
    icon: ImageVector,
    title: String,
    subtitle: String,
    ctaText: String,
    onCta: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Box(
                Modifier.size(EMPTY_DISC).background(colors.accent.container, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.accent.onContainer, modifier = Modifier.size(EMPTY_ICON))
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = AppTypography.titleSmall, color = colors.text.primary)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = AppTypography.secondary,
                color = colors.text.secondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            LiuliButton(onClick = onCta, style = LiuliButtonStyle.Prominent) { Text(ctaText) }
        }
    }
}

/** 搜索无结果（A-13）：一行文案 + 一个「清除搜索」文字钮（文案照暖陶 `NoSearchResults` 的字）。 */
@Composable
fun LiuliNoResults(
    text: String,
    clearText: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                text,
                style = AppTypography.secondary,
                color = AppTheme.colors.text.secondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            LiuliButton(onClick = onClear, style = LiuliButtonStyle.Text) { Text(clearText) }
        }
    }
}
