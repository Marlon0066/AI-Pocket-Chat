package com.situ.aichat.ui.moments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppMomentIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.grainSurface
import kotlinx.coroutines.delay

/**
 * 角色 / 用户动态页（M06 7.2.8，对齐 iOS `CharacterMomentsView` / `UserMomentsView`）：封面头（头像 + 名 +
 * 动态数）+ 该作者的帖子列表（复用 [MomentPostCard]，点开进详情）。模式由 [MomentAuthorViewModel] 路由参数决定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentAuthorScreen(
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    viewModel: MomentAuthorViewModel = hiltViewModel(),
) {
    val posts by viewModel.posts.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()

    val meLabel = stringResource(R.string.moment_author_me)
    val aiLabel = stringResource(R.string.moment_author_ai)
    val isUser = viewModel.isUserMode
    val character = if (!isUser) characters[viewModel.characterUuid] else null
    val headerName = if (isUser) (userProfile?.nickname?.ifBlank { null } ?: meLabel) else (character?.name ?: aiLabel)
    val headerAvatar = if (isUser) userProfile?.avatarPath else character?.avatarPath
    val title = if (isUser) stringResource(R.string.moment_user_moments_title) else headerName
    val cardUserName = userProfile?.nickname.orEmpty()
    val cardUserAvatar = userProfile?.avatarPath

    val listState = rememberLazyListState()

    // 窗口分页（图纸 §4.2·照 MomentsListScreen 同款）：滑到接近列表末尾 ⇒ 窗口 +30；延迟 200ms 防快速滑动连触。
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
    // 回到顶部停 5s ⇒ 缩窗回 30 释放历史（中途离开则取消，不缩）。
    val isNearTop by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(isNearTop) {
        if (isNearTop) {
            delay(5_000)
            viewModel.shrinkWindow()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = title,
                onBack = onBack,
                lifted = listState.canScrollBackward,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            // 页底 = surface.base + 纸感 grain（契约 §2.3）。
            modifier = Modifier.fillMaxSize().padding(padding).background(AppTheme.colors.surface.base).grainSurface(),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                AuthorHeader(name = headerName, avatarPath = headerAvatar, postCount = totalCount)
            }
            // 复核 R1 🟡-1：首帧未从 DB 返回前留白——否则计数流先到会出现「N 条动态」压着「还没有动态」。
            if (loaded && posts.isEmpty()) {
                item(key = "empty") { AuthorEmptyState(isUser) }
            } else {
                items(posts, key = { it.post.uuid }) { post ->
                    MomentPostCard(
                        post = post,
                        characterDict = characters,
                        userName = cardUserName,
                        userAvatarPath = cardUserAvatar,
                        onToggleLike = {
                            val hasUserLike = post.likes.any { it.authorTypeRaw == MomentAuthorType.USER.raw }
                            viewModel.toggleLike(post.post.uuid, hasUserLike)
                        },
                        modifier = Modifier.padding(horizontal = 20.dp) // v2 军规：屏 gutter 恒 20
                            .clickable(onClickLabel = stringResource(R.string.a11y_moment_open_post)) { onOpenPost(post.post.uuid) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthorHeader(name: String, avatarPath: String?, postCount: Int) {
    // 静默头（契约 §2.3·D1 拍板 2026-07-13）：原 120dp 渐变染色 banner 整体删除（连同硬编码橙/蓝与彩色投影），
    // 头像+文字直接坐在 base+grain 上；器物感 = 白瓷描边圈——68dp appCardSurface 圆环（raised 白底 + rest 软影
    // + 发丝线）内衬 64dp 头像，即 2dp 白瓷边（Hub 头像墙同笔法·升软影版）。用户页与角色页同一规格。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(68.dp).appCardSurface(cornerRadius = 34.dp),
            contentAlignment = Alignment.Center,
        ) {
            CharacterAvatar(name = name, avatarPath = avatarPath, size = 64.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.text.primary)
            Text(
                stringResource(R.string.moment_author_posts_count, postCount),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.text.secondary,
            )
        }
    }
}

@Composable
private fun AuthorEmptyState(isUser: Boolean) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 空态去裸 emoji（契约 §1-3）：自绘评论泡，tertiary 装饰档。
        Icon(AppMomentIcons.CommentBubble, contentDescription = null, tint = colors.text.tertiary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.moment_author_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.text.primary)
        Text(
            stringResource(if (isUser) R.string.moment_user_empty_desc else R.string.moment_character_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text.secondary,
            textAlign = TextAlign.Center,
        )
    }
}
