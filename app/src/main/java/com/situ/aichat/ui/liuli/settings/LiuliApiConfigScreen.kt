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
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.share.ApiConfigShareCodec
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliSnackbarHost
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.settings.ApiConfigViewModel
import com.situ.aichat.ui.settings.ApiSaveFeedback

/** 三处硬编码中文（与暖陶 `ApiConfigScreen.kt:151 / :252` 同值·A-6）。 */
private const val PAGE_TITLE = "API 配置"
private const val SAVED_SECTION_TITLE = "已保存的配置"

/** 卡与卡之间的缝（逐字照暖陶 `LazyColumn` 的 spacedBy 12）。 */
private val CARD_GAP = 12.dp

/**
 * API 配置页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 7·T2 表单 + 卡列表 + [LiuliSnackbarHost] 首用）。
 * 与暖陶 `ApiConfigScreen` 共用 [ApiConfigViewModel]。
 *
 * 机制锁（逐字搬）：进屏自动刷一次余额 · 保存反馈三分支（成功清空输入 + 提示 / 两种失败保留已填密钥）·
 * **扫码回填**（[ApiConfigShareCodec] 解得出就预填表单并提示，解不出提示无效·两条路都要 `onScanConsumed()`）·
 * 二维码导出弹窗（含明文密钥的警示句必留）。顶栏尾随 = 扫码 [LiuliPageCircleAction]（卷四 R1 ③）。
 */
@Composable
fun LiuliApiConfigScreen(
    onBack: () -> Unit,
    onEditConfig: (String) -> Unit,
    onOpenScan: () -> Unit = {},
    scannedConfig: String? = null,
    onScanConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ApiConfigViewModel = hiltViewModel(),
    listState: LazyListState = rememberLazyListState(),
) {
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val detecting by viewModel.detecting.collectAsStateWithLifecycle()
    val modelCatalogState by viewModel.modelCatalogState.collectAsStateWithLifecycle()
    val balances by viewModel.balances.collectAsStateWithLifecycle()
    val assignments by viewModel.assignments.collectAsStateWithLifecycle() // settings-api-3 功能承接提示
    val undetermined by viewModel.undetermined.collectAsStateWithLifecycle() // settings-api-6 检测不确定提示
    val exportPayload by viewModel.exportPayload.collectAsStateWithLifecycle() // 13.10b 扫码导出

    // 列表首次出现时刷一次余额（镜像 iOS refresh-on-appear·逐字照暖陶 :102）。
    LaunchedEffect(configs.isNotEmpty()) {
        if (configs.isNotEmpty()) viewModel.refreshBalances()
    }

    var provider by remember { mutableStateOf(ApiProviderType.DEEPSEEK) }
    var baseUrl by remember { mutableStateOf(provider.defaultBaseUrl) }
    var model by remember { mutableStateOf(provider.defaultModelName) }
    var apiKey by remember { mutableStateOf("") }
    var providerMenuOpen by remember { mutableStateOf(false) }

    // settings-api-5：保存反馈（成功清空输入 + 提示；失败弹错误且保留已填密钥·逐字照暖陶 :112–125）。
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

    // 13.10b 扫码导入：扫码屏回传的二维码文本 → 解码为配置则预填表单，否则提示无效（逐字照暖陶 :131–145）。
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
            // 13.10b 扫码导入：扫二维码一键填好配置（相机 / 相册）。
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
                            viewModel.clearModels() // 换了端点，旧列表即刻作废
                        },
                        onModelChange = { model = it },
                        onApiKeyChange = {
                            apiKey = it
                            viewModel.clearModels() // 换了 Key，旧列表即刻作废
                        },
                        onFetchModels = { viewModel.fetchModels(provider, baseUrl, apiKey) },
                        onSave = {
                            // 不在此乐观清空；成功（SavedCreate）才清空，失败保留已填密钥（settings-api-5）。
                            viewModel.save(provider, baseUrl, model, apiKey)
                        },
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
                        onDelete = { viewModel.delete(cfg) },
                        modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter),
                    )
                }
            }
        }
        LiuliSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    // 13.10b 扫码导出：「生成二维码」后弹窗显示二维码 + 明文密钥警示。
    exportPayload?.let { payload ->
        LiuliExportQrDialog(payload = payload, onDismiss = viewModel::dismissExportQr)
    }
}
