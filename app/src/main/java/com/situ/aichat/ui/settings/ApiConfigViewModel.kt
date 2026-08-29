package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AudioInputMode
import com.situ.aichat.data.model.MaxOutputLength
import com.situ.aichat.data.model.ThinkingBudgetLevel
import com.situ.aichat.data.model.ThinkingModelMode
import com.situ.aichat.data.model.ToolCallingMode
import com.situ.aichat.data.model.VisionMode
import com.situ.aichat.data.remote.llm.ApiBalanceResult
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.modelcatalog.ModelCatalogService
import com.situ.aichat.data.remote.llm.modelcatalog.sanitizeCatalogErrorMessage
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.ConfigSaveResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 模型列表拉取的**显式状态机**。旧实现用「list + loading + error」三个平行 flow 表达，
 * 无法区分「从没拉过」与「拉到 0 条」（服务端 200 但 data 为空时用户看到的和没拉过一模一样），
 * 也无法表达「拉到了但用的是兜底表」。
 */
sealed interface ModelCatalogUiState {
    /** 当前可选模型（失败态保留上一次成功的列表，不清空）。 */
    val models: List<APIModelOption> get() = emptyList()
    val isLoading: Boolean get() = false
    /** 需要红字提示的话（失败原因 / 兜底告警）。 */
    val error: String? get() = null

    /** 从没拉过（或已作废）——展开下拉时自动拉一次。 */
    data object Idle : ModelCatalogUiState

    data class Loading(override val models: List<APIModelOption>) : ModelCatalogUiState {
        override val isLoading: Boolean get() = true
    }

    /** 拿到列表（一律来自服务商实时返回——本 App 无任何写死的模型清单）。 */
    data class Loaded(override val models: List<APIModelOption>) : ModelCatalogUiState

    /** 拉取成功但服务商一个模型都没返回——与 [Idle] 明确区分，提示用户手输模型名。 */
    data object Empty : ModelCatalogUiState

    data class Failed(
        val message: String,
        override val models: List<APIModelOption>,
    ) : ModelCatalogUiState {
        override val error: String? get() = message
    }
}

/** 保存反馈（settings-api-5）：成功(新建)清空输入 + 提示；失败弹错误提示且不离屏/不丢 key。 */
sealed interface ApiSaveFeedback {
    data object SavedCreate : ApiSaveFeedback
    data object KeychainFailed : ApiSaveFeedback
    data object DbFailed : ApiSaveFeedback
}

