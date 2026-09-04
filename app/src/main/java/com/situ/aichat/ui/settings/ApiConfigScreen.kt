package com.situ.aichat.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.isHttpsBaseUrl
import com.situ.aichat.data.local.entity.detectedToolSupportLevel
import com.situ.aichat.data.local.entity.effectiveAudioInputEnabled
import com.situ.aichat.data.local.entity.effectiveIsThinkingModel
import com.situ.aichat.data.local.entity.effectiveVisionEnabled
import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.KnownModelCapabilityTable
import com.situ.aichat.data.model.ToolSupportLevel
import com.situ.aichat.data.remote.llm.ApiBalanceResult
import com.situ.aichat.share.ApiConfigShareCodec
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDropdownField
import com.situ.aichat.ui.designsystem.AppDropdownMenuItem
import com.situ.aichat.ui.designsystem.AppDropdownTextField
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.util.QrCodec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConfigScreen(
    onBack: () -> Unit,
    onEditConfig: (String) -> Unit,
    onOpenScan: () -> Unit = {},
    scannedConfig: String? = null,
    onScanConsumed: () -> Unit = {},
    viewModel: ApiConfigViewModel = hiltViewModel(),
) {
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val detecting by viewModel.detecting.collectAsStateWithLifecycle()
    val modelCatalogState by viewModel.modelCatalogState.collectAsStateWithLifecycle()
    val balances by viewModel.balances.collectAsStateWithLifecycle()
    val assignments by viewModel.assignments.collectAsStateWithLifecycle() // settings-api-3 功能承接提示
    val undetermined by viewModel.undetermined.collectAsStateWithLifecycle() // settings-api-6 检测不确定提示
    val exportPayload by viewModel.exportPayload.collectAsStateWithLifecycle() // 13.10b 扫码导出

    // Auto-refresh balances once the config list first appears (mirrors iOS refresh-on-appear).
    LaunchedEffect(configs.isNotEmpty()) {
        if (configs.isNotEmpty()) viewModel.refreshBalances()
    }

    var provider by remember { mutableStateOf(ApiProviderType.DEEPSEEK) }
    var baseUrl by remember { mutableStateOf(provider.defaultBaseUrl) }
    var model by remember { mutableStateOf(provider.defaultModelName) }
    var apiKey by remember { mutableStateOf("") }
    var providerMenuOpen by remember { mutableStateOf(false) }

    // settings-api-5：保存反馈（成功清空输入+提示；失败弹错误且保留已填密钥）。
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMsg = stringResource(R.string.api_config_saved)
    val keychainFailMsg = stringResource(R.string.api_save_failed_keychain)
    val dbFailMsg = stringResource(R.string.api_save_failed_db)
    LaunchedEffect(Unit) {
        viewModel.feedback.collect { fb ->
            when (fb) {
                ApiSaveFeedback.SavedCreate -> { apiKey = ""; snackbarHostState.showSnackbar(savedMsg) }
                ApiSaveFeedback.KeychainFailed -> snackbarHostState.showSnackbar(keychainFailMsg)
                ApiSaveFeedback.DbFailed -> snackbarHostState.showSnackbar(dbFailMsg)
            }
        }
    }

    // 13.10b 扫码导入：扫码屏回传的二维码文本 → 解码为配置则预填表单（让用户核对后走既有「保存并启用」），否则提示无效。
    val scanImportedMsg = stringResource(R.string.api_scan_imported)
    val scanInvalidMsg = stringResource(R.string.api_scan_invalid)
    LaunchedEffect(scannedConfig) {
        val raw = scannedConfig ?: return@LaunchedEffect
        val decoded = ApiConfigShareCodec.decode(raw)
        if (decoded != null) {
            provider = decoded.provider
            baseUrl = decoded.baseUrl.ifBlank { decoded.provider.defaultBaseUrl }
            model = decoded.model.ifBlank { decoded.provider.defaultModelName }
            apiKey = decoded.key
            viewModel.clearModels()
            snackbarHostState.showSnackbar(scanImportedMsg)
        } else {
            snackbarHostState.showSnackbar(scanInvalidMsg)
        }
        onScanConsumed()
    }

    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = "API 配置",
                onBack = onBack,
                lifted = listState.canScrollBackward,
                actions = {
                    // 13.10b 扫码导入：扫二维码一键填好配置（相机/相册）。
                    IconButton(onClick = onOpenScan) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = stringResource(R.string.api_scan_import),
                        )
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .contentMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 表单区（服务商→保存并启用）合并为一个 item 内的无标题卡壳（§4.B1·操作页 16 gutter 由 LazyColumn
            // 提供·卡内 16·字段组 spacedBy 12 沿用；模型+能力提示保持原组紧凑）。
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().appCardSurface().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
                                    baseUrl = p.defaultBaseUrl
                                    model = p.defaultModelName
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
                    // 模型 + 能力提示保持原组紧凑（原同一 item·hint 自带 top 4）。
                    Column(Modifier.fillMaxWidth()) {
                        ModelDropdownField(
                            model = model,
                            onModelChange = { model = it },
                            state = modelCatalogState,
                            onFetch = { viewModel.fetchModels(provider, baseUrl, apiKey) },
                        )
                        KnownCapabilityHint(model)
                    }
                    ApiKeyField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            viewModel.clearModels() // 换了 Key，旧列表即刻作废
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppButton(
                        onClick = {
                            if (apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank() && isHttpsBaseUrl(baseUrl)) {
                                // 不在此乐观清空；成功(SavedCreate)才清空，失败保留已填密钥（settings-api-5）。
                                viewModel.save(provider, baseUrl, model, apiKey)
                            }
                        },
                        style = AppButtonStyle.Text,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("保存并启用")
                    }
                }
            }

            if (configs.isNotEmpty()) {
                item {
                    Text(
                        "已保存的配置",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                    )
                }
                items(configs, key = { it.uuid }) { cfg ->
                    ConfigCard(
                        cfg = cfg,
                        isDetecting = cfg.uuid in detecting,
                        isUndetermined = cfg.uuid in undetermined,
                        balance = balances[cfg.uuid],
                        functionNames = ApiFunctionRouter.assignedFunctionDisplayNames(assignments, cfg.uuid, cfg.isActive),
                        onActivate = { viewModel.activate(cfg.uuid) },
                        onEdit = { onEditConfig(cfg.uuid) },
                        onClone = { viewModel.clone(cfg.uuid) },
                        onExportQr = { viewModel.exportQr(cfg.uuid) },
                        onRedetect = { viewModel.redetect(cfg.uuid) },
                        onRefreshBalance = { viewModel.refreshBalance(cfg.uuid) },
                        onDelete = { viewModel.delete(cfg) },
                    )
                }
            }
        }
    }

    // 13.10b 扫码导出：「生成二维码」后弹窗显示二维码 + 明文密钥警示。
    exportPayload?.let { payload ->
        ExportQrDialog(payload = payload, onDismiss = viewModel::dismissExportQr)
    }
}

