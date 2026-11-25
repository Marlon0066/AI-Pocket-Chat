package com.situ.aichat.ui.worldbook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.data.worldbook.WorldBookRepository
import com.situ.aichat.data.worldbook.decodeStringList
import com.situ.aichat.data.worldbook.encodeStringList
import com.situ.aichat.data.worldbook.isRegexKeywordForm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 触发方式三态（酒馆同款互斥：绿灯关键词 / 蓝灯常驻 / 链接语义）。 */
enum class WorldBookTriggerMode { KEYWORD, CONSTANT, VECTOR }

/**
 * 条目编辑器（WB7b·契约 §12.4/§12.10）：整条实体作草稿、字段级 copy 更新；
 * 保存经 [WorldBookRepository.saveEntry]（内容变更清嵌入 = 热更新 §12.11）。
 * 向导类别（§12.10）只决定预选触发方式与 placeholder，不落假数据。
 *
 * [draft] 用 Compose 快照态而非 StateFlow：文本框的值必须**同步**回读，
 * 经 Flow 异步回环会在快速输入 / IME 联想时丢字（实测模拟器批量注入整段丢失）。
 */
@HiltViewModel
class WorldBookEntryEditViewModel @Inject constructor(
    private val repository: WorldBookRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val bookUuid: String = savedStateHandle["bookUuid"] ?: ""
    private val entryUuid: String? = savedStateHandle["entryUuid"]

    /** 向导类别（仅新建时可能非空）。 */
    val guideCategory: WorldBookGuideCategory? =
        WorldBookGuideCategory.fromKeyOrNull(savedStateHandle["guide"])

    val isEditing: Boolean = entryUuid != null

    private var original: WorldBookEntryEntity? = null

    /** 草稿（null = 加载中）；只在主线程写。 */
    var draft: WorldBookEntryEntity? by mutableStateOf(null)
        private set

    /** 主 / 次关键词输入框尚未按回车的暂存文本——点右上角「保存」时自动收词入库，防忘按回车丢词。 */
    var pendingPrimaryKeyInput: String by mutableStateOf("")
    var pendingSecondaryKeyInput: String by mutableStateOf("")

    init {
        viewModelScope.launch {
            val loaded = entryUuid?.let { repository.getEntry(it) }
                ?: repository.newEntryDraft(bookUuid).let { fresh ->
                    if (guideCategory?.constant == true) fresh.copy(constant = true) else fresh
                }
            original = loaded
            draft = loaded
        }
    }

    val isDirty: Boolean
        get() = draft != null && (
            draft != original ||
                pendingPrimaryKeyInput.isNotBlank() ||
                pendingSecondaryKeyInput.isNotBlank()
            )

    fun update(transform: (WorldBookEntryEntity) -> WorldBookEntryEntity) {
        draft = draft?.let(transform)
    }

    // MARK: - 触发方式

    fun triggerMode(entry: WorldBookEntryEntity): WorldBookTriggerMode = when {
        entry.constant -> WorldBookTriggerMode.CONSTANT
        entry.vectorized -> WorldBookTriggerMode.VECTOR
        else -> WorldBookTriggerMode.KEYWORD
    }

    fun setTriggerMode(mode: WorldBookTriggerMode) = update {
        when (mode) {
            WorldBookTriggerMode.KEYWORD -> it.copy(constant = false, vectorized = false)
            WorldBookTriggerMode.CONSTANT -> it.copy(constant = true, vectorized = false)
            WorldBookTriggerMode.VECTOR -> it.copy(constant = false, vectorized = true)
        }
    }

    // MARK: - 关键词（主 / 次共用一套编解码）

    fun keys(entry: WorldBookEntryEntity): List<String> = decodeStringList(entry.keysJson)

    fun secondaryKeys(entry: WorldBookEntryEntity): List<String> = decodeStringList(entry.secondaryKeysJson)

    /** 加词：`/regex/flags` 整条保留（防 `/\d{1,3}/i` 量词逗号被拆断）；普通词按中英文逗号、顿号切分去空去重。 */
    fun addKeys(raw: String, secondary: Boolean = false) = update { entry ->
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return@update entry
        val incoming = if (isRegexKeywordForm(trimmed)) {
            listOf(trimmed)
        } else {
            trimmed.split(',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (incoming.isEmpty()) return@update entry
        val current = if (secondary) secondaryKeys(entry) else keys(entry)
        val merged = (current + incoming).distinct()
        if (secondary) entry.copy(secondaryKeysJson = encodeStringList(merged))
        else entry.copy(keysJson = encodeStringList(merged))
    }

    fun removeKey(key: String, secondary: Boolean = false) = update { entry ->
        val current = if (secondary) secondaryKeys(entry) else keys(entry)
        val remaining = current - key
        if (secondary) entry.copy(secondaryKeysJson = encodeStringList(remaining))
        else entry.copy(keysJson = encodeStringList(remaining))
    }

    // MARK: - 保存 / 删除

    fun save(onSaved: () -> Unit) {
        if (pendingPrimaryKeyInput.isNotBlank()) {
            addKeys(pendingPrimaryKeyInput, secondary = false)
            pendingPrimaryKeyInput = ""
        }
        if (pendingSecondaryKeyInput.isNotBlank()) {
            addKeys(pendingSecondaryKeyInput, secondary = true)
            pendingSecondaryKeyInput = ""
        }
        val entry = draft ?: return
        viewModelScope.launch {
            repository.saveEntry(entry)
            original = entry
            onSaved()
        }
    }

    fun deleteEntry(onDeleted: () -> Unit) {
        if (!isEditing) return
        val entry = draft ?: return
        viewModelScope.launch {
            repository.deleteEntry(entry.uuid)
            onDeleted()
        }
    }
}
