package com.situ.aichat.ui.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.moments.MomentGenerationService
import com.situ.aichat.moments.MomentInteractionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * 朋友圈信息流（M06 7.2.7，对齐 iOS `FriendCircleView`）的 ViewModel。响应式观察 feed（滑动窗口·非软删，
 * 新→旧）/ 角色字典 / 用户资料 / 未读通知数；下拉刷新触发 [MomentGenerationService.checkAndGeneratePosts]
 * 并按发帖数差报告「N 条新动态」（对齐 iOS 刷新前后比对）。点赞/删除写库在此（界面经回调上抛）。
 *
 * **窗口分页**（图纸 2026-09-03-朋友圈信息流窗口分页·D-1）：feed 走滑动窗口（30 起步 / 每次 +30 /
 * 回顶停 5s 缩回 30），取代此前「一次性装最近 200 条、没有下文」；不上 Paging3——照聊天屏手写窗口
 * 范式（`ChatViewModel` 同款三件套），零新依赖，理由见图纸 §0。
 */
@HiltViewModel
class MomentsViewModel @Inject constructor(
    private val momentRepo: MomentRepository,
    characterRepo: CharacterRepository,
    userProfileDao: UserProfileDao,
    private val generationService: MomentGenerationService,
    private val interactionService: MomentInteractionService,
) : ViewModel() {

    /**
     * 显示窗口大小（图纸 2026-09-03-朋友圈信息流窗口分页 §3.1·照 `ChatViewModel.loadedMessageCount` 范式）：
     * 滑到接近列表末尾 [loadOlderPosts] +30；回到顶部停 5s [shrinkWindow] 缩回 30。
     * 取代此前「一次性装最近 200 条、没有下文」——正常动态永不删，200 条的窗口只会越来越近视。
     */
    private val loadedPostCount = MutableStateFlow(MOMENTS_WINDOW_INITIAL)

    @OptIn(ExperimentalCoroutinesApi::class)
    val feed: StateFlow<List<MomentPostWithRelations>> =
        loadedPostCount
            .flatMapLatest { limit -> momentRepo.observeFeed(limit) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 是否还有更早的动态可加载（窗口已装满 ⇒ 库里可能还有更老的）。照 `ChatViewModel.hasMoreOlderMessages`
     * 的 `size >= limit` 口径（K3）：库里恰好等于窗口大小时会多跑一次空续查询，换免掉一条 COUNT 查询。
     */
    val hasMoreOlderPosts: StateFlow<Boolean> =
        combine(feed, loadedPostCount) { posts, limit -> posts.size >= limit }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 滑到接近列表末尾：窗口 +30。 */
    fun loadOlderPosts() {
        loadedPostCount.update { it + MOMENTS_WINDOW_PAGE }
    }

    /** 回到列表顶部停留 5s 后：窗口缩回初始 30，释放翻出来的历史（幂等·K5）。 */
    fun shrinkWindow() {
        loadedPostCount.update { if (it > MOMENTS_WINDOW_INITIAL) MOMENTS_WINDOW_INITIAL else it }
    }

    val characters: StateFlow<Map<String, CharacterEntity>> =
        characterRepo.observeAll()
            .map { list -> list.associateBy { it.uuid } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val userProfile: StateFlow<UserProfileEntity?> =
        userProfileDao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val unreadNotificationCount: StateFlow<Int> =
        momentRepo.observeUnreadNotificationCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * 下拉刷新结果（null = 尚无结果待展示）。UI 消费后调 [consumeRefreshResult]。
     * **超越 iOS**：iOS `triggerAIGenerationCheck` 只按帖数差报「N 条新动态/暂无新动态」、从不报失败（FriendCircleView.swift:277-300）；
     * 安卓多一个 [RefreshOutcome.Failed] 失败态，弱网/超时不再误报「暂无新动态」。
     */
    sealed interface RefreshOutcome {
        data class NewPosts(val count: Int) : RefreshOutcome
        data object NoNew : RefreshOutcome
        data object Failed : RefreshOutcome
    }

    private val _refreshResult = MutableStateFlow<RefreshOutcome?>(null)
    val refreshResult: StateFlow<RefreshOutcome?> = _refreshResult.asStateFlow()

    /** 下拉刷新：触发 AI 发帖检查（对齐 iOS `triggerAIGenerationCheck`），比对前后非软删帖数报告新增；超时则报失败。 */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val before = momentRepo.feedCount()
                // checkAndGeneratePosts 自身吞掉所有异常（设计如此），唯一可得的失败信号是「卡过 30s 超时」。
                val timedOut = withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                    runCatching { generationService.checkAndGeneratePosts() }
                } == null
                val after = momentRepo.feedCount()
                val delta = (after - before).coerceAtLeast(0)
                _refreshResult.value = when {
                    delta > 0 -> RefreshOutcome.NewPosts(delta) // 出帖了就算成功，哪怕跑得久
                    timedOut -> RefreshOutcome.Failed
                    else -> RefreshOutcome.NoNew
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun consumeRefreshResult() {
        _refreshResult.value = null
    }

    /** 点赞/取消点赞（对齐 iOS `toggleLike`）：用户已赞则删其赞，否则插一条用户赞。 */
    fun toggleLike(postUuid: String, hasUserLike: Boolean) {
        viewModelScope.launch {
            if (hasUserLike) {
                momentRepo.removeUserLike(postUuid)
            } else {
                momentRepo.addLike(postUuid, MomentAuthorType.USER, characterUuid = null)
            }
        }
    }

    /** 删帖（对齐 iOS confirmationDialog 的删除）：软删 + 取消该帖在途的延迟互动任务。 */
    fun delete(postUuid: String) {
        viewModelScope.launch {
            momentRepo.softDelete(postUuid)
            interactionService.cancelPendingInteractions(postUuid)
        }
    }

    private companion object {
        const val REFRESH_TIMEOUT_MS = 30_000L

        /** 信息流显示窗口初始 / 增量大小（图纸 §3.1·D-1 拍板 30/30·聊天屏是 50/50，朋友圈卡片更重故取 30）。 */
        const val MOMENTS_WINDOW_INITIAL = 30
        const val MOMENTS_WINDOW_PAGE = 30
    }
}
