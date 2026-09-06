package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.situ.aichat.tts.TtsProviderType
import com.situ.aichat.tts.TtsResponseFormat
import com.situ.aichat.tts.provider.MiniMaxCatalog
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.designsystem.LiuliSnackbarHost
import com.situ.aichat.ui.liuli.designsystem.LiuliSpinner
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliInputRow
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliMenuRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliSaveBar
import com.situ.aichat.ui.liuli.page.liuliSaveBarInset
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.TtsConfigViewModel
import com.situ.aichat.ui.settings.systemVoiceQualityLabel
import kotlinx.coroutines.launch

/** 本屏同时只许一个下拉展开。 */
private enum class TtsMenu { Engine, Region, SystemVoice, Model, RemoteVoice }

/** 未 seeded 时的整屏转圈直径（暖陶用 `AppLoadingRingSize.Large`·琉璃取 28）。 */
private val SEED_SPINNER = 28.dp
/** 试听钮里转圈与字的缝（逐字照暖陶的 8）。 */
private val PREVIEW_GAP = 8.dp

/**
 * 语音 / TTS 设置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 11·A-6 全硬编码中文 / A-9 底部两钮保存栏）。
 * 与暖陶 `TtsConfigurationScreen` 共用 [TtsConfigViewModel]。
 *
 * 机制锁（逐字搬）：`seeded` 一次性播种（未 seeded 整屏转圈并 return）· 换引擎重置
 * `baseUrl / model / remoteVoice` + `clearCatalogs()` · **VOLINK 换模型清空音色与音色表**（中转按音色 ID
 * 路由·错配会静默 200·记忆 `reference_volink_tts_api`）· MiniMax 保存 / 播种后刷用量 · 密钥留空不改。
 *
 * 底栏两钮（A-9）：「试听」`Glass` + 「保存」`Prominent` 并排。
 */
