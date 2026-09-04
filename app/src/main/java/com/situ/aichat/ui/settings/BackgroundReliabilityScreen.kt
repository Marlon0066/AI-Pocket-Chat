package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.work.BackgroundReliability

/**
 * 后台运行保障引导页(P5.0)。教用户在国行 ROM 上做两项一次性设置——免电池优化(可查状态)、
 * 自启动白名单(跳厂商安全中心)——保证日程生成 / 定时提醒等后台任务不被系统杀掉。
 * 纯系统设置导航 + 实时状态查询，不涉及任何通知逻辑(那部分留到 P6 与用户确认后再做)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundReliabilityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var batteryExempt by remember {
        mutableStateOf(BackgroundReliability.isIgnoringBatteryOptimizations(context))
    }

    // 从系统设置返回时自动复查电池优化状态，让页面状态保持最新。
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

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.bg_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.bg_intro), style = MaterialTheme.typography.bodyMedium)

            ReliabilityCard(
                title = stringResource(R.string.bg_battery_title),
                description = stringResource(R.string.bg_battery_desc),
                statusLabel = if (batteryExempt) {
                    stringResource(R.string.bg_battery_status_exempt)
                } else {
                    stringResource(R.string.bg_battery_status_active)
                },
                statusOk = batteryExempt,
                actionEnabled = !batteryExempt,
                onAction = { BackgroundReliability.requestIgnoreBatteryOptimizations(context) },
            )

            ReliabilityCard(
                title = stringResource(R.string.bg_autostart_title),
                description = stringResource(R.string.bg_autostart_desc) + "\n" +
                    stringResource(R.string.bg_autostart_hint),
                statusLabel = null,
                statusOk = false,
                actionEnabled = true,
                onAction = { BackgroundReliability.openAutoStartSettings(context) },
            )

            Text(
                stringResource(R.string.bg_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReliabilityCard(
    title: String,
    description: String,
    statusLabel: String?,
    statusOk: Boolean,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    // 承托改 appCardSurface（内层 Column 参数上提·减一层嵌套·§4.A3）；卡内布局零改。
    Column(
        modifier = Modifier.fillMaxWidth().appCardSurface().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (statusLabel != null) {
                val color = if (statusOk) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
                Icon(
                    imageVector = if (statusOk) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = color,
                )
                Spacer(Modifier.width(4.dp))
                Text(statusLabel, color = color, style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppButton(
            onClick = onAction,
            style = AppButtonStyle.Primary,
            enabled = actionEnabled,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.bg_action_open_settings))
        }
    }
}
