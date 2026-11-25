package com.situ.aichat.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import com.situ.aichat.data.backup.BackupByteSource
import com.situ.aichat.data.backup.BackupPreview
import com.situ.aichat.data.backup.BackupProgress
import com.situ.aichat.data.backup.BackupService
import com.situ.aichat.data.backup.ImportResult
import com.situ.aichat.data.backup.ImportStrategy
import com.situ.aichat.data.model.AutoBackupConfig
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.foreground.LlmGenerationForegroundController
import com.situ.aichat.work.AutoBackupWorker
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.EmbeddingBackfillWorker
import com.situ.aichat.work.NotificationRescheduleWorker
import com.situ.aichat.work.ReliabilityPromptController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.time.Duration
import javax.inject.Inject

/**
 * Drives export/import on [viewModelScope] (survives recomposition). The screen supplies the SAF I/O
 * lambdas (it owns the Context + the picked Uri); this VM keeps the DB/JSON work in a stable scope.
 *
 * 13.6b-2b：导入改成「先预览冲突 → 选策略 → 确认导入」两段式（1:1 iOS）。新 zip 备份走预览段（[preview] 非 null）；
 * 旧 `.json`（无法预览）→ 直接覆盖式导入。
 *
 * 15.2-P1·P1-7：导出/导入全程持 [LlmGenerationForegroundController]（首个非 LLM 消费者，acquire/release 进
 * try/finally——切走/锁屏不被 HyperOS 杀进程）+ 确定性进度 [progress]。**有意分叉（登记）**：返回键退出备份页 →
 * viewModelScope 取消导出并删目标文件（iOS Task.detached 退视图仍续跑，CharacterBackupView.swift:266）——安卓写的
 * 是用户选定的目的地而非 iOS 的 tmp+分享流，「取消即清理」语义自洽；FGS 只防「切走/锁屏」不防「主动退页」。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupService: BackupService,
    private val backgroundScheduler: BackgroundScheduler,
    private val settingsRepo: SettingsRepository,
    private val reliabilityPromptController: ReliabilityPromptController,
    private val llmForeground: LlmGenerationForegroundController,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 非 null = 导出/导入正在跑的确定性进度（P1-7；间隙段为 null → UI 退不确定转圈兜底）。 */
    private val _progress = MutableStateFlow<BackupProgress?>(null)
    val progress: StateFlow<BackupProgress?> = _progress.asStateFlow()

    /** 定时自动备份配置（13.6c；设备本地）。 */
    val autoBackupConfig: StateFlow<AutoBackupConfig> = settingsRepo.autoBackupConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoBackupConfig())

    /** B-7：手动导出是否包含图片/语音/头像（持久化偏好，默认开）。 */
    val includeMedia: StateFlow<Boolean> = settingsRepo.backupIncludeMedia
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setIncludeMedia(include: Boolean) {
        viewModelScope.launch { settingsRepo.setBackupIncludeMedia(include) }
    }

    private val _importResult = MutableStateFlow<ImportResult?>(null)
    val importResult: StateFlow<ImportResult?> = _importResult.asStateFlow()

    /** null = no export attempt yet; true/false = last export succeeded/failed. */
    private val _exportOk = MutableStateFlow<Boolean?>(null)
    val exportOk: StateFlow<Boolean?> = _exportOk.asStateFlow()

    /** 选中的备份文件读取失败（SAF 流打不开/读断）。复核修：随读字节一起提升进 VM busy 单源。 */
    private val _readFailed = MutableStateFlow(false)
    val readFailed: StateFlow<Boolean> = _readFailed.asStateFlow()

    /** 非 null = 正在冲突预览段（已解析、待用户选策略并确认导入）。 */
    private val _preview = MutableStateFlow<BackupPreview?>(null)
    val preview: StateFlow<BackupPreview?> = _preview.asStateFlow()

    /** 逐角色策略（uuid→策略）。冲突角色默认 [ImportStrategy.DUPLICATE]（1:1 iOS）；无冲突角色不在 map（=新导入）。 */
    private val _strategies = MutableStateFlow<Map<String, ImportStrategy>>(emptyMap())
    val strategies: StateFlow<Map<String, ImportStrategy>> = _strategies.asStateFlow()

    // 预览段持有的字节源（确认导入时重开一条流复用）。非 StateFlow（不展示，仅跨预览→确认存活在 VM 里）。
    // 卷 A：这里只留「怎么打开」的引用（Uri），不再驻留整包字节——大备份的内存就是这么省下来的。
    private var pendingSource: BackupByteSource? = null

    /**
     * 导出全量备份（P1-7 原子化）：cache 临时文件 → 整文件拷贝到 SAF 目标，失败/取消删目标（[BackupService.exportAtomic]）。
     * [openStream]/[deleteTarget] 由屏幕提供（它持 Context + 选中的 Uri）。全程 FGS 保活 + 确定性进度。
     */
    fun export(includeMedia: Boolean, openStream: suspend () -> OutputStream?, deleteTarget: () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            clearEvents()
            llmForeground.acquire()
            try {
                _exportOk.value = backupService.exportAtomic(
                    includeMedia = includeMedia,
                    onProgress = { _progress.value = it }, // 只读回调约定：仅赋值、不抛
                    openOut = openStream,
                    deleteTarget = deleteTarget,
                )
            } finally {
                // 取消（退页）也必达：FGS 引用计数必须归还，进度/忙态复位。
                llmForeground.release()
                _progress.value = null
                _busy.value = false
            }
        }
    }

    /**
     * 选完文件后调用：解析 + 构建冲突预览。zip → 进预览段（[preview] 非 null，等用户选策略）；非 zip
     * （旧 .json / 损坏）→ 无冲突预览，直接覆盖式导入。导入同包 FGS（P1-7）：解析/直接导入段全程保活。
     *
     * 复核修（MED）：解析下沉进 busy 守卫内——否则「最近备份」大媒体份的秒级窗口里 busy 恒 false：零反馈诱导
     * 二次点击，且迟到的第二份解析完后会在用户审阅预览时静默覆写 preview/strategies（批1「in-flight 守卫提升
     * 单源」同款思路）。预览停留期（[preview] 非 null）直接拒绝。
     *
     * 卷 A：入参从「读整包字节的 lambda」换成 [BackupByteSource]（可重开的流）——`readFailed` 语义不变，
     * 判据从「读回来是 null」改为「这个源根本打不开」（文件被移走 / 授权失效）。
     */
    fun startImport(source: BackupByteSource) {
        if (_busy.value || _preview.value != null) return
        viewModelScope.launch {
            // Main.immediate：busy 在首个挂起点前同步置位 → 双击守卫无缝隙。
            _busy.value = true
            clearEvents()
            llmForeground.acquire()
            try {
                if (!backupService.canOpen(source)) {
                    _readFailed.value = true
                    return@launch
                }
                val pv = backupService.previewArchive(source)
                if (pv == null) {
                    // 旧 .json / 无法预览 → 直接导入（覆盖式，importArchive 内部回退 .json 解析）。
                    runImport(source, emptyMap())
                } else {
                    pendingSource = source
                    // 冲突角色默认「创建副本」（1:1 iOS）；无冲突角色不入 map（=新导入）。
                    _strategies.value = pv.characters
                        .filter { it.hasConflict }
                        .associate { it.uuid to ImportStrategy.DUPLICATE }
                    _preview.value = pv
                }
            } finally {
                llmForeground.release()
                _progress.value = null
                _busy.value = false
            }
        }
    }

    fun setStrategy(uuid: String, strategy: ImportStrategy) {
        _strategies.value = _strategies.value.toMutableMap().apply { this[uuid] = strategy }
    }

    /** 确认导入：用当前逐角色策略执行（结果回填 [importResult]，预览屏切到结果区）。全程 FGS 保活（P1-7）。 */
    fun confirmImport() {
        if (_busy.value) return
        val source = pendingSource ?: return
        viewModelScope.launch {
            _busy.value = true
            llmForeground.acquire()
            try {
                runImport(source, _strategies.value)
            } finally {
                llmForeground.release()
                _progress.value = null
                _busy.value = false
            }
        }
    }

    /** 关闭预览（取消，或导入完成后用户点「完成」）。 */
    fun dismissPreview() {
        _preview.value = null
        _strategies.value = emptyMap()
        pendingSource = null
        _importResult.value = null
    }

    private suspend fun runImport(source: BackupByteSource, strategies: Map<String, ImportStrategy>) {
        val result = backupService.importArchive(source, strategies) { _progress.value = it }
        // 导入【旧版备份】（无 embedding）后立即排一次后台回填，让历史记忆即刻可被语义检索（12.3）；新版备份已带
        // embedding 无需此步，worker 会 EXISTS 秒探测到无缺失后秒退。纯本地 ONNX → 不需联网。
        if (result is ImportResult.Success) {
            backgroundScheduler.scheduleOneShot(
                uniqueName = EmbeddingBackfillWorker.UNIQUE_ENSURE,
                workerClass = EmbeddingBackfillWorker::class.java,
                requireNetwork = false,
                existingPolicy = ExistingWorkPolicy.KEEP,
            )
            // W14：换机恢复后世界在途到达闹钟不跨设备——导入成功即重排一次（worker 全家重排幂等·各扫真理源无副作用；
            // 顺带把日历/宠物/约定/故事的恢复重排也白拿）。天然在导入事务之外（此处已是 importArchive 返回之后·图纸 §6 红线）。
            backgroundScheduler.scheduleOneShot(
                uniqueName = NotificationRescheduleWorker.UNIQUE_ONESHOT,
                workerClass = NotificationRescheduleWorker::class.java,
                requireNetwork = false,
                existingPolicy = ExistingWorkPolicy.REPLACE,
            )
        }
        _importResult.value = result
    }

    // ── 定时自动备份（13.6c） ──

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setAutoBackupEnabled(enabled)
            syncAutoBackupSchedule()
            // 13.7a：自动备份靠后台周期 worker 执行，首次开启时主动引导 HyperOS 白名单（一次性）。
            if (enabled) reliabilityPromptController.onBackgroundFeatureEnabled()
        }
    }

    fun setAutoBackupFolder(treeUri: String) {
        viewModelScope.launch {
            settingsRepo.setAutoBackupTreeUri(treeUri)
            syncAutoBackupSchedule()
        }
    }

    /**
     * 按当前配置排程或取消周期 worker：仅当「已启用 且 已选目录」才排（约每日，无需联网）；否则取消，避免空跑唤醒。
     * WorkManager 周期任务跨重启/重启机/升级自动存活（KEEP）→ 无需冷启动重排。
     */
    private suspend fun syncAutoBackupSchedule() {
        val cfg = settingsRepo.getAutoBackupConfig()
        if (cfg.enabled && cfg.treeUri.isNotBlank()) {
            backgroundScheduler.schedulePeriodic(
                uniqueName = AutoBackupWorker.UNIQUE_PERIODIC,
                workerClass = AutoBackupWorker::class.java,
                repeatInterval = Duration.ofHours(24),
                requireNetwork = false,
            )
        } else {
            backgroundScheduler.cancel(AutoBackupWorker.UNIQUE_PERIODIC)
        }
    }

    fun clearEvents() {
        _importResult.value = null
        _exportOk.value = null
        _readFailed.value = false
    }
}