/** 13.10b · C7：配置二维码导出弹窗——显示二维码（含明文密钥）+ 安全警示 + 关闭。 */
@Composable
private fun ExportQrDialog(payload: String, onDismiss: () -> Unit) {
    val qr = remember(payload) { QrCodec.encode(payload, 640).asImageBitmap() }
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.api_export_qr_title),
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        content = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = qr,
                    contentDescription = stringResource(R.string.api_export_qr_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.api_export_qr_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

/** Instant capability hint from the static known-model table, shown under the model field. */
@Composable
internal fun KnownCapabilityHint(model: String) {
    val known = KnownModelCapabilityTable.lookup(model) ?: return
    val parts = buildList {
        if (known.isThinking) add(stringResource(R.string.api_capability_thinking))
        if (known.hasVision) add(stringResource(R.string.api_capability_vision))
        if (known.hasToolCalling) add(stringResource(R.string.api_capability_tool))
        if (known.hasAudioInput) add(stringResource(R.string.api_capability_audio))
    }
    val text = if (parts.isEmpty()) {
        stringResource(R.string.api_capability_known_none)
    } else {
        stringResource(R.string.api_capability_known_prefix) +
            parts.joinToString(stringResource(R.string.api_capability_separator))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

@Composable
private fun ConfigCard(
    cfg: ApiConfigEntity,
    isDetecting: Boolean,
    isUndetermined: Boolean,
    balance: ApiBalanceResult?,
    functionNames: List<String>,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onClone: () -> Unit,
    onExportQr: () -> Unit,
    onRedetect: () -> Unit,
    onRefreshBalance: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val supportsBalance = ApiProviderType.fromRaw(cfg.providerTypeRaw).let {
        it == ApiProviderType.DEEPSEEK || it == ApiProviderType.OPENROUTER
    }
    // M3 Card → appCardSurface（§4.B1·外层承托 + 内层 padding 12→16）；卡内行/徽章/菜单/按钮零改。
    Column(Modifier.fillMaxWidth().appCardSurface()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(cfg.providerName, style = MaterialTheme.typography.titleSmall)
                    Text(cfg.modelName, style = MaterialTheme.typography.bodySmall)
                    BalanceLabel(balance)
                }
                if (supportsBalance) {
                    IconButton(onClick = onRefreshBalance) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.api_balance_refresh),
                        )
                    }
                }
                if (cfg.isActive) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.a11y_already_enabled),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    AppButton(onClick = onActivate, style = AppButtonStyle.Text) { Text("启用") }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.api_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                }
                // settings-api-4：复制配置（安卓地道收进溢出菜单，等价 iOS 左滑/长按两入口）。
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.api_more))
                    }
                    AppMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                        AppMenuItem(
                            text = stringResource(R.string.api_duplicate),
                            leadingIcon = Icons.Filled.ContentCopy,
                            onClick = { onClone(); menuOpen = false },
                        )
                        // 13.10b 扫码导出：生成含本配置的二维码供另一台设备扫码导入。
                        AppMenuItem(
                            text = stringResource(R.string.api_export_qr),
                            leadingIcon = Icons.Filled.QrCode2,
                            onClick = { onExportQr(); menuOpen = false },
                        )
                    }
                }
            }

            CapabilityBadges(cfg = cfg, isDetecting = isDetecting)

            // settings-api-6：最近检测返回「不确定」时的原因提示（橙/error 色，对齐 iOS detectionHint）。
            if (isUndetermined) {
                Text(
                    text = stringResource(R.string.api_detection_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // settings-api-3：该配置承接的功能「用于：…」（对齐 iOS functionAssignmentText）。
            if (functionNames.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.api_function_assignment_prefix) +
                        functionNames.joinToString(stringResource(R.string.api_capability_separator)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), // ≈ iOS .tertiary
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            AppButton(
                onClick = onRedetect,
                style = AppButtonStyle.Text,
                enabled = !isDetecting,
            ) {
                Text(stringResource(R.string.api_capability_redetect))
            }
        }
    }
}

