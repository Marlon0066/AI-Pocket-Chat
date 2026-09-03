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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 角色 / 用户动态页（M06 7.2.8，对齐 iOS `CharacterMomentsView` / `UserMomentsView`）共用 VM。路由参数
 * `characterUuid` 非空 = 角色模式（该角色发的帖），空 = 用户模式（我发的帖）；两者近乎同构，合一 VM。
 * 卡片点赞按钮经 [toggleLike] 写库（与主信息流一致）。
 */
@HiltViewModel
class MomentAuthorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val momentRepo: MomentRepository,
    characterRepo: CharacterRepository,
    userProfileDao: UserProfileDao,
) : ViewModel() {

    val characterUuid: String? = savedStateHandle.get<String>(ARG_CHARACTER_UUID)?.takeIf { it.isNotEmpty() }
    val isUserMode: Boolean = characterUuid == null

    /**
     * 显示窗口大小（图纸 2026-09-03-作者动态页窗口分页 §3.3·照 `MomentsViewModel.loadedPostCount` 范式）：
     * 滑到接近列表末尾 [loadOlderPosts] +30；回到顶部停 5s [shrinkWindow] 缩回 30。
     * 取代此前「一次性装该作者全部帖子（连同全部评论点赞）、没有上限」。
     */
    private val loadedPostCount = MutableStateFlow(AUTHOR_WINDOW_INITIAL)

    @OptIn(ExperimentalCoroutinesApi::class)
    val posts: StateFlow<List<MomentPostWithRelations>> =
        loadedPostCount
            .flatMapLatest { limit ->
                if (characterUuid != null) momentRepo.observeCharacterFeed(characterUuid, limit) else momentRepo.observeUserFeed(limit)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 首屏是否已从 DB 返回（**复核 R1 🟡-1 追加**）：区分「加载中」与「真·空作者」。
     * 计数流（单表 COUNT）恒比列表流（三表关联）先到——实测帧序列 `(0,空) → (45,空) → (45,有)`——
     * 不设此闸就会有一帧「45 条动态」压着空态「还没有动态」。首次 emit 即恒 true。
     * 独立订阅同一窗口查询（Room 复用同查询、开销可忽略），照 `ChatViewModel.messagesLoaded` /
     * `DayMomentsUiState.loaded` 先例。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val loaded: StateFlow<Boolean> =
        loadedPostCount
            .flatMapLatest { limit ->
                if (characterUuid != null) momentRepo.observeCharacterFeed(characterUuid, limit) else momentRepo.observeUserFeed(limit)
            }
            .map { true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 头部「N 条动态」的**真实总数**（图纸 §3.3·K1）：走 COUNT 查询、**不从 [posts] 的长度推导**——
     * 窗口分页后 `posts.size` 是窗口条数，拿它当总数会在屏上显示成「30 条动态」。
     */
    val totalCount: StateFlow<Int> =
        (if (characterUuid != null) momentRepo.observeCharacterFeedCount(characterUuid) else momentRepo.observeUserFeedCount())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 是否还有更早的可加载（窗口已装满 ⇒ 库里可能还有更老的）。照信息流 `size >= limit` 口径。 */
    val hasMoreOlderPosts: StateFlow<Boolean> =
        combine(posts, loadedPostCount) { list, limit -> list.size >= limit }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 滑到接近列表末尾：窗口 +30。 */
    fun loadOlderPosts() {
        loadedPostCount.update { it + AUTHOR_WINDOW_PAGE }
    }

    /** 回到列表顶部停留 5s 后：窗口缩回初始 30，释放翻出来的历史（幂等）。 */
    fun shrinkWindow() {
        loadedPostCount.update { if (it > AUTHOR_WINDOW_INITIAL) AUTHOR_WINDOW_INITIAL else it }
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

        /** 作者动态页显示窗口初始 / 增量（图纸 §3.3·与信息流同值 30/30，但各屏自持·K4）。 */
        private const val AUTHOR_WINDOW_INITIAL = 30
        private const val AUTHOR_WINDOW_PAGE = 30
    }
}
