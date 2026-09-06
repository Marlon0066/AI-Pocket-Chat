package com.situ.aichat.ui.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Fable-5 提示纸条（六件套草图 2026-07-17 过审）——取代 M3 `Snackbar` 那块深色胶囊。
 *
 * **包壳不重写**（沿用卷一「行为重的组件包壳、不重写」口径）：内部仍是 M3 [SnackbarHost]，排队、时长、
 * `SnackbarResult` 回传、`withDismissAction` 全由 M3 承担；本组件只换那张卡的长相。站点侧
 * [SnackbarHostState] 的用法与 `showSnackbar(...)` 调用**一字不用改**。
 *
 * 造型：纸卡 = [Modifier.appCardSurface]（raised·圆角 16dp）+ [Modifier.grainSurface] 纸感，
 * 外边距 20dp（= 屏 gutter 军规 [AppSpacing.screenGutter]）、内边距 14×11dp、槽间距 10dp；文案 [AppTypography.snackbarBody] 两行省略，
 * 动作词 [AppTypography.snackbarAction] 靠右。
 *
 * **有意不做**（用户已知悉的降格）：① 不画「成功带灰绿点」——收编站现在没有语义分级信息可判；
 * ② 不覆盖 M3 宿主的浮入过渡——自写一层等于重写宿主。**离场时长由站点侧的 `SnackbarDuration` 决定，
 * 本组件不设定时器。**
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        AppSnackbarCard(data)
    }
}

@Composable
private fun AppSnackbarCard(data: SnackbarData) {
    val colors = AppTheme.colors
    val actionLabel = data.visuals.actionLabel
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 纸条是有底色的卡：屏 gutter 恒 20（设计语言 §2.5 军规），无内部补偿故 padding 直接给 20。
            .padding(horizontal = AppSpacing.screenGutter)
            .appCardSurface(raised = true, cornerRadius = 16.dp)
            .grainSurface()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            data.visuals.message,
            style = AppTypography.snackbarBody,
            color = colors.text.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null) {
            Spacer(Modifier.weight(1f))
            Text(
                actionLabel,
                style = AppTypography.snackbarAction,
                color = colors.accent.text,
                maxLines = 1,
                modifier = Modifier.clickable { data.performAction() },
            )
        }
    }
}
