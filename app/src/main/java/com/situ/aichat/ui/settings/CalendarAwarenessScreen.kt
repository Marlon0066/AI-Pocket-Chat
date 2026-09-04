package com.situ.aichat.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.grainSurface
import com.situ.aichat.work.BackgroundReliability

/**
 * 日历与提醒设置页（P5.3a/P5.3b + P12.1c）。1:1 iOS `CalendarSettingsView`：日历集成开关 + 操作确认开关
 * （受集成门控）+ 随状态变化的脚注；外加安卓特有的 READ/WRITE_CALENDAR 运行时授权 UI（iOS 仅 `requestAllAccess`，
 * 安卓需显式授予 + 隐私提示「事件随提示词上传给 API」）。开启集成时联动一次性请求读写日历权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarAwarenessScreen(
    onBack: () -> Unit,
    viewModel: CalendarAwarenessViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 感知（读）是主功能，状态以 READ 为准；写权限随同一对话框一并申请，授予后 AI 才能写日历。
    fun checkGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(checkGranted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = checkGranted()
    }

    // 从系统设置返回时复查授权状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = checkGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 脚注随开关状态变化（1:1 iOS：关→泛说能力；开→说明能读能写 + 操作确认子句）。子句作为占位符参数，
    // 让每个语言在 cal_footer_on 里自管句间空格（中文无空格、英文有）。
    val footer = if (!state.integrationEnabled) {
        stringResource(R.string.cal_footer_off)
    } else {
        val confirmClause = if (state.actionConfirmation) {
            stringResource(R.string.cal_footer_confirm_on)
        } else {
            stringResource(R.string.cal_footer_confirm_off)
        }
        stringResource(R.string.cal_footer_on, confirmClause)
    }

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.cal_title),
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
                .contentMaxWidth(),
        ) {
            SettingsSection(
                title = stringResource(R.string.cal_toggle_section_title),
                footer = footer,
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.cal_integration_label),
                    checked = state.integrationEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setIntegrationEnabled(enabled)
                        // 开启集成 → 立即请求读写日历权限（对齐 iOS requestAllAccess）。
                        if (enabled && !granted) {
                            launcher.launch(
                                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                            )
                        }
                    },
                )
                if (state.integrationEnabled) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.cal_action_confirm_label),
                        checked = state.actionConfirmation,
                        onCheckedChange = { viewModel.setActionConfirmation(it) },
                    )
                }
            }

            // 仅在集成开启时展示运行时授权 UI + 隐私提示（关闭时无需授权）。
            if (state.integrationEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.cal_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    Box(Modifier.fillMaxWidth().appCardSurface(raised = true, cornerRadius = 16.dp).grainSurface()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.cal_permission_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                val color = if (granted) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                                Icon(
                                    imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = color,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(if (granted) R.string.cal_status_granted else R.string.cal_status_denied),
                                    color = color,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            if (!granted) {
                                AppButton(
                                    onClick = {
                                        launcher.launch(
                                            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                                        )
                                    },
                                    style = AppButtonStyle.Primary,
                                    modifier = Modifier.align(Alignment.End),
                                ) {
                                    Text(stringResource(R.string.cal_action_grant))
                                }
                                AppButton(
                                    onClick = { BackgroundReliability.openAppDetailsSettings(context) },
                                    style = AppButtonStyle.Primary,
                                    modifier = Modifier.align(Alignment.End),
                                ) {
                                    Text(stringResource(R.string.cal_action_settings))
                                }
                            }
                        }
                    }

                    Text(
                        stringResource(R.string.cal_footnote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
