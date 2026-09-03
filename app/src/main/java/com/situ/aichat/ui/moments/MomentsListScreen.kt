package com.situ.aichat.ui.moments

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppMomentIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.grainSurface
import kotlinx.coroutines.delay

/**
 * 朋友圈信息流（M06 7.2.7，对齐 iOS `FriendCircleView`）：下拉刷新触发 AI 发帖检查、未读通知 banner、发布 FAB、
 * 帖子卡列表（点开详情 7.2.8 / 长按点赞·删除）、空状态。
 *
 * [onOpenPost]→帖子详情（7.2.8 接线，现路由占位）。[onOpenNotifications]→通知列表（7.2.8）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MomentsListScreen(
    onBack: () -> Unit,
    onCompose: () -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenCharacterMoments: (String) -> Unit,
    viewModel: MomentsViewModel = hiltViewModel(),
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadNotificationCount.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val refreshResult by viewModel.refreshResult.collectAsStateWithLifecycle()

    var deleteTarget by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // 刷新结果提示（M-18 + P15·P0-18）：新增 N 条 / 暂无新动态 / 刷新失败（超越 iOS 的失败态）。
    val refreshNewFmt = stringResource(R.string.moment_refresh_new)
    val refreshNoneText = stringResource(R.string.moment_refresh_none)
    val refreshFailedText = stringResource(R.string.moment_refresh_failed)
    LaunchedEffect(refreshResult) {
        val msg = when (val r = refreshResult) {
            null -> return@LaunchedEffect
            is MomentsViewModel.RefreshOutcome.NewPosts -> refreshNewFmt.format(r.count)
            MomentsViewModel.RefreshOutcome.NoNew -> refreshNoneText
            MomentsViewModel.RefreshOutcome.Failed -> refreshFailedText
        }
        snackbarHostState.showMessage(msg)
        viewModel.consumeRefreshResult()
    }

    // 窗口分页（图纸 §4.2·照 ChatScreen:503-524 范式）：滑到接近列表末尾 ⇒ 窗口 +30；
    // 延迟 200ms 防快速滑动连触。朋友圈是普通列表（index 0 = 最新），故「末尾」= 最旧那头。
    val hasMoreOlder by viewModel.hasMoreOlderPosts.collectAsStateWithLifecycle()
    val shouldLoadOlder by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            shouldLoadOlderPosts(layout.visibleItemsInfo.lastOrNull()?.index, layout.totalItemsCount, hasMoreOlder)
        }
    }
    LaunchedEffect(shouldLoadOlder) {
        if (shouldLoadOlder) {
            delay(200)
            viewModel.loadOlderPosts()
        }
    }
    // 回到顶部（= 最新那头）停 5s ⇒ 缩窗回 30 释放历史（1:1 ChatScreen 的近底缩减；中途离开则取消，不缩）。
    val isNearTop by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(isNearTop) {
        if (isNearTop) {
            delay(5_000)
            viewModel.shrinkWindow()
        }
    }

    val userName = userProfile?.nickname.orEmpty()
    val userAvatarPath = userProfile?.avatarPath

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.moment_nav_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            // 深陶羽毛笔 FAB（契约 §2.2·D2 拍板）：56dp 圆 + deepStart→deepEnd 135° 双 stop（同旧 Hero/深档气泡族）
            // + raised 双层软影 + 按压 clickableScale（calm）+ 轻触觉；56dp ≥ 48dp a11y 触达。
            val colors = AppTheme.colors
            val haptics = LocalAppHaptics.current
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .appCardSurface(
                        raised = true,
                        cornerRadius = 28.dp,
                        background = Brush.linearGradient(listOf(colors.accent.deepStart, colors.accent.deepEnd)),
                    )
                    .clickableScale { haptics.light(); onCompose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppMomentIcons.QuillBold,
                    contentDescription = stringResource(R.string.moment_publish),
                    tint = colors.accent.onDeep,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refresh,
            // 页底 = surface.base + 纸感 grain（契约 §2.2·v2 质感层）。
            modifier = Modifier.fillMaxSize().padding(padding).background(AppTheme.colors.surface.base).grainSurface(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (feed.isEmpty()) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            MomentsEmptyState(onCompose)
                        }
                    }
                } else {
                    if (unreadCount > 0) {
                        item(key = "notif-banner") {
                            // moments-ui-6：通知 banner 插入/消失带滑入淡入（= iOS .move(.top)+.opacity，spring）
                            NotificationBanner(
                                count = unreadCount,
                                onClick = onOpenNotifications,
                                modifier = Modifier.animateItem().padding(horizontal = 20.dp), // v2 军规：屏 gutter 恒 20
                            )
                        }
                    }
                    items(feed, key = { it.post.uuid }) { post ->
                        // moments-ui-6：帖子卡出现/重排带淡入+弹性位移（= iOS 滚入淡入的安卓地道等价；
                        // animateItem 不复刻 iOS 连续滚动驱动的 scale，按 LazyColumn 习惯只做出现/位移动画）
                        MomentFeedItem(
                            post = post,
                            characterDict = characters,
                            userName = userName,
                            userAvatarPath = userAvatarPath,
                            onOpenPost = { onOpenPost(post.post.uuid) },
                            onToggleLike = {
                                val hasUserLike = post.likes.any { it.authorTypeRaw == MomentAuthorType.USER.raw }
                                viewModel.toggleLike(post.post.uuid, hasUserLike)
                            },
                            onRequestDelete = { deleteTarget = post.post.uuid },
                            onCharacterTap = onOpenCharacterMoments,
                            modifier = Modifier.animateItem().padding(horizontal = 20.dp), // v2 军规：屏 gutter 恒 20
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { uuid ->
        AppDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.moment_delete_title),
            body = stringResource(R.string.moment_delete_message),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { viewModel.delete(uuid); deleteTarget = null },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { deleteTarget = null },
        )
    }
}

/** 一条 feed 项：卡片 + 长按菜单（点赞/取消赞、删除）。点开卡片进详情。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MomentFeedItem(
    post: MomentPostWithRelations,
    characterDict: Map<String, CharacterEntity>,
    userName: String,
    userAvatarPath: String?,
    onOpenPost: () -> Unit,
    onToggleLike: () -> Unit,
    onRequestDelete: () -> Unit,
    onCharacterTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hasUserLike = post.likes.any { it.authorTypeRaw == MomentAuthorType.USER.raw }
    Box(
        // P1-21：动作标签（combinedClickable 自带 mergeDescendants，整卡已是单焦点节点=iOS 消费方 Button 包卡；
        // 卡内绝不再加 semantics(mergeDescendants)——嵌套双合并劣化 TalkBack）。
        modifier = modifier.combinedClickable(
            onClickLabel = stringResource(R.string.a11y_moment_open_post),
            onClick = onOpenPost,
            onLongClickLabel = stringResource(R.string.a11y_moment_post_actions),
            onLongClick = { menuExpanded = true },
        ),
    ) {
        MomentPostCard(
            post = post,
            characterDict = characterDict,
            userName = userName,
            userAvatarPath = userAvatarPath,
            onToggleLike = onToggleLike,
            onCharacterTap = onCharacterTap,
        )
        AppMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }) {
            AppMenuItem(
                text = stringResource(if (hasUserLike) R.string.moment_unlike else R.string.moment_like),
                onClick = { onToggleLike(); menuExpanded = false },
            )
            AppMenuItem(
                text = stringResource(R.string.moment_menu_delete),
                onClick = { onRequestDelete(); menuExpanded = false },
                danger = true,
            )
        }
    }
}

/** 仿微信朋友圈通知入口（契约 §2.2 换皮）：卡皮横条 + 自绘铃铛（深陶）+ "N 条新消息" + chevron。点击进通知列表（7.2.8）。 */
@Composable
private fun NotificationBanner(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Row(
        // appCardSurface 收尾自带 clip → clickable 排其后，ripple 吃圆角。
        modifier = modifier
            .fillMaxWidth()
            .appCardSurface()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            AppMomentIcons.Bell,
            contentDescription = null,
            tint = colors.accent.text,
            modifier = Modifier.size(20.dp),
        )
        Text(
            stringResource(R.string.moment_new_messages, count),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.text.tertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun MomentsEmptyState(onCompose: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 空态去裸 emoji（契约 §1-3·N4 先例）：自绘评论泡大图标，tertiary 装饰档。
        Icon(AppMomentIcons.CommentBubble, contentDescription = null, tint = colors.text.tertiary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.moment_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.text.primary)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.moment_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text.secondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        AppButton(onClick = onCompose, style = AppButtonStyle.Primary) { Text(stringResource(R.string.moment_empty_action)) }
    }
}

private suspend fun SnackbarHostState.showMessage(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message)
}

/**
 * 扩窗判据（图纸 §3.2·K4）：还有更早的 ∧ 最后一个可见项已进入列表末尾 4 项之内 ⇒ 该续了。
 * 抽成纯函数是为了可测（聊天屏同款判据写在屏里、无覆盖）。[lastVisibleIndex] 为 null = 一项都没渲染。
 */
internal fun shouldLoadOlderPosts(lastVisibleIndex: Int?, totalItemsCount: Int, hasMoreOlder: Boolean): Boolean =
    hasMoreOlder && lastVisibleIndex != null && lastVisibleIndex >= totalItemsCount - 4
