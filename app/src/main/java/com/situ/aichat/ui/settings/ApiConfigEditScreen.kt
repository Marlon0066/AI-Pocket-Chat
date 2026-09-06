package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.repository.isHttpsBaseUrl
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AudioInputMode
import com.situ.aichat.data.model.ThinkingBudgetLevel
import com.situ.aichat.data.model.ThinkingBudgetSupport
import com.situ.aichat.data.model.ThinkingModelMode
import com.situ.aichat.data.model.ToolCallingMode
import com.situ.aichat.data.model.VisionMode
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDropdownField
import com.situ.aichat.ui.designsystem.AppDropdownMenuItem
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * Per-config edit screen (P3.3c) — faithful port of iOS APIConfigurationView edit mode:
 * provider/url/model/key + the Advanced Capabilities section (thinking-model detection + thinking
 * intensity + tool/vision/audio modes). Saving re-runs detection in the background if inputs changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConfigEditScreen(
    uuid: String,
    onBack: () -> Unit,
    viewModel: ApiConfigViewModel = hiltViewModel(),
) {
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val modelCatalogState by viewModel.modelCatalogState.collectAsStateWithLifecycle()
    val detecting by viewModel.detecting.collectAsStateWithLifecycle()
    val config = configs.firstOrNull { it.uuid == uuid }

    // Editable draft, (re)initialized once the config row first loads (keyed on its uuid).
    var provider by remember(config?.uuid) {
        mutableStateOf(config?.let { ApiProviderType.fromRaw(it.providerTypeRaw) } ?: ApiProviderType.DEEPSEEK)
    }
    var baseUrl by remember(config?.uuid) { mutableStateOf(config?.baseURL ?: "") }
    var model by remember(config?.uuid) { mutableStateOf(config?.modelName ?: "") }
    // key 预填：进屏后把加密库里已存的 key 读出填入（默认打码·点眼睛可见），「拉取模型列表」因此能拿到真 key。
    // storedKey 同时留作保存时的比对基准——没真改就传 null 保持不变，避免误触发重写密钥库 + 重跑能力检测。
    var apiKey by remember(config?.uuid) { mutableStateOf("") }
    var storedKey by remember(config?.uuid) { mutableStateOf("") }
    LaunchedEffect(config?.uuid) {
        if (config != null) {
            val loaded = viewModel.storedApiKey(uuid)
            storedKey = loaded
            if (apiKey.isEmpty()) apiKey = loaded
        }
    }
    var thinkingMode by remember(config?.uuid) {
        mutableStateOf(config?.let { ThinkingModelMode.fromRaw(it.thinkingModelModeRaw) } ?: ThinkingModelMode.AUTO)
    }
    var toolMode by remember(config?.uuid) {
        mutableStateOf(config?.let { ToolCallingMode.fromRaw(it.toolCallingModeRaw) } ?: ToolCallingMode.AUTO)
    }
    var visionMode by remember(config?.uuid) {
        mutableStateOf(config?.let { VisionMode.fromRaw(it.visionModeRaw) } ?: VisionMode.AUTO)
    }
    var audioMode by remember(config?.uuid) {
        mutableStateOf(config?.let { AudioInputMode.fromRaw(it.audioInputModeRaw) } ?: AudioInputMode.AUTO)
    }
    var thinkingLevel by remember(config?.uuid) {
        mutableStateOf(config?.let { ThinkingBudgetLevel.fromRaw(it.thinkingBudgetLevelRaw) } ?: ThinkingBudgetLevel.AUTO)
    }
    var providerMenuOpen by remember { mutableStateOf(false) }

    val support = ThinkingBudgetSupport.resolve(provider, baseUrl, model)
    val effectiveThinking = when (thinkingMode) {
        ThinkingModelMode.THINKING -> true
        ThinkingModelMode.STANDARD -> false
        ThinkingModelMode.AUTO -> config?.detectedThinkingModelType == 1
    }
    val showIntensity = effectiveThinking && support.showsControl

    // settings-api-5：保存失败（Keychain/DB）留在编辑屏弹错误，不再静默返回丢密钥；成功由 VM onSaved 正常返回。
    val snackbarHostState = remember { SnackbarHostState() }
    val keychainFailMsg = stringResource(R.string.api_save_failed_keychain)
    val dbFailMsg = stringResource(R.string.api_save_failed_db)
    LaunchedEffect(Unit) {
        viewModel.feedback.collect { fb ->
            when (fb) {
                ApiSaveFeedback.KeychainFailed -> snackbarHostState.showSnackbar(keychainFailMsg)
                ApiSaveFeedback.DbFailed -> snackbarHostState.showSnackbar(dbFailMsg)
                ApiSaveFeedback.SavedCreate -> Unit // 新建反馈属列表屏，编辑屏忽略
            }
        }
    }

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.api_edit_title),
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding() // C4：键盘弹起时 API key 等字段可滚到键盘上方
                .padding(horizontal = AppSpacing.screenGutter) // 屏 gutter 恒 20（设计语言 §2.5 军规）
                .verticalScroll(scrollState)
                .contentMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Provider
            AppDropdownField(
                value = provider.displayName,
                expanded = providerMenuOpen,
                onExpandedChange = { providerMenuOpen = it },
                label = "服务商",
                modifier = Modifier.fillMaxWidth(),
            ) {
                ApiProviderType.entries.forEach { p ->
                    AppDropdownMenuItem(
                        text = p.displayName,
                        selected = provider == p,
                        onClick = {
                            provider = p
                            providerMenuOpen = false
                            viewModel.clearModels()
                        },
                    )
                }
            }

            val urlInsecure = baseUrl.isNotBlank() && !isHttpsBaseUrl(baseUrl)
            AppTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    viewModel.clearModels() // 换了端点，旧列表即刻作废
                },
                label = "Base URL",
                isError = urlInsecure,
                supportingText = if (urlInsecure) stringResource(R.string.api_url_https_required) else null,
                modifier = Modifier.fillMaxWidth(),
            )

            ModelDropdownField(
                model = model,
                onModelChange = { model = it },
                state = modelCatalogState,
                onFetch = { viewModel.fetchModels(provider, baseUrl, apiKey) },
            )
            KnownCapabilityHint(model)

            ApiKeyField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    viewModel.clearModels() // 换了 Key，旧列表即刻作废
                },
                supportingText = stringResource(R.string.api_key_keep),
                modifier = Modifier.fillMaxWidth(),
            )

            AppListDivider(startInset = 0.dp)
            Text(
                stringResource(R.string.api_section_capabilities),
                style = MaterialTheme.typography.titleMedium,
            )

            // Thinking model detection
            ModePickerRow(
                title = stringResource(R.string.api_row_thinking),
                badge = if (thinkingMode == ThinkingModelMode.AUTO) {
                    stringResource(R.string.api_det_prefix) + thinkingDetectedText(config?.detectedThinkingModelType ?: -1)
                } else {
                    null
                },
                options = listOf(
                    stringResource(R.string.api_tmode_auto) to ThinkingModelMode.AUTO,
                    stringResource(R.string.api_tmode_standard) to ThinkingModelMode.STANDARD,
                    stringResource(R.string.api_tmode_thinking) to ThinkingModelMode.THINKING,
                ),
                selected = thinkingMode,
                onSelect = { thinkingMode = it },
            )

            // Thinking intensity (only when effectively thinking + provider supports a budget control)
            if (showIntensity) {
                ModePickerRow(
                    title = stringResource(R.string.api_row_thinking_intensity),
                    badge = null,
                    options = support.allowedLevels.map { levelLabel(it) to it },
                    selected = support.normalized(thinkingLevel),
                    onSelect = { thinkingLevel = it },
                )
                Text(
                    levelHint(support.normalized(thinkingLevel)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            // Tool calling — 检测状态改由下方 ToolDetectionStatusBlock 完整展示（取代原小角标·PLAN §9）。
            ModePickerRow(
                title = stringResource(R.string.api_row_tool),
                badge = null,
                options = capabilityModeOptions { auto, enabled, disabled ->
                    listOf(auto to ToolCallingMode.AUTO, enabled to ToolCallingMode.ENABLED, disabled to ToolCallingMode.DISABLED)
                },
                selected = toolMode,
                onSelect = { toolMode = it },
            )
            if (config != null) {
                ToolDetectionStatusBlock(
                    config = config,
                    toolMode = toolMode,
                    detecting = uuid in detecting,
                    onRedetect = { viewModel.redetect(uuid) },
                )
            }

            // Vision
            ModePickerRow(
                title = stringResource(R.string.api_row_vision),
                badge = if (visionMode == VisionMode.AUTO) {
                    stringResource(R.string.api_det_prefix) + capDetectedText(config?.detectedVisionSupport ?: -1)
                } else {
                    null
                },
                options = capabilityModeOptions { auto, enabled, disabled ->
                    listOf(auto to VisionMode.AUTO, enabled to VisionMode.ENABLED, disabled to VisionMode.DISABLED)
                },
                selected = visionMode,
                onSelect = { visionMode = it },
            )

            // Audio input
            ModePickerRow(
                title = stringResource(R.string.api_row_audio),
                badge = if (audioMode == AudioInputMode.AUTO) {
                    stringResource(R.string.api_det_prefix) + capDetectedText(config?.detectedAudioInputSupport ?: -1)
                } else {
                    null
                },
                options = capabilityModeOptions { auto, enabled, disabled ->
                    listOf(auto to AudioInputMode.AUTO, enabled to AudioInputMode.ENABLED, disabled to AudioInputMode.DISABLED)
                },
                selected = audioMode,
                onSelect = { audioMode = it },
            )

            AppButton(
                onClick = {
                    if (config == null) return@AppButton
                    viewModel.updateConfig(
                        uuid = uuid,
                        provider = provider,
                        baseUrl = baseUrl,
                        model = model,
                        newApiKey = resolveNewApiKey(apiKey, storedKey),
                        thinkingModelMode = thinkingMode,
                        toolCallingMode = toolMode,
                        visionMode = visionMode,
                        audioInputMode = audioMode,
                        thinkingBudgetLevel = thinkingLevel,
                        onSaved = onBack,
                    )
                },
                style = AppButtonStyle.Primary,
                enabled = config != null && baseUrl.isNotBlank() && model.isNotBlank() && isHttpsBaseUrl(baseUrl),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Text(stringResource(R.string.api_save))
            }
        }
    }
}

