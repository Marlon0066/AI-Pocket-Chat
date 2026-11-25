package com.situ.aichat.ui.worldbook

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldBookSummary
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.worldbook.WorldBookRepository
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

/** 待确认的「离开世界并换用世界书」目标。 */
data class PendingWorldBookSwitch(
    val bookUuid: String,
    val bookName: String,
    val sole: Boolean,
)

/**
 * 角色 × 世界观绑定（WB7c·契约 §12.5 + 世界×世界书自由切换）：
 * - 响应式订阅 [CharacterDao.observeByUuid] 与 [WorldNativeDao.observeAll]，同屏与世界开关毫秒级同步；
 * - 仅原住民出身（[nativeOrigin]）不可绑定；普通角色即便当前在世界里也可打开选书弹层，选书时弹确认框，
 *   确认后经 [WorldMembershipService.leaveAndBindWorldBook] 单事务离开世界并挂载世界书。
 */
@HiltViewModel
class WorldBookBindingViewModel @Inject constructor(
    private val repository: WorldBookRepository,
    private val characterDao: CharacterDao,
    private val worldNativeDao: WorldNativeDao,
    private val membershipService: WorldMembershipService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val characterUuid: String = savedStateHandle["characterUuid"] ?: ""

    private val _joinedWorld = MutableStateFlow(false)
    val joinedWorld: StateFlow<Boolean> = _joinedWorld.asStateFlow()

    private val _nativeOrigin = MutableStateFlow(false)
    val nativeOrigin: StateFlow<Boolean> = _nativeOrigin.asStateFlow()

    init {
        viewModelScope.launch {
            characterDao.observeByUuid(characterUuid).collect { ch ->
                _joinedWorld.value = ch?.joinedWorld == true
            }
        }
        viewModelScope.launch {
            worldNativeDao.observeAll().collect { list ->
                _nativeOrigin.value = list.any { it.recruitedCharacterUuid == characterUuid }
            }
        }
    }

    /** 兼容显式刷新入口（进段重组兜底）。 */
    fun refreshJoinedWorld() {
        viewModelScope.launch {
            _joinedWorld.value = characterDao.getByUuid(characterUuid)?.joinedWorld == true
            _nativeOrigin.value = worldNativeDao.getByRecruitedUuid(characterUuid) != null
        }
    }

    /** 直读库当前 joinedWorld（防御纵深·回写 StateFlow）。 */
    suspend fun isJoinedWorldNow(): Boolean {
        val joined = characterDao.getByUuid(characterUuid)?.joinedWorld == true
        _joinedWorld.value = joined
        return joined
    }

    /** 直读库当前是否原住民出身。 */
    suspend fun isNativeOriginNow(): Boolean {
        val native = worldNativeDao.getByRecruitedUuid(characterUuid) != null
        _nativeOrigin.value = native
        return native
    }

    /** 该角色绑定的书（按绑定先后·带条目数）。 */
    val boundBooks: StateFlow<List<WorldBookSummary>> =
        repository.observeBoundBookUuidsForCharacter(characterUuid)
            .combine(repository.observeBookSummaries()) { uuids, all ->
                uuids.mapNotNull { uuid -> all.firstOrNull { it.book.uuid == uuid } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 可选列表 = 非全局书（全局书自动生效不参与选择）。 */
    val selectableBooks: StateFlow<List<WorldBookSummary>> = repository.observeBookSummaries()
        .map { list -> list.filter { !it.book.isGlobal } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 启用中的全局书（sheet 里只作提示行）。 */
    val globalBooks: StateFlow<List<WorldBookEntity>> = repository.observeAllBooks()
        .map { list -> list.filter { it.isGlobal && it.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 未入世时的常规单选（或摘除 `bookUuid == null`，摘除即使在世界里也允许清理残留脏绑定）。 */
    fun selectSole(bookUuid: String?) {
        viewModelScope.launch {
            if (isNativeOriginNow()) return@launch
            if (bookUuid != null && isJoinedWorldNow()) return@launch
            repository.setSoleBinding(characterUuid, bookUuid)
        }
    }

    /** 未入世时的常规多选切换（取消勾选 `!bound` 即使在世界里也允许清理残留脏绑定）。 */
    fun toggle(bookUuid: String, bound: Boolean) {
        viewModelScope.launch {
            if (isNativeOriginNow()) return@launch
            if (bound && isJoinedWorldNow()) return@launch
            if (bound) repository.bind(characterUuid, bookUuid) else repository.unbind(characterUuid, bookUuid)
        }
    }

    /** 用户在切换弹窗点确认后：单事务离开世界并挂载目标世界书。 */
    fun switchLeaveAndBind(bookUuid: String, sole: Boolean) {
        viewModelScope.launch {
            if (isNativeOriginNow()) return@launch
            membershipService.leaveAndBindWorldBook(
                characterUuid = characterUuid,
                bookUuid = bookUuid,
                sole = sole,
                nowMs = System.currentTimeMillis(),
            )
        }
    }
}
