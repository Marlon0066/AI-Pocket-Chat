package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AudioInputMode
import com.situ.aichat.data.model.ThinkingBudgetLevel
import com.situ.aichat.data.model.ThinkingBudgetSupport
import com.situ.aichat.data.model.ThinkingModelMode
import com.situ.aichat.data.model.ToolCallingMode
import com.situ.aichat.data.model.VisionMode
import com.situ.aichat.data.repository.isHttpsBaseUrl
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.designsystem.LiuliSnackbarHost
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
import com.situ.aichat.ui.settings.ApiConfigViewModel
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.ui.settings.ApiSaveFeedback
import com.situ.aichat.ui.settings.ModelCatalogUiState
import kotlinx.coroutines.flow.Flow
import com.situ.aichat.ui.settings.ToolDetectionStatusBlock
import com.situ.aichat.ui.settings.resolveNewApiKey

/** 两处硬编码中文（与暖陶 `ApiConfigEditScreen.kt:148 / :171` 同值·A-6）。 */
private const val PROVIDER_LABEL = "服务商"
private const val BASE_URL_LABEL = "Base URL"

/** 本屏同时只许一个下拉展开——用一枚枚举当「谁开着」的钥匙。 */
private enum class EditMenu { Provider, Model, Thinking, Intensity, Tool, Vision, Audio }

/**
 * API 配置编辑页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 8·A-9 长表单 + `bottomBar` 保存栏）。
 * 与暖陶 `ApiConfigEditScreen` 共用 [ApiConfigViewModel]。
 *
 * 机制锁（逐字搬）：草稿 `remember(config?.uuid)` 播种 · 进屏读回已存 key 作 [resolveNewApiKey] 的比对基准
 * （§2.2-2 已提 internal·**安全逻辑零碰**：没真改就传 null，不重写密钥库也不白跑一轮能力检测）·
 * 换 provider / baseUrl / key 一律 `clearModels()` · 保存守卫四条件 · 思考强度显隐
 * （`effectiveThinking && support.showsControl`）· `ToolDetectionStatusBlock` 直接**借用**（§9 ⑤ 明列可借）。
 */
@Composable
fun LiuliApiConfigEditScreen(
    uuid: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ApiConfigViewModel = hiltViewModel(),
    listState: LazyListState = rememberLazyListState(),
) {
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val modelCatalogState by viewModel.modelCatalogState.collectAsStateWithLifecycle()
    val detecting by viewModel.detecting.collectAsStateWithLifecycle()
    val config = configs.firstOrNull { it.uuid == uuid }
    // 已存 key 只有 VM 拿得到；读回来交给内容层当 [resolveNewApiKey] 的比对基准（安全逻辑零碰）。
    var loadedKey by remember(config?.uuid) { mutableStateOf("") }
    LaunchedEffect(config?.uuid) { if (config != null) loadedKey = viewModel.storedApiKey(uuid) }

    LiuliApiConfigEditContent(
        uuid = uuid,
        config = config,
        storedKey = loadedKey,
        catalogState = modelCatalogState,
        isDetecting = uuid in detecting,
        feedback = viewModel.feedback,
        onClearModels = viewModel::clearModels,
        onFetchModels = { provider, baseUrl, key -> viewModel.fetchModels(provider, baseUrl, key) },
        onRedetect = { viewModel.redetect(uuid) },
        onSave = { p, b, m, k, tm, tc, vm, am, lv ->
            viewModel.updateConfig(
                uuid = uuid,
                provider = p,
                baseUrl = b,
                model = m,
                newApiKey = k,
                thinkingModelMode = tm,
                toolCallingMode = tc,
                visionMode = vm,
                audioInputMode = am,
                thinkingBudgetLevel = lv,
                onSaved = onBack,
            )
        },
        onBack = onBack,
        modifier = modifier,
        listState = listState,
    )
}

/**
 * API 编辑页内容层（纯参数·可测）。草稿态住这里（暖陶也一样住屏里），VM 只在外层订阅与写。
 * [feedback] 是保存失败的事件流（Keychain / DB）；[onSave] 的九个实参与
 * [ApiConfigViewModel.updateConfig] 一一对应，`newApiKey` 已由本层经 [resolveNewApiKey] 归一。
 */
