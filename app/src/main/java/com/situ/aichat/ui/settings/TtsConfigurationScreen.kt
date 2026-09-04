package com.situ.aichat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.tts.TtsProviderType
import com.situ.aichat.tts.TtsResponseFormat
import com.situ.aichat.tts.pricing.TtsCostEstimate
import com.situ.aichat.tts.provider.MiniMaxCatalog
import com.situ.aichat.tts.provider.MiniMaxRegion
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppChoiceChip
import com.situ.aichat.ui.designsystem.AppDropdownField
import com.situ.aichat.ui.designsystem.AppDropdownMenuItem
import com.situ.aichat.ui.designsystem.AppDropdownTextField
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import kotlinx.coroutines.launch

/**
 * Global TTS provider configuration (1:1-effect of iOS `TTSSettingsView`, Material 3 idiom).
 * Provider picker + per-provider inline setup tutorial (the Android "exceed iOS" touch) + remote
 * fields (base URL / MiniMax region / model + voice live-fetch / masked write-only key / format /
 * cost). The API key is user-supplied, same as the LLM API config. Per-character voice/emotion/speed
 * /pitch live on the character edit screen. Voice preview (playback) arrives with the player chunk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsConfigurationScreen(
    onBack: () -> Unit,
    viewModel: TtsConfigViewModel = hiltViewModel(),
) {
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val modelsLoading by viewModel.modelsLoading.collectAsStateWithLifecycle()
    val modelsError by viewModel.modelsError.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val voicesLoading by viewModel.voicesLoading.collectAsStateWithLifecycle()
    val voicesError by viewModel.voicesError.collectAsStateWithLifecycle()
    val cost by viewModel.cost.collectAsStateWithLifecycle()
    val systemVoices by viewModel.systemVoices.collectAsStateWithLifecycle()
    val systemVoicesLoading by viewModel.systemVoicesLoading.collectAsStateWithLifecycle()
    val previewBusy by viewModel.previewBusy.collectAsStateWithLifecycle()
    val previewError by viewModel.previewError.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(previewError) {
        previewError?.let { snackbarHostState.showSnackbar(it) }
    }

    var seeded by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf(TtsProviderType.SYSTEM) }
    var providerName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var remoteVoice by remember { mutableStateOf("") }
    var systemVoice by remember { mutableStateOf("") }
    var responseFormat by remember { mutableStateOf(TtsResponseFormat.MP3) }
    var providerMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(configuration) {
        val c = configuration ?: return@LaunchedEffect
        if (seeded) return@LaunchedEffect
        provider = c.providerType
        providerName = c.providerName
        baseUrl = c.baseURL
        model = c.modelName
        remoteVoice = c.defaultRemoteVoiceID
        systemVoice = c.defaultSystemVoiceIdentifier
        responseFormat = c.responseFormat
        seeded = true
        if (c.providerType == TtsProviderType.MINIMAX) viewModel.refreshUsage(c.modelName)
    }

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = "语音 / TTS 设置",
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        if (!seeded) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { AppLoadingRing(size = AppLoadingRingSize.Large) }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState)
                .contentMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // 段1「语音引擎」无标题卡壳（§4.A12·操作页 16 gutter 由外 Column 提供·卡内 16·§11 D-A11 同口径）。
            Column(Modifier.fillMaxWidth().appCardSurface().padding(16.dp)) {
                AppDropdownField(
                    value = provider.displayName,
                    expanded = providerMenuOpen,
                    onExpandedChange = { providerMenuOpen = it },
                    label = "语音引擎",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TtsProviderType.entries.forEach { p ->
                        AppDropdownMenuItem(
                            text = p.displayName,
                            selected = provider == p,
                            onClick = {
                                provider = p
                                providerName = p.displayName
                                baseUrl = p.defaultBaseUrl
                                model = if (p == TtsProviderType.MINIMAX) MiniMaxCatalog.DEFAULT_MODEL_ID else ""
                                remoteVoice = ""
                                providerMenuOpen = false
                                viewModel.clearCatalogs()
                            },
                        )
                    }
                }
            }

            // 段1 教程卡：TtsProviderTutorial 自成一卡（其 M3 Card → appCardSurface）·紧随段1卡后。
            TtsProviderTutorial(provider)

            // 段2：provider 条件字段整块进一张无标题卡壳（条件逻辑零改·字段间距 spacedBy 12·§4.A12）。
            Column(
                Modifier.fillMaxWidth().appCardSurface().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (provider == TtsProviderType.SYSTEM) {
                    Text(
                        "使用本机系统语音引擎（免 key、免费）。国行 HyperOS 多为小米引擎（部分需联网、质量参差），无 Google TTS——追求自然度与情绪建议改用 MiniMax 云。角色的具体音色在「角色编辑 → 语音」里选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TtsCatalogDropdownField(
                        label = "系统音色（默认）",
                        value = systemVoice,
                        onValueChange = { systemVoice = it },
                        items = systemVoices.map { TtsCatalogItem(it.id, it.name, systemVoiceQualityLabel(it.quality)) },
                        loading = systemVoicesLoading,
                        error = null,
                        onFetch = { viewModel.loadSystemVoices() },
                    )
                } else {
                    if (provider == TtsProviderType.MINIMAX) {
                        MiniMaxRegionPicker(baseUrl = baseUrl, onPick = { baseUrl = it })
                    }
                    AppTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = "Base URL",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TtsCatalogDropdownField(
                        label = "模型名",
                        value = model,
                        onValueChange = {
                            // Volink voices belong to one model, and the relay routes by voice ID even
                            // when it mismatches the model (silently — verified live). Changing the
                            // model therefore invalidates the picked voice and the fetched voice list.
                            if (provider == TtsProviderType.VOLINK && it != model) {
                                remoteVoice = ""
                                viewModel.clearVoices()
                            }
                            model = it
                        },
                        items = models.map { TtsCatalogItem(it.id, it.name, null) },
                        loading = modelsLoading,
                        error = modelsError,
                        onFetch = { viewModel.fetchModels(provider, providerName, baseUrl, apiKey) },
                    )
                    ApiKeyField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = if (hasApiKey) "API Key（已设置，留空则不修改）" else "API Key",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TtsCatalogDropdownField(
                        label = "默认音色 ID",
                        value = remoteVoice,
                        onValueChange = { remoteVoice = it },
                        items = voices.map { TtsCatalogItem(it.id, it.name, it.detail) },
                        loading = voicesLoading,
                        error = voicesError,
                        onFetch = { viewModel.fetchVoices(provider, providerName, baseUrl, model, apiKey) },
                    )
                    ResponseFormatPicker(value = responseFormat, onPick = { responseFormat = it })
                    if (provider == TtsProviderType.MINIMAX) {
                        cost?.let { CostHint(it) }
                    }
                }
            }

            AppButton(
                style = AppButtonStyle.Tonal,
                onClick = {
                    viewModel.preview(
                        provider = provider,
                        providerName = providerName,
                        baseUrl = baseUrl,
                        modelName = model,
                        remoteVoice = remoteVoice,
                        systemVoice = systemVoice,
                        responseFormat = responseFormat,
                        apiKeyInput = apiKey,
                    )
                },
                enabled = !previewBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (previewBusy) {
                    AppLoadingRing(size = AppLoadingRingSize.Small)
                    Spacer(Modifier.width(8.dp))
                }
                Text("试听")
            }

            AppButton(
                style = AppButtonStyle.Text,
                onClick = {
                    viewModel.save(
                        provider = provider,
                        providerName = providerName,
                        baseUrl = baseUrl,
                        modelName = model,
                        remoteVoice = remoteVoice,
                        systemVoice = systemVoice,
                        responseFormat = responseFormat,
                        apiKeyInput = apiKey,
                    ) {
                        apiKey = ""
                        scope.launch { snackbarHostState.showSnackbar("已保存") }
                        if (provider == TtsProviderType.MINIMAX) viewModel.refreshUsage(model)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private data class TtsCatalogItem(val id: String, val name: String, val subtitle: String?)

/** Map an Android [android.speech.tts.Voice] quality bucket (100–500) to a short label (≈ iOS premium/enhanced/标准). */
internal fun systemVoiceQualityLabel(quality: Int): String = when {
    quality >= 400 -> "高品质" // VERY_HIGH / HIGH
    quality >= 300 -> "标准"   // NORMAL
    else -> "低"               // LOW / VERY_LOW
}

