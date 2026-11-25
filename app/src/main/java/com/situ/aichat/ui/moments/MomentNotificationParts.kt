package com.situ.aichat.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentNotificationEntity
import com.situ.aichat.data.model.MomentNotificationType
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppMomentIcons
import com.situ.aichat.ui.designsystem.AppTheme
import kotlinx.coroutines.launch

/** 点开一条通知（原 MomentNotificationListScreen :151–158 逐式·两张脸共用）：标已读 + 定位帖子；帖已删 → snackbar。 */
@Composable
internal fun rememberMomentNotificationOpener(
    viewModel: MomentNotificationViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenPost: (String) -> Unit,
): (MomentNotificationEntity) -> Unit {
    val scope = rememberCoroutineScope()
    val deletedText = stringResource(R.string.moment_detail_deleted)
    val currentOnOpenPost by rememberUpdatedState(onOpenPost)
    return remember(viewModel, snackbarHostState, deletedText) {
        { notification ->
            viewModel.openNotification(notification) { uuid ->
                if (uuid != null) {
                    currentOnOpenPost(uuid)
                } else {
                    scope.launch { snackbarHostState.showSnackbar(deletedText) }
                }
            }
        }
    }
}

/** 左滑标记已读的滑动态（原 :127–140 逐字·含弃用说明）：只认从右往左 → [onMarkRead] 并让行滑走（读后从未读流消失）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberMomentMarkReadSwipeState(onMarkRead: () -> Unit): SwipeToDismissBoxState {
    // confirmValueChange 在 material3（compose BOM 2026.06）被弃用且官方未给替代 API。
    // 此处沿用以保持「左滑标记已读」的现有手感字节级不变（铁律：本次升级不改行为）；
    // 迁移到新 anchor 模式留待专门的 UI 走查再议。
    @Suppress("DEPRECATION")
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onMarkRead()
                true
            } else {
                false
            }
        },
    )
    return state
}

@Composable
internal fun MomentMarkReadBackground() {
    // 滑动已读揭示底（契约 §2.6）：陶土软容器 + 同族深字（完成语义走品牌而非 M3 primaryContainer）。
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.accent.container)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppActionIcons.Check, contentDescription = null, tint = colors.accent.onContainer, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.moment_notif_mark_read), style = MaterialTheme.typography.labelLarge, color = colors.accent.onContainer)
    }
}

@Composable
internal fun MomentNotificationRow(
    notification: MomentNotificationEntity,
    character: CharacterEntity?,
    aiLabel: String,
    timeText: String,
    onClick: () -> Unit,
    surface: Modifier = Modifier.background(AppTheme.colors.surface.base),
) {
    val name = character?.name ?: aiLabel
    val colors = AppTheme.colors
    Row(
        // 「透明行」（D6 拍板）：视觉上与页底一体——但必须画不透明 base 底，否则滑动揭示底会从行身透出。
        modifier = Modifier
            .fillMaxWidth()
            .then(surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CharacterAvatar(name = name, avatarPath = character?.avatarPath, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(
                momentNotificationTitle(MomentNotificationType.fromRaw(notification.typeRaw), name),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text.primary,
                maxLines = 2,
            )
            if (notification.contentPreview.isNotEmpty()) {
                Text(
                    notification.contentPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text.secondary,
                    maxLines = 2,
                )
            }
            Text(timeText, style = MaterialTheme.typography.labelSmall, color = colors.text.secondary)
        }
        Icon(
            AppActionIcons.ChevronRight,
            contentDescription = null,
            tint = colors.text.tertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 通知描述（对齐 iOS `notificationTitle` 4 类）。 */
@Composable
internal fun momentNotificationTitle(type: MomentNotificationType, name: String): String = when (type) {
    MomentNotificationType.COMMENT_ON_USER_POST -> stringResource(R.string.moment_notif_title_comment, name)
    MomentNotificationType.REPLY_TO_USER_COMMENT -> stringResource(R.string.moment_notif_title_reply, name)
    MomentNotificationType.LIKE_ON_USER_POST -> stringResource(R.string.moment_notif_title_like, name)
    MomentNotificationType.CO_LIKE -> stringResource(R.string.moment_notif_title_colike, name)
}

/** 通知空态（原 :104–122 的 `Column` 子项逐字）：铃铛 + 两句。[modifier] 由调用方给（暖陶带底色与 grain）。 */
@Composable
internal fun MomentNotificationsEmptyState(modifier: Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 空态去 M3 内置图标（契约 §2.6）：自绘铃铛，tertiary 装饰档。
        Icon(AppMomentIcons.Bell, contentDescription = null, modifier = Modifier.size(48.dp), tint = colors.text.tertiary)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.moment_notif_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.text.primary)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.moment_notif_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text.secondary,
            textAlign = TextAlign.Center,
        )
    }
}
