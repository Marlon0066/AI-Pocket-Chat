package com.situ.aichat.ui.moments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.ourdays.OurDayKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * 按当天窗口二分（图纸 §3.3）：`[startMillis, endMillis)` 内 = 「这一天发的」，其余 = 「更早发的」。
 * **保序**——排序由 SQL 的 `ORDER BY timestamp DESC` 负责，本函数不重排（E7 / E17）。
 */
internal fun partitionDayMoments(
    posts: List<MomentPostWithRelations>,
    startMillis: Long,
    endMillis: Long,
): Pair<List<MomentPostWithRelations>, List<MomentPostWithRelations>> =
    posts.partition { it.post.timestamp in startMillis until endMillis }

/** 那一天的朋友圈 UI 状态（图纸 §3.4）：[loaded] = 首帧已从 DB 返回（未加载不画空态·J6）。 */
data class DayMomentsUiState(
    val loaded: Boolean = false,
    val date: LocalDate? = null,
    val postedThatDay: List<MomentPostWithRelations> = emptyList(),
    val earlier: List<MomentPostWithRelations> = emptyList(),
)

/**
 * 「我们的日子」日页「看动态 ›」的落点（图纸 2026-09-03 §3.5）：路由参数 `characterUuid` + `dayKey`
 * → 当天毫秒半开窗口 → 那一天涉及的动态，二分成「这一天发的」/「更早发的 · 这一天有来往」两组。
 *
 * 本页**无自有写入路径**（唯一写 = 既有 [toggleLike]），无草稿状态，故进程死亡后由 `SavedStateHandle`
 * 带回路由参数重查即恢复。非法日键 / 空角色 uuid 走空态而非崩溃（J5）——`OurDayKey.dayBounds` 对非法键
 * 抛异常，故必须**先 parse 再取窗口**。
 */
@HiltViewModel
class DayMomentsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val momentRepo: MomentRepository,
    characterRepo: CharacterRepository,
    userProfileDao: UserProfileDao,
) : ViewModel() {

    private val characterUuid: String = savedStateHandle.get<String>(ARG_CHARACTER_UUID).orEmpty()
    private val dayKey: String = savedStateHandle.get<String>(ARG_DAY_KEY).orEmpty()

    /** 唯一时区源（E8）：与日页 / 日键写入侧同源，全 VM 只取一次。 */
    private val zone: ZoneId = ZoneId.systemDefault()
    private val date: LocalDate? = OurDayKey.parse(dayKey)

    val uiState: StateFlow<DayMomentsUiState> = run {
        val empty = DayMomentsUiState(date = date)
        if (characterUuid.isBlank() || date == null) {
            // 守卫：不建订阅，直接给一个「已加载的空状态」（E3 / E4）。
            MutableStateFlow(empty.copy(loaded = true))
        } else {
            val bounds = OurDayKey.dayBounds(dayKey, zone)
            val startMillis = bounds.first
            val endMillis = bounds.last + 1
            momentRepo.observeDayMoments(characterUuid, startMillis, endMillis)
                .map { posts ->
                    val (postedThatDay, earlier) = partitionDayMoments(posts, startMillis, endMillis)
                    DayMomentsUiState(loaded = true, date = date, postedThatDay = postedThatDay, earlier = earlier)
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), empty)
        }
    }

    val characters: StateFlow<Map<String, CharacterEntity>> =
        characterRepo.observeAll()
            .map { list -> list.associateBy { it.uuid } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val userProfile: StateFlow<UserProfileEntity?> =
        userProfileDao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleLike(postUuid: String, hasUserLike: Boolean) {
        viewModelScope.launch {
            if (hasUserLike) momentRepo.removeUserLike(postUuid) else momentRepo.addLike(postUuid, MomentAuthorType.USER, characterUuid = null)
        }
    }

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
        const val ARG_DAY_KEY = "dayKey"
    }
}
