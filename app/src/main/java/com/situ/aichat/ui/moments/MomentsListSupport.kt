package com.situ.aichat.ui.moments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppMomentIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import kotlinx.coroutines.delay

/**
 * 屏上「用户点过赞没有」（琉璃 2.0 卷六·二：列表 ×2 / 作者页 / 那天页四处同式合一·原式逐字）。
 * **与卡内判据不等价**：[MomentPostCard] 里用 `MomentAuthorType.fromRaw(..) == USER`（未知 raw 也算用户），这里只认
 * `"user"`——只搬不改，两式不合并（T1 钉住差异）。
 */
internal fun momentUserLiked(post: MomentPostWithRelations): Boolean =
    post.likes.any { it.authorTypeRaw == MomentAuthorType.USER.raw }

/** 刷新结果 → 提示语（纯函数·T1）：null = 无事可报。 */
internal fun momentsRefreshMessage(
    outcome: MomentsViewModel.RefreshOutcome?,
    newFmt: String,
    none: String,
    failed: String,
): String? = when (outcome) {
    null -> null
    is MomentsViewModel.RefreshOutcome.NewPosts -> newFmt.format(outcome.count)
    MomentsViewModel.RefreshOutcome.NoNew -> none
    MomentsViewModel.RefreshOutcome.Failed -> failed
}

/** 刷新结果提示（原 MomentsListScreen :95–108·两张脸共用）：有结果 → snackbar → 回报已消费。 */
@Composable
internal fun MomentsRefreshResultEffect(
    refreshResult: MomentsViewModel.RefreshOutcome?,
    snackbarHostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    // 刷新结果提示（M-18 + P15·P0-18）：新增 N 条 / 暂无新动态 / 刷新失败（超越 iOS 的失败态）。
    val refreshNewFmt = stringResource(R.string.moment_refresh_new)
    val refreshNoneText = stringResource(R.string.moment_refresh_none)
    val refreshFailedText = stringResource(R.string.moment_refresh_failed)
    LaunchedEffect(refreshResult) {
        val msg = momentsRefreshMessage(refreshResult, refreshNewFmt, refreshNoneText, refreshFailedText) ?: return@LaunchedEffect
        snackbarHostState.showMessage(msg)
        onConsumed()
    }
}

/**
 * 窗口分页两段（原 MomentsListScreen :110–132 / MomentAuthorScreen :76–97 两份逐字同·合一）。
 * [hasMoreOlder] 经 [rememberUpdatedState] 进 derivedState——直接捕获形参会停在首帧值（记忆 reference-compose-stale-capture）。
 */
@Composable
internal fun MomentsWindowPagingEffects(
    listState: LazyListState,
    hasMoreOlder: Boolean,
    onLoadOlder: () -> Unit,
    onShrinkWindow: () -> Unit,
) {
    val hasMore by rememberUpdatedState(hasMoreOlder)
    val loadOlder by rememberUpdatedState(onLoadOlder)
    val shrink by rememberUpdatedState(onShrinkWindow)
    // 窗口分页（图纸 §4.2·照 ChatScreen:503-524 范式）：滑到接近列表末尾 ⇒ 窗口 +30；
    // 延迟 200ms 防快速滑动连触。朋友圈是普通列表（index 0 = 最新），故「末尾」= 最旧那头。
    val shouldLoadOlder by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            shouldLoadOlderPosts(layout.visibleItemsInfo.lastOrNull()?.index, layout.totalItemsCount, hasMore)
        }
    }
    LaunchedEffect(shouldLoadOlder) {
        if (shouldLoadOlder) {
            delay(200)
            loadOlder()
        }
    }
    // 回到顶部（= 最新那头）停 5s ⇒ 缩窗回 30 释放历史（1:1 ChatScreen 的近底缩减；中途离开则取消，不缩）。
    val isNearTop by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(isNearTop) {
        if (isNearTop) {
            delay(5_000)
            shrink()
        }
    }
}

/** 仿微信朋友圈通知入口（契约 §2.2 换皮）：卡皮横条 + 自绘铃铛（深陶）+ "N 条新消息" + chevron。点击进通知列表（7.2.8）。 */
@Composable
internal fun NotificationBanner(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    surface: Modifier = Modifier.appCardSurface(),
) {
    val colors = AppTheme.colors
    Row(
        // appCardSurface 收尾自带 clip → clickable 排其后，ripple 吃圆角。
        modifier = modifier
            .fillMaxWidth()
            .then(surface)
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
            AppActionIcons.ChevronRight,
            contentDescription = null,
            tint = colors.text.tertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun MomentsEmptyState(action: @Composable () -> Unit) {
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
        action()
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