/** Account-balance label (mirrors iOS balanceLabel): ¥ for DeepSeek, $ for OpenRouter; red when low. */
@Composable
private fun BalanceLabel(result: ApiBalanceResult?) {
    when (result) {
        is ApiBalanceResult.DeepSeek -> {
            val low = result.totalBalance < 10
            Text(
                "¥%.2f".format(result.totalBalance),
                style = MaterialTheme.typography.labelSmall,
                color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is ApiBalanceResult.OpenRouter -> {
            val limit = result.limit
            if (limit != null) {
                Text(
                    "\$%.2f/\$%.2f".format(result.usage, limit),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                result.limitRemaining?.let { rem ->
                    val low = rem < 10
                    Text(
                        stringResource(R.string.api_balance_remaining, "\$%.2f".format(rem)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.api_balance_used, "\$%.2f".format(result.usage)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ApiBalanceResult.Unsupported, ApiBalanceResult.Failed, null -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityBadges(cfg: ApiConfigEntity, isDetecting: Boolean) {
    if (isDetecting) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppLoadingRing(size = AppLoadingRingSize.Small)
            Text(
                stringResource(R.string.api_capability_detecting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val labels = buildList {
        if (cfg.effectiveIsThinkingModel()) add(stringResource(R.string.api_capability_thinking))
        if (cfg.effectiveVisionEnabled()) add(stringResource(R.string.api_capability_vision))
        if (cfg.effectiveAudioInputEnabled()) add(stringResource(R.string.api_capability_audio))
        when (cfg.detectedToolSupportLevel) {
            ToolSupportLevel.FULL -> add(stringResource(R.string.api_capability_tool_full))
            ToolSupportLevel.BASIC -> add(stringResource(R.string.api_capability_tool_basic))
            else -> Unit
        }
    }

    if (labels.isEmpty()) {
        Text(
            stringResource(R.string.api_capability_none_detected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEach { CapabilityChip(it) }
    }
}

/**
 * Editable model field that doubles as a fetch-on-open, type-to-filter dropdown.
 * - Tapping the field/arrow auto-fetches the model list for the current form values —— **仅当状态是
 *   [ModelCatalogUiState.Idle]**（旧实现的条件是「列表为空」，失败会清空列表 → 每次展开都重发一次
 *   最长 20s 的请求；现在失败后停在 Failed 态，只由用户点「重新拉取」）。
 * - Typing filters the fetched list; 精确命中已选 id 时不过滤成自己。
 * - Manual entry still works (just type a custom model name and don't pick).
 * - 列表**全量可滚**（旧实现 take(100)，OpenRouter 300+ 模型时已选中的那个可能根本不在前 100 条里，
 *   重开菜单选中态就消失了）；已选中项恒排首位保证可见。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelDropdownField(
    model: String,
    onModelChange: (String) -> Unit,
    state: ModelCatalogUiState,
    onFetch: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val query = model.trim()
    val available = state.models
    val isExactSelection = available.any { it.id.equals(query, ignoreCase = true) }
    val filtered = if (query.isEmpty() || isExactSelection) {
        available
    } else {
        available.filter {
            it.id.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)
        }
    }
    // 已选中项置顶：长列表里它可能排在很后面，滚动不到 = 看不到选中态。
    val shown = if (isExactSelection) {
        filtered.sortedByDescending { it.id.equals(query, ignoreCase = true) }
    } else {
        filtered
    }

    AppDropdownTextField(
        value = model,
        onValueChange = {
            onModelChange(it)
            expanded = true
        },
        expanded = expanded,
        onExpandedChange = {
            expanded = it
            if (it && state is ModelCatalogUiState.Idle) onFetch()
        },
        label = "模型名",
        loading = state.isLoading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        state.error?.let { err ->
            DropdownMenuItem(
                text = { Text(err, color = MaterialTheme.colorScheme.error) },
                onClick = {},
                enabled = false,
            )
        }
        when {
            state.isLoading -> DropdownMenuItem(
                text = { Text(stringResource(R.string.api_fetching_models)) },
                onClick = {},
                enabled = false,
            )
            state is ModelCatalogUiState.Empty -> DropdownMenuItem(
                text = { Text(stringResource(R.string.api_models_empty_hint)) },
                onClick = {},
                enabled = false,
            )
            else -> {
                if (available.isNotEmpty() && shown.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.api_model_no_match)) },
                        onClick = {},
                        enabled = false,
                    )
                }
                shown.forEach { opt ->
                    AppDropdownMenuItem(
                        text = opt.subtitle?.let { "${opt.name} · $it" } ?: opt.name,
                        selected = opt.id == model,
                        onClick = {
                            onModelChange(opt.id)
                            expanded = false
                        },
                    )
                }
            }
        }
        if (!state.isLoading) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (available.isEmpty()) R.string.api_fetch_models else R.string.api_refetch_models,
                        ),
                    )
                },
                onClick = { onFetch() },
            )
        }
    }
}

@Composable
private fun CapabilityChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
