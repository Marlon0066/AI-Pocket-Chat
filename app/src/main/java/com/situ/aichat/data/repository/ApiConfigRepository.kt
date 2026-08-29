package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.ApiConfigDao
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.local.entity.audioInputMode
import com.situ.aichat.data.local.entity.effectiveIsThinkingModel
import com.situ.aichat.data.local.entity.effectiveAudioInputEnabled
import com.situ.aichat.data.local.entity.effectiveToolCallingEnabled
import com.situ.aichat.data.local.entity.effectiveVisionEnabled
import com.situ.aichat.data.local.entity.prefilledFromKnownCapabilities
import com.situ.aichat.data.local.entity.resettingCapabilityDetectionResults
import com.situ.aichat.data.local.entity.thinkingModelMode
import com.situ.aichat.data.local.entity.toolCallingMode
import com.situ.aichat.data.local.entity.visionMode
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ThinkingBudgetSupport
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AudioInputMode
import com.situ.aichat.data.model.MaxOutputLength
import com.situ.aichat.data.model.ThinkingBudgetLevel
import com.situ.aichat.data.model.ThinkingModelMode
import com.situ.aichat.data.model.ToolCallingMode
import com.situ.aichat.data.model.VisionMode
import com.situ.aichat.data.remote.llm.ApiBalanceResult
import com.situ.aichat.data.remote.llm.ApiBalanceService
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.CapabilityDetector
import com.situ.aichat.security.ApiKeyStore
import com.situ.aichat.share.ApiConfigShareCodec
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 13.2 / settings-api-1：出于 API Key 安全，Base URL 仅接受 https://（对齐 iOS hasValidURLScheme，
 * 拒绝任何 http://，含本地代理）。纯函数，便于单测。
 */
internal fun isHttpsBaseUrl(raw: String): Boolean =
    raw.trim().lowercase().startsWith("https://")

/** 保存结果（settings-api-5）：用于把 Keychain/DB 失败上抛给 UI，而非静默吞掉丢密钥。 */
enum class ConfigSaveResult { SUCCESS, KEYCHAIN_FAILED, DB_FAILED }

/** 新建配置结果：成功时带回 uuid（供后续能力检测）。 */
data class AddConfigOutcome(val uuid: String?, val result: ConfigSaveResult)

/** 编辑配置结果：needsDetect=输入变化需重跑能力检测。 */
data class UpdateConfigOutcome(val result: ConfigSaveResult, val needsDetect: Boolean)