@Composable
fun LiuliTtsConfigScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TtsConfigViewModel = hiltViewModel(),
    listState: LazyListState = rememberLazyListState(),
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
    LaunchedEffect(previewError) { previewError?.let { snackbarHostState.showSnackbar(it) } }

    var seeded by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf(TtsProviderType.SYSTEM) }
    var providerName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var remoteVoice by remember { mutableStateOf("") }
    var systemVoice by remember { mutableStateOf("") }
    var responseFormat by remember { mutableStateOf(TtsResponseFormat.MP3) }
    var openMenu by remember { mutableStateOf<TtsMenu?>(null) }

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

    val savedToast = LiuliTtsText.SAVED_TOAST
    val bottomInset = LiuliPageGeometry.pageBottom +
        liuliSaveBarInset +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = LiuliTtsText.PAGE_TITLE,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
        bottomBar = if (!seeded) {
            null
        } else {
            {
                LiuliSaveBar {
                    LiuliButton(
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
                        style = LiuliButtonStyle.Glass,
                        enabled = !previewBusy,
                        modifier = Modifier.weight(1f).height(LiuliPageGeometry.saveBarButton),
                    ) {
                        if (previewBusy) {
                            LiuliSpinner()
                            Box(Modifier.width(PREVIEW_GAP))
                        }
                        Text(LiuliTtsText.PREVIEW)
                    }
                    LiuliButton(
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
                                scope.launch { snackbarHostState.showSnackbar(savedToast) }
                                if (provider == TtsProviderType.MINIMAX) viewModel.refreshUsage(model)
                            }
                        },
                        style = LiuliButtonStyle.Prominent,
                        modifier = Modifier.weight(1f).height(LiuliPageGeometry.saveBarButton),
                    ) {
                        Text(LiuliTtsText.SAVE)
                    }
                }
            }
        },
    ) {
        if (!seeded) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { LiuliSpinner(size = SEED_SPINNER) }
            return@LiuliPage
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
            verticalArrangement = Arrangement.spacedBy(LiuliPageGeometry.tileGap),
        ) {
            item(key = "large-title") { LiuliLargeTitle(LiuliTtsText.PAGE_TITLE) }
            item(key = "engine") {
                Column(Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter)) {
                    LiuliGroup {
                        LiuliMenuRow(
                            title = LiuliTtsText.ENGINE_LABEL,
                            value = provider.displayName,
                            options = TtsProviderType.entries.map { p ->
                                LiuliMenuEntry(
                                    text = p.displayName,
                                    selected = provider == p,
                                    onClick = {
                                        provider = p
                                        providerName = p.displayName
                                        baseUrl = p.defaultBaseUrl
                                        model = if (p == TtsProviderType.MINIMAX) MiniMaxCatalog.DEFAULT_MODEL_ID else ""
                                        remoteVoice = ""
                                        viewModel.clearCatalogs()
                                    },
                                )
                            },
                            expanded = openMenu == TtsMenu.Engine,
                            onExpandedChange = { openMenu = if (it) TtsMenu.Engine else null },
                            divider = false,
                        )
                    }
                    LiuliTtsTutorialCard(provider)
                }
            }
            item(key = "fields") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    if (provider == TtsProviderType.SYSTEM) {
                        LiuliGroup(footer = LiuliTtsText.SYSTEM_NOTE) {
                            LiuliCatalogField(
                                value = systemVoice,
                                onValueChange = { systemVoice = it },
                                label = LiuliTtsText.SYSTEM_VOICE_LABEL,
                                placeholder = LiuliTtsText.SYSTEM_VOICE_PLACEHOLDER,
                                items = systemVoices.map { v ->
                                    v.id to "${v.name} · ${systemVoiceQualityLabel(v.quality)}"
                                },
                                loading = systemVoicesLoading,
                                error = null,
                                onFetch = { viewModel.loadSystemVoices() },
                                emptyHint = LiuliTtsText.NO_MATCH,
                                divider = false,
                            )
                        }
                    } else {
                        LiuliGroup(
                            footer = if (provider == TtsProviderType.MINIMAX) cost?.let { liuliTtsCostText(it) } else null,
                        ) {
                            if (provider == TtsProviderType.MINIMAX) {
                                LiuliMiniMaxRegionRow(
                                    baseUrl = baseUrl,
                                    onPick = { baseUrl = it },
                                    expanded = openMenu == TtsMenu.Region,
                                    onExpandedChange = { openMenu = if (it) TtsMenu.Region else null },
                                    divider = false,
                                )
                            }
                            LiuliInputRow(
                                label = LiuliTtsText.BASE_URL_LABEL,
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                divider = provider == TtsProviderType.MINIMAX,
                            )
                            LiuliCatalogField(
                                value = model,
                                onValueChange = {
                                    // Volink 的音色属于某一个模型，中转按音色 ID 路由（错配会静默 200）——
                                    // 换模型即作废已选音色与已拉的音色表（逐字照暖陶 :217–220）。
                                    if (provider == TtsProviderType.VOLINK && it != model) {
                                        remoteVoice = ""
                                        viewModel.clearVoices()
                                    }
                                    model = it
                                },
                                label = LiuliTtsText.MODEL_LABEL,
                                items = models.map { it.id to it.name },
                                loading = modelsLoading,
                                error = modelsError,
                                onFetch = { viewModel.fetchModels(provider, providerName, baseUrl, apiKey) },
                                emptyHint = LiuliTtsText.NO_MATCH,
                            )
                            LiuliApiKeyRow(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = LiuliTtsText.API_KEY_LABEL,
                                placeholder = if (hasApiKey) LiuliTtsText.API_KEY_SET_PLACEHOLDER else null,
                            )
                            LiuliCatalogField(
                                value = remoteVoice,
                                onValueChange = { remoteVoice = it },
                                label = LiuliTtsText.REMOTE_VOICE_LABEL,
                                items = voices.map { v -> v.id to (v.detail?.let { "${v.name} · $it" } ?: v.name) },
                                loading = voicesLoading,
                                error = voicesError,
                                onFetch = { viewModel.fetchVoices(provider, providerName, baseUrl, model, apiKey) },
                                emptyHint = LiuliTtsText.NO_MATCH,
                            )
                            LiuliResponseFormatRow(value = responseFormat, onPick = { responseFormat = it })
                        }
                    }
                }
            }
        }
        LiuliSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}