/**
 * 编辑保存时把输入框的 key 归一成 [ApiConfigViewModel.updateConfig] 的 newApiKey 参数：
 * 空 / 与已存 key 相同（trim 后）→ null（保持不变），真改了 → trim 后的新值。
 * 仓库层把「非空 newApiKey」一律视为 key 变更并重置重跑能力检测，预填后必须先在此比对，
 * 否则每次保存都会白跑一轮联网检测。纯函数设 internal 便于单测。
 */
internal fun resolveNewApiKey(input: String, storedKey: String): String? =
    input.trim().takeIf { it.isNotEmpty() && it != storedKey }

/** Shared auto/enabled/disabled option labels, mapped to a provider-specific mode enum. */
@Composable
private fun <T> capabilityModeOptions(build: (String, String, String) -> List<Pair<String, T>>): List<Pair<String, T>> =
    build(
        stringResource(R.string.api_cmode_auto),
        stringResource(R.string.api_cmode_enabled),
        stringResource(R.string.api_cmode_disabled),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ModePickerRow(
    title: String,
    badge: String?,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.second == selected }?.first.orEmpty()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AppDropdownField(
            value = selectedLabel,
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            options.forEach { (label, value) ->
                AppDropdownMenuItem(
                    text = label,
                    selected = value == selected,
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun thinkingDetectedText(detected: Int): String = when (detected) {
    1 -> stringResource(R.string.api_det_thinking_model)
    0 -> stringResource(R.string.api_det_standard_model)
    else -> stringResource(R.string.api_det_undetermined)
}

@Composable
private fun capDetectedText(detected: Int): String = when (detected) {
    1 -> stringResource(R.string.api_cap_supported)
    0 -> stringResource(R.string.api_cap_unsupported)
    else -> stringResource(R.string.api_cap_undetected)
}

@Composable
private fun levelLabel(level: ThinkingBudgetLevel): String = when (level) {
    ThinkingBudgetLevel.OFF -> stringResource(R.string.api_level_off)
    ThinkingBudgetLevel.AUTO -> stringResource(R.string.api_level_auto)
    ThinkingBudgetLevel.LOW -> stringResource(R.string.api_level_low)
    ThinkingBudgetLevel.MEDIUM -> stringResource(R.string.api_level_medium)
    ThinkingBudgetLevel.HIGH -> stringResource(R.string.api_level_high)
}

@Composable
private fun levelHint(level: ThinkingBudgetLevel): String = when (level) {
    ThinkingBudgetLevel.OFF -> stringResource(R.string.api_levelhint_off)
    ThinkingBudgetLevel.AUTO -> stringResource(R.string.api_levelhint_auto)
    ThinkingBudgetLevel.LOW -> stringResource(R.string.api_levelhint_low)
    ThinkingBudgetLevel.MEDIUM -> stringResource(R.string.api_levelhint_medium)
    ThinkingBudgetLevel.HIGH -> stringResource(R.string.api_levelhint_high)
}
