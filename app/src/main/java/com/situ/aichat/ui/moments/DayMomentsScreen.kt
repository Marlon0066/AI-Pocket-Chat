package com.situ.aichat.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.ui.designsystem.AppMomentIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.grainSurface
import com.situ.aichat.ui.ourdays.OurDaysFormat

/**
 * 「{日期} · 朋友圈」（图纸 2026-09-03 §4）：「我们的日子」日页事实层「看动态 ›」的落点——只装这一天涉及的
 * 动态，分「这一天发的」/「更早发的 · 这一天有来往」两组。骨架照 [MomentAuthorScreen] 范式；卡片复用
 * [MomentPostCard]（零改）。
 *
 * 本页有意不做（§0.3-5）：下拉刷新、发布 FAB、长按菜单、`onCharacterTap`、`animateItem()`、顶栏 actions。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayMomentsScreen(
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    viewModel: DayMomentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()

    val date = state.date
    val title = if (date != null) {
        stringResource(R.string.moment_day_title, OurDaysFormat.date(date, stringResource(R.string.our_days_fmt_md)))
    } else {
        stringResource(R.string.tab_moments)
    }
    val cardUserName = userProfile?.nickname.orEmpty()
    val cardUserAvatar = userProfile?.avatarPath

    val listState = rememberLazyListState()
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
            // 页底 = surface.base + 纸感 grain（同范式页 MomentAuthorScreen）。
            modifier = Modifier.fillMaxSize().padding(padding).background(AppTheme.colors.surface.base).grainSurface(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 组为空 ⇒ 标签与该组整体都不发射（不留空壳·E5 / E6）。
            if (state.postedThatDay.isNotEmpty()) {
                item(key = "label-posted") { GroupLabel(stringResource(R.string.moment_day_group_posted)) }
                postCards(state.postedThatDay, characters, cardUserName, cardUserAvatar, viewModel, onOpenPost)
            }
            if (state.earlier.isNotEmpty()) {
                item(key = "label-earlier") { GroupLabel(stringResource(R.string.moment_day_group_earlier)) }
                postCards(state.earlier, characters, cardUserName, cardUserAvatar, viewModel, onOpenPost)
            }
            // 首帧未从 DB 返回前留白，不画空态（J6）。
            if (state.loaded && state.postedThatDay.isEmpty() && state.earlier.isEmpty()) {
                item(key = "empty") { DayMomentsEmptyState() }
            }
        }
    }
}

/** 分节标签（§4.3·样式逐字取自日页「这一天」F18）。 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = AppTypography.caption.copy(fontSize = 12.sp, letterSpacing = 1.5.sp),
        color = AppTheme.colors.text.tertiary,
        // 22 = 卡片 gutter 20 + 内缩 2（与日页「这一天」标签的 start = 2 内缩同口径）。
        modifier = Modifier.padding(start = 22.dp, end = 20.dp, top = 6.dp),
    )
}

/** 帖子卡列表（§4.4·复用 [MomentPostCard] 零改：不传 onCharacterTap、不加 animateItem / 长按分支）。 */
private fun LazyListScope.postCards(
    list: List<MomentPostWithRelations>,
    characters: Map<String, CharacterEntity>,
    cardUserName: String,
    cardUserAvatar: String?,
    viewModel: DayMomentsViewModel,
    onOpenPost: (String) -> Unit,
) {
    items(list, key = { it.post.uuid }) { post ->
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

/** 空态（§4.5·结构照 `MomentAuthorScreen` 的 AuthorEmptyState 逐项）。 */
@Composable
private fun DayMomentsEmptyState() {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AppMomentIcons.CommentBubble, contentDescription = null, tint = colors.text.tertiary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.moment_day_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.text.primary)
        Text(
            stringResource(R.string.moment_day_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text.secondary,
            textAlign = TextAlign.Center,
        )
    }
}
