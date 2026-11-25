package com.situ.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppIconButton
import com.situ.aichat.ui.designsystem.AppSlider
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface
import kotlin.math.roundToInt

/**
 * 设置页通用滑杆行（标题 + 当前值 + Slider）。
 * 卷一升级（D-8·加法零回归）：支持 [defaultValue] 默认刻度提示、[startLabel]/[endLabel] 两端含义与 [enabled]。
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
    onValueChangeFinished: (() -> Unit)? = null,
    subtitle: String? = null,
    subtitleIsWarning: Boolean = false,
    defaultValue: Float? = null,
    startLabel: String? = null,
    endLabel: String? = null,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    val colors = AppTheme.colors
    var showInfo by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.48f)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = AppTypography.settingsRowTitle, color = colors.text.primary)
            if (infoMessage != null) {
                AppIconButton(
                    icon = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.settings_info_dialog_title),
                    onClick = { showInfo = true },
                    tint = colors.text.secondary,
                    iconSize = 16.dp,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                valueLabel,
                style = AppTypography.settingsRowTitle,
                color = colors.accent.text,
                modifier = if (onManualInput != null && enabled) {
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
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
        if (startLabel != null || endLabel != null || defaultValue != null) {
            val defaultLabel = stringResource(R.string.settings_slider_default_mark)
            val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f }
            val defaultFraction = if (defaultValue != null && span != null) {
                ((defaultValue - valueRange.start) / span).coerceIn(0f, 1f)
            } else {
                null
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(startLabel.orEmpty(), style = AppTypography.caption, color = colors.text.tertiary)
                if (defaultFraction != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width(4.dp).height(4.dp).clip(CircleShape).background(colors.accent.primary))
                        Text(defaultLabel, style = AppTypography.caption, color = colors.text.tertiary)
                    }
                }
                Text(endLabel.orEmpty(), style = AppTypography.caption, color = colors.text.tertiary)
            }
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = AppTypography.settingsRowSubtitle,
                color = if (subtitleIsWarning) colors.status.onWarning else colors.text.secondary,
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
                editText.toIntOrNull()?.let { if (it >= 0) onManualInput(it) }
                showManual = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showManual = false },
            content = {
                Column {
                    Text(stringResource(R.string.settings_manual_input_message, label), style = AppTypography.dialogBody, color = colors.text.secondary)
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

/** 设置页分区：标题 + 卡壳内容 + 可选脚注（暖陶自有 token，零 M3 依赖）。 */
@Composable
fun SettingsSection(
    title: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = AppTypography.label,
            color = colors.accent.text,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() },
        )
        Column(Modifier.fillMaxWidth().appCardSurface().padding(vertical = 6.dp)) { content() }
        if (footer != null) {
            Text(
                text = footer,
                style = AppTypography.settingsRowSubtitle,
                color = colors.text.secondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