@Composable
internal fun LiuliApiConfigEditContent(
    uuid: String,
    config: ApiConfigEntity?,
    storedKey: String,
    catalogState: ModelCatalogUiState,
    isDetecting: Boolean,
    feedback: Flow<ApiSaveFeedback>,
    onClearModels: () -> Unit,
    onFetchModels: (ApiProviderType, String, String) -> Unit,
    onRedetect: () -> Unit,
    onSave: (
        ApiProviderType, String, String, String?,
        ThinkingModelMode, ToolCallingMode, VisionMode, AudioInputMode, ThinkingBudgetLevel,
    ) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val modelCatalogState = catalogState

    var provider by remember(config?.uuid) {
        mutableStateOf(config?.let { ApiProviderType.fromRaw(it.providerTypeRaw) } ?: ApiProviderType.DEEPSEEK)
    }
    var baseUrl by remember(config?.uuid) { mutableStateOf(config?.baseURL ?: "") }
    var model by remember(config?.uuid) { mutableStateOf(config?.modelName ?: "") }
    // key 预填：进屏后把加密库里已存的 key 读出填入（默认打码·点眼睛可见），「拉取模型列表」因此能拿到真 key。
    // storedKey 同时留作保存时的比对基准（逐字照暖陶 :72–81）。
    var apiKey by remember(config?.uuid) { mutableStateOf("") }
    LaunchedEffect(storedKey) { if (apiKey.isEmpty()) apiKey = storedKey }
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
    var openMenu by remember { mutableStateOf<EditMenu?>(null) }

    val support = ThinkingBudgetSupport.resolve(provider, baseUrl, model)
    val effectiveThinking = when (thinkingMode) {
        ThinkingModelMode.THINKING -> true
        ThinkingModelMode.STANDARD -> false
        ThinkingModelMode.AUTO -> config?.detectedThinkingModelType == 1
    }
    val showIntensity = effectiveThinking && support.showsControl

    // settings-api-5：保存失败（Keychain/DB）留在编辑屏弹错误，不再静默返回丢密钥（逐字照暖陶 :110–120）。
    val snackbarHostState = remember { SnackbarHostState() }
    val keychainFailMsg = stringResource(R.string.api_save_failed_keychain)
    val dbFailMsg = stringResource(R.string.api_save_failed_db)
    LaunchedEffect(Unit) {
        feedback.collect { fb ->
            when (fb) {
                ApiSaveFeedback.KeychainFailed -> snackbarHostState.showSnackbar(keychainFailMsg)
                ApiSaveFeedback.DbFailed -> snackbarHostState.showSnackbar(dbFailMsg)
                ApiSaveFeedback.SavedCreate -> Unit // 新建反馈属列表屏，编辑屏忽略
            }
        }
    }

    val title = stringResource(R.string.api_edit_title)
    val urlInsecure = baseUrl.isNotBlank() && !isHttpsBaseUrl(baseUrl)
    val canSave = config != null && baseUrl.isNotBlank() && model.isNotBlank() && isHttpsBaseUrl(baseUrl)
    val bottomInset = LiuliPageGeometry.pageBottom +
        liuliSaveBarInset +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
        bottomBar = {
            LiuliSaveBar(
                text = stringResource(R.string.api_save),
                enabled = canSave,
                onClick = {
                    if (config == null) return@LiuliSaveBar
                    onSave(
                        provider, baseUrl, model, resolveNewApiKey(apiKey, storedKey),
                        thinkingMode, toolMode, visionMode, audioMode, thinkingLevel,
                    )
                },
            )
        },
    ) {
        LazyColumn(
            // C4：键盘弹起时 API key 等字段可滚到键盘上方（逐字照暖陶）。
            modifier = Modifier.fillMaxSize().imePadding().contentMaxWidth(),
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
                    LiuliGroup(footer = liuliKnownCapabilityHint(model)) {
                        LiuliMenuRow(
                            title = PROVIDER_LABEL,
                            value = provider.displayName,
                            options = ApiProviderType.entries.map { p ->
                                LiuliMenuEntry(
                                    text = p.displayName,
                                    selected = provider == p,
                                    onClick = { provider = p; onClearModels() },
                                )
                            },
                            expanded = openMenu == EditMenu.Provider,
                            onExpandedChange = { openMenu = if (it) EditMenu.Provider else null },
                            divider = false,
                        )
                        LiuliInputRow(
                            label = BASE_URL_LABEL,
                            value = baseUrl,
                            onValueChange = {
                                baseUrl = it
                                onClearModels() // 换了端点，旧列表即刻作废
                            },
                            supportingText = if (urlInsecure) stringResource(R.string.api_url_https_required) else null,
                        )
                        LiuliCatalogField(
                            value = model,
                            onValueChange = { model = it },
                            label = LiuliApiText.MODEL_LABEL,
                            items = modelCatalogState.models.map { it.id to (it.subtitle?.let { s -> "${it.name} · $s" } ?: it.name) },
                            loading = modelCatalogState.isLoading,
                            error = modelCatalogState.error,
                            onFetch = { onFetchModels(provider, baseUrl, apiKey) },
                            emptyHint = stringResource(R.string.api_model_no_match),
                            fetchedEmptyHint = if (modelCatalogState is ModelCatalogUiState.Empty) stringResource(R.string.api_models_empty_hint) else null,
                        )
                        LiuliApiKeyRow(
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                onClearModels() // 换了 Key，旧列表即刻作废
                            },
                            supportingText = stringResource(R.string.api_key_keep),
                        )
                    }

                    LiuliGroup(
                        header = stringResource(R.string.api_section_capabilities),
                        footer = if (showIntensity) liuliLevelHint(support.normalized(thinkingLevel)) else null,
                    ) {
                        LiuliModePickerRow(
                            title = stringResource(R.string.api_row_thinking),
                            badge = if (thinkingMode == ThinkingModelMode.AUTO) {
                                stringResource(R.string.api_det_prefix) +
                                    liuliThinkingDetectedText(config?.detectedThinkingModelType ?: -1)
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
                            expanded = openMenu == EditMenu.Thinking,
                            onExpandedChange = { openMenu = if (it) EditMenu.Thinking else null },
                            divider = false,
                        )
                        // 思考强度只在「实际是思考模型 + 该服务商真有这档控制」时出（逐字照暖陶 :106）。
                        if (showIntensity) {
                            LiuliModePickerRow(
                                title = stringResource(R.string.api_row_thinking_intensity),
                                badge = null,
                                options = support.allowedLevels.map { liuliLevelLabel(it) to it },
                                selected = support.normalized(thinkingLevel),
                                onSelect = { thinkingLevel = it },
                                expanded = openMenu == EditMenu.Intensity,
                                onExpandedChange = { openMenu = if (it) EditMenu.Intensity else null },
                            )
                        }
                        LiuliModePickerRow(
                            title = stringResource(R.string.api_row_tool),
                            badge = null,
                            options = liuliCapabilityModeOptions { auto, enabled, disabled ->
                                listOf(
                                    auto to ToolCallingMode.AUTO,
                                    enabled to ToolCallingMode.ENABLED,
                                    disabled to ToolCallingMode.DISABLED,
                                )
                            },
                            selected = toolMode,
                            onSelect = { toolMode = it },
                            expanded = openMenu == EditMenu.Tool,
                            onExpandedChange = { openMenu = if (it) EditMenu.Tool else null },
                        )
                        if (config != null) {
                            LiuliRowBase(verticalPadding = LiuliPageGeometry.rowTwoLinePad, verticalAlignment = Alignment.Top) {
                                // 纯状态块·读 MaterialTheme 取色自动对档（§9 ⑤ 明列「禁重写」= 直接借用）。
                                ToolDetectionStatusBlock(
                                    config = config,
                                    toolMode = toolMode,
                                    detecting = isDetecting,
                                    onRedetect = onRedetect,
                                )
                            }
                        }
                        LiuliModePickerRow(
                            title = stringResource(R.string.api_row_vision),
                            badge = if (visionMode == VisionMode.AUTO) {
                                stringResource(R.string.api_det_prefix) +
                                    liuliCapDetectedText(config?.detectedVisionSupport ?: -1)
                            } else {
                                null
                            },
                            options = liuliCapabilityModeOptions { auto, enabled, disabled ->
                                listOf(auto to VisionMode.AUTO, enabled to VisionMode.ENABLED, disabled to VisionMode.DISABLED)
                            },
                            selected = visionMode,
                            onSelect = { visionMode = it },
                            expanded = openMenu == EditMenu.Vision,
                            onExpandedChange = { openMenu = if (it) EditMenu.Vision else null },
                        )
                        LiuliModePickerRow(
                            title = stringResource(R.string.api_row_audio),
                            badge = if (audioMode == AudioInputMode.AUTO) {
                                stringResource(R.string.api_det_prefix) +
                                    liuliCapDetectedText(config?.detectedAudioInputSupport ?: -1)
                            } else {
                                null
                            },
                            options = liuliCapabilityModeOptions { auto, enabled, disabled ->
                                listOf(
                                    auto to AudioInputMode.AUTO,
                                    enabled to AudioInputMode.ENABLED,
                                    disabled to AudioInputMode.DISABLED,
                                )
                            },
                            selected = audioMode,
                            onSelect = { audioMode = it },
                            expanded = openMenu == EditMenu.Audio,
                            onExpandedChange = { openMenu = if (it) EditMenu.Audio else null },
                        )
                    }
                }
            }
        }
        LiuliSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}