@Singleton
class ApiConfigRepository @Inject constructor(
    private val dao: ApiConfigDao,
    private val keyStore: ApiKeyStore,
    private val capabilityDetector: CapabilityDetector,
    private val balanceService: ApiBalanceService,
    private val functionRouter: ApiFunctionRouter,
) {
    fun observeAll(): Flow<List<ApiConfigEntity>> = dao.observeAll()
    fun observeActive(): Flow<ApiConfigEntity?> = dao.observeActive()

    suspend fun hasAnyConfig(): Boolean = dao.count() > 0
    suspend fun getActive(): ApiConfigEntity? = dao.getActive()

    /**
     * Create a config, store its key in the encrypted store, and (optionally) make it active.
     * settings-api-5：Keychain/DB 写失败时上抛结果并回滚（DB 失败删已写入的 key），不静默吞。
     */
    suspend fun addConfig(
        providerType: ApiProviderType,
        baseUrl: String,
        modelName: String,
        apiKey: String,
        makeActive: Boolean = true,
    ): AddConfigOutcome {
        val uuid = UUID.randomUUID().toString()
        val apiKeyId = UUID.randomUUID().toString()
        // 非空 key 写失败 = 致命（对齐 iOS create 分支 keychain-fail 中止）；空 key 无需写。
        if (apiKey.isNotEmpty() && !keyStore.put(apiKeyId, apiKey)) {
            return AddConfigOutcome(null, ConfigSaveResult.KEYCHAIN_FAILED)
        }
        val entity = ApiConfigEntity(
            uuid = uuid,
            providerName = providerType.displayName,
            providerTypeRaw = providerType.raw,
            apiKeyId = apiKeyId,
            baseURL = baseUrl,
            modelName = modelName,
            isActive = false,
            creationDate = System.currentTimeMillis(),
            detectedToolProtocolFamilyRaw = providerType.toolProtocolFamily.raw,
        )
        runCatching { dao.upsert(entity) }.getOrElse {
            keyStore.delete(apiKeyId) // 回滚已写入的 key（对齐 iOS create-mode 回滚）
            return AddConfigOutcome(null, ConfigSaveResult.DB_FAILED)
        }
        if (makeActive) setActive(uuid)
        return AddConfigOutcome(uuid, ConfigSaveResult.SUCCESS)
    }

    suspend fun setActive(uuid: String) {
        dao.clearActive()
        dao.setActiveFlag(uuid)
    }

    /**
     * Edit an existing config (mirrors iOS APIConfigurationView.save edit branch). Persists the
     * provider/url/model/key + capability modes + (normalized) thinking budget. If a capability
     * input (provider/baseURL/model/key) changed, detection is reset and re-run for auto modes.
     */
    suspend fun updateConfig(
        uuid: String,
        providerType: ApiProviderType,
        baseUrl: String,
        modelName: String,
        newApiKey: String?,
        thinkingModelMode: ThinkingModelMode,
        toolCallingMode: ToolCallingMode,
        visionMode: VisionMode,
        audioInputMode: AudioInputMode,
        thinkingBudgetLevel: ThinkingBudgetLevel,
    ): UpdateConfigOutcome {
        // 配置不存在（不应发生于编辑路径）：保持旧的静默无操作语义，按成功无检测处理。
        val existing = dao.getByUuid(uuid) ?: return UpdateConfigOutcome(ConfigSaveResult.SUCCESS, false)
        val trimmedUrl = baseUrl.trim()
        val trimmedModel = modelName.trim()
        val keyChanged = !newApiKey.isNullOrBlank()
        val inputChanged = existing.providerTypeRaw != providerType.raw ||
            existing.baseURL != trimmedUrl ||
            existing.modelName != trimmedModel ||
            keyChanged
        // settings-api-5：key 写失败 = 中止保存（对齐 iOS edit 分支 setApiKey 失败 return），不继续写 DB。
        if (keyChanged && !keyStore.put(existing.apiKeyId, newApiKey.trim())) {
            return UpdateConfigOutcome(ConfigSaveResult.KEYCHAIN_FAILED, false)
        }

        // Normalize the thinking budget to what this provider/model actually supports (iOS persistedThinkingBudgetLevel).
        val support = ThinkingBudgetSupport.resolve(providerType, trimmedUrl, trimmedModel)
        val effectiveThinking = when (thinkingModelMode) {
            ThinkingModelMode.THINKING -> true
            ThinkingModelMode.STANDARD -> false
            ThinkingModelMode.AUTO -> existing.detectedThinkingModelType == 1
        }
        val persistedLevel = if (effectiveThinking && support.showsControl) {
            support.normalized(thinkingBudgetLevel)
        } else {
            ThinkingBudgetLevel.AUTO
        }

        var updated = existing.copy(
            providerName = providerType.displayName,
            providerTypeRaw = providerType.raw,
            baseURL = trimmedUrl,
            modelName = trimmedModel,
            thinkingModelModeRaw = thinkingModelMode.raw,
            toolCallingModeRaw = toolCallingMode.raw,
            visionModeRaw = visionMode.raw,
            audioInputModeRaw = audioInputMode.raw,
            thinkingBudgetLevelRaw = persistedLevel.raw,
            detectedToolProtocolFamilyRaw = providerType.toolProtocolFamily.raw,
        )
        if (inputChanged) updated = updated.resettingCapabilityDetectionResults()
        runCatching { dao.update(updated) }.getOrElse {
            return UpdateConfigOutcome(ConfigSaveResult.DB_FAILED, false)
        }
        return UpdateConfigOutcome(ConfigSaveResult.SUCCESS, inputChanged)
    }

    suspend fun updateApiKey(uuid: String, apiKey: String) {
        val entity = dao.getByUuid(uuid) ?: return
        keyStore.put(entity.apiKeyId, apiKey)
    }

    /** 编辑屏预填：读出某配置已存的明文 key（配置不存在 / 未存过 key → 空串）。 */
    suspend fun storedApiKey(uuid: String): String {
        val entity = dao.getByUuid(uuid) ?: return ""
        return keyStore.get(entity.apiKeyId).orEmpty()
    }

    suspend fun delete(entity: ApiConfigEntity) {
        keyStore.delete(entity.apiKeyId)
        functionRouter.clearAssignmentsForConfig(entity.uuid)
        dao.delete(entity)
    }

    /**
     * 13.10b · C7：把一份已保存配置导出为二维码负载字符串（provider/baseURL/model + 从加密库读出的明文 key），
     * 供「生成二维码」让另一台设备扫码导入。配置不存在 → null。
     * ⚠️ 负载含明文密钥，调用 UI 须警示「仅给信任设备扫、勿截图公开」。
     */
    suspend fun exportConfigPayload(uuid: String): String? {
        val entity = dao.getByUuid(uuid) ?: return null
        return ApiConfigShareCodec.encode(
            provider = ApiProviderType.fromRaw(entity.providerTypeRaw),
            baseUrl = entity.baseURL,
            model = entity.modelName,
            key = keyStore.get(entity.apiKeyId).orEmpty(),
        )
    }

    /**
     * 复制配置（settings-api-4，1:1 iOS APIConfigurationService.cloneConfiguration）：全字段拷贝
     * （含能力模式 + 全部检测结果），新 uuid/apiKeyId、name 加 " (副本)" 后缀、isActive=false、creationDate=now，
     * 源 key 值复制到新 apiKeyId 下（独立密钥副本）。**不重新检测**（iOS clone 保留检测结果且保持非激活）。
     */
    suspend fun cloneConfig(uuid: String): String? {
        val source = dao.getByUuid(uuid) ?: return null
        val newUuid = UUID.randomUUID().toString()
        val newApiKeyId = UUID.randomUUID().toString()
        keyStore.put(newApiKeyId, keyStore.get(source.apiKeyId).orEmpty())
        dao.upsert(
            source.copy(
                uuid = newUuid,
                apiKeyId = newApiKeyId,
                providerName = source.providerName + " (副本)",
                isActive = false,
                creationDate = System.currentTimeMillis(),
            ),
        )
        return newUuid
    }

    // MARK: - Capability detection (P3.1) — mirrors iOS prefillFromKnownCapabilities + runCapabilityDetections

    /**
     * Prefill thinking/vision/audio from the static known-model table (auto + undetected only).
     *
     * [catalogVision] = 拉取模型列表时服务商**官方给出的**视觉能力（OpenRouter `architecture.input_modalities`
     * / Anthropic `capabilities.image_input.supported`——各家 models 接口里只有这两家给）。它比名字表可靠，
     * 故**优先于名字表**；null = 该服务商没给这项信息，回落名字表。
     */
    suspend fun prefillFromKnownCapabilities(uuid: String, catalogVision: Boolean? = null): ApiConfigEntity? {
        val entity = dao.getByUuid(uuid) ?: return null
        var prefilled = entity.prefilledFromKnownCapabilities()
        if (catalogVision != null && prefilled.visionMode == VisionMode.AUTO) {
            // 官方元数据是权威：名字表若已填过（可能填错，如把 `*-vision-exp` 当成基础款）也照样覆盖。
            prefilled = prefilled.copy(detectedVisionSupport = if (catalogVision) 1 else 0)
        }
        if (prefilled != entity) dao.update(prefilled)
        return prefilled
    }

    /**
     * Run runtime capability probes for every capability still in auto mode, writing each result
     * back to the config. Prefills from the known table first to skip unnecessary API calls.
     * Probes run sequentially (matches iOS), so the thinking result is available when deciding
     * whether to probe thinking-model tool support. A `-1` (undetermined) never overwrites a
     * prefilled value.
     *
     * settings-api-6：返回 anyUndetermined = 最后一个执行的 auto 探针（thinking/vision/audio 中，**不含 tool**）
     * 是否返回 -1（对齐 iOS detectionHint「最近探针胜出、确定结果清除」语义）。供列表卡显示「检测无法判定」提示。
     */
    suspend fun runCapabilityDetections(uuid: String, catalogVision: Boolean? = null): Boolean {
        val entity = prefillFromKnownCapabilities(uuid, catalogVision) ?: return false
        var thinkingProbe: Int? = null
        var visionProbe: Int? = null
        var audioProbe: Int? = null
        val key = keyStore.get(entity.apiKeyId).orEmpty()
        val probe = ApiConfigValues(
            providerType = ApiProviderType.fromRaw(entity.providerTypeRaw),
            apiKey = key,
            baseUrl = entity.baseURL,
            modelName = entity.modelName,
            thinkingBudgetLevel = ThinkingBudgetLevel.AUTO,
            isThinkingModel = false,
            maxOutputLength = MaxOutputLength.AUTO,
        )

        var thinkingType = entity.detectedThinkingModelType
        if (entity.thinkingModelMode == ThinkingModelMode.AUTO) {
            val result = capabilityDetector.detectThinkingModelType(probe)
            thinkingProbe = result
            if (result != -1 || entity.detectedThinkingModelType == -1) {
                dao.updateThinkingDetection(uuid, result)
                thinkingType = result
            }
        }

        if (entity.toolCallingMode == ToolCallingMode.AUTO) {
            val modelLower = entity.modelName.lowercase()
            val shouldProbeThinkingTools =
                entity.copy(detectedThinkingModelType = thinkingType).effectiveIsThinkingModel() ||
                    modelLower.contains("reasoner") || modelLower.contains("thinking")
            val result = capabilityDetector.detectToolCallingSupport(probe, shouldProbeThinkingTools)
            dao.updateToolDetection(
                uuid = uuid,
                levelRaw = result.level.raw,
                familyRaw = result.protocolFamily.raw,
                streamingRaw = result.streamingState.raw,
                thinkingRaw = result.thinkingState.raw,
                summary = result.summary,
                checkedAt = result.checkedAt,
            )
        }

        // 官方元数据在场时**不跑视觉探针**：它比探针可靠（探针把任何 400 都读成「不支持」，而中转站
        // 为参数不认 / 限流返 400 很常见），跑了还会反过来覆盖权威值——这正是契约 A8 说的「免跑探针」。
        if (entity.visionMode == VisionMode.AUTO && catalogVision == null) {
            val result = capabilityDetector.detectVisionSupport(probe)
            visionProbe = result
            if (result != -1 || entity.detectedVisionSupport == -1) {
                dao.updateVisionDetection(uuid, result)
            }
        }

        if (entity.audioInputMode == AudioInputMode.AUTO) {
            val result = capabilityDetector.detectAudioInputSupport(probe)
            audioProbe = result
            if (result != -1 || entity.detectedAudioInputSupport == -1) {
                dao.updateAudioDetection(uuid, result)
            }
        }
        // 最后执行的 auto 探针（audio→vision→thinking 优先级）== -1 → 不确定（tool 不计入，对齐 iOS）。
        return (audioProbe ?: visionProbe ?: thinkingProbe) == -1
    }

    /** Clear all detection results then re-run detection (the "重新检测" action). 返回 anyUndetermined（settings-api-6）。 */
    suspend fun redetectCapabilities(uuid: String, catalogVision: Boolean? = null): Boolean {
        dao.resetDetection(uuid)
        return runCapabilityDetections(uuid, catalogVision)
    }

    /** Query the account balance for a config (resolves its key); null if no key stored (skip). */
    suspend fun fetchBalance(config: ApiConfigEntity): ApiBalanceResult? {
        val key = keyStore.get(config.apiKeyId).orEmpty()
        if (key.isEmpty()) return null
        return balanceService.fetchBalance(
            providerType = ApiProviderType.fromRaw(config.providerTypeRaw),
            baseUrl = config.baseURL,
            apiKey = key,
        )
    }

    /** Resolve the active config + its secret key into a request-ready snapshot. */
    suspend fun resolveActiveConfigValues(): ApiConfigValues? {
        val entity = dao.getActive() ?: return null
        val key = keyStore.get(entity.apiKeyId).orEmpty()
        return entity.toConfigValues(key)
    }

    /**
     * Resolve the config for a specific app function (mirrors iOS APIFunctionRouter.configuration):
     * the function's assigned config if it still exists, else the active default. A stale assignment
     * (config deleted) is cleared and falls back to active.
     */
    suspend fun resolveConfigValues(function: ApiFunction): ApiConfigValues? {
        val assignedUuid = functionRouter.assignedId(function)
        val entity = if (assignedUuid != null) {
            dao.getByUuid(assignedUuid) ?: run {
                functionRouter.clearAssignmentsForConfig(assignedUuid)
                dao.getActive()
            }
        } else {
            dao.getActive()
        } ?: return null
        val key = keyStore.get(entity.apiKeyId).orEmpty()
        return entity.toConfigValues(key)
    }

    private fun ApiConfigEntity.toConfigValues(apiKey: String): ApiConfigValues =
        ApiConfigValues(
            providerType = ApiProviderType.fromRaw(providerTypeRaw),
            apiKey = apiKey,
            baseUrl = baseURL,
            modelName = modelName,
            thinkingBudgetLevel = ThinkingBudgetLevel.fromRaw(thinkingBudgetLevelRaw),
            isThinkingModel = effectiveIsThinkingModel(),
            maxOutputLength = MaxOutputLength.fromRaw(maxOutputLengthRaw),
            toolCallingEnabled = effectiveToolCallingEnabled(),
            audioInputEnabled = effectiveAudioInputEnabled(),
            visionEnabled = effectiveVisionEnabled(),
        )
}