/** Generic fetch-on-open, type-to-filter dropdown for TTS models or voices (shape mirrors ApiConfigScreen's). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsCatalogDropdownField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    items: List<TtsCatalogItem>,
    loading: Boolean,
    error: String?,
    onFetch: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val query = value.trim()
    val isExact = items.any { it.id.equals(query, ignoreCase = true) }
    val filtered = if (query.isEmpty() || isExact) {
        items
    } else {
        // subtitle joins the match so Volink voices are searchable by both their Chinese name
        // and the English alias shown after「·」(and system voices by their quality label).
        items.filter {
            it.id.contains(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true) ||
                it.subtitle?.contains(query, ignoreCase = true) == true
        }
    }
    val shown = filtered.take(CATALOG_LIST_CAP)

    AppDropdownTextField(
        value = value,
        onValueChange = { onValueChange(it); expanded = true },
        expanded = expanded,
        onExpandedChange = {
            expanded = it
            if (it && items.isEmpty() && !loading) onFetch()
        },
        label = label,
        loading = loading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            loading -> DropdownMenuItem(text = { Text("拉取中…") }, onClick = {}, enabled = false)
            items.isEmpty() -> {
                error?.let { err ->
                    DropdownMenuItem(
                        text = { Text(err, color = MaterialTheme.colorScheme.error) },
                        onClick = {},
                        enabled = false,
                    )
                }
                DropdownMenuItem(text = { Text("点此拉取") }, onClick = { onFetch() })
            }
            else -> {
                if (shown.isEmpty()) {
                    DropdownMenuItem(text = { Text("无匹配项") }, onClick = {}, enabled = false)
                }
                shown.forEach { item ->
                    AppDropdownMenuItem(
                        text = item.subtitle?.let { "${item.name} · $it" } ?: item.name,
                        selected = item.id == value,
                        onClick = { onValueChange(item.id); expanded = false },
                    )
                }
                if (filtered.size > shown.size) {
                    DropdownMenuItem(text = { Text("继续输入以筛选…") }, onClick = {}, enabled = false)
                }
                DropdownMenuItem(text = { Text("重新拉取") }, onClick = { onFetch() })
            }
        }
    }
}

private const val CATALOG_LIST_CAP = 100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniMaxRegionPicker(baseUrl: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val region = MiniMaxRegion.detect(baseUrl)
    val hint = region?.localizedHint
    AppDropdownField(
        value = region?.localizedLabel ?: "自定义端点",
        expanded = open,
        onExpandedChange = { open = it },
        label = "MiniMax 区域",
        supportingText = hint,
        modifier = Modifier.fillMaxWidth(),
    ) {
        MiniMaxRegion.entries.forEach { entry ->
            AppDropdownMenuItem(
                text = entry.localizedLabel,
                selected = entry == region,
                onClick = { onPick(entry.baseUrl); open = false },
            )
        }
    }
}

@Composable
private fun ResponseFormatPicker(value: TtsResponseFormat, onPick: (TtsResponseFormat) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("音频格式", style = MaterialTheme.typography.bodyMedium)
        TtsResponseFormat.entries.forEach { format ->
            AppChoiceChip(
                selected = value == format,
                onClick = { onPick(format) },
                label = format.displayName,
            )
        }
    }
}

@Composable
private fun CostHint(estimate: TtsCostEstimate) {
    val text = buildString {
        append("近 7 天 ${estimate.actualCharactersLast7Days} 字符")
        val usd = estimate.projectedMonthlyUSD
        if (usd != null) append(" · 预估月费 ~\$%.2f".format(usd)) else append(" · 该模型未公开按量单价")
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TtsProviderTutorial(provider: TtsProviderType) {
    var expanded by remember(provider) { mutableStateOf(false) }
    val (header, body) = tutorialContent(provider)
    // M3 Card → appCardSurface（§4.A12·折叠行为零改·clickable 后置裁 ripple）；教程卡自成一卡。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appCardSurface()
            .clickable { expanded = !expanded }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(header, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
            )
        }
        if (expanded) {
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun tutorialContent(provider: TtsProviderType): Pair<String, String> = when (provider) {
    TtsProviderType.SYSTEM -> "关于系统 TTS" to
        "本机系统语音引擎，免 key、免费、纯本地。国行 HyperOS 多为小米语音引擎（部分功能需联网、各机型质量参差），无 Google TTS。追求自然度与情绪表现，建议改用 MiniMax 云。"
    TtsProviderType.MINIMAX -> "如何获取 MiniMax key" to
        "1. 打开 platform.minimaxi.com 注册账号（国内区，需实名）。\n" +
        "2. 在「账户管理 → 接口密钥」创建 API Key 并复制。\n" +
        "3. 国内区端点 api.minimaxi.com 已默认填好；海外 minimax.io 的 key 在此不通用（会报错 1004）。\n" +
        "4. 模型默认 speech-2.8-hd（支持情绪与 (laughs)/(sighs) 语气标签）。点「默认音色 ID」可从你的账号实时拉取音色列表。"
    TtsProviderType.VOLINK -> "如何获取 Volink key" to
        "1. 打开 api.volink.org 注册中转账号并充值（套餐通常较实惠）。\n" +
        "2. 在后台获取 API Key（合成端点 /v1/tts/speech 已默认填好）。\n" +
        "3. 点「模型名」「默认音色 ID」可从你的账号实时拉取可用列表（先选模型再拉音色，列表按模型过滤；也可直接手填音色 ID）。"
    TtsProviderType.OPENAI -> "如何获取 OpenAI key" to
        "1. 在 platform.openai.com 创建 API Key（国内通常需中转/代理才能连通）。\n" +
        "2. 模型如 tts-1 / tts-1-hd / gpt-4o-mini-tts；内置音色 alloy、echo、nova 等。"
    TtsProviderType.CUSTOM_OPENAI_COMPATIBLE -> "自定义 OpenAI 兼容 TTS" to
        "任何兼容 OpenAI /v1/audio/speech 协议的 TTS 服务：填写其 Base URL、API Key、模型名与音色 ID 即可。"
}
