package com.situ.aichat.ui.liuli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Tune
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
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.share.ApiConfigShareCodec
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppSaverIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliSnackbarHost
import com.situ.aichat.ui.liuli.designsystem.LiuliTileTone
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.ApiConfigViewModel
import com.situ.aichat.ui.settings.ApiSaveFeedback

private const val PAGE_TITLE = "API 配置"
private const val SAVED_SECTION_TITLE = "已保存的配置"
private val CARD_GAP = 12.dp

/**
 * 琉璃 2.0 API 配置页（设置全面重构卷二）：
 * 顶部新增「功能 API 分配」与「缓存省钱模式」；保存校验显式提示缺项；删除前二次玻璃弹窗确认。
 */
@Composable
fun LiuliApiConfigScreen(
    onBack: () -> Unit,
    onEditConfig: (String) -> Unit,
    onOpenScan: () -> Unit = {},
    scannedConfig: String? = null,
    onScanConsumed: () -> Unit = {},
    onOpenApiFunctions: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ApiConfigViewModel = hiltViewModel(),
    listState: LazyListState = rememberLazyListState(),
) {
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val detecting by viewModel.detecting.collectAsStateWithLifecycle()
    val modelCatalogState by viewModel.modelCatalogState.collectAsStateWithLifecycle()
    val balances by viewModel.balances.collectAsStateWithLifecycle()
    val assignments by viewModel.assignments.collectAsStateWithLifecycle()
    val undetermined by viewModel.undetermined.collectAsStateWithLifecycle()
    val exportPayload by viewModel.exportPayload.collectAsStateWithLifecycle()
    val cacheSaverEnabled by viewModel.cacheSaverEnabled.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ApiConfigEntity?>(null) }

    LaunchedEffect(configs.isNotEmpty()) {
        if (configs.isNotEmpty()) viewModel.refreshBalances()
    }

    var provider by remember { mutableStateOf(ApiProviderType.DEEPSEEK) }
    var baseUrl by remember { mutableStateOf(provider.defaultBaseUrl) }
    var model by remember { mutableStateOf(provider.defaultModelName) }
    var apiKey by remember { mutableStateOf("") }
    var providerMenuOpen by remember { mutableStateOf(false) }

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

    val bottomInset = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = PAGE_TITLE,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
        actions = {
            LiuliPageCircleAction(
                onClick = onOpenScan,
                contentDescription = stringResource(R.string.api_scan_import),
                icon = Icons.Filled.QrCodeScanner,
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
            verticalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            item(key = "large-title") { LiuliLargeTitle(PAGE_TITLE) }
            item(key = "service-extras") {
                Column(Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter)) {
                    LiuliGroup {
                        LiuliNavRow(
                            title = stringResource(R.string.api_fn_assign_title),
                            subtitle = stringResource(R.string.api_fn_assign_subtitle),
                            icon = Icons.Filled.Tune,
                            tileColor = LiuliTileTone.Sky,
                            divider = false,
                            onClick = onOpenApiFunctions,
                        )
                        LiuliToggleRow(
                            title = stringResource(R.string.pm_saver_title),
                            subtitle = stringResource(R.string.pm_saver_subtitle),
                            icon = AppSaverIcons.Yen,
                            tileColor = LiuliTileTone.Gold,
                            checked = cacheSaverEnabled,
                            onCheckedChange = viewModel::setCacheSaverEnabled,
                        )
                    }
                }
            }
            item(key = "new-config") {
                Column(Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter)) {
                    LiuliApiConfigForm(
                        provider = provider,
                        baseUrl = baseUrl,
                        model = model,
                        apiKey = apiKey,
                        catalogState = modelCatalogState,
                        providerMenuOpen = providerMenuOpen,
                        onProviderMenuOpenChange = { providerMenuOpen = it },
                        onProviderChange = { p ->
                            provider = p
                            baseUrl = p.defaultBaseUrl
                            model = p.defaultModelName
                            viewModel.clearModels()
                        },
                        onBaseUrlChange = {
                            baseUrl = it
                            viewModel.clearModels()
                        },
                        onModelChange = { model = it },
                        onApiKeyChange = {
                            apiKey = it
                            viewModel.clearModels()
                        },
                        onFetchModels = { viewModel.fetchModels(provider, baseUrl, apiKey) },
                        onSave = { viewModel.save(provider, baseUrl, model, apiKey) },
                    )
                }
            }
            if (configs.isNotEmpty()) {
                item(key = "saved-title") {
                    Text(
                        SAVED_SECTION_TITLE,
                        style = AppTypography.bodyEmphasis,
                        color = AppTheme.colors.text.primary,
                        modifier = Modifier.padding(
                            start = LiuliPageGeometry.gutter + LiuliPageGeometry.groupPadH,
                            top = LiuliPageGeometry.titleGap,
                        ),
                    )
                }
                items(configs, key = { it.uuid }) { cfg ->
                    LiuliApiConfigCard(
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
                        onDelete = { pendingDelete = cfg },
                        modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter),
                    )
                }
            }
        }
        LiuliSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))

        pendingDelete?.let { cfg ->
            LiuliDialog(
                onDismissRequest = { pendingDelete = null },
                title = stringResource(R.string.settings_api_delete_confirm_title),
                body = stringResource(R.string.settings_api_delete_confirm_body, "${cfg.providerName} · ${cfg.modelName}"),
                confirmText = stringResource(R.string.action_delete),
                confirmDanger = true,
                dismissText = stringResource(R.string.action_cancel),
                onConfirm = {
                    viewModel.delete(cfg)
                    pendingDelete = null
                },
            )
        }
    }

    exportPayload?.let { payload ->
        LiuliExportQrDialog(payload = payload, onDismiss = viewModel::dismissExportQr)
    }
}
