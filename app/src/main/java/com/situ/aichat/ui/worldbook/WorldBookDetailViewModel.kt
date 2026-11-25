package com.situ.aichat.ui.worldbook

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.worldbook.WorldBookRepository
import com.situ.aichat.data.worldbook.decodeStringList
import com.situ.aichat.world.member.WorldMembershipService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 书详情（WB7a·契约 §12.3）：书头卡（简介/开关/在用角色）+ 条目列表（搜索过滤·条目开关）+ 导出。
 * 全部现读现写经 [WorldBookRepository]——聊天中途改动下一回合即生效（热更新 §12.11）。
 */
@HiltViewModel
class WorldBookDetailViewModel @Inject constructor(
    private val repository: WorldBookRepository,
    private val characterRepo: CharacterRepository,
    private val worldNativeDao: WorldNativeDao,
    private val membershipService: WorldMembershipService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val bookUuid: String = savedStateHandle["bookUuid"] ?: ""

    val book: StateFlow<WorldBookEntity?> = repository.observeBook(bookUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val allEntries: StateFlow<List<WorldBookEntryEntity>> = repository.observeEntriesForBook(bookUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 头卡元信息：条目总数 × 内容总字数（不随搜索过滤变）。 */
    val entryStats: StateFlow<Pair<Int, Int>> = allEntries
        .map { list -> list.size to list.sumOf { it.content.length } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0 to 0)

    /** 搜索过滤后的条目（标题 / 内容 / 主次关键词任一命中）。 */
    val entries: StateFlow<List<WorldBookEntryEntity>> = combine(allEntries, _searchQuery) { list, query ->
        if (query.isBlank()) {
            list
        } else {
            list.filter { e ->
                e.comment.contains(query, ignoreCase = true) ||
                    e.content.contains(query, ignoreCase = true) ||
                    decodeStringList(e.keysJson).any { it.contains(query, ignoreCase = true) } ||
                    decodeStringList(e.secondaryKeysJson).any { it.contains(query, ignoreCase = true) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 「在用角色」行（响应式·按绑定先后）。 */
    val boundCharacters: StateFlow<List<CharacterEntity>> = repository.observeBoundCharacterUuids(bookUuid)
        .map { uuids -> uuids.mapNotNull { characterRepo.get(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 全部角色（书侧绑定 sheet·WB7c）。 */
    val allCharacters: StateFlow<List<CharacterEntity>> = characterRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 原住民出身角色 uuid 集合（常驻世界·不可绑定世界书）。 */
    val nativeCharacterUuids: StateFlow<Set<String>> = worldNativeDao.observeAll()
        .map { list -> list.mapNotNull { it.recruitedCharacterUuid }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** 书侧绑定：原住民拒绝；未确认入世角色拒绝直接绑（走 [switchLeaveAndBindCharacter]）。 */
    fun toggleCharacter(characterUuid: String, bound: Boolean) {
        viewModelScope.launch {
            if (worldNativeDao.getByRecruitedUuid(characterUuid) != null) return@launch
            if (bound && characterRepo.get(characterUuid)?.joinedWorld == true) return@launch
            if (bound) repository.bind(characterUuid, bookUuid) else repository.unbind(characterUuid, bookUuid)
        }
    }

    /** 确认切换：单事务令该角色离开世界并增量挂载本书。 */
    fun switchLeaveAndBindCharacter(characterUuid: String) {
        viewModelScope.launch {
            if (worldNativeDao.getByRecruitedUuid(characterUuid) != null) return@launch
            membershipService.leaveAndBindWorldBook(
                characterUuid = characterUuid,
                bookUuid = bookUuid,
                sole = false,
                nowMs = System.currentTimeMillis(),
            )
        }
    }

    private val _exportResult = MutableStateFlow<Boolean?>(null)
    val exportResult: StateFlow<Boolean?> = _exportResult.asStateFlow()

    fun exportBook(writeText: suspend (String) -> Boolean) {
        viewModelScope.launch {
            val json = repository.exportBookAsJson(bookUuid)
            _exportResult.value = json != null && writeText(json)
        }
    }

    fun consumeExportResult() {
        _exportResult.value = null
    }

    fun setSearch(query: String) {
        _searchQuery.value = query
    }

    fun setBookEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setBookEnabled(bookUuid, enabled) }
    }

    fun setBookGlobal(isGlobal: Boolean) {
        viewModelScope.launch { repository.setBookGlobal(bookUuid, isGlobal) }
    }

    fun updateMeta(name: String, description: String) {
        viewModelScope.launch { repository.updateBookMeta(bookUuid, name.trim(), description.trim()) }
    }

    fun setEntryEnabled(entryUuid: String, enabled: Boolean) {
        viewModelScope.launch { repository.setEntryEnabled(entryUuid, enabled) }
    }

    fun deleteBook(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteBook(bookUuid)
            onDeleted()
        }
    }
}
