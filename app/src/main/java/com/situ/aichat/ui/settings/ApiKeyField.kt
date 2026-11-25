package com.situ.aichat.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppIconButton
import com.situ.aichat.ui.designsystem.AppTextField

/**
 * API Key 输入框（带眼睛明文切换），共三处复用：[ApiConfigScreen] / [ApiConfigEditScreen] /
 * [TtsConfigurationScreen]（P15.2 #12，补齐 iOS APIConfigurationView.swift:146-147 `isAPIKeyVisible`——
 * 安卓此前三处硬挂 [PasswordVisualTransformation] 无 reveal，是相对 iOS 的 UX 倒退）。DRY 一处实现（CLAUDE.md §2）。
 *
 * 输入框重构 §6.4：内部 = 软填充 [AppTextField]（眼睛走 `trailingIcon` 槽·密码遮蔽走 `visualTransformation`），
 * 替原裸 M3 `OutlinedTextField`。[label]/[supportingText] 改 String（范式 A·框上方静态标签）。
 */
@Composable
fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "API Key",
    supportingText: String? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        supportingText = supportingText,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            AppIconButton(
                icon = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = stringResource(if (visible) R.string.api_key_hide else R.string.api_key_show),
                onClick = { visible = !visible },
            )
        },
    )
}
