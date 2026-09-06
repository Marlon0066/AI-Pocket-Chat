package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.work.BackgroundReliability

/** 卡内块间缝 / 状态图标尺寸 / 状态词与图标的缝（逐字照暖陶 8 / 20 / 4）。 */
private val CARD_GAP = 8.dp
private val STATUS_ICON = 20.dp
private val STATUS_GAP = 4.dp

/**
 * 后台保障页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 25「两组各一状态行 + 钮」）。无 VM
 * （状态直接问 [BackgroundReliability]）。
 *
 * 机制锁（F8·逐字搬）：`ON_RESUME` 复查电池优化状态——从系统设置返回时页面要自己更新，
 * 否则用户刚放行完回来还看见「仍在优化」。自启动那张卡**没有状态词**（安卓查不到自启动状态·
 * 记忆 `project_dream_module_parked` 的同一个事实），故恒可点。
 */
@Composable
fun LiuliBackgroundReliabilityScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var batteryExempt by remember { mutableStateOf(BackgroundReliability.isIgnoringBatteryOptimizations(context)) }

    // 从系统设置返回时自动复查电池优化状态（逐字照暖陶 :58–66）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = BackgroundReliability.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LiuliBackgroundReliabilityContent(
        batteryExempt = batteryExempt,
        onRequestBatteryExempt = { BackgroundReliability.requestIgnoreBatteryOptimizations(context) },
        onOpenAutoStart = { BackgroundReliability.openAutoStartSettings(context) },
        onBack = onBack,
        modifier = modifier,
    )
}

/** 后台保障页内容层（纯参数·可测）。 */
@Composable
internal fun LiuliBackgroundReliabilityContent(
    batteryExempt: Boolean,
    onRequestBatteryExempt: () -> Unit,
    onOpenAutoStart: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = AppTheme.colors
    val title = stringResource(R.string.bg_title)
    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            item(key = "intro") {
                Text(
                    stringResource(R.string.bg_intro),
                    style = AppTypography.listPreview,
                    color = colors.text.secondary,
                    modifier = Modifier.padding(
                        start = LiuliPageGeometry.gutter,
                        end = LiuliPageGeometry.gutter,
                        top = LiuliPageGeometry.titleGap,
                        bottom = LiuliPageGeometry.groupPadH,
                    ),
                )
            }
            item(key = "groups") {
                Column(Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter)) {
                    ReliabilityGroup(
                        title = stringResource(R.string.bg_battery_title),
                        description = stringResource(R.string.bg_battery_desc),
                        statusLabel = stringResource(
                            if (batteryExempt) R.string.bg_battery_status_exempt else R.string.bg_battery_status_active,
                        ),
                        statusOk = batteryExempt,
                        actionEnabled = !batteryExempt,
                        onAction = onRequestBatteryExempt,
                    )
                    ReliabilityGroup(
                        title = stringResource(R.string.bg_autostart_title),
                        // 拼接逐字照暖陶：说明 + 换行 + 提示。
                        description = stringResource(R.string.bg_autostart_desc) + "\n" +
                            stringResource(R.string.bg_autostart_hint),
                        statusLabel = null,
                        statusOk = false,
                        actionEnabled = true,
                        onAction = onOpenAutoStart,
                        footer = stringResource(R.string.bg_footnote),
                    )
                }
            }
        }
    }
}

/** 一张引导卡（标题 + 可选状态 + 说明 + 右下一枚钮）。 */
@Composable
private fun ReliabilityGroup(
    title: String,
    description: String,
    statusLabel: String?,
    statusOk: Boolean,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    footer: String? = null,
) {
    val colors = AppTheme.colors
    LiuliGroup(footer = footer) {
        LiuliRowBase(divider = false, verticalPadding = LiuliPageGeometry.groupPadH, verticalAlignment = Alignment.Top) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = AppTypography.bodyEmphasis, color = colors.text.primary, modifier = Modifier.weight(1f))
                    if (statusLabel != null) {
                        val color = if (statusOk) colors.status.onSuccess else colors.status.onError
                        Icon(
                            imageVector = if (statusOk) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(STATUS_ICON),
                        )
                        Spacer(Modifier.width(STATUS_GAP))
                        Text(statusLabel, style = AppTypography.label, color = color)
                    }
                }
                Text(description, style = AppTypography.listPreview, color = colors.text.secondary)
                LiuliButton(
                    onClick = onAction,
                    style = LiuliButtonStyle.Prominent,
                    enabled = actionEnabled,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.bg_action_open_settings))
                }
            }
        }
    }
}
