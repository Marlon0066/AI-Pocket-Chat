package com.situ.aichat.ui.character

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.memory.ManualEditResult
import com.situ.aichat.prompt.memory.MemoryEditMode
import com.situ.aichat.prompt.memory.MemoryEditText
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.memory.MemorySummaryCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 编辑页状态（图纸 2026-09-01 件③）。 */
data class MemoryEditState(
    val mode: MemoryEditMode = MemoryEditMode.Whole(""),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    /** 与进屏初值不同 = 有未保存改动（驱动保存钮与返回确认）。 */
    val dirty: Boolean = false,
    /** 实时字数（口径 = [MemoryService.cjkLength] codePoint 计数，与上限判定同源）。 */
    val count: Int = 0,
    /** 记忆长度上限（0 或负 = 用户关闭了上限）。 */
    val maxLength: Int = 0,
    /** 非 null = 冲突弹窗开，值 = 库内当前新版文本。 */
    val conflict: String? = null,
    val showDiscardDialog: Boolean = false,
) {
    val canSave: Boolean get() = dirty && !saving && MemoryEditText.canSave(mode)
}

/**
 * 记忆手动编辑页 VM（图纸 2026-09-01「记忆与防污染加固批」件③）。
 *
 * 写库**只经** [MemorySummaryCoordinator.applyManualEdit]（与自动整理同一把 per-角色锁），绝不直接碰 DAO。
 * 载入取一次性快照而非订阅流——编辑期间后台整理写回不该把用户正在打的字顶掉；冲突由 baseline 比对在保存
 * 那一刻兜住（弹窗让用户选覆盖还是重载）。
 */
@HiltViewModel
class MemoryEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val characterRepo: CharacterRepository,
    private val settingsRepo: SettingsRepository,
    private val coordinator: MemorySummaryCoordinator,
) : ViewModel() {

    private val characterUuid: String = savedStateHandle.get<String>(ARG_CHARACTER_UUID).orEmpty()

    private var baseline: String = ""
    private var initialMode: MemoryEditMode = MemoryEditMode.Whole("")

    private val _state = MutableStateFlow(MemoryEditState())
    val state: StateFlow<MemoryEditState> = _state.asStateFlow()

    /** 一次性事件：保存成功 toast（string res id）。 */
    private val _savedToast = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val savedToast: SharedFlow<Int> = _savedToast.asSharedFlow()

    /**
     * 关闭本页信号（保存完成 / 放弃 / 角色已不存在）。
     * 用 StateFlow 而非一次性 SharedFlow：init 里「角色已不存在」的置位发生在 UI 订阅**之前**，
     * replay=0 的 SharedFlow 会把它丢掉 → 页面永远不关。置位后本页即被 pop，不存在重复触发问题。
     */
    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    init {
        viewModelScope.launch {
            val character = characterRepo.get(characterUuid)
            if (character == null) {
                _closed.value = true
                return@launch
            }
            baseline = character.memorySummary
            initialMode = MemoryEditText.toMode(baseline)
            val maxLength = settingsRepo.getAppSettings().memorySummaryMaxLength
            _state.value = MemoryEditState(
                mode = initialMode,
                loaded = true,
                count = MemoryService.cjkLength(MemoryEditText.compose(initialMode)),
                maxLength = maxLength,
            )
        }
    }

    fun updateLongTerm(text: String) {
        val mode = _state.value.mode
        if (mode !is MemoryEditMode.Sections) return
        applyMode(mode.copy(longTermText = text))
    }

    fun updateRecent(text: String) {
        val mode = _state.value.mode
        if (mode !is MemoryEditMode.Sections) return
        applyMode(mode.copy(recentText = text))
    }

    fun updateWhole(text: String) {
        val mode = _state.value.mode
        if (mode !is MemoryEditMode.Whole) return
        applyMode(mode.copy(text = text))
    }

    private fun applyMode(mode: MemoryEditMode) {
        _state.value = _state.value.copy(
            mode = mode,
            dirty = mode != initialMode,
            count = MemoryService.cjkLength(MemoryEditText.compose(mode)),
        )
    }

    /**
     * 保存（[force]=true 由冲突弹窗的「仍然保存」触发）。
     * 包 NonCancellable：离页不撕半份写入（照 `organizeMemoryNow` 先例）。
     */
    fun save(force: Boolean = false) {
        val current = _state.value
        if (current.saving || !MemoryEditText.canSave(current.mode)) return
        _state.value = current.copy(saving = true, conflict = null)
        viewModelScope.launch {
            try {
                val result = withContext(NonCancellable) {
                    coordinator.applyManualEdit(
                        characterUuid = characterUuid,
                        baseline = baseline,
                        newMemory = MemoryEditText.compose(_state.value.mode),
                        force = force,
                    )
                }
                when (result) {
                    ManualEditResult.Saved -> {
                        _savedToast.tryEmit(com.situ.aichat.R.string.memory_edit_saved_toast)
                        _closed.value = true
                    }
                    is ManualEditResult.Conflict -> _state.value = _state.value.copy(conflict = result.current)
                    ManualEditResult.CharacterGone -> _closed.value = true
                }
            } finally {
                _state.value = _state.value.copy(saving = false)
            }
        }
    }

    /** 冲突弹窗「查看新版」：丢弃本地改动、以库内新版重载（弹窗文案已明示会丢改动）。 */
    fun reloadFromConflict() {
        val newest = _state.value.conflict ?: return
        baseline = newest
        initialMode = MemoryEditText.toMode(newest)
        _state.value = _state.value.copy(
            mode = initialMode,
            dirty = false,
            count = MemoryService.cjkLength(MemoryEditText.compose(initialMode)),
            conflict = null,
        )
    }

    /** 返回：有未保存改动先确认（保存中一律忽略返回，防写入中途被撕）。 */
    fun requestClose() {
        val current = _state.value
        if (current.saving) return
        if (current.dirty) _state.value = current.copy(showDiscardDialog = true) else _closed.value = true
    }

    fun dismissDiscardDialog() {
        _state.value = _state.value.copy(showDiscardDialog = false)
    }

    fun confirmDiscard() {
        _state.value = _state.value.copy(showDiscardDialog = false)
        _closed.value = true
    }

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
    }
}
