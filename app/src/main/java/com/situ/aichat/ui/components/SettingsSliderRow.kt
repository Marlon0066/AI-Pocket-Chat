package com.situ.aichat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppSlider
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface
import kotlin.math.roundToInt

/**
 * 设置页通用滑杆行（标题 + 当前值 + Slider）。多个设置页复用（12.1 记忆设置首个使用方）。
 * 形态对齐既有 `ImmersiveSettingsScreen`/`MomentSettingsScreen` 的私有 SliderRow，抽出避免再复制。
 */
@Composable
fun SettingsSliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    infoMessage: String? = null,
    onManualInput: ((Int) -> Unit)? = null,
    /**
     * 松手回调（add-only·默认 null = 行为与加它之前**逐字节一致**）。
     * 给「拖动中只更本地态、松手才落盘」的调用点用；不传就还是原来的每档即时生效。
     */
    onValueChangeFinished: (() -> Unit)? = null,
    /**
     * 滑杆下方的一行小字（add-only·默认 null = 不放任何节点，其余调用点渲染**逐字节一致**）。
     * 给「按当前值实时重算的活例子 / 越界提示」用（记忆设置页首个使用方·图纸 2026-09-05 §4.2）。
     */
    subtitle: String? = null,
    /** 副标走警示档（琥珀 [AppStatusColors.onWarning]）而非常态次级色——用于「设出了安全区」这类提示。 */
    subtitleIsWarning: Boolean = false,
    onValueChange: (Float) -> Unit,
) {
    // settings-slider-infobutton：infoMessage 非空时标题旁加 ⓘ，点开「说明」弹窗（对齐 iOS SettingSliderRow info.circle）。
    var showInfo by remember { mutableStateOf(false) }
    // settings-slider-manualinput：onManualInput 非空时数值可点 → 手动输入弹窗（可超过滑杆上限，对齐 iOS）。
    var showManual by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (infoMessage != null) {
                IconButton(onClick = { showInfo = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.settings_info_dialog_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = if (onManualInput != null) {
                    Modifier.clickable { editText = value.roundToInt().toString(); showManual = true }
                } else {
                    Modifier
                },
            )
        }
        AppSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = AppTypography.settingsRowSubtitle,
                color = if (subtitleIsWarning) AppTheme.colors.status.onWarning else AppTheme.colors.text.secondary,
                // 滑杆自带触达高度已把上方隔开，故只留下缘 4dp。
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
    if (showInfo && infoMessage != null) {
        AppDialog(
            onDismissRequest = { showInfo = false },
            title = stringResource(R.string.settings_info_dialog_title),
            body = infoMessage,
            confirmText = stringResource(R.string.settings_info_dialog_ok),
            onConfirm = { showInfo = false },
        )
    }
    if (showManual && onManualInput != null) {
        AppDialog(
            onDismissRequest = { showManual = false },
            title = stringResource(R.string.settings_manual_input_title),
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = {
                // 解析为整数且 >=0 才写入（对齐 iOS num>=0），可超过滑杆上限（setter 已放宽上限钳位）。
                editText.toIntOrNull()?.let { if (it >= 0) onManualInput(it) }
                showManual = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showManual = false },
            content = {
                Column {
                    Text(stringResource(R.string.settings_manual_input_message, label))
                    AppTextField(
                        value = editText,
                        onValueChange = { editText = it.filter { c -> c.isDigit() } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
        )
    }
}

/** 设置页分区：标题 + 卡壳内容 + 可选脚注（2026-07-12 加壳=appCardSurface·mockup 过审；标题/脚注留卡外）。 */
@Composable
fun SettingsSection(
    title: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            // start 4 → 屏缘缩进 20+4=24，与设置主页组标题缩进对齐（V-c）。
            modifier = Modifier.padding(start = 4.dp).semantics { heading() },
        )
        Column(Modifier.fillMaxWidth().appCardSurface().padding(vertical = 6.dp)) { content() }
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
