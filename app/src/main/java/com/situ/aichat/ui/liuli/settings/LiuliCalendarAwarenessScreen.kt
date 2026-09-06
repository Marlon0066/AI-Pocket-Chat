package com.situ.aichat.ui.liuli.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.CalendarAwarenessViewModel
import com.situ.aichat.work.BackgroundReliability

/** 权限卡内的行距 / 状态图标尺寸 / 状态词与图标的缝。 */
private val CARD_GAP = 12.dp
private val STATUS_ICON = 20.dp
private val STATUS_GAP = 4.dp

/** 开启集成时一并申请的两枚权限（读是主功能·写让 AI 能落日程·逐字照暖陶）。 */
private val CALENDAR_PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

/**
 * 日历与提醒设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 6）。与暖陶 `CalendarAwarenessScreen` 共用
 * [CalendarAwarenessViewModel]。
 *
 * 机制锁（F8·逐字搬）：权限 launcher + `ON_RESUME` 复查 + 「开集成即请求读写日历」的联动 + 脚注四分支
 * （关 → 泛说能力；开 → 能读能写 + 操作确认子句·子句作占位符参数让各语言自管句间空格）。
 */
@Composable
fun LiuliCalendarAwarenessScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarAwarenessViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 感知（读）是主功能，状态以 READ 为准；写权限随同一对话框一并申请（逐字照暖陶 :68）。
    fun checkGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(checkGranted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = checkGranted()
    }

    // 从系统设置返回时复查授权状态（逐字照暖陶 :79–85）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = checkGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LiuliCalendarAwarenessContent(
        integrationEnabled = state.integrationEnabled,
        actionConfirmation = state.actionConfirmation,
        granted = granted,
        onSetIntegrationEnabled = { enabled ->
            viewModel.setIntegrationEnabled(enabled)
            // 开启集成 → 立即请求读写日历权限（对齐 iOS requestAllAccess·同暖陶）。
            if (enabled && !granted) launcher.launch(CALENDAR_PERMISSIONS)
        },
        onSetActionConfirmation = viewModel::setActionConfirmation,
        onRequestPermission = { launcher.launch(CALENDAR_PERMISSIONS) },
        onOpenSystemSettings = { BackgroundReliability.openAppDetailsSettings(context) },
        onBack = onBack,
        modifier = modifier,
    )
}

/** 日历页内容层（纯参数·可测）。三处门控（开关子行 / 权限区 / 两枚钮）逐字继承暖陶。 */
@Composable
internal fun LiuliCalendarAwarenessContent(
    integrationEnabled: Boolean,
    actionConfirmation: Boolean,
    granted: Boolean,
    onSetIntegrationEnabled: (Boolean) -> Unit,
    onSetActionConfirmation: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = AppTheme.colors
    val title = stringResource(R.string.cal_title)
    // 脚注随开关状态变化（1:1 iOS·子句作占位符参数让每个语言自管句间空格）。
    val footer = if (!integrationEnabled) {
        stringResource(R.string.cal_footer_off)
    } else {
        val confirmClause = if (actionConfirmation) {
            stringResource(R.string.cal_footer_confirm_on)
        } else {
            stringResource(R.string.cal_footer_confirm_off)
        }
        stringResource(R.string.cal_footer_on, confirmClause)
    }
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
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    LiuliGroup(header = stringResource(R.string.cal_toggle_section_title), footer = footer) {
                        LiuliToggleRow(
                            title = stringResource(R.string.cal_integration_label),
                            checked = integrationEnabled,
                            onCheckedChange = onSetIntegrationEnabled,
                            divider = false,
                        )
                        // 「操作确认」只在集成开启时出现（逐字照暖陶 :134）——这里用 `if` 包行不用
                        // AnimatedVisibility：隐着的 AnimatedVisibility 是 0 高节点，会在组里留一条孤发丝。
                        if (integrationEnabled) {
                            LiuliToggleRow(
                                title = stringResource(R.string.cal_action_confirm_label),
                                checked = actionConfirmation,
                                onCheckedChange = onSetActionConfirmation,
                            )
                        }
                    }

                    // 仅在集成开启时展示运行时授权 UI + 隐私提示（关闭时无需授权·逐字照暖陶 :144）。
                    if (integrationEnabled) {
                        LiuliGroup(footer = stringResource(R.string.cal_footnote)) {
                            LiuliRowBase(
                                divider = false,
                                verticalPadding = LiuliPageGeometry.groupPadH,
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                                    Text(
                                        stringResource(R.string.cal_privacy_note),
                                        style = AppTypography.secondary,
                                        color = colors.status.onError,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            stringResource(R.string.cal_permission_title),
                                            style = AppTypography.body,
                                            color = colors.text.primary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        val statusColor = if (granted) colors.status.onSuccess else colors.status.onError
                                        Icon(
                                            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = statusColor,
                                            modifier = Modifier.size(STATUS_ICON),
                                        )
                                        Spacer(Modifier.width(STATUS_GAP))
                                        Text(
                                            stringResource(
                                                if (granted) R.string.cal_status_granted else R.string.cal_status_denied,
                                            ),
                                            style = AppTypography.label,
                                            color = statusColor,
                                        )
                                    }
                                    if (!granted) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                                            LiuliButton(onClick = onRequestPermission, style = LiuliButtonStyle.Prominent) {
                                                Text(stringResource(R.string.cal_action_grant))
                                            }
                                            LiuliButton(onClick = onOpenSystemSettings, style = LiuliButtonStyle.Text) {
                                                Text(stringResource(R.string.cal_action_settings))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