@HiltViewModel
class ApiConfigViewModel @Inject constructor(
    private val repo: ApiConfigRepository,
    private val modelCatalog: ModelCatalogService,
    private val functionRouter: ApiFunctionRouter,
) : ViewModel() {

    val configs: StateFlow<List<ApiConfigEntity>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeConfig: StateFlow<ApiConfigEntity?> =
        repo.observeActive().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 功能→配置 显式分配映射，配置卡「用于：…」承接提示用（settings-api-3）。 */
    val assignments: StateFlow<Map<ApiFunction, String>> =
        functionRouter.assignments.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** UUIDs whose capability detection is currently running (drives the per-card spinner). */
    private val _detecting = MutableStateFlow<Set<String>>(emptySet())
    val detecting: StateFlow<Set<String>> = _detecting.asStateFlow()

    /** UUIDs whose最近一次检测返回「不确定(-1)」（settings-api-6，列表卡显示原因提示）。 */
    private val _undetermined = MutableStateFlow<Set<String>>(emptySet())
    val undetermined: StateFlow<Set<String>> = _undetermined.asStateFlow()

    /** 保存反馈事件（settings-api-5），编辑/新建屏各自观察并弹 snackbar。 */
    private val _feedback = MutableSharedFlow<ApiSaveFeedback>(extraBufferCapacity = 1)
    val feedback = _feedback.asSharedFlow()

    // MARK: - Model catalog (3.3b)

    private val _modelCatalog = MutableStateFlow<ModelCatalogUiState>(ModelCatalogUiState.Idle)
    val modelCatalogState: StateFlow<ModelCatalogUiState> = _modelCatalog.asStateFlow()

    /** 当前在飞的拉取任务——防重入：连点只保留最后一次，旧任务取消（旧实现每次裸 launch，先回来的会关掉转圈、后回来的失败还会把已成功的列表抹掉）。 */
    private var fetchJob: Job? = null

    /**
     * 拉取模型列表。旧实现的三个坑一并修掉：
     * ① 无防重入 → 单 Job + 取消旧任务；
     * ② 失败即 `emptyList()` → **失败不动已成功的列表**，只切错误态；
     * ③ 「拉到 0 条」与「从没拉过」不可分 → 显式 [ModelCatalogUiState.Empty]。
     */
    fun fetchModels(provider: ApiProviderType, baseUrl: String, apiKey: String) {
        // 表单还没填好就别报错：编辑屏冷启时 baseUrl/apiKey 要等 StateFlow 吐行 + 密钥库异步读回才有值，
        // 这个窗口里点开下拉会拿空地址拉取 → 落 Failed(「地址无效」)。而 Failed 不会自愈（状态重建不经
        // onValueChange，clearModels 不触发），用户得手点「重新拉取」才消。保持 Idle 即可，下次展开自然重拉。
        if (baseUrl.isBlank()) return
        val previous = _modelCatalog.value.models
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _modelCatalog.value = ModelCatalogUiState.Loading(previous)
            try {
                val values = ApiConfigValues(
                    providerType = provider,
                    apiKey = apiKey.trim(),
                    baseUrl = baseUrl.trim(),
                    modelName = "",
                    thinkingBudgetLevel = ThinkingBudgetLevel.AUTO,
                    isThinkingModel = false,
                    maxOutputLength = MaxOutputLength.AUTO,
                )
                val models = modelCatalog.fetchModels(values)
                _modelCatalog.value =
                    if (models.isEmpty()) ModelCatalogUiState.Empty else ModelCatalogUiState.Loaded(models)
            } catch (e: CancellationException) {
                throw e // 协程取消不是拉取失败（旧实现裸 catch(Exception) 会误判成错误态）
            } catch (e: Exception) {
                _modelCatalog.value = ModelCatalogUiState.Failed(
                    message = sanitizeCatalogErrorMessage(e.message, apiKey),
                    models = previous,
                )
            }
        }
    }

    /**
     * 作废已拉到的列表——切服务商、**改 Base URL、改 API Key** 都要调（旧实现只在切服务商时调，
     * 改完地址还一直显示上一个端点的模型列表）。回到 Idle 后下次展开会自动重拉一次。
     */
    fun clearModels() {
        fetchJob?.cancel()
        fetchJob = null
        _modelCatalog.value = ModelCatalogUiState.Idle
    }

    // MARK: - Account balance (3.3d)

    private val _balances = MutableStateFlow<Map<String, ApiBalanceResult>>(emptyMap())
    val balances: StateFlow<Map<String, ApiBalanceResult>> = _balances.asStateFlow()

    /** Refresh balances for all saved configs in parallel (skips configs without a stored key). */
    fun refreshBalances() {
        viewModelScope.launch {
            configs.value.forEach { cfg ->
                launch {
                    val result = repo.fetchBalance(cfg) ?: return@launch
                    _balances.update { it + (cfg.uuid to result) }
                }
            }
        }
    }

    /** Refresh the balance for a single config. */
    fun refreshBalance(uuid: String) {
        viewModelScope.launch {
            val cfg = configs.value.firstOrNull { it.uuid == uuid } ?: return@launch
            val result = repo.fetchBalance(cfg) ?: return@launch
            _balances.update { it + (uuid to result) }
        }
    }

    fun save(provider: ApiProviderType, baseUrl: String, model: String, apiKey: String) {
        viewModelScope.launch {
            val outcome = repo.addConfig(
                providerType = provider,
                baseUrl = baseUrl.trim(),
                modelName = model.trim(),
                apiKey = apiKey.trim(),
                makeActive = true,
            )
            when (outcome.result) {
                ConfigSaveResult.SUCCESS -> {
                    _feedback.emit(ApiSaveFeedback.SavedCreate)
                    val newUuid = outcome.uuid ?: return@launch
                    runDetection(newUuid) {
                        repo.runCapabilityDetections(newUuid, catalogVisionFor(model))
                    }
                }
                ConfigSaveResult.KEYCHAIN_FAILED -> _feedback.emit(ApiSaveFeedback.KeychainFailed)
                ConfigSaveResult.DB_FAILED -> _feedback.emit(ApiSaveFeedback.DbFailed)
            }
        }
    }

    /**
     * 该模型在**刚拉回的列表里**是否被服务商官方标注为有视觉能力
     *（OpenRouter `architecture.input_modalities` / Anthropic `capabilities.image_input`——
     * 只有这两家给）。null = 没这条信息，交回名字表 + 探针裁决。
     *
     * 从当前列表现取而不另存一份状态：用户可能手输模型名、也可能拉完列表后又改字，
     * 单独存一份必然要处理各种失效时机；按名字回查天然不会过期。
     */
    private fun catalogVisionFor(modelName: String): Boolean? {
        val target = modelName.trim()
        if (target.isEmpty()) return null
        return _modelCatalog.value.models.firstOrNull { it.id.equals(target, ignoreCase = true) }?.supportsVision
    }

    fun redetect(uuid: String) {
        viewModelScope.launch {
            // 手点「重新检测」也要带上官方元数据——否则这一下就把权威通道整条丢掉，退回只靠探针。
            val model = configs.value.firstOrNull { it.uuid == uuid }?.modelName.orEmpty()
            runDetection(uuid) { repo.redetectCapabilities(uuid, catalogVisionFor(model)) }
        }
    }

    /**
     * Mark a uuid as detecting, run [block], then clear the flag (errors are swallowed; -1 results persist).
     * settings-api-6：block 返回 anyUndetermined → 更新 [undetermined] 集合驱动列表卡的「检测无法判定」提示。
     */
    private suspend fun runDetection(uuid: String, block: suspend () -> Boolean) {
        _detecting.update { it + uuid }
        try {
            val anyUndetermined = block()
            _undetermined.update { if (anyUndetermined) it + uuid else it - uuid }
        } finally {
            _detecting.update { it - uuid }
        }
    }

    /** 编辑屏预填：读出已存明文 key（默认打码显示·点眼睛可见），拉取模型列表因此能拿到真 key。 */
    suspend fun storedApiKey(uuid: String): String = repo.storedApiKey(uuid)

    /** Save edits to an existing config; re-runs detection in the background if inputs changed. */
    fun updateConfig(
        uuid: String,
        provider: ApiProviderType,
        baseUrl: String,
        model: String,
        newApiKey: String?,
        thinkingModelMode: ThinkingModelMode,
        toolCallingMode: ToolCallingMode,
        visionMode: VisionMode,
        audioInputMode: AudioInputMode,
        thinkingBudgetLevel: ThinkingBudgetLevel,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            val outcome = repo.updateConfig(
                uuid = uuid,
                providerType = provider,
                baseUrl = baseUrl,
                modelName = model,
                newApiKey = newApiKey,
                thinkingModelMode = thinkingModelMode,
                toolCallingMode = toolCallingMode,
                visionMode = visionMode,
                audioInputMode = audioInputMode,
                thinkingBudgetLevel = thinkingBudgetLevel,
            )
            when (outcome.result) {
                ConfigSaveResult.SUCCESS -> {
                    // 成功才离屏（保留安卓地道的「保存即返回」，无 UX 倒退）；失败留在屏上弹错误。
                    onSaved()
                    if (outcome.needsDetect) {
                        runDetection(uuid) { repo.runCapabilityDetections(uuid, catalogVisionFor(model)) }
                    }
                }
                ConfigSaveResult.KEYCHAIN_FAILED -> _feedback.emit(ApiSaveFeedback.KeychainFailed)
                ConfigSaveResult.DB_FAILED -> _feedback.emit(ApiSaveFeedback.DbFailed)
            }
        }
    }

    /** 复制配置（settings-api-4）：全字段拷贝 + 新 uuid/apiKeyId + 同 key 值，inactive，不重新检测（对齐 iOS cloneConfiguration）。 */
    fun clone(uuid: String) {
        viewModelScope.launch { repo.cloneConfig(uuid) }
    }

    // MARK: - 扫码导出（13.10b · C7）

    /** 「生成二维码」的负载字符串（非空 = 弹二维码弹窗；含明文密钥）。 */
    private val _exportPayload = MutableStateFlow<String?>(null)
    val exportPayload: StateFlow<String?> = _exportPayload.asStateFlow()

    /** 生成某配置的二维码导出负载（读取明文密钥拼装）；配置不存在则不弹窗。 */
    fun exportQr(uuid: String) {
        viewModelScope.launch { _exportPayload.value = repo.exportConfigPayload(uuid) }
    }

    /** 关闭二维码弹窗。 */
    fun dismissExportQr() {
        _exportPayload.value = null
    }

    fun activate(uuid: String) {
        viewModelScope.launch { repo.setActive(uuid) }
    }

    fun delete(entity: ApiConfigEntity) {
        viewModelScope.launch { repo.delete(entity) }
    }
}
